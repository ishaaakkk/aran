package edu.upc.dtak.aran.operators.bootstrapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a Singer tap and turns its record stream into a parquet file.
 *
 * <h2>Why this is not a SourceConnector</h2>
 *
 * <p>{@code SourceConnector.introspect} must describe a source without moving its rows — it
 * returns a schema and a binding, and the query engine reaches the data later. A Singer tap cannot
 * satisfy that: the protocol has no "describe without reading" mode that yields something Aran can
 * query, and a tap's whole purpose is to emit records. So taps plug in one layer down. A tap runs,
 * its records land in the lake as parquet, and {@code ParquetConnector} introspects the result
 * through the path that already exists.
 *
 * <p>That is the trade, and it should be stated plainly: this buys breadth — hundreds of taps for
 * SaaS APIs nothing else here can reach — at the cost of freshness, because the result is a
 * snapshot rather than a live source. It is the right trade for a CRM or a ticketing system, and
 * the wrong one for a database, which is what the JDBC connector is for.
 *
 * <h2>Licensing</h2>
 *
 * <p>Aran ships no taps and depends on none. A tap is a command an operator configures, run as a
 * subprocess. That is deliberate: the tap ecosystems are licensed inconsistently — much of
 * Airbyte's catalogue moved to ELv2, which restricts offering the connector as part of a hosted
 * service, and would therefore be a problem for Aran specifically. Singer taps on Meltano
 * Hub are predominantly Apache-2 or MIT. Keeping taps out of the build makes that the operator's
 * choice about their own deployment rather than a licence Aran inherits on their behalf.
 *
 * <h2>This now has an API path — what holds it up</h2>
 *
 * <p>{@code command} is an argv list handed straight to {@link ProcessBuilder}. Until T6 this class
 * had no production caller at all, and the javadoc here named the two rules whoever wired one would
 * owe. Both were paid, one layer up:
 *
 * <ul>
 *   <li><b>The executable comes from an operator-configured allowlist.</b>
 *       {@code SingerTapCatalog.commandFor} builds the argv from configuration plus the config-file
 *       path Aran itself chose; a request carries only a tap <em>id</em>. Nothing a user sends
 *       reaches this list.</li>
 *   <li><b>User values are the content of a config file, never argv elements.</b> A value beginning
 *       with {@code --} is a flag to the tap however carefully it was escaped, so it never gets the
 *       chance to be one.</li>
 * </ul>
 *
 * <p>There is also no shell — the {@code List<String>} constructor never invokes one, so the
 * classic {@code sh -c} injection does not exist here and no amount of quoting in an argument can
 * create it. {@code SingerTapRunnerTest} asserts that rather than trusting this paragraph.
 *
 * <p>What the tap does <em>not</em> get is Aran's environment (IMPORTS-8): see
 * {@code INHERITED_ENV}. What is still unproven is any published tap against a real API — that is
 * V14, and it needs credentials only the owner has.
 */
@NullMarked
public final class SingerTapRunner {

    private static final Logger log = LoggerFactory.getLogger(SingerTapRunner.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectMapper mapper = MAPPER;

    /**
     * Run {@code command}, collect its records, and write them to a parquet file.
     *
     * @param command  the tap and its arguments, e.g. {@code [tap-github, --config, config.json]}
     * @param stream   which of the tap's streams to keep. A tap commonly emits several; one Aran
     *                 dataset is one relation, so the caller names the one it wants.
     * @param timeout  how long the tap may run before it is killed.
     * @return the parquet file. The caller owns it and must delete it.
     */
    public Path run(List<String> command, String stream, java.time.Duration timeout)
            throws IOException, InterruptedException {

        Path records = Files.createTempFile("aran-singer-", ".ndjson");
        Path parquet = Files.createTempFile("aran-singer-", ".parquet");
        long kept = 0;

        try {
            Process tap = startTap(command);

            java.util.concurrent.atomic.AtomicBoolean killed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            Thread watchdog = startWatchdog(tap, timeout, killed);

            try (BufferedReader out = new BufferedReader(
                        new InputStreamReader(tap.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter sink = Files.newBufferedWriter(records, StandardCharsets.UTF_8)) {

                String line;
                while ((line = out.readLine()) != null) {
                    JsonNode message = parseOrNull(line);
                    if (message == null) {
                        // Taps interleave human-readable logging with protocol messages on the
                        // same stream. A non-JSON line is normal output, not a failure.
                        continue;
                    }
                    String type = message.path("type").asText("");
                    if (!"RECORD".equals(type)) {
                        // SCHEMA, STATE, ACTIVATE_VERSION. The schema is deliberately not used:
                        // Aran re-derives the schema from the parquet through ParquetConnector, so
                        // there is exactly one place types are decided rather than two that can
                        // disagree.
                        continue;
                    }
                    if (!stream.equals(message.path("stream").asText())) {
                        continue;
                    }
                    JsonNode record = message.get("record");
                    if (record == null || record.isNull()) {
                        continue;
                    }
                    sink.write(mapper.writeValueAsString(record));
                    sink.newLine();
                    kept++;
                }
            }

            // Reading has finished, which means the tap closed its output — either because it
            // exited or because the watchdog killed it.
            tap.waitFor();
            watchdog.interrupt();

            // Checked before the exit status on purpose: a forcibly destroyed process exits
            // non-zero, and "exited with status 137" would bury the fact that it was a timeout.
            if (killed.get()) {
                throw new IOException("the tap did not finish within " + timeout);
            }
            if (tap.exitValue() != 0) {
                throw new IOException("the tap exited with status " + tap.exitValue());
            }
            if (kept == 0) {
                // An empty parquet has no columns, so it would bootstrap into a class with no
                // properties — the failure shape the conformance kit exists to prevent, arriving
                // by a different road.
                throw new IOException("the tap emitted no records for stream '" + stream + "'");
            }

            toParquet(records, parquet);
            log.info("Singer tap produced {} records for stream {}", kept, stream);
            return parquet;
        } catch (Exception e) {
            Files.deleteIfExists(parquet);
            throw e;
        } finally {
            Files.deleteIfExists(records);
        }
    }

    /**
     * The environment a tap is allowed to see (IMPORTS-8).
     *
     * <p>A tap is third-party code running with the backend's privileges, and Aran's own
     * environment holds every secret the process was started with — the lake's shared secret, the
     * database password, the credential encryption key. None of it is anything a tap needs: its
     * settings travel in a config file, by construction (see {@code SingerTapCatalog}), precisely
     * so that no value has to become an argument or a variable.
     *
     * <p>Three names survive because a program that cannot find its own interpreter, its own
     * config directory or a place to write is not a program that runs. Anything else a specific
     * tap needs is a reason to extend this list deliberately, which is the point of having one.
     */
    private static final List<String> INHERITED_ENV = List.of("PATH", "HOME", "TMPDIR");

    /**
     * Start the tap with a cleared environment and merged output — the one {@link ProcessBuilder}
     * site, so the two callers cannot drift on either property.
     */
    private static Process startTap(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        // Merged so a tap's diagnostics are not lost, and so a tap that writes a lot to stderr
        // cannot fill that pipe's buffer and deadlock waiting for someone to drain it.
        builder.redirectErrorStream(true);

        java.util.Map<String, String> env = builder.environment();
        env.clear();
        for (String name : INHERITED_ENV) {
            String value = System.getenv(name);
            if (value != null) {
                env.put(name, value);
            }
        }
        return builder.start();
    }

    /**
     * Kill {@code tap} and everything it started if it outlives {@code timeout}, recording that it
     * was killed rather than leaving the caller to infer it from an exit status.
     *
     * <p>The deadline has to be enforced <em>around the reading</em>, not after it. readLine blocks
     * until the tap closes stdout, so waiting on the process afterwards bounds nothing — a tap that
     * emits one record and then sleeps holds the calling thread for as long as it likes, which on a
     * request thread is precisely what the timeout exists to stop. This is not hypothetical: it was
     * the first version of {@link #run}, and the test for it hung for a full minute before failing.
     * {@code discoverStreams} shipped with the same bug and was fixed by sharing this method
     * (IMPORTS-7).
     *
     * <p>Descendants first, and this is the part that is easy to get wrong. destroyForcibly kills
     * only the direct child, and a tap is typically a wrapper script around a Python process — the
     * child inherits the stdout pipe, so killing the parent alone leaves the pipe open and readLine
     * blocks exactly as long as it would have without any timeout at all.
     */
    private static Thread startWatchdog(Process tap, java.time.Duration timeout,
                                        java.util.concurrent.atomic.AtomicBoolean killed) {
        return Thread.ofVirtual().start(() -> {
            try {
                if (!tap.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    killed.set(true);
                    tap.descendants().forEach(ProcessHandle::destroyForcibly);
                    tap.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * NDJSON to parquet, via DuckDB.
     *
     * <p>DuckDB infers the column types from the records, which is the same inference
     * {@code ParquetConnector} will then read back — so the types a user sees are the ones the
     * query engine will actually use, rather than a Singer JSON-Schema translated twice.
     */
    private static void toParquet(Path records, Path parquet) throws IOException {
        String in = Names.sqlLiteral(records.toAbsolutePath().toString());
        String out = Names.sqlLiteral(parquet.toAbsolutePath().toString());
        try (Connection conn = DuckDbSession.open();
             Statement stmt = conn.createStatement()) {
            stmt.execute("COPY (SELECT * FROM read_json_auto('" + in + "', format='newline_delimited'))"
                    + " TO '" + out + "' (FORMAT PARQUET)");
        } catch (Exception e) {
            throw new IOException("could not convert the tap's records to parquet: "
                    + e.getMessage(), e);
        }
    }

    private @Nullable JsonNode parseOrNull(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        try {
            return mapper.readTree(trimmed);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The streams a tap offers, from its own SCHEMA messages — for letting a user pick one.
     *
     * <p>IMPORTS-7: this runs a program, so it gets exactly the discipline {@link #run} does and
     * for the same reasons — the watchdog wraps the read loop rather than following it, descendants
     * are killed with the child, the process is reaped if the reader throws, and a non-zero exit is
     * an error. That last one is the difference a user notices: a tap that failed to authenticate
     * used to answer "no streams", sending them to look for a stream that was never missing.
     */
    public List<String> discoverStreams(List<String> command, java.time.Duration timeout)
            throws IOException, InterruptedException {
        List<String> streams = new ArrayList<>();
        Process tap = startTap(command);
        java.util.concurrent.atomic.AtomicBoolean killed =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread watchdog = startWatchdog(tap, timeout, killed);

        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(tap.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                JsonNode message = parseOrNull(line);
                if (message == null) {
                    continue;
                }
                if ("SCHEMA".equals(message.path("type").asText())) {
                    String name = message.path("stream").asText("");
                    if (!name.isEmpty() && !streams.contains(name)) {
                        streams.add(name);
                    }
                }
            }
        } catch (Exception e) {
            // Reaped here rather than left to the watchdog: the caller is walking away now, and a
            // discovery is short enough that "it dies at the deadline" would leave a live process
            // for most of the timeout with nobody reading its output.
            tap.descendants().forEach(ProcessHandle::destroyForcibly);
            tap.destroyForcibly();
            watchdog.interrupt();
            throw e;
        }

        tap.waitFor();
        watchdog.interrupt();

        // Before the exit status, for the same reason run() does it: a forcibly destroyed process
        // exits non-zero, and "exited with status 137" would bury the fact that it was a timeout.
        if (killed.get()) {
            throw new IOException("the tap did not finish within " + timeout);
        }
        if (tap.exitValue() != 0) {
            throw new IOException("the tap exited with status " + tap.exitValue());
        }
        return List.copyOf(streams);
    }
}

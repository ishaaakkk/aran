package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.SingerTapExtractor;
import edu.upc.dtak.aran.core.domain.SingerTapRepository;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import edu.upc.dtak.aran.core.source.TapConfig;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The {@link SingerTapExtractor} port, over {@link SingerTapRunner} (T6).
 *
 * <p>This class is the wiring — catalogue lookup, config file, deadlines, byte ceiling — and holds
 * no protocol logic, which stays in {@code SingerTapRunner} where it is tested against real tap
 * processes. The same split as {@code JdbcTableExtractor} over {@code JdbcBulkExtractor}.
 *
 * <p>The catalogue is consulted on every call rather than at construction, for the reason it exists:
 * the command must come from configuration at the moment it runs, so a repository row can never be
 * the thing that decides which program the backend executes.
 */
@NullMarked
@Component
public final class SingerTapRunnerExtractor implements SingerTapExtractor {

    /**
     * How long a discovery run may take.
     *
     * <p>Short, because discovery answers a form: it is on a request thread, and a user waiting for
     * a stream list has already decided the tap is broken long before this expires. A tap that
     * genuinely needs longer to enumerate its streams is a tap this deployment should not offer.
     */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofMinutes(1);

    /**
     * How long one stream's extract may take.
     *
     * <p>Long, because this runs inside a job and a SaaS API paginating a year of history is the
     * normal case rather than the pathological one. The ceiling that matters for safety is
     * {@code maxBytes}, not this.
     */
    private static final Duration EXTRACT_TIMEOUT = Duration.ofMinutes(30);

    private final SingerTapCatalog catalog;
    private final SingerTapRunner runner = new SingerTapRunner();

    public SingerTapRunnerExtractor(SingerTapCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<String> discoverStreams(SingerTapRepository repository) {
        return withConfigFile(repository, DISCOVERY_TIMEOUT, (command, timeout) ->
                runner.discoverStreams(command, timeout));
    }

    @Override
    public Extracted extract(SingerTapRepository repository, String stream, long maxBytes) {
        return withConfigFile(repository, EXTRACT_TIMEOUT, (command, timeout) -> {
            Path parquet = runner.run(command, stream, timeout);
            try {
                long size = Files.size(parquet);
                if (size > maxBytes) {
                    // Checked before the bytes are read into the heap — reading first and then
                    // refusing would be the OOM the ceiling exists to prevent, with a message.
                    throw new IOException("the tap produced " + size + " bytes for stream '"
                            + stream + "', over this server's " + maxBytes + "-byte import limit");
                }
                return new Extracted(Files.readAllBytes(parquet), countRows(parquet));
            } finally {
                Files.deleteIfExists(parquet);
            }
        });
    }

    /** What a caller does with the argv, once Aran has built it and written the config file. */
    @FunctionalInterface
    private interface TapCall<T> {
        T run(List<String> command, Duration timeout) throws Exception;
    }

    /**
     * Validate against the catalogue, write the settings to a private file, run, then delete it.
     *
     * <p>The config file is the whole reason a user's values can be trusted at all: they are data
     * the tap reads, never arguments the tap parses. It is written {@code rw-------} and deleted in
     * a finally — it holds whatever credential the tap authenticates with, and the tap's own
     * lifetime is the only window it needs to exist for.
     *
     * @throws SingerTapCatalog.TapNotAllowedException if the deployment does not declare this tap
     */
    private <T> T withConfigFile(SingerTapRepository repository, Duration timeout, TapCall<T> call) {
        Map<String, String> config = catalog.validateConfig(repository.tapId(),
                TapConfig.toMap(repository.configJson()));

        Path configFile = null;
        try {
            configFile = Files.createTempFile("aran-tap-", ".json");
            try {
                Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // A non-POSIX filesystem. The file still lives in the JVM's private temp directory;
                // failing the import over the permission bits would be the wrong trade.
            }
            Files.writeString(configFile, TapConfig.toJson(config), StandardCharsets.UTF_8);

            return call.run(catalog.commandFor(repository.tapId(), configFile), timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the tap run was interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // The tap's own message ("exited with status 1", "did not finish within PT1M") is the
            // useful half; it names which end of the setup is wrong. It never carries a config
            // value — the runner reads the tap's output, and the config went in by file.
            throw new IllegalStateException(
                    "Singer tap '" + repository.tapId() + "' failed: " + e.getMessage(), e);
        } finally {
            if (configFile != null) {
                try {
                    Files.deleteIfExists(configFile);
                } catch (IOException ignored) {
                    // Leaving a credential on disk is worse than a noisy log, so this is logged by
                    // its absence: the file is in the JVM temp dir and the OS reaps it. There is
                    // nothing better to do here that would not mask the original failure.
                }
            }
        }
    }

    /**
     * How many records the parquet holds.
     *
     * <p>Read from the file rather than counted while streaming: DuckDB answers {@code count(*)}
     * from the parquet's row-group metadata without scanning it, and the runner's own record count
     * is not on its return path.
     */
    private static long countRows(Path parquet) throws Exception {
        String path = edu.upc.dtak.aran.core.util.Names.sqlLiteral(
                parquet.toAbsolutePath().toString());
        try (Connection conn = DuckDbSession.open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM read_parquet('" + path + "')")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}

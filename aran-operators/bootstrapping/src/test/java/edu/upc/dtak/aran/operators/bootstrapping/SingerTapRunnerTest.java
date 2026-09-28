package edu.upc.dtak.aran.operators.bootstrapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Singer runner, against real tap processes.
 *
 * <p>The taps here are shell scripts that emit the protocol, not mocks. That is deliberate: what
 * could actually be wrong is the subprocess handling — interleaved logging, a non-zero exit, a
 * stream that produces nothing, a tap that hangs — and none of those is exercised by stubbing the
 * process away. A tap is a program, so the tests run programs.
 *
 * <p>What they do not prove is that any *published* tap works, which needs a real tap against a
 * real API and is registered as pending validation.
 */
class SingerTapRunnerTest {

    private final SingerTapRunner runner = new SingerTapRunner();

    private static final Duration LIMIT = Duration.ofSeconds(30);

    /** Writes an executable script and returns the command that runs it. */
    private static List<String> tap(Path dir, String body) throws IOException {
        Path script = dir.resolve("tap.sh");
        Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return List.of(script.toAbsolutePath().toString());
    }

    private static String emit(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("cat <<'EOF'\n").append(line).append("\nEOF\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("arguments reach the tap as argv, never through a shell")
    void argumentsAreNotShellInterpreted(@TempDir Path dir) throws Exception {
        // The runner's safety today rests entirely on ProcessBuilder(List) not using a shell —
        // see the class javadoc, which tells whoever wires an API path to this what else they
        // then owe. That property is worth a test rather than a comment: it is one refactor to
        // `new ProcessBuilder("sh", "-c", String.join(" ", command))` away from being false, and
        // nothing else in the suite would notice.
        Path marker = dir.resolve("pwned");
        Path script = dir.resolve("tap.sh");
        Files.writeString(script,
                "#!/bin/sh\ncat <<'EOF'\n"
                        + "{\"type\":\"RECORD\",\"stream\":\"s\",\"record\":{\"arg\":\"$1\"}}\n"
                        + "EOF\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));

        // A shell would run the subcommand and create the marker. argv passes it as text.
        List<String> command = List.of(
                script.toAbsolutePath().toString(), "$(touch " + marker.toAbsolutePath() + ")");

        Path parquet = runner.run(command, "s", LIMIT);
        try {
            assertThat(marker).doesNotExist();
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    @Test
    @DisplayName("turns a tap's record stream into a queryable parquet file")
    void producesParquet(@TempDir Path dir) throws Exception {
        List<String> command = tap(dir, emit(
                "{\"type\":\"SCHEMA\",\"stream\":\"users\",\"key_properties\":[\"id\"],"
                        + "\"schema\":{\"properties\":{\"id\":{\"type\":\"integer\"}}}}",
                "{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":1,\"name\":\"Ada\"}}",
                "{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":2,\"name\":\"Grace\"}}",
                "{\"type\":\"STATE\",\"value\":{\"bookmarks\":{}}}"));

        Path parquet = runner.run(command, "users", LIMIT);
        try {
            assertThat(parquet).exists();
            assertThat(rowsIn(parquet)).isEqualTo(2);
            assertThat(columnsIn(parquet)).containsExactlyInAnyOrder("id", "name");
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    @Test
    @DisplayName("keeps only the requested stream — one dataset is one relation")
    void filtersByStream(@TempDir Path dir) throws Exception {
        List<String> command = tap(dir, emit(
                "{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":1}}",
                "{\"type\":\"RECORD\",\"stream\":\"orders\",\"record\":{\"id\":99}}",
                "{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":2}}"));

        Path parquet = runner.run(command, "users", LIMIT);
        try {
            assertThat(rowsIn(parquet)).isEqualTo(2);
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    @Test
    @DisplayName("ignores the human-readable logging taps interleave with the protocol")
    void toleratesNonProtocolOutput(@TempDir Path dir) throws Exception {
        // Real taps do this constantly — INFO lines on the same stream as the messages. A parser
        // that treated a non-JSON line as a failure would reject most taps in existence.
        List<String> command = tap(dir,
                "echo 'INFO Starting sync of users'\n"
                + emit("{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":1}}")
                + "echo 'INFO Sync complete, 1 record'\n");

        Path parquet = runner.run(command, "users", LIMIT);
        try {
            assertThat(rowsIn(parquet)).isEqualTo(1);
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    @Test
    @DisplayName("a tap that fails takes the ingestion with it")
    void nonZeroExitIsAnError(@TempDir Path dir) throws Exception {
        List<String> command = tap(dir,
                emit("{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":1}}")
                + "echo 'CRITICAL auth failed' >&2\nexit 1\n");

        // Partial data that looks complete is the failure worth preventing: a tap that dies
        // halfway through would otherwise land a truncated dataset nobody could tell was truncated.
        assertThatThrownBy(() -> runner.run(command, "users", LIMIT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exited with status 1");
    }

    @Test
    @DisplayName("a stream that yields nothing is an error, not an empty dataset")
    void emptyStreamIsAnError(@TempDir Path dir) throws Exception {
        List<String> command = tap(dir,
                emit("{\"type\":\"RECORD\",\"stream\":\"orders\",\"record\":{\"id\":1}}"));

        // An empty parquet has no columns, so it would bootstrap into a class with no properties —
        // which the conformance kit rejects, but only after the dataset had been created.
        assertThatThrownBy(() -> runner.run(command, "orders_typo", LIMIT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no records");
    }

    @Test
    @DisplayName("a tap that hangs is killed rather than pinning the thread forever")
    void hangingTapIsKilled(@TempDir Path dir) throws Exception {
        List<String> command = tap(dir,
                emit("{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":1}}")
                + "sleep 60\n");

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> runner.run(command, "users", Duration.ofSeconds(2)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not finish");
        assertThat(System.currentTimeMillis() - start)
                .describedAs("the timeout must actually bound the wait")
                .isLessThan(30_000);
    }

    @Test
    @DisplayName("discovery lists the streams a tap offers")
    void discoversStreams(@TempDir Path dir) throws Exception {
        List<String> command = tap(dir, emit(
                "{\"type\":\"SCHEMA\",\"stream\":\"users\",\"schema\":{}}",
                "{\"type\":\"SCHEMA\",\"stream\":\"orders\",\"schema\":{}}",
                "{\"type\":\"SCHEMA\",\"stream\":\"users\",\"schema\":{}}"));

        assertThat(runner.discoverStreams(command, LIMIT))
                .describedAs("deduplicated, in the order the tap declared them")
                .containsExactly("users", "orders");
    }

    @Test
    @DisplayName("discovery kills a tap that hangs instead of blocking on its read (IMPORTS-7)")
    void hangingDiscoveryIsKilled(@TempDir Path dir) throws Exception {
        // The exact failure run()'s comments document as its own fatal first version: the deadline
        // was applied AFTER the blocking read loop, so a tap that emitted a schema and then slept
        // held the calling thread for as long as it liked.
        List<String> command = tap(dir,
                emit("{\"type\":\"SCHEMA\",\"stream\":\"users\",\"schema\":{}}")
                + "sleep 60\n");

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> runner.discoverStreams(command, Duration.ofSeconds(2)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not finish");
        assertThat(System.currentTimeMillis() - start)
                .describedAs("the timeout must actually bound the wait")
                .isLessThan(30_000);
    }

    @Test
    @DisplayName("a discovery that fails is an error, not an empty stream list (IMPORTS-7)")
    void discoveryNonZeroExitIsAnError(@TempDir Path dir) throws Exception {
        // "No streams" and "your credentials are wrong" must not read the same: the first sends a
        // user hunting for a stream that exists, the second names the thing to fix.
        List<String> command = tap(dir, "echo 'CRITICAL auth failed' >&2\nexit 1\n");

        assertThatThrownBy(() -> runner.discoverStreams(command, LIMIT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exited with status 1");
    }

    @Test
    @DisplayName("a tap sees an allowlisted environment, never Aran's own (IMPORTS-8)")
    void tapEnvironmentIsCleared(@TempDir Path dir) throws Exception {
        // A tap is third-party code running as the backend. Inheriting the backend's environment
        // hands it ARAN_LAKE_SECRET and every other credential the process holds, for nothing —
        // a tap's own settings travel by config file and never by environment.
        String leaked = System.getenv().keySet().stream()
                .filter(k -> !Set.of("PATH", "HOME", "TMPDIR").contains(k))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "this JVM has no non-allowlisted environment variable to test with"));

        List<String> command = tap(dir,
                "printf '{\"type\":\"RECORD\",\"stream\":\"e\",\"record\":"
                + "{\"leaked\":\"%s\",\"path_present\":\"%s\"}}\\n' \"$" + leaked + "\" \"${PATH:+yes}\"\n");

        Path parquet = runner.run(command, "e", LIMIT);
        try {
            assertThat(valueIn(parquet, "leaked"))
                    .describedAs("%s must not reach the tap", leaked)
                    .isEmpty();
            assertThat(valueIn(parquet, "path_present"))
                    .describedAs("PATH is allowlisted — a tap that cannot find its own tools is useless")
                    .isEqualTo("yes");
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    // ── reading the result back ──────────────────────────────────────────────

    private static long rowsIn(Path parquet) throws Exception {
        try (ResultSet rs = query("SELECT count(*) FROM read_parquet('" + esc(parquet) + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The single row's value for {@code column}, as text. */
    private static String valueIn(Path parquet, String column) throws Exception {
        try (ResultSet rs = query("SELECT \"" + column + "\" FROM read_parquet('"
                + esc(parquet) + "')")) {
            rs.next();
            String value = rs.getString(1);
            return value == null ? "" : value;
        }
    }

    private static List<String> columnsIn(Path parquet) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        try (ResultSet rs = query("DESCRIBE SELECT * FROM read_parquet('" + esc(parquet) + "')")) {
            while (rs.next()) {
                names.add(rs.getString("column_name"));
            }
        }
        return names;
    }

    /** The connection is closed with the ResultSet by the caller's try-with-resources. */
    private static ResultSet query(String sql) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        Statement stmt = conn.createStatement();
        stmt.closeOnCompletion();
        return stmt.executeQuery(sql);
    }

    private static String esc(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }
}

package edu.upc.dtak.aran.operators.bootstrapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner against a genuine published Singer tap.
 *
 * <p>Skipped unless pointed at one, because a build must not depend on a Python environment
 * nobody promised it. Run it with:
 *
 * <pre>{@code
 * ./gradlew :aran-operators:bootstrapping:test --tests '*RealSingerTapTest*' \
 *     -Daran.singer.command="/path/to/venv/bin/tap-exchangeratesapi" \
 *     -Daran.singer.stream=exchange_rate
 * }</pre>
 *
 * <p>{@code SingerTapRunnerTest} covers the protocol handling with taps written as shell scripts,
 * which is where the subprocess bugs live. What only a real tap proves is that the assumptions
 * hold against output nobody wrote for this test — {@code singer-python}'s exact message
 * serialisation, its logging on the same stream, and the shape of records from a live API.
 */
class RealSingerTapTest {

    private static final String COMMAND = "aran.singer.command";
    private static final String STREAM = "aran.singer.stream";

    @Test
    @EnabledIfSystemProperty(named = COMMAND, matches = ".+")
    @DisplayName("a published tap's output becomes a queryable parquet file")
    void realTapProducesQueryableParquet() throws Exception {
        List<String> command = List.of(System.getProperty(COMMAND).split("\\s+"));
        String stream = System.getProperty(STREAM, "");
        assertThat(stream)
                .describedAs("set -D%s to the stream to keep", STREAM)
                .isNotBlank();

        SingerTapRunner runner = new SingerTapRunner();

        assertThat(runner.discoverStreams(command, Duration.ofMinutes(2)))
                .describedAs("the tap should declare the stream being requested")
                .contains(stream);

        Path parquet = runner.run(command, stream, Duration.ofMinutes(2));
        try {
            assertThat(parquet).exists();
            List<String> columns = columnsIn(parquet);
            assertThat(columns)
                    .describedAs("a parquet with no columns would bootstrap into an unqueryable class")
                    .isNotEmpty();
            assertThat(rowsIn(parquet)).isPositive();
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    private static long rowsIn(Path parquet) throws Exception {
        try (ResultSet rs = query("SELECT count(*) FROM read_parquet('" + esc(parquet) + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> columnsIn(Path parquet) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = query("DESCRIBE SELECT * FROM read_parquet('" + esc(parquet) + "')")) {
            while (rs.next()) {
                names.add(rs.getString("column_name"));
            }
        }
        return names;
    }

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

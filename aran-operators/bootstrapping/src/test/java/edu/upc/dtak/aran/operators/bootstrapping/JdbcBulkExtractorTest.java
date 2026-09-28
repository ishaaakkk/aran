package edu.upc.dtak.aran.operators.bootstrapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generic JDBC extract, end to end.
 *
 * <p>DuckDB plays the source database as well as the parquet writer, which is not a stub: rows go
 * out over a real JDBC {@link ResultSet}, through the same {@link java.sql.ResultSetMetaData} path
 * an Oracle or SQL Server driver would use, and come back out of a real parquet file. What it
 * cannot cover is per-driver behaviour — Oracle's {@code NUMBER}, MySQL's zero dates — which needs
 * those databases.
 */
class JdbcBulkExtractorTest {

    private final JdbcBulkExtractor extractor = new JdbcBulkExtractor();

    private static final long NO_LIMIT = Long.MAX_VALUE;

    private static Connection source() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    /** Read a parquet file back through DuckDB, as the query engine would. */
    private static List<List<Object>> readParquet(Path parquet) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT * FROM read_parquet('" + parquet.toAbsolutePath() + "')")) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @Test
    @DisplayName("rows go out over JDBC and come back out of parquet")
    void round_trips(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("out.parquet");
        try (Connection source = source(); Statement s = source.createStatement()) {
            s.execute("CREATE TABLE papers (id INTEGER, title VARCHAR, year INTEGER)");
            s.execute("INSERT INTO papers VALUES (1, 'Bell', 1964), (2, 'GAV', 1997)");

            JdbcBulkExtractor.Extract extract =
                    extractor.extractToParquet(source, "SELECT * FROM papers", target, NO_LIMIT);

            assertThat(extract.rowCount()).isEqualTo(2);
            assertThat(readParquet(extract.parquet()))
                    .containsExactly(List.of(1, "Bell", 1964), List.of(2, "GAV", 1997));
        }
    }

    @Test
    @DisplayName("DECIMAL survives as exact text rather than being rounded into a double")
    void decimals_are_not_rounded(@TempDir Path dir) throws Exception {
        // The case that forces this: Oracle NUMBER has no lossless DuckDB equivalent, and silently
        // wrong money is worse than obviously stringy money. Mirrors the lake's numeric -> string.
        Path target = dir.resolve("money.parquet");
        try (Connection source = source(); Statement s = source.createStatement()) {
            s.execute("CREATE TABLE budgets (amount DECIMAL(18,4))");
            s.execute("INSERT INTO budgets VALUES (1234567890123.4567), (0.0001)");

            extractor.extractToParquet(source, "SELECT * FROM budgets", target, NO_LIMIT);

            assertThat(readParquet(target))
                    .containsExactly(List.of("1234567890123.4567"), List.of("0.0001"));
        }
    }

    @Test
    @DisplayName("a large decimal is written in full, never in scientific notation")
    void decimals_are_plain(@TempDir Path dir) throws Exception {
        // toString() would emit 1E+2 for some scales, making the column's contents depend on the
        // magnitude of the value. toPlainString() does not.
        Path target = dir.resolve("plain.parquet");
        try (Connection source = source(); Statement s = source.createStatement()) {
            s.execute("CREATE TABLE t (v DECIMAL(30,2))");
            s.execute("INSERT INTO t VALUES (100000000000000000000.00)");

            extractor.extractToParquet(source, "SELECT * FROM t", target, NO_LIMIT);

            assertThat(readParquet(target).getFirst().getFirst().toString())
                    .doesNotContain("E").doesNotContain("e+");
        }
    }

    @Test
    @DisplayName("nulls survive the round trip as nulls, not as empty strings")
    void nulls_survive(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("nulls.parquet");
        try (Connection source = source(); Statement s = source.createStatement()) {
            s.execute("CREATE TABLE t (a INTEGER, b VARCHAR)");
            s.execute("INSERT INTO t VALUES (1, NULL), (NULL, 'x')");

            extractor.extractToParquet(source, "SELECT * FROM t", target, NO_LIMIT);

            List<List<Object>> rows = readParquet(target);
            assertThat(rows.get(0).get(1)).isNull();
            assertThat(rows.get(1).get(0)).isNull();
        }
    }

    @Test
    @DisplayName("more rows than one batch all arrive")
    void batching_loses_nothing(@TempDir Path dir) throws Exception {
        // Off-by-one in the batch flush would drop the tail, and a row count taken from the loop
        // rather than the file would agree with itself and be wrong.
        Path target = dir.resolve("many.parquet");
        try (Connection source = source(); Statement s = source.createStatement()) {
            s.execute("CREATE TABLE t AS SELECT range AS n FROM range(2500)");

            JdbcBulkExtractor.Extract extract =
                    extractor.extractToParquet(source, "SELECT * FROM t", target, NO_LIMIT);

            assertThat(extract.rowCount()).isEqualTo(2500);
            assertThat(readParquet(target)).hasSize(2500);
        }
    }

    @Test
    @DisplayName("an empty relation produces an empty parquet, not a failure")
    void empty_relation(@TempDir Path dir) throws Exception {
        // A table that legitimately has no rows is not an error; the schema still matters.
        Path target = dir.resolve("empty.parquet");
        try (Connection source = source(); Statement s = source.createStatement()) {
            s.execute("CREATE TABLE t (a INTEGER)");

            JdbcBulkExtractor.Extract extract =
                    extractor.extractToParquet(source, "SELECT * FROM t", target, NO_LIMIT);

            assertThat(extract.rowCount()).isZero();
            assertThat(readParquet(target)).isEmpty();
        }
    }

    @Test
    @DisplayName("a column name containing a quote does not break the staging DDL")
    void hostile_column_names(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("quoted.parquet");
        try (Connection source = source(); Statement s = source.createStatement()) {
            s.execute("CREATE TABLE t (\"we\"\"ird\" INTEGER)");
            s.execute("INSERT INTO t VALUES (7)");

            extractor.extractToParquet(source, "SELECT * FROM t", target, NO_LIMIT);

            assertThat(readParquet(target)).containsExactly(List.of(7));
        }
    }

    @Test
    @DisplayName("an extract over the ceiling is abandoned and its file removed")
    void ceiling_is_enforced(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("big.parquet");
        try (Connection source = source(); Statement s = source.createStatement()) {
            s.execute("CREATE TABLE t AS SELECT range AS n, 'padding padding padding' AS p "
                    + "FROM range(20000)");

            assertThatThrownBy(() ->
                    extractor.extractToParquet(source, "SELECT * FROM t", target, 128))
                    .isInstanceOf(JdbcBulkExtractor.ExtractTooLargeException.class)
                    .hasMessageContaining("over the 128 limit");

            // Left behind, the partial file would later be uploaded as if it were the whole table.
            assertThat(Files.exists(target)).isFalse();
        }
    }

    @Test
    @DisplayName("a query returning no columns is refused rather than producing an empty file")
    void no_columns_is_an_error(@TempDir Path dir) throws IOException {
        assertThatThrownBy(() -> {
            try (Connection source = source()) {
                extractor.extractToParquet(source, "SELECT", dir.resolve("x.parquet"), NO_LIMIT);
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("an unmappable JDBC type is carried as text rather than failing the table")
    void unknown_types_degrade_to_text() {
        // Refusing a whole table for one exotic column is a worse answer than carrying it as text.
        assertThat(JdbcBulkExtractor.duckDbType(java.sql.Types.OTHER, 0, 0)).isEqualTo("VARCHAR");
        assertThat(JdbcBulkExtractor.duckDbType(java.sql.Types.SQLXML, 0, 0)).isEqualTo("VARCHAR");
        assertThat(JdbcBulkExtractor.duckDbType(java.sql.Types.NUMERIC, 18, 4)).isEqualTo("VARCHAR");
        assertThat(JdbcBulkExtractor.duckDbType(java.sql.Types.BIGINT, 0, 0)).isEqualTo("BIGINT");
    }
}

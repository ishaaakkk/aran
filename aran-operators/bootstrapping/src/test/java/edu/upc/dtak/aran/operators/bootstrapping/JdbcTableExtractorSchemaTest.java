package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TableExtractor;
import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-7: the generic JDBC extract must read the relation the scan offered, not the one the
 * connection's default search path happens to find.
 *
 * <p>{@code DataRepositoryService.listJdbcDatasets} scopes its candidate list to the repository's
 * {@code schema}, so the table name that reaches {@link TableExtractor#extract} is only unique
 * within that schema. Until this test, the query was {@code SELECT * FROM "<table>"} — unqualified
 * — so two schemas holding a table of the same name made the extract copy whichever one the server
 * resolved first, silently, under the name of the one the user chose.
 *
 * <p>DuckDB plays the source database, as {@link JdbcBulkExtractorTest} already has it do: it has
 * real schemas, and the ORACLE engine's identifier quoting is the SQL standard's, so the qualified
 * name this test asserts on is the one an Oracle server would parse. What it cannot cover is
 * per-engine schema semantics — MySQL's "schema" is a database, SQL Server's is one level of a
 * three-part name — which needs those servers and is not what S2-7 was about.
 */
@DisplayName("JdbcTableExtractor — the extract is schema-qualified (S2-7)")
class JdbcTableExtractorSchemaTest {

    private static final int PUBLIC_ROWS = 2;
    private static final int REPORTING_ROWS = 5;

    @TempDir Path tempDir;

    private Path database;

    /** Hands back a connection to the DuckDB standing in for the source, whatever URL it is given. */
    private final class DuckDbDownloader implements DriverDownloader {
        @Override
        public Driver loadDriver(String engine) {
            return new DuckDbDriver();
        }

        @Override
        public void unloadDriver(String engine) {
        }
    }

    /** A {@link Driver} that ignores the Oracle URL and opens the test's DuckDB file. */
    private final class DuckDbDriver implements Driver {
        @Override public Connection connect(String url, Properties info) throws SQLException {
            return DriverManager.getConnection("jdbc:duckdb:" + database.toAbsolutePath());
        }
        @Override public boolean acceptsURL(String url) { return true; }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getLogger("duckdb-stand-in"); }
    }

    @BeforeEach
    void seedTwoSchemasWithTheSameTable() throws Exception {
        database = tempDir.resolve("source.duckdb");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + database.toAbsolutePath());
             Statement s = conn.createStatement()) {
            // "main" is DuckDB's default search path, standing in for PostgreSQL's "public": the
            // relation an unqualified read would find.
            s.execute("CREATE TABLE main.venue (id INTEGER)");
            for (int i = 1; i <= PUBLIC_ROWS; i++) {
                s.execute("INSERT INTO main.venue VALUES (" + i + ")");
            }
            s.execute("CREATE SCHEMA reporting");
            s.execute("CREATE TABLE reporting.venue (id INTEGER)");
            for (int i = 1; i <= REPORTING_ROWS; i++) {
                s.execute("INSERT INTO reporting.venue VALUES (" + i + ")");
            }
        }
    }

    private RelationalDatabaseRepository repo(String schema) {
        return new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src", "", "ORACLE", "127.0.0.1", 1521, "srcdb", "u", "p", schema, false);
    }

    private TableExtractor extractor() {
        return new JdbcTableExtractor(new DuckDbDownloader(), new OutboundAddressGuard(false));
    }

    private static long rowsIn(byte[] parquet, Path scratch) throws Exception {
        java.nio.file.Files.write(scratch, parquet);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM read_parquet('" + scratch.toAbsolutePath() + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("the configured schema's rows are copied, not the search path's same-named table")
    void extracts_from_the_configured_schema() throws Exception {
        TableExtractor.Extracted extracted = extractor().extract(repo("reporting"), "venue", 10_000_000);

        assertThat(extracted.rowCount())
                .as("main.venue has %d rows and reporting.venue has %d — the count says which was read",
                        PUBLIC_ROWS, REPORTING_ROWS)
                .isEqualTo(REPORTING_ROWS);
        assertThat(rowsIn(extracted.parquet(), tempDir.resolve("out.parquet")))
                .isEqualTo(REPORTING_ROWS);
    }

    @Test
    @DisplayName("a repository with no schema still reads the default search path")
    void no_schema_reads_the_search_path() throws Exception {
        assertThat(extractor().extract(repo(""), "venue", 10_000_000).rowCount())
                .isEqualTo(PUBLIC_ROWS);
    }
}

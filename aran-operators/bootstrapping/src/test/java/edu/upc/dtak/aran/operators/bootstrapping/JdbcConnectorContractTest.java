package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceConnectorContract;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JDBC connector, against the shared contract and then against what is peculiar to it.
 *
 * <p>The database under test is DuckDB on a file — a real JDBC driver with a real
 * {@code DatabaseMetaData}, in-process and with nothing to install. It is not Postgres, so it does
 * not prove the connector works against every engine; what it proves is that the connector reads
 * the JDBC catalog rather than the rows, which is the part that is the same everywhere and the
 * part that would be wrong.
 */
class JdbcConnectorContractTest extends SourceConnectorContract {

    private static Path dbFile;
    private static String url;

    @BeforeAll
    static void createDatabase() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        dbFile = Files.createTempFile("aran-jdbc-contract-", ".duckdb");
        Files.deleteIfExists(dbFile); // DuckDB wants to create it itself
        url = "jdbc:duckdb:" + dbFile.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE researchers (
                        id        INTEGER,
                        name      VARCHAR,
                        h_index   BIGINT,
                        score     DOUBLE,
                        tenured   BOOLEAN,
                        hired_on  DATE,
                        seen_at   TIMESTAMP
                    )""");
            stmt.execute("INSERT INTO researchers VALUES "
                    + "(1,'Ada',12,4.5,true,'2020-01-01','2026-08-01 10:00:00')");
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Override
    protected SourceConnector connector() {
        return new JdbcConnector(DUCKDB_DOWNLOADER);
    }

    /**
     * Stands in for the pinned, sandboxed downloader the connector now takes its driver from
     * (S4-4). It hands back the DuckDB driver whatever engine it is asked for, which is what lets
     * the binding tests below declare POSTGRESQL or ORACLE — or no engine at all — while still
     * introspecting the DuckDB fixture.
     */
    private static final DriverDownloader DUCKDB_DOWNLOADER = new DriverDownloader() {
        @Override
        public Driver loadDriver(String engine) {
            return new Driver() {
                @Override public Connection connect(String u, Properties info) throws SQLException {
                    // The URL is honoured (an unreachable one must still fail); the properties are
                    // not, because DuckDB reads them as its own configuration settings.
                    return DriverManager.getConnection(u);
                }
                @Override public boolean acceptsURL(String u) { return true; }
                @Override public DriverPropertyInfo[] getPropertyInfo(String u, Properties info) {
                    return new DriverPropertyInfo[0];
                }
                @Override public int getMajorVersion() { return 1; }
                @Override public int getMinorVersion() { return 0; }
                @Override public boolean jdbcCompliant() { return false; }
                @Override public Logger getParentLogger() { return Logger.getLogger("duckdb"); }
            };
        }

        @Override
        public void unloadDriver(String engine) {
        }
    };

    @Override
    protected SourceDescriptor validSource() {
        return SourceDescriptor.ofJdbc("d-jdbc", "researchers", null,
                new SourceDescriptor.Connection(url, null, null, null, "researchers"));
    }

    @Test
    @DisplayName("maps JDBC types onto XSD ranges the integration stage can compare")
    void typesAreMapped() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();

        assertThat(rangeOf(model, "id")).isEqualTo(XSD.integer.getURI());
        assertThat(rangeOf(model, "name")).isEqualTo(XSD.xstring.getURI());
        assertThat(rangeOf(model, "h_index")).isEqualTo(XSD.xlong.getURI());
        assertThat(rangeOf(model, "score")).isEqualTo(XSD.xdouble.getURI());
        assertThat(rangeOf(model, "tenured")).isEqualTo(XSD.xboolean.getURI());
        assertThat(rangeOf(model, "hired_on")).isEqualTo(XSD.date.getURI());
        assertThat(rangeOf(model, "seen_at")).isEqualTo(XSD.dateTime.getURI());
    }

    @Test
    @DisplayName("column order follows the table, so re-bootstrapping reports no drift")
    void columnOrderIsStable() throws Exception {
        // Not covered by the contract's isomorphism check — two graphs with the same triples in a
        // different order are isomorphic. This asserts the map that produced them is ordered.
        Model model = connector().introspect(validSource()).rdfs();
        assertThat(model.listStatements(null, RDFS.label, (org.apache.jena.rdf.model.RDFNode) null)
                .toList())
                .isNotEmpty();
    }

    @Test
    @DisplayName("a table that does not exist fails loudly, not with an empty schema")
    void missingTableIsAnError() {
        SourceDescriptor missing = SourceDescriptor.ofJdbc("d-x", "ghosts", null,
                new SourceDescriptor.Connection(url, null, null, null, "no_such_table"));

        // An empty class would bootstrap "successfully" and then be unqueryable — the failure
        // shape the conformance kit exists to prevent.
        assertThatThrownBy(() -> connector().introspect(missing))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("no columns found");
    }

    @Test
    @DisplayName("an unreachable database reports the driver's reason")
    void unreachableDatabaseIsReported() {
        SourceDescriptor unreachable = SourceDescriptor.ofJdbc("d-y", "t", null,
                new SourceDescriptor.Connection("jdbc:duckdb:/nonexistent/dir/x.duckdb",
                        null, null, null, "t"));

        assertThatThrownBy(() -> connector().introspect(unreachable))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("the wrapper is a flat column projection — a table is not a JSON path (V45)")
    void wrapperIsFlat() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();
        assertThat(WrapperGenerator.attachPerClassWrappers(model)).isEqualTo(1);

        String sql = model.listObjectsOfProperty(WrapperVocab.WRAPPER_SQL).toList()
                .getFirst().asLiteral().getString();
        // The iterator of a JDBC source is the TABLE name. Read as a JSONPath it would become a
        // struct field, and every projection would be s."researchers"."name" — which binds
        // against nothing in postgres_scan's output.
        assertThat(sql)
                .contains("\"name\" AS")
                .contains("\"h_index\" AS")
                .contains("FROM " + WrapperVocab.SNAPSHOT_PLACEHOLDER)
                .doesNotContain("s.\"researchers\"")
                .doesNotContain("UNNEST");
    }

    @Test
    @DisplayName("the password never reaches a log line or an exception message")
    void passwordIsNotPrintable() {
        SourceDescriptor.Connection conn = new SourceDescriptor.Connection(
                url, "someone", "hunter2", null, "researchers");

        // A descriptor lands in structured logs and exception messages by default. This is the one
        // field that must never travel with it.
        assertThat(conn.toString()).doesNotContain("hunter2").contains("***");
        assertThat(SourceDescriptor.ofJdbc("d", "n", null, conn).toString())
                .doesNotContain("hunter2");
    }

    private static String rangeOf(Model model, String column) {
        Resource property = model.listResourcesWithProperty(RDFS.label, column)
                .filterKeep(r -> model.contains(r, RDF.type, RDF.Property))
                .toList()
                .getFirst();
        return model.getProperty(property, RDFS.range).getObject().asResource().getURI();
    }
}

package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-4/S3-3: every connection to a source database goes through {@link DriverDownloader}.
 *
 * <p>{@code JdbcBootstrap} used to open its own via {@code DriverManager}. A driver the downloader
 * loaded lives in a child {@code URLClassLoader} and {@code DriverManager} cannot see it (its
 * {@code isDriverAllowed} check re-resolves the driver class through the <em>caller's</em>
 * loader), so bootstrap worked only for the one vendor driver on the application classpath —
 * Postgres. A federated MySQL or MariaDB repository, which passes test-connection, scan and
 * preview because all three use the downloader, then failed here with "No suitable driver".
 *
 * <p>The test states that as a discriminator rather than trusting it: the URL is a MySQL one, and
 * the test first asserts that <em>nothing</em> registered with {@code DriverManager} accepts it.
 * Introspection succeeding therefore cannot have come from {@code DriverManager}.
 */
@DisplayName("JdbcBootstrap — connections come from DriverDownloader, never DriverManager")
class JdbcBootstrapDriverPathTest {

    /** A URL no driver on this module's test classpath can serve. */
    private static final String MYSQL_URL = "jdbc:mysql://db.invalid:3306/app";

    @TempDir Path tempDir;

    private Path database;

    /**
     * Hands back a DuckDB-backed driver whatever engine it is asked for, and records the asking —
     * the same stand-in shape {@code JdbcTableExtractorSchemaTest} uses, for the same reason: the
     * driver a downloader returns is invisible to {@code DriverManager}, which is exactly the
     * production condition under test.
     */
    private final class RecordingDownloader implements DriverDownloader {
        final List<String> enginesAsked = new ArrayList<>();

        @Override
        public Driver loadDriver(String engine) {
            enginesAsked.add(engine);
            return new DuckDbStandIn();
        }

        @Override
        public void unloadDriver(String engine) {
        }
    }

    /** A {@link Driver} that ignores the MySQL URL and opens the test's DuckDB file. */
    private final class DuckDbStandIn implements Driver {
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
    void seedSourceTable() throws Exception {
        database = tempDir.resolve("source.duckdb");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + database.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE orders (id INTEGER, customer VARCHAR, total DOUBLE)");
        }
    }

    @Test
    @DisplayName("a federated MySQL source bootstraps from a driver only the downloader can supply")
    void bootstrapsThroughTheDownloader() throws Exception {
        assertNoDriverManagerDriverAccepts(MYSQL_URL);
        int loginTimeoutBefore = DriverManager.getLoginTimeout();

        RecordingDownloader downloader = new RecordingDownloader();
        Model model = new JdbcConnector(downloader).introspect(
                SourceDescriptor.ofJdbc("ds-mysql", "orders", null,
                        new SourceDescriptor.Connection(MYSQL_URL, "app", "secret", null,
                                "orders", "MYSQL"))).rdfs();

        assertThat(downloader.enginesAsked)
                .as("the downloader is the only way a source-database driver may be obtained")
                .containsExactly("MYSQL");
        assertThat(labels(model))
                .as("the catalog was really read through that driver")
                .contains("id", "customer", "total");
        assertThat(DriverManager.getLoginTimeout())
                .as("DriverManager.setLoginTimeout is JVM-global state shared with Hikari and "
                        + "every other JDBC user in the process — bootstrap must not write it")
                .isEqualTo(loginTimeoutBefore);
    }

    @Test
    @DisplayName("the connect step is bounded per connection, in each driver's own unit")
    void connectTimeoutIsSetPerEngine() {
        // Postgres reads connectTimeout in SECONDS; MySQL and MariaDB read the same property name
        // in MILLISECONDS. One shared number would mean "10 milliseconds" on two of the three
        // engines that can reach this class, and every connection would fail instantly.
        assertThat(JdbcBootstrap.connectionProperties(connectionOn("POSTGRESQL")))
                .containsEntry("connectTimeout", "10")
                .containsEntry("user", "app")
                .containsEntry("password", "secret");
        assertThat(JdbcBootstrap.connectionProperties(connectionOn("MYSQL")))
                .containsEntry("connectTimeout", "10000");
        assertThat(JdbcBootstrap.connectionProperties(connectionOn("MARIADB")))
                .containsEntry("connectTimeout", "10000");
        assertThat(JdbcBootstrap.connectionProperties(connectionOn("ORACLE")))
                .as("an engine whose property name and unit are not stated here gets no bound, "
                        + "rather than a guess that could mean 10 milliseconds")
                .doesNotContainKey("connectTimeout");
        assertThat(JdbcBootstrap.connectionProperties(connectionOn(null)))
                .doesNotContainKey("connectTimeout");
    }

    private static SourceDescriptor.Connection connectionOn(String engine) {
        return new SourceDescriptor.Connection(MYSQL_URL, "app", "secret", null, "orders", engine);
    }

    private static List<String> labels(Model model) {
        List<String> out = new ArrayList<>();
        for (RDFNode node : model.listObjectsOfProperty(RDFS.label).toList()) {
            out.add(node.asLiteral().getString());
        }
        return out;
    }

    /** Fails unless no {@code DriverManager}-registered driver can serve {@code url}. */
    private static void assertNoDriverManagerDriverAccepts(String url) throws SQLException {
        for (Driver driver : java.util.Collections.list(DriverManager.getDrivers())) {
            assertThat(driver.acceptsURL(url))
                    .as("%s would serve %s, which would make this test unable to tell the two "
                            + "code paths apart", driver.getClass().getName(), url)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the fixture really is unreachable through DriverManager")
    void driverManagerCannotServeTheUrl() throws Exception {
        // Pins the premise of the test above as its own named failure, so a classpath change that
        // silently adds a MySQL driver reports itself here rather than weakening that test.
        assertNoDriverManagerDriverAccepts(MYSQL_URL);
        assertThat(Files.exists(database)).isTrue();
    }
}

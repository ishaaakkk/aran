package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import edu.upc.dtak.aran.core.source.FederatedScan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A federated Postgres read, prepared the way the query resolver prepares it, leaves nothing a
 * catalogue read could hand back.
 *
 * <p>DuckDB returns every view's SQL through {@code duckdb_views()},
 * {@code information_schema.views}, {@code sqlite_master} and {@code pg_views}, and a {@code src_}
 * view's body is the resolver's read expression. Under {@code postgres_scan('dbname=… password=…',
 * …)} that body was the source's password, readable by any statement on the connection — the
 * sources tier, a products-tier statement over a virtual product, {@code run}. The SQL screen now
 * refuses those catalogue reads ({@code QueryEngineSqlGuardTest}); this is the other half: the
 * read became an attached catalog, so the credential lives only in a secret, which
 * {@code duckdb_secrets()} redacts.
 *
 * <p>Runs against a real Postgres because the attached-catalog shape is only worth anything if the
 * read still answers, and only the executor's own connection knows what its views say.
 */
@Tag("docker")
class FederatedPostgresViewBodyTest {

    private static final String PASSWORD = "pw-sentinel-7c1e";
    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String ORDER = NS + "integration/Order";
    private static final String P_ID = NS + "integration/id";
    private static final String P_NOTE = NS + "integration/note";

    private static PostgreSQLContainer<?> pg;

    @BeforeAll
    static void startPostgres() throws Exception {
        pg = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withPassword(PASSWORD);
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id INT PRIMARY KEY, note TEXT)");
            st.execute("INSERT INTO orders VALUES (1, 'a'), (2, 'b'), (3, NULL)");
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (pg != null) {
            pg.stop();
        }
    }

    @Test
    @DisplayName("no view body, secret or attachment on the prepared connection carries the source's password")
    void preparedConnectionHoldsNoCredentialInItsCatalogue() throws Exception {
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "src", "", "POSTGRESQL",
                pg.getHost(), pg.getMappedPort(5432), pg.getDatabaseName(), pg.getUsername(),
                PASSWORD, "public", true);
        FederatedScan scan = FederatedScanExpression.forRepository(repo, "orders");
        assertThat(scan).isNotNull();

        // Exactly the resolver's federated branch: the setup rides on the connection, the
        // expression is what src_ selects from.
        ConnectionSetup setup = new ConnectionSetup();
        setup.addAttachedCatalog(scan);
        MappingCatalog cat = new MappingCatalog(Map.of(
                ORDER, new MappingCatalog.IntegratedClass(ORDER, List.of(
                        new MappingCatalog.SourceView("ds1", "SELECT * FROM ${SNAPSHOT}",
                                Map.of(P_ID, "id", P_NOTE, "note"), 3L)))),
                List.of(), List.of(), "v1");

        try (DuckDbExecutor exec = new DuckDbExecutor(cat, ds -> scan.expression())
                .withConnectionSetup(setup)
                .restrictFilesystemTo(List.of(QueryTempDir.get()))) {
            exec.prepare();

            // The live read still answers — the shape change must not cost the read.
            assertThat(exec.run("SELECT count(*) FROM int_Order").rows())
                    .containsExactly(List.of(3L));

            for (String sql : List.of(
                    "SELECT sql FROM duckdb_views() WHERE NOT internal",
                    "SELECT view_definition FROM information_schema.views",
                    "SELECT sql FROM sqlite_master",
                    "SELECT definition FROM pg_views",
                    "SELECT secret_string FROM duckdb_secrets()",
                    "SELECT coalesce(path, '') FROM duckdb_databases()")) {
                List<List<Object>> rows = exec.run(sql).rows();
                assertThat(rows).as(sql).isNotEmpty();
                assertThat(rows.toString()).as(sql)
                        .doesNotContain(PASSWORD)
                        .doesNotContainIgnoringCase("password=" + PASSWORD);
            }
            // The src_ body names the attached table, and no connection string at all.
            assertThat(exec.run("SELECT sql FROM duckdb_views() WHERE view_name LIKE 'src_%'")
                    .rows().toString())
                    .contains(scan.attachAlias())
                    .doesNotContain("dbname=")
                    .doesNotContain("postgres_scan");
        }
    }
}

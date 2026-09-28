package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import edu.upc.dtak.aran.core.source.DuckDbS3Config;
import edu.upc.dtak.aran.core.source.FederatedScan;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * The per-connection setup carrier the object-storage resolver fills and {@link DuckDbExecutor}
 * runs before a query: {@code httpfs} loaded once plus one {@code CREATE SECRET} per distinct
 * object-storage repository. These pin the de-duplication, the order, and — load-bearing — that the
 * secret never appears in {@code toString} nor in the exception raised when binding fails.
 *
 * <p>Offline: the statements are captured against a mock {@link Statement}, so no network / no
 * httpfs download is needed. The real execution against MinIO is exercised by the docker-tagged
 * end-to-end tests.
 */
@DisplayName("ConnectionSetup — httpfs + per-repo S3 secrets, run once, never leaked")
class ConnectionSetupTest {

    private static final String SECRET = "TOP-SECRET-KEY-VALUE";

    private static DuckDbS3Config cfg() {
        return new DuckDbS3Config("MINIO", "http://localhost:9000", "AKID", SECRET, "us-east-1");
    }

    @Test
    @DisplayName("a fresh carrier is empty")
    void freshIsEmpty() {
        assertThat(new ConnectionSetup().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("applyTo installs+loads httpfs once, then one CREATE SECRET per distinct name, in order")
    void appliesHttpfsThenSecrets() throws Exception {
        ConnectionSetup setup = new ConnectionSetup();
        setup.addObjectStorageSecret(cfg(), "aran_s3_a", "s3://bucket-a");
        setup.addObjectStorageSecret(cfg(), "aran_s3_b", "s3://bucket-b");

        Statement stmt = mock(Statement.class);
        setup.applyTo(stmt);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(stmt, Mockito.atLeastOnce()).execute(sql.capture());
        List<String> executed = new ArrayList<>(sql.getAllValues());

        assertThat(executed).containsSubsequence("INSTALL httpfs", "LOAD httpfs");
        // httpfs comes before any secret
        assertThat(executed.indexOf("LOAD httpfs"))
                .isLessThan(indexOfStartingWith(executed, "CREATE OR REPLACE SECRET aran_s3_a"));
        // both distinct secrets are issued
        assertThat(indexOfStartingWith(executed, "CREATE OR REPLACE SECRET aran_s3_a")).isGreaterThanOrEqualTo(0);
        assertThat(indexOfStartingWith(executed, "CREATE OR REPLACE SECRET aran_s3_b")).isGreaterThanOrEqualTo(0);
        // httpfs installed exactly once, regardless of how many secrets
        assertThat(executed.stream().filter("INSTALL httpfs"::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same repository added twice de-duplicates to one CREATE SECRET")
    void deduplicatesByName() throws Exception {
        ConnectionSetup setup = new ConnectionSetup();
        setup.addObjectStorageSecret(cfg(), "aran_s3_same", "s3://bucket");
        setup.addObjectStorageSecret(cfg(), "aran_s3_same", "s3://bucket");

        Statement stmt = mock(Statement.class);
        setup.applyTo(stmt);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(stmt, Mockito.atLeastOnce()).execute(sql.capture());
        long secrets = sql.getAllValues().stream()
                .filter(s -> s.startsWith("CREATE OR REPLACE SECRET")).count();
        assertThat(secrets).isEqualTo(1);
    }

    @Test
    @DisplayName("toString never contains the secret key value")
    void toStringRedactsSecret() {
        ConnectionSetup setup = new ConnectionSetup();
        setup.addObjectStorageSecret(cfg(), "aran_s3_a", "s3://bucket-a");
        assertThat(setup.toString()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a failed CREATE SECRET surfaces only the scope — never the secret nor the driver message")
    void secretBindFailureIsRedacted() throws Exception {
        ConnectionSetup setup = new ConnectionSetup();
        setup.addObjectStorageSecret(cfg(), "aran_s3_a", "s3://bucket-a");

        Statement stmt = mock(Statement.class);
        // A DuckDB secret error can echo option values, so the driver message itself is a leak vector.
        doThrow(new SQLException("Invalid secret: SECRET '" + SECRET + "' rejected"))
                .when(stmt).execute(Mockito.startsWith("CREATE OR REPLACE SECRET"));

        assertThatThrownBy(() -> setup.applyTo(stmt))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("s3://bucket-a")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    @DisplayName("an HTTP bearer secret (Gap 2) loads httpfs, carries the token, and never leaks it")
    void httpBearerSecret() throws Exception {
        ConnectionSetup setup = new ConnectionSetup();
        setup.addHttpBearerSecret("aran_pat_bearer-token-value", "aran_ice_http_a", null);

        Statement stmt = mock(Statement.class);
        setup.applyTo(stmt);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(stmt, Mockito.atLeastOnce()).execute(sql.capture());
        List<String> executed = new ArrayList<>(sql.getAllValues());

        // httpfs is needed for the http(s) reads the token authenticates, even with no S3 secret.
        assertThat(executed).containsSubsequence("INSTALL httpfs", "LOAD httpfs");
        int idx = indexOfStartingWith(executed, "CREATE OR REPLACE SECRET aran_ice_http_a");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(executed.get(idx)).contains("TYPE HTTP").contains("BEARER_TOKEN");
        // The carrier's own string form must not leak the token.
        assertThat(setup.toString()).doesNotContain("bearer-token-value");
        assertThat(setup.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("a failed HTTP-secret bind surfaces neither the token nor the driver message")
    void httpSecretBindFailureIsRedacted() throws Exception {
        ConnectionSetup setup = new ConnectionSetup();
        setup.addHttpBearerSecret("aran_pat_bearer-token-value", "aran_ice_http_a", "https://aran.example/");

        Statement stmt = mock(Statement.class);
        doThrow(new SQLException("Invalid secret: BEARER_TOKEN 'aran_pat_bearer-token-value'"))
                .when(stmt).execute(Mockito.startsWith("CREATE OR REPLACE SECRET"));

        assertThatThrownBy(() -> setup.applyTo(stmt))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("https://aran.example/")
                .hasMessageNotContaining("bearer-token-value");
    }

    private static FederatedScan mysqlScan(UUID repositoryId, String table) {
        return FederatedScanExpression.forRepository(new RelationalDatabaseRepository(
                repositoryId, UUID.randomUUID(), UUID.randomUUID(), "shop", "", "MYSQL",
                "db", 3306, "shop", "u", SECRET, "public", true), table);
    }

    @Test
    @DisplayName("an attached MySQL catalog runs its own setup, once per repository, and needs no httpfs")
    void attachedCatalogRunsOncePerRepository() throws Exception {
        UUID repo = UUID.randomUUID();
        ConnectionSetup setup = new ConnectionSetup();
        setup.addAttachedCatalog(mysqlScan(repo, "orders"));
        setup.addAttachedCatalog(mysqlScan(repo, "customers"));
        assertThat(setup.isEmpty()).isFalse();

        Statement stmt = mock(Statement.class);
        setup.applyTo(stmt);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(stmt, Mockito.atLeastOnce()).execute(sql.capture());
        assertThat(sql.getAllValues())
                .as("two tables of one repository share one attachment")
                .containsExactlyElementsOf(mysqlScan(repo, "orders").setupSql());
        assertThat(sql.getAllValues()).doesNotContain("INSTALL httpfs");
        assertThat(setup.toString()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a table-function scan has nothing to attach — postgres_scan's extension comes from the DDL")
    void tableFunctionScanRegistersNothing() {
        ConnectionSetup setup = new ConnectionSetup();
        setup.addAttachedCatalog(FederatedScan.tableFunction("postgres", "postgres_scan('x', 'y', 'z')"));
        assertThat(setup.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a failed MySQL secret surfaces its index and keyword — never the password nor the driver message")
    void attachedSecretFailureIsRedacted() throws Exception {
        ConnectionSetup setup = new ConnectionSetup();
        setup.addAttachedCatalog(mysqlScan(UUID.randomUUID(), "orders"));

        Statement stmt = mock(Statement.class);
        doThrow(new SQLException("Parser Error: syntax error at or near \"'" + SECRET + "'\""))
                .when(stmt).execute(Mockito.startsWith("CREATE OR REPLACE TEMPORARY SECRET"));

        assertThatThrownBy(() -> setup.applyTo(stmt))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("(CREATE)")
                .hasMessageNotContaining(SECRET);
    }

    private static int indexOfStartingWith(List<String> list, String prefix) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }
}

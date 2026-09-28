package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * S7-16: verify-wrapper's fresh connection binds its {@code src_} view by the query path's own
 * sequence — extensions, secrets, view — instead of trusting DuckDB's autoload. The mocked
 * statements pin which statements run and in what order without an extension download; the one
 * real-engine test shows what the explicit sequence buys.
 */
@DisplayName("verify-wrapper binds a federated src_ view the way the query path does (S7-16)")
class VerifyWrapperSourceBindingTest {

    private static List<String> executed(Statement stmt) throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(stmt, Mockito.atLeastOnce()).execute(sql.capture());
        return sql.getAllValues();
    }

    @Test
    @DisplayName("a relational scan LOADs its scanner before the view binds")
    void scannerIsLoadedBeforeTheViewBinds() throws Exception {
        Statement stmt = mock(Statement.class);

        QueryEngineOperator.bindSourceView(stmt, "src_ds",
                "postgres_scan('host=db port=5432', 'public', 'orders')", new ConnectionSetup());

        assertThat(executed(stmt)).containsExactly(
                "INSTALL postgres;", "LOAD postgres;",
                "CREATE OR REPLACE VIEW src_ds AS SELECT * FROM "
                        + "postgres_scan('host=db port=5432', 'public', 'orders')");
    }

    @Test
    @DisplayName("an iceberg_scan source gets httpfs + iceberg, then its bearer secret, then the view")
    void icebergExtensionsThenSecretsThenView() throws Exception {
        Statement stmt = mock(Statement.class);
        ConnectionSetup setup = new ConnectionSetup();
        setup.addHttpBearerSecret("aran_pat_do_not_log", "aran_ice_http_x", null);

        QueryEngineOperator.bindSourceView(stmt, "src_ds",
                "iceberg_scan('https://aran.example/api/iceberg/files/p/s/metadata/v1.metadata.json')",
                setup);

        List<String> sql = executed(stmt);
        assertThat(sql).containsSubsequence("INSTALL httpfs;", "LOAD httpfs;",
                "INSTALL iceberg;", "LOAD iceberg;");
        int secret = indexOfPrefix(sql, "CREATE OR REPLACE SECRET");
        int view = indexOfPrefix(sql, "CREATE OR REPLACE VIEW src_ds");
        assertThat(secret).as("secret registered").isNotNegative();
        assertThat(sql.indexOf("LOAD iceberg;")).isLessThan(secret);
        assertThat(secret).as("the view binds — and reads — only once its secret exists")
                .isLessThan(view);
        assertThat(view).isEqualTo(sql.size() - 1);
    }

    @Test
    @DisplayName("an attached MySQL catalog: its extension, setting, secret and ATTACH, then the view")
    void mysqlAttachesBeforeTheViewBinds() throws Exception {
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "shop", "", "MYSQL",
                "db", 3306, "shop", "u", "do-not-log", "public", true);
        FederatedScan scan = FederatedScanExpression.forRepository(repo, "orders");
        ConnectionSetup setup = new ConnectionSetup();
        setup.addAttachedCatalog(scan);
        Statement stmt = mock(Statement.class);

        QueryEngineOperator.bindSourceView(stmt, "src_ds", scan.expression(), setup);

        List<String> expected = new java.util.ArrayList<>(scan.setupSql());
        expected.add("CREATE OR REPLACE VIEW src_ds AS SELECT * FROM " + scan.expression());
        assertThat(executed(stmt))
                .as("no mysql sniff in the DDL: the extension load is the scan's own setup")
                .containsExactlyElementsOf(expected);
        assertThat(DuckDbExecutor.isFederated(List.of(expected.get(expected.size() - 1))))
                .as("an attached table is a live read, so verify-wrapper must not lock it down")
                .isTrue();
    }

    @Test
    @DisplayName("a local snapshot read installs nothing: the view is the only statement")
    void localReadNeedsNoExtension() throws Exception {
        Statement stmt = mock(Statement.class);

        QueryEngineOperator.bindSourceView(stmt, "src_ds",
                "read_parquet('/tmp/snap.parquet')", new ConnectionSetup());

        assertThat(executed(stmt)).containsExactly(
                "CREATE OR REPLACE VIEW src_ds AS SELECT * FROM read_parquet('/tmp/snap.parquet')");
    }

    /**
     * On a real engine with autoload switched off — a setting a hardened deployment may choose —
     * the explicit sequence is what lets the view reach the scanner at all: the bind fails on the
     * missing metadata file, the scanner's own error, where it used to fail on "Table Function
     * with name iceberg_scan is not in the catalog". The probe installs the extension first
     * (idempotent, and the bind's own sequence does the same) so the test runs wherever the
     * extension can be had; it is skipped only where it cannot — no network and no local copy.
     */
    @Test
    @DisplayName("with autoload off, a real iceberg_scan bind reaches the scanner instead of a missing function")
    void explicitSequenceDoesNotNeedAutoload() throws Exception {
        try (Connection probe = DuckDbSession.open(); Statement st = probe.createStatement()) {
            st.execute("INSTALL iceberg");
            st.execute("LOAD iceberg");
        } catch (SQLException unavailable) {
            Assumptions.abort("iceberg extension cannot be installed here: " + unavailable.getMessage());
        }

        try (Connection conn = DuckDbSession.open(); Statement st = conn.createStatement()) {
            st.execute("SET autoload_known_extensions = false");
            assertThatThrownBy(() -> QueryEngineOperator.bindSourceView(st, "src_ds",
                            "iceberg_scan('/nonexistent/aran/metadata/v1.metadata.json')",
                            new ConnectionSetup()))
                    .hasMessageNotContaining("is not in the catalog")
                    .hasMessageContaining("Cannot open file")
                    .hasMessageContaining("/nonexistent/aran/metadata/v1.metadata.json");
        }
    }

    private static int indexOfPrefix(List<String> sql, String prefix) {
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}

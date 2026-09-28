package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * T3.5 (audit JDBC_AVATICA-1 = QUERY_EXECUTION-6 = DATA_PRODUCTS-13): the products tier executes
 * the most hostile input in the system — arbitrary client SQL from JDBC — and its DuckDB
 * connection was the ONLY SQL-accepting one with no filesystem lockdown: the token screen was the
 * sole layer, which {@code DuckDbExecutor}'s own javadoc rejects for every sibling path ("a screen
 * that has to keep up with an engine's syntax will eventually be wrong about something"). This
 * pins the second layer at the connection itself, {@code DuckDbLockdownTest}-style: what the
 * screen hypothetically misses, the connection refuses.
 */
class ProductSqlLockdownTest {

    private ProductSqlExecutor executor() throws Exception {
        return new ProductSqlExecutor(UUID.randomUUID(),
                ProductCatalog.of(List.of()), new SnapshotCache(mock(LakeClient.class), "", 0L),
                mock(LakeClient.class), QueryTempDir.get());
    }

    @Test
    @DisplayName("after lockdown, a file read outside the allowed roots is refused by the connection itself")
    void lockdownRefusesOutsideFileReads(@TempDir Path outside) throws Exception {
        Path hostile = outside.resolve("hostile.csv");
        Files.writeString(hostile, "a\n1\n");

        try (ProductSqlExecutor exec = executor()) {
            exec.registerReferencedBy("SELECT 1");
            exec.lockDownUnlessFederated(List.of(QueryTempDir.get()));

            Path target = QueryTempDir.createFile("lockdown-test-", ".parquet");
            try {
                assertThatThrownBy(() -> exec.copyToParquet(
                        "SELECT * FROM read_csv_auto('" + hostile + "')", target))
                        .as("the connection, not the screen, must refuse the read")
                        .hasMessageContaining("Cannot access file")
                        .hasMessageContaining("disabled by configuration");
            } finally {
                Files.deleteIfExists(target);
            }
        }
    }

    @Test
    @DisplayName("a wrapper's s3:// literal in the virtual graph DDL cannot skip this tier's lockdown (F-QUERY-2)")
    void wrapperLiteralCannotSkipProductsLockdown(@TempDir Path outside) throws Exception {
        Path hostile = outside.resolve("hostile.csv");
        Files.writeString(hostile, "a\n1\n");

        // A virtual context whose only real source read (src_) is a LOCAL snapshot; the per-class
        // wrapper (w_) merely projects an s3:// literal. That must not be read as federation and
        // drop the lockdown — the federation signal comes from resolver-emitted src_ reads only.
        List<String> graphDdl = List.of(
                "CREATE OR REPLACE VIEW src_ds AS SELECT * FROM read_csv_auto('/nonexistent/local.csv')",
                "CREATE OR REPLACE VIEW w_ds_c AS SELECT col, 's3://evil/x' AS note FROM src_ds");

        try (ProductSqlExecutor exec = executor()) {
            exec.withVirtualContext(graphDdl, p -> null, null);
            exec.registerReferencedBy("SELECT 1");
            exec.lockDownUnlessFederated(List.of(QueryTempDir.get()));

            Path target = QueryTempDir.createFile("lockdown-test-", ".parquet");
            try {
                assertThatThrownBy(() -> exec.copyToParquet(
                        "SELECT * FROM read_csv_auto('" + hostile + "')", target))
                        .hasMessageContaining("disabled by configuration");
            } finally {
                Files.deleteIfExists(target);
            }
        }
    }

    @Test
    @DisplayName("a virtual context with an EMPTY connection setup is still locked down — the shape production passes")
    void emptyConnectionSetupDoesNotSkipTheLockdown(@TempDir Path outside) throws Exception {
        Path hostile = outside.resolve("hostile.csv");
        Files.writeString(hostile, "a\n1\n");

        // The operator's resolver always builds a ConnectionSetup, so a virtual context over local
        // snapshots only arrives with an empty one rather than null. Treating "present" as
        // "federated" dropped this tier's lockdown for every statement naming a VIRTUAL product.
        List<String> localOnlyDdl = List.of(
                "CREATE OR REPLACE VIEW src_ds AS SELECT * FROM read_csv_auto('/nonexistent/local.csv')");

        try (ProductSqlExecutor exec = executor()) {
            exec.withVirtualContext(localOnlyDdl, p -> null, new ConnectionSetup());
            exec.registerReferencedBy("SELECT 1");
            exec.lockDownUnlessFederated(List.of(QueryTempDir.get()));

            Path target = QueryTempDir.createFile("lockdown-test-", ".parquet");
            try {
                assertThatThrownBy(() -> exec.copyToParquet(
                        "SELECT * FROM read_csv_auto('" + hostile + "')", target))
                        .hasMessageContaining("disabled by configuration");
            } finally {
                Files.deleteIfExists(target);
            }
        }
    }

    @Test
    @DisplayName("the registry view still answers after lockdown — confinement must not brick the tier")
    void registryStillWorksAfterLockdown() throws Exception {
        try (ProductSqlExecutor exec = executor()) {
            exec.registerReferencedBy("SELECT * FROM _products");
            exec.lockDownUnlessFederated(List.of(QueryTempDir.get()));

            Path target = QueryTempDir.createFile("lockdown-test-", ".parquet");
            try {
                exec.copyToParquet("SELECT count(*) AS n FROM aran.products._products", target);
                assertThat(exec.rowCountOf(target)).isEqualTo(1L);
            } finally {
                Files.deleteIfExists(target);
            }
        }
    }
}

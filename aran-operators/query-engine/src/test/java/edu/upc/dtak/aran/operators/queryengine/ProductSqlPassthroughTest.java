package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code materialize-product-sql}: the passthrough half of the JDBC catalog split.
 *
 * <p>These run against a real DuckDB over real parquet, with a lake that hands back files a test
 * wrote. Faking the execution would leave the claim this operation makes — that the client's SQL
 * reaches the store <em>unrewritten</em> — asserted nowhere.
 */
class ProductSqlPassthroughTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final LakeClient lake = mock(LakeClient.class);
    private final StubProductRepository registry = new StubProductRepository();
    private final List<Path> written = new ArrayList<>();
    /** snapshotId → the parquet a test wrote for it. */
    private final Map<String, Path> snapshots = new HashMap<>();

    private QueryEngineOperator operator() {
        return new QueryEngineOperator(
                mock(GraphStore.class), mock(MappingCatalogOperator.class), lake, new ObjectMapper(),
                mock(IntegrationModelRepository.class), mock(DatasetRepository.class),
                mock(DataRepositoryRepository.class),
                // Caching disabled: this is about execution, and a cache would make "was the
                // snapshot fetched?" depend on test order.
                new SnapshotCache(lake, "", 0L),
                registry);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Path path : written) {
            Files.deleteIfExists(path);
        }
        for (Path path : snapshots.values()) {
            Files.deleteIfExists(path);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Register a materialized product of {@code tenant} whose snapshot is {@code query}'s result. */
    private DataProduct materializedProduct(String tenant, String name, String query) throws Exception {
        String snapshotId = "snap-" + name;
        snapshots.put(snapshotId, writeParquet(query));
        DataProduct product = DataProduct.draft(UUID.randomUUID(), UUID.fromString(tenant),
                        UUID.randomUUID(), name, null, "SELECT Paper.title FROM Paper",
                        UUID.randomUUID(), null, T0)
                .materialized(snapshotId, 2L,
                        ProductColumn.toJson(List.of(
                                new ProductColumn("region", "VARCHAR", java.sql.Types.VARCHAR))),
                        T0);
        registry.add(product);
        return product;
    }

    /**
     * Register a READY VIRTUAL product (Phase 5) of {@code tenant} — no snapshot, ever. Used here
     * only to assert on its {@code _products} registry row; a query that actually names it needs
     * {@link ProductSqlExecutor#withVirtualContext}, which the registry-only tests below never
     * trigger (their SQL never mentions the product by name).
     */
    private DataProduct virtualProduct(String tenant, String name, String oql) {
        DataProduct product = DataProduct.draft(UUID.randomUUID(), UUID.fromString(tenant),
                        UUID.randomUUID(), name, null, oql, UUID.randomUUID(), null, T0,
                        ProductAccessMode.VIRTUAL)
                .virtualReady(null);
        registry.add(product);
        return product;
    }

    /** The lake hands back whatever file the test wrote for that snapshot id. */
    private void lakeServesSnapshots() {
        when(lake.downloadSnapshotTo(any(UUID.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    Path source = snapshots.get(call.getArgument(1, String.class));
                    Files.copy(source, call.getArgument(2, Path.class),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return "sha";
                });
    }

    private OperatorResult run(String sql) {
        return run(TENANT, sql);
    }

    private OperatorResult run(String tenant, String sql) {
        OperatorResult result = operator().execute(new OperatorRequest(
                tenant, null, "materialize-product-sql", Map.of("sql", sql)));
        String path = result.success() ? result.data().get("path") : null;
        if (path != null) {
            written.add(Path.of(path));
        }
        return result;
    }

    // ── execution ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a client's SQL runs against the product's snapshot, unrewritten")
    void runsClientSqlOverTheSnapshot() throws Exception {
        materializedProduct(TENANT, "Sales report",
                "SELECT * FROM (VALUES ('EU', 10), ('US', 4)) AS t(region, total)");
        lakeServesSnapshots();

        String sql = "SELECT region FROM Sales_report WHERE total > 5";
        OperatorResult result = run(sql);

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(result.data().get("sql"))
                .as("the statement must reach the store byte-for-byte — that is the whole claim")
                .isEqualTo(sql);
        assertThat(result.data().get("products")).isEqualTo("Sales_report");
        assertThat(result.data().get("rowCount")).isEqualTo("1");
        assertThat(rowsOf(Path.of(result.data().get("path")))).containsExactly(List.of("EU"));
    }

    @Test
    @DisplayName("the result's columns are described for the caller")
    void describesTheResult() throws Exception {
        materializedProduct(TENANT, "Sales report",
                "SELECT * FROM (VALUES ('EU', 10)) AS t(region, total)");
        lakeServesSnapshots();

        OperatorResult result = run("SELECT region, total FROM Sales_report");

        assertThat(ProductColumn.fromJson(result.data().get("columns")))
                .extracting(ProductColumn::name).containsExactly("region", "total");
        assertThat(ProductColumn.fromJson(result.data().get("columns")).get(1).jdbcType())
                .isEqualTo(java.sql.Types.INTEGER);
    }

    @Test
    @DisplayName("every qualification a SQL client might send resolves to the same table")
    void qualifiedNamesResolve() throws Exception {
        materializedProduct(TENANT, "Sales report",
                "SELECT * FROM (VALUES ('EU', 10)) AS t(region, total)");
        lakeServesSnapshots();

        for (String from : List.of("Sales_report", "products.Sales_report",
                "aran.products.Sales_report", "\"products\".\"Sales_report\"")) {
            OperatorResult result = run("SELECT region FROM " + from);

            assertThat(result.success()).as("FROM %s: %s", from, result.errorMessage()).isTrue();
            assertThat(rowsOf(Path.of(result.data().get("path")))).containsExactly(List.of("EU"));
        }
    }

    @Test
    @DisplayName("a join across two products is one statement, not two queries and a client-side merge")
    void joinsAcrossProducts() throws Exception {
        materializedProduct(TENANT, "sales",
                "SELECT * FROM (VALUES ('EU', 10), ('US', 4)) AS t(region, total)");
        materializedProduct(TENANT, "regions",
                "SELECT * FROM (VALUES ('EU', 'Europe'), ('US', 'America')) AS t(code, label)");
        lakeServesSnapshots();

        OperatorResult result = run(
                "SELECT r.label, s.total FROM sales s JOIN regions r ON s.region = r.code"
                        + " ORDER BY s.total DESC");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(result.data().get("products").split(",")).containsExactlyInAnyOrder("sales", "regions");
        assertThat(rowsOf(Path.of(result.data().get("path"))))
                .containsExactly(List.of("Europe", 10), List.of("America", 4));
    }

    @Test
    @DisplayName("only the snapshots the statement names are fetched")
    void fetchesOnlyReferencedSnapshots() throws Exception {
        materializedProduct(TENANT, "sales",
                "SELECT * FROM (VALUES ('EU', 10)) AS t(region, total)");
        materializedProduct(TENANT, "untouched", "SELECT 1 AS n");
        lakeServesSnapshots();

        run("SELECT region FROM sales");

        verify(lake, times(1)).downloadSnapshotTo(any(UUID.class), anyString(), any(Path.class));
    }

    // ── the registry table ───────────────────────────────────────────────────

    @Test
    @DisplayName("the registry table reports every product's freshness, materialized or not")
    void registryTableIsQueryable() throws Exception {
        materializedProduct(TENANT, "sales", "SELECT 1 AS n");
        registry.add(DataProduct.draft(UUID.randomUUID(), UUID.fromString(TENANT),
                UUID.randomUUID(), "never run", null, "SELECT Paper.title FROM Paper",
                UUID.randomUUID(), null, T0.plusSeconds(1)));

        OperatorResult result = run(
                "SELECT product, status, last_refreshed, row_count FROM _products ORDER BY product");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(rowsOf(Path.of(result.data().get("path")))).containsExactly(
                List.of("never_run", "DRAFT"),          // nulls are dropped by rowsOf
                List.of("sales", "READY",
                        java.sql.Timestamp.valueOf("2026-01-01 10:00:00"), 2L));
    }

    @Test
    @DisplayName("the registry's access column separates virtual (never refreshed) from "
            + "materialized (real last_refreshed/row_count) products")
    void registryTableSurfacesAccessMode() throws Exception {
        materializedProduct(TENANT, "sales", "SELECT 1 AS n");
        virtualProduct(TENANT, "papers", "SELECT Paper.year FROM Paper");

        OperatorResult result = run(
                "SELECT product, status, access, last_refreshed, row_count FROM _products"
                        + " ORDER BY product");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(rowsOf(Path.of(result.data().get("path")))).containsExactly(
                // A virtual product never materializes: last_refreshed/row_count stay NULL
                // (dropped by rowsOf), never a stale or fabricated value.
                List.of("papers", "READY", "VIRTUAL"),
                List.of("sales", "READY", "MATERIALIZED",
                        java.sql.Timestamp.valueOf("2026-01-01 10:00:00"), 2L));
    }

    @Test
    @DisplayName("the registry table exists for a tenant with no products at all")
    void registryTableWithoutProducts() {
        OperatorResult result = run("SELECT product FROM _products");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(result.data().get("rowCount")).isEqualTo("0");
    }

    // ── tenancy and guards ───────────────────────────────────────────────────

    @Test
    @DisplayName("another tenant's product is not a table here")
    void productsAreTenantScoped() throws Exception {
        materializedProduct(OTHER_TENANT, "theirs", "SELECT 1 AS n");
        lakeServesSnapshots();

        OperatorResult result = run(TENANT, "SELECT n FROM theirs");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_SQL");
    }

    @Test
    @DisplayName("a file-reading function is refused before anything is registered")
    void refusesForbiddenFunctions() {
        OperatorResult result = run("SELECT * FROM read_csv_auto('/etc/passwd')");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_SQL");
        assertThat(result.errorMessage()).contains("read_csv_auto");
    }

    @Test
    @DisplayName("a statement that is not a SELECT is refused")
    void refusesWrites() {
        assertThat(run("DROP TABLE sales").errorCode()).isEqualTo("INVALID_SQL");
        assertThat(run("SELECT 1; DROP TABLE sales").errorCode()).isEqualTo("INVALID_SQL");
    }

    @Test
    @DisplayName("an unknown table is answered with the names that exist")
    void unknownTableNamesTheAlternatives() throws Exception {
        materializedProduct(TENANT, "Sales report", "SELECT 1 AS n");

        OperatorResult result = run("SELECT * FROM sales_report_2024");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
                .contains("Sales_report")
                .contains("_products");
    }

    @Test
    @DisplayName("a tenant with nothing materialized is told that, not just 'no such table'")
    void emptyRegistryExplainsItself() {
        OperatorResult result = run("SELECT * FROM anything");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("has been materialized yet");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Path writeParquet(String query) throws Exception {
        Path target = Files.createTempFile("aran-product-fixture-", ".parquet");
        Files.deleteIfExists(target);
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("COPY (" + query + ") TO '" + target.toAbsolutePath() + "' (FORMAT PARQUET)");
        }
        return target;
    }

    /** The rows of a parquet file, nulls dropped, so a row reads as what it actually carries. */
    private static List<List<Object>> rowsOf(Path parquet) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        List<List<Object>> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT * FROM read_parquet('" + parquet.toAbsolutePath() + "')")) {
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    if (value != null) {
                        row.add(value);
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }
}

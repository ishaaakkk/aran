package edu.upc.dtak.aran.app.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.MissingResultsException;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Track B: the JDBC endpoint's {@code Meta}, against a stub query engine.
 *
 * <p>The stub writes a real parquet file with DuckDB rather than returning canned rows, because
 * everything interesting downstream — column types, value coercion, paging — is derived from that
 * file. A fake that skipped it would only test the fake.
 */
class AranMetaTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @TempDir
    Path tempDir;

    private StubQueryEngine engine;
    private AtomicReference<AuthenticatedUser> caller;
    private List<edu.upc.dtak.aran.core.domain.DataProduct> registry;
    private AranMeta meta;
    private Meta.ConnectionHandle connection;

    @BeforeEach
    void setUp() {
        engine = new StubQueryEngine(tempDir);
        caller = new AtomicReference<>(user(TENANT));
        registry = new ArrayList<>();
        // A UsageService over a repository that records nowhere: metering must never be able to
        // fail the thing it measures, and these tests are about the JDBC path, not the meter.
        meta = new AranMeta(new OperatorOrchestrator(List.of(engine)), resolverReturning(caller),
                new ObjectMapper(),
                new edu.upc.dtak.aran.core.service.UsageService(new NoopUsageEvents()),
                uncappedLicenses(),
                who -> edu.upc.dtak.aran.core.domain.ProductCatalog.of(registry.stream()
                        .filter(p -> p.tenantId().equals(who.tenantId())).toList()));
        connection = new Meta.ConnectionHandle("conn-1");
    }

    /** A materialized product of {@link #TENANT}, with the columns its snapshot would describe. */
    private edu.upc.dtak.aran.core.domain.DataProduct product(
            String name, String oql, java.time.Instant refreshedAt,
            edu.upc.dtak.aran.core.domain.ProductColumn... columns) {
        return edu.upc.dtak.aran.core.domain.DataProduct
                .draft(UUID.randomUUID(), TENANT, UUID.randomUUID(), name, null, oql,
                        UUID.randomUUID(), USER, refreshedAt)
                .materialized("snap-" + name, 42L,
                        edu.upc.dtak.aran.core.domain.ProductColumn.toJson(List.of(columns)),
                        refreshedAt);
    }

    /**
     * A VIRTUAL product of {@link #TENANT} (Phase 5), READY with no snapshot: its output columns
     * come from definition-time OQL planning, exactly as {@link #product} fakes a MATERIALIZED
     * one's parquet-described columns — the JDBC surface reads both the same way, from
     * {@code output_schema}, never the lake.
     */
    private edu.upc.dtak.aran.core.domain.DataProduct virtualProduct(
            String name, String oql, java.time.Instant createdAt,
            edu.upc.dtak.aran.core.domain.ProductColumn... columns) {
        return edu.upc.dtak.aran.core.domain.DataProduct
                .draft(UUID.randomUUID(), TENANT, UUID.randomUUID(), name, null, oql,
                        UUID.randomUUID(), USER, createdAt,
                        edu.upc.dtak.aran.core.domain.ProductAccessMode.VIRTUAL)
                .virtualReady(edu.upc.dtak.aran.core.domain.ProductColumn.toJson(List.of(columns)));
    }

    private static AuthenticatedUser user(UUID tenant) {
        return new AuthenticatedUser(USER, "subject", tenant, TenantRole.OWNER);
    }

    /** {@link CurrentUserResolver} is a class, so the seam is a subclass rather than an interface. */
    private static CurrentUserResolver resolverReturning(AtomicReference<AuthenticatedUser> ref) {
        return new CurrentUserResolver(null) {
            @Override public AuthenticatedUser require() {
                return ref.get();
            }
        };
    }

    private Meta.ExecuteResult run(String oql) throws NoSuchStatementException {
        return run(oql, 100);
    }

    private Meta.ExecuteResult run(String oql, int firstFrameRows) throws NoSuchStatementException {
        Meta.StatementHandle handle = meta.createStatement(connection);
        return meta.prepareAndExecute(handle, oql, -1, firstFrameRows, null);
    }

    private static Meta.StatementHandle handleOf(Meta.ExecuteResult result) {
        Meta.MetaResultSet rs = result.resultSets.get(0);
        return new Meta.StatementHandle(rs.connectionId, rs.statementId, rs.signature);
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> rowsOf(Meta.Frame frame) {
        List<List<Object>> rows = new ArrayList<>();
        frame.rows.forEach(row -> rows.add((List<Object>) row));
        return rows;
    }

    @Nested
    @DisplayName("running an OQL statement")
    class Running {

        @Test
        @DisplayName("hands the query text to the engine's uncapped materialize operation")
        void sendsOqlToTheEngine() throws Exception {
            run("SELECT Paper.title FROM Paper");

            assertThat(engine.lastRequest.operation()).isEqualTo("materialize-oql");
            assertThat(engine.lastRequest.parameters()).containsEntry("oql", "SELECT Paper.title FROM Paper");
            assertThat(engine.lastRequest.parameters()).containsEntry("format", "PARQUET");
            assertThat(engine.lastRequest.tenantId()).isEqualTo(TENANT.toString());
        }

        @Test
        @DisplayName("reports the result's real column types, not a guess")
        void reportsColumnTypes() throws Exception {
            Meta.Signature signature = run("SELECT Paper.title FROM Paper").resultSets.get(0).signature;

            List<ColumnMetaData> columns = signature.columns;
            assertThat(columns).extracting(c -> c.label)
                    .containsExactly("title", "year", "score", "rating", "reviewed");
            assertThat(columns).extracting(c -> c.type.id)
                    .containsExactly(Types.VARCHAR, Types.INTEGER, Types.DOUBLE, Types.DECIMAL,
                            Types.BOOLEAN);
        }

        @Test
        @DisplayName("a DECIMAL column is described, not fatal — Avatica's own class table omits it")
        void decimalColumnsAreDescribable() throws Exception {
            Meta.Signature signature = run("SELECT Paper.rating FROM Paper").resultSets.get(0).signature;

            ColumnMetaData rating = signature.columns.get(3);
            assertThat(rating.type.rep).isEqualTo(ColumnMetaData.Rep.NUMBER);
            assertThat(rowsOf(run("SELECT Paper.rating FROM Paper").resultSets.get(0).firstFrame).get(0).get(3))
                    .isEqualTo(new java.math.BigDecimal("1.5"));
        }

        @Test
        @DisplayName("carries the OQL, not the generated SQL, as the statement's text")
        void signatureCarriesOql() throws Exception {
            Meta.Signature signature = run("SELECT Paper.title FROM Paper").resultSets.get(0).signature;

            assertThat(signature.sql).isEqualTo("SELECT Paper.title FROM Paper");
        }

        @Test
        @DisplayName("returns the rows of the materialised result")
        void returnsRows() throws Exception {
            Meta.Frame frame = run("SELECT Paper.title FROM Paper").resultSets.get(0).firstFrame;

            List<List<Object>> rows = rowsOf(frame);
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0))
                    .containsExactly("Bell", 1964, 0.5, new java.math.BigDecimal("1.5"), true);
            assertThat(frame.done).isTrue();
        }

        @Test
        @DisplayName("an empty query is refused before the engine is asked")
        void refusesEmptyQuery() {
            Meta.StatementHandle handle = meta.createStatement(connection);

            assertThatThrownBy(() -> meta.prepareAndExecute(handle, "   ", -1, 100, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
            assertThat(engine.lastRequest).isNull();
        }

        @Test
        @DisplayName("a rejected query surfaces the engine's own message")
        void surfacesEngineErrors() {
            engine.failWith("Unknown property: Paper.titel");
            Meta.StatementHandle handle = meta.createStatement(connection);

            assertThatThrownBy(() -> meta.prepareAndExecute(handle, "SELECT Paper.titel FROM Paper", -1, 100, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown property: Paper.titel");
        }
    }

    @Nested
    @DisplayName("paging through a result")
    class Paging {

        @Test
        @DisplayName("a partial frame ends the result set")
        void partialFrameIsDone() throws Exception {
            Meta.Frame first = run("SELECT Paper.title FROM Paper", 2).resultSets.get(0).firstFrame;

            assertThat(rowsOf(first)).hasSize(2);
            assertThat(first.done).isFalse();
        }

        @Test
        @DisplayName("fetch continues where the first frame stopped")
        void fetchContinues() throws Exception {
            Meta.ExecuteResult result = run("SELECT Paper.title FROM Paper", 2);

            Meta.Frame next = meta.fetch(handleOf(result), 2, 10);

            assertThat(next.offset).isEqualTo(2);
            assertThat(rowsOf(next)).hasSize(1);
            assertThat(next.done).isTrue();
        }

        @Test
        @DisplayName("an exactly-full frame is followed by an empty one that ends the result")
        void exactlyFullFrameNeedsOneMoreFetch() throws Exception {
            Meta.ExecuteResult result = run("SELECT Paper.title FROM Paper", 3);
            Meta.Frame first = result.resultSets.get(0).firstFrame;
            assertThat(first.done).isFalse();

            Meta.Frame next = meta.fetch(handleOf(result), 3, 10);

            assertThat(rowsOf(next)).isEmpty();
            assertThat(next.done).isTrue();
        }

        @Test
        @DisplayName("a fetch that tries to rewind is a missing result, not silently wrong rows")
        void refusesToRewind() throws Exception {
            Meta.ExecuteResult result = run("SELECT Paper.title FROM Paper", 2);

            assertThatThrownBy(() -> meta.fetch(handleOf(result), 0, 10))
                    .isInstanceOf(MissingResultsException.class);
        }

        @Test
        @DisplayName("fetching an unknown statement is a missing result")
        void unknownStatement() {
            Meta.StatementHandle unknown = new Meta.StatementHandle("conn-1", 999, null);

            assertThatThrownBy(() -> meta.fetch(unknown, 0, 10))
                    .isInstanceOf(MissingResultsException.class);
        }
    }

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("another tenant cannot fetch from a statement it did not open")
        void fetchIsTenantChecked() throws Exception {
            Meta.ExecuteResult result = run("SELECT Paper.title FROM Paper", 2);
            caller.set(user(OTHER_TENANT));

            assertThatThrownBy(() -> meta.fetch(handleOf(result), 2, 10))
                    .isInstanceOf(NoSuchStatementException.class);
        }
    }

    @Nested
    @DisplayName("statement lifetime")
    class Lifetime {

        @Test
        @DisplayName("closing a statement deletes the result file it was reading")
        void closeDeletesTheResultFile() throws Exception {
            Meta.ExecuteResult result = run("SELECT Paper.title FROM Paper", 1);
            Path written = engine.lastWrittenFile;
            assertThat(written).exists();

            meta.closeStatement(handleOf(result));

            assertThat(written).doesNotExist();
            assertThat(meta.openStatementCount()).isZero();
        }

        @Test
        @DisplayName("closing a connection closes the statements opened on it")
        void closeConnectionClosesStatements() throws Exception {
            run("SELECT Paper.title FROM Paper", 1);
            run("SELECT Paper.year FROM Paper", 1);
            assertThat(meta.openStatementCount()).isEqualTo(2);

            meta.closeConnection(connection);

            assertThat(meta.openStatementCount()).isZero();
        }

        @Test
        @DisplayName("re-running a statement handle does not orphan the previous result file")
        void reuseDeletesThePreviousFile() throws Exception {
            Meta.StatementHandle handle = meta.createStatement(connection);
            meta.prepareAndExecute(handle, "SELECT Paper.title FROM Paper", -1, 1, null);
            Path first = engine.lastWrittenFile;

            meta.prepareAndExecute(handle, "SELECT Paper.year FROM Paper", -1, 1, null);

            assertThat(first).doesNotExist();
            assertThat(engine.lastWrittenFile).exists();
            assertThat(meta.openStatementCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("JDBC catalog")
    class Catalog {

        @Test
        @DisplayName("lists integrated classes as tables of the graph schema, by the name OQL uses")
        void listsClassesAsTables() {
            Meta.MetaResultSet tables = meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.GRAPH_SCHEMA), null, null);

            List<List<Object>> rows = rowsOf(tables.firstFrame);
            assertThat(rows).extracting(row -> row.get(2))
                    .containsExactly("Paper", "Researcher");
            assertThat(rows.get(0)).contains("aran", "graph", "TABLE");
        }

        @Test
        @DisplayName("filters tables by the JDBC name pattern")
        void filtersTables() {
            Meta.MetaResultSet tables = meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.GRAPH_SCHEMA), Meta.Pat.of("Pap%"), null);

            assertThat(rowsOf(tables.firstFrame)).hasSize(1);
        }

        @Test
        @DisplayName("lists a class's global properties as columns, deduplicated across sources")
        void listsProperties() {
            Meta.MetaResultSet columns = meta.getColumns(connection, null,
                    Meta.Pat.of(AranMeta.GRAPH_SCHEMA), Meta.Pat.of("Paper"), null);

            assertThat(rowsOf(columns.firstFrame))
                    .extracting(row -> row.get(3))
                    .containsExactly("title", "year", "authorId");
        }

        @Test
        @DisplayName("a catalog the engine cannot produce is an empty branch, not a broken connection")
        void catalogFailureIsEmpty() {
            engine.failWith("No active global schema");

            Meta.MetaResultSet tables = meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.GRAPH_SCHEMA), null, null);

            assertThat(rowsOf(tables.firstFrame)).isEmpty();
        }

        @Test
        @DisplayName("reports one catalog and all three schemas")
        void reportsCatalogAndSchema() {
            assertThat(rowsOf(meta.getCatalogs(connection).firstFrame)).hasSize(1);
            assertThat(rowsOf(meta.getSchemas(connection, null, null).firstFrame))
                    .extracting(row -> row.get(0))
                    .containsExactly("graph", "products", "sources");
            assertThat(rowsOf(meta.getTableTypes(connection).firstFrame)).hasSize(1);
        }

        @Test
        @DisplayName("names itself Aran so a client does not apply another database's rules")
        void identifiesItself() {
            assertThat(meta.getDatabaseProperties(connection))
                    .containsEntry(Meta.DatabaseProperty.GET_DATABASE_PRODUCT_NAME, "Aran");
        }
    }

    @Nested
    @DisplayName("the graph / products split")
    class Split {

        private final java.time.Instant refreshed =
                java.time.Instant.parse("2026-08-01T09:30:00Z");

        @Test
        @DisplayName("a materialized product is a table of the products schema")
        void productsAreTables() {
            registry.add(product("Papers per year", "SELECT Paper.year FROM Paper", refreshed,
                    new edu.upc.dtak.aran.core.domain.ProductColumn("year", "INTEGER", Types.INTEGER)));

            List<List<Object>> rows = rowsOf(meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA), null, null).firstFrame);

            assertThat(rows).extracting(row -> row.get(2))
                    .containsExactly("Papers_per_year", AranMeta.PRODUCT_REGISTRY_TABLE);
            assertThat(rows.get(0).get(1)).isEqualTo("products");
        }

        @Test
        @DisplayName("a product's remarks carry its freshness and the OQL behind it")
        void productRemarksCarryProvenance() {
            registry.add(product("Sales", "SELECT Order.total FROM Order", refreshed));

            List<List<Object>> rows = rowsOf(meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA), Meta.Pat.of("Sales"), null).firstFrame);

            assertThat((String) rows.get(0).get(4))
                    .contains("READY")
                    // Regression (Phase 5 Task 4): a MATERIALIZED product's remarks are unchanged in
                    // substance, plus now name the access mode explicitly.
                    .contains("access MATERIALIZED")
                    .contains("last refreshed 2026-08-01T09:30:00Z")
                    .contains("OQL: SELECT Order.total FROM Order");
        }

        @Test
        @DisplayName("a virtual product's remarks say VIRTUAL and always fresh, not \"last refreshed never\"")
        void virtualProductRemarksAreHonest() {
            registry.add(virtualProduct("Papers per year", "SELECT Paper.year FROM Paper", refreshed));

            List<List<Object>> rows = rowsOf(meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA), Meta.Pat.of("Papers_per_year"), null)
                    .firstFrame);

            assertThat((String) rows.get(0).get(4))
                    .contains("access VIRTUAL")
                    .contains("always fresh")
                    .doesNotContain("last refreshed")
                    .contains("OQL: SELECT Paper.year FROM Paper");
        }

        @Test
        @DisplayName("a virtual product is a table; its columns describe from output_schema, no "
                + "query-engine interaction — the \"products never reads the lake\" invariant, extended")
        void virtualProductIsDescribedWithNoLakeRead() {
            registry.add(virtualProduct("Papers per year", "SELECT Paper.year FROM Paper", refreshed,
                    new edu.upc.dtak.aran.core.domain.ProductColumn("year", "INTEGER", Types.INTEGER)));

            List<List<Object>> tables = rowsOf(meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA), null, null).firstFrame);
            assertThat(tables).extracting(row -> row.get(2))
                    .contains("Papers_per_year", AranMeta.PRODUCT_REGISTRY_TABLE);

            List<List<Object>> columns = rowsOf(meta.getColumns(connection, null,
                    Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA), Meta.Pat.of("Papers_per_year"), null)
                    .firstFrame);
            assertThat(columns).extracting(row -> row.get(3)).containsExactly("year");
            assertThat(columns.get(0).get(4)).isEqualTo(Types.INTEGER);
            assertThat(columns.get(0).get(5)).isEqualTo("INTEGER");

            // No lake read AND no plan-and-run: describing a virtual product must not touch the
            // query engine at all (it never even reaches "catalog", let alone materialize-oql) —
            // the stub records every call it receives, and none happened.
            assertThat(engine.lastRequest)
                    .as("products-only metadata must never call the query engine").isNull();
        }

        @Test
        @DisplayName("a product's columns are the types its snapshot was described with, not VARCHAR")
        void productColumnsCarryRealTypes() {
            registry.add(product("Sales", "SELECT Order.total FROM Order", refreshed,
                    new edu.upc.dtak.aran.core.domain.ProductColumn("region", "VARCHAR", Types.VARCHAR),
                    new edu.upc.dtak.aran.core.domain.ProductColumn("total", "BIGINT", Types.BIGINT)));

            List<List<Object>> rows = rowsOf(meta.getColumns(connection, null,
                    Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA), Meta.Pat.of("Sales"), null).firstFrame);

            assertThat(rows).extracting(row -> row.get(3)).containsExactly("region", "total");
            assertThat(rows.get(1).get(4)).isEqualTo(Types.BIGINT);
            assertThat(rows.get(1).get(5)).isEqualTo("BIGINT");
        }

        @Test
        @DisplayName("the registry table is listed even for a tenant with no products")
        void registryTableAlwaysExists() {
            List<List<Object>> rows = rowsOf(meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA), null, null).firstFrame);

            assertThat(rows).extracting(row -> row.get(2))
                    .containsExactly(AranMeta.PRODUCT_REGISTRY_TABLE);
            // Pinned column set (Phase 5 Task 4 added "access" — additive, ordinal position 3):
            // a client introspecting _products via getColumns must see it, not just a bare
            // SELECT * — this list is that contract.
            assertThat(rowsOf(meta.getColumns(connection, null, Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA),
                    Meta.Pat.of(AranMeta.PRODUCT_REGISTRY_TABLE), null).firstFrame))
                    .extracting(row -> row.get(3))
                    .containsExactly("product", "status", "access", "last_refreshed", "row_count",
                            "project_id", "oql");
        }

        @Test
        @DisplayName("another tenant's products are not in this tenant's tree")
        void productsAreTenantScoped() {
            registry.add(edu.upc.dtak.aran.core.domain.DataProduct
                    .draft(UUID.randomUUID(), OTHER_TENANT, UUID.randomUUID(), "Theirs", null,
                            "SELECT Paper.title FROM Paper", UUID.randomUUID(), USER, refreshed)
                    .materialized("snap-theirs", 1L, null, refreshed));

            assertThat(rowsOf(meta.getTables(connection, null, Meta.Pat.of(AranMeta.PRODUCTS_SCHEMA),
                    Meta.Pat.of("Theirs"), null).firstFrame)).isEmpty();
        }

        @Test
        @DisplayName("a statement on the default connection is OQL against the mediated schema")
        void defaultsToTheGraph() throws Exception {
            run("SELECT Paper.title FROM Paper");

            assertThat(engine.lastRequest.operation()).isEqualTo("materialize-oql");
            assertThat(engine.lastRequest.parameters()).containsKey("oql");
        }

        @Test
        @DisplayName("setting the schema to products sends SQL down the passthrough path")
        void schemaChoosesTheProductsPath() throws Exception {
            meta.connectionSync(connection, new org.apache.calcite.avatica.ConnectionPropertiesImpl()
                    .setSchema(AranMeta.PRODUCTS_SCHEMA));

            run("SELECT region, total FROM sales");

            assertThat(engine.lastRequest.operation()).isEqualTo("materialize-product-sql");
            assertThat(engine.lastRequest.parameters())
                    .containsEntry("sql", "SELECT region, total FROM sales")
                    .doesNotContainKey("oql");
        }

        @Test
        @DisplayName("the schema a client set is what connectionSync reports back")
        void connectionSyncReportsTheSchema() {
            Meta.ConnectionProperties synced = meta.connectionSync(connection,
                    new org.apache.calcite.avatica.ConnectionPropertiesImpl()
                            .setSchema(AranMeta.PRODUCTS_SCHEMA));

            assertThat(synced.getSchema()).isEqualTo("products");
            assertThat(synced.getCatalog()).isEqualTo("aran");
        }

        @Test
        @DisplayName("a schema Aran does not have leaves the connection where it was")
        void unknownSchemaIsIgnored() {
            meta.connectionSync(connection, new org.apache.calcite.avatica.ConnectionPropertiesImpl()
                    .setSchema("public"));

            assertThat(meta.schemaOf("conn-1")).isEqualTo("graph");
        }

        @Test
        @DisplayName("the schema can be set from the connection string")
        void schemaFromConnectionProperties() {
            meta.openConnection(new Meta.ConnectionHandle("conn-2"), Map.of("schema", "products"));

            assertThat(meta.schemaOf("conn-2")).isEqualTo("products");
        }

        @Test
        @DisplayName("a products-qualified statement takes the passthrough path from any schema")
        void qualificationOverridesTheSchema() throws Exception {
            run("SELECT * FROM products.sales");

            assertThat(engine.lastRequest.operation()).isEqualTo("materialize-product-sql");
        }

        @Test
        @DisplayName("routing reads the schema reference, not the leading keyword — both are SELECT")
        void routingRules() {
            // The mediated path is the default, and only an explicit products reference or an
            // explicit schema takes a statement off it.
            assertThat(AranMeta.routeOf("SELECT Paper.title FROM Paper", "graph"))
                    .isEqualTo(AranMeta.Route.GRAPH);
            assertThat(AranMeta.routeOf("SELECT * FROM sales", "products"))
                    .isEqualTo(AranMeta.Route.PRODUCTS);
            assertThat(AranMeta.routeOf("SELECT * FROM aran.products.\"sales\"", "graph"))
                    .isEqualTo(AranMeta.Route.PRODUCTS);
            assertThat(AranMeta.routeOf("SELECT * FROM PRODUCTS.sales", "graph"))
                    .isEqualTo(AranMeta.Route.PRODUCTS);
            // A word that merely ends in "products", and prose that happens to end a sentence
            // with it, are not references.
            assertThat(AranMeta.routeOf("SELECT Sub.x FROM subproducts.x", "graph"))
                    .isEqualTo(AranMeta.Route.GRAPH);
            assertThat(AranMeta.routeOf(
                    "SELECT Paper.title FROM Paper WHERE Paper.note = 'see products. it explains'", "graph"))
                    .isEqualTo(AranMeta.Route.GRAPH);
            // S10-2: a graph class genuinely called `products` reads as a products reference —
            // the settled text match, documented rather than guessed around…
            assertThat(AranMeta.routeOf("SELECT products.price FROM products", "graph"))
                    .isEqualTo(AranMeta.Route.PRODUCTS);
            // …and the graph. qualifier is the escape hatch, from any connection schema.
            assertThat(AranMeta.routeOf("SELECT graph.products.price FROM graph.products", "products"))
                    .isEqualTo(AranMeta.Route.GRAPH);
            assertThat(AranMeta.routeOf(
                    "SELECT aran.graph.sources.x FROM aran.graph.sources", "sources"))
                    .isEqualTo(AranMeta.Route.GRAPH);
            assertThat(AranMeta.routeOf("SELECT * FROM \"aran\".\"graph\".\"Paper\"", "products"))
                    .isEqualTo(AranMeta.Route.GRAPH);
        }

        @Test
        @DisplayName("graph. beside products. or sources. is rejected like the products/sources mix")
        void graphMixedWithAnotherSchemaIsRejected() {
            assertThatThrownBy(() -> AranMeta.routeOf(
                    "SELECT * FROM graph.Paper JOIN products.sales ON 1=1", "graph"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("graph")
                    .hasMessageContaining("products")
                    .hasMessageContaining("graph.products");
            assertThatThrownBy(() -> AranMeta.routeOf(
                    "SELECT graph.Paper.title FROM graph.Paper WHERE sources.acm.x = 1", "graph"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sources");
            // Qualifying only some mentions of a class called products is still a mix: the
            // unqualified one reads as the products schema.
            assertThatThrownBy(() -> AranMeta.routeOf(
                    "SELECT products.price FROM graph.products", "graph"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("graph.products");
        }

        @Test
        @DisplayName("the graph. qualifier is removed before OQL sees it, and nothing else is")
        void graphQualifierIsStripped() {
            assertThat(AranMeta.withoutGraphQualifiers(
                    "SELECT graph.products.price FROM graph.products WHERE graph.products.price > 5"))
                    .isEqualTo("SELECT products.price FROM products WHERE products.price > 5");
            assertThat(AranMeta.withoutGraphQualifiers(
                    "SELECT COUNT(aran.graph.Paper.title) FROM aran.graph.Paper -[author]-> graph.Researcher"))
                    .isEqualTo("SELECT COUNT(Paper.title) FROM Paper -[author]-> Researcher");
            assertThat(AranMeta.withoutGraphQualifiers(
                    "SELECT \"graph\".\"Order\".\"first-name\" FROM \"graph\".\"Order\""))
                    .isEqualTo("SELECT \"Order\".\"first-name\" FROM \"Order\"");
            // A string literal is data: the same characters inside one are left alone.
            assertThat(AranMeta.withoutGraphQualifiers(
                    "SELECT graph.Paper.note FROM graph.Paper WHERE graph.Paper.note = 'FROM graph.x.y'"))
                    .isEqualTo("SELECT Paper.note FROM Paper WHERE Paper.note = 'FROM graph.x.y'");
        }

        @Test
        @DisplayName("qualifier-shaped text inside a quoted identifier or a literal is not a qualifier")
        void quotedTextIsNotAQualifier() {
            // A source column literally named graph.x.y is one name, not a graph reference.
            assertThat(AranMeta.routeOf("SELECT \"graph.x.y\" FROM sources.t", "graph"))
                    .isEqualTo(AranMeta.Route.SOURCES);
            assertThat(AranMeta.routeOf("SELECT \"my graph.x.y\" FROM sources.t", "graph"))
                    .isEqualTo(AranMeta.Route.SOURCES);
            assertThat(AranMeta.withoutGraphQualifiers("SELECT \"graph.x.y\" FROM t"))
                    .isEqualTo("SELECT \"graph.x.y\" FROM t");
            // Beside a graph qualifier, a literal spelling products.x is data, not a mix.
            String withLiteral = "SELECT graph.P.note FROM graph.P WHERE graph.P.note = 'products.x'";
            assertThat(AranMeta.routeOf(withLiteral, "graph")).isEqualTo(AranMeta.Route.GRAPH);
            assertThat(AranMeta.withoutGraphQualifiers(withLiteral))
                    .isEqualTo("SELECT P.note FROM P WHERE P.note = 'products.x'");
            // An apostrophe inside a quoted identifier does not open a literal that hides the rest.
            assertThat(AranMeta.withoutGraphQualifiers(
                    "SELECT graph.P.\"it's\" FROM graph.P WHERE graph.P.\"it's\" = 'x'"))
                    .isEqualTo("SELECT P.\"it's\" FROM P WHERE P.\"it's\" = 'x'");
        }

        @Test
        @DisplayName("a class called graph keeps working unqualified, and qualified reads as itself")
        void aClassCalledGraph() {
            assertThat(AranMeta.routeOf("SELECT graph.x FROM graph", "graph"))
                    .isEqualTo(AranMeta.Route.GRAPH);
            assertThat(AranMeta.withoutGraphQualifiers("SELECT graph.x FROM graph WHERE graph.x = 1"))
                    .isEqualTo("SELECT graph.x FROM graph WHERE graph.x = 1");
            assertThat(AranMeta.withoutGraphQualifiers("SELECT graph.graph.x FROM graph.graph"))
                    .isEqualTo("SELECT graph.x FROM graph");
        }

        @Test
        @DisplayName("a graph-qualified statement reaches materialize-oql without its qualifier")
        void graphQualifiedStatementRunsAsOql() throws Exception {
            meta.openConnection(connection, Map.of("schema", "products"));

            run("SELECT graph.products.price FROM graph.products");

            assertThat(engine.lastRequest.operation()).isEqualTo("materialize-oql");
            assertThat(engine.lastRequest.parameters())
                    .containsEntry("oql", "SELECT products.price FROM products");
        }

        @Test
        @DisplayName("closing a connection forgets the schema it was on")
        void closingForgetsTheSchema() {
            meta.openConnection(new Meta.ConnectionHandle("conn-3"), Map.of("schema", "products"));

            meta.closeConnection(new Meta.ConnectionHandle("conn-3"));

            assertThat(meta.schemaOf("conn-3")).isEqualTo("graph");
        }
    }

    @Nested
    @DisplayName("pattern matching")
    class Patterns {

        @Test
        @DisplayName("a null or % pattern matches everything")
        void nullMatchesEverything() {
            assertThat(AranMeta.matches(null, "anything")).isTrue();
            assertThat(AranMeta.matches(Meta.Pat.of("%"), "anything")).isTrue();
        }

        @Test
        @DisplayName("% spans characters and _ matches exactly one")
        void wildcards() {
            assertThat(AranMeta.matches(Meta.Pat.of("Pa%"), "Paper")).isTrue();
            assertThat(AranMeta.matches(Meta.Pat.of("Pape_"), "Paper")).isTrue();
            assertThat(AranMeta.matches(Meta.Pat.of("Pape_"), "Papers")).isFalse();
            assertThat(AranMeta.matches(Meta.Pat.of("Researcher"), "Paper")).isFalse();
        }

        @Test
        @DisplayName("a regex metacharacter in a name is matched literally")
        void metacharactersAreLiteral() {
            assertThat(AranMeta.matches(Meta.Pat.of("a.b"), "axb")).isFalse();
            assertThat(AranMeta.matches(Meta.Pat.of("a.b"), "a.b")).isTrue();
        }
    }

    @Nested
    @DisplayName("the read-only contract")
    class ReadOnly {

        @Test
        @DisplayName("batches are refused rather than silently ignored")
        void refusesBatches() {
            Meta.StatementHandle handle = meta.createStatement(connection);

            assertThatThrownBy(() -> meta.prepareAndExecuteBatch(handle, List.of("INSERT …")))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("read-only");
        }

        @Test
        @DisplayName("prepared statements are refused with the reason")
        void refusesPrepare() {
            assertThatThrownBy(() -> meta.prepare(connection, "SELECT Paper.title FROM Paper", -1))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("parameter markers");
        }

        @Test
        @DisplayName("commit on an autocommit client is a no-op, not a failure")
        void commitIsHarmless() {
            meta.commit(connection);
            meta.rollback(connection);
        }

        @Test
        @DisplayName("a lost result set cannot be re-created")
        void syncResultsIsFalse() {
            assertThat(meta.syncResults(new Meta.StatementHandle("conn-1", 1, null), null, 0))
                    .isFalse();
        }
    }

    /**
     * A query engine that writes a real parquet file, so the cursor under test reads real
     * columns, types and values.
     */
    @Nested
    @DisplayName("the sources tier")
    class Sources {

        @Test
        @DisplayName("every source class is a table of the sources schema, once")
        void sourcesAreTables() {
            List<List<Object>> rows = rowsOf(meta.getTables(connection, null,
                    Meta.Pat.of(AranMeta.SOURCES_SCHEMA), null, null).firstFrame);

            assertThat(rows).extracting(row -> row.get(2))
                    .containsExactly("acm", "dblp", "researchers");
            assertThat(rows.get(0).get(1)).isEqualTo("sources");
            assertThat((String) rows.get(0).get(4)).contains("ds1");
        }

        @Test
        @DisplayName("a source table's columns are its own local names, with the IRI as remarks")
        void sourceColumnsAreSourceNative() {
            List<List<Object>> rows = rowsOf(meta.getColumns(connection, null,
                    Meta.Pat.of(AranMeta.SOURCES_SCHEMA), Meta.Pat.of("acm"), null).firstFrame);

            assertThat(rows).extracting(row -> row.get(3))
                    .containsExactly("author_id", "title", "year");
            assertThat(rows).extracting(row -> row.get(8))
                    .containsExactly("http://aran/src/ds1/acm/author_id",
                            "http://aran/src/ds1/acm/title", "http://aran/src/ds1/acm/year");
        }

        @Test
        @DisplayName("a sources-qualified statement goes to materialize-source-sql as plain SQL")
        void sourcesQualifierRoutes() throws Exception {
            run("SELECT * FROM sources.acm");

            assertThat(engine.lastRequest.operation()).isEqualTo("materialize-source-sql");
            assertThat(engine.lastRequest.parameters())
                    .containsEntry("sql", "SELECT * FROM sources.acm");
        }

        @Test
        @DisplayName("a connection on the sources schema sends unqualified SQL there")
        void connectionSchemaRoutes() throws Exception {
            meta.openConnection(connection, Map.of("schema", "sources"));

            run("SELECT title FROM acm");

            assertThat(engine.lastRequest.operation()).isEqualTo("materialize-source-sql");
        }

        @Test
        @DisplayName("a statement naming both sources. and products. is rejected, not guessed at")
        void bothQualifiersRejected() {
            assertThatThrownBy(() -> run("SELECT * FROM sources.acm JOIN products.sales ON 1=1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sources")
                    .hasMessageContaining("products");
        }

        @Test
        @DisplayName("the accepted join surfaces as an imported key on the left table")
        void importedKeysFromJoinProperties() {
            List<List<Object>> rows = rowsOf(meta.getImportedKeys(connection, null,
                    AranMeta.SOURCES_SCHEMA, "acm").firstFrame);

            assertThat(rows).hasSize(1);
            List<Object> key = rows.get(0);
            assertThat(key.get(2)).isEqualTo("researchers");   // PKTABLE_NAME
            assertThat(key.get(3)).isEqualTo("id");            // PKCOLUMN_NAME
            assertThat(key.get(6)).isEqualTo("acm");           // FKTABLE_NAME
            assertThat(key.get(7)).isEqualTo("author_id");     // FKCOLUMN_NAME
            assertThat(key.get(11)).isEqualTo("hasAuthor");    // FK_NAME
        }

        @Test
        @DisplayName("the same join is an exported key of the right table")
        void exportedKeysMirrorImported() {
            List<List<Object>> rows = rowsOf(meta.getExportedKeys(connection, null,
                    AranMeta.SOURCES_SCHEMA, "researchers").firstFrame);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get(6)).isEqualTo("acm");
        }
    }

    private static final class StubQueryEngine implements Operator {

        private static final String CATALOG_JSON = """
                {
                  "empty": false,
                  "classes": [
                    {"iri": "http://aran/Paper", "name": "Paper", "sources": [
                      {"datasetId": "ds1", "sourceTable": "acm", "columns": [
                        {"global": "http://aran/Paper#title", "globalName": "title",
                         "source": "http://aran/src/ds1/acm/title"},
                        {"global": "http://aran/Paper#year", "globalName": "year",
                         "source": "http://aran/src/ds1/acm/year"},
                        {"global": "http://aran/Paper#authorId", "globalName": "authorId",
                         "source": "http://aran/src/ds1/acm/author_id"}]},
                      {"datasetId": "ds2", "sourceTable": "dblp", "columns": [
                        {"global": "http://aran/Paper#title", "globalName": "title",
                         "source": "http://aran/src/ds2/dblp/title"}]}
                    ]},
                    {"iri": "http://aran/Researcher", "name": "Researcher", "sources": [
                      {"datasetId": "ds3", "sourceTable": "researchers", "columns": [
                        {"global": "http://aran/Researcher#name", "globalName": "name",
                         "source": "http://aran/src/ds3/researchers/name"},
                        {"global": "http://aran/Researcher#id", "globalName": "id",
                         "source": "http://aran/src/ds3/researchers/id"}]}
                    ]}
                  ],
                  "joinProperties": [
                    {"iri": "http://aran/hasAuthor", "name": "hasAuthor",
                     "leftClass": "Paper", "rightClass": "Researcher",
                     "leftCol": "authorId", "rightCol": "id"}
                  ]
                }
                """;

        private final Path directory;
        private String failure;
        OperatorRequest lastRequest;
        Path lastWrittenFile;

        StubQueryEngine(Path directory) {
            this.directory = directory;
        }

        void failWith(String message) {
            this.failure = message;
        }

        @Override public String getOperatorId() {
            return "query-engine";
        }

        @Override public String getDisplayName() {
            return "Stub Query Engine";
        }

        @Override public OperatorResult execute(OperatorRequest request) {
            lastRequest = request;
            if (failure != null) {
                return OperatorResult.failure("STUB", failure);
            }
            if ("catalog".equals(request.operation())) {
                return OperatorResult.success(Map.of("status", "ok", "catalog", CATALOG_JSON));
            }
            try {
                lastWrittenFile = writeParquet();
                Map<String, String> data = new LinkedHashMap<>();
                data.put("path", lastWrittenFile.toString());
                data.put("rowCount", "3");
                return OperatorResult.success(data);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private Path writeParquet() throws Exception {
            Path file = Files.createTempFile(directory, "result", ".parquet");
            Files.deleteIfExists(file);
            Class.forName("org.duckdb.DuckDBDriver");
            try (Connection duckdb = DriverManager.getConnection("jdbc:duckdb:");
                 Statement statement = duckdb.createStatement()) {
                // `rating` is deliberately a bare decimal literal: DuckDB types it DECIMAL, which
                // is the case Avatica's class-based column helper cannot represent.
                statement.execute("""
                        COPY (
                          SELECT * FROM (VALUES
                            ('Bell', 1964, 0.5::DOUBLE, 1.5, true),
                            ('GAV', 1997, 1.5::DOUBLE, 2.5, false),
                            ('Aran', 2026, 2.5::DOUBLE, 3.5, true)
                          ) AS t(title, year, score, rating, reviewed)
                        ) TO '%s' (FORMAT PARQUET)""".formatted(file));
            }
            return file;
        }
    }

    /** Records nothing. UsageService swallows failures anyway; this keeps the tests silent. */
    private static final class NoopUsageEvents
            implements edu.upc.dtak.aran.core.domain.UsageEventRepository {
        @Override public edu.upc.dtak.aran.core.domain.UsageEvent save(
                edu.upc.dtak.aran.core.domain.UsageEvent event) {
            return event;
        }

        @Override public List<UsagePerUser> usagePerUser(UUID tenantId, java.time.Instant since) {
            return List.of();
        }

        @Override public UsageTotals totals(UUID tenantId, java.time.Instant since) {
            return new UsageTotals(0, 0, 0, 0, 0);
        }
    }

    /**
     * A licensing layer that permits everything.
     *
     * <p>Local rather than shared with aran-core's fakes, which are not on this module's test
     * classpath. Explicitly uncapped rather than a mock: a mock answering 0 for a ceiling would
     * make every JDBC test fail as though the JDBC path were broken.
     */
    private static edu.upc.dtak.aran.core.licensing.LicenseService uncappedLicenses() {
        edu.upc.dtak.aran.core.licensing.LicenseProvider provider = tenantId ->
                java.util.Optional.of(new edu.upc.dtak.aran.core.domain.LicenseEntitlements(
                        tenantId,
                        edu.upc.dtak.aran.core.domain.LicensePlan.ENTERPRISE,
                        edu.upc.dtak.aran.core.domain.LicenseStatus.ACTIVE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE,
                        null, null,
                        java.util.Set.of(), null));

        edu.upc.dtak.aran.core.licensing.UsageCounter counter =
                new edu.upc.dtak.aran.core.licensing.UsageCounter() {
                    @Override public long countUsers(UUID tenantId) {
                        return 0;
                    }

                    @Override public long countProjects(UUID tenantId) {
                        return 0;
                    }

                    @Override public long estimatedStorageBytes(UUID tenantId) {
                        return 0;
                    }

                    @Override public long egressBytesSince(UUID tenantId, java.time.Instant since) {
                        return 0;
                    }
                };
        return new edu.upc.dtak.aran.core.licensing.LicenseService(provider, counter);
    }
}

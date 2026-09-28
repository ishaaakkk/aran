package edu.upc.dtak.aran.operators.queryengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Virtual data products execute by expanding their stored OQL through the GAV pipeline at read
 * time (Phase 5, Task 3). These run against a real DuckDB over a real snapshot the test writes:
 * the whole claim — that a {@code products.<virtual>} read returns exactly the rows its OQL would,
 * with live denial-constraint enforcement — is only asserted by actually executing it.
 *
 * <p>The regression half matters just as much: a purely-materialized statement must take today's
 * plain-SQL-over-parquet path untouched, proven here by {@code verifyNoInteractions} on the
 * catalog the virtual path fetches.
 */
class VirtualProductExecutionTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String NX = NS;
    private static final String PAPER = NS + "integration/Paper";
    private static final String P_ID = NS + "integration/id";
    private static final String P_TITLE = NS + "integration/title";
    private static final String P_YEAR = NS + "integration/year";

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    private final GraphStore graphStore = mock(GraphStore.class);
    private final LakeClient lake = mock(LakeClient.class);
    private final IntegrationModelRepository modelRepository = mock(IntegrationModelRepository.class);
    private final StubProductRepository products = new StubProductRepository();
    private final List<Path> cleanup = new ArrayList<>();
    /** snapshotId → the parquet a test wrote for a MATERIALIZED product. */
    private final Map<String, Path> matSnapshots = new java.util.HashMap<>();

    private QueryEngineOperator operator() {
        return new QueryEngineOperator(
                graphStore, catalogOperator, lake, new ObjectMapper(),
                modelRepository,
                mock(DatasetRepository.class),
                mock(DataRepositoryRepository.class),
                // Caching disabled: this is about execution, not caching.
                new SnapshotCache(lake, "", 0L),
                products);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Path p : cleanup) {
            Files.deleteIfExists(p);
        }
        for (Path p : matSnapshots.values()) {
            Files.deleteIfExists(p);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * One integrated class {@code Paper} (id, title, year) fed by dataset {@code ds1}, whose
     * snapshot is the ND-JSON {@code snapshotJson}. {@code enforced} are the enforced denial
     * constraints, written against the class's local property names, that the query engine applies.
     *
     * <p>The class has three properties on purpose: the products below project only {@code title}
     * and {@code year}, so the OQL is a genuine projection whose output columns are the local
     * names {@code title}/{@code year} — not a full-class selection, which Calcite collapses to
     * {@code SELECT *} and whose columns keep their full-IRI names (that shape is exercised, and
     * exactly matches a materialized product from the same OQL, by the identity assertion which
     * compares row values, not names).
     */
    private void wirePaperCatalog(String snapshotJson, List<ModelConstraint> enforced) {
        MappingCatalog cat = new MappingCatalog(Map.of(
                PAPER, new MappingCatalog.IntegratedClass(PAPER, List.of(
                        new MappingCatalog.SourceView("ds1", "SELECT * FROM ${SNAPSHOT}",
                                Map.of(P_ID, "id", P_TITLE, "title", P_YEAR, "year"), 3L)))),
                List.of(), List.of(), "v1");
        when(catalogOperator.getCatalog(any(TenantContext.class))).thenReturn(cat);

        Model m = ModelFactory.createDefaultModel();
        Resource c = m.createResource("urn:snap:ds1");
        m.add(c, m.createProperty(NX + "datasetId"), "ds1");
        m.add(c, m.createProperty(NX + "snapshotId"), "snap1");
        m.add(c, m.createProperty(NX + "format"), "json");
        when(graphStore.query(any(TenantContext.class), anyString())).thenReturn(m);

        when(lake.downloadSnapshotTo(any(), eq("snap1"), any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(2);
            Files.writeString(target, snapshotJson);
            return target.toAbsolutePath().toString();
        });

        when(modelRepository.findEnforcedConstraints(any(TenantContext.class))).thenReturn(enforced);
    }

    private ModelConstraint enforcedConstraint(String expression) {
        return new ModelConstraint(UUID.randomUUID(), UUID.fromString(TENANT), UUID.randomUUID(),
                PAPER, expression, true, T0, "MANUAL");
    }

    /** Register a READY VIRTUAL product (no snapshot) whose stored OQL is {@code oql}. */
    private DataProduct virtualProduct(String name, String oql) {
        DataProduct product = DataProduct.draft(UUID.randomUUID(), UUID.fromString(TENANT),
                        UUID.randomUUID(), name, null, oql, UUID.randomUUID(), null, T0,
                        ProductAccessMode.VIRTUAL)
                .virtualReady(null);
        products.add(product);
        return product;
    }

    /** Register a MATERIALIZED product whose snapshot holds {@code query}'s result. */
    private DataProduct materializedProduct(String name, String query) throws Exception {
        String snapshotId = "matsnap-" + name;
        matSnapshots.put(snapshotId, writeParquet(query));
        DataProduct product = DataProduct.draft(UUID.randomUUID(), UUID.fromString(TENANT),
                        UUID.randomUUID(), name, null, "SELECT Paper.title FROM Paper",
                        UUID.randomUUID(), null, T0)
                .materialized(snapshotId, 1L, null, T0);
        products.add(product);
        when(lake.downloadSnapshotTo(any(), eq(snapshotId), any(Path.class))).thenAnswer(inv -> {
            Files.copy(matSnapshots.get(snapshotId), inv.getArgument(2, Path.class),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "sha";
        });
        return product;
    }

    private OperatorResult productSql(String sql) {
        OperatorResult result = operator().execute(new OperatorRequest(
                TENANT, null, "materialize-product-sql", Map.of("sql", sql)));
        if (result.success() && result.data().get("path") != null) {
            cleanup.add(Path.of(result.data().get("path")));
        }
        return result;
    }

    private OperatorResult materializeOql(String oql) {
        OperatorResult result = operator().execute(new OperatorRequest(
                TENANT, null, "materialize-oql", Map.of("oql", oql)));
        if (result.success() && result.data().get("path") != null) {
            cleanup.add(Path.of(result.data().get("path")));
        }
        return result;
    }

    // ── the four required categories ───────────────────────────────────────────

    @Test
    @DisplayName("identity: a virtual product returns exactly the rows its OQL returns directly")
    void virtualReturnsTheSameRowsAsItsOql() throws Exception {
        // The last row has no year, so a null cell is carried end to end — the comparison below
        // preserves nulls in position, so it is exact enough to catch a column-misalignment
        // regression (a swap of title/year would turn ["d", null] into [null, "d"]).
        wirePaperCatalog(
                "{\"id\":1,\"title\":\"a\",\"year\":2001}\n{\"id\":2,\"title\":\"b\",\"year\":2002}\n"
                        + "{\"id\":3,\"title\":\"c\",\"year\":2003}\n{\"id\":4,\"title\":\"d\"}\n",
                List.of());
        String oql = "SELECT Paper.title, Paper.year FROM Paper";
        virtualProduct("papers", oql);

        // The OQL run directly (materialize-oql), and the same OQL reached through a virtual product.
        OperatorResult direct = materializeOql(oql);
        assertThat(direct.success()).as("%s", direct.errorMessage()).isTrue();
        OperatorResult virtual = productSql("SELECT * FROM papers");
        assertThat(virtual.success()).as("%s", virtual.errorMessage()).isTrue();

        // Null-preserving, so ["d", null] and [null, "d"] are distinguishable — an exact identity.
        assertThat(rowsPreservingNulls(Path.of(virtual.data().get("path"))))
                .containsExactlyInAnyOrderElementsOf(
                        rowsPreservingNulls(Path.of(direct.data().get("path"))));
        assertThat(virtual.data().get("products")).isEqualTo("papers");
    }

    @Test
    @DisplayName("composition: client SQL wrapping a virtual product filters and projects it")
    void clientSqlComposesOverAVirtualProduct() throws Exception {
        wirePaperCatalog(
                "{\"id\":1,\"title\":\"a\",\"year\":2001}\n{\"id\":2,\"title\":\"b\",\"year\":2002}\n"
                        + "{\"id\":3,\"title\":\"c\",\"year\":2003}\n",
                List.of());
        virtualProduct("papers", "SELECT Paper.title, Paper.year FROM Paper");

        OperatorResult result = productSql(
                "SELECT title FROM papers WHERE year >= 2002 ORDER BY title");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(rowsOf(Path.of(result.data().get("path"))))
                .containsExactly(List.of("b"), List.of("c"));
    }

    @Test
    @DisplayName("live DC enforcement: an enforced denial constraint on the class excludes rows "
            + "from a virtual-product read")
    void enforcedConstraintIsHonoredByAVirtualRead() throws Exception {
        wirePaperCatalog(
                "{\"id\":1,\"title\":\"old\",\"year\":1999}\n{\"id\":2,\"title\":\"new\",\"year\":2001}\n"
                        + "{\"id\":3,\"title\":\"newer\",\"year\":2003}\n",
                // Only papers from 2000 on are admissible; 1999 must never surface.
                List.of(enforcedConstraint("year >= 2000")));
        virtualProduct("papers", "SELECT Paper.title, Paper.year FROM Paper");

        OperatorResult result = productSql("SELECT title FROM papers ORDER BY title");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        List<List<Object>> rows = rowsOf(Path.of(result.data().get("path")));
        assertThat(rows).containsExactly(List.of("new"), List.of("newer"));
        assertThat(rows).as("the constraint-excluded 1999 row must not surface")
                .doesNotContain(List.of("old"));
    }

    @Test
    @DisplayName("regression: a purely-materialized statement takes today's path — the virtual "
            + "context (catalog, buildDdl) is never built")
    void materializedStatementNeverBuildsTheVirtualContext() throws Exception {
        materializedProduct("sales", "SELECT * FROM (VALUES ('EU', 10)) AS t(region, total)");

        OperatorResult result = productSql("SELECT region, total FROM sales");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(rowsOf(Path.of(result.data().get("path")))).containsExactly(List.of("EU", 10));
        // Byte-identical path: a materialized-only statement must not fetch the mapping catalog,
        // plan any OQL, or build the graph-tier view context.
        verifyNoInteractions(catalogOperator, graphStore, modelRepository);
    }

    @Test
    @DisplayName("a statement over a virtual product of local snapshots runs behind the filesystem lockdown")
    void virtualReadOverLocalSnapshotsIsLockedDown() throws Exception {
        wirePaperCatalog("{\"id\":1,\"title\":\"a\",\"year\":2001}\n", List.of());
        virtualProduct("papers", "SELECT Paper.title, Paper.year FROM Paper");

        // Nothing federated is in play, so the products tier's second layer (T3.5) must be on:
        // the connection itself reports whether external access survived.
        OperatorResult result = productSql(
                "SELECT current_setting('enable_external_access') AS ext FROM papers");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(rowsOf(Path.of(result.data().get("path")))).containsExactly(List.of(false));
    }

    // ── optional: virtual + materialized in one connection ─────────────────────

    @Test
    @DisplayName("a materialized product and a virtual product join in one statement")
    void materializedAndVirtualJoinInOneConnection() throws Exception {
        wirePaperCatalog(
                "{\"id\":1,\"title\":\"a\",\"year\":2001}\n{\"id\":2,\"title\":\"b\",\"year\":2002}\n",
                List.of());
        virtualProduct("papers", "SELECT Paper.title, Paper.year FROM Paper");
        materializedProduct("labels",
                "SELECT * FROM (VALUES (2001, 'first'), (2002, 'second')) AS t(yr, label)");

        OperatorResult result = productSql(
                "SELECT p.title, l.label FROM papers p JOIN labels l ON p.year = l.yr"
                        + " ORDER BY p.title");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(rowsOf(Path.of(result.data().get("path"))))
                .containsExactly(List.of("a", "first"), List.of("b", "second"));
        assertThat(result.data().get("products").split(","))
                .containsExactlyInAnyOrder("papers", "labels");
    }

    // ── failure isolation: a broken virtual product must not poison unrelated statements ────────

    @Test
    @DisplayName("a broken virtual product does NOT fail a statement that only word-matches its "
            + "name (not a real reference)")
    void brokenVirtualDoesNotPoisonAWordMatch() throws Exception {
        wirePaperCatalog(
                "{\"id\":1,\"title\":\"a\",\"year\":2001}\n",
                List.of());
        // Unplannable: the class Ghost is not in the active catalog, so its OQL does not expand.
        virtualProduct("ghosts", "SELECT Ghost.title FROM Ghost");
        materializedProduct("sales", "SELECT * FROM (VALUES ('EU', 10), ('US', 4)) AS t(region, total)");

        // "ghosts" appears only in a string literal — a word-match, not a FROM. It must not matter
        // that its expansion is broken.
        OperatorResult result = productSql(
                "SELECT region FROM sales WHERE region <> 'ghosts' ORDER BY region");

        assertThat(result.success()).as("%s", result.errorMessage()).isTrue();
        assertThat(rowsOf(Path.of(result.data().get("path"))))
                .containsExactly(List.of("EU"), List.of("US"));
        // The broken product was skipped, so it is not reported as a table the statement read.
        assertThat(result.data().get("products")).isEqualTo("sales");
    }

    @Test
    @DisplayName("a statement that actually FROMs a broken virtual product fails with a clear error "
            + "naming that product")
    void selectingFromABrokenVirtualFailsClearly() throws Exception {
        wirePaperCatalog(
                "{\"id\":1,\"title\":\"a\",\"year\":2001}\n",
                List.of());
        virtualProduct("ghosts", "SELECT Ghost.title FROM Ghost");

        OperatorResult result = productSql("SELECT * FROM ghosts");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_SQL");
        assertThat(result.errorMessage())
                .contains("ghosts")
                .containsIgnoringCase("no longer expands")
                .containsIgnoringCase("Recreate");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Path writeParquet(String query) throws Exception {
        Path target = Files.createTempFile("aran-virtual-fixture-", ".parquet");
        Files.deleteIfExists(target);
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("COPY (" + query + ") TO '" + target.toAbsolutePath() + "' (FORMAT PARQUET)");
        }
        return target;
    }

    /**
     * The rows of a parquet file with null cells preserved in position — so a row that carries a
     * null stays distinguishable from one that dropped it. Used by the identity assertion, where an
     * exact match (including where the nulls fall) is the whole point; {@link #rowsOf} below drops
     * nulls, which is fine for the value-only assertions but would hide a column swap.
     */
    private static List<List<Object>> rowsPreservingNulls(Path parquet) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        List<List<Object>> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT * FROM read_parquet('" + parquet.toAbsolutePath() + "')")) {
            while (rs.next()) {
                List<Object> row = new ArrayList<>();   // permits null cells, unlike List.of
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
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

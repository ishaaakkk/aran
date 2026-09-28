package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.source.RefreshPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Physical placement end to end through {@code rewrite-oql} (Option A): a table imported from a
 * federated relational repository — the one data-complete dual-access shape — is planned live or
 * from its lake copy by cost, gated by its declared refresh policy. Offline like every rewrite
 * test: nothing executes, so the placement decision, its legality gate, the payload and the
 * access annotation are all observable without a database.
 *
 * <p>The headline behavior change this pins: before placement, such a dataset was served
 * FEDERATED unconditionally ({@code SourceAccess} ignores the copy for relational). With an
 * {@code Every}-maintained copy, a full scan now chooses MATERIALIZED — and a selective query
 * still chooses FEDERATED, so the flip is the plan's, not a new fixed default.
 */
@DisplayName("physical placement — dual-access relational dataset, chosen by cost, gated by policy")
class RelationalDualAccessPlacementTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String PEOPLE = NS + "integration/People";
    private static final String P_ID = NS + "integration/id";
    private static final String P_NAME = NS + "integration/name";
    private static final UUID DS = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeee02");
    private static final UUID REPO = UUID.fromString("11112222-3333-4444-5555-666677778802");

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    private final GraphStore graphStore = mock(GraphStore.class);
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryRepository dataRepositoryRepository = mock(DataRepositoryRepository.class);
    private final DatasetRefreshRepository refreshRepository = mock(DatasetRefreshRepository.class);
    private final LakeClient lake = mock(LakeClient.class);

    private QueryEngineOperator operator(PlacementConfig config) {
        return new QueryEngineOperator(
                graphStore, catalogOperator, lake, new ObjectMapper(),
                mock(IntegrationModelRepository.class), datasetRepository, dataRepositoryRepository,
                new SnapshotCache(lake, "", 0L), mock(DataProductRepository.class),
                Optional.empty(), WrapperOutputCache.disabled(), config, PlannerConfig.defaults(),
                Optional.of(refreshRepository));
    }

    private static ColumnProfile idProfile(long cardinality) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), "id", 0, "VARCHAR",
                cardinality, null, 0.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    /** 100k-row class over the dual-access dataset; id has NDV 10k for a sharp equality. */
    private static MappingCatalog peopleCatalog() {
        MappingCatalog.SourceView sv = new MappingCatalog.SourceView(
                DS.toString(), "SELECT * FROM ${SNAPSHOT}", Map.of(P_ID, "id", P_NAME, "name"),
                100_000L, List.of(idProfile(10_000L)));
        return new MappingCatalog(
                Map.of(PEOPLE, new MappingCatalog.IntegratedClass(PEOPLE, List.of(sv))),
                List.of(), List.of(), "v1");
    }

    /** The graph facts snapshotMap reads: a REAL snapshot id — the copy exists. */
    private Model graphWithSnapshot() {
        Model m = ModelFactory.createDefaultModel();
        Resource c = m.createResource("http://ex/src/People");
        c.addProperty(m.createProperty(NS + "datasetId"), DS.toString());
        c.addProperty(m.createProperty(NS + "snapshotId"), "snap-people-1");
        c.addProperty(m.createProperty(NS + "format"), "PARQUET");
        return m;
    }

    private void wireDualAccess(RefreshPolicy policy, Instant lastRefreshAt) {
        wireDualAccess(policy, lastRefreshAt, "snap-people-1");
    }

    private void wireDualAccess(RefreshPolicy policy, Instant lastRefreshAt, String rowSnapshotId) {
        when(catalogOperator.getCatalog(any())).thenReturn(peopleCatalog());
        when(graphStore.query(any(), any())).thenReturn(graphWithSnapshot());
        when(datasetRepository.findById(any(), eq(DS)))
                .thenReturn(Optional.of(new Dataset(DS, REPO, "people", "", rowSnapshotId, List.of())));
        when(dataRepositoryRepository.findById(any(), eq(REPO)))
                .thenReturn(Optional.of(new RelationalDatabaseRepository(
                        REPO, UUID.randomUUID(), UUID.randomUUID(), "warehouse", "",
                        "POSTGRESQL", "db-host", 5432, "db", "user", "pw", "public", true)));
        // Built BEFORE the when(): a throwing constructor inside a stubbing leaves Mockito's
        // stubbing unfinished, and Every-policy rows must carry a nextRunAt (domain invariant).
        Optional<DatasetRefresh> refresh = policy == null ? Optional.empty()
                : Optional.of(new DatasetRefresh(
                        DS, UUID.fromString(TENANT), REPO, UUID.randomUUID(), UUID.randomUUID(),
                        "people", policy, lastRefreshAt,
                        policy instanceof RefreshPolicy.Every
                                ? Instant.now().plus(Duration.ofDays(365)) : null,
                        null));
        when(refreshRepository.findByDataset(any(), eq(DS))).thenReturn(refresh);
    }

    private Map<String, String> rewrite(QueryEngineOperator op, String oql) {
        OperatorResult result = op.execute(new OperatorRequest(
                TENANT, "rewrite-oql", Map.of("oql", oql)));
        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        return result.data();
    }

    @Test
    @DisplayName("Every-maintained copy: full scan → MATERIALIZED, selective query → FEDERATED")
    void costFlipsWithSelectivity() {
        wireDualAccess(new RefreshPolicy.Every(Duration.ofHours(1)), Instant.now());
        QueryEngineOperator op = operator(PlacementConfig.defaults());

        Map<String, String> fullScan = rewrite(op, "SELECT People.name");
        assertThat(fullScan.get("planShapeText")).contains("access=MATERIALIZED");
        assertThat(fullScan.get("placement"))
                .contains("\"chosen\":\"MATERIALIZED\"")
                .contains("snapshot wins on cost");

        Map<String, String> selective = rewrite(op, "SELECT People.name WHERE People.id = 'k42'");
        assertThat(selective.get("planShapeText")).contains("access=FEDERATED");
        assertThat(selective.get("placement"))
                .contains("\"chosen\":\"FEDERATED\"")
                .contains("live wins on cost");
    }

    @Test
    @DisplayName("no refresh row at all → snapshot leaf illegal → live serves, reason in the payload")
    void oneOffImportStaysLive() {
        wireDualAccess(null, null); // no refresh row at all — a one-off import
        Map<String, String> data = rewrite(operator(PlacementConfig.defaults()),
                "SELECT People.name");
        assertThat(data.get("planShapeText")).contains("access=FEDERATED");
        assertThat(data.get("placement"))
                .contains("\"chosen\":\"FEDERATED\"")
                .contains("snapshot leaf illegal");
    }

    @Test
    @DisplayName("an explicit Manual policy is equally illegal — no declared staleness bound")
    void manualCopyStaysLive() {
        wireDualAccess(new RefreshPolicy.Manual(), Instant.now());
        Map<String, String> data = rewrite(operator(PlacementConfig.defaults()),
                "SELECT People.name");
        assertThat(data.get("planShapeText")).contains("access=FEDERATED");
        assertThat(data.get("placement")).contains("no Every refresh policy");
    }

    @Test
    @DisplayName("Every declared but never successfully refreshed → illegal: the copy's age is unbounded")
    void neverRefreshedEveryStaysLive() {
        wireDualAccess(new RefreshPolicy.Every(Duration.ofHours(1)), null);
        Map<String, String> data = rewrite(operator(PlacementConfig.defaults()),
                "SELECT People.name");
        assertThat(data.get("planShapeText")).contains("access=FEDERATED");
        assertThat(data.get("placement")).contains("no successful refresh recorded");
    }

    @Test
    @DisplayName("a copy stale beyond the grace bound is illegal — the broken promise forces live")
    void staleCopyForcedLive() {
        wireDualAccess(new RefreshPolicy.Every(Duration.ofMinutes(5)),
                Instant.now().minus(Duration.ofHours(3)));
        Map<String, String> data = rewrite(operator(PlacementConfig.defaults()),
                "SELECT People.name");
        assertThat(data.get("planShapeText")).contains("access=FEDERATED");
        assertThat(data.get("placement")).contains("copy stale");
    }

    @Test
    @DisplayName("run-oql: a forced MATERIALIZED executes against the dataset ROW's snapshot — the artifact the gate measured")
    void forcedMaterializedServesTheRowCopy() throws Exception {
        // The critical-finding fix under test: the graph pins snap-people-1, but the maintained
        // (row) copy is row-snap-2 — execution must fetch and serve row-snap-2, or the legality
        // gate bounds an artifact nobody reads. The pre-fix code would have downloaded the graph
        // pin; the pre-placement default (live postgres_scan) would have dialed a database this
        // test does not have — so three rows coming back is proof the override drove execution.
        wireDualAccess(new RefreshPolicy.Every(Duration.ofHours(1)), Instant.now(), "row-snap-2");
        java.nio.file.Path parquet = java.nio.file.Files.createTempFile("placement-row-snap", ".parquet");
        java.nio.file.Files.deleteIfExists(parquet);
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('a','Ada'), ('b','Bob'), ('c','Cy'))"
                    + " t(id, name)) TO '" + parquet.toAbsolutePath() + "' (FORMAT PARQUET)");
        }
        java.util.List<String> fetched = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(lake.downloadSnapshotTo(any(), any(), any())).thenAnswer(inv -> {
            fetched.add(inv.getArgument(1));
            java.nio.file.Files.copy(parquet, inv.getArgument(2),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "ok";
        });

        OperatorResult result = operator(PlacementConfig.defaults()).execute(new OperatorRequest(
                TENANT, "run-oql", Map.of("oql", "SELECT People.name")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(fetched).containsExactly("row-snap-2"); // the ROW copy, not the graph pin
        String payload = result.data().get("result");
        assertThat(payload).contains("\"rowCount\":3")
                .contains("\"placement\"")
                .contains("\"chosen\":\"MATERIALIZED\"")
                .contains("access=MATERIALIZED");
        java.nio.file.Files.deleteIfExists(parquet);
    }

    @Test
    @DisplayName("materialize-oql honors placement too: a product materializes from the row copy")
    void materializeOqlHonorsPlacement() throws Exception {
        // The third wired call site (product materialization) — untested until the coverage
        // audit. Same proof shape as run-oql: the graph pins a different id, so rows coming
        // back means the override drove the resolver.
        wireDualAccess(new RefreshPolicy.Every(Duration.ofHours(1)), Instant.now(), "row-snap-2");
        java.nio.file.Path parquet = java.nio.file.Files.createTempFile("placement-mat", ".parquet");
        java.nio.file.Files.deleteIfExists(parquet);
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('a','Ada'), ('b','Bob'), ('c','Cy'))"
                    + " t(id, name)) TO '" + parquet.toAbsolutePath() + "' (FORMAT PARQUET)");
        }
        java.util.List<String> fetched = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(lake.downloadSnapshotTo(any(), any(), any())).thenAnswer(inv -> {
            fetched.add(inv.getArgument(1));
            java.nio.file.Files.copy(parquet, inv.getArgument(2),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "ok";
        });

        OperatorResult result = operator(PlacementConfig.defaults()).execute(new OperatorRequest(
                TENANT, "materialize-oql", Map.of("oql", "SELECT People.name")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(fetched).containsExactly("row-snap-2");
        assertThat(result.data().get("rowCount")).isEqualTo("3");
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(result.data().get("path")));
        java.nio.file.Files.deleteIfExists(parquet);
    }

    @Test
    @DisplayName("placement disabled → no payload, pre-placement behavior (FEDERATED) stands")
    void disabledKeepsToday() {
        wireDualAccess(new RefreshPolicy.Every(Duration.ofHours(1)), Instant.now());
        Map<String, String> data = rewrite(
                operator(new PlacementConfig(false, 25.0, 1.0, 2.0)), "SELECT People.name");
        assertThat(data).doesNotContainKey("placement");
        assertThat(data.get("planShapeText")).contains("access=FEDERATED");
    }
}

package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
 * What the query engine owes a data product's two consumers (audit A12):
 *
 * <ul>
 *   <li>a VIRTUAL product's read ({@code run-oql} on {@code DataProductService.preview}) honours the
 *       caller's {@code limit} — an MCP agent asking for 20 rows was handed the engine's whole
 *       1,000-row page (V37 gap 3, J6-F11);</li>
 *   <li>a MATERIALIZED build ({@code materialize-oql}) reports the schema version it planned
 *       against, so the product can record what its rows mean (UI-OD12).</li>
 * </ul>
 *
 * Same offline harness as {@code ObjectStorageMaterializedQueryTest}: one class over a lake
 * snapshot the mocked lake writes as parquet.
 */
@DisplayName("Product reads: run-oql honours limit, materialize-oql reports its schema version")
class ProductReadContractTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String PEOPLE = NS + "integration/People";
    private static final String P_ID = NS + "integration/id";
    private static final String P_NAME = NS + "integration/name";
    private static final UUID DS = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REPO = UUID.fromString("11112222-3333-4444-5555-666677778888");
    private static final String SNAPSHOT_ID = "snap-read-contract-1";
    private static final String ACTIVE_VERSION = "5b1e6c0a-0000-4000-8000-000000000003";

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    private final GraphStore graphStore = mock(GraphStore.class);
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryRepository dataRepositoryRepository = mock(DataRepositoryRepository.class);
    private final LakeClient lake = mock(LakeClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private QueryEngineOperator operator() throws Exception {
        MappingCatalog.SourceView sv = new MappingCatalog.SourceView(
                DS.toString(), "SELECT * FROM ${SNAPSHOT}", Map.of(P_ID, "id", P_NAME, "name"), 5L);
        MappingCatalog cat = new MappingCatalog(
                Map.of(PEOPLE, new MappingCatalog.IntegratedClass(PEOPLE, List.of(sv))),
                List.of(), List.of(), ACTIVE_VERSION);
        when(catalogOperator.getCatalog(any())).thenReturn(cat);

        Model g = ModelFactory.createDefaultModel();
        Resource c = g.createResource("urn:src:People");
        g.add(c, g.createProperty(NS + "datasetId"), DS.toString());
        g.add(c, g.createProperty(NS + "snapshotId"), SNAPSHOT_ID);
        g.add(c, g.createProperty(NS + "format"), "PARQUET");
        g.add(c, g.createProperty(NS + "iterator"), "people.parquet");
        when(graphStore.query(any(), anyString())).thenReturn(g);

        when(datasetRepository.findById(any(), eq(DS)))
                .thenReturn(Optional.of(new Dataset(DS, REPO, "People", "", SNAPSHOT_ID)));
        DataRepository repo = new ObjectStorageRepository(REPO, UUID.randomUUID(), UUID.randomUUID(),
                "lake", "", "MINIO", "http://127.0.0.1:1", "bucket", "AKID", "SEKRET", "us-east-1");
        when(dataRepositoryRepository.findById(any(), eq(REPO))).thenReturn(Optional.of(repo));

        when(lake.downloadSnapshotTo(any(), eq(SNAPSHOT_ID), any())).thenAnswer(inv -> {
            Path target = inv.getArgument(2);
            Files.deleteIfExists(target);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                 Statement s = conn.createStatement()) {
                s.execute("COPY (SELECT * FROM (VALUES (1, 'ada'), (2, 'bea'), (3, 'cy'), (4, 'dot'), (5, 'eve'))"
                        + " AS t(id, name)) TO '" + target.toAbsolutePath() + "' (FORMAT parquet)");
            }
            return "application/vnd.apache.parquet";
        });

        return new QueryEngineOperator(
                graphStore, catalogOperator, lake, mapper,
                mock(IntegrationModelRepository.class), datasetRepository, dataRepositoryRepository,
                new SnapshotCache(lake, "", 0L), mock(DataProductRepository.class));
    }

    private JsonNode runOql(Map<String, String> params) throws Exception {
        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "run-oql", params));
        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        return mapper.readTree(result.data().get("result"));
    }

    @Test
    @DisplayName("run-oql with a limit returns that many rows and says more exist")
    void runOqlHonoursTheLimit() throws Exception {
        JsonNode payload = runOql(Map.of("oql", "SELECT People.name FROM People", "limit", "2"));

        assertThat(payload.get("rows")).hasSize(2);
        assertThat(payload.get("rowCount").asInt()).isEqualTo(2);
        assertThat(payload.get("hasMore").asBoolean()).isTrue();
        assertThat(payload.get("pageSize").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a limit at or above the result returns every row and no hasMore")
    void aLimitAboveTheResultReturnsEverything() throws Exception {
        JsonNode payload = runOql(Map.of("oql", "SELECT People.name FROM People", "limit", "20"));

        assertThat(payload.get("rows")).hasSize(5);
        assertThat(payload.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("without a limit run-oql keeps the 1,000-row page — the workbench's contract is unchanged")
    void noLimitKeepsTheEnginePage() throws Exception {
        JsonNode payload = runOql(Map.of("oql", "SELECT People.name FROM People"));

        assertThat(payload.get("rows")).hasSize(5);
        assertThat(payload.get("pageSize").asInt()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a limit above the engine page is clamped to it, and a nonsense one is refused")
    void limitIsClampedAndValidated() throws Exception {
        assertThat(runOql(Map.of("oql", "SELECT People.name FROM People", "limit", "50000"))
                .get("pageSize").asInt()).isEqualTo(1000);

        OperatorResult bad = operator().execute(new OperatorRequest(TENANT, null, "run-oql",
                Map.of("oql", "SELECT People.name FROM People", "limit", "lots")));
        assertThat(bad.success()).isFalse();
        assertThat(bad.errorCode()).isEqualTo("INVALID_LIMIT");
    }

    @Test
    @DisplayName("materialize-oql reports the schema version it planned against (UI-OD12)")
    void materializeReportsItsSchemaVersion() throws Exception {
        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "materialize-oql",
                Map.of("oql", "SELECT People.name FROM People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data()).containsEntry("schemaVersionId", ACTIVE_VERSION);
        Files.deleteIfExists(Path.of(result.data().get("path")));
    }
}

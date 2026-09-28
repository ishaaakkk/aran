package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * The MATERIALIZED object-storage read path: an object-storage dataset that has been copied into the
 * lake (a snapshot on its row and in the active graph) is served <em>from that copy</em> through the
 * ordinary snapshot path, not read live over httpfs.
 *
 * <p>The per-dataset selection is the point: with the same repository kind (object storage), the
 * presence of a lake snapshot flips {@code SourceAccess.resolve} from FEDERATED to MATERIALIZED. This
 * test proves the MATERIALIZED half — the lake is mocked to hand back a snapshot with rows that exist
 * nowhere in any object store ({@code carol}/{@code dave}), so a query returning them can only have
 * read the lake copy. (The FEDERATED half — no snapshot → live {@code s3://} read — is proven against
 * real Garage in {@code ObjectStorageFederatedQueryGarageTest}; the pure resolve logic both ways is
 * {@code SourceAccessTest}.) No Docker: the snapshot path goes through the lake, which is mocked, so
 * this runs in the offline gate.
 */
@DisplayName("MATERIALIZED object storage — read from the lake snapshot, not live over httpfs")
class ObjectStorageMaterializedQueryTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String PEOPLE = NS + "integration/People";
    private static final String P_ID = NS + "integration/id";
    private static final String P_NAME = NS + "integration/name";
    private static final UUID DS = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REPO = UUID.fromString("11112222-3333-4444-5555-666677778888");
    private static final String SNAPSHOT_ID = "snap-materialized-1";

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    private final GraphStore graphStore = mock(GraphStore.class);
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryRepository dataRepositoryRepository = mock(DataRepositoryRepository.class);
    private final LakeClient lake = mock(LakeClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private QueryEngineOperator operator() throws Exception {
        MappingCatalog.SourceView sv = new MappingCatalog.SourceView(
                DS.toString(), "SELECT * FROM ${SNAPSHOT}", Map.of(P_ID, "id", P_NAME, "name"), 2L);
        MappingCatalog cat = new MappingCatalog(
                Map.of(PEOPLE, new MappingCatalog.IntegratedClass(PEOPLE, List.of(sv))),
                List.of(), List.of(), "v1");
        when(catalogOperator.getCatalog(any())).thenReturn(cat);

        // The active schema graph: a real (non-blank) snapshot id marks this dataset copied into the
        // lake. The object key is still recorded, but MATERIALIZED must ignore it and read the copy.
        Model g = ModelFactory.createDefaultModel();
        Resource c = g.createResource("urn:src:People");
        g.add(c, g.createProperty(NS + "datasetId"), DS.toString());
        g.add(c, g.createProperty(NS + "snapshotId"), SNAPSHOT_ID);
        g.add(c, g.createProperty(NS + "format"), "PARQUET");
        g.add(c, g.createProperty(NS + "iterator"), "people.parquet");
        when(graphStore.query(any(), anyString())).thenReturn(g);

        // The dataset row carries the snapshot — the per-dataset signal SourceAccess.resolve reads.
        when(datasetRepository.findById(any(), eq(DS)))
                .thenReturn(Optional.of(new Dataset(DS, REPO, "People", "", SNAPSHOT_ID)));
        DataRepository repo = new ObjectStorageRepository(REPO, UUID.randomUUID(), UUID.randomUUID(),
                "lake", "", "MINIO", "http://127.0.0.1:1", "bucket", "AKID", "SEKRET", "us-east-1");
        when(dataRepositoryRepository.findById(any(), eq(REPO))).thenReturn(Optional.of(repo));

        // The lake hands back a snapshot whose rows exist in no object store: carol/dave. A query
        // returning them proves the snapshot path ran. downloadSnapshotTo is stubbed to actually
        // write the parquet the resolver then reads.
        when(lake.downloadSnapshotTo(any(), eq(SNAPSHOT_ID), any())).thenAnswer(inv -> {
            Path target = inv.getArgument(2);
            writeParquet(target);
            return "application/vnd.apache.parquet";
        });

        return new QueryEngineOperator(
                graphStore, catalogOperator, lake, mapper,
                mock(IntegrationModelRepository.class), datasetRepository, dataRepositoryRepository,
                new SnapshotCache(lake, "", 0L), mock(DataProductRepository.class));
    }

    private static void writeParquet(Path target) throws Exception {
        Files.deleteIfExists(target); // COPY TO creates the file
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES (1, 'carol'), (2, 'dave')) AS t(id, name)) TO '"
                    + target.toAbsolutePath() + "' (FORMAT parquet)");
        }
    }

    @Test
    @DisplayName("run-oql reads the lake snapshot's rows, not the object store")
    void materializedReadsFromLakeSnapshot() throws Exception {
        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "run-oql",
                Map.of("oql", "SELECT People.name FROM People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        JsonNode payload = mapper.readTree(result.data().get("result"));
        List<String> cells = new java.util.ArrayList<>();
        payload.get("rows").forEach(row -> row.forEach(cell -> cells.add(cell.asText())));
        // The lake copy's rows — proof the resolver read the snapshot path, not live s3://.
        assertThat(cells).contains("carol", "dave");
        assertThat(cells).doesNotContain("alice", "bob");

        // And it actually went to the lake for the snapshot.
        verify(lake).downloadSnapshotTo(any(), eq(SNAPSHOT_ID), any());
    }

    @Test
    @DisplayName("the plan annotates the materialized source MATERIALIZED, matching what ran")
    void planAnnotationIsMaterialized() throws Exception {
        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "rewrite-oql",
                Map.of("oql", "SELECT People.name FROM People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        // The access-mode annotation must agree with the execution resolver — MATERIALIZED, because
        // the dataset has a lake snapshot. The plan-shape carries the label.
        assertThat(result.data().get("planShapeText")).contains("MATERIALIZED");
        assertThat(result.data().get("planShapeText")).doesNotContain("FEDERATED");
    }

    @Test
    @DisplayName("a materialized read never opens the federated httpfs path")
    void materializedDoesNotReadObjectStorageLive() throws Exception {
        // If the resolver mis-selected FEDERATED it would try to reach http://127.0.0.1:1 (a dead
        // endpoint) and fail; success is itself proof, but pin the intent: the lake was used.
        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "run",
                Map.of("sql", "SELECT * FROM int_People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        verify(lake).downloadSnapshotTo(any(), eq(SNAPSHOT_ID), any());
        verify(lake, never()).downloadSnapshot(any(), anyString());
    }
}

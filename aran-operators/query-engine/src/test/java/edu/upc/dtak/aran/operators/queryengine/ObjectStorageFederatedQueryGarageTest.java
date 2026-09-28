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
import edu.upc.dtak.aran.core.source.GarageContainer;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The federated object-storage query path end to end, against a real S3-compatible store (Garage in
 * Testcontainers): an OQL query over a class whose one source is an {@code s3://} parquet object is
 * planned, the resolver builds a {@code read_parquet('s3://…')} scan plus a server-side
 * {@code CREATE SECRET}, and DuckDB reads the rows over httpfs.
 *
 * <p>Two things are proven together, because they are the point of this task:
 * <ol>
 *   <li>the right rows come back from Garage (the mechanism works), and</li>
 *   <li>the secret key value never appears in any string the operator returns — not the
 *       {@code sql}, the plan strings, the plan shape, nor the result payload. The bucket URL in the
 *       read expression is fine; the credential is not.</li>
 * </ol>
 *
 * <p>Docker-tagged: {@code validate.sh --offline} excludes it; a normal build runs it.
 */
@Tag("docker")
@DisplayName("Federated object-storage query over Garage — right rows, zero credential leak")
class ObjectStorageFederatedQueryGarageTest {

    private static final String BUCKET = "aran-people";
    private static final String OBJECT_KEY = "people.parquet";

    private static final GarageContainer GARAGE = new GarageContainer().withBucket(BUCKET);
    private static final String SECRET_KEY = GARAGE.secretKey();   // the value that must never surface

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String PEOPLE = NS + "integration/People";
    private static final String P_ID = NS + "integration/id";
    private static final String P_NAME = NS + "integration/name";
    private static final UUID DS = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REPO = UUID.fromString("11112222-3333-4444-5555-666677778888");

    @BeforeAll
    static void up() throws Exception {
        GARAGE.start();
        // The MinIO SDK is only a generic S3 client here; the bucket already exists.
        MinioClient client = MinioClient.builder()
                .endpoint(GARAGE.endpoint())
                .credentials(GARAGE.accessKey(), GARAGE.secretKey())
                .region(GARAGE.region())
                .build();

        Path parquet = Files.createTempFile("aran-people-", ".parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES (1, 'alice'), (2, 'bob')) AS t(id, name)) TO '"
                    + parquet.toAbsolutePath() + "' (FORMAT parquet)");
        }
        client.uploadObject(UploadObjectArgs.builder()
                .bucket(BUCKET).object(OBJECT_KEY)
                .filename(parquet.toAbsolutePath().toString()).build());
        Files.deleteIfExists(parquet);
    }

    @AfterAll
    static void down() {
        GARAGE.stop();
    }

    // ── operator wiring ────────────────────────────────────────────────────────

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    private final GraphStore graphStore = mock(GraphStore.class);
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryRepository dataRepositoryRepository = mock(DataRepositoryRepository.class);
    private final LakeClient lake = mock(LakeClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private QueryEngineOperator operator() {
        // Catalog: one class "People" whose only source is the object-storage dataset.
        MappingCatalog.SourceView sv = new MappingCatalog.SourceView(
                DS.toString(), "SELECT * FROM ${SNAPSHOT}", Map.of(P_ID, "id", P_NAME, "name"), 2L);
        MappingCatalog cat = new MappingCatalog(
                Map.of(PEOPLE, new MappingCatalog.IntegratedClass(PEOPLE, List.of(sv))),
                List.of(), List.of(), "v1");
        when(catalogOperator.getCatalog(any())).thenReturn(cat);

        // The active schema graph the resolver reads: object key + format, no snapshot, no creds.
        Model g = ModelFactory.createDefaultModel();
        Resource c = g.createResource("urn:src:People");
        g.add(c, g.createProperty(NS + "datasetId"), DS.toString());
        g.add(c, g.createProperty(NS + "snapshotId"), "");        // no lake copy — federated
        g.add(c, g.createProperty(NS + "format"), "PARQUET");
        g.add(c, g.createProperty(NS + "iterator"), OBJECT_KEY);  // the object key within the bucket
        when(graphStore.query(any(), anyString())).thenReturn(g);

        when(datasetRepository.findById(any(), eq(DS)))
                .thenReturn(Optional.of(new Dataset(DS, REPO, "People", "")));
        DataRepository repo = new ObjectStorageRepository(REPO, UUID.randomUUID(), UUID.randomUUID(),
                "lake", "", GarageContainer.PROVIDER, GARAGE.endpoint(), BUCKET,
                GARAGE.accessKey(), GARAGE.secretKey(), GARAGE.region());
        when(dataRepositoryRepository.findById(any(), eq(REPO))).thenReturn(Optional.of(repo));

        return new QueryEngineOperator(
                graphStore, catalogOperator, lake, mapper,
                mock(IntegrationModelRepository.class), datasetRepository, dataRepositoryRepository,
                new SnapshotCache(lake, "", 0L), mock(DataProductRepository.class));
    }

    // ── the tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("run-oql reads the object's rows over httpfs")
    void runOqlReadsRowsFromGarage() throws Exception {
        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "run-oql",
                Map.of("oql", "SELECT People.name FROM People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        JsonNode payload = mapper.readTree(result.data().get("result"));
        // Collect every cell as text and assert the names came back from Garage.
        java.util.List<String> cells = new java.util.ArrayList<>();
        payload.get("rows").forEach(row -> row.forEach(cell -> cells.add(cell.asText())));
        assertThat(cells).contains("alice", "bob");
    }

    @Test
    @DisplayName("run-oql leaks no credential in sql / plan strings / plan shape / result payload")
    void runOqlDoesNotLeakSecret() throws Exception {
        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "run-oql",
                Map.of("oql", "SELECT People.name FROM People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        // The whole returned map, serialized, must not contain the secret key value anywhere.
        String everything = mapper.writeValueAsString(result.data());
        assertThat(everything).doesNotContain(SECRET_KEY);
        // And check each surface individually.
        JsonNode payload = mapper.readTree(result.data().get("result"));
        for (String key : List.of("sql", "logicalPlan", "optimizedPlan", "planShapeText")) {
            assertThat(payload.get(key).asText())
                    .as("secret must not appear in %s", key).doesNotContain(SECRET_KEY);
        }
        assertThat(payload.get("planShape").toString()).doesNotContain(SECRET_KEY);
        assertThat(result.data().get("sql")).doesNotContain(SECRET_KEY);
        // The read expression (bucket URL, no credential) is legitimately present in the DDL but not
        // in these client-facing strings — sanity that the SQL is a view translation, not the scan.
        assertThat(result.data().get("sql")).doesNotContain("s3://");
    }

    @Test
    @DisplayName("run (raw SQL over the int_ view) also reads Garage and leaks no credential")
    void rawRunReadsAndDoesNotLeak() throws Exception {
        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "run",
                Map.of("sql", "SELECT * FROM int_People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        JsonNode payload = mapper.readTree(result.data().get("result"));
        java.util.List<String> cells = new java.util.ArrayList<>();
        payload.get("rows").forEach(row -> row.forEach(cell -> cells.add(cell.asText())));
        assertThat(cells).contains("alice", "bob");
        assertThat(mapper.writeValueAsString(result.data())).doesNotContain(SECRET_KEY);
    }
}

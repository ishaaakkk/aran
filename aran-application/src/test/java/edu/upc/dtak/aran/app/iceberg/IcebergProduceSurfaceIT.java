package edu.upc.dtak.aran.app.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRegistration;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.IcebergTableExtractor;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import edu.upc.dtak.aran.core.service.DataProductService;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.service.JobEventPublisher;
import edu.upc.dtak.aran.core.service.JobService;
import edu.upc.dtak.aran.core.service.ProjectService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.operators.bootstrapping.BootstrappingOperator;
import edu.upc.dtak.aran.operators.queryengine.QueryEngineOperator;
import edu.upc.dtak.aran.operators.schemaintegration.SchemaIntegrationOperator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The produce half of Gap 2 over real HTTP — V53 (c) and (d): a MATERIALIZED product, served
 * by a running Aran on a real port, read by two independent Iceberg implementations with a real
 * {@code aran_pat_} token, and then consumed back by the same Aran as an {@code ICEBERG_REST}
 * source.
 *
 * <ul>
 *   <li><b>iceberg-core's {@code RESTCatalog}</b> lists and loads the table and reads its
 *       manifests. iceberg-core has no FileIO for {@code http(s)}, so the client is handed a
 *       bearer-carrying one ({@link IcebergTestFileIOs.BearerHttpFileIO}) — which is exactly what
 *       any engine needs to follow metadata whose every location is an authenticated URL.</li>
 *   <li><b>DuckDB</b> reads the rows twice over — {@code iceberg_scan} on the metadata location,
 *       and its own REST-catalog {@code ATTACH} — over httpfs with an HTTP bearer secret: no
 *       custom code on the client at all, and the exact recipe the docs page gives.</li>
 *   <li><b>The dogfood loop</b>: Aran enrols its own {@code /api/iceberg/v1} as an Iceberg REST
 *       repository, bootstraps two products from it, integrates them and answers OQL — the
 *       resolver's {@code http(s)} metadata branch with the HTTP bearer secret, end to end.</li>
 * </ul>
 *
 * <p>The lake is the byte-store fake {@code JdbcRealClientIT} uses: Garage is not what is under
 * test, and every byte an Iceberg client reads here still leaves through Aran's own controllers.
 */
@DisplayName("Iceberg produce over real HTTP — iceberg-core, DuckDB and Aran itself read a product (V53 c/d)")
class IcebergProduceSurfaceIT extends BaseIntegrationTest {

    private static final List<String> COLUMNS =
            List.of("id", "name", "joined_on", "fee", "created_at");

    /** See {@code JdbcRealClientIT}: a job from afterCommit must not run on the calling thread. */
    @TestConfiguration
    static class DirectExecutorJobConfig {
        @Bean
        @Primary
        JobService directJobService(JobRepository jobRepository, JobEventPublisher publisher) {
            Executor awaitedOffThread = task -> {
                Thread worker = new Thread(task, "test-job-worker");
                worker.start();
                try {
                    worker.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting the job", ex);
                }
            };
            return new JobService(jobRepository, awaitedOffThread, publisher);
        }
    }

    @LocalServerPort private int port;

    @MockitoBean private LicenseProvider licenseProvider;
    @MockitoBean private LakeClient lakeClient;

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DatasetService datasetService;
    @Autowired private ProjectService projectService;
    @Autowired private DataRepositoryService dataRepositoryService;
    @Autowired private IntegrationModelService modelService;
    @Autowired private DataProductService productService;
    @Autowired private ApiTokenService apiTokenService;
    @Autowired private BootstrappingOperator bootstrapping;
    @Autowired private SchemaIntegrationOperator integration;
    @Autowired private QueryEngineOperator queryEngine;
    @Autowired private GraphStore graphStore;
    @Autowired private IcebergTableExtractor icebergExtractor;

    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, byte[]> lakeStore = new ConcurrentHashMap<>();
    private final Map<String, String> lakeContentTypes = new ConcurrentHashMap<>();
    private volatile byte[] pendingUpload;
    private volatile String pendingContentType;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext tenant = new TenantContext(tenantId.toString());
    private AuthenticatedUser caller;

    @Test
    @DisplayName("iceberg-core and DuckDB read a served product with a PAT; Aran consumes its own catalog back")
    void productIsReadableOverHttpAndConsumableBack() throws Exception {
        Project project = seedTenantWithTwoIntegratedSources();
        DataProduct export = materialize(project, "PeopleExport",
                "SELECT " + String.join(", ", COLUMNS.stream().map(c -> "People." + c).toList())
                        + " FROM People");
        materialize(project, "PeopleNames",
                "SELECT People.id, People.name FROM People");

        String pat = apiTokenService.create(tenant, userId, "iceberg-it", ApiTokenScope.READ, null)
                .secret();
        String base = "http://localhost:" + port;

        // ── (c) iceberg-core's reference RESTCatalog, over real HTTP, with the PAT ───────────
        List<TableIdentifier> served;
        String exportTable;
        String metadataLocation;
        try (RESTCatalog reader = new RESTCatalog()) {
            reader.initialize("aran", Map.of(
                    "uri", base + "/api/iceberg",
                    "token", pat,
                    "io-impl", IcebergTestFileIOs.BearerHttpFileIO.class.getName()));

            served = reader.listTables(Namespace.of("products"));
            assertThat(served).as("one table per MATERIALIZED product").hasSize(2);
            exportTable = served.stream().map(TableIdentifier::name)
                    .filter(n -> n.equalsIgnoreCase("PeopleExport")).findFirst().orElseThrow();

            Table table = reader.loadTable(TableIdentifier.of("products", exportTable));
            assertThat(table.schema().columns())
                    .extracting(Types.NestedField::name)
                    .containsExactlyElementsOf(COLUMNS);
            assertThat(table.properties()).containsKey(TableProperties.DEFAULT_NAME_MAPPING);
            metadataLocation = ((HasTableOperations) table).operations().current()
                    .metadataFileLocation();
            assertThat(metadataLocation)
                    .as("every location is Aran's own authenticated endpoint — never the lake")
                    .startsWith(base + "/api/iceberg/files/" + export.id() + "/");

            // The manifest list and the manifest are fetched over HTTP with the bearer, and
            // parsed by iceberg-core's own readers.
            List<ManifestFile> manifests = table.currentSnapshot().allManifests(table.io());
            assertThat(manifests).hasSize(1);
            List<DataFile> dataFiles = new ArrayList<>();
            try (ManifestReader<DataFile> manifest = ManifestFiles.read(manifests.getFirst(), table.io())) {
                manifest.forEach(f -> dataFiles.add(f.copy()));
            }
            assertThat(dataFiles).singleElement().satisfies(f -> {
                assertThat(f.path().toString())
                        .isEqualTo(base + "/api/iceberg/files/" + export.id() + "/"
                                + productService.get(tenant, export.id()).snapshotId()
                                + "/data/data.parquet");
                assertThat(f.recordCount()).isEqualTo(4L);
            });
            try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
                assertThat(tasks).as("scan planning over the served manifests").hasSize(1);
            }
        }

        // Every byte is behind the token: the same data file without it is refused.
        HttpResponse<Void> anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(base + "/api/iceberg/files/" + export.id() + "/"
                                + productService.get(tenant, export.id()).snapshotId()
                                + "/data/data.parquet")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        // ── (c) DuckDB: iceberg_scan over httpfs with an HTTP bearer secret, nothing else ────
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = duck.createStatement()) {
            st.execute("SET TimeZone='UTC'");
            for (String ext : List.of("httpfs", "iceberg")) {
                st.execute("INSTALL " + ext);
                st.execute("LOAD " + ext);
            }
            st.execute("CREATE SECRET aran (TYPE HTTP, BEARER_TOKEN '" + pat + "')");
            try (ResultSet rs = st.executeQuery("SELECT id, name, joined_on, fee, created_at"
                    + " FROM iceberg_scan('" + metadataLocation + "') ORDER BY id")) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getLong(1) + "|" + rs.getString(2) + "|"
                            + rs.getObject(3, LocalDate.class) + "|"
                            + rs.getBigDecimal(4).stripTrailingZeros().toPlainString() + "|"
                            + rs.getObject(5, LocalDateTime.class));
                }
                assertThat(rows).as("parquet with no field ids still resolves to the right columns")
                        .containsExactly(
                                "1|Alice|2024-03-15|12.5|2024-03-15T10:30",
                                "2|Bob|2024-06-01|0|2024-06-01T23:59:59",
                                "3|Carol|2023-12-25|7.25|2023-12-25T00:00",
                                "4|Dave|2024-01-01|100|2024-01-01T12:00:01");
            }
        }

        // ── (c) DuckDB's own REST-catalog ATTACH — the recipe the docs page gives, verbatim ─────
        // ENDPOINT stops before /v1 (DuckDB appends it: "/api/iceberg/v1" asks for
        // /v1/v1/config and 404s), and TOKEN authenticates only the catalog calls — the manifest
        // and parquet reads are plain httpfs GETs that need the HTTP secret as well.
        String attach = "ATTACH 'aran' AS aran (TYPE ICEBERG, ENDPOINT '" + base
                + "/api/iceberg', TOKEN '" + pat + "')";
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = duck.createStatement()) {
            st.execute("LOAD httpfs");
            st.execute("LOAD iceberg");
            st.execute(attach);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.executeQuery(
                            "SELECT count(*) FROM aran.products." + exportTable))
                    .as("the catalog TOKEN does not reach the file reads")
                    .hasMessageContaining("401");
        }
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = duck.createStatement()) {
            st.execute("LOAD httpfs");
            st.execute("LOAD iceberg");
            st.execute("CREATE SECRET aran_files (TYPE HTTP, BEARER_TOKEN '" + pat + "')");
            st.execute(attach);
            try (ResultSet rs = st.executeQuery("SELECT name FROM aran.products." + exportTable
                    + " ORDER BY id")) {
                List<String> attached = new ArrayList<>();
                while (rs.next()) {
                    attached.add(rs.getString(1));
                }
                assertThat(attached).containsExactly("Alice", "Bob", "Carol", "Dave");
            }
        }

        // ── (d) the dogfood loop: Aran enrols its own produce catalog as an ICEBERG_REST source ──
        // Through the service, as the repository form is: its stored-target screen sees the
        // loopback catalog URI a same-host dogfood enrolment really stores.
        IcebergRestRepository self = (IcebergRestRepository) dataRepositoryService.createRepository(
                caller, project.id(),
                new IcebergRestRepository(UUID.randomUUID(), project.id(), tenantId, "self", "",
                        base + "/api/iceberg", "", pat, "S3", "", "", "", ""));
        String namesTable = served.stream().map(TableIdentifier::name)
                .filter(n -> !n.equals(exportTable)).findFirst().orElseThrow();
        Dataset exportDs = enrolAndBootstrap(project, self, "products." + exportTable);
        Dataset namesDs = enrolAndBootstrap(project, self, "products." + namesTable);

        Model exportGraph = sourceGraphOf(exportDs);
        Model namesGraph = sourceGraphOf(namesDs);
        OperatorResult compiled = integration.execute(new OperatorRequest(
                tenant.value(), userId, "compile", Map.of(
                        "sourceDatasetIds",
                        om.writeValueAsString(List.of(exportDs.id().toString(), namesDs.id().toString())),
                        "alignments", om.writeValueAsString(List.of(
                                align(classIriOf(exportGraph), classIriOf(namesGraph), "Member", "class"),
                                align(propertyIriOf(exportGraph, "id"), propertyIriOf(namesGraph, "id"),
                                        "memberId", "datatype"),
                                align(propertyIriOf(exportGraph, "name"), propertyIriOf(namesGraph, "name"),
                                        "memberName", "datatype"))),
                        "modelId", UUID.randomUUID().toString())));
        assertThat(compiled.success())
                .as("compile: %s / %s", compiled.errorCode(), compiled.errorMessage()).isTrue();
        graphStore.promote(tenant, UUID.fromString(compiled.data().get("draftVersionId")),
                new AuditedActor(userId));

        OperatorResult result = queryEngine.execute(new OperatorRequest(tenant.value(), userId,
                "run-oql", Map.of("oql", "SELECT Member.memberId, Member.memberName FROM Member")));
        assertThat(result.success())
                .as("run-oql: %s / %s", result.errorCode(), result.errorMessage()).isTrue();
        JsonNode payload = om.readTree(result.data().get("result"));
        assertThat(payload.get("planShapeText").asText()).contains("access=FEDERATED");
        List<String> names = new ArrayList<>();
        payload.get("rows").forEach(row -> names.add(row.get(1).asText()));
        assertThat(names)
                .as("both products, read back through Aran's own catalog and file endpoints")
                .containsExactlyInAnyOrder("Alice", "Bob", "Carol", "Dave",
                        "Alice", "Bob", "Carol", "Dave");

        // ── (e) the MATERIALIZED half of the same loop: a copy of an authenticated http(s)
        //    data plane binds the catalog's bearer for its file reads, as the live read does ──
        assertThat(icebergExtractor.extract(self, "products." + exportTable, 64L << 20).rowCount())
                .as("the copy read every file through the PAT-authed file endpoint")
                .isEqualTo(4L);
    }

    // ── fixture: a tenant, two integrated parquet sources, materialized products ────────────

    private Project seedTenantWithTwoIntegratedSources() throws Exception {
        wireLakeFake();
        when(licenseProvider.findFor(any(UUID.class))).thenAnswer(inv -> java.util.Optional.of(
                new edu.upc.dtak.aran.core.domain.LicenseEntitlements(
                        inv.getArgument(0), edu.upc.dtak.aran.core.domain.LicensePlan.ENTERPRISE,
                        edu.upc.dtak.aran.core.domain.LicenseStatus.ACTIVE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE, null, null, Set.of(), null)));
        caller = new AuthenticatedUser(userId, "sub", tenantId, TenantRole.OWNER);
        tenantRepository.save(new Tenant(tenantId, "iceberg-it-" + tenantId, "Iceberg IT", Instant.now()));
        userRepository.save(new User(userId, "iceberg-it-" + userId, "iceberg-it@example.test",
                "Iceberg IT", Instant.now()));
        // The API token filter re-resolves the caller from a live membership.
        tenantRepository.saveMembership(new Membership(
                UUID.randomUUID(), userId, tenantId, TenantRole.OWNER, Instant.now()));

        Project project = projectService.createProject(caller, "Iceberg IT", "desc", "PUBLIC", "#fff");
        DataRepository repo = dataRepositoryService.createRepository(caller, project.id(),
                new FileSystemRepository(UUID.randomUUID(), project.id(), tenantId, "Repo", "desc"));
        DatasetRegistration reg1 = datasetService.registerDatasetWithFile(caller, project.id(), repo.id(),
                new Dataset(UUID.randomUUID(), repo.id(), "people1", "desc"), peopleParquet(
                        "(1, 'Alice', DATE '2024-03-15', 12.50, TIMESTAMP '2024-03-15 10:30:00'),"
                        + " (2, 'Bob', DATE '2024-06-01', 0.00, TIMESTAMP '2024-06-01 23:59:59')"),
                "application/octet-stream", "people1.parquet");
        DatasetRegistration reg2 = datasetService.registerDatasetWithFile(caller, project.id(), repo.id(),
                new Dataset(UUID.randomUUID(), repo.id(), "people2", "desc"), peopleParquet(
                        "(3, 'Carol', DATE '2023-12-25', 7.25, TIMESTAMP '2023-12-25 00:00:00'),"
                        + " (4, 'Dave', DATE '2024-01-01', 100.00, TIMESTAMP '2024-01-01 12:00:01')"),
                "application/octet-stream", "people2.parquet");

        List<ModelSource> s1 = modelService.addSource(tenant, project.id(), reg1.dataset().id());
        List<ModelSource> s2 = modelService.addSource(tenant, project.id(), reg2.dataset().id());
        String class1 = s1.getFirst().classIri();
        String class2 = s2.getFirst().classIri();
        modelService.addMapping(tenant, project.id(), MappingKinds.CLASS, class1, class2, "People",
                userId, null);
        for (String column : COLUMNS) {
            modelService.addMapping(tenant, project.id(), MappingKinds.DATATYPE,
                    class1 + "/" + column, class2 + "/" + column, column, userId, null);
        }
        Map<String, String> compiled = modelService.rebuildToDraft(tenant, project.id(), userId);
        GraphVersion active = modelService.promoteRun(tenant, project.id(),
                UUID.fromString(compiled.get("integrationRunId")), userId);
        assertThat(active.status()).isEqualTo("ACTIVE");
        return project;
    }

    private DataProduct materialize(Project project, String name, String oql) {
        DataProduct product = productService.create(tenant, project.id(), name, null, oql, userId);
        productService.materialize(tenant, product.id(), userId);
        DataProduct ready = productService.get(tenant, product.id());
        assertThat(ready.status().name()).isEqualTo("READY");
        return ready;
    }

    private Dataset enrolAndBootstrap(Project project, IcebergRestRepository repository,
                                      String identifier) {
        Dataset dataset = datasetService.registerDataset(caller, project.id(), repository.id(),
                new Dataset(UUID.randomUUID(), repository.id(), identifier, "dogfood"));
        OperatorResult boot = bootstrapping.execute(new OperatorRequest(tenant.value(), userId,
                "bootstrap", Map.of("datasetId", dataset.id().toString())));
        assertThat(boot.success())
                .as("bootstrap %s: %s / %s", identifier, boot.errorCode(), boot.errorMessage())
                .isTrue();
        return dataset;
    }

    private static byte[] peopleParquet(String values) throws Exception {
        Path tmp = Files.createTempFile("iceberg-it-people-", ".parquet");
        try {
            try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                 Statement st = duck.createStatement()) {
                st.execute("CREATE TABLE t(id BIGINT, name VARCHAR, joined_on DATE,"
                        + " fee DECIMAL(10,2), created_at TIMESTAMP)");
                st.execute("INSERT INTO t VALUES " + values);
                st.execute("COPY t TO '" + tmp.toAbsolutePath() + "' (FORMAT PARQUET)");
            }
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void wireLakeFake() {
        when(lakeClient.initUpload(any(UUID.class), any(UUID.class), anyLong(), any()))
                .thenAnswer(inv -> {
                    pendingContentType = inv.getArgument(3);
                    return new LakeClient.InitUploadResponse(
                            "upload-" + UUID.randomUUID(), "http://mock/upload", inv.getArgument(3));
                });
        doAnswer(inv -> {
            pendingUpload = inv.getArgument(1);
            return null;
        }).when(lakeClient).uploadToS3(any(String.class), any(byte[].class), any(String.class));
        when(lakeClient.computeSha256(any())).thenReturn("hash");
        when(lakeClient.completeUpload(any(UUID.class), any(String.class), any(String.class), anyLong()))
                .thenAnswer(inv -> {
                    String snapshotId = "snap-" + UUID.randomUUID();
                    lakeStore.put(snapshotId, pendingUpload);
                    lakeContentTypes.put(snapshotId,
                            pendingContentType != null ? pendingContentType : "text/csv");
                    return snapshotId;
                });
        when(lakeClient.downloadSnapshot(any(UUID.class), any(String.class)))
                .thenAnswer(inv -> new LakeClient.SnapshotBytes(
                        lakeStore.get(inv.getArgument(1, String.class)),
                        lakeContentTypes.getOrDefault(inv.getArgument(1, String.class), "text/csv")));
        doAnswer(inv -> {
            String snapshotId = inv.getArgument(1);
            Path target = inv.getArgument(2);
            byte[] bytes = lakeStore.get(snapshotId);
            if (bytes == null) {
                throw new IOException("no such snapshot in the fake lake store: " + snapshotId);
            }
            Files.write(target, bytes);
            return lakeContentTypes.getOrDefault(snapshotId, "text/csv");
        }).when(lakeClient).downloadSnapshotTo(any(UUID.class), any(String.class), any(Path.class));
    }

    // ── graph helpers ───────────────────────────────────────────────────────────────────────

    private Model sourceGraphOf(Dataset dataset) {
        return graphStore.query(tenant, "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:source:"
                + dataset.id() + "> { ?s ?p ?o } }");
    }

    private static String classIriOf(Model source) {
        return source.listResourcesWithProperty(RDF.type, RDFS.Class).toList().getFirst().getURI();
    }

    private static String propertyIriOf(Model source, String column) {
        return source.listResourcesWithProperty(RDFS.label, column)
                .filterKeep(r -> source.contains(r, RDF.type, RDF.Property))
                .toList().stream()
                .map(Resource::getURI)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no source property for column " + column));
    }

    private static Map<String, String> align(String iriA, String iriB, String label, String type) {
        return Map.of("iriA", iriA, "iriB", iriB, "label", label, "type", type);
    }
}

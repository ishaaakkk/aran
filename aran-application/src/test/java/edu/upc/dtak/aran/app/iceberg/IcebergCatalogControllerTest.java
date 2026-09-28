package edu.upc.dtak.aran.app.iceberg;

import edu.upc.dtak.aran.adapter.iceberg.IcebergProductTable;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.jdbc.ProductRegistry;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The produce-side REST catalog (Gap 2), driven as a spec-following client would drive it:
 * config → namespaces → tables → loadTable → the files the metadata points at. Everything runs
 * against fakes — the registry hands back a fixed catalog, the lake hands back fixed bytes — so
 * what is pinned is the protocol surface and the invariant that no location ever leaves Aran's
 * own {@code /api/iceberg/files} prefix.
 */
@DisplayName("Iceberg REST catalog over data products — the read protocol, end to end in process")
class IcebergCatalogControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-00000000000b");
    private static final UUID PRODUCT = UUID.fromString("55555555-0000-0000-0000-00000000000b");
    private static final byte[] PARQUET = "PAR1-fake-bytes-PAR1".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper = new ObjectMapper();
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    private final LakeClient lake = mock(LakeClient.class);

    private IcebergProductService service;
    private IcebergCatalogController catalog;
    private IcebergFileController files;

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "subject", TENANT, TenantRole.OWNER);
    }

    private static DataProduct materialized() {
        return DataProduct.draft(PRODUCT, TENANT, UUID.randomUUID(), "papers per year", null,
                        "SELECT Paper.year FROM Paper", UUID.randomUUID(), null, Instant.EPOCH,
                        ProductAccessMode.MATERIALIZED)
                .materialized("snap-1", 42L,
                        ProductColumn.toJson(List.of(
                                new ProductColumn("year", "BIGINT", java.sql.Types.BIGINT),
                                new ProductColumn("n", "BIGINT", java.sql.Types.BIGINT))),
                        Instant.ofEpochMilli(1_756_400_000_000L));
    }

    private static DataProduct virtualProduct() {
        return DataProduct.draft(UUID.randomUUID(), TENANT, UUID.randomUUID(), "live view", null,
                        "SELECT Paper.year FROM Paper", UUID.randomUUID(), null, Instant.EPOCH,
                        ProductAccessMode.VIRTUAL)
                .virtualReady(ProductColumn.toJson(List.of(
                        new ProductColumn("year", "BIGINT", java.sql.Types.BIGINT))));
    }

    private static DataProduct schemaless() {
        return DataProduct.draft(UUID.randomUUID(), TENANT, UUID.randomUUID(), "pre v29", null,
                        "SELECT Paper.year FROM Paper", UUID.randomUUID(), null, Instant.EPOCH,
                        ProductAccessMode.MATERIALIZED)
                .materialized("snap-2", 1L, null, Instant.EPOCH);
    }

    @BeforeEach
    void setUp() throws Exception {
        when(currentUser.require()).thenReturn(caller());
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(2, Path.class), PARQUET);
            return null;
        }).when(lake).downloadSnapshotTo(eq(TENANT), any(), any(Path.class));
        // Fresh staging dir per run so a previous test's cached parquet cannot mask a download.
        Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"), "aran-iceberg-data",
                TENANT + "-snap-1.parquet"));

        ProductRegistry registry = ignored -> ProductCatalog.of(
                List.of(materialized(), virtualProduct(), schemaless()));
        service = new IcebergProductService(registry, lake);
        IcebergFilesBaseUrl baseUrl = new IcebergFilesBaseUrl("");
        catalog = new IcebergCatalogController(service, currentUser, mapper, baseUrl);
        // Licensing and metering are IcebergFileControllerTest's subject; here they are wired
        // permissive-and-silent so this test stays about the protocol.
        files = new IcebergFileController(service, currentUser, baseUrl, uncappedLicenses(),
                new edu.upc.dtak.aran.core.service.UsageService(new NoopUsageEvents()));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("aran.example");
        request.setServerPort(443);
        request.setRequestURI("/api/iceberg/v1/namespaces/products/tables/papers_per_year");
        return request;
    }

    @Test
    @DisplayName("the tree is one namespace holding only servable products")
    void listsOnlyServableTables() {
        assertThat(catalog.listNamespaces().get("namespaces").get(0).get(0).asString())
                .isEqualTo("products");

        ResponseEntity<JsonNode> tables = catalog.listTables("products");
        JsonNode identifiers = tables.getBody().get("identifiers");
        // The VIRTUAL product (no files to serve) and the pre-V29 one (no describable schema)
        // are honestly absent, not listed-and-unreadable.
        assertThat(identifiers).hasSize(1);
        assertThat(identifiers.get(0).get("name").asString()).isEqualTo("papers_per_year");
    }

    @Test
    @DisplayName("an unknown namespace or table answers the spec's error shape")
    void notFoundUsesSpecErrors() {
        ResponseEntity<JsonNode> ns = catalog.listTables("warehouse");
        assertThat(ns.getStatusCode().value()).isEqualTo(404);
        assertThat(ns.getBody().get("error").get("type").asString())
                .isEqualTo("NoSuchNamespaceException");

        ResponseEntity<JsonNode> table = catalog.loadTable("products", "nope", request());
        assertThat(table.getStatusCode().value()).isEqualTo(404);
        assertThat(table.getBody().get("error").get("type").asString())
                .isEqualTo("NoSuchTableException");
    }

    @Test
    @DisplayName("loadTable answers metadata whose every location stays under Aran's own files endpoint")
    void loadTableStaysUnderAran() {
        ResponseEntity<JsonNode> response = catalog.loadTable("products", "papers_per_year", request());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        String expectedBase = "https://aran.example/api/iceberg/files/" + PRODUCT + "/snap-1";
        assertThat(body.get("metadata-location").asString())
                .isEqualTo(expectedBase + "/metadata/v1.metadata.json");
        // The inline metadata is real table metadata: v2, right schema, and — the invariant —
        // no location outside Aran's own endpoint, so no lake URL can leak from here.
        JsonNode metadata = body.get("metadata");
        assertThat(metadata.get("format-version").asInt()).isEqualTo(2);
        assertThat(metadata.toString()).doesNotContain("s3://");
        assertThat(metadata.get("location").asString()).isEqualTo(expectedBase);
    }

    @Test
    @DisplayName("the metadata's manifest chain and data file are all served by Aran, PAT-authed")
    void filesAreServedByAran() throws Exception {
        JsonNode body = catalog.loadTable("products", "papers_per_year", request()).getBody();
        String metadataLocation = body.get("metadata-location").asString();
        String relative = metadataLocation.substring(
                ("https://aran.example/api/iceberg/files/" + PRODUCT + "/snap-1/metadata/").length());

        ResponseEntity<byte[]> metadataFile =
                files.metadataFile(PRODUCT, "snap-1", relative, request());
        assertThat(metadataFile.getStatusCode().value()).isEqualTo(200);

        // Follow the chain exactly as a reader would: metadata.json → manifest list.
        JsonNode parsed = mapper.readTree(metadataFile.getBody());
        String manifestList = parsed.get("snapshots").get(0).get("manifest-list").asString();
        String manifestListFile = manifestList.substring(manifestList.lastIndexOf('/') + 1);
        ResponseEntity<byte[]> avro = files.metadataFile(PRODUCT, "snap-1", manifestListFile, request());
        assertThat(avro.getStatusCode().value()).isEqualTo(200);
        assertThat(avro.getBody()).isNotEmpty();

        var data = files.dataFile(PRODUCT, "snap-1");
        assertThat(data.getStatusCode().value()).isEqualTo(200);
        assertThat(data.getBody().getContentAsByteArray()).isEqualTo(PARQUET);
    }

    @Test
    @DisplayName("a stale snapshot path 404s the moment a refresh moves the product on")
    void staleSnapshotPathsGoAway() {
        assertThat(files.dataFile(PRODUCT, "snap-0").getStatusCode().value()).isEqualTo(404);
        assertThat(files.metadataFile(PRODUCT, "snap-0", IcebergProductTable.METADATA_FILE,
                request()).getStatusCode().value()).isEqualTo(404);
    }

    /** Records nothing: a usage write must never be able to fail the thing it measures. */
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

    /** No ceiling — the OSS default, and what every assertion in this class assumes. */
    private static edu.upc.dtak.aran.core.licensing.LicenseService uncappedLicenses() {
        edu.upc.dtak.aran.core.licensing.LicenseProvider provider = tenantId ->
                java.util.Optional.of(new edu.upc.dtak.aran.core.domain.LicenseEntitlements(
                        tenantId,
                        edu.upc.dtak.aran.core.domain.LicensePlan.ENTERPRISE,
                        edu.upc.dtak.aran.core.domain.LicenseStatus.ACTIVE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE, null, null,
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

    /**
     * Through Spring MVC's own message conversion — the layer every test above steps around by
     * calling the controller directly. The application's converter is Jackson 3's; a Jackson 2
     * {@code JsonNode} handed to it is serialized as a bean, so the catalog answered
     * {@code {"array":false,"nodeType":"OBJECT",…}} to every call and iceberg-core's RESTCatalog
     * read an empty table list (found by {@code IcebergProduceSurfaceIT}, V53(c)).
     */
    @Test
    @DisplayName("over HTTP the catalog answers protocol JSON, not a serialized tree object")
    void servedJsonIsTheProtocolNotATreeBean() throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(catalog).build();

        http.perform(get("/api/iceberg/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults").isMap())
                .andExpect(jsonPath("$.overrides").isMap())
                .andExpect(jsonPath("$.nodeType").doesNotExist());
        http.perform(get("/api/iceberg/v1/namespaces"))
                .andExpect(jsonPath("$.namespaces[0][0]").value("products"));
        http.perform(get("/api/iceberg/v1/namespaces/products/tables"))
                .andExpect(jsonPath("$.identifiers[0].namespace[0]").value("products"))
                .andExpect(jsonPath("$.identifiers[0].name").value("papers_per_year"));
        http.perform(get("/api/iceberg/v1/namespaces/products/tables/papers_per_year"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['metadata-location']")
                        .value(org.hamcrest.Matchers.endsWith("/metadata/v1.metadata.json")))
                .andExpect(jsonPath("$.metadata['format-version']").value(2));
        http.perform(get("/api/iceberg/v1/namespaces/warehouse/tables"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("NoSuchNamespaceException"));
    }

    @Test
    @DisplayName("HEAD table answers existence without a body")
    void headTable() {
        assertThat(catalog.tableExists("products", "papers_per_year").getStatusCode().value())
                .isEqualTo(204);
        assertThat(catalog.tableExists("products", "nope").getStatusCode().value())
                .isEqualTo(404);
    }
}

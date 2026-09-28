package edu.upc.dtak.aran.app.iceberg;

import edu.upc.dtak.aran.adapter.iceberg.IcebergProductTable;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.jdbc.ProductRegistry;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.LicenseStatus;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.UsageEvent;
import edu.upc.dtak.aran.core.domain.UsageEventRepository;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseLimitExceededException;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.licensing.UsageCounter;
import edu.upc.dtak.aran.core.service.UsageService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The produce surface's egress gate and meter (S10-1).
 *
 * <p>Iceberg is the one bulk door with no row cap: it hands out a product's whole parquet. It
 * served that with neither {@code requireWithinEgressLimit} nor a usage row, while the two other
 * bulk doors — {@code AranMeta.materialize} and {@code QueryExportService.export} — did both, so a
 * tenant's egress ceiling and its admin figures were simply blind to it.
 *
 * <p>The metering assertions are about <em>served</em> bytes, not the file's size: a parquet
 * reader fetches a footer, then a column chunk, then another, as separate {@code Range} requests.
 * Recording {@code Files.size} per request would bill a 4 KB footer read as a whole-file download.
 */
@DisplayName("Iceberg data-file serving — the egress gate and what it meters")
class IcebergFileControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-00000000000f");
    private static final UUID USER = UUID.fromString("22222222-0000-0000-0000-00000000000f");
    private static final UUID PRODUCT = UUID.fromString("55555555-0000-0000-0000-00000000000f");
    private static final String SNAPSHOT = "snap-metered";
    private static final byte[] PARQUET =
            "PAR1-fake-bytes-with-a-known-length-PAR1".getBytes(StandardCharsets.UTF_8);

    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    private final LakeClient lake = mock(LakeClient.class);
    private final RecordingUsageEvents recorded = new RecordingUsageEvents();

    private IcebergProductService service;

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(USER, "subject", TENANT, TenantRole.OWNER);
    }

    private static DataProduct materialized() {
        return DataProduct.draft(PRODUCT, TENANT, UUID.randomUUID(), "papers per year", null,
                        "SELECT Paper.year FROM Paper", UUID.randomUUID(), null, Instant.EPOCH,
                        ProductAccessMode.MATERIALIZED)
                .materialized(SNAPSHOT, 42L,
                        ProductColumn.toJson(List.of(
                                new ProductColumn("year", "BIGINT", java.sql.Types.BIGINT))),
                        Instant.ofEpochMilli(1_756_400_000_000L));
    }

    @BeforeEach
    void setUp() throws Exception {
        when(currentUser.require()).thenReturn(caller());
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(2, Path.class), PARQUET);
            return null;
        }).when(lake).downloadSnapshotTo(eq(TENANT), any(), any(Path.class));
        Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"), "aran-iceberg-data",
                TENANT + "-" + SNAPSHOT + ".parquet"));

        ProductRegistry registry = ignored -> ProductCatalog.of(List.of(materialized()));
        service = new IcebergProductService(registry, lake);
    }

    private IcebergFileController controller(LicenseService licenses) {
        return new IcebergFileController(service, currentUser, new IcebergFilesBaseUrl(""),
                licenses, new UsageService(recorded));
    }

    @Test
    @DisplayName("a tenant over its egress ceiling is refused exactly as a JDBC statement is")
    void overTheEgressLimitIsRefused() {
        IcebergFileController files = controller(licenses(1_000L, 5_000L));

        assertThatThrownBy(() -> files.dataFile(PRODUCT, SNAPSHOT))
                .isInstanceOfSatisfying(LicenseLimitExceededException.class, ex -> {
                    assertThat(ex.limitName()).isEqualTo("maxMonthlyEgressBytes");
                    assertThat(ex.currentUsage()).isEqualTo(5_000L);
                    assertThat(ex.limit()).isEqualTo(1_000L);
                });

        assertThat(recorded.events).isEmpty();
    }

    @Test
    @DisplayName("the bytes actually written are recorded once, as ICEBERG egress")
    void servedBytesAreMetered() throws Exception {
        IcebergFileController files = controller(licenses(null, 0));

        ResponseEntity<Resource> response = files.dataFile(PRODUCT, SNAPSHOT);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Handing back a resource is not egress; the meter must wait for the body to be written,
        // which happens after this controller method has already returned.
        assertThat(recorded.events).isEmpty();

        assertThat(response.getBody().getContentAsByteArray()).isEqualTo(PARQUET);

        assertThat(recorded.events).hasSize(1);
        UsageEvent event = recorded.events.get(0);
        assertThat(event.kind()).isEqualTo(UsageKind.ICEBERG);
        assertThat(UsageKind.ICEBERG.direction()).isEqualTo(UsageKind.Direction.EGRESS);
        assertThat(event.byteCount()).isEqualTo(PARQUET.length);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.userId()).isEqualTo(USER);
    }

    @Test
    @DisplayName("a partial read bills the range it read, not the size of the file")
    void partialReadsBillOnlyWhatWasRead() throws Exception {
        IcebergFileController files = controller(licenses(null, 0));

        ResponseEntity<Resource> response = files.dataFile(PRODUCT, SNAPSHOT);
        try (InputStream in = response.getBody().getInputStream()) {
            assertThat(in.readNBytes(8)).hasSize(8);
        }

        assertThat(recorded.events).hasSize(1);
        assertThat(recorded.events.get(0).byteCount()).isEqualTo(8L);
        assertThat(recorded.events.get(0).byteCount()).isNotEqualTo((long) PARQUET.length);
    }

    @Test
    @DisplayName("metadata and manifests stay outside the gate and the meter — protocol, not payload")
    void metadataIsNeitherGatedNorMetered() {
        // Deliberate, and the same call the JDBC surface makes for its catalog: a metadata.json is
        // a few kilobytes a client must fetch before it can decide not to read anything at all.
        IcebergFileController files = controller(licenses(1_000L, 5_000L));

        ResponseEntity<byte[]> metadata = files.metadataFile(PRODUCT, SNAPSHOT,
                IcebergProductTable.METADATA_FILE, new MockHttpServletRequest());

        assertThat(metadata.getStatusCode().value()).isEqualTo(200);
        assertThat(recorded.events).isEmpty();
    }

    /** Keeps every event so a test can assert how many were written, and with what. */
    private static final class RecordingUsageEvents implements UsageEventRepository {
        private final List<UsageEvent> events = new ArrayList<>();

        @Override public UsageEvent save(UsageEvent event) {
            events.add(event);
            return event;
        }

        @Override public List<UsagePerUser> usagePerUser(UUID tenantId, Instant since) {
            return List.of();
        }

        @Override public UsageTotals totals(UUID tenantId, Instant since) {
            return new UsageTotals(0, 0, 0, 0, 0);
        }
    }

    /**
     * A licensing layer with one dial that matters here.
     *
     * <p>Local rather than shared with aran-core's fakes, which are not on this module's test
     * classpath — the same reason {@code AranMetaTest} carries its own.
     *
     * @param egressCeiling bytes per rolling window, or null for the unlicensed default (no
     *                      ceiling), which is what an OSS install runs with
     * @param egressUsed    what the tenant has already sent
     */
    private static LicenseService licenses(Long egressCeiling, long egressUsed) {
        LicenseProvider provider = tenantId -> Optional.of(new LicenseEntitlements(
                tenantId, LicensePlan.ENTERPRISE, LicenseStatus.ACTIVE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, null, egressCeiling, Set.of(), null));
        UsageCounter counter = new UsageCounter() {
            @Override public long countUsers(UUID tenantId) {
                return 0;
            }

            @Override public long countProjects(UUID tenantId) {
                return 0;
            }

            @Override public long estimatedStorageBytes(UUID tenantId) {
                return 0;
            }

            @Override public long egressBytesSince(UUID tenantId, Instant since) {
                return egressUsed;
            }
        };
        return new LicenseService(provider, counter);
    }
}

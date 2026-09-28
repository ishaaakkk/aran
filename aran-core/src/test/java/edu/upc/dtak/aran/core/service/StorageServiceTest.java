package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.SnapshotReferenceRepository;
import edu.upc.dtak.aran.core.domain.StorageSample;
import edu.upc.dtak.aran.core.domain.StorageSampleRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage picture, and in particular the orphan scan.
 *
 * <p>The orphan figure is the one an operator would act on — it is the number that says how much
 * disk is reclaimable — so the cases that matter here are the ones where being wrong is expensive:
 * reporting live bytes as garbage, and reporting "nothing to reclaim" when the scan never ran.
 */
class StorageServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    private CapturingLakeClient lake;
    private RecordingSamples samples;
    private StubReferences references;
    private StorageService service;

    private static AuthenticatedUser owner() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private static AuthenticatedUser member() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.MEMBER);
    }

    private static LakeClient.SnapshotInventoryItem snapshot(String id, long size) {
        return new LakeClient.SnapshotInventoryItem(id, UUID.randomUUID(), size, "hash-" + id);
    }

    @BeforeEach
    void setUp() {
        lake = new CapturingLakeClient();
        samples = new RecordingSamples();
        references = new StubReferences();
        service = new StorageService(lake, samples, references);
    }

    @Test
    @DisplayName("only an owner may read storage")
    void memberIsRefused() {
        assertThatThrownBy(() -> service.read(member()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("unreferenced counts exactly the snapshots nothing points at")
    void countsOnlyOrphans() {
        lake.inventory = List.of(snapshot("live-1", 1000), snapshot("dead-1", 300),
                snapshot("live-2", 2000), snapshot("dead-2", 700));
        references.referenced = Set.of("live-1", "live-2");

        StorageService.Orphans orphans = service.read(owner()).unreferenced();

        assertThat(orphans.known()).isTrue();
        assertThat(orphans.complete()).isTrue();
        assertThat(orphans.snapshots()).isEqualTo(2);
        assertThat(orphans.bytes()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a snapshot referenced by anything at all is never counted as reclaimable")
    void referencedIsNeverOrphaned() {
        // The conservative direction: over-counting references only under-reports what can be
        // freed. Under-counting them would have an operator delete data that is still in use.
        lake.inventory = List.of(snapshot("pinned", 5000));
        references.referenced = Set.of("pinned");

        assertThat(service.read(owner()).unreferenced().bytes()).isZero();
    }

    @Test
    @DisplayName("nothing referenced means everything is reclaimable")
    void allOrphaned() {
        lake.inventory = List.of(snapshot("a", 10), snapshot("b", 20));
        references.referenced = Set.of();

        StorageService.Orphans orphans = service.read(owner()).unreferenced();
        assertThat(orphans.bytes()).isEqualTo(30);
        assertThat(orphans.snapshots()).isEqualTo(2);
    }

    @Test
    @DisplayName("a scan that could not run reports unknown, never zero")
    void failureIsUnknownNotZero() {
        // "Nothing to reclaim" and "nobody looked" must not render the same. Telling an operator
        // their disk is clean when the lake was unreachable is the one wrong answer here.
        service = new StorageService(new ThrowingLake(), samples, references);

        StorageService.Orphans orphans = service.read(owner()).unreferenced();
        assertThat(orphans.known()).isFalse();
        assertThat(orphans.bytes()).isZero();
    }

    @Test
    @DisplayName("the first view records a sample; an immediate second view does not")
    void samplingIsThrottled() {
        lake.storageUsage = new LakeClient.StorageUsage(3, 900, 600, null, null, List.of());

        service.read(owner());
        service.read(owner());

        assertThat(samples.saved).hasSize(1);
        assertThat(samples.saved.getFirst().physicalBytes()).isEqualTo(600);
    }

    @Test
    @DisplayName("with no history there is no baseline, rather than a fabricated one")
    void noBaselineWithoutHistory() {
        StorageService.StorageReport report = service.read(owner());
        assertThat(report.baselinePhysicalBytes()).isNull();
        assertThat(report.baselineMeasuredAt()).isNull();
    }

    @Test
    @DisplayName("a baseline within the window becomes the growth comparison")
    void baselineComesFromHistory() {
        Instant then = Instant.now().minus(Duration.ofDays(20));
        samples.earliest = Optional.of(
                new StorageSample(UUID.randomUUID(), TENANT, then, 4000, 3000, 5));
        lake.storageUsage = new LakeClient.StorageUsage(9, 8000, 6000, null, null, List.of());

        StorageService.StorageReport report = service.read(owner());
        assertThat(report.physicalBytes()).isEqualTo(6000);
        assertThat(report.baselinePhysicalBytes()).isEqualTo(3000);
        assertThat(report.baselineMeasuredAt()).isEqualTo(then);
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    private static final class RecordingSamples implements StorageSampleRepository {
        private final List<StorageSample> saved = new ArrayList<>();
        private Optional<StorageSample> earliest = Optional.empty();

        @Override public StorageSample save(StorageSample sample) {
            saved.add(sample);
            return sample;
        }

        @Override public Optional<StorageSample> latest(UUID tenantId) {
            return saved.isEmpty() ? Optional.empty() : Optional.of(saved.getLast());
        }

        @Override public Optional<StorageSample> earliestSince(UUID tenantId, Instant since) {
            return earliest;
        }
    }

    private static final class StubReferences implements SnapshotReferenceRepository {
        private Set<String> referenced = Set.of();

        @Override public Set<String> referencedSnapshotIds(TenantContext tenant) {
            return referenced;
        }
    }

    /** A lake that cannot answer — the case that must not read as "your disk is clean". */
    private static final class ThrowingLake extends CapturingLakeClient {
        @Override public List<SnapshotInventoryItem> snapshotInventory(
                UUID tenantId, int limit, int offset) {
            throw new IllegalStateException("lake unreachable");
        }
    }
}

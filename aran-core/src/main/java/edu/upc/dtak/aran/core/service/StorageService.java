package edu.upc.dtak.aran.core.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.SnapshotReferenceRepository;
import edu.upc.dtak.aran.core.domain.StorageSample;
import edu.upc.dtak.aran.core.domain.StorageSampleRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What a tenant is holding, and whether that is growing.
 *
 * <h2>Read live, sample for history</h2>
 *
 * <p>The current figure comes from the lake on every view, not from the newest sample. An
 * operator opening this screen is usually asking "am I about to fill the disk", and yesterday's
 * number is the wrong answer to that — particularly right after the upload that prompted them to
 * look.
 *
 * <p>Samples exist for the other half of the question. "6.1 GB" is not actionable; "6.1 GB, and
 * it was 4.9 GB a month ago" is, because growth rate is what predicts a full disk. History can
 * only come from having written some down.
 *
 * <h2>Why no scheduler</h2>
 *
 * <p>Sampling happens on view, throttled to at most one row per tenant per day. A scheduled sweep
 * would need to enumerate every tenant, which is a platform-level capability Aran does not
 * otherwise have, and would add a background job that fails quietly at 3am against a lake that
 * might be down — all to keep history for tenants nobody is looking at. On a single-tenant OSS
 * install the operator *is* the viewer, so on-view sampling records exactly the history that gets
 * read. Cloud will want the sweep; it can add one without changing anything here.
 */
@Service
@NullMarked
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    /** At most one sample per tenant per day. Storage does not move fast enough to justify more. */
    private static final Duration SAMPLE_INTERVAL = Duration.ofDays(1);

    /** The window a growth figure is measured over, matching the usage screen's default. */
    private static final Duration GROWTH_WINDOW = Duration.ofDays(30);

    /**
     * How many snapshots the orphan scan will look at.
     *
     * <p>A bound, not a page size: past this the answer is reported as partial rather than the
     * scan quietly walking a table of unknown size on an admin page load. A tenant with more
     * snapshots than this has a different problem, and a truthful "at least N GB, based on the
     * first 20,000 snapshots" is more useful than a number that took a minute to produce.
     */
    private static final int ORPHAN_SCAN_LIMIT = 20_000;

    private static final int INVENTORY_PAGE = 5_000;

    /**
     * How many datasets the breakdown shows. Enough that a normal tenant sees all of them, and
     * when it is not, the screen says so rather than presenting a cut list as the whole picture.
     */
    private static final int DATASET_LIMIT = 50;

    private final LakeClient lake;
    private final StorageSampleRepository samples;
    private final SnapshotReferenceRepository references;

    /**
     * Throttles the write, not the read.
     *
     * <p>Without it, an operator refreshing the page ten times writes ten rows an hour apart from
     * nothing, and the growth baseline drifts to "an hour ago" — which reports no growth at all,
     * for a tenant that has been filling up for weeks.
     */
    private final Cache<UUID, Boolean> sampleThrottle = Caffeine.newBuilder()
            .expireAfterWrite(SAMPLE_INTERVAL)
            .maximumSize(10_000)
            .build();

    public StorageService(LakeClient lake, StorageSampleRepository samples,
                          SnapshotReferenceRepository references) {
        this.lake = lake;
        this.samples = samples;
        this.references = references;
    }

    /**
     * The storage picture for the caller's tenant.
     *
     * @throws AccessDeniedException if the caller is not a tenant owner.
     */
    public StorageReport read(AuthenticatedUser actor) {
        if (!actor.canAdministerTenant()) {
            throw new AccessDeniedException("only a tenant owner can view storage");
        }
        UUID tenantId = actor.tenantId();
        LakeClient.StorageUsage now = lake.storageUsage(tenantId, DATASET_LIMIT);

        recordSampleIfDue(tenantId, now);

        Optional<StorageSample> baseline =
                samples.earliestSince(tenantId, Instant.now().minus(GROWTH_WINDOW));

        return new StorageReport(
                now.snapshotCount(),
                now.logicalBytes(),
                now.physicalBytes(),
                now.oldestSnapshotAt(),
                now.newestSnapshotAt(),
                now.datasets().stream()
                        .map(d -> new DatasetStorage(d.datasetId(), d.snapshotCount(), d.logicalBytes()))
                        .toList(),
                baseline.map(StorageSample::physicalBytes).orElse(null),
                baseline.map(StorageSample::measuredAt).orElse(null),
                // Equal, not greater: the lake returns at most what was asked for, so hitting the
                // limit exactly is the only signal that there may be more.
                now.datasets().size() >= DATASET_LIMIT,
                unreferenced(tenantId));
    }

    /**
     * Bytes nothing in Aran points at any more.
     *
     * <p>For a local operator this is the number that converts directly into free disk: every
     * product refresh writes a new parquet and keeps the old one, so a nightly-materialized
     * product accumulates a year of copies that nothing can reach.
     *
     * <p>Read-only, and nothing here deletes. It is also the input to deciding whether deletion is
     * worth building at all — a tenant with 40 MB of orphans does not need a retention policy.
     *
     * <p>Failures are swallowed into an "unknown" result rather than propagated: the current-size
     * figure above it is the one someone opened this page for, and a lake that cannot page through
     * its inventory must not take that away too.
     */
    private Orphans unreferenced(UUID tenantId) {
        try {
            Set<String> referenced = references.referencedSnapshotIds(
                    new TenantContext(tenantId.toString()));

            long bytes = 0;
            long count = 0;
            long scanned = 0;
            boolean complete = true;

            while (scanned < ORPHAN_SCAN_LIMIT) {
                List<LakeClient.SnapshotInventoryItem> page = lake.snapshotInventory(
                        tenantId, INVENTORY_PAGE, (int) scanned);
                if (page.isEmpty()) {
                    break;
                }
                for (LakeClient.SnapshotInventoryItem item : page) {
                    if (!referenced.contains(item.snapshotId())) {
                        bytes += item.sizeBytes();
                        count++;
                    }
                }
                scanned += page.size();
                if (page.size() < INVENTORY_PAGE) {
                    break;
                }
                complete = scanned < ORPHAN_SCAN_LIMIT;
            }

            return new Orphans(bytes, count, scanned, complete);
        } catch (Exception e) {
            log.warn("could not compute unreferenced storage for tenant {}", tenantId, e);
            return Orphans.unknown();
        }
    }

    /**
     * Write today's reading, if today's has not been written.
     *
     * <p>Failures are swallowed: this is the history behind a trend line, and taking down the
     * screen that shows current storage because the row could not be inserted would be losing the
     * useful half to protect the ornamental one.
     */
    private void recordSampleIfDue(UUID tenantId, LakeClient.StorageUsage usage) {
        if (sampleThrottle.getIfPresent(tenantId) != null) {
            return;
        }
        sampleThrottle.put(tenantId, Boolean.TRUE);
        try {
            samples.save(new StorageSample(UUID.randomUUID(), tenantId, Instant.now(),
                    usage.logicalBytes(), usage.physicalBytes(), usage.snapshotCount()));
        } catch (Exception e) {
            log.debug("could not record a storage sample for tenant {}", tenantId, e);
        }
    }

    /**
     * @param baselinePhysicalBytes what the tenant held at the start of the growth window, or null
     *                              if it has not been measured for that long. Null rather than the
     *                              oldest reading available: a tenant first sampled three days ago
     *                              has no honest 30-day baseline, and reaching further back than
     *                              the data goes would report the start of measurement as growth.
     */
    public record StorageReport(
            long snapshotCount,
            long logicalBytes,
            long physicalBytes,
            @Nullable Instant oldestSnapshotAt,
            @Nullable Instant newestSnapshotAt,
            List<DatasetStorage> datasets,
            @Nullable Long baselinePhysicalBytes,
            @Nullable Instant baselineMeasuredAt,
            boolean datasetsTruncated,
            Orphans unreferenced
    ) {
    }

    /**
     * Snapshots nothing references.
     *
     * @param known    false when the scan could not run at all. Distinguished from "zero orphans"
     *                 on purpose: telling an operator there is nothing to reclaim when in fact
     *                 nobody looked is the one wrong answer this must never give.
     * @param complete false when the scan hit {@link #ORPHAN_SCAN_LIMIT}, so the figure is a lower
     *                 bound rather than the total.
     */
    public record Orphans(long bytes, long snapshots, long scanned, boolean complete, boolean known) {

        Orphans(long bytes, long snapshots, long scanned, boolean complete) {
            this(bytes, snapshots, scanned, complete, true);
        }

        static Orphans unknown() {
            return new Orphans(0, 0, 0, false, false);
        }
    }

    /** Per-dataset breakdown, largest first, as the lake ordered it. */
    public record DatasetStorage(UUID datasetId, long snapshotCount, long logicalBytes) {
    }
}

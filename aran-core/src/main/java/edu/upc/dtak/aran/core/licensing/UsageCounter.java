package edu.upc.dtak.aran.core.licensing;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence port for tenant usage counters — the DB side of the
 * limit/usage composition.
 *
 * <p>Implementations live in the persistence adapter and do indexed
 * {@code COUNT(*)} queries. Kept as a port (rather than reusing the
 * project / tenant repositories) so the licensing layer has a small, focused
 * dependency that documents exactly what it asks the DB for.
 *
 * <p>Why this lives in the licensing package, not the domain package: these
 * counters are <em>only</em> ever consumed by {@link LicenseService} and have
 * no meaning outside it. Keeping them out of the general repository surface
 * stops them from being misused for non-licensing decisions.
 */
@NullMarked
public interface UsageCounter {

    /** Number of {@code Membership} rows in the tenant. */
    long countUsers(UUID tenantId);

    /** Number of {@code Project} rows in the tenant. */
    long countProjects(UUID tenantId);

    /**
     * Roughly how many bytes the tenant is holding, without asking the lake.
     *
     * <p>The newest {@code storage_sample}, plus every byte recorded as written since that
     * sample was taken. Deliberately an estimate: the exact figure lives in the lake and costs a
     * network round trip, and this is called on the upload path, where the cost of being a few
     * megabytes stale is nothing and the cost of a synchronous cross-service call on every write
     * is real.
     *
     * <p>It errs high rather than low — deletions between samples are not subtracted, because
     * nothing deletes yet — which is the right direction for a ceiling. A tenant is briefly told
     * it is fuller than it is, rather than being let past the limit.
     *
     * <p>Zero when the tenant has never been sampled and has written nothing. On a fresh install
     * that is correct; after a first upload the accrual term carries it.
     */
    long estimatedStorageBytes(UUID tenantId);

    /** Bytes sent to clients since {@code since} — the {@code EGRESS}-direction usage events. */
    long egressBytesSince(UUID tenantId, java.time.Instant since);
}

package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence port for {@link DatasetRefresh}.
 *
 * <p>Tenant-scoped like every other repository port, with one deliberate exception:
 * {@link #claimDue} is not, because the scheduler runs for the whole installation and has no
 * tenant to be in. Everything it hands back carries {@link DatasetRefresh#tenantId()}, and the
 * caller enters that tenant's context before touching anything else — see the note on that method.
 */
@NullMarked
public interface DatasetRefreshRepository {

    /** The refresh record for this dataset, or empty if it has no upstream to refresh from. */
    Optional<DatasetRefresh> findByDataset(TenantContext tenant, UUID datasetId);

    /** Every refresh record under a repository, for listing datasets with their freshness. */
    List<DatasetRefresh> findAllByRepository(TenantContext tenant, UUID repositoryId);

    DatasetRefresh save(TenantContext tenant, DatasetRefresh refresh);

    /** Drop the record, e.g. when a user sets a dataset back to manual-only or deletes it. */
    void delete(TenantContext tenant, UUID datasetId);

    /**
     * Atomically take ownership of up to {@code limit} refreshes that are due at {@code now},
     * advancing each one's next run before returning it.
     *
     * <p><b>Claim and return in one statement, not read-then-write.</b> Aran can run more than one
     * instance against one database, and two schedulers reading the same due row a millisecond
     * apart would both extract the same table and race for the dataset's snapshot pointer. The
     * implementation must make the advance the condition of the read (an {@code UPDATE … WHERE
     * next_run_at <= now … RETURNING}, or a locking select in the same transaction), so exactly one
     * caller sees each row.
     *
     * <p>Advancing before the work runs also bounds the damage from a crash: a scheduler that dies
     * mid-refresh costs one interval of extra staleness, where advancing afterwards would leave the
     * row permanently due and every instance retrying it forever.
     *
     * <p>Not tenant-scoped: the scheduler serves the installation. Callers must enter the
     * {@link DatasetRefresh#tenantId()} of each returned row before doing anything with it.
     *
     * @param limit ceiling on one poll, so a backlog is worked through over several cycles instead
     *              of starting hundreds of extracts at once
     */
    List<DatasetRefresh> claimDue(Instant now, int limit);
}

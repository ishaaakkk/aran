package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.source.RefreshPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A materialized dataset's upstream, and how often Aran goes back to it.
 *
 * <h2>Why a separate record rather than columns on {@link Dataset}</h2>
 *
 * <p>Because most datasets do not have one. An uploaded CSV <em>is</em> its own source: there is
 * nothing to refresh from, and {@code RefreshPolicy.Never} is not a setting it holds but a fact
 * about it. Representing that as the absence of a row makes it impossible to express the
 * contradiction — a dataset with no upstream and an hourly schedule — that nullable columns on
 * {@code Dataset} would let through and then need a check constraint and a validator to forbid.
 *
 * <p>It also keeps the scheduler's query honest. "Which datasets are due?" is a scan of a small
 * table with an index on {@link #nextRunAt()}, not a scan of every dataset in the installation
 * filtering on a column that is null for almost all of them.
 *
 * @param createdBy   who a scheduled refresh runs as. A schedule has no logged-in user, and the
 *                    alternative to naming one is a system identity that bypasses authorization.
 *                    Running as the person who set the schedule gives the refresh exactly their
 *                    access: if they lose the project, it starts failing visibly rather than
 *                    continuing to read a source its owner can no longer see
 * @param sourceTable the relation in the upstream repository, exactly as
 *                    {@code DataRepositoryService.listAvailableDatasets} reports it — a refresh
 *                    re-checks membership against that list rather than trusting this string,
 *                    because a table can be dropped between the import and the refresh
 * @param nextRunAt   when the scheduler should next consider this, or null under
 *                    {@link RefreshPolicy.Manual} — null means "never due", which is what makes
 *                    the scheduler's query a plain {@code nextRunAt <= now} with no policy
 *                    inspection at all
 * @param lastError   why the last refresh failed, or null if it succeeded or has not run. Kept on
 *                    the row rather than left in the job log because a schedule that has been
 *                    quietly failing for a week is the thing a user most needs to see, and job
 *                    rows are pruned
 */
@NullMarked
public record DatasetRefresh(
        UUID datasetId,
        UUID tenantId,
        UUID repositoryId,
        UUID projectId,
        UUID createdBy,
        String sourceTable,
        RefreshPolicy policy,
        @Nullable Instant lastRefreshAt,
        @Nullable Instant nextRunAt,
        @Nullable String lastError
) {

    public DatasetRefresh {
        if (sourceTable == null || sourceTable.isBlank()) {
            throw new IllegalArgumentException("sourceTable must not be blank");
        }
        boolean scheduled = policy instanceof RefreshPolicy.Every;
        if (scheduled && nextRunAt == null) {
            throw new IllegalArgumentException(
                    "a scheduled refresh must know when it next runs, or the scheduler will never "
                    + "pick it up and the schedule will silently do nothing");
        }
        if (!scheduled && nextRunAt != null) {
            throw new IllegalArgumentException(
                    "only a scheduled refresh has a next run (policy is " + policy.describe() + ")");
        }
    }

    /** A newly imported table: it has an upstream, and nothing runs until someone asks. */
    public static DatasetRefresh manual(UUID datasetId, UUID tenantId, UUID repositoryId,
                                        UUID projectId, UUID createdBy, String sourceTable) {
        return new DatasetRefresh(datasetId, tenantId, repositoryId, projectId, createdBy,
                sourceTable, new RefreshPolicy.Manual(), null, null, null);
    }

    /**
     * The same upstream under a new policy, with the next run recomputed from {@code now}.
     *
     * <p>Anchored to now rather than to the last refresh, so that shortening an interval on a
     * dataset that has not been refreshed in days does not fire immediately and then again at the
     * new interval — a user changing a setting is not asking for a refresh.
     */
    public DatasetRefresh withPolicy(RefreshPolicy newPolicy, Instant now) {
        Instant next = newPolicy instanceof RefreshPolicy.Every every
                ? now.plus(every.interval())
                : null;
        return new DatasetRefresh(datasetId, tenantId, repositoryId, projectId, createdBy, sourceTable,
                newPolicy, lastRefreshAt, next, lastError);
    }

    /**
     * Move the next run forward one interval from {@code now}, without recording an outcome.
     *
     * <p>Called <em>before</em> the refresh runs, not after. A refresh that takes longer than its
     * interval must not be picked up again while it is still going: two extracts of the same table
     * would race for one dataset's snapshot pointer and the loser's work would be thrown away
     * after being paid for. Claiming first means the worst case is a skipped cycle rather than a
     * duplicated one, and a crash mid-refresh costs one interval of staleness rather than a hot
     * loop of retries.
     */
    public DatasetRefresh claimed(Instant now) {
        if (!(policy instanceof RefreshPolicy.Every every)) {
            return this;
        }
        return new DatasetRefresh(datasetId, tenantId, repositoryId, projectId, createdBy, sourceTable,
                policy, lastRefreshAt, now.plus(every.interval()), lastError);
    }

    /** Record a successful refresh, clearing any error the previous one left. */
    public DatasetRefresh succeeded(Instant at) {
        return new DatasetRefresh(datasetId, tenantId, repositoryId, projectId, createdBy, sourceTable,
                policy, at, nextRunAt, null);
    }

    /**
     * Record a failure, leaving {@link #lastRefreshAt()} pointing at the last copy that actually
     * landed — which is the snapshot still being served, so it is the honest answer to "how old is
     * the data I am looking at".
     */
    public DatasetRefresh failed(String error) {
        return new DatasetRefresh(datasetId, tenantId, repositoryId, projectId, createdBy, sourceTable,
                policy, lastRefreshAt, nextRunAt, error);
    }

    /** How stale the served copy is as of {@code now}, or empty if it has never been refreshed. */
    public java.util.Optional<Duration> stalenessAt(Instant now) {
        return lastRefreshAt == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(Duration.between(lastRefreshAt, now));
    }
}

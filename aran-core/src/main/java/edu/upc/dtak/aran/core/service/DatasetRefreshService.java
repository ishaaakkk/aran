package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.source.RefreshPolicy;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A materialized dataset's refresh policy, and the scheduler that honours it.
 *
 * <p>Owns the policy; {@code DatasetService} owns the copy. The split is deliberate — how often to
 * remake a copy is a scheduling question, and how to remake it is the source-access question that
 * {@code RelationalEngine} already answers. Merging them would put a scheduler inside the service
 * that also does uploads, bootstraps and licence checks.
 */
@Service
@NullMarked
@Transactional
public class DatasetRefreshService {

    private static final Logger log = LoggerFactory.getLogger(DatasetRefreshService.class);

    /**
     * How many due refreshes one poll starts.
     *
     * <p>A ceiling rather than "all of them": a backlog — an installation restarted after a day
     * down, say — would otherwise start every overdue extract at once, and each one holds a whole
     * parquet in the backend's heap. The rest are picked up on the next tick, seconds later.
     */
    static final int MAX_PER_POLL = 8;

    private final DatasetRefreshRepository refreshes;
    private final DatasetService datasets;
    private final DataRepositoryService repositories;
    private final IdentityService identity;
    private final edu.upc.dtak.aran.core.domain.UserRepository users;

    public DatasetRefreshService(DatasetRefreshRepository refreshes,
                                 DatasetService datasets,
                                 DataRepositoryService repositories,
                                 IdentityService identity,
                                 edu.upc.dtak.aran.core.domain.UserRepository users) {
        this.refreshes = refreshes;
        this.datasets = datasets;
        this.repositories = repositories;
        this.identity = identity;
        this.users = users;
    }

    /** This dataset's refresh policy, or empty if it has no upstream to refresh from. */
    @Transactional(readOnly = true)
    public Optional<DatasetRefresh> find(AuthenticatedUser caller, UUID projectId,
                                         UUID repositoryId, UUID datasetId) {
        repositories.getRepository(caller, projectId, repositoryId);
        return refreshes.findByDataset(tenantOf(caller), datasetId);
    }

    /** Every refresh policy under a repository, for listing datasets with their freshness. */
    @Transactional(readOnly = true)
    public List<DatasetRefresh> findAll(AuthenticatedUser caller, UUID projectId, UUID repositoryId) {
        repositories.getRepository(caller, projectId, repositoryId);
        return refreshes.findAllByRepository(tenantOf(caller), repositoryId);
    }

    /**
     * Change how often this dataset is refreshed.
     *
     * <p>Refused for a dataset with no upstream. Not silently ignored and not silently created: an
     * uploaded CSV has nothing to refresh <em>from</em>, so an hourly schedule on it would be a
     * setting that could never do anything, displayed as though it would.
     */
    public DatasetRefresh setPolicy(AuthenticatedUser caller, UUID projectId, UUID repositoryId,
                                    UUID datasetId, RefreshPolicy policy) {
        repositories.getRepository(caller, projectId, repositoryId);
        TenantContext tenant = tenantOf(caller);
        DatasetRefresh current = refreshes.findByDataset(tenant, datasetId)
                .orElseThrow(() -> new NoUpstreamException(
                        "This dataset has no upstream to refresh from — its data was uploaded, so "
                        + "the file it was created from is the source. Import it from a repository "
                        + "table to make it refreshable."));
        return refreshes.save(tenant, current.withPolicy(policy, Instant.now()));
    }

    /**
     * Refresh now, whatever the policy says.
     *
     * <p>Available under {@code Every} too: "I know something changed" is a normal thing to want,
     * and refusing it because a schedule exists would push users into shortening the interval to
     * force one — which is worse for the source database than the manual refresh they asked for.
     */
    public Job refreshNow(AuthenticatedUser caller, UUID projectId, UUID repositoryId, UUID datasetId) {
        repositories.getRepository(caller, projectId, repositoryId);
        DatasetRefresh refresh = refreshes.findByDataset(tenantOf(caller), datasetId)
                .orElseThrow(() -> new NoUpstreamException(
                        "This dataset has no upstream to refresh from."));
        return datasets.refreshDataset(caller, projectId, repositoryId, datasetId, refresh);
    }

    /**
     * Start every refresh that is due, installation-wide. The scheduler's entry point.
     *
     * <p>Each claimed row is submitted independently and a failure on one is logged rather than
     * propagated: one unreachable source database must not stop every other tenant's schedule.
     * {@code claimDue} has already advanced the next run, so a failure here costs one interval of
     * staleness rather than a retry loop, and the reason is recorded on the row by
     * {@code DatasetService.refreshDataset}.
     *
     * @return how many refreshes were started
     */
    public int runDue(Instant now) {
        List<DatasetRefresh> due = refreshes.claimDue(now, MAX_PER_POLL);
        int started = 0;
        for (DatasetRefresh refresh : due) {
            try {
                datasets.refreshDataset(
                        callerFor(refresh), refresh.projectId(), refresh.repositoryId(),
                        refresh.datasetId(), refresh);
                started++;
            } catch (RuntimeException e) {
                log.warn("Scheduled refresh of dataset {} could not be started: {}",
                        refresh.datasetId(), e.toString());
                TenantContext tenant = new TenantContext(refresh.tenantId().toString());
                refreshes.findByDataset(tenant, refresh.datasetId())
                        .ifPresent(current -> refreshes.save(tenant, current.failed(
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            }
        }
        if (started > 0) {
            log.info("Started {} scheduled dataset refresh(es)", started);
        }
        return started;
    }

    /**
     * The identity a scheduled refresh runs as: the person who set the schedule.
     *
     * <p>Rebuilt from stored ids on every tick rather than captured once, which is the point. The
     * alternative — a system principal that skips authorization because a schedule has no session
     * — is an ambient superuser created to avoid answering this question, and it would keep reading
     * a source long after the only person who could see that source had lost access to it.
     *
     * <p>So the checks are live. If the account was deleted, or no longer belongs to the tenant the
     * refresh names, this throws and {@link #runDue} records the reason on the row where a user
     * looks for "why is this stale" — rather than the schedule continuing on borrowed authority.
     */
    private AuthenticatedUser callerFor(DatasetRefresh refresh) {
        var user = users.findById(refresh.createdBy())
                .orElseThrow(() -> new IllegalStateException(
                        "the account this schedule runs as (" + refresh.createdBy()
                        + ") no longer exists; set the policy again as a current user"));
        var membership = identity.soleMembership(user.id())
                .filter(m -> m.tenantId().equals(refresh.tenantId()))
                .orElseThrow(() -> new IllegalStateException(
                        "the account this schedule runs as no longer belongs to tenant "
                        + refresh.tenantId()));
        return new AuthenticatedUser(user.id(), user.subject(), membership.tenantId(), membership.role());
    }

    private static TenantContext tenantOf(AuthenticatedUser caller) {
        return new TenantContext(caller.tenantId().toString());
    }

    /** A dataset whose data was uploaded has no upstream, so there is nothing to refresh from. */
    public static class NoUpstreamException extends RuntimeException {
        public NoUpstreamException(String message) {
            super(message);
        }
    }
}

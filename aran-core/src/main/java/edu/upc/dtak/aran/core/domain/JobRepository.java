package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Persistence <em>port</em> for {@link Job}.
 *
 * <p>Tenant-scoped exactly like {@link DatasetRepository}: reads cannot cross
 * tenants, and the adapter enforces that with the shared Hibernate tenant
 * filter rather than a hand-written predicate at each call site.
 */
@NullMarked
public interface JobRepository {

    /** Insert or update a job row. */
    Job save(TenantContext tenant, Job job);

    /** @return the job, or empty if it does not exist <em>in this tenant</em>. */
    Optional<Job> findById(TenantContext tenant, UUID jobId);

    /**
     * Most recently created jobs first.
     *
     * @param projectId when non-null, only jobs owned by that project
     * @param limit     maximum rows to return
     */
    List<Job> findRecent(TenantContext tenant, @Nullable UUID projectId, int limit);

    /**
     * Fail every job the JVM left non-terminal, in <em>every</em> tenant.
     *
     * <p>The one method on this port that is deliberately not tenant-scoped. After a restart no
     * PENDING row can ever be dispatched (its after-commit hook died with the process) and no
     * RUNNING row has a thread behind it, so both are orphans whatever tenant owns them — and the
     * caller is startup code, which has no tenant to scope by. Rows already terminal are left
     * alone, which is what makes calling this twice harmless.
     *
     * @return how many rows were failed
     */
    int failOrphaned(String errorCode, String errorMessage, Instant finishedAt);
}

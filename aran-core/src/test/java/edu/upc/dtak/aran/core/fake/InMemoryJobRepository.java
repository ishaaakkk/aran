package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link JobRepository} for service tests.
 *
 * <p>Filters by tenant on read, the way the real adapter's Hibernate filter does,
 * so a test that leaks across tenants fails here rather than only in the IT.
 * Also keeps every version of every job it is handed, so a test can assert on the
 * <em>sequence</em> of states a job passed through, not just where it ended up.
 */
public class InMemoryJobRepository implements JobRepository {

    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final List<Job> history = new ArrayList<>();

    @Override
    public synchronized Job save(TenantContext tenant, Job job) {
        jobs.put(job.id(), job);
        history.add(job);
        return job;
    }

    @Override
    public synchronized Optional<Job> findById(TenantContext tenant, UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId))
                .filter(j -> j.tenantId().toString().equals(tenant.value()));
    }

    @Override
    public synchronized List<Job> findRecent(TenantContext tenant, UUID projectId, int limit) {
        return jobs.values().stream()
                .filter(j -> j.tenantId().toString().equals(tenant.value()))
                .filter(j -> projectId == null || projectId.equals(j.projectId()))
                .sorted(Comparator.comparing(Job::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized int failOrphaned(String errorCode, String errorMessage, java.time.Instant finishedAt) {
        int swept = 0;
        for (Map.Entry<UUID, Job> entry : jobs.entrySet()) {
            Job j = entry.getValue();
            if (j.isTerminal()) {
                continue;
            }
            entry.setValue(new Job(j.id(), j.tenantId(), j.projectId(), j.jobType(), JobStatus.FAILED,
                    j.parameters(), j.progressStage(), j.progressPercent(), null,
                    errorCode, errorMessage, j.createdBy(), j.createdAt(), j.startedAt(), finishedAt));
            swept++;
        }
        return swept;
    }

    /** Every state this job was persisted in, oldest first. */
    public synchronized List<JobStatus> statusHistory(UUID jobId) {
        return history.stream()
                .filter(j -> j.id().equals(jobId))
                .map(Job::status)
                .toList();
    }

    /** Every stage persisted for this job, in order. */
    public synchronized List<String> stageHistory(UUID jobId) {
        return history.stream()
                .filter(j -> j.id().equals(jobId))
                .map(Job::progressStage)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}

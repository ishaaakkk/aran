package edu.upc.dtak.aran.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.operator.ProgressReporter;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.tenant.TenantContextHolder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work that outlives the request that asked for it.
 *
 * <p>The contract callers depend on: by the time {@link #submit} returns, the
 * job is <em>persisted</em> as PENDING. Everything after that — RUNNING, each
 * stage, the terminal state — is written to the same row before it is
 * published, so a client that reconnects after missing every event can still
 * recover the whole truth with a single {@code GET /api/jobs/{id}}.
 *
 * <p><b>Tenant propagation.</b> {@link TenantContextHolder} is a
 * {@link ScopedValue}, which does <em>not</em> follow a task onto an executor
 * thread. The tenant is therefore captured at submit time (it is an explicit
 * parameter, not a read of the ambient scope) and re-bound around the task body
 * on the worker thread. Persistence inside the task then sees the right tenant
 * and the Hibernate tenant filter applies as it does on a request thread.
 *
 * <p>This service is deliberately not a {@code @Service}: it needs an
 * {@link Executor} and a {@link JobEventPublisher}, both of which are chosen by
 * the application layer. aran-application declares it as a bean.
 */
@NullMarked
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /**
     * The body of a job. Returns the result payload; anything it throws fails the job.
     */
    @FunctionalInterface
    public interface JobTask {
        Map<String, String> run(ProgressReporter progress) throws Exception;
    }

    private final JobRepository jobs;
    private final Executor executor;
    private final JobEventPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobService(JobRepository jobs, Executor executor, JobEventPublisher publisher) {
        this.jobs = jobs;
        this.executor = executor;
        this.publisher = publisher;
    }

    /**
     * Persist a PENDING job and hand the task to the executor.
     *
     * @return the persisted PENDING job — its id is valid immediately, before
     *         the task has run
     */
    public Job submit(TenantContext tenant,
                      @Nullable UUID projectId,
                      String jobType,
                      Map<String, String> parameters,
                      @Nullable UUID actor,
                      JobTask task) {

        Job pending = Job.pending(
                UUID.randomUUID(),
                UUID.fromString(tenant.value()),
                projectId,
                jobType,
                parameters,
                actor,
                Instant.now());

        Job saved = jobs.save(tenant, pending);
        publisher.publish(saved.id(), saved);

        dispatch(() -> executor.execute(
                () -> TenantContextHolder.runWith(tenant, () -> run(tenant, saved, task))));
        return saved;
    }

    /**
     * Hand the task to the executor — but never before the PENDING row is committed.
     *
     * <p>When {@code submit} is called inside a transaction (a {@code @Transactional}
     * service method, say), the insert above is not yet visible to any other
     * connection. A worker that starts immediately would fail to find the row, insert
     * it again, and die on the primary key. Waiting for the commit also means a caller
     * whose transaction rolls back never gets a job running for work that did not
     * happen.
     *
     * <p>With no transaction in progress the row is already durable, so the task goes
     * out at once.
     */
    private void dispatch(Runnable submitToExecutor) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submitToExecutor.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitToExecutor.run();
            }
        });
    }

    /** @return the job, or empty if it does not exist in this tenant. */
    public java.util.Optional<Job> find(TenantContext tenant, UUID jobId) {
        return jobs.findById(tenant, jobId);
    }

    /** @return recent jobs, newest first, optionally narrowed to one project. */
    public java.util.List<Job> listRecent(TenantContext tenant, @Nullable UUID projectId, int limit) {
        return jobs.findRecent(tenant, projectId, limit);
    }

    private void run(TenantContext tenant, Job pending, JobTask task) {
        // The task's view of the row, kept current so each progress write and the
        // terminal write build on the latest state rather than on the stale insert.
        AtomicReference<Job> current = new AtomicReference<>(
                persistAndPublish(tenant, new Job(
                        pending.id(), pending.tenantId(), pending.projectId(), pending.jobType(),
                        JobStatus.RUNNING, pending.parameters(), null, null, null, null, null,
                        pending.createdBy(), pending.createdAt(), Instant.now(), null)));

        ProgressReporter progress = new ProgressReporter() {
            @Override
            public void stage(String stage) {
                Job j = current.get();
                current.set(persistAndPublish(tenant, withProgress(j, stage, j.progressPercent())));
            }

            @Override
            public void percent(int percent) {
                Job j = current.get();
                current.set(persistAndPublish(tenant, withProgress(j, j.progressStage(), percent)));
            }
        };

        try {
            Map<String, String> result = task.run(progress);
            Job j = current.get();
            persistAndPublish(tenant, new Job(
                    j.id(), j.tenantId(), j.projectId(), j.jobType(), JobStatus.SUCCEEDED, j.parameters(),
                    j.progressStage(), j.progressPercent(), toJson(result), null, null,
                    j.createdBy(), j.createdAt(), j.startedAt(), Instant.now()));
        } catch (Throwable ex) {
            // Throwable, not Exception: an Error (the planner's OOM from PERF-P1, a
            // StackOverflowError on a deep plan, an ExceptionInInitializerError) would otherwise
            // escape, kill the virtual thread, and leave the row RUNNING forever — indistinguishable
            // from a live job to the UI's spinner and to the refresh scheduler.
            log.warn("Job {} ({}) failed", pending.id(), pending.jobType(), ex);
            String errorCode = ex instanceof JobFailureException failure
                    ? failure.errorCode()
                    : ex.getClass().getSimpleName();
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
            Job j = current.get();
            persistAndPublish(tenant, new Job(
                    j.id(), j.tenantId(), j.projectId(), j.jobType(), JobStatus.FAILED, j.parameters(),
                    j.progressStage(), j.progressPercent(), null, errorCode, errorMessage,
                    j.createdBy(), j.createdAt(), j.startedAt(), Instant.now()));
            // Catching a VirtualMachineError is for the row's sake only. The JVM is in an
            // unrecoverable state and every other thread needs to see that, so the row is written
            // first and the Error then continues on its way.
            if (ex instanceof VirtualMachineError fatal) {
                throw fatal;
            }
        }
    }

    private static Job withProgress(Job j, @Nullable String stage, @Nullable Integer percent) {
        return new Job(j.id(), j.tenantId(), j.projectId(), j.jobType(), j.status(), j.parameters(),
                stage, percent, j.resultJson(), j.errorCode(), j.errorMessage(),
                j.createdBy(), j.createdAt(), j.startedAt(), j.finishedAt());
    }

    private Job persistAndPublish(TenantContext tenant, Job job) {
        Job saved = jobs.save(tenant, job);
        try {
            publisher.publish(saved.id(), saved);
        } catch (RuntimeException ex) {
            // A listener that has gone away must not fail the job it was watching.
            log.debug("Publishing job {} to listeners failed", saved.id(), ex);
        }
        return saved;
    }

    private String toJson(Map<String, String> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialise job result", ex);
        }
    }
}

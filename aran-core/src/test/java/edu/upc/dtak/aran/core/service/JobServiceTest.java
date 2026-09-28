package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.fake.InMemoryJobRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.tenant.TenantContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JobService} against an in-memory repository and a direct executor, so
 * the task runs inline and every assertion is about state that was actually
 * persisted rather than about timing.
 */
@DisplayName("JobService")
class JobServiceTest {

    /** Runs the task on the calling thread — no scheduling, no sleeps in tests. */
    private static final Executor DIRECT = Runnable::run;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantContext TENANT = new TenantContext(TENANT_ID.toString());

    private InMemoryJobRepository jobs;
    private List<Job> published;
    private JobService service;

    @BeforeEach
    void setUp() {
        jobs = new InMemoryJobRepository();
        published = new ArrayList<>();
        service = new JobService(jobs, DIRECT, (jobId, snapshot) -> published.add(snapshot));
    }

    @Test
    @DisplayName("a successful task moves PENDING → RUNNING → SUCCEEDED and stores its result")
    void success_path() {
        Job submitted = service.submit(TENANT, null, "test:ok", Map.of("k", "v"), null,
                progress -> Map.of("rows", "42"));

        assertThat(jobs.statusHistory(submitted.id()))
                .containsExactly(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.SUCCEEDED);

        Job finished = jobs.findById(TENANT, submitted.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(finished.resultJson()).isEqualTo("{\"rows\":\"42\"}");
        assertThat(finished.parameters()).containsEntry("k", "v");
        assertThat(finished.startedAt()).isNotNull();
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(finished.errorCode()).isNull();
    }

    @Test
    @DisplayName("every state transition is published")
    void publishes_every_transition() {
        Job submitted = service.submit(TENANT, null, "test:ok", Map.of(), null,
                progress -> Map.of());

        assertThat(published)
                .allMatch(j -> j.id().equals(submitted.id()))
                .extracting(Job::status)
                .containsExactly(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("progress stages persist as the task reports them")
    void progress_persists() {
        Job submitted = service.submit(TENANT, null, "test:staged", Map.of(), null, progress -> {
            progress.stage("downloading snapshot");
            progress.percent(50);
            progress.stage("done");
            return Map.of();
        });

        assertThat(jobs.stageHistory(submitted.id()))
                .containsSubsequence("downloading snapshot", "done");

        Job finished = jobs.findById(TENANT, submitted.id()).orElseThrow();
        assertThat(finished.progressStage()).isEqualTo("done");
        assertThat(finished.progressPercent()).isEqualTo(50);
    }

    @Test
    @DisplayName("a throwing task FAILS the job and stores the error, without propagating")
    void failure_path() {
        Job submitted = service.submit(TENANT, null, "test:boom", Map.of(), null, progress -> {
            progress.stage("about to fail");
            throw new IllegalStateException("snapshot is unreadable");
        });

        Job finished = jobs.findById(TENANT, submitted.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("IllegalStateException");
        assertThat(finished.errorMessage()).isEqualTo("snapshot is unreadable");
        assertThat(finished.progressStage()).isEqualTo("about to fail");
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(finished.resultJson()).isNull();
    }

    @Test
    @DisplayName("a JobFailureException keeps its own error code on the row")
    void failure_keeps_coded_error() {
        Job submitted = service.submit(TENANT, null, "test:coded", Map.of(), null, progress -> {
            throw new JobFailureException("UNSUPPORTED_OPERATION", "no such operation 'frobnicate'");
        });

        Job finished = jobs.findById(TENANT, submitted.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("UNSUPPORTED_OPERATION");
        assertThat(finished.errorMessage()).isEqualTo("no such operation 'frobnicate'");
    }

    @Test
    @DisplayName("a non-fatal Error FAILS the job instead of stranding it RUNNING, and does not propagate")
    void error_fails_the_job() {
        Job submitted = service.submit(TENANT, null, "test:error", Map.of(), null, progress -> {
            progress.stage("planning");
            throw new ExceptionInInitializerError("static init blew up");
        });

        Job finished = jobs.findById(TENANT, submitted.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("ExceptionInInitializerError");
        assertThat(finished.errorMessage()).isEqualTo("static init blew up");
        assertThat(finished.progressStage()).isEqualTo("planning");
        assertThat(finished.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("an OutOfMemoryError FAILS the job and then propagates — a dying JVM is not swallowed")
    void oom_fails_the_job_and_propagates() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                service.submit(TENANT, null, "test:oom", Map.of(), null, progress -> {
                    throw new OutOfMemoryError("Java heap space");
                }));

        assertThat(thrown)
                .as("a VirtualMachineError is written to the row and then rethrown, never swallowed")
                .isInstanceOf(OutOfMemoryError.class);

        Job finished = jobs.findRecent(TENANT, null, 1).getFirst();
        assertThat(finished.status()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("OutOfMemoryError");
        assertThat(finished.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("the task runs with the submitting tenant bound, not the ambient one")
    void rebinds_tenant_on_the_worker() {
        List<String> seen = new ArrayList<>();
        service.submit(TENANT, null, "test:tenant", Map.of(), null, progress -> {
            seen.add(TenantContextHolder.require().value());
            return Map.of();
        });

        assertThat(seen).containsExactly(TENANT_ID.toString());
    }

    @Test
    @DisplayName("inside a transaction the task waits for the commit, so the worker sees the row")
    void defers_dispatch_until_commit() {
        List<String> ran = new ArrayList<>();

        TransactionSynchronizationManager.initSynchronization();
        try {
            Job submitted = service.submit(TENANT, null, "test:tx", Map.of(), null, progress -> {
                ran.add("task");
                return Map.of();
            });

            // Mid-transaction the PENDING insert is not yet visible to another
            // connection. A worker started now would not find the row, would insert it
            // again, and would die on the primary key — so it must not have started.
            assertThat(ran).isEmpty();
            assertThat(jobs.findById(TENANT, submitted.id()).orElseThrow().status())
                    .isEqualTo(JobStatus.PENDING);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            assertThat(ran).containsExactly("task");
            assertThat(jobs.findById(TENANT, submitted.id()).orElseThrow().status())
                    .isEqualTo(JobStatus.SUCCEEDED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("findRecent narrows to a project and stays inside the tenant")
    void find_recent_scopes() {
        UUID projectId = UUID.randomUUID();
        service.submit(TENANT, projectId, "test:a", Map.of(), null, progress -> Map.of());
        service.submit(TENANT, null, "test:b", Map.of(), null, progress -> Map.of());

        assertThat(service.listRecent(TENANT, projectId, 10))
                .extracting(Job::jobType)
                .containsExactly("test:a");
        assertThat(service.listRecent(TENANT, null, 10)).hasSize(2);

        TenantContext other = new TenantContext(UUID.randomUUID().toString());
        assertThat(service.listRecent(other, null, 10)).isEmpty();
    }
}

package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Hibernate tenantFilter on JobEntity keeps jobs inside their tenant —
 * the calling code contains no {@code WHERE tenant_id} of its own.
 */
@DisplayName("Job persistence — tenant isolation")
class JobTenantIsolationIT extends BaseIntegrationTest {

    @Autowired JobRepository jobs;

    @Test
    @Transactional
    @DisplayName("tenant A cannot read, list, or overwrite tenant B's job")
    void jobs_are_isolated_by_tenant() {
        UUID tenantAId = UUID.randomUUID();
        UUID tenantBId = UUID.randomUUID();
        var ctxA = new TenantContext(tenantAId.toString());
        var ctxB = new TenantContext(tenantBId.toString());

        Job jobA = jobs.save(ctxA, Job.pending(UUID.randomUUID(), tenantAId, null,
                "test:a", Map.of("p", "1"), null, Instant.now()));
        Job jobB = jobs.save(ctxB, Job.pending(UUID.randomUUID(), tenantBId, null,
                "test:b", Map.of("p", "2"), null, Instant.now()));

        // findById is tenant-scoped: B's id under A's context is simply not found.
        assertThat(jobs.findById(ctxA, jobA.id())).isPresent();
        assertThat(jobs.findById(ctxA, jobB.id()))
                .as("tenant A must not be able to read tenant B's job")
                .isEmpty();

        // Listing never crosses the boundary either.
        assertThat(jobs.findRecent(ctxA, null, 50))
                .extracting(Job::id)
                .containsExactly(jobA.id())
                .doesNotContain(jobB.id());

        // Round-trip: the JSONB parameters survive.
        assertThat(jobs.findById(ctxA, jobA.id()).orElseThrow().parameters())
                .containsExactly(Map.entry("p", "1"));
    }

    @Test
    @Transactional
    @DisplayName("saving a job whose tenant differs from the active context is rejected")
    void save_rejects_tenant_mismatch() {
        UUID tenantAId = UUID.randomUUID();
        UUID tenantBId = UUID.randomUUID();
        var ctxA = new TenantContext(tenantAId.toString());

        Job foreign = Job.pending(UUID.randomUUID(), tenantBId, null,
                "test:foreign", Map.of(), null, Instant.now());

        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> jobs.save(ctxA, foreign)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    @DisplayName("a job's status, progress and result are updated in place")
    void save_updates_in_place() {
        UUID tenantId = UUID.randomUUID();
        var ctx = new TenantContext(tenantId.toString());

        Job pending = jobs.save(ctx, Job.pending(UUID.randomUUID(), tenantId, null,
                "test:update", Map.of(), null, Instant.now()));

        jobs.save(ctx, new Job(pending.id(), tenantId, null, "test:update", JobStatus.SUCCEEDED,
                Map.of(), "done", 100, "{\"rows\":\"7\"}", null, null,
                null, pending.createdAt(), Instant.now(), Instant.now()));

        Job reloaded = jobs.findById(ctx, pending.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(reloaded.progressStage()).isEqualTo("done");
        assertThat(reloaded.progressPercent()).isEqualTo(100);
        assertThat(reloaded.resultJson()).contains("\"rows\"");
        assertThat(jobs.findRecent(ctx, null, 50))
                .as("an update must not insert a second row")
                .hasSize(1);
    }
}

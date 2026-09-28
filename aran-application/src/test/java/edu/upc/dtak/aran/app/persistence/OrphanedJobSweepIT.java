package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.app.config.OrphanedJobSweep;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup sweep, against a real Postgres.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: the sweep is one JPQL bulk update, which
 * bypasses the persistence context, so a test holding everything in one transaction would assert
 * against stale managed entities rather than against what the database now holds.
 *
 * <p>Two tenants, because the sweep runs at startup with no tenant bound and must not be narrowed
 * by the {@code tenantFilter} on {@code JobEntity} — an orphan the sweep skips is an orphan that
 * spins in the UI forever.
 */
@DisplayName("Job persistence — orphaned-on-restart sweep")
class OrphanedJobSweepIT extends BaseIntegrationTest {

    @Autowired JobRepository jobs;
    @Autowired OrphanedJobSweep sweep;
    @Autowired DataProductRepository products;
    @Autowired ProjectRepository projects;
    @Autowired UserRepository users;

    @Test
    @DisplayName("PENDING and RUNNING rows in every tenant become FAILED; a terminal row is untouched")
    void sweeps_non_terminal_rows_across_tenants() {
        UUID tenantAId = UUID.randomUUID();
        UUID tenantBId = UUID.randomUUID();
        var ctxA = new TenantContext(tenantAId.toString());
        var ctxB = new TenantContext(tenantBId.toString());

        UUID pendingA = seed(ctxA, tenantAId, "test:pending-a", JobStatus.PENDING);
        UUID runningA = seed(ctxA, tenantAId, "test:running-a", JobStatus.RUNNING);
        UUID succeededA = seed(ctxA, tenantAId, "test:succeeded-a", JobStatus.SUCCEEDED);
        UUID pendingB = seed(ctxB, tenantBId, "test:pending-b", JobStatus.PENDING);
        UUID runningB = seed(ctxB, tenantBId, "test:running-b", JobStatus.RUNNING);

        int swept = sweep.sweep();

        assertThat(swept).isGreaterThanOrEqualTo(4);

        for (UUID orphan : java.util.List.of(pendingA, runningA)) {
            assertOrphaned(ctxA, orphan);
        }
        for (UUID orphan : java.util.List.of(pendingB, runningB)) {
            assertOrphaned(ctxB, orphan);
        }

        Job terminal = jobs.findById(ctxA, succeededA).orElseThrow();
        assertThat(terminal.status())
                .as("a terminal row is not rewritten by the sweep")
                .isEqualTo(JobStatus.SUCCEEDED);
        assertThat(terminal.errorCode()).isNull();
        // Compared loosely: Postgres re-renders JSONB with its own spacing.
        assertThat(terminal.resultJson()).contains("\"rows\"", "\"1\"");
    }

    @Test
    @DisplayName("a second sweep over the same rows changes nothing — FAILED is terminal")
    void sweep_is_idempotent() {
        UUID tenantId = UUID.randomUUID();
        var ctx = new TenantContext(tenantId.toString());
        UUID pending = seed(ctx, tenantId, "test:twice", JobStatus.PENDING);

        sweep.sweep();
        Instant firstFinishedAt = jobs.findById(ctx, pending).orElseThrow().finishedAt();

        sweep.sweep();

        Job after = jobs.findById(ctx, pending).orElseThrow();
        assertThat(after.errorCode()).isEqualTo(OrphanedJobSweep.ERROR_CODE);
        assertThat(after.finishedAt())
                .as("a swept row is terminal, so a later sweep must not move its finishedAt")
                .isEqualTo(firstFinishedAt);
    }

    @Test
    @DisplayName("a product left MATERIALIZING is failed with a reason; a READY one is untouched (J6-F7)")
    void sweeps_products_stuck_materializing() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var ctx = new TenantContext(tenantId.toString());
        User alice = users.findBySubject(JwtTestHelper.ALICE_SUBJECT)
                .orElseGet(() -> users.save(new User(UUID.randomUUID(),
                        JwtTestHelper.ALICE_SUBJECT,
                        JwtTestHelper.ALICE_EMAIL,
                        JwtTestHelper.ALICE_NAME, Instant.now())));
        UUID projectId = UUID.randomUUID();
        projects.save(ctx, new Project(projectId, tenantId,
                "sweep-" + projectId, "", alice.id(), Instant.now()));

        UUID stuck = seedProduct(ctx, tenantId, projectId, "stuck",
                DataProductStatus.MATERIALIZING);
        UUID ready = seedProduct(ctx, tenantId, projectId, "ready",
                DataProductStatus.READY);

        sweep.sweep();

        var after = products.findById(ctx, stuck).orElseThrow();
        assertThat(after.status()).isEqualTo(DataProductStatus.FAILED);
        assertThat(after.lastError()).isEqualTo(OrphanedJobSweep.PRODUCT_ERROR);
        assertThat(products.findById(ctx, ready).orElseThrow().status())
                .isEqualTo(DataProductStatus.READY);
    }

    private UUID seedProduct(TenantContext ctx, UUID tenantId, UUID projectId, String name,
                             DataProductStatus status) {
        UUID id = UUID.randomUUID();
        products.save(ctx, new DataProduct(id, tenantId, projectId,
                name, null, "SELECT x FROM X", UUID.randomUUID(),
                ProductAccessMode.MATERIALIZED, status,
                status == DataProductStatus.READY ? "snap-1" : null,
                status == DataProductStatus.READY ? 1L : null,
                null, status == DataProductStatus.READY ? Instant.now() : null,
                null, null, Instant.now()));
        return id;
    }

    private void assertOrphaned(TenantContext ctx, UUID jobId) {
        Job job = jobs.findById(ctx, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo(OrphanedJobSweep.ERROR_CODE);
        assertThat(job.errorMessage()).contains("restart");
        assertThat(job.finishedAt()).isNotNull();
    }

    private UUID seed(TenantContext ctx, UUID tenantId, String jobType, JobStatus status) {
        UUID id = UUID.randomUUID();
        jobs.save(ctx, new Job(id, tenantId, null, jobType, status, Map.of(),
                null, null, status == JobStatus.SUCCEEDED ? "{\"rows\":\"1\"}" : null, null, null,
                null, Instant.now(), status == JobStatus.PENDING ? null : Instant.now(),
                status == JobStatus.SUCCEEDED ? Instant.now() : null));
        return id;
    }
}

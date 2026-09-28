package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.JobEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.JobJpaRepository;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * JPA-backed implementation of the core {@link JobRepository} port.
 *
 * <p><b>Tenant scoping is explicit here.</b> Every finder names {@code tenantId}
 * in its predicate rather than relying solely on the shared {@code tenantFilter}
 * Hibernate filter, because that filter is not consulted on a load by primary
 * key — a bare {@code findById} would return another tenant's job. The filter
 * still applies (the aspect's pointcut names this class, and {@code JobEntity}
 * carries the {@code @Filter}) and is left in place as defence in depth for the
 * list queries; {@code JobTenantIsolationIT} pins the actual guarantee.
 *
 * <p>Mapping lives here rather than in {@code EntityMapper} because a job's
 * {@code parameters} column is JSONB and the conversion needs the injected
 * {@link ObjectMapper} — the same reason {@code JpaAuditAdapter} maps its own
 * entity. {@code result} arrives already serialised (the core writes a JSON
 * string), so only {@code parameters} is converted here.
 */
@Component
@NullMarked
class JpaJobRepository implements JobRepository {

    private final JobJpaRepository jpa;
    private final ObjectMapper objectMapper;

    JpaJobRepository(JobJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    public Job save(TenantContext tenant, Job job) {
        UUID tenantId = UUID.fromString(tenant.value());
        if (!job.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Job tenant ID does not match the transactional tenant context");
        }

        JobEntity entity = jpa.findByIdAndTenantId(job.id(), tenantId).orElseGet(() -> new JobEntity(
                job.id(), tenantId, job.projectId(), job.jobType(), job.status(),
                toJson(job.parameters()), job.progressStage(), job.progressPercent(),
                job.resultJson(), job.errorCode(), job.errorMessage(),
                job.createdBy(), job.createdAt(), job.startedAt(), job.finishedAt()));

        entity.setStatus(job.status());
        entity.setProgressStage(job.progressStage());
        entity.setProgressPercent(job.progressPercent());
        entity.setResult(job.resultJson());
        entity.setErrorCode(job.errorCode());
        entity.setErrorMessage(job.errorMessage());
        entity.setStartedAt(job.startedAt());
        entity.setFinishedAt(job.finishedAt());

        return toDomain(jpa.save(entity));
    }

    @Override
    public Optional<Job> findById(TenantContext tenant, UUID jobId) {
        return jpa.findByIdAndTenantId(jobId, UUID.fromString(tenant.value())).map(this::toDomain);
    }

    @Override
    public List<Job> findRecent(TenantContext tenant, @Nullable UUID projectId, int limit) {
        UUID tenantId = UUID.fromString(tenant.value());
        Limit cap = Limit.of(limit);
        List<JobEntity> rows = projectId == null
                ? jpa.findAllByTenantIdOrderByCreatedAtDesc(tenantId, cap)
                : jpa.findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId, cap);
        return rows.stream().map(this::toDomain).toList();
    }

    /**
     * Runs at startup with no tenant bound, so it is the one call here without a
     * {@code tenantId} predicate — see the port's javadoc for why that is the point rather than an
     * oversight. Its own transaction: a {@code @Modifying} query needs one, and startup code has
     * no ambient transaction to join.
     */
    @Override
    @Transactional
    public int failOrphaned(String errorCode, String errorMessage, Instant finishedAt) {
        return jpa.failNonTerminal(errorCode, errorMessage, finishedAt);
    }

    private Job toDomain(JobEntity e) {
        return new Job(
                e.getId(),
                e.getTenantId(),
                e.getProjectId(),
                e.getJobType(),
                e.getStatus(),
                fromJson(e.getParameters()),
                e.getProgressStage(),
                e.getProgressPercent(),
                e.getResult(),
                e.getErrorCode(),
                e.getErrorMessage(),
                e.getCreatedBy(),
                e.getCreatedAt(),
                e.getStartedAt(),
                e.getFinishedAt());
    }

    private String toJson(Map<String, String> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialise job parameters", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fromJson(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("corrupt job parameters JSON", ex);
        }
    }
}

package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.DatasetRefreshEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.DatasetRefreshJpaRepository;
import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.source.RefreshPolicy;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link DatasetRefreshRepository} (V28).
 *
 * <p>Tenant scoping is explicit in every predicate except {@link #claimDue}, which serves the whole
 * installation — see the port's note.
 */
@Component
@NullMarked
class JpaDatasetRefreshRepository implements DatasetRefreshRepository {

    private static final String MANUAL = "MANUAL";
    private static final String EVERY = "EVERY";

    private final DatasetRefreshJpaRepository refreshes;

    JpaDatasetRefreshRepository(DatasetRefreshJpaRepository refreshes) {
        this.refreshes = refreshes;
    }

    @Override
    public Optional<DatasetRefresh> findByDataset(TenantContext tenant, UUID datasetId) {
        return refreshes.findByDatasetIdAndTenantId(datasetId, tenantId(tenant))
                .map(JpaDatasetRefreshRepository::toDomain);
    }

    @Override
    public List<DatasetRefresh> findAllByRepository(TenantContext tenant, UUID repositoryId) {
        return refreshes.findAllByTenantIdAndRepositoryId(tenantId(tenant), repositoryId)
                .stream().map(JpaDatasetRefreshRepository::toDomain).toList();
    }

    @Override
    public DatasetRefresh save(TenantContext tenant, DatasetRefresh refresh) {
        UUID tenantId = tenantId(tenant);
        if (!tenantId.equals(refresh.tenantId())) {
            // The record carries its own tenant so the scheduler can hand it around outside any
            // context; that makes it possible to save one into the wrong context, so it is checked
            // rather than silently overwritten by whichever value the caller happened to have.
            throw new IllegalArgumentException(
                    "refresh belongs to tenant " + refresh.tenantId() + ", not " + tenantId);
        }
        return toDomain(refreshes.save(toEntity(refresh)));
    }

    @Override
    public void delete(TenantContext tenant, UUID datasetId) {
        refreshes.findByDatasetIdAndTenantId(datasetId, tenantId(tenant)).ifPresent(refreshes::delete);
    }

    /**
     * Lock the due rows and advance them in one transaction.
     *
     * <p>{@code REQUIRES_NEW} rather than joining a caller's transaction: the row lock must be
     * released as soon as the claim is durable, not held for however long the refreshes themselves
     * take. A scheduler that held its locks across the extract would block every other instance for
     * the duration and turn {@code SKIP LOCKED} into a no-op.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<DatasetRefresh> claimDue(Instant now, int limit) {
        List<DatasetRefreshEntity> due = refreshes.lockDue(now, limit);
        return due.stream().map(entity -> {
            DatasetRefresh claimed = toDomain(entity).claimed(now);
            entity.setNextRunAt(claimed.nextRunAt());
            return claimed;
        }).toList();
    }

    private static DatasetRefreshEntity toEntity(DatasetRefresh refresh) {
        boolean scheduled = refresh.policy() instanceof RefreshPolicy.Every;
        return new DatasetRefreshEntity(
                refresh.datasetId(),
                refresh.tenantId(),
                refresh.repositoryId(),
                refresh.projectId(),
                refresh.createdBy(),
                refresh.sourceTable(),
                scheduled ? EVERY : MANUAL,
                scheduled ? ((RefreshPolicy.Every) refresh.policy()).interval().toSeconds() : null,
                refresh.lastRefreshAt(),
                refresh.nextRunAt(),
                refresh.lastError());
    }

    private static DatasetRefresh toDomain(DatasetRefreshEntity e) {
        RefreshPolicy policy = EVERY.equals(e.getPolicy())
                ? new RefreshPolicy.Every(Duration.ofSeconds(requireInterval(e)))
                : new RefreshPolicy.Manual();
        return new DatasetRefresh(
                e.getDatasetId(), e.getTenantId(), e.getRepositoryId(), e.getProjectId(),
                e.getCreatedBy(), e.getSourceTable(),
                policy, e.getLastRefreshAt(), e.getNextRunAt(), e.getLastError());
    }

    private static long requireInterval(DatasetRefreshEntity e) {
        Long seconds = e.getIntervalSeconds();
        if (seconds == null) {
            // ck_dataset_refresh_policy forbids this, so reaching it means the constraint was
            // dropped or the row was written around it. Refusing beats defaulting to some interval
            // and quietly extracting on a schedule nobody chose.
            throw new IllegalStateException(
                    "dataset_refresh row " + e.getDatasetId() + " is EVERY with no interval");
        }
        return seconds;
    }

    private static UUID tenantId(TenantContext tenant) {
        return UUID.fromString(tenant.value());
    }
}

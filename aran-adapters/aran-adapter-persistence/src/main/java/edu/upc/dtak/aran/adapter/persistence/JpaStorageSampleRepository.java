package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.StorageSampleEntity;
import edu.upc.dtak.aran.core.domain.StorageSample;
import edu.upc.dtak.aran.core.domain.StorageSampleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link StorageSampleRepository} over JPA.
 *
 * <p>Both reads take a single row off the {@code (tenant_id, measured_at DESC)} index. There is
 * deliberately no aggregate here — see the port for why summing a level is always a bug.
 */
@Repository
@NullMarked
public class JpaStorageSampleRepository implements StorageSampleRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public StorageSample save(StorageSample sample) {
        entityManager.persist(new StorageSampleEntity(
                sample.id(), sample.tenantId(), sample.measuredAt(),
                sample.logicalBytes(), sample.physicalBytes(), sample.snapshotCount()));
        return sample;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StorageSample> latest(UUID tenantId) {
        return first(entityManager.createQuery("""
                        SELECT s FROM StorageSampleEntity s
                         WHERE s.tenantId = :tenantId
                         ORDER BY s.measuredAt DESC
                        """, StorageSampleEntity.class)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StorageSample> earliestSince(UUID tenantId, Instant since) {
        return first(entityManager.createQuery("""
                        SELECT s FROM StorageSampleEntity s
                         WHERE s.tenantId = :tenantId AND s.measuredAt >= :since
                         ORDER BY s.measuredAt ASC
                        """, StorageSampleEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("since", since)
                .setMaxResults(1)
                .getResultList());
    }

    private static Optional<StorageSample> first(List<StorageSampleEntity> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(toDomain(rows.getFirst()));
    }

    private static StorageSample toDomain(StorageSampleEntity e) {
        return new StorageSample(e.getId(), e.getTenantId(), e.getMeasuredAt(),
                e.getLogicalBytes(), e.getPhysicalBytes(), e.getSnapshotCount());
    }
}

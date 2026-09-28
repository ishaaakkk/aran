package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.core.domain.SnapshotReferenceRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SnapshotReferenceRepository} over JPA.
 *
 * <p>One native UNION rather than three JPQL queries, because the union is the point: the caller
 * wants "is this snapshot reachable at all", and three separate answers would have to be merged
 * by a caller that could forget a source.
 *
 * <p>The three sources, and why each counts:
 * <ul>
 *   <li>{@code dataset.snapshot_id} — the bytes a registered dataset is made of.</li>
 *   <li>{@code data_product.snapshot_id} — the materialized parquet a READY product serves.</li>
 *   <li>{@code dataset_schema_fingerprint.snapshot_id} — the snapshot drift detection measured.
 *       This one is arguably only a record of what was fingerprinted rather than a live claim on
 *       the bytes, and it is counted anyway: see the port for why the conservative direction is
 *       the only safe one here.</li>
 * </ul>
 *
 * <p>Tenant scoping is explicit rather than left to {@code TenantFilterAspect}: this is a native
 * query, so Hibernate's filter does not apply to it at all. {@code dataset} carries no tenant
 * column of its own, so it is scoped through the join to {@code data_repository}, which does. A
 * mistake here leaks another tenant's ids into a decision about what may be deleted.
 */
@Repository
@NullMarked
public class JpaSnapshotReferenceRepository implements SnapshotReferenceRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Set<String> referencedSnapshotIds(TenantContext tenant) {
        UUID tenantId = UUID.fromString(tenant.value());

        List<String> rows = entityManager.createNativeQuery("""
                SELECT d.snapshot_id
                  FROM dataset d
                  JOIN data_repository r ON r.id = d.repository_id
                 WHERE r.tenant_id = :tenantId AND d.snapshot_id IS NOT NULL
                UNION
                SELECT dp.snapshot_id
                  FROM data_product dp
                 WHERE dp.tenant_id = :tenantId AND dp.snapshot_id IS NOT NULL
                UNION
                SELECT f.snapshot_id
                  FROM dataset_schema_fingerprint f
                 WHERE f.tenant_id = :tenantId AND f.snapshot_id IS NOT NULL
                """)
                .setParameter("tenantId", tenantId)
                .getResultList();

        return new HashSet<>(rows);
    }
}

package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.core.domain.NamedObjectRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NamedObjectRepository} as one projection query per kind — ids, names and projects only,
 * never the entities (a dataset's column profiles load eagerly with it).
 *
 * <p>Tenant scoping is in every predicate, as in {@code JpaDatasetRepository}: the shared
 * {@code tenantFilter} is defence in depth behind it, not the boundary.
 */
@Repository
@NullMarked
public class JpaNamedObjectRepository implements NamedObjectRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, NamedObject> datasets(TenantContext tenant, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return index(entityManager.createQuery("""
                        SELECT d.id, d.name, r.projectId, d.repositoryId
                          FROM DatasetEntity d, DataRepositoryEntity r
                         WHERE r.id = d.repositoryId
                           AND d.tenantId = :tenantId AND r.tenantId = :tenantId
                           AND d.id IN :ids
                        """, Object[].class)
                .setParameter("tenantId", tenantId(tenant))
                .setParameter("ids", ids)
                .getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, NamedObject> products(TenantContext tenant, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return index(entityManager.createQuery("""
                        SELECT p.id, p.name, p.projectId
                          FROM DataProductEntity p
                         WHERE p.tenantId = :tenantId AND p.id IN :ids
                        """, Object[].class)
                .setParameter("tenantId", tenantId(tenant))
                .setParameter("ids", ids)
                .getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, NamedObject> projects(TenantContext tenant, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return index(entityManager.createQuery("""
                        SELECT p.id, p.name, p.id
                          FROM ProjectEntity p
                         WHERE p.tenantId = :tenantId AND p.id IN :ids
                        """, Object[].class)
                .setParameter("tenantId", tenantId(tenant))
                .setParameter("ids", ids)
                .getResultList());
    }

    private static Map<UUID, NamedObject> index(List<Object[]> rows) {
        Map<UUID, NamedObject> byId = new HashMap<>();
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            // A dataset's row carries its repository; the other kinds select three columns.
            @Nullable UUID repositoryId = row.length > 3 ? (UUID) row[3] : null;
            byId.put(id, new NamedObject(id, (String) row[1], (UUID) row[2], repositoryId));
        }
        return byId;
    }

    private static UUID tenantId(TenantContext tenant) {
        return UUID.fromString(tenant.value());
    }
}

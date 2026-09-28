package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.DeclaredForeignKeyEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.DeclaredForeignKeyJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.mapper.EntityMapper;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKeyRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link DeclaredForeignKeyRepository} (V33, Phase 4B).
 *
 * <p>Tenant scoping is explicit in every query, like {@code JpaDatasetRefreshRepository} — no
 * {@code @Filter}/{@code TenantFilterAspect} registration needed for this entity.
 */
@Component
@NullMarked
class JpaDeclaredForeignKeyRepository implements DeclaredForeignKeyRepository {

    private final DeclaredForeignKeyJpaRepository jpa;

    JpaDeclaredForeignKeyRepository(DeclaredForeignKeyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<DeclaredForeignKey> findAllByTenant(TenantContext tenant) {
        return jpa.findAllByTenantId(tenantId(tenant)).stream()
                .map(EntityMapper::toDomain)
                .toList();
    }

    /**
     * Delete-then-insert in one transaction: a fresh harvest's set is the new whole truth for this
     * repository (see {@code HarvestPersistenceService}'s own javadoc for why this is a full
     * replace rather than a merge), and doing both in one transaction means no reader ever
     * observes the repository with zero declared FKs mid-replace.
     */
    @Override
    @Transactional
    public void replaceForRepository(TenantContext tenant, UUID repositoryId,
                                      List<DeclaredForeignKey> foreignKeys) {
        UUID tenantId = tenantId(tenant);
        List<DeclaredForeignKeyEntity> existing =
                jpa.findAllByTenantIdAndRepositoryId(tenantId, repositoryId);
        if (!existing.isEmpty()) {
            jpa.deleteAll(existing);
        }
        List<DeclaredForeignKeyEntity> fresh = foreignKeys.stream()
                .map(EntityMapper::toEntity)
                .toList();
        if (!fresh.isEmpty()) {
            jpa.saveAll(fresh);
        }
    }

    private static UUID tenantId(TenantContext tenant) {
        return UUID.fromString(tenant.value());
    }
}

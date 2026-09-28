package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.RepositoryConnectionCheckEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.RepositoryConnectionCheckJpaRepository;
import edu.upc.dtak.aran.core.domain.ConnectionCheck;
import edu.upc.dtak.aran.core.domain.ConnectionCheckRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link ConnectionCheckRepository} (V46).
 *
 * <p>Tenant scoping is explicit in every query, like {@code JpaDatasetRefreshRepository}.
 */
@Component
@NullMarked
class JpaConnectionCheckRepository implements ConnectionCheckRepository {

    private final RepositoryConnectionCheckJpaRepository jpa;

    JpaConnectionCheckRepository(RepositoryConnectionCheckJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(TenantContext tenant, ConnectionCheck check) {
        UUID tenantId = tenantId(tenant);
        if (!tenantId.equals(check.tenantId())) {
            throw new IllegalArgumentException(
                    "connection check belongs to tenant " + check.tenantId() + ", not " + tenantId);
        }
        jpa.save(new RepositoryConnectionCheckEntity(check.repositoryId(), tenantId, check.success(),
                check.message(), check.details(), check.testedAt(), check.testedBy()));
    }

    @Override
    public void delete(TenantContext tenant, UUID repositoryId) {
        jpa.findByRepositoryIdAndTenantId(repositoryId, tenantId(tenant)).ifPresent(jpa::delete);
    }

    @Override
    public List<ConnectionCheck> findAllByRepositoryIds(TenantContext tenant, Collection<UUID> repositoryIds) {
        if (repositoryIds.isEmpty()) {
            return List.of();
        }
        return jpa.findCurrentByTenantIdAndRepositoryIdIn(tenantId(tenant), repositoryIds).stream()
                .map(e -> new ConnectionCheck(e.getRepositoryId(), e.getTenantId(), e.isSuccess(),
                        e.getMessage(), e.getDetails(), e.getTestedAt(), e.getTestedBy()))
                .toList();
    }

    private static UUID tenantId(TenantContext tenant) {
        return UUID.fromString(tenant.value());
    }
}

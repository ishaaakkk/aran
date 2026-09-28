package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.DataRepositoryEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.DataRepositoryJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.mapper.EntityMapper;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link DataRepositoryRepository} port.
 *
 * <p>Every query here names {@code tenant_id} in its own predicate; the shared-schema
 * {@code tenantFilter} enabled by {@code TenantFilterAspect} is defence in depth behind it.
 * Two independent reasons, both load-bearing: Hibernate never applies a {@code @Filter} to a
 * load by primary key ({@code CrossTenantByIdIT}), and the aspect cannot enable the filter at
 * all outside a transaction — audit AUTH_IDENTITY-3, pinned by
 * {@code CrossTenantListWithoutTransactionIT}.
 */
@Component
@NullMarked
class JpaDataRepositoryRepository implements DataRepositoryRepository {

    private final DataRepositoryJpaRepository repositoryJpa;

    JpaDataRepositoryRepository(DataRepositoryJpaRepository repositoryJpa) {
        this.repositoryJpa = repositoryJpa;
    }

    @Override
    public List<DataRepository> findAllByProject(TenantContext tenant, UUID projectId) {
        return repositoryJpa
                .findAllByProjectIdAndTenantId(projectId, UUID.fromString(tenant.value()))
                .stream()
                .map(EntityMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<DataRepository> findById(TenantContext tenant, UUID repositoryId) {
        return repositoryJpa.findByIdAndTenantId(repositoryId, UUID.fromString(tenant.value()))
                .map(EntityMapper::toDomain);
    }

    @Override
    public DataRepository save(TenantContext tenant, DataRepository repository) {
        UUID tenantId = UUID.fromString(tenant.value());
        DataRepositoryEntity entity = EntityMapper.toEntity(repository, tenantId);
        // Every save is a possible configuration change; see the port's save.
        entity.setConfigSavedAt(Instant.now());
        return EntityMapper.toDomain(repositoryJpa.save(entity));
    }

    @Override
    public void delete(TenantContext tenant, UUID repositoryId) {
        // Resolve inside the tenant first: deleteById(id) would happily delete
        // another tenant's repository, cascading to its datasets.
        repositoryJpa.findByIdAndTenantId(repositoryId, UUID.fromString(tenant.value()))
                .ifPresent(repositoryJpa::delete);
    }
}

package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.TenantGraphVersionJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.mapper.EntityMapper;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link GraphVersionPort} port.
 *
 * <p>Every read names {@code tenant_id} in its own predicate. The list queries used to
 * lean on the shared-schema {@code tenantFilter} instead, which was wrong on any path
 * where the filter is not applied — promotion being one: it read another tenant's graph
 * version, and only the write-side guard in {@code EntityMapper.toEntity} stopped it.
 * Hibernate also never applies a {@code @Filter} to a load by primary key, so by-id
 * reads could never have relied on it either. {@code CrossTenantByIdIT} pins by-id;
 * {@code CrossTenantGraphVersionIT} pins the rest.
 */
@Component
@NullMarked
class JpaGraphVersionAdapter implements GraphVersionPort {

    private final TenantGraphVersionJpaRepository repositoryJpa;

    JpaGraphVersionAdapter(TenantGraphVersionJpaRepository repositoryJpa) {
        this.repositoryJpa = repositoryJpa;
    }

    @Override
    public GraphVersion save(TenantContext tenant, GraphVersion version) {
        UUID tenantId = UUID.fromString(tenant.value());
        var entity = EntityMapper.toEntity(version, tenantId);
        var saved = repositoryJpa.save(entity);
        return EntityMapper.toDomain(saved);
    }

    @Override
    public Optional<GraphVersion> findById(TenantContext tenant, UUID id) {
        return repositoryJpa.findByIdAndTenantId(id, UUID.fromString(tenant.value()))
                .map(EntityMapper::toDomain);
    }

    @Override
    public Optional<GraphVersion> findCurrentActive(TenantContext tenant) {
        return repositoryJpa
                .findByTenantIdAndStatus(UUID.fromString(tenant.value()), "ACTIVE")
                .map(EntityMapper::toDomain);
    }

    @Override
    public Optional<GraphVersion> findLatest(TenantContext tenant) {
        return repositoryJpa
                .findFirstByTenantIdOrderByVersionNumberDesc(UUID.fromString(tenant.value()))
                .map(EntityMapper::toDomain);
    }

    @Override
    public List<GraphVersion> findAll(TenantContext tenant) {
        return repositoryJpa
                .findAllByTenantIdOrderByVersionNumberDesc(UUID.fromString(tenant.value()))
                .stream()
                .map(EntityMapper::toDomain)
                .toList();
    }
}

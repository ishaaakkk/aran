package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.CubeDefinitionEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.CubeDefinitionJpaRepository;
import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.domain.cube.CubeRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link CubeRepository} port (V40).
 *
 * <p><b>Tenant scoping is explicit here</b>, exactly as in {@link JpaDataProductRepository}:
 * every finder names {@code tenantId} in its predicate because the shared {@code tenantFilter}
 * Hibernate filter is not consulted on a load by primary key. The filter stays on
 * {@code CubeDefinitionEntity} as defence in depth for the list query.
 *
 * <p>{@code definitionJson} and {@code productIdsJson} pass through untouched. The domain owns
 * that JSON in both directions, so an adapter that parsed it would be a second author of the wire
 * shape — and the one that fails silently when the two drift.
 */
@Component
@NullMarked
class JpaCubeRepository implements CubeRepository {

    private final CubeDefinitionJpaRepository jpa;

    JpaCubeRepository(CubeDefinitionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<CubeRecord> list(TenantContext tenant, UUID projectId) {
        return jpa.findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(
                        UUID.fromString(tenant.value()), projectId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<CubeRecord> find(TenantContext tenant, UUID id) {
        return jpa.findByIdAndTenantId(id, UUID.fromString(tenant.value())).map(this::toDomain);
    }

    @Override
    public CubeRecord save(TenantContext tenant, CubeRecord cube) {
        UUID tenantId = UUID.fromString(tenant.value());
        if (!cube.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Cube tenant ID does not match the transactional tenant context");
        }

        // No find-then-mutate branch, unlike JpaDataProductRepository: v1 has no cube update path
        // (see CubeRepository), so every call here is the one insert a cube ever gets. A re-save
        // of an existing id would reach Hibernate as a merge, but every column on the entity is
        // updatable = false, so it rewrites nothing rather than silently rewriting the row.
        return toDomain(jpa.save(new CubeDefinitionEntity(
                cube.id(), tenantId, cube.projectId(), cube.name(), cube.description(),
                cube.factClassIri(), cube.definitionJson(), cube.productIdsJson(),
                cube.schemaVersionId(), cube.createdBy(), cube.createdAt())));
    }

    @Override
    public void delete(TenantContext tenant, UUID id) {
        jpa.findByIdAndTenantId(id, UUID.fromString(tenant.value())).ifPresent(jpa::delete);
    }

    private CubeRecord toDomain(CubeDefinitionEntity e) {
        return new CubeRecord(
                e.getId(),
                e.getTenantId(),
                e.getProjectId(),
                e.getName(),
                e.getDescription(),
                e.getFactClassIri(),
                e.getDefinitionJson(),
                e.getProductIdsJson(),
                e.getSchemaVersionId(),
                e.getCreatedBy(),
                e.getCreatedAt());
    }
}

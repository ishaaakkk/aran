package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.DataProductEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.DataProductJpaRepository;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link DataProductRepository} port.
 *
 * <p><b>Tenant scoping is explicit here</b>, exactly as in {@code JpaJobRepository}:
 * every finder names {@code tenantId} in its predicate because the shared
 * {@code tenantFilter} Hibernate filter is not consulted on a load by primary key.
 * The filter stays on {@code DataProductEntity} as defence in depth for list queries.
 */
@Component
@NullMarked
class JpaDataProductRepository implements DataProductRepository {

    private final DataProductJpaRepository jpa;

    JpaDataProductRepository(DataProductJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public DataProduct save(TenantContext tenant, DataProduct product) {
        UUID tenantId = UUID.fromString(tenant.value());
        if (!product.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Data product tenant ID does not match the transactional tenant context");
        }

        DataProductEntity entity = jpa.findByIdAndTenantId(product.id(), tenantId)
                .orElseGet(() -> new DataProductEntity(
                        product.id(), tenantId, product.projectId(), product.name(),
                        product.description(), product.oql(), product.schemaVersionId(),
                        product.accessMode(), product.status(), product.snapshotId(),
                        product.rowCount(), product.outputSchema(), product.materializedAt(),
                        product.lastError(), product.createdBy(), product.createdAt(),
                        product.builtAgainstVersionId()));

        entity.setName(product.name());
        entity.setDescription(product.description());
        entity.setOql(product.oql());
        entity.setStatus(product.status());
        entity.setSnapshotId(product.snapshotId());
        entity.setRowCount(product.rowCount());
        entity.setOutputSchema(product.outputSchema());
        entity.setMaterializedAt(product.materializedAt());
        entity.setLastError(product.lastError());
        entity.setBuiltAgainstVersionId(product.builtAgainstVersionId());

        return toDomain(jpa.save(entity));
    }

    @Override
    public Optional<DataProduct> findById(TenantContext tenant, UUID productId) {
        return jpa.findByIdAndTenantId(productId, UUID.fromString(tenant.value())).map(this::toDomain);
    }

    @Override
    public List<DataProduct> findByProject(TenantContext tenant, UUID projectId) {
        return jpa.findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(
                        UUID.fromString(tenant.value()), projectId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<DataProduct> findAll(TenantContext tenant) {
        return jpa.findAllByTenantIdOrderByCreatedAtAsc(UUID.fromString(tenant.value()))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<DataProduct> findByStatus(TenantContext tenant, DataProductStatus status) {
        return jpa.findAllByTenantIdAndStatus(UUID.fromString(tenant.value()), status)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void delete(TenantContext tenant, UUID productId) {
        jpa.findByIdAndTenantId(productId, UUID.fromString(tenant.value()))
                .ifPresent(jpa::delete);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int failInterruptedMaterializations(String error) {
        return jpa.failMaterializing(error);
    }

    private DataProduct toDomain(DataProductEntity e) {
        return new DataProduct(
                e.getId(),
                e.getTenantId(),
                e.getProjectId(),
                e.getName(),
                e.getDescription(),
                e.getOql(),
                e.getSchemaVersionId(),
                e.getAccessMode(),
                e.getStatus(),
                e.getSnapshotId(),
                e.getRowCount(),
                e.getOutputSchema(),
                e.getMaterializedAt(),
                e.getLastError(),
                e.getCreatedBy(),
                e.getCreatedAt(),
                e.getBuiltAgainstVersionId());
    }
}

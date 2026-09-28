package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Port for persisting {@link DataProduct}s. Implemented by the persistence adapter
 * (JPA over the V18 {@code data_product} table), mirroring {@link JobRepository}.
 */
@NullMarked
public interface DataProductRepository {

    DataProduct save(TenantContext tenant, DataProduct product);

    Optional<DataProduct> findById(TenantContext tenant, UUID productId);

    /** A project's products, newest first. */
    List<DataProduct> findByProject(TenantContext tenant, UUID projectId);

    /**
     * Every product of the tenant, across projects, oldest first — backs the JDBC
     * {@code products} schema, which is scoped to a tenant and has no project in its tree.
     */
    List<DataProduct> findAll(TenantContext tenant);

    /** Every product of the tenant in the given status — backs stale-marking. */
    List<DataProduct> findByStatus(TenantContext tenant, DataProductStatus status);

    void delete(TenantContext tenant, UUID productId);

    /**
     * Fail every product a previous run of the backend left {@code MATERIALIZING}, installation
     * wide — the product half of the startup sweep ({@code OrphanedJobSweep}, UI audit J6-F7).
     *
     * <p>Only a materialize job's body sets MATERIALIZING, and no job survives a restart, so at
     * startup every such row is an orphan: left alone it reads "materializing" forever and its
     * refresh button stays disabled. Tenant-blind because startup has no tenant.
     *
     * @return how many products were failed
     */
    int failInterruptedMaterializations(String error);
}

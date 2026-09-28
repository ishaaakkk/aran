package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.tenant.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The registry as the query engine sees it: a seeded list, and nothing else.
 *
 * <p>Every other method throws. The operator reads the registry to name and locate product
 * snapshots and does no lifecycle work on it, so a fake that answered them would be describing a
 * collaboration that does not exist — and would hide it the day one appeared.
 */
final class StubProductRepository implements DataProductRepository {

    private final List<DataProduct> products = new ArrayList<>();

    StubProductRepository add(DataProduct product) {
        products.add(product);
        return this;
    }

    @Override
    public List<DataProduct> findAll(TenantContext tenant) {
        return products.stream()
                .filter(p -> p.tenantId().toString().equals(tenant.value()))
                .toList();
    }

    @Override
    public DataProduct save(TenantContext tenant, DataProduct product) {
        throw new UnsupportedOperationException("the query engine does not write the registry");
    }

    @Override
    public Optional<DataProduct> findById(TenantContext tenant, UUID productId) {
        throw new UnsupportedOperationException("the query engine reads the registry by tenant");
    }

    @Override
    public List<DataProduct> findByProject(TenantContext tenant, UUID projectId) {
        throw new UnsupportedOperationException("the products schema is tenant-wide");
    }

    @Override
    public List<DataProduct> findByStatus(TenantContext tenant, DataProductStatus status) {
        throw new UnsupportedOperationException("the products schema lists every status");
    }

    @Override
    public void delete(TenantContext tenant, UUID productId) {
        throw new UnsupportedOperationException("the query engine does not write the registry");
    }

    @Override
    public int failInterruptedMaterializations(String error) {
        throw new UnsupportedOperationException("the query engine does not write the registry");
    }
}

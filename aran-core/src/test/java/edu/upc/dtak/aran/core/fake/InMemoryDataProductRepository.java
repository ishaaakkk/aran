package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link DataProductRepository} for service tests. Filters by tenant on
 * read, the way the real adapter's explicit tenant predicates do, so a test that
 * leaks across tenants fails here rather than only in an IT.
 */
public class InMemoryDataProductRepository implements DataProductRepository {

    private final Map<UUID, DataProduct> products = new LinkedHashMap<>();

    @Override
    public synchronized DataProduct save(TenantContext tenant, DataProduct product) {
        products.put(product.id(), product);
        return product;
    }

    @Override
    public synchronized Optional<DataProduct> findById(TenantContext tenant, UUID productId) {
        return Optional.ofNullable(products.get(productId))
                .filter(p -> p.tenantId().toString().equals(tenant.value()));
    }

    @Override
    public synchronized List<DataProduct> findByProject(TenantContext tenant, UUID projectId) {
        return products.values().stream()
                .filter(p -> p.tenantId().toString().equals(tenant.value()))
                .filter(p -> p.projectId().equals(projectId))
                .sorted(Comparator.comparing(DataProduct::createdAt).reversed())
                .toList();
    }

    @Override
    public synchronized List<DataProduct> findAll(TenantContext tenant) {
        return products.values().stream()
                .filter(p -> p.tenantId().toString().equals(tenant.value()))
                .sorted(Comparator.comparing(DataProduct::createdAt))
                .toList();
    }

    @Override
    public synchronized List<DataProduct> findByStatus(TenantContext tenant, DataProductStatus status) {
        return products.values().stream()
                .filter(p -> p.tenantId().toString().equals(tenant.value()))
                .filter(p -> p.status() == status)
                .toList();
    }

    @Override
    public synchronized void delete(TenantContext tenant, UUID productId) {
        findById(tenant, productId).ifPresent(p -> products.remove(p.id()));
    }

    @Override
    public synchronized int failInterruptedMaterializations(String error) {
        int failed = 0;
        for (DataProduct p : List.copyOf(products.values())) {
            if (p.status() == DataProductStatus.MATERIALIZING) {
                products.put(p.id(), p.failed(error));
                failed++;
            }
        }
        return failed;
    }
}

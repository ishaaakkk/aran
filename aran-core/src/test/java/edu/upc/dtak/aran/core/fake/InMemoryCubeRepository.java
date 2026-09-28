package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.domain.cube.CubeRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link CubeRepository} for services that only read cubes — {@code DataProductService}
 * asks it whether a product is a star member. Filters by tenant on read, like the adapter.
 */
public class InMemoryCubeRepository implements CubeRepository {

    private final Map<UUID, CubeRecord> rows = new LinkedHashMap<>();

    @Override
    public synchronized List<CubeRecord> list(TenantContext tenant, UUID projectId) {
        return rows.values().stream()
                .filter(r -> r.tenantId().toString().equals(tenant.value()))
                .filter(r -> r.projectId().equals(projectId))
                .toList();
    }

    @Override
    public synchronized Optional<CubeRecord> find(TenantContext tenant, UUID id) {
        return Optional.ofNullable(rows.get(id))
                .filter(r -> r.tenantId().toString().equals(tenant.value()));
    }

    @Override
    public synchronized CubeRecord save(TenantContext tenant, CubeRecord cube) {
        rows.put(cube.id(), cube);
        return cube;
    }

    @Override
    public synchronized void delete(TenantContext tenant, UUID id) {
        rows.remove(id);
    }
}

package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryDataRepositoryRepository implements DataRepositoryRepository {

    private final Map<UUID, DataRepository> db = new HashMap<>();

    @Override
    public List<DataRepository> findAllByProject(TenantContext tenant, UUID projectId) {
        return db.values().stream()
                .filter(r -> r.projectId().equals(projectId))
                .filter(r -> r.tenantId().toString().equals(tenant.value()))
                .toList();
    }

    @Override
    public Optional<DataRepository> findById(TenantContext tenant, UUID repositoryId) {
        return Optional.ofNullable(db.get(repositoryId))
                .filter(r -> r.tenantId().toString().equals(tenant.value()));
    }

    @Override
    public DataRepository save(TenantContext tenant, DataRepository repository) {
        db.put(repository.id(), repository);
        return repository;
    }

    @Override
    public void delete(TenantContext tenant, UUID repositoryId) {
        db.remove(repositoryId);
    }
}

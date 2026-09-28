package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.ConnectionCheck;
import edu.upc.dtak.aran.core.domain.ConnectionCheckRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InMemoryConnectionCheckRepository implements ConnectionCheckRepository {

    private final Map<UUID, ConnectionCheck> db = new HashMap<>();

    @Override
    public void save(TenantContext tenant, ConnectionCheck check) {
        db.put(check.repositoryId(), check);
    }

    @Override
    public void delete(TenantContext tenant, UUID repositoryId) {
        db.remove(repositoryId);
    }

    @Override
    public List<ConnectionCheck> findAllByRepositoryIds(TenantContext tenant, Collection<UUID> repositoryIds) {
        return repositoryIds.stream()
                .map(db::get)
                .filter(check -> check != null && check.tenantId().toString().equals(tenant.value()))
                .toList();
    }

    public int size() {
        return db.size();
    }
}

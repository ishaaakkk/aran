package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link ApiTokenRepository} for service tests. */
public final class InMemoryApiTokenRepository implements ApiTokenRepository {

    private final Map<UUID, ApiToken> byId = new LinkedHashMap<>();

    @Override
    public synchronized ApiToken save(TenantContext tenant, ApiToken token) {
        if (!token.tenantId().equals(UUID.fromString(tenant.value()))) {
            throw new IllegalArgumentException("tenant mismatch");
        }
        byId.put(token.id(), token);
        return token;
    }

    @Override
    public synchronized List<ApiToken> findByUser(TenantContext tenant, UUID userId) {
        UUID tenantId = UUID.fromString(tenant.value());
        List<ApiToken> found = new ArrayList<>(byId.values().stream()
                .filter(t -> t.tenantId().equals(tenantId) && t.userId().equals(userId))
                .toList());
        found.sort(Comparator.comparing(ApiToken::createdAt).reversed());
        return found;
    }

    @Override
    public synchronized Optional<ApiToken> findById(TenantContext tenant, UUID id) {
        UUID tenantId = UUID.fromString(tenant.value());
        return Optional.ofNullable(byId.get(id)).filter(t -> t.tenantId().equals(tenantId));
    }

    @Override
    public synchronized Optional<ApiToken> findByTokenHash(String tokenHash) {
        return byId.values().stream().filter(t -> t.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public synchronized void touch(UUID id, Instant usedAt) {
        ApiToken token = byId.get(id);
        if (token != null) {
            byId.put(id, token.used(usedAt));
        }
    }

    /** Test hook: replace a stored token wholesale, to age or revoke one without a clock. */
    public synchronized void put(ApiToken token) {
        byId.put(token.id(), token);
    }

    @Override
    public java.util.List<edu.upc.dtak.aran.core.domain.ApiToken> findByTenant(
            edu.upc.dtak.aran.core.tenant.TenantContext tenant) {
        java.util.UUID tenantId = java.util.UUID.fromString(tenant.value());
        return byId.values().stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .toList();
    }
}

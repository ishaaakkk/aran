package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.ApiTokenEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.ApiTokenJpaRepository;
import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA-backed {@link ApiTokenRepository}.
 *
 * <p>Tenant scoping is explicit in every finder, as elsewhere in this package, because the shared
 * {@code tenantFilter} is not consulted on a load by primary key. The one exception is
 * {@link #findByTokenHash}, which runs during authentication before a tenant is known — the token
 * establishes it.
 */
@Component
@NullMarked
class JpaApiTokenRepository implements ApiTokenRepository {

    private final ApiTokenJpaRepository jpa;

    JpaApiTokenRepository(ApiTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ApiToken save(TenantContext tenant, ApiToken token) {
        UUID tenantId = UUID.fromString(tenant.value());
        if (!token.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "API token tenant ID does not match the transactional tenant context");
        }
        ApiTokenEntity entity = jpa.findByIdAndTenantId(token.id(), tenantId)
                .orElseGet(() -> new ApiTokenEntity(
                        token.id(), tenantId, token.userId(), token.name(),
                        token.tokenHash(), token.tokenPrefix(), token.scope(),
                        token.expiresAt(), token.lastUsedAt(), token.revokedAt(),
                        token.createdAt()));

        // Only the mutable columns: the hash and prefix are `updatable = false`, so a token's
        // secret cannot be swapped under an existing row.
        entity.setName(token.name());
        entity.setScope(token.scope());
        entity.setExpiresAt(token.expiresAt());
        entity.setLastUsedAt(token.lastUsedAt());
        entity.setRevokedAt(token.revokedAt());

        return toDomain(jpa.save(entity));
    }

    @Override
    public List<ApiToken> findByUser(TenantContext tenant, UUID userId) {
        return jpa.findAllByTenantIdAndUserIdOrderByCreatedAtDesc(
                        UUID.fromString(tenant.value()), userId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<ApiToken> findByTenant(TenantContext tenant) {
        return jpa.findAllByTenantIdOrderByCreatedAtDesc(UUID.fromString(tenant.value()))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<ApiToken> findById(TenantContext tenant, UUID id) {
        return jpa.findByIdAndTenantId(id, UUID.fromString(tenant.value())).map(this::toDomain);
    }

    @Override
    public Optional<ApiToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(this::toDomain);
    }

    /**
     * In its own transaction: the request this records is about to run its own work, and a
     * last-used timestamp must not be rolled back — nor hold a lock — because that work failed.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(UUID id, Instant usedAt) {
        jpa.findById(id).ifPresent(entity -> {
            entity.setLastUsedAt(usedAt);
            jpa.save(entity);
        });
    }

    private ApiToken toDomain(ApiTokenEntity e) {
        return new ApiToken(e.getId(), e.getTenantId(), e.getUserId(), e.getName(),
                e.getTokenHash(), e.getTokenPrefix(), e.getScope(),
                e.getExpiresAt(), e.getLastUsedAt(), e.getRevokedAt(), e.getCreatedAt());
    }
}

package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link ApiToken}. */
@NullMarked
public interface ApiTokenRepository {

    ApiToken save(TenantContext tenant, ApiToken token);

    /** Every token belonging to {@code userId}, newest first, including revoked and expired ones. */
    List<ApiToken> findByUser(TenantContext tenant, UUID userId);

    /**
     * Every token in the tenant — the owner's credential inventory.
     *
     * <p>Separate from {@link #findByUser} because the two answer different questions and only
     * one of them is an admin's. Revocation already happens implicitly when a membership is
     * removed; this exists so an owner can *see* what exists, which is the half that was missing.
     */
    List<ApiToken> findByTenant(TenantContext tenant);

    Optional<ApiToken> findById(TenantContext tenant, UUID id);

    /**
     * Look a token up by the hex SHA-256 of its secret.
     *
     * <p>Deliberately <em>not</em> tenant-scoped: this runs during authentication, before a tenant
     * is known — the token is what establishes it. The tenant on the returned row is then
     * authoritative, exactly as the JWT's {@code tenant_id} claim is on the other path.
     */
    Optional<ApiToken> findByTokenHash(String tokenHash);

    /**
     * Record that a token authenticated a request. Separate from {@link #save} so the
     * authentication path can update one column without a read-modify-write of the whole row.
     */
    void touch(UUID id, java.time.Instant usedAt);
}

package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A membership — the association between a {@link User} and a {@link Tenant},
 * carrying the user's {@link TenantRole} <em>within that tenant</em>.
 *
 * <p>The role cannot live on {@code User} (it is not global — a tenant may have
 * many users, each with their own role) nor on {@code Tenant} (it is per-user),
 * so it lives on this join entity. A user holds at most one membership overall
 * (V23, {@code uq_membership_user}) — see {@code IdentityService.soleMembership}.
 *
 * <p>Authorization rule this type enforces: a user may only act within tenants
 * they have a {@code Membership} in. Resolving the membership for the
 * authenticated user + the request's tenant is the first authorization check
 * on every tenant-scoped operation.
 */
@NullMarked
public record Membership(
        UUID id,
        UUID userId,
        UUID tenantId,
        TenantRole role,
        Instant joinedAt
) {
    /** @return {@code true} if this membership grants tenant-administration rights. */
    public boolean canAdministerTenant() {
        return role == TenantRole.OWNER;
    }
}

package edu.upc.dtak.aran.core.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence <em>port</em> for {@link Tenant} and the {@link Membership}
 * rows that connect users to tenants.
 *
 * <p>These two are grouped in one port because they are almost always queried
 * together: "which tenants can this user act in, and with what role?" is a
 * single question answered by joining them.
 *
 * <p>Like {@link UserRepository}, this port is <strong>not</strong> itself
 * tenant-scoped — its whole job is to answer questions that come <em>before</em>
 * a tenant context exists (resolving the set of tenants a freshly
 * authenticated user is allowed into).
 */
@NullMarked
public interface TenantRepository {

    Optional<Tenant> findById(UUID tenantId);

    /**
     * Find a tenant by its URL-safe slug.
     */
    Optional<Tenant> findBySlug(String slug);

    /**
     * Persist (insert or update) a tenant.
     */
    Tenant save(Tenant tenant);

    /**
     * List every membership held by the given user, across all tenants.
     *
     * <p>This is what backs the post-login "tenant selector": the set of
     * tenants the user may choose to act within, each with the role they hold
     * there.
     */
    List<Membership> findMembershipsForUser(UUID userId);

    /**
     * Resolve the membership linking a specific user to a specific tenant, if
     * one exists.
     *
     * <p>This is the first authorization check on every tenant-scoped request:
     * absence of a membership means the user may not act in that tenant at all.
     */
    Optional<Membership> findMembership(UUID userId, UUID tenantId);

    /**
     * Every membership in a tenant — the tenant owner's "who is in my organisation" list.
     *
     * <p>Counterpart to {@link #findMembershipsForUser}: that one answers "which tenant is this
     * person in", which since V23 is at most one. This one answers the question the admin UI asks.
     */
    List<Membership> findMembershipsForTenant(UUID tenantId);

    /**
     * Persist (insert or update) a membership.
     */
    Membership saveMembership(Membership membership);

    /**
     * Remove a membership by its id. Idempotent: deleting a non-existent
     * membership is a no-op.
     */
    void deleteMembership(UUID membershipId);

    /**
     * Count the number of {@code OWNER} memberships in a tenant. Used by the
     * "last owner safeguard" when a user attempts to leave the tenant.
     */
    long countOwners(UUID tenantId);
}

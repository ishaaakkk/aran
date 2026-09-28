package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant administration the customer's own {@code OWNER}s perform — the
 * user-facing counterpart to {@link TenantAdminService}.
 *
 * <p>Operations here are bounded by:
 * <ul>
 *   <li>the license (read-only mode blocks all mutations);</li>
 *   <li>the {@code OWNER} role gate on add/remove (a {@code MEMBER} cannot
 *       administer the tenant);</li>
 *   <li>the {@code maxUsers} quota on additions.</li>
 * </ul>
 *
 * <p>One method — {@link #leaveCurrentTenant} — is the exception: any tenant
 * member may remove their <em>own</em> membership without owner rights. The
 * safeguard against the last-owner-leaving-an-orphaned-tenant case lives
 * here, not in the controller.
 */
@Service
@NullMarked
public class TenantOwnerService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final LicenseService licensing;
    private final AuditService audit;
    private final MemberDepartureService departures;

    public TenantOwnerService(TenantRepository tenants, UserRepository users,
                              LicenseService licensing, AuditService audit,
                              MemberDepartureService departures) {
        this.tenants = tenants;
        this.users = users;
        this.licensing = licensing;
        this.audit = audit;
        this.departures = departures;
    }

    /**
     * Add an existing Aran user to the caller's tenant. The user must have
     * signed in at least once (so their {@code User} row exists), and must
     * not already be a member of this tenant.
     */
    @Transactional
    public Membership addMember(AuthenticatedUser caller, String subject, TenantRole role) {
        licensing.requireMutationsAllowed(caller);
        if (!caller.canAdministerTenant()) {
            throw new AccessDeniedException("only tenant OWNERs may add members");
        }
        licensing.requireWithinUserLimit(caller);

        User user = users.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "user has never signed in to Aran: " + subject));

        // Idempotent in the "already a member" case — returning the existing
        // membership prevents partial races where a second click would 500.
        return tenants.findMembership(user.id(), caller.tenantId())
                .orElseGet(() -> {
                    Membership saved = tenants.saveMembership(new Membership(
                            UUID.randomUUID(),
                            user.id(),
                            caller.tenantId(),
                            role,
                            Instant.now()
                    ));
                    audit.tenantMemberAdded(caller.userId(),
                            caller.tenantId(), user.id(), role);
                    return saved;
                });
    }

    /**
     * Remove the caller's own membership in the current tenant.
     *
     * <p>Refuses if the caller is the last remaining {@code OWNER}: a tenant
     * without an owner is unreachable for further administration and would
     * require platform-admin intervention to recover. The caller must
     * promote someone else first.
     */
    @Transactional
    public void leaveCurrentTenant(AuthenticatedUser caller) {
        licensing.requireMutationsAllowed(caller);

        if (caller.canAdministerTenant()) {
            long otherOwners = tenants.countOwners(caller.tenantId()) - 1;
            if (otherOwners <= 0) {
                throw new AccessDeniedException(
                        "you are the last OWNER of this tenant; promote another " +
                        "OWNER before leaving, or contact platform support");
            }
        }

        // Removal is idempotent — if there's nothing to remove the operation
        // succeeds silently. The membership existed when the JWT was issued;
        // it might not by the time this lands.
        Membership existing = tenants.findMembership(caller.userId(), caller.tenantId())
                .orElse(null);
        if (existing != null) {
            // Leaving is removing yourself, and ends what removal ends (UI-OD9): the tokens are
            // revoked and the project access dropped, so being re-added later brings neither back.
            // A project only the leaver belongs to goes to the organization's owners — there is
            // no acting owner here, and a member cannot share a project to hand it on themselves.
            List<UUID> owners = tenants.findMembershipsForTenant(caller.tenantId())
                    .stream()
                    .filter(m -> m.role() == TenantRole.OWNER)
                    .map(Membership::userId)
                    .toList();
            departures.depart(caller, existing, owners);
        }
    }
}

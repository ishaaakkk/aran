package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A tenant OWNER administering their own tenant's members.
 *
 * <p>Distinct from {@link TenantAdminService}, which is the platform-admin surface: that one
 * creates tenants and is gated on a realm role, so a tenant owner cannot use it — adding a
 * colleague to your own organisation used to require the Aran operator. This is the surface that
 * makes a tenant self-administering, and every method is scoped to the caller's own tenant with no
 * parameter that could name another.
 *
 * <p>Users are created <em>here</em>, not in Keycloak's console. Aran owns the account's lifecycle
 * from the admin UI; Keycloak stores the credential and is never seen.
 */
@Service
@NullMarked
public class TenantMemberService {

    private static final Logger log = LoggerFactory.getLogger(TenantMemberService.class);

    /**
     * Unambiguous alphabet — no I/l/1, no O/0. This password is read off a screen and typed by
     * hand exactly once before Keycloak forces a change, so the only property that matters
     * besides entropy is that it can be transcribed without a second attempt.
     */
    private static final String PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int PASSWORD_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenants;
    private final UserRepository users;
    private final IdentityProviderPort identityProvider;
    private final LicenseService licensing;
    private final AuditService audit;
    private final MemberDepartureService departures;

    public TenantMemberService(TenantRepository tenants, UserRepository users,
                               IdentityProviderPort identityProvider, LicenseService licensing,
                               AuditService audit, MemberDepartureService departures) {
        this.tenants = tenants;
        this.users = users;
        this.identityProvider = identityProvider;
        this.licensing = licensing;
        this.audit = audit;
        this.departures = departures;
    }

    /**
     * A member of a tenant, as the admin UI lists them.
     *
     * @param lastSeenAt the member's last authenticated request, throttled to the minute; null
     *                   when they have never been seen — not the same as "long ago" — and always
     *                   null for a caller who is not an owner: per-person activity is an
     *                   administrator's view, and every member can read this list
     */
    public record MemberView(UUID userId, String email, String displayName, TenantRole role,
                             Instant joinedAt, @Nullable Instant lastSeenAt) {}

    /**
     * A member just added. The password is returned <em>once</em> and never stored: Aran keeps
     * no copy, and Keycloak keeps only a hash.
     *
     * @param temporaryPassword always present — a reinstated account gets a new one too, so no
     *                          credential from before the removal works again
     * @param reinstated        true when the email belonged to someone this organization removed,
     *                          whose account was re-enabled rather than created (UI-OD9)
     */
    public record CreatedMember(MemberView member, String temporaryPassword,
                                boolean reinstated) {}

    @Transactional(readOnly = true)
    public List<MemberView> listMembers(AuthenticatedUser actor) {
        List<Membership> memberships = tenants.findMembershipsForTenant(actor.tenantId());
        Map<UUID, Instant> lastSeen = actor.canAdministerTenant()
                ? users.lastSeenFor(memberships.stream().map(Membership::userId).toList())
                : Map.of();
        return memberships.stream()
                .map(m -> toView(m, lastSeen.get(m.userId())))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Create an account and add it to the caller's tenant.
     *
     * <p><b>Transactional across two systems, in the only order that can be.</b> The Keycloak
     * account is provisioned first and Aran's rows are committed second, so the failure that needs
     * undoing is the local one. If the local write fails after provisioning, the Keycloak account
     * is deleted on the way out — best effort, and logged loudly if even that fails, because the
     * alternative is an orphaned account nobody knows about.
     *
     * <p>The reverse order would be worse: an Aran user with no credential looks like a working
     * member in the admin UI and cannot log in.
     *
     * <p>Both license gates run before anything is provisioned (audit AUTH_IDENTITY-1). This is
     * the surface a tenant owner actually uses to add people — the two sibling paths
     * ({@code TenantOwnerService.addMember}, {@code TenantAdminService.addMember}) gate on the
     * same two, and a member added here counts against {@code maxUsers} identically, so leaving
     * this one open made the quota advisory.
     */
    @Transactional
    public CreatedMember createMember(AuthenticatedUser actor, String email, String displayName,
                                      TenantRole role) {
        requireOwner(actor);
        licensing.requireMutationsAllowed(actor);
        licensing.requireWithinUserLimit(actor);
        String normalisedEmail = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalisedEmail.isBlank() || !normalisedEmail.contains("@")) {
            throw new IllegalArgumentException("a valid email address is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("a display name is required");
        }
        requireNotAlreadyMember(actor, normalisedEmail);

        Optional<String> existing = identityProvider.findSubjectByEmail(normalisedEmail);
        if (existing.isPresent()) {
            return reinstate(actor, existing.get(), normalisedEmail, role);
        }

        String subject = identityProvider.createUser(normalisedEmail, normalisedEmail, displayName);
        try {
            String password = generatePassword();
            identityProvider.setTemporaryPassword(subject, password);

            User user = users.save(new User(UUID.randomUUID(), subject, normalisedEmail,
                    displayName, Instant.now()));
            Membership membership = tenants.saveMembership(new Membership(
                    UUID.randomUUID(), user.id(), actor.tenantId(), role, Instant.now()));

            audit.tenantMemberAdded(actor.userId(), actor.tenantId(), user.id(), role);
            return new CreatedMember(
                    new MemberView(user.id(), user.email(), user.displayName(), membership.role(),
                            membership.joinedAt(), null),
                    password, false);
        } catch (RuntimeException e) {
            compensate(subject, e);
            throw e;
        }
    }

    /**
     * Bring back an account this organization removed (UI-OD9, audit J7-F2).
     *
     * <p>Only one case is a reinstatement: Aran knows the account (a {@link User} row exists for
     * its subject), it belongs to no organization, and <em>this</em> organization's own record
     * shows it ended that membership ({@link AuditService#removedFromTenant}). Everything else is
     * a {@link MemberConflictException}: a member of this tenant already, a member of another
     * (V23), an account Aran has never seen, or a membership-less account this organization never
     * let go of — removed from, or left, another organization, or signed in and never added.
     * Those accounts are not this tenant's to re-enable: another organization's owner disabled
     * one, and may know its password.
     *
     * <p><b>No credential survives the removal.</b> The account is re-enabled with a new
     * temporary password, returned once as on creation, so a password known before — to the
     * person, to whoever held it while it was out, to anyone it leaked to if a leak was why they
     * were removed — does not work again. Okta's reactivate-a-deactivated-user works the same way;
     * the cost is one handover for a member removed by mistake. The tokens and project access
     * their removal revoked stay revoked too.
     *
     * <p><b>The order is the reverse of creation's, on purpose.</b> Creation provisions the
     * account first because an Aran row with no credential looks like a working member. Here the
     * account exists; what must not happen is an enabled account the tenant does not hold, or one
     * enabled with its old password. So the membership is written first, the password replaced
     * next and the account enabled last: if the provider refuses either step, the exception rolls
     * the membership back and the account stays disabled.
     */
    private CreatedMember reinstate(AuthenticatedUser actor, String subject, String email,
                                    TenantRole role) {
        User user = users.findBySubject(subject).orElseThrow(() -> new MemberConflictException(
                "An account for " + email + " already exists but has never signed in to Aran, so "
                        + "it cannot be added from here. Ask them to sign in once, then add them "
                        + "again."));

        List<Membership> held = tenants.findMembershipsForUser(user.id());
        if (held.stream().anyMatch(m -> m.tenantId().equals(actor.tenantId()))) {
            throw new MemberConflictException(email + " is already a member of this organization.");
        }
        if (!held.isEmpty()) {
            throw new MemberConflictException(email + " belongs to another organization. An "
                    + "account can belong to one organization only.");
        }
        if (!audit.removedFromTenant(actor.tenantId(), user.id())) {
            throw new MemberConflictException("An Aran account for " + email + " already exists "
                    + "and was not removed from this organization, so it cannot be added from "
                    + "here. Ask your Aran administrator to move it.");
        }

        Membership membership = tenants.saveMembership(new Membership(
                UUID.randomUUID(), user.id(), actor.tenantId(), role, Instant.now()));
        String password = generatePassword();
        identityProvider.setTemporaryPassword(subject, password);
        identityProvider.enableUser(subject);
        audit.tenantMemberReinstated(actor, user.id(), role);

        return new CreatedMember(
                new MemberView(user.id(), user.email(), user.displayName(), membership.role(),
                        membership.joinedAt(), null),
                password, true);
    }

    @Transactional
    public MemberView changeRole(AuthenticatedUser actor, UUID userId, TenantRole role) {
        requireOwner(actor);
        Membership existing = membershipInOwnTenant(actor, userId);

        if (existing.role() == TenantRole.OWNER && role != TenantRole.OWNER
                && tenants.countOwners(actor.tenantId()) <= 1) {
            // A tenant with no owner cannot be administered by anyone, including by whoever
            // just did this to it.
            throw new IllegalArgumentException(
                    "this is the tenant's only owner; promote someone else first");
        }

        Membership updated = tenants.saveMembership(new Membership(
                existing.id(), existing.userId(), existing.tenantId(), role, existing.joinedAt()));
        if (existing.role() != role) {
            audit.tenantMemberRoleChanged(actor, userId, existing.role(), role);
        }
        return toView(updated, null).orElseThrow();
    }

    /**
     * Remove a member from the caller's tenant.
     *
     * <p>Dropping the membership is what ends access, and it is immediate: it is what
     * {@code CurrentUserResolver} reads, and {@code ApiTokenAuthenticationFilter} already rejects
     * a valid token whose owner holds no membership. Disabling the Keycloak account afterwards is
     * hygiene — it stops the credential working too — and a failure there must not undo a removal
     * the owner asked for.
     *
     * <p><b>Everything the membership granted goes with it (UI-OD9)</b>, through
     * {@link MemberDepartureService}: the member's API tokens are revoked, not left to fail on a
     * missing membership, and their project visibility rows are dropped — otherwise re-adding the
     * same email, which reinstates the account, would silently bring back every credential and
     * project they held. A project only they belonged to is first handed to the acting owner, so
     * none becomes unreachable; the result names those projects.
     */
    @Transactional
    public MemberDepartureService.Departure removeMember(AuthenticatedUser actor, UUID userId) {
        requireOwner(actor);
        if (actor.userId().equals(userId)) {
            throw new IllegalArgumentException(
                    "you cannot remove yourself; ask another owner to do it");
        }
        Membership membership = membershipInOwnTenant(actor, userId);
        if (membership.role() == TenantRole.OWNER && tenants.countOwners(actor.tenantId()) <= 1) {
            throw new IllegalArgumentException("this is the tenant's only owner");
        }

        MemberDepartureService.Departure departure =
                departures.depart(actor, membership, List.of(actor.userId()));

        users.findById(userId).ifPresent(user -> {
            try {
                identityProvider.disableUser(user.subject());
            } catch (RuntimeException e) {
                log.warn("membership removed for user {} but the identity provider account could "
                        + "not be disabled; access has ended regardless", userId, e);
            }
        });
        return departure;
    }

    /** Reset a member's password to a new temporary one, returned once. */
    @Transactional
    public String resetPassword(AuthenticatedUser actor, UUID userId) {
        requireOwner(actor);
        membershipInOwnTenant(actor, userId);
        User user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("no such user: " + userId));

        String password = generatePassword();
        identityProvider.setTemporaryPassword(user.subject(), password);
        audit.tenantMemberPasswordReset(actor, userId);
        return password;
    }

    private void compensate(String subject, RuntimeException cause) {
        try {
            identityProvider.deleteUser(subject);
        } catch (RuntimeException cleanupFailure) {
            // Both failed. Say so precisely: an account exists that Aran has no row for, and
            // somebody has to remove it by hand.
            log.error("could not create the Aran user for subject {} AND could not remove the "
                    + "identity-provider account created for it — that account is now orphaned "
                    + "and must be deleted manually", subject, cleanupFailure);
            return;
        }
        log.warn("rolled back identity-provider account {} after a local failure", subject, cause);
    }

    /**
     * Refuses an email that already belongs to a member of the caller's own tenant, before the
     * identity provider is asked (UI audit J7-F7). The likeliest way to get here is a create that
     * timed out in the browser after it had succeeded: the provider's generic "account exists"
     * would then describe an active member as someone removed or in another organization, and
     * hide the one action that recovers the lost one-time password — Reset password.
     *
     * <p>Only the caller's own tenant is read, so the answer reveals nothing about any other
     * organization's members; an email held elsewhere still reaches the provider's refusal.
     */
    private void requireNotAlreadyMember(AuthenticatedUser actor, String normalisedEmail) {
        boolean member = tenants.findMembershipsForTenant(actor.tenantId()).stream()
                .map(m -> users.findById(m.userId()))
                .flatMap(Optional::stream)
                .anyMatch(u -> normalisedEmail.equalsIgnoreCase(u.email()));
        if (member) {
            throw new ConflictException(ConflictException.ALREADY_A_MEMBER,
                    normalisedEmail + " is already a member of this organization. If an earlier "
                            + "attempt to add them timed out, it did add them — use Reset password "
                            + "to give them a new one-time password.");
        }
    }

    private Membership membershipInOwnTenant(AuthenticatedUser actor, UUID userId) {
        return tenants.findMembership(userId, actor.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "that user is not a member of this tenant"));
    }

    private static void requireOwner(AuthenticatedUser actor) {
        if (!actor.canAdministerTenant()) {
            throw new AccessDeniedException("only a tenant owner can manage members");
        }
    }

    private Optional<MemberView> toView(Membership membership, @Nullable Instant lastSeenAt) {
        return users.findById(membership.userId())
                .map(u -> new MemberView(u.id(), u.email(), u.displayName(), membership.role(),
                        membership.joinedAt(), lastSeenAt));
    }

    private static String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}

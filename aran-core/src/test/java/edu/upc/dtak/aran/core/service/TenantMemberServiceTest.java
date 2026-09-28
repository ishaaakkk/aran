package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.LicenseStatus;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.fake.InMemoryApiTokenRepository;
import edu.upc.dtak.aran.core.fake.InMemoryProjectRepository;
import edu.upc.dtak.aran.core.fake.InMemoryTenantRepository;
import edu.upc.dtak.aran.core.fake.RecordingAuditPort;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.licensing.LicenseExpiredException;
import edu.upc.dtak.aran.core.licensing.LicenseLimitExceededException;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.licensing.UsageCounter;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A tenant owner creating and managing their own members.
 *
 * <p>The behaviour worth pinning is not the happy path — it is what happens when the identity
 * provider and Aran's database disagree, and what stops an owner locking their own tenant out.
 */
class TenantMemberServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private InMemoryTenantRepository tenants;
    private StubUsers users;
    private StubIdentityProvider idp;
    private InMemoryApiTokenRepository tokenStore;
    private InMemoryProjectRepository projects;
    private RecordingAuditPort auditTrail;
    private AuditService audit;
    private ApiTokenService tokens;
    private TenantMemberService service;
    private AuthenticatedUser owner;

    private TenantMemberService newService(LicenseService licensing) {
        return new TenantMemberService(tenants, users, idp, licensing, audit,
                new MemberDepartureService(tenants, tokens, projects, audit));
    }

    @BeforeEach
    void setUp() {
        tenants = new InMemoryTenantRepository();
        users = new StubUsers();
        idp = new StubIdentityProvider();
        tokenStore = new InMemoryApiTokenRepository();
        projects = new InMemoryProjectRepository();
        auditTrail = new RecordingAuditPort();
        audit = new AuditService(auditTrail);
        tokens = new ApiTokenService(tokenStore, audit);
        service = newService(UncappedLicenses.create());

        User ownerUser = users.save(new User(UUID.randomUUID(), "owner-subject", "owner@upc.edu",
                "Owner", Instant.EPOCH));
        tenants.saveMembership(new Membership(UUID.randomUUID(), ownerUser.id(), TENANT,
                TenantRole.OWNER, Instant.EPOCH));
        owner = new AuthenticatedUser(ownerUser.id(), "owner-subject", TENANT, TenantRole.OWNER);
    }

    private AuthenticatedUser member() {
        User u = users.save(new User(UUID.randomUUID(), "member-subject", "m@upc.edu", "M",
                Instant.EPOCH));
        tenants.saveMembership(new Membership(UUID.randomUUID(), u.id(), TENANT,
                TenantRole.MEMBER, Instant.EPOCH));
        return new AuthenticatedUser(u.id(), "member-subject", TENANT, TenantRole.MEMBER);
    }

    @Nested
    @DisplayName("creating a member")
    class Creating {

        @Test
        @DisplayName("provisions the account and returns a password shown only once")
        void createsAndReturnsPassword() {
            var created = service.createMember(owner, "New@UPC.edu", "New Person",
                    TenantRole.MEMBER);

            assertThat(created.member().email()).isEqualTo("new@upc.edu");
            assertThat(created.temporaryPassword()).hasSize(16);
            assertThat(idp.created).containsKey("new@upc.edu");
            assertThat(idp.temporaryPasswords).containsValue(created.temporaryPassword());
        }

        @Test
        @DisplayName("a local failure removes the account that was just provisioned")
        void rollsBackTheProviderAccount() {
            // The Keycloak account is created first so the failure that needs undoing is the
            // local one. Without the compensation an orphaned account survives, and the email is
            // then permanently unusable.
            users.failNextSave = true;

            assertThatThrownBy(() -> service.createMember(owner, "x@upc.edu", "X",
                    TenantRole.MEMBER))
                    .isInstanceOf(RuntimeException.class);

            assertThat(idp.deleted).hasSize(1);
        }

        @Test
        @DisplayName("a provider that refuses leaves no Aran row behind")
        void providerFailureLeavesNoRow() {
            idp.failCreate = true;

            assertThatThrownBy(() -> service.createMember(owner, "x@upc.edu", "X",
                    TenantRole.MEMBER))
                    .isInstanceOf(IdentityProviderPort.IdentityProviderException.class);

            assertThat(users.saved).hasSize(1);   // only the owner from setUp
        }

        @Test
        @DisplayName("an email already in this organization is refused as a member, before the provider is asked (J7-F7)")
        void refusesAnExistingMemberOfThisTenant() {
            // The create that timed out in the browser but succeeded on the server: the retry must
            // say the person IS a member and point at Reset password, not answer as if the email
            // belonged to someone removed or in another organization.
            service.createMember(owner, "twice@upc.edu", "Twice", TenantRole.MEMBER);
            int accountsBefore = idp.created.size();

            assertThatThrownBy(() -> service.createMember(owner, " Twice@UPC.edu ", "Twice",
                    TenantRole.MEMBER))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already a member of this organization")
                    .hasMessageContaining("Reset password")
                    .extracting(e -> ((ConflictException) e).errorCode())
                    .isEqualTo(ConflictException.ALREADY_A_MEMBER);
            assertThat(idp.created).hasSize(accountsBefore);
        }

        @Test
        @DisplayName("an email held by another organization's member is not answered from their membership")
        void doesNotReadAnotherTenantsMembers() {
            // The pre-check reads the caller's tenant only; the provider's own refusal is what
            // answers for an email held elsewhere, and it names no organization.
            User stranger = users.save(new User(UUID.randomUUID(), "s", "elsewhere@upc.edu", "S",
                    Instant.EPOCH));
            tenants.saveMembership(new Membership(UUID.randomUUID(), stranger.id(), OTHER_TENANT,
                    TenantRole.MEMBER, Instant.EPOCH));

            service.createMember(owner, "elsewhere@upc.edu", "Elsewhere", TenantRole.MEMBER);

            assertThat(idp.created).containsKey("elsewhere@upc.edu");
        }

        @Test
        @DisplayName("a member cannot create members")
        void memberCannotCreate() {
            assertThatThrownBy(() -> service.createMember(member(), "x@upc.edu", "X",
                    TenantRole.MEMBER))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("owner");
        }

        @Test
        @DisplayName("an address that is not one is refused before the provider is called")
        void rejectsBadEmail() {
            assertThatThrownBy(() -> service.createMember(owner, "not-an-email", "X",
                    TenantRole.MEMBER))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(idp.created).isEmpty();
        }
    }

    /**
     * Audit AUTH_IDENTITY-1. This is the path the admin UI uses to add people, and it was the
     * only member-adding path with no license gate at all — {@code TenantOwnerService.addMember}
     * and {@code TenantAdminService.addMember} both refuse past the quota, so a tenant could
     * exceed {@code maxUsers} indefinitely by using the surface a tenant owner actually has.
     */
    @Nested
    @DisplayName("license gates")
    class LicenseGates {

        /** A license whose ceiling is {@code maxUsers} with {@code current} seats already taken. */
        private TenantMemberService serviceLicensed(LicenseStatus status, int maxUsers,
                                                    long current) {
            LicenseProvider provider = tenantId -> Optional.of(new LicenseEntitlements(
                    tenantId, LicensePlan.FREE, status, maxUsers, Integer.MAX_VALUE,
                    null, null, Set.of(), null));
            UsageCounter counter = new UsageCounter() {
                @Override public long countUsers(UUID tenantId) {
                    return current;
                }

                @Override public long countProjects(UUID tenantId) {
                    return 0;
                }

                @Override public long estimatedStorageBytes(UUID tenantId) {
                    return 0;
                }

                @Override public long egressBytesSince(UUID tenantId, Instant since) {
                    return 0;
                }
            };
            return newService(new LicenseService(provider, counter));
        }

        @Test
        @DisplayName("a tenant already at maxUsers cannot create another member")
        void refusesAtTheUserLimit() {
            TenantMemberService capped = serviceLicensed(LicenseStatus.ACTIVE, 3, 3);

            assertThatThrownBy(() -> capped.createMember(owner, "over@upc.edu", "Over",
                    TenantRole.MEMBER))
                    .isInstanceOf(LicenseLimitExceededException.class);

            // Refused before the identity provider is touched: an account provisioned for a
            // member Aran then refuses to record is exactly the orphan compensate() exists for.
            assertThat(idp.created).isEmpty();
        }

        @Test
        @DisplayName("a read-only (suspended/expired) license cannot create members")
        void refusesWhenMutationsAreNotAllowed() {
            TenantMemberService readOnly = serviceLicensed(LicenseStatus.SUSPENDED,
                    Integer.MAX_VALUE, 0);

            assertThatThrownBy(() -> readOnly.createMember(owner, "nope@upc.edu", "Nope",
                    TenantRole.MEMBER))
                    .isInstanceOf(LicenseExpiredException.class);

            assertThat(idp.created).isEmpty();
        }

        @Test
        @DisplayName("within both limits the path is unchanged")
        void allowsWithinTheLimits() {
            TenantMemberService withRoom = serviceLicensed(LicenseStatus.ACTIVE, 10, 3);

            var created = withRoom.createMember(owner, "room@upc.edu", "Room", TenantRole.MEMBER);

            assertThat(created.member().email()).isEqualTo("room@upc.edu");
            assertThat(created.temporaryPassword()).hasSize(16);
        }
    }

    @Nested
    @DisplayName("removing a member")
    class Removing {

        @Test
        @DisplayName("drops the membership and disables the account")
        void removes() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            service.removeMember(owner, created.member().userId());

            assertThat(tenants.findMembership(created.member().userId(), TENANT)).isEmpty();
            assertThat(idp.disabled).hasSize(1);
        }

        @Test
        @DisplayName("a provider that will not disable does not undo the removal")
        void disableFailureDoesNotUndoRemoval() {
            // Access ends with the membership row; disabling the credential is hygiene. Failing
            // the whole operation would leave a member the owner asked to remove.
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);
            idp.failDisable = true;

            service.removeMember(owner, created.member().userId());

            assertThat(tenants.findMembership(created.member().userId(), TENANT)).isEmpty();
        }

        @Test
        @DisplayName("an owner cannot remove themselves")
        void cannotRemoveSelf() {
            assertThatThrownBy(() -> service.removeMember(owner, owner.userId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("yourself");
        }

        @Test
        @DisplayName("someone in another tenant is not found, not removed")
        void cannotReachAnotherTenant() {
            User stranger = users.save(new User(UUID.randomUUID(), "s", "s@x.com", "S",
                    Instant.EPOCH));
            tenants.saveMembership(new Membership(UUID.randomUUID(), stranger.id(), OTHER_TENANT,
                    TenantRole.MEMBER, Instant.EPOCH));

            assertThatThrownBy(() -> service.removeMember(owner, stranger.id()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("the last owner")
    class LastOwner {

        @Test
        @DisplayName("cannot be demoted, or nobody could administer the tenant")
        void cannotDemoteTheOnlyOwner() {
            var second = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            assertThatThrownBy(() -> service.changeRole(owner, owner.userId(), TenantRole.MEMBER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only owner");

            // With a second owner it is allowed.
            service.changeRole(owner, second.member().userId(), TenantRole.OWNER);
            assertThat(service.changeRole(owner, owner.userId(), TenantRole.MEMBER).role())
                    .isEqualTo(TenantRole.MEMBER);
        }
    }

    /**
     * UI-OD9 (audit J7-F2, J7-F8). Removal used to leave three kinds of authority behind — live
     * tokens that failed only because the filter found no membership, project visibility rows,
     * and a disabled identity-provider account that made re-adding the same email a 500 forever.
     */
    @Nested
    @DisplayName("removal and reinstatement")
    class RemovalAndReinstatement {

        private TenantContext tenant() {
            return new TenantContext(TENANT.toString());
        }

        @Test
        @DisplayName("removing a member revokes every live token they hold, and says how many")
        void removalRevokesTokens() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);
            UUID userId = created.member().userId();
            tokens.create(tenant(), userId, "notebook", ApiTokenScope.READ, null);
            tokens.create(tenant(), userId, "ci", ApiTokenScope.FULL, null);

            service.removeMember(owner, userId);

            assertThat(tokenStore.findByUser(tenant(), userId))
                    .hasSize(2)
                    .allMatch(t -> t.revokedAt() != null);
            var removal = auditTrail.withAction("tenant.member.removed");
            assertThat(removal).hasSize(1);
            assertThat(removal.getFirst().metadata())
                    .containsEntry("removedUserId", userId.toString())
                    .containsEntry("tokensRevoked", "2");
        }

        @Test
        @DisplayName("removing a member drops their project access, so re-adding them restores none")
        void removalDropsProjectAccess() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);
            UUID userId = created.member().userId();
            UUID projectId = UUID.randomUUID();
            projects.save(tenant(), new Project(projectId, TENANT, "papers", "", owner.userId(),
                    Instant.EPOCH, "#000", "PRIVATE", "ACTIVE", 0));
            projects.addMember(tenant(), new ProjectMember(UUID.randomUUID(), projectId, userId,
                    Instant.EPOCH));

            service.removeMember(owner, userId);
            service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            assertThat(projects.findAllVisibleToUser(tenant(), userId)).isEmpty();
            assertThat(auditTrail.withAction("tenant.member.removed").getFirst().metadata())
                    .containsEntry("projectAccessRemoved", "1");
        }

        @Test
        @DisplayName("re-adding a removed member's email reinstates the account instead of a 500")
        void reAddReinstates() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);
            UUID userId = created.member().userId();
            String subject = idp.created.get("x@upc.edu");
            service.removeMember(owner, userId);

            var again = service.createMember(owner, "X@upc.edu", "X again", TenantRole.OWNER);

            assertThat(again.reinstated()).isTrue();
            assertThat(again.member().userId()).isEqualTo(userId);
            assertThat(again.member().role()).isEqualTo(TenantRole.OWNER);
            assertThat(idp.enabled).containsExactly(subject);
            assertThat(idp.created).hasSize(1);
            assertThat(tenants.findMembership(userId, TENANT)).isPresent();
            assertThat(auditTrail.withAction("tenant.member.reinstated")).hasSize(1);
        }

        @Test
        @DisplayName("a reinstated account gets a new temporary password — the old one never works again")
        void reinstatementReplacesTheCredential() {
            // Removal is often why a credential must stop working (offboarding, a leak). Enabling
            // the account with the password it had would bring that credential back with it.
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);
            String subject = idp.created.get("x@upc.edu");
            service.removeMember(owner, created.member().userId());

            var again = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            assertThat(again.temporaryPassword()).hasSize(16)
                    .isNotEqualTo(created.temporaryPassword());
            assertThat(idp.temporaryPasswords).containsEntry(subject, again.temporaryPassword());
        }

        @Test
        @DisplayName("an account removed by another organization is not re-enabled from this one")
        void removedByAnotherTenantIsAConflict() {
            // Marta was removed from B (whose owner reset her password, then disabled her).
            // A's owner types her email: enabling that account would hand A an account B last
            // governed, with a credential B's owner may know, and pull Marta in without asking.
            User marta = users.save(new User(UUID.randomUUID(), "marta-sub", "marta@x.com",
                    "Marta", Instant.EPOCH));
            idp.created.put("marta@x.com", "marta-sub");
            AuthenticatedUser ownerOfB = new AuthenticatedUser(UUID.randomUUID(), "b-owner",
                    OTHER_TENANT, TenantRole.OWNER);
            audit.tenantMemberRemoved(ownerOfB, marta.id(), 0, 0, 0);

            assertThatThrownBy(() -> service.createMember(owner, "marta@x.com", "Marta",
                    TenantRole.MEMBER))
                    .isInstanceOf(MemberConflictException.class)
                    .hasMessageContaining("was not removed from this organization");
            assertThat(tenants.findMembership(marta.id(), TENANT)).isEmpty();
            assertThat(idp.enabled).isEmpty();
            assertThat(idp.temporaryPasswords).doesNotContainKey("marta-sub");
            assertThat(auditTrail.withAction("tenant.member.reinstated")).isEmpty();
        }

        @Test
        @DisplayName("someone who signed in but was never a member here is not adopted either")
        void signedInButNeverAddedIsAConflict() {
            User walkIn = users.save(new User(UUID.randomUUID(), "walk-in", "w@upc.edu", "W",
                    Instant.EPOCH));
            idp.created.put("w@upc.edu", "walk-in");

            assertThatThrownBy(() -> service.createMember(owner, "w@upc.edu", "W",
                    TenantRole.MEMBER))
                    .isInstanceOf(MemberConflictException.class);
            assertThat(tenants.findMembership(walkIn.id(), TENANT)).isEmpty();
            assertThat(idp.enabled).isEmpty();
        }

        @Test
        @DisplayName("reinstating does not bring back the tokens the removal revoked")
        void reinstatementDoesNotResurrectTokens() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);
            UUID userId = created.member().userId();
            tokens.create(tenant(), userId, "notebook", ApiTokenScope.READ, null);
            service.removeMember(owner, userId);

            service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            assertThat(tokenStore.findByUser(tenant(), userId))
                    .allMatch(t -> !t.isUsable(Instant.now()));
        }

        @Test
        @DisplayName("someone already in this organization is a conflict, not a second account")
        void alreadyAMember() {
            service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            // A3's requireNotAlreadyMember answers first, as ConflictException ALREADY_A_MEMBER —
            // the errorCode the SPA's memberCreateOutcome reads; A8's MemberConflictException
            // covers the cross-tenant and removed-elsewhere cases (V86).
            assertThatThrownBy(() -> service.createMember(owner, "x@upc.edu", "X",
                    TenantRole.MEMBER))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already a member");
            assertThat(idp.created).hasSize(1);
        }

        @Test
        @DisplayName("someone in another organization is a conflict naming the one-tenant rule")
        void inAnotherTenant() {
            User stranger = users.save(new User(UUID.randomUUID(), "s-sub", "s@x.com", "S",
                    Instant.EPOCH));
            idp.created.put("s@x.com", "s-sub");
            tenants.saveMembership(new Membership(UUID.randomUUID(), stranger.id(), OTHER_TENANT,
                    TenantRole.MEMBER, Instant.EPOCH));

            assertThatThrownBy(() -> service.createMember(owner, "s@x.com", "S",
                    TenantRole.MEMBER))
                    .isInstanceOf(MemberConflictException.class)
                    .hasMessageContaining("another organization");
            assertThat(tenants.findMembership(stranger.id(), TENANT)).isEmpty();
            assertThat(idp.enabled).isEmpty();
        }

        @Test
        @DisplayName("an identity-provider account Aran has never seen is a conflict, not adopted")
        void unknownProviderAccount() {
            // Adopting it would reset or reuse a credential nobody in this tenant owns.
            idp.created.put("seeded@upc.edu", "seeded-sub");

            assertThatThrownBy(() -> service.createMember(owner, "seeded@upc.edu", "Seeded",
                    TenantRole.MEMBER))
                    .isInstanceOf(MemberConflictException.class)
                    .hasMessageContaining("never signed in");
            assertThat(idp.enabled).isEmpty();
        }

        @Test
        @DisplayName("a provider that will not re-enable the account leaves no membership behind")
        void enableFailureLeavesNoMembership() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);
            UUID userId = created.member().userId();
            service.removeMember(owner, userId);
            idp.failEnable = true;

            assertThatThrownBy(() -> service.createMember(owner, "x@upc.edu", "X",
                    TenantRole.MEMBER))
                    .isInstanceOf(IdentityProviderPort.IdentityProviderException.class);
            // In production the exception rolls the membership back with the transaction; the
            // stub repository is not transactional, so what this pins is the order — the account
            // is enabled before anything claims the reinstatement happened.
            assertThat(auditTrail.withAction("tenant.member.reinstated")).isEmpty();
        }
    }

    /**
     * Review of UI-OD9: dropping every project row a removed member held orphaned each project
     * they were the only member of — invisible to everyone, owners included, for good.
     */
    @Nested
    @DisplayName("a project only the removed member belongs to")
    class SoleMemberProjects {

        private TenantContext tenant() {
            return new TenantContext(TENANT.toString());
        }

        private UUID project(String name, UUID... memberIds) {
            UUID id = UUID.randomUUID();
            projects.save(tenant(), new Project(id, TENANT, name, "", memberIds[0], Instant.EPOCH,
                    "#000", "PRIVATE", "ACTIVE", 0));
            for (UUID m : memberIds) {
                projects.addMember(tenant(), new ProjectMember(UUID.randomUUID(), id, m,
                        Instant.EPOCH));
            }
            return id;
        }

        @Test
        @DisplayName("is handed to the owner who removed them, and the result names it")
        void handedToTheActingOwner() {
            UUID marta = service.createMember(owner, "m@upc.edu", "Marta", TenantRole.MEMBER)
                    .member().userId();
            UUID sales = project("Sales", marta);

            var departure = service.removeMember(owner, marta);

            assertThat(projects.findAllVisibleToUser(tenant(), owner.userId()))
                    .extracting(Project::id).contains(sales);
            assertThat(projects.findMembers(tenant(), sales))
                    .extracting(ProjectMember::userId).containsExactly(owner.userId());
            assertThat(departure.projectsHandedOver())
                    .containsExactly(new MemberDepartureService.ProjectRef(sales, "Sales"));
            assertThat(auditTrail.withAction("tenant.member.removed").getFirst().metadata())
                    .containsEntry("projectsHandedOver", "1");
            assertThat(auditTrail.withAction("project.member.added"))
                    .anySatisfy(e -> assertThat(e.metadata())
                            .containsEntry("addedUserId", owner.userId().toString())
                            .containsEntry("handedOverFrom", marta.toString()));
        }

        @Test
        @DisplayName("a project someone else still belongs to is left as it is")
        void sharedProjectIsNotHandedOver() {
            UUID marta = service.createMember(owner, "m@upc.edu", "Marta", TenantRole.MEMBER)
                    .member().userId();
            UUID ana = service.createMember(owner, "a@upc.edu", "Ana", TenantRole.MEMBER)
                    .member().userId();
            UUID shared = project("Shared", marta, ana);

            var departure = service.removeMember(owner, marta);

            assertThat(departure.projectsHandedOver()).isEmpty();
            assertThat(projects.findMembers(tenant(), shared))
                    .extracting(ProjectMember::userId).containsExactly(ana);
        }
    }

    /** Audit J7-F1: the governance log records the changes an owner asks it about. */
    @Nested
    @DisplayName("the audit trail")
    class AuditTrail {

        @Test
        @DisplayName("a role change is recorded with the role before and after")
        void roleChangeIsAudited() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            service.changeRole(owner, created.member().userId(), TenantRole.OWNER);

            var changes = auditTrail.withAction("tenant.member.role_changed");
            assertThat(changes).hasSize(1);
            assertThat(changes.getFirst().actorId()).isEqualTo(owner.userId());
            assertThat(changes.getFirst().metadata())
                    .containsEntry("userId", created.member().userId().toString())
                    .containsEntry("from", "MEMBER")
                    .containsEntry("to", "OWNER");
        }

        @Test
        @DisplayName("choosing the role a member already has records nothing, because nothing changed")
        void unchangedRoleIsNotAudited() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            service.changeRole(owner, created.member().userId(), TenantRole.MEMBER);

            assertThat(auditTrail.withAction("tenant.member.role_changed")).isEmpty();
        }

        @Test
        @DisplayName("a password reset is recorded — who replaced whose password, never the password")
        void passwordResetIsAudited() {
            var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);

            String password = service.resetPassword(owner, created.member().userId());

            var resets = auditTrail.withAction("tenant.member.password_reset");
            assertThat(resets).hasSize(1);
            assertThat(resets.getFirst().metadata())
                    .containsEntry("userId", created.member().userId().toString())
                    .doesNotContainValue(password);
        }
    }

    @Test
    @DisplayName("a member reading the list sees no one's last activity — that is an owner's view")
    void lastSeenIsForOwnersOnly() {
        users.lastSeen.put(owner.userId(), Instant.parse("2026-09-20T10:00:00Z"));

        var listed = service.listMembers(member());

        assertThat(listed).isNotEmpty()
                .extracting(TenantMemberService.MemberView::lastSeenAt).containsOnlyNulls();
    }

    @Test
    @DisplayName("the member list carries each member's last activity, null when never seen")
    void listCarriesLastSeen() {
        var created = service.createMember(owner, "x@upc.edu", "X", TenantRole.MEMBER);
        Instant seen = Instant.parse("2026-09-20T10:00:00Z");
        users.lastSeen.put(owner.userId(), seen);

        var listed = service.listMembers(owner);

        assertThat(listed).filteredOn(m -> m.userId().equals(owner.userId()))
                .extracting(TenantMemberService.MemberView::lastSeenAt).containsExactly(seen);
        assertThat(listed).filteredOn(m -> m.userId().equals(created.member().userId()))
                .extracting(TenantMemberService.MemberView::lastSeenAt).containsOnlyNulls();
    }

    // ── stubs ───────────────────────────────────────────────────────────────

    /** Behaves as Keycloak does where it matters: one account per email, disable/enable by subject. */
    private static final class StubIdentityProvider implements IdentityProviderPort {
        final Map<String, String> created = new HashMap<>();
        final Map<String, String> temporaryPasswords = new HashMap<>();
        final List<String> disabled = new ArrayList<>();
        final List<String> enabled = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean failCreate;
        boolean failDisable;
        boolean failEnable;

        @Override public String createUser(String username, String email, String displayName) {
            if (failCreate) {
                throw new IdentityProviderException("stub refuses");
            }
            if (created.containsKey(email)) {
                // Keycloak's 409 — the call the reinstate branch must never reach.
                throw new IdentityProviderException(
                        "an account with that username or email already exists");
            }
            String subject = "subject-" + created.size();
            created.put(email, subject);
            return subject;
        }

        @Override public Optional<String> findSubjectByUsername(String username) {
            return Optional.ofNullable(created.get(username));
        }

        @Override public Optional<String> findSubjectByEmail(String email) {
            return Optional.ofNullable(created.get(email));
        }

        @Override public void setTemporaryPassword(String subject, String password) {
            temporaryPasswords.put(subject, password);
        }

        @Override public void disableUser(String subject) {
            if (failDisable) {
                throw new IdentityProviderException("stub refuses");
            }
            disabled.add(subject);
        }

        @Override public void enableUser(String subject) {
            if (failEnable) {
                throw new IdentityProviderException("stub refuses");
            }
            enabled.add(subject);
        }

        @Override public void deleteUser(String subject) {
            deleted.add(subject);
        }
    }

    private static final class StubUsers implements UserRepository {
        final Map<UUID, User> saved = new HashMap<>();
        final Map<UUID, Instant> lastSeen = new HashMap<>();
        boolean failNextSave;

        @Override public User save(User user) {
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("simulated database failure");
            }
            saved.put(user.id(), user);
            return user;
        }

        @Override public Optional<User> findById(UUID id) {
            return Optional.ofNullable(saved.get(id));
        }

        @Override public void touchLastSeen(UUID userId, java.time.Instant at) {
            lastSeen.put(userId, at);
        }

        @Override public java.util.Map<UUID, java.time.Instant> lastSeenFor(
                java.util.Collection<UUID> userIds) {
            Map<UUID, Instant> found = new HashMap<>();
            userIds.forEach(id -> {
                if (lastSeen.containsKey(id)) {
                    found.put(id, lastSeen.get(id));
                }
            });
            return found;
        }

        @Override public Optional<User> findBySubject(String subject) {
            return saved.values().stream().filter(u -> u.subject().equals(subject)).findFirst();
        }
    }
}

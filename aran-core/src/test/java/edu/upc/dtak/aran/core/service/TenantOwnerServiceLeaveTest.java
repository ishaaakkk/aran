package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.InMemoryApiTokenRepository;
import edu.upc.dtak.aran.core.fake.InMemoryProjectRepository;
import edu.upc.dtak.aran.core.fake.InMemoryTenantRepository;
import edu.upc.dtak.aran.core.fake.InMemoryUserRepository;
import edu.upc.dtak.aran.core.fake.RecordingAuditPort;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Leaving a tenant is removing yourself, so it ends what an owner's removal ends (UI-OD9): tokens
 * revoked, project access dropped, both counted on the audit line — and a project only the leaver
 * belongs to goes to the organization's owners rather than becoming unreachable.
 */
@DisplayName("TenantOwnerService — leaving ends what removal ends")
class TenantOwnerServiceLeaveTest {

    private static final UUID TENANT = UUID.fromString("dddddddd-0000-0000-0000-00000000000d");
    private static final TenantContext CTX = new TenantContext(TENANT.toString());

    private final InMemoryTenantRepository tenants = new InMemoryTenantRepository();
    private final InMemoryApiTokenRepository tokenStore = new InMemoryApiTokenRepository();
    private final InMemoryProjectRepository projects = new InMemoryProjectRepository();
    private final RecordingAuditPort trail = new RecordingAuditPort();
    private final AuditService audit = new AuditService(trail);
    private final ApiTokenService tokens = new ApiTokenService(tokenStore, audit);
    private final TenantOwnerService service = new TenantOwnerService(tenants,
            new InMemoryUserRepository(), UncappedLicenses.create(), audit,
            new MemberDepartureService(tenants, tokens, projects, audit));

    private UUID enrol(TenantRole role) {
        UUID userId = UUID.randomUUID();
        tenants.saveMembership(new Membership(UUID.randomUUID(), userId, TENANT, role,
                Instant.EPOCH));
        return userId;
    }

    private UUID soleProject(String name, UUID memberId) {
        UUID projectId = UUID.randomUUID();
        projects.save(CTX, new Project(projectId, TENANT, name, "", memberId, Instant.EPOCH,
                "#000", "PRIVATE", "ACTIVE", 0));
        projects.addMember(CTX, new ProjectMember(UUID.randomUUID(), projectId, memberId,
                Instant.EPOCH));
        return projectId;
    }

    @Test
    @DisplayName("a member who leaves has their tokens revoked and their projects dropped")
    void leavingRevokes() {
        enrol(TenantRole.OWNER);
        UUID userId = enrol(TenantRole.MEMBER);
        AuthenticatedUser me = new AuthenticatedUser(userId, "me", TENANT, TenantRole.MEMBER);
        tokens.create(CTX, userId, "laptop", ApiTokenScope.READ, null);
        soleProject("p", userId);

        service.leaveCurrentTenant(me);

        assertThat(tokenStore.findByUser(CTX, userId)).allMatch(t -> t.revokedAt() != null);
        assertThat(projects.findAllVisibleToUser(CTX, userId)).isEmpty();
        assertThat(trail.withAction("tenant.member.removed")).singleElement()
                .satisfies(e -> assertThat(e.metadata())
                        .containsEntry("removedUserId", userId.toString())
                        .containsEntry("tokensRevoked", "1")
                        .containsEntry("projectAccessRemoved", "1"));
    }

    @Test
    @DisplayName("a project only the leaver belongs to goes to the organization's owners")
    void soleProjectGoesToTheOwners() {
        UUID ownerA = enrol(TenantRole.OWNER);
        UUID ownerB = enrol(TenantRole.OWNER);
        UUID userId = enrol(TenantRole.MEMBER);
        UUID sales = soleProject("Sales", userId);

        service.leaveCurrentTenant(new AuthenticatedUser(userId, "me", TENANT, TenantRole.MEMBER));

        assertThat(projects.findMembers(CTX, sales)).extracting(ProjectMember::userId)
                .containsExactlyInAnyOrder(ownerA, ownerB);
        assertThat(trail.withAction("tenant.member.removed").getFirst().metadata())
                .containsEntry("projectsHandedOver", "1");
    }

    @Test
    @DisplayName("with nobody to take an unshared project, leaving is refused and nothing changes")
    void noCustodianRefuses() {
        UUID userId = enrol(TenantRole.MEMBER);
        UUID solo = soleProject("Solo", userId);

        assertThatThrownBy(() -> service.leaveCurrentTenant(
                new AuthenticatedUser(userId, "me", TENANT, TenantRole.MEMBER)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Solo");
        assertThat(tenants.findMembership(userId, TENANT)).isPresent();
        assertThat(projects.findMembers(CTX, solo)).hasSize(1);
    }
}

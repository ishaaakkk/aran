package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.InMemoryProjectRepository;
import edu.upc.dtak.aran.core.fake.InMemoryTenantRepository;
import edu.upc.dtak.aran.core.fake.RecordingAuditPort;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Project membership from the owner's side (audit J1-F4): the Members section grants and takes
 * away visibility, and both directions are recorded.
 */
@DisplayName("ProjectService — adding and removing project members")
class ProjectServiceMembersTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");

    private InMemoryProjectRepository projects;
    private InMemoryTenantRepository tenants;
    private RecordingAuditPort auditTrail;
    private ProjectService service;
    private AuthenticatedUser owner;
    private UUID ana;
    private Project project;

    @BeforeEach
    void setUp() {
        projects = new InMemoryProjectRepository();
        tenants = new InMemoryTenantRepository();
        auditTrail = new RecordingAuditPort();
        service = new ProjectService(projects, tenants, new AuditService(auditTrail),
                UncappedLicenses.create());

        owner = join(TenantRole.OWNER);
        ana = join(TenantRole.MEMBER).userId();
        project = service.createProject(owner, "papers", "");
    }

    private AuthenticatedUser join(TenantRole role) {
        UUID userId = UUID.randomUUID();
        tenants.saveMembership(new Membership(UUID.randomUUID(), userId, TENANT, role,
                Instant.EPOCH));
        return new AuthenticatedUser(userId, "sub-" + userId, TENANT, role);
    }

    private AuthenticatedUser as(UUID userId, TenantRole role) {
        return new AuthenticatedUser(userId, "sub-" + userId, TENANT, role);
    }

    @Test
    @DisplayName("an owner adds a member, who then sees the project")
    void addGrantsVisibility() {
        service.addMember(owner, project.id(), ana);

        assertThat(service.listVisibleProjects(as(ana, TenantRole.MEMBER)))
                .extracting(Project::id).containsExactly(project.id());
    }

    @Test
    @DisplayName("an owner removes a member, who then no longer sees it, and the trail says so")
    void removeTakesVisibilityAway() {
        service.addMember(owner, project.id(), ana);

        service.removeMember(owner, project.id(), ana);

        assertThat(service.listVisibleProjects(as(ana, TenantRole.MEMBER))).isEmpty();
        assertThat(auditTrail.withAction("project.member.removed")).singleElement()
                .satisfies(e -> {
                    assertThat(e.targetId()).isEqualTo(project.id());
                    assertThat(e.actorId()).isEqualTo(owner.userId());
                    assertThat(e.metadata()).containsEntry("removedUserId", ana.toString());
                });
    }

    @Test
    @DisplayName("a member cannot remove anyone — sharing is a tenant-administration action")
    void memberCannotRemove() {
        service.addMember(owner, project.id(), ana);

        assertThatThrownBy(() -> service.removeMember(as(ana, TenantRole.MEMBER), project.id(),
                owner.userId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the project's last member cannot be removed, or nobody could ever see it again")
    void lastMemberStays() {
        assertThatThrownBy(() -> service.removeMember(owner, project.id(), owner.userId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only member");
        assertThat(projects.findMembers(new TenantContext(TENANT.toString()), project.id()))
                .hasSize(1);
    }

    @Test
    @DisplayName("removing someone who is not a member is a not-found, not a silent success")
    void removingANonMember() {
        service.addMember(owner, project.id(), ana);
        UUID stranger = join(TenantRole.MEMBER).userId();

        assertThatThrownBy(() -> service.removeMember(owner, project.id(), stranger))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(auditTrail.withAction("project.member.removed")).isEmpty();
    }
}

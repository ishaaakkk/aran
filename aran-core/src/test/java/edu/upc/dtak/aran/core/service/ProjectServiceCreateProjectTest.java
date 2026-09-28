package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.InMemoryProjectRepository;
import edu.upc.dtak.aran.core.fake.InMemoryTenantRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectService - createProject")
class ProjectServiceCreateProjectTest {

    InMemoryProjectRepository projects;
    InMemoryTenantRepository tenants;
    @Mock AuditService auditService;
    @Mock LicenseService licenseService;

    ProjectService service;

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CALLER_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        projects = new InMemoryProjectRepository();
        tenants = new InMemoryTenantRepository();
        service = new ProjectService(projects, tenants, auditService, licenseService);
    }

    private AuthenticatedUser caller() {
        return new AuthenticatedUser(CALLER_ID, "caller-subject", TENANT_ID, TenantRole.OWNER);
    }

    @Test
    @DisplayName("createdBy is set from the authenticated caller, not from external input")
    void creator_is_server_set() {
        doNothing().when(licenseService).requireMutationsAllowed(any());
        doNothing().when(licenseService).requireWithinProjectLimit(any());
        doNothing().when(auditService).projectCreated(any(), any(), any());

        var project = service.createProject(caller(), "My Project", "desc");

        assertThat(project.createdBy())
                .as("createdBy must equal the authenticated caller's userId")
                .isEqualTo(CALLER_ID);
    }

    @Test
    @DisplayName("project belongs to the caller's tenant")
    void project_is_in_callers_tenant() {
        doNothing().when(licenseService).requireMutationsAllowed(any());
        doNothing().when(licenseService).requireWithinProjectLimit(any());
        doNothing().when(auditService).projectCreated(any(), any(), any());

        var project = service.createProject(caller(), "Tenant Project", "desc");

        assertThat(project.tenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("creator can see their own project immediately after creation")
    void creator_sees_own_project_after_creation() {
        doNothing().when(licenseService).requireMutationsAllowed(any());
        doNothing().when(licenseService).requireWithinProjectLimit(any());
        doNothing().when(auditService).projectCreated(any(), any(), any());

        var project = service.createProject(caller(), "Visible Project", "desc");

        var visible = service.listVisibleProjects(caller());
        assertThat(visible)
                .extracting(Project::id)
                .contains(project.id());
    }

    @Test
    @DisplayName("tenant B project is not visible to tenant A caller even with a cross-tenant membership row")
    void tenant_b_project_not_visible_to_tenant_a_caller() {
        doNothing().when(licenseService).requireMutationsAllowed(any());
        doNothing().when(licenseService).requireWithinProjectLimit(any());
        doNothing().when(auditService).projectCreated(any(), any(), any());

        var ownProject = service.createProject(caller(), "Own Project", "");

        var otherTenantId = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
        var otherCtx = new TenantContext(otherTenantId.toString());
        var otherProject = new Project(
                UUID.randomUUID(), otherTenantId, "Other Tenant Project", "",
                UUID.randomUUID(), Instant.now());
        projects.save(otherCtx, otherProject);
        projects.addMember(otherCtx, new ProjectMember(
                UUID.randomUUID(), otherProject.id(), CALLER_ID, Instant.now()));

        var visible = service.listVisibleProjects(caller());

        assertThat(visible)
                .extracting(Project::id)
                .containsOnly(ownProject.id())
                .doesNotContain(otherProject.id());
    }
}

package edu.upc.dtak.aran.app.service;

import edu.upc.dtak.aran.core.domain.*;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import edu.upc.dtak.aran.core.service.AuditService;
import edu.upc.dtak.aran.core.service.ProjectService;
import java.util.Optional;
import java.util.UUID;

import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProjectService} authorization invariants.
 *
 * <p>These tests focus on the rules that are hard to exercise through the HTTP
 * layer: the tenant-OWNER check on {@code addMember}, the cross-tenant
 * isolation check, and the licensing gates.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectService")
class ProjectServiceTest {

    @Mock
    ProjectRepository projectRepository;
    @Mock
    TenantRepository tenantRepository;
    @Mock AuditService auditService;
    @Mock LicenseService licenseService;

    ProjectService projectService;

    private static final UUID TENANT_ID  = UUID.randomUUID();
    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID TARGET_ID  = UUID.randomUUID();

    private AuthenticatedUser owner() {
        return new AuthenticatedUser(USER_ID, "subject", TENANT_ID, TenantRole.OWNER);
    }

    private AuthenticatedUser member() {
        return new AuthenticatedUser(USER_ID, "subject", TENANT_ID, TenantRole.MEMBER);
    }

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(projectRepository, tenantRepository,
                auditService, licenseService);
    }

    @Nested
    @DisplayName("addMember")
    class AddMember {

        @Test
        @DisplayName("throws AccessDeniedException when caller is MEMBER, not OWNER")
        void throws_for_member_caller() {
            doNothing().when(licenseService).requireMutationsAllowed(any());

            assertThatThrownBy(() ->
                    projectService.addMember(member(), PROJECT_ID, TARGET_ID))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("OWNER");

            // The project and tenant repositories should never be touched
            // when the role check fails early.
            verify(projectRepository, never()).findVisibleToUser(any(), any(), any());
            verify(tenantRepository, never()).findMembership(any(), any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when target has no tenant membership")
        void throws_when_target_not_tenant_member() {
            doNothing().when(licenseService).requireMutationsAllowed(any());

            // Set up: caller can see the project
            var tenant = new TenantContext(TENANT_ID.toString());
            var project = new Project(
                    PROJECT_ID, TENANT_ID, "Test", "",
                    USER_ID, java.time.Instant.now());
            when(projectRepository.findVisibleToUser(any(), any(), any()))
                    .thenReturn(Optional.of(project));

            // Target user has no membership in this tenant
            when(tenantRepository.findMembership(TARGET_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    projectService.addMember(owner(), PROJECT_ID, TARGET_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            // Should never reach the addMember port call
            verify(projectRepository, never()).addMember(any(), any());
        }

        @Test
        @DisplayName("succeeds when caller is OWNER and target is a tenant member")
        void succeeds_for_valid_addition() {
            doNothing().when(licenseService).requireMutationsAllowed(any());

            var project = new Project(
                    PROJECT_ID, TENANT_ID, "Test", "",
                    USER_ID, java.time.Instant.now());
            when(projectRepository.findVisibleToUser(any(), any(), any()))
                    .thenReturn(Optional.of(project));

            var membership = new Membership(
                    UUID.randomUUID(), TARGET_ID, TENANT_ID,
                    TenantRole.MEMBER, java.time.Instant.now());
            when(tenantRepository.findMembership(TARGET_ID, TENANT_ID))
                    .thenReturn(Optional.of(membership));

            var addedMember = new ProjectMember(
                    UUID.randomUUID(), PROJECT_ID, TARGET_ID, java.time.Instant.now());
            when(projectRepository.addMember(any(), any())).thenReturn(addedMember);

            var result = projectService.addMember(owner(), PROJECT_ID, TARGET_ID);

            org.assertj.core.api.Assertions.assertThat(result.userId()).isEqualTo(TARGET_ID);
            verify(auditService).projectMemberAdded(any(), any(), any());
        }
    }
}

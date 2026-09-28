package edu.upc.dtak.aran.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The Schema versions page's promote ({@code POST /api/graph/promote/{id}}) promotes a project's
 * draft as that project's run since J4-F2, so it writes that project's run status and baseline.
 * Review UA5 (round 2): it asked no project question, so a tenant member outside the project
 * could change the project's model state. It now asks {@link ProjectAccess} for the owning project,
 * as every model route does. No Spring context, no Docker.
 */
@DisplayName("POST /api/graph/promote/{id} — only the owning project's members promote its draft")
class SourceGraphControllerPromoteAccessTest {

    private final IntegrationModelService modelService = mock(IntegrationModelService.class);
    private final ProjectAccess projectAccess = mock(ProjectAccess.class);
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    private final AuthenticatedUser caller =
            new AuthenticatedUser(UUID.randomUUID(), "sub", UUID.randomUUID(), TenantRole.MEMBER);
    private final UUID draftId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(caller);
        when(modelService.promoteDraftVersion(any(), eq(draftId), eq(caller.userId()))).thenReturn(
                new GraphVersion(draftId, caller.tenantId(), 4, "ACTIVE", Instant.now(), caller.userId(),
                        Instant.now(), null));
        mvc = MockMvcBuilders.standaloneSetup(new SourceGraphController(
                        mock(GraphStore.class), mock(GraphVersionPort.class), mock(DatasetRepository.class),
                        currentUser, modelService, projectAccess,
                        mock(edu.upc.dtak.aran.core.service.DataProductService.class),
                        mock(edu.upc.dtak.aran.core.service.AuditService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a draft of a project the caller is not a member of is 404, names no project, and is not promoted")
    void aNonMemberCannotPromoteAProjectsDraft() throws Exception {
        when(modelService.owningProjectOfDraft(any(), eq(draftId))).thenReturn(Optional.of(projectId));
        when(projectAccess.getVisibleProject(caller, projectId))
                .thenThrow(new ResourceNotFoundException("project not found: " + projectId));

        mvc.perform(post("/api/graph/promote/{d}", draftId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", containsString("project you are not a member of")))
                .andExpect(jsonPath("$.detail", not(containsString(projectId.toString()))));
        verify(modelService, never()).promoteDraftVersion(any(), any(), any());
    }

    @Test
    @DisplayName("a member of the owning project promotes its draft")
    void aMemberPromotes() throws Exception {
        when(modelService.owningProjectOfDraft(any(), eq(draftId))).thenReturn(Optional.of(projectId));

        mvc.perform(post("/api/graph/promote/{d}", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(4));
        verify(projectAccess).getVisibleProject(caller, projectId);
    }

    @Test
    @DisplayName("a draft no project's run compiled asks no project question — it stays tenant-wide, behind the guard")
    void aDraftNoProjectOwnsIsTenantWide() throws Exception {
        when(modelService.owningProjectOfDraft(any(), eq(draftId))).thenReturn(Optional.empty());

        mvc.perform(post("/api/graph/promote/{d}", draftId))
                .andExpect(status().isOk());
        verify(projectAccess, never()).getVisibleProject(any(), any());
    }
}

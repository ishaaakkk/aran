package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.ProjectDtos.AddMemberRequest;
import edu.upc.dtak.aran.app.web.dto.ProjectDtos.CreateProjectRequest;
import edu.upc.dtak.aran.app.web.dto.ProjectDtos.ProjectMemberResponse;
import edu.upc.dtak.aran.app.web.dto.ProjectDtos.ProjectResponse;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.ProjectService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for projects — the worked example of a full vertical slice through
 * the architecture: HTTP → {@link CurrentUserResolver} → {@link ProjectService}
 * (core) → persistence ports → JPA adapter.
 *
 * <p>The controller is deliberately thin. It does three things and no more:
 * resolve the caller, validate the request body (Jakarta Validation on the
 * DTOs), and map between DTOs and domain records. All authorization rules and
 * invariants live in {@link ProjectService}, so they hold regardless of
 * transport.
 *
 * <p>Every endpoint here sits under {@code /api/**}, so it is matched by the
 * stateless JWT resource-server filter chain in
 * {@code edu.upc.dtak.aran.app.security.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentUserResolver currentUser;

    public ProjectController(ProjectService projectService, CurrentUserResolver currentUser) {
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    /**
     * List the projects in the caller's tenant that the caller can see
     * (i.e. has a {@code ProjectMember} row for).
     */
    @GetMapping
    public List<ProjectResponse> listProjects() {
        AuthenticatedUser caller = currentUser.require();
        return projectService.listVisibleProjects(caller).stream()
                .map(ProjectResponse::from)
                .toList();
    }

    /**
     * Create a project in the caller's tenant. The caller becomes its creator
     * and first member, atomically (enforced in the service layer).
     */
    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(
            @Valid @RequestBody CreateProjectRequest body) {

        AuthenticatedUser caller = currentUser.require();
        Project created = projectService.createProject(
                caller, body.name(), body.descriptionOrEmpty(), body.colorOrDefault(), body.privacyOrDefault());

        return ResponseEntity
                .created(URI.create("/api/projects/" + created.id()))
                .body(ProjectResponse.from(created));
    }

    /**
     * Fetch a single project the caller can see. Returns 404 both when the
     * project does not exist and when it exists but is not visible to the
     * caller — the two are deliberately indistinguishable.
     */
    @GetMapping("/{projectId}")
    public ProjectResponse getProject(@PathVariable UUID projectId) {
        AuthenticatedUser caller = currentUser.require();
        return ProjectResponse.from(projectService.getVisibleProject(caller, projectId));
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberResponse> listMembers(@PathVariable UUID projectId) {
        AuthenticatedUser caller = currentUser.require();
        return projectService.listMembers(caller, projectId).stream()
                .map(ProjectMemberResponse::from)
                .toList();
    }

    @PostMapping("/{projectId}/members")
    public ResponseEntity<ProjectMemberResponse> addMember(
            @PathVariable UUID projectId,
            @Valid @RequestBody AddMemberRequest body) {

        AuthenticatedUser caller = currentUser.require();
        var member = projectService.addMember(caller, projectId, body.userId());
        return ResponseEntity
                .created(URI.create("/api/projects/" + projectId + "/members/" + member.userId()))
                .body(ProjectMemberResponse.from(member));
    }

    /**
     * Take a member's visibility of the project away — owner-only, audited, and refused for the
     * project's last member, whom nobody could replace (audit J1-F4).
     */
    @DeleteMapping("/{projectId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID projectId,
                                             @PathVariable UUID userId) {
        projectService.removeMember(currentUser.require(), projectId, userId);
        return ResponseEntity.noContent().build();
    }
}

package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Data-transfer objects for the project API.
 *
 * <p>Kept separate from the core domain records on purpose: the wire format is
 * an API concern and should be free to evolve independently of the domain
 * model. Request DTOs carry Jakarta Validation constraints; response DTOs are
 * built from domain records by their static {@code from(...)} factories.
 */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    /**
     * Request body for creating a project. Note there is deliberately no
     * {@code createdBy} or {@code tenantId} field — both are derived
     * server-side from the authenticated caller and must never be client-supplied.
     */
    public record CreateProjectRequest(
            @NotBlank(message = "project name is required")
            @Size(max = 256, message = "project name must be at most 256 characters")
            String name,

            @Size(max = 4000, message = "description must be at most 4000 characters")
            String description,

            @Size(max = 32, message = "color must be at most 32 characters")
            String color,

            @Size(max = 32, message = "privacy must be at most 32 characters")
            String privacy
    ) {
        public String descriptionOrEmpty() {
            return description == null ? "" : description;
        }

        public String colorOrDefault() {
            return color == null || color.isBlank() ? "#4945FF" : color;
        }

        public String privacyOrDefault() {
            return privacy == null || privacy.isBlank() ? "PRIVATE" : privacy;
        }
    }

    public record AddMemberRequest(
            @jakarta.validation.constraints.NotNull
            UUID userId
    ) {
    }

    public record ProjectResponse(
            UUID id,
            UUID tenantId,
            String name,
            String description,
            UUID createdBy,
            Instant createdAt,
            String color,
            String privacy,
            String status,
            int progress
    ) {
        public static ProjectResponse from(Project p) {
            return new ProjectResponse(
                    p.id(), p.tenantId(), p.name(), p.description(),
                    p.createdBy(), p.createdAt(), p.color(), p.privacy(),
                    p.status(), p.progress());
        }
    }

    /** Response view of a project-member visibility row. */
    public record ProjectMemberResponse(
            UUID userId,
            Instant addedAt
    ) {
        public static ProjectMemberResponse from(ProjectMember m) {
            return new ProjectMemberResponse(m.userId(), m.addedAt());
        }
    }
}

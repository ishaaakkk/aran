package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A project — a tenant-owned unit of work.
 *
 * <p>A project belongs to exactly one {@link Tenant} and records the
 * {@link User} who created it. The creator is <em>immutable</em>: it is set
 * server-side from the authenticated principal at creation time and never
 * accepted from a request body.
 *
 * <p>Visibility is <em>not</em> a property of the project itself — it is
 * modelled explicitly via {@link ProjectMember} rows (Option A: explicit
 * project membership). A user can see a project only if it is in their tenant
 * <em>and</em> they have a {@code ProjectMember} entry for it. The creator is
 * auto-added as the first member.
 *
 * @param tenantId  the owning tenant — the isolation boundary
 * @param name      human-readable project name (unique within the tenant)
 * @param description free-text description; may be empty but not null
 * @param createdBy  the user who created the project; immutable
 */
@NullMarked
public record Project(
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
    public Project {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project name must not be blank");
        }
        if (description == null) {
            throw new IllegalArgumentException("project description must not be null (use \"\")");
        }
        if (color == null || color.isBlank()) {
            throw new IllegalArgumentException("project color must not be blank");
        }
        if (privacy == null || privacy.isBlank()) {
            throw new IllegalArgumentException("project privacy must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("project status must not be blank");
        }
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("project progress must be between 0 and 100");
        }
    }

    public Project(UUID id, UUID tenantId, String name, String description, UUID createdBy, Instant createdAt) {
        this(id, tenantId, name, description, createdBy, createdAt, "#4945FF", "PRIVATE", "ACTIVE", 0);
    }
}

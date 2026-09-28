package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A project member — the explicit association that grants a {@link User}
 * visibility of, and access to, a {@link Project}.
 *
 * <p>This is the "Option A" visibility model: a project is visible to a user
 * only if a {@code ProjectMember} row links them. There is no implicit
 * tenant-wide visibility — sharing a project with everyone in the tenant would
 * be expressed as many {@code ProjectMember} rows (or, later, as a flag layered
 * on top of this table), never by removing the explicit check.
 *
 * <p>Invariant: the project's creator is always added as a {@code ProjectMember}
 * at creation time, so the creator can always see their own project.
 *
 * <p>The pair {@code (projectId, userId)} is unique. This skeleton keeps the
 * association role-free; a per-project role (owner/editor/viewer) is the
 * natural place to extend, and would be added as a field here.
 */
@NullMarked
public record ProjectMember(
        UUID id,
        UUID projectId,
        UUID userId,
        Instant addedAt
) {
}

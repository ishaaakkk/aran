package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * An audit-log entry — an immutable record of a mutating action.
 *
 * <p>Recorded in the same transaction as the action it audits, so either both
 * land or neither does. Retrofitting an audit trail later is genuinely
 * painful — the trail starts the day you ship the first feature, not later.
 *
 * <p>The {@code action} is a short, stable identifier
 * ({@code project.created}, {@code project.member.added}) — not a free-text
 * sentence — so the log is queryable. {@code targetType} + {@code targetId}
 * point to the affected aggregate; {@code metadata} carries action-specific
 * detail (e.g. the new member id, the previous value of a renamed field) as
 * a small string map. Heavy payloads do not belong here.
 *
 * @param tenantId   the tenant the action happened in (audit is tenant-owned)
 * @param actorId    the user who performed the action; may be null only for
 *                   system actions (none yet)
 * @param action     the action identifier (e.g. {@code "project.created"})
 * @param targetType the kind of aggregate affected (e.g. {@code "project"})
 * @param targetId   the affected aggregate's id; may be null for actions that
 *                   are not bound to a single aggregate
 * @param metadata   action-specific detail; never null (use empty map)
 * @param occurredAt when the action happened
 */
@NullMarked
public record AuditEvent(
        UUID id,
        UUID tenantId,
        @Nullable UUID actorId,
        String action,
        String targetType,
        @Nullable UUID targetId,
        Map<String, String> metadata,
        Instant occurredAt
) {
    public AuditEvent {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("audit action must not be blank");
        }
        if (targetType == null || targetType.isBlank()) {
            throw new IllegalArgumentException("audit targetType must not be blank");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("audit metadata must not be null (use Map.of())");
        }
    }
}

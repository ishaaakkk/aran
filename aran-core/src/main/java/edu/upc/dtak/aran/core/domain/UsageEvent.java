package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One attributable action — the unit the admin surface counts.
 *
 * <p>Separate from {@code AuditEvent} on purpose. Audit answers "who did what", is read when
 * something went wrong, and every row is individually meaningful. Usage answers "how much", is
 * read on a dashboard, and is meaningful in aggregate. Mixing them would drown the events an
 * administrator needs behind thousands of query executions.
 *
 * @param userId  null for work no human asked for — a scheduled refresh, a system job. Inventing
 *                an actor there would corrupt the per-user attribution this table exists for.
 * @param durationMs how long it took, where the caller knows. Null rather than 0: a zero that
 *                   means "not measured" becomes a wrong average on a dashboard.
 * @param rowCount rows produced, where that is meaningful for the kind.
 * @param byteCount bytes moved, where that is meaningful for the kind.
 */
@NullMarked
public record UsageEvent(
        UUID id,
        UUID tenantId,
        @Nullable UUID userId,
        UsageKind kind,
        Instant occurredAt,
        @Nullable Long durationMs,
        @Nullable Long rowCount,
        @Nullable Long byteCount,
        Map<String, String> metadata
) {
    public UsageEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

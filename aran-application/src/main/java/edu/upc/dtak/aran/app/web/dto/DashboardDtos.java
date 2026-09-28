package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.AuditEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Data-transfer objects for the dashboard summary API.
 *
 * <p>Every field here is a number the system actually knows or a row it actually
 * wrote. The dashboard has no series it cannot source, and shows nothing it
 * cannot count.
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /**
     * One line of the activity feed, drawn from the audit log.
     *
     * @param ts     when the action happened
     * @param actor  the acting user's id, or {@code null} for system actions
     * @param action the stable action identifier, e.g. {@code project.created}
     * @param target {@code targetType} and {@code targetId} joined, e.g. {@code project:1f0e…}
     */
    public record AuditLine(
            Instant ts,
            @Nullable String actor,
            String action,
            String target
    ) {
        public static AuditLine from(AuditEvent event) {
            String target = event.targetId() != null
                    ? event.targetType() + ":" + event.targetId()
                    : event.targetType();
            return new AuditLine(
                    event.occurredAt(),
                    event.actorId() != null ? event.actorId().toString() : null,
                    event.action(),
                    target);
        }
    }

    /**
     * The dashboard's whole truth.
     *
     * @param activeSchemaVersion the promoted {@code GraphVersion}'s number, or
     *                            {@code null} when no schema has been promoted yet
     * @param draftSchemaVersion  the number of a compiled DRAFT newer than the active version,
     *                            waiting to be promoted, or {@code null} when there is none
     */
    public record DashboardSummary(
            int projects,
            int repositories,
            int datasets,
            int readyDatasets,
            @Nullable Integer activeSchemaVersion,
            @Nullable Integer draftSchemaVersion,
            java.util.List<AuditLine> recentAudit
    ) {
    }
}

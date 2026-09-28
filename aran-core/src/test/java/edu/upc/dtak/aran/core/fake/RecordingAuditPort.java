package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.AuditEvent;
import edu.upc.dtak.aran.core.domain.AuditPort;
import edu.upc.dtak.aran.core.service.AuditService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * An {@link AuditPort} that keeps what it is given, so a test can assert on the trail a service
 * wrote — the action, the target and the metadata — through the real {@link AuditService}.
 */
public final class RecordingAuditPort implements AuditPort {

    private final List<AuditEvent> events = new ArrayList<>();

    @Override
    public synchronized AuditEvent record(TenantContext tenant, AuditEvent event) {
        events.add(event);
        return event;
    }

    @Override
    public synchronized List<AuditEvent> recent(TenantContext tenant, int limit) {
        UUID tenantId = UUID.fromString(tenant.value());
        return events.stream()
                .filter(e -> e.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(AuditEvent::occurredAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean exists(TenantContext tenant, String action, String metadataKey,
                                       String metadataValue) {
        UUID tenantId = UUID.fromString(tenant.value());
        return events.stream().anyMatch(e -> e.tenantId().equals(tenantId)
                && e.action().equals(action)
                && metadataValue.equals(e.metadata().get(metadataKey)));
    }

    @Override
    public synchronized List<AuditEvent> recentForProjects(TenantContext tenant,
                                                           Collection<UUID> projectIds, int limit) {
        return recent(tenant, Integer.MAX_VALUE).stream()
                .filter(e -> ("project".equals(e.targetType()) && projectIds.contains(e.targetId()))
                        || projectIds.stream().anyMatch(id ->
                                id.toString().equals(e.metadata().get(AuditService.PROJECT_ID_KEY))))
                .limit(limit)
                .toList();
    }

    /** Every event recorded with this action, oldest first. */
    public synchronized List<AuditEvent> withAction(String action) {
        return events.stream().filter(e -> e.action().equals(action)).toList();
    }

    public synchronized List<AuditEvent> all() {
        return List.copyOf(events);
    }
}

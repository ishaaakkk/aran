package edu.upc.dtak.aran.adapter.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.adapter.persistence.entity.AuditEventEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.AuditEventJpaRepository;
import edu.upc.dtak.aran.core.domain.AuditEvent;
import edu.upc.dtak.aran.core.domain.AuditPort;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link AuditPort}.
 *
 * <p>The read names {@code tenant_id} in its own predicate rather than relying on the
 * shared-schema tenant filter. That filter is still enabled by {@code TenantFilterAspect},
 * but it was demonstrably not in force on at least one path — a tenant's promote read
 * another tenant's graph version — and an unscoped read here would return the most recent
 * audit events across every tenant in the installation.
 *
 * <p>Serialises {@link AuditEvent#metadata()} to JSON for the JSONB column.
 * Uses Jackson directly rather than rolling a String map encoder — Jackson is
 * already on the classpath via spring-boot-starter-web in the application.
 */
@Component
@NullMarked
class JpaAuditAdapter implements AuditPort {

    private final AuditEventJpaRepository jpa;
    private final ObjectMapper objectMapper;

    JpaAuditAdapter(AuditEventJpaRepository jpa,
                    ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    public AuditEvent record(TenantContext tenant, AuditEvent event) {
        // Port-boundary invariant, matching the write-side guards in JpaJobRepository:51 and
        // JpaDataProductRepository:36 — this adapter was the only one of the three missing it.
        //
        // It cannot fire today: the sole caller (AuditService.recordRaw) builds the
        // TenantContext out of the same id it puts in the event, so the two agree by
        // construction. That is precisely why it is worth stating — the pairing is currently an
        // unwritten convention, and an audit row filed under the wrong tenant would corrupt the
        // one table whose job is to say who did what. Deliberately NOT compared against the
        // ambient TenantContextHolder: recordRaw exists for platform-admin actions and for
        // tenant creation itself, which legitimately run outside any bound tenant scope.
        UUID tenantId = UUID.fromString(tenant.value());
        if (!event.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "AuditEvent tenant ID does not match the transactional tenant context");
        }
        String metadataJson = toJson(event.metadata());
        AuditEventEntity saved = jpa.save(new AuditEventEntity(
                event.id(),
                event.tenantId(),
                event.actorId(),
                event.action(),
                event.targetType(),
                event.targetId(),
                metadataJson,
                event.occurredAt()
        ));
        return toDomain(saved);
    }

    @Override
    public List<AuditEvent> recent(TenantContext tenant, int limit) {
        return jpa
                .findAllByTenantIdOrderByOccurredAtDesc(
                        UUID.fromString(tenant.value()), Limit.of(limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean exists(TenantContext tenant, String action, String metadataKey,
                          String metadataValue) {
        return jpa.existsByMetadata(UUID.fromString(tenant.value()), action, metadataKey,
                metadataValue);
    }

    @Override
    public List<AuditEvent> recentForProjects(TenantContext tenant,
                                              java.util.Collection<UUID> projectIds, int limit) {
        if (projectIds.isEmpty()) {
            // `IN ()` is not SQL; and a member who sees no project has no project lines to see.
            return List.of();
        }
        return jpa
                .findRecentForProjects(UUID.fromString(tenant.value()), projectIds,
                        projectIds.stream().map(UUID::toString).toList(), limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException ex) {
            // Map<String,String> serialisation cannot realistically fail; if it
            // does it is a bug, not a runtime condition to handle.
            throw new IllegalStateException("failed to serialise audit metadata", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("corrupt audit metadata JSON", ex);
        }
    }

    private AuditEvent toDomain(AuditEventEntity e) {
        return new AuditEvent(
                e.getId(),
                e.getTenantId(),
                e.getActorId(),
                e.getAction(),
                e.getTargetType(),
                e.getTargetId(),
                fromJson(e.getMetadata()),
                e.getOccurredAt()
        );
    }
}

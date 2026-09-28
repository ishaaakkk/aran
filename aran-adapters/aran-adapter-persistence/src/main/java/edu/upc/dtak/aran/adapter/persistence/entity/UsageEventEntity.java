package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code usage_event}. See V24 for why it is separate from {@code audit_event}. */
@Entity
@Table(name = "usage_event")
public class UsageEventEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "byte_count")
    private Long byteCount;

    protected UsageEventEntity() {
    }

    public UsageEventEntity(UUID id, UUID tenantId, UUID userId, String kind, Instant occurredAt,
                            Long durationMs, Long rowCount, Long byteCount) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.kind = kind;
        this.occurredAt = occurredAt;
        this.durationMs = durationMs;
        this.rowCount = rowCount;
        this.byteCount = byteCount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getKind() {
        return kind;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public Long getByteCount() {
        return byteCount;
    }
}

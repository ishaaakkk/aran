package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code storage_sample}. See V25 for why a level does not belong in {@code usage_event}. */
@Entity
@Table(name = "storage_sample")
public class StorageSampleEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Column(name = "logical_bytes", nullable = false)
    private long logicalBytes;

    @Column(name = "physical_bytes", nullable = false)
    private long physicalBytes;

    @Column(name = "snapshot_count", nullable = false)
    private long snapshotCount;

    protected StorageSampleEntity() {
    }

    public StorageSampleEntity(UUID id, UUID tenantId, Instant measuredAt,
                               long logicalBytes, long physicalBytes, long snapshotCount) {
        this.id = id;
        this.tenantId = tenantId;
        this.measuredAt = measuredAt;
        this.logicalBytes = logicalBytes;
        this.physicalBytes = physicalBytes;
        this.snapshotCount = snapshotCount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public long getLogicalBytes() {
        return logicalBytes;
    }

    public long getPhysicalBytes() {
        return physicalBytes;
    }

    public long getSnapshotCount() {
        return snapshotCount;
    }
}

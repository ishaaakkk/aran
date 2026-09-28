package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for a captured schema fingerprint (V22) — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.drift.StoredSchemaFingerprint}.
 *
 * <p>Append-only: every field is {@code updatable = false} because a capture is a statement about
 * a moment. Amending one would rewrite history and make a later diff compare against something
 * that was never true.
 *
 * <p>{@code fingerprint} is JSONB held as its JSON text, the same pass-through
 * {@code ModelMappingEntity} uses for {@code evidence}.
 */
@Entity
@Table(name = "dataset_schema_fingerprint")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DatasetSchemaFingerprintEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "dataset_id", nullable = false, updatable = false)
    private UUID datasetId;

    @Column(name = "fingerprint_hash", nullable = false, updatable = false, length = 64)
    private String fingerprintHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fingerprint", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String fingerprint;

    @Column(name = "snapshot_id", updatable = false, length = 128)
    private String snapshotId;

    @Column(name = "captured_via", nullable = false, updatable = false, length = 16)
    private String capturedVia;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;
}

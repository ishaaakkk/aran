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
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for an audit event — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.AuditEvent}.
 *
 * <p>Append-only by design. There is no setter for any non-id field, and the
 * column-level immutability is enforced in the schema (UPDATE/DELETE
 * privileges should be revoked on the table in production; the migration
 * leaves a comment noting this).
 *
 * <p>{@code metadata} is stored as JSONB ({@code SqlTypes.JSON}), which keeps
 * it queryable in Postgres without inventing a side table.
 *
 * <p>Tenant-filtered: the {@code tenantFilter} filter definition lives on
 * {@code ProjectEntity}; this entity carries the {@link Filter} annotation
 * referencing it. Audit reads cannot cross tenants.
 */
@Entity
@Table(name = "audit_event")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AuditEventEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}

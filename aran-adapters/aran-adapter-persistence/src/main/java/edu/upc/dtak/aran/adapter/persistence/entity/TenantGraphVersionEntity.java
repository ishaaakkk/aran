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
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * JPA entity for a tenant's graph version index.
 *
 * <p>At most one row per tenant may be {@code ACTIVE}, and that rule lives only in V43's partial
 * unique index: a {@code WHERE} clause has no {@code @UniqueConstraint} form, and {@code ddl-auto:
 * validate} would not check one anyway — it compares tables and columns, never constraints.
 * {@code OneActiveGraphVersionPerTenantIT} is what proves the database enforces it.
 */
@Entity
@Table(name = "tenant_graph_version")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TenantGraphVersionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(nullable = false)
    private String status; // 'DRAFT' | 'ACTIVE' | 'HISTORICAL'

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    private String notes;
}

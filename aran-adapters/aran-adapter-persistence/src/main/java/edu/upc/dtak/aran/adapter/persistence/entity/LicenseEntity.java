package edu.upc.dtak.aran.adapter.persistence.entity;

import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.LicenseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for a tenant's cloud-mode license — one row per tenant, used by
 * {@code DatabaseLicenseProvider} as the source of truth.
 *
 * <p>Stores the named plan plus a small set of per-license override columns.
 * The plan-to-limits mapping comes from configuration ({@code aran.licensing.plans.*});
 * the override columns let a platform admin negotiate a non-standard
 * arrangement without inventing a new plan name.
 *
 * <p>Not tenant-filtered through the Hibernate {@code tenantFilter} — the
 * license itself is part of the tenant's identity, looked up by tenant id
 * directly. Per-tenant isolation is structural: the {@code tenantId} column
 * is the primary key.
 */
@Entity
@Table(name = "license")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LicenseEntity {

    /** The owning tenant id — also the PK. One license row per tenant. */
    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LicensePlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LicenseStatus status;

    /**
     * Override columns. Null means "use the plan default from config". Set to
     * a positive number to override on a per-tenant basis.
     */
    @Column(name = "max_users_override")
    private Integer maxUsersOverride;

    @Column(name = "max_projects_override")
    private Integer maxProjectsOverride;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    public LicenseEntity(UUID tenantId, LicensePlan plan, LicenseStatus status,
                         Instant issuedAt) {
        this.tenantId = tenantId;
        this.plan = plan;
        this.status = status;
        this.issuedAt = issuedAt;
    }
}

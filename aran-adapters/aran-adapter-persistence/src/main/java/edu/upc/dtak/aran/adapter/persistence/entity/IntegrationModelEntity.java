package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/**
 * JPA entity for a project's integration model — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.IntegrationModel}. One row per
 * {@code (tenant, project)} (V16 unique constraint). Tenant-filtered like the
 * dataset/job entities.
 */
@Entity
@Table(name = "integration_model",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_integration_model_tenant_project",
                columnNames = {"tenant_id", "project_id"}))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IntegrationModelEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The run whose draft is the current active graph; null until the first promotion. */
    @Column(name = "active_integration_run_id")
    private UUID activeIntegrationRunId;

    /** Set when integrated material is edited under an incremental baseline. */
    @Column(name = "rebuild_required", nullable = false)
    private boolean rebuildRequired;
}

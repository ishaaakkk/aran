package edu.upc.dtak.aran.adapter.persistence.entity;

import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * JPA entity for a data product — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.DataProduct} (V18 migration).
 *
 * <p>Tenant-filtered: the {@code tenantFilter} definition lives on
 * {@code ProjectEntity}; this entity carries the {@link Filter} referencing it.
 */
@Entity
@Table(name = "data_product")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DataProductEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(nullable = false)
    private String oql;

    @Column(name = "schema_version_id", nullable = false, updatable = false)
    private UUID schemaVersionId;

    /** Fixed at creation, like {@code schemaVersionId} above (V34, Phase 5) — never updated. */
    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false, updatable = false)
    private ProductAccessMode accessMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataProductStatus status;

    @Column(name = "snapshot_id")
    private String snapshotId;

    @Column(name = "row_count")
    private Long rowCount;

    /** {@code ProductColumn} JSON for the materialized result's columns (V29). */
    @Column(name = "output_schema")
    private String outputSchema;

    @Column(name = "materialized_at")
    private Instant materializedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The schema version the last successful build was planned against (UI-OD12, V47). */
    @Column(name = "built_against_version_id")
    private UUID builtAgainstVersionId;
}

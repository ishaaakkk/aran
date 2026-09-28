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
 * JPA entity for a mapping's algorithm-application state — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.MappingApplication}. One row per mapping.
 */
@Entity
@Table(name = "model_mapping_application",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_mapping_application_mapping",
                columnNames = {"mapping_id"}))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ModelMappingApplicationEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID modelId;

    @Column(name = "mapping_id", nullable = false, updatable = false)
    private UUID mappingId;

    @Column(nullable = false)
    private String state;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

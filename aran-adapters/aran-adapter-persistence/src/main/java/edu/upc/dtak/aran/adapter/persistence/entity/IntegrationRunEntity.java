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

/**
 * JPA entity for one execution of the schema-integration algorithm — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.IntegrationRun}. {@code mode}/{@code status} are
 * held as the enum name strings.
 */
@Entity
@Table(name = "integration_run")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IntegrationRunEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID modelId;

    @Column(nullable = false, updatable = false)
    private String mode;

    @Column(nullable = false)
    private String status;

    @Column(name = "base_version_id")
    private UUID baseVersionId;

    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "draft_version_id")
    private UUID draftVersionId;

    @Column(name = "promoted_version_id")
    private UUID promotedVersionId;

    @Column(name = "error_code", columnDefinition = "text")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

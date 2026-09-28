package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/**
 * JPA entity for one enrolled source class of an integration model — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.ModelSource}.
 */
@Entity
@Table(name = "model_source",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_model_source_model_dataset_class",
                columnNames = {"model_id", "dataset_id", "class_iri"}))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ModelSourceEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID modelId;

    @Column(name = "dataset_id", nullable = false, updatable = false)
    private UUID datasetId;

    @Column(name = "class_iri", nullable = false, updatable = false, columnDefinition = "text")
    private String classIri;

    @Column(name = "wrapper_sql", columnDefinition = "text")
    private String wrapperSql;

    /** Phase 3A verify-wrapper verdict for {@link #wrapperSql} — see V30 and {@code ModelSource}. */
    @Column(name = "expansion_status", length = 16)
    private String expansionStatus;

    @Column(name = "expansion_reason", length = 512)
    private String expansionReason;

    /**
     * V47 (Flyway V38): true once a person has saved this wrapper through the wrapper endpoint.
     * NOT NULL with a FALSE default, so an enrolment seed and every pre-V38 row read as "not
     * edited" and a drift refresh may re-seed them — see the migration for why that backfill is
     * the right trade pre-release.
     */
    @Column(name = "wrapper_edited", nullable = false)
    private boolean wrapperEdited;
}

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
 * JPA entity for a model-owned denial/validation constraint — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.ModelConstraint}.
 */
@Entity
@Table(name = "model_constraint")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ModelConstraintEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID modelId;

    @Column(name = "class_iri", nullable = false, updatable = false, columnDefinition = "text")
    private String classIri;

    @Column(nullable = false, columnDefinition = "text")
    private String expression;

    @Column(nullable = false)
    private boolean enforced;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** {@code "MANUAL"} or {@code "DISCOVERED"} (Phase 3B, V32) — see {@code ModelConstraint}. */
    @Column(nullable = false, length = 16)
    private String origin;
}

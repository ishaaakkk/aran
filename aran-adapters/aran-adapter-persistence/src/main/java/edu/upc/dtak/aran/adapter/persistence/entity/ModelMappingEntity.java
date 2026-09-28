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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for a durable alignment of an integration model — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.ModelMapping}.
 *
 * <p>{@code evidence} is JSONB, held as its JSON text; the repository owns the pass-through
 * (the core writes a JSON string already), exactly as {@code JobEntity} does for {@code result}.
 */
@Entity
@Table(name = "model_mapping",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_model_mapping_model_kind_src_tgt",
                columnNames = {"model_id", "kind", "source_iri", "target_iri"}))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ModelMappingEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID modelId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(name = "source_iri", nullable = false, updatable = false, columnDefinition = "text")
    private String sourceIri;

    @Column(name = "target_iri", nullable = false, updatable = false, columnDefinition = "text")
    private String targetIri;

    @Column
    private String label;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String evidence;

    @Column
    private UUID author;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

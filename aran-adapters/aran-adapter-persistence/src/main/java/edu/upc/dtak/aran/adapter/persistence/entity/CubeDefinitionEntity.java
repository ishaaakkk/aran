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
 * JPA entity for a cube — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.cube.CubeRecord} (V40 migration).
 *
 * <p>{@code definitionJson} and {@code productIdsJson} are opaque here on purpose: the domain
 * owns both directions of that JSON, so this entity stores strings and never reads them. They are
 * plain {@code text} rather than {@code jsonb} (unlike {@code DeclaredForeignKeyEntity}'s column
 * lists) because nothing queries inside them — a jsonb column would only buy operators no caller
 * uses, at the cost of Postgres re-parsing every write.
 *
 * <p>Tenant-filtered: the {@code tenantFilter} definition lives on {@code ProjectEntity}; this
 * entity carries the {@link Filter} referencing it as defence in depth behind the explicit
 * {@code tenant_id} predicates in {@code JpaRepositories.CubeDefinitionJpaRepository}.
 *
 * <p>Every column is {@code updatable = false}: v1 has no cube update path, and Hibernate must
 * never emit an UPDATE for a row this adapter only ever inserts.
 */
@Entity
@Table(name = "cube_definition",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_cube_definition_name",
                columnNames = {"tenant_id", "project_id", "name"}))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CubeDefinitionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(updatable = false)
    private String description;

    @Column(name = "fact_class_iri", nullable = false, updatable = false)
    private String factClassIri;

    /** Measures, dimensions and the calendar — everything the columns above do not carry. */
    @Column(name = "definition_json", nullable = false, updatable = false)
    private String definitionJson;

    /** The star's member product ids, fact first then dimensions in definition order. */
    @Column(name = "product_ids_json", nullable = false, updatable = false)
    private String productIdsJson;

    /** The ACTIVE graph version the definition's gates were checked against. */
    @Column(name = "schema_version_id", nullable = false, updatable = false)
    private UUID schemaVersionId;

    /** The creating user, as a string; null for an unattributed system-run creation. */
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

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
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * JPA entity for a project — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.Project}.
 *
 * <p><strong>This is a tenant-owned entity</strong>, so it carries the
 * shared-schema tenant discriminator and the Hibernate filter that enforces
 * isolation. The {@link FilterDef} is declared here (it only needs to be
 * declared once in the persistence unit) and the {@link Filter} applies the
 * {@code tenant_id = :tenantId} predicate to every query against this entity
 * once the filter is enabled on the session.
 *
 * <p>The filter is enabled transparently per request/invocation using the
 * {@code TenantFilterAspect} in this module, which automatically extracts the
 * active tenant context and enables Hibernate's {@code tenantFilter} on the current
 * session. That is what makes "every project query is tenant-scoped" an invariant
 * enforced by Hibernate, not by remembering to add a where-clause.
 *
 * <p>{@code (tenant_id, name)} is unique — project names are unique within
 * their tenant, not globally.
 */
@Entity
@Table(
        name = "project",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_project_tenant_name",
                columnNames = {"tenant_id", "name"}
        )
)
@FilterDef(
        name = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = UUID.class)
)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProjectEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private String color;

    @Column(nullable = false)
    private String privacy;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int progress;
}

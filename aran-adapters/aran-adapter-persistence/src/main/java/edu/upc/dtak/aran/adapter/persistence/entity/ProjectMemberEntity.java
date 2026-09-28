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

/**
 * JPA entity for a project-member visibility row — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.ProjectMember}.
 *
 * <p>This is the "Option A" explicit-visibility join: a user sees a project
 * only if a row here links them. {@code (project_id, user_id)} is unique.
 *
 * <p>Note this entity is <em>not</em> directly tenant-filtered — it has no
 * {@code tenant_id} column of its own. Tenant isolation reaches it
 * transitively: it is only ever queried by joining through {@link ProjectEntity},
 * which <em>is</em> filtered. Keeping the discriminator off the join table
 * avoids a denormalised column that could drift out of sync with its project.
 */
@Entity
@Table(
        name = "project_member",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_project_member_project_user",
                columnNames = {"project_id", "user_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProjectMemberEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;
}

package edu.upc.dtak.aran.adapter.persistence.entity;

import edu.upc.dtak.aran.core.domain.TenantRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * JPA entity for a membership — the user&harr;tenant association carrying the
 * user's {@link TenantRole}. Counterpart of
 * {@code edu.upc.dtak.aran.core.domain.Membership}.
 *
 * <p>The {@code (user_id, tenant_id)} pair is unique — a user has at most one
 * membership per tenant. The role is stored as a string ({@code EnumType.STRING})
 * so the enum can be reordered or extended without a data migration.
 */
@Entity
@Table(
        name = "membership",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_membership_user_tenant",
                columnNames = {"user_id", "tenant_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MembershipEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;
}

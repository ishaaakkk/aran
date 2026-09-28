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

/**
 * JPA entity for a user — the persistence-side counterpart of
 * {@code edu.upc.dtak.aran.core.domain.User}.
 *
 * <p>Deliberately a separate type from the core domain record: the core stays
 * free of JPA annotations, and the adapter's mapper translates between the
 * two. {@code User} is <em>not</em> tenant-owned, so this entity carries no
 * {@code tenant_id} column and no tenant filter.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** The Keycloak subject (JWT {@code sub}). Unique, the join key to the IdP. */
    @Column(nullable = false, unique = true, updatable = false)
    private String subject;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Last authenticated request; null until the user has actually signed in once.
     *
     * <p>{@code updatable = false} is load-bearing. The domain {@code User} record has no such
     * field — it is not a profile attribute — so every profile re-sync would otherwise persist a
     * null over it and reset the column the admin screen reads. It is written only by
     * {@code UserJpaRepository.touchLastSeen}.
     */
    @Column(name = "last_seen_at", updatable = false)
    private Instant lastSeenAt;

    /** The mapper's constructor: a domain {@code User} knows nothing about last-seen. */
    public UserEntity(UUID id, String subject, String email, String displayName,
                      Instant createdAt) {
        this(id, subject, email, displayName, createdAt, null);
    }
}

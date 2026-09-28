package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A user — a <em>local projection</em> of the identity that Keycloak owns.
 *
 * <p>Keycloak remains the source of truth for authentication and credentials.
 * Aran keeps this lightweight local record so it can own the
 * application-domain relationships (memberships, project membership, the
 * {@code createdBy} of a project) without calling Keycloak for every
 * association.
 *
 * <p>The record is created/refreshed by a just-in-time provisioning step on
 * login: the first time a valid token arrives for an unknown {@code subject},
 * a {@code User} row is inserted; on subsequent logins the mutable profile
 * fields are re-synced from the token claims.
 *
 * @param id         Aran-local stable identifier
 * @param subject    the Keycloak user id — the JWT {@code sub} claim; the
 *                   join key back to the identity provider, unique platform-wide
 * @param email      email address, synced from the token
 * @param displayName human-readable name, synced from the token
 * @param createdAt  when this user was first seen by Aran
 */
@NullMarked
public record User(
        UUID id,
        String subject,
        String email,
        String displayName,
        Instant createdAt
) {
    public User {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("user subject (Keycloak sub) must not be blank");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("user email must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("user displayName must not be blank");
        }
    }
}

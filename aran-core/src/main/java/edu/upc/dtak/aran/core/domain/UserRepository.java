package edu.upc.dtak.aran.core.domain;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence <em>port</em> for {@link User}.
 *
 * <p>Hexagonal seam: {@code aran-core} declares what it needs, and
 * {@code aran-adapter-persistence} supplies the JPA/Hibernate implementation.
 *
 * <p>Unlike tenant-owned aggregates, {@code User} is <strong>not</strong>
 * tenant-scoped. A user exists independently of any tenant and may belong to
 * several. In particular {@link #findBySubject} runs during just-in-time
 * provisioning — on the very first request from a new identity, before any
 * tenant has been resolved — so it cannot take a tenant argument.
 */
@NullMarked
public interface UserRepository {

    /**
     * Find a user by their Aran-local id.
     */
    Optional<User> findById(UUID userId);

    /**
     * Find a user by their Keycloak subject (the JWT {@code sub} claim).
     *
     * <p>This is the lookup used by just-in-time provisioning to decide
     * whether an incoming identity is already known.
     */
    Optional<User> findBySubject(String subject);

    /**
     * Persist (insert or update) a user. Used both for first-login
     * provisioning and for re-syncing profile fields from later tokens.
     */
    User save(User user);

    /**
     * Stamp the user's last authenticated request.
     *
     * <p>Not part of {@link User} because it is not a profile field: it changes on essentially
     * every request, and putting it on the record would make every profile re-sync a write of
     * something that is not the profile. Callers throttle — see {@code IdentityService}.
     */
    void touchLastSeen(UUID userId, java.time.Instant at);

    /**
     * Last-seen stamps for a set of users, for the admin member list.
     *
     * <p>Bulk rather than per-user because the alternative is one query per member on a page
     * that renders every member.
     */
    java.util.Map<UUID, java.time.Instant> lastSeenFor(java.util.Collection<UUID> userIds);
}

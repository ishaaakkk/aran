package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.UserJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.mapper.EntityMapper;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link UserRepository} port.
 *
 * <p>A thin adapter: delegate to Spring Data, map entity &harr; domain. No
 * tenant filtering — {@code User} is not a tenant-owned aggregate.
 */
@Component
@NullMarked
class JpaUserRepository implements UserRepository {

    private final UserJpaRepository jpa;

    JpaUserRepository(UserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<User> findById(UUID userId) {
        return jpa.findById(userId).map(EntityMapper::toDomain);
    }

    @Override
    public Optional<User> findBySubject(String subject) {
        return jpa.findBySubject(subject).map(EntityMapper::toDomain);
    }

    @Override
    public User save(User user) {
        return EntityMapper.toDomain(jpa.save(EntityMapper.toEntity(user)));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void touchLastSeen(java.util.UUID userId, java.time.Instant at) {
        jpa.touchLastSeen(userId, at);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.Map<java.util.UUID, java.time.Instant> lastSeenFor(
            java.util.Collection<java.util.UUID> userIds) {
        if (userIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<java.util.UUID, java.time.Instant> out = new java.util.HashMap<>();
        for (Object[] row : jpa.lastSeenFor(userIds)) {
            // Never seen is a real answer, and a null value in the map would be
            // indistinguishable from an absent key for the caller.
            if (row[1] != null) {
                out.put((java.util.UUID) row[0], (java.time.Instant) row[1]);
            }
        }
        return out;
    }
}

package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryUserRepository implements UserRepository {

    private final Map<UUID, User> store = new HashMap<>();

    @Override
    public Optional<User> findById(UUID userId) {
        return Optional.ofNullable(store.get(userId));
    }

    @Override
    public Optional<User> findBySubject(String subject) {
        return store.values().stream()
                .filter(u -> u.subject().equals(subject))
                .findFirst();
    }

    @Override
    public User save(User user) {
        store.put(user.id(), user);
        return user;
    }

    /** Last-seen stamps, so a test can assert the throttle actually wrote one. */
    public final java.util.Map<java.util.UUID, java.time.Instant> lastSeen =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void touchLastSeen(java.util.UUID userId, java.time.Instant at) {
        lastSeen.put(userId, at);
    }

    @Override
    public java.util.Map<java.util.UUID, java.time.Instant> lastSeenFor(
            java.util.Collection<java.util.UUID> userIds) {
        java.util.Map<java.util.UUID, java.time.Instant> out = new java.util.HashMap<>();
        userIds.forEach(id -> {
            java.time.Instant at = lastSeen.get(id);
            if (at != null) {
                out.put(id, at);
            }
        });
        return out;
    }
}

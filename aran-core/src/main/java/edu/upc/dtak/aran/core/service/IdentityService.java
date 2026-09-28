package edu.upc.dtak.aran.core.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity service — JIT user provisioning and tenant-membership
 * resolution. Replaces (and was previously called) {@code UserProvisioningService};
 * the name changed once it grew responsibilities beyond pure provisioning.
 *
 * <p>Two responsibilities, both deliberately here in the core service layer
 * rather than in a controller:
 * <ol>
 *   <li><b>Provisioning</b> — Keycloak owns authentication; Aran keeps a
 *       local {@link User} projection. On the first authenticated request
 *       from an identity Aran has never seen, a {@code User} row is inserted;
 *       on later requests it re-syncs the mutable profile fields from the
 *       token. Neither path needs a tenant context — provisioning happens
 *       before any tenant decision.</li>
 *   <li><b>Tenant resolution</b> — answering "which tenants can this user act
 *       within, and with what role and display name?". Returns rich
 *       {@link TenantMembershipView}s that already include the tenant's
 *       slug/name, so the web layer does not need to fan out to multiple
 *       repositories.</li>
 * </ol>
 *
 * <p>The {@link edu.upc.dtak.aran.app.web.MeController} should depend only on this
 * service, not on {@link UserRepository} or {@link TenantRepository}
 * directly — controllers should not own repository references.
 */
@Service
@NullMarked
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    /**
     * Short-TTL cache for the per-request "resolve user from claims" path.
     *
     * <p>Without this, every authenticated API request opens a write
     * transaction against {@code app_user} (the {@code @Transactional}
     * provisioning method runs even when no row actually changes). At any
     * meaningful throughput that's a serious bottleneck and creates
     * contention on a single hot table.
     *
     * <p>The cache key is {@code subject} plus a hash of the mutable
     * profile claims — so a profile change in Keycloak invalidates the entry
     * and triggers a re-sync, while stable repeat requests bypass the DB
     * entirely. 60-second TTL keeps drift to a known bounded window.
     *
     * <p>Sized for tens of thousands of concurrent users without blowing
     * heap — entries are small (a {@code User} record + the cache key).
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX_SIZE = 100_000;

    private final UserRepository users;
    private final TenantRepository tenants;
    private final IdentityService self;
    private final Cache<String, User> userCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(CACHE_MAX_SIZE)
            .build();

    /**
     * Throttles the last-seen write to at most one per {@link #CACHE_TTL} per user.
     *
     * <p>Without it, "when was this person last active" costs a database write on every
     * authenticated request — a permanent tax on the whole API for a column read on one admin
     * screen. A minute of staleness is invisible in a UI that shows "2 hours ago".
     *
     * <p>Deliberately not the same cache as {@code userCache}: that one is keyed on the profile
     * claims and is invalidated when a display name changes, which has nothing to do with how
     * recently anyone was seen.
     */
    private final Cache<UUID, Boolean> lastSeenThrottle = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(CACHE_MAX_SIZE)
            .build();

    public IdentityService(UserRepository users, TenantRepository tenants, @Lazy IdentityService self) {
        this.users = users;
        this.tenants = tenants;
        this.self = self;
    }

    /**
     * Resolve the local {@link User} for a set of token claims, creating it on
     * first sight and otherwise refreshing its profile fields.
     *
     * <p>Called once per request, right after the JWT is validated and before
     * any tenant-scoped work. Caches the result for {@value #CACHE_TTL}
     * seconds keyed on the subject and the mutable claim values — so cache
     * hits are fully read-free, and any drift in email/displayName forces
     * a fresh resolution and re-sync.
     *
     * @param subject     the Keycloak subject — JWT {@code sub}
     * @param email       email claim
     * @param displayName display-name claim
     * @return the persisted local user
     */
    public User resolveFromClaims(String subject, String email, String displayName) {
        String key = cacheKey(subject, email, displayName);
        User cached = userCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        User resolved = self.doResolveFromClaims(subject, email, displayName);
        userCache.put(key, resolved);
        markSeen(resolved.id());
        return resolved;
    }

    /**
     * Stamp last-seen, at most once per minute per user.
     *
     * <p>Failures are swallowed: this is a column on an admin screen, and taking a request down
     * because it could not be written would be a self-inflicted outage in service of a
     * timestamp.
     */
    public void markSeen(UUID userId) {
        if (lastSeenThrottle.getIfPresent(userId) != null) {
            return;
        }
        lastSeenThrottle.put(userId, Boolean.TRUE);
        try {
            users.touchLastSeen(userId, Instant.now());
        } catch (Exception e) {
            log.debug("could not stamp last-seen for user {}", userId, e);
        }
    }

    /**
     * Public, not protected, and that is load-bearing.
     *
     * <p>{@code AnnotationTransactionAttributeSource} defaults to {@code publicMethodsOnly},
     * so {@code @Transactional} on a protected method is silently inert. With
     * {@code open-in-view: false} the find-or-create below then ran as two independent
     * auto-commit units, and two concurrent first-sight requests for the same {@code sub}
     * could both insert. The {@code @Lazy IdentityService self} injection exists solely so the
     * caller reaches this through the proxy; while the method was protected that whole
     * mechanism did nothing.
     */
    @Transactional
    public User doResolveFromClaims(String subject, String email, String displayName) {
        return users.findBySubject(subject)
                .map(existing -> syncIfChanged(existing, email, displayName))
                .orElseGet(() -> users.save(new User(
                        UUID.randomUUID(),
                        subject,
                        email,
                        displayName,
                        Instant.now()
                )));
    }

    private static String cacheKey(String subject, String email, String displayName) {
        // Combining the mutable claims into the key means a drift in either
        // invalidates the entry — the next call falls through to syncIfChanged.
        return subject + '|' + email + '|' + displayName;
    }

    private User syncIfChanged(User existing, String email, String displayName) {
        if (existing.email().equals(email) && existing.displayName().equals(displayName)) {
            return existing;
        }
        // Profile drifted in Keycloak since last login — re-sync the projection.
        return users.save(new User(
                existing.id(),
                existing.subject(),
                email,
                displayName,
                existing.createdAt()
        ));
    }

    /**
     * The local user row with this id — for a caller whose identity is already resolved (an API
     * token names its user), with no profile claims to provision from.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<User> user(UUID userId) {
        return users.findById(userId);
    }

    /**
     * The memberships this user holds across all tenants — the set of tenants
     * they may act within, each enriched with the tenant's display name/slug
     * and the role they hold there.
     *
     * <p>Returns an empty list (not an error) if the user has no memberships.
     */
    @Transactional(readOnly = true)
    public List<TenantMembershipView> membershipsOf(UUID userId) {
        return tenants.findMembershipsForUser(userId).stream()
                .map(this::enrich)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    /**
     * The one membership this user holds, or empty if they hold none.
     *
     * <p>This is how a request's tenant is determined. It used to come from the JWT's
     * {@code tenant_id} claim — a Keycloak user attribute — which meant a user's tenant was
     * recorded in two places that nothing kept in agreement, and that adding a colleague to a
     * tenant required editing their Keycloak profile. Aran's own membership row is now the single
     * answer, and Keycloak is asked only who the caller is.
     *
     * <p>Unlike {@link #membershipsOf} this does not enrich with the tenant's name and slug: the
     * authentication path needs the tenant id and the role, and nothing else, on every request.
     *
     * @throws AccessDeniedException if the user somehow holds more than one membership. V23's
     *         {@code uq_membership_user} makes that unreachable through the database; if it
     *         happens anyway the constraint has been dropped, and guessing which tenant the
     *         caller meant is the one thing this must never do.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Membership> soleMembership(UUID userId) {
        List<Membership> held = tenants.findMembershipsForUser(userId);
        if (held.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (held.size() > 1) {
            log.error("user {} holds {} memberships; Aran allows one (see V23). Refusing to guess.",
                    userId, held.size());
            throw new AccessDeniedException(
                    "this account belongs to more than one tenant, which is not supported");
        }
        return java.util.Optional.of(held.getFirst());
    }

    private java.util.Optional<TenantMembershipView> enrich(Membership m) {
        return tenants.findById(m.tenantId())
                .map(t -> new TenantMembershipView(
                        t.id(), t.slug(), t.name(), m.role(), m.joinedAt()));
    }

    /**
     * A tenant the user belongs to, flattened with the user's role in it and
     * the tenant's display fields. The natural shape for the web layer's
     * "who am I" response.
     *
     * <p>Returned by {@link #membershipsOf}. Defined here, in the core, rather
     * than as a web DTO because it has no transport concerns and the {@code Me}
     * response is just a thin wrapper around a list of these.
     */
    public record TenantMembershipView(
            UUID tenantId,
            String slug,
            String name,
            TenantRole role,
            Instant joinedAt
    ) {
    }
}

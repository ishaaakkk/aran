package edu.upc.dtak.aran.core.licensing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicenseFeature;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The licensing enforcement layer — the single chokepoint every gated action
 * passes through. Deployment-mode agnostic: it pulls entitlements from
 * {@link LicenseProvider} (DB or signed token) and composes them with the
 * current usage counts from {@link UsageCounter}.
 *
 * <p>Three primary gates, designed to be called from inside service methods
 * just before they perform a mutation:
 * <ul>
 *   <li>{@link #requireMutationsAllowed} — read-only mode check; the cheapest
 *       gate, applied broadly.</li>
 *   <li>{@link #requireWithinUserLimit} — quota check on member additions.</li>
 *   <li>{@link #requireWithinProjectLimit} — quota check on project creation.</li>
 *   <li>{@link #requireFeature} — boolean feature flag check.</li>
 * </ul>
 *
 * <p>Entitlements are cached briefly (60s). Usage counts are NOT cached: they
 * change second-to-second and a stale "9 of 10 users" check would let the
 * limit be silently breached. The DB COUNT(*) here is one cheap indexed
 * query per gated mutation — acceptable, and bounded by the gate set above.
 */
@Service
@NullMarked
public class LicenseService {

    /** The rolling window the egress ceiling is measured over. */
    private static final java.time.Duration EGRESS_WINDOW = java.time.Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    private static final Duration ENTITLEMENTS_TTL = Duration.ofSeconds(60);

    private final LicenseProvider provider;
    private final UsageCounter usage;
    private final Cache<UUID, LicenseEntitlements> entitlementsCache = Caffeine.newBuilder()
            .expireAfterWrite(ENTITLEMENTS_TTL)
            .maximumSize(10_000)
            .build();

    public LicenseService(LicenseProvider provider, UsageCounter usage) {
        this.provider = provider;
        this.usage = usage;
    }

    /**
     * Resolve the entitlements applying to the caller's tenant right now.
     * Throws if there is no license — callers must handle, or wrap, that case.
     */
    public LicenseEntitlements entitlementsFor(UUID tenantId) {
        LicenseEntitlements cached = entitlementsCache.getIfPresent(tenantId);
        if (cached != null) {
            return cached;
        }
        LicenseEntitlements fresh = provider.findFor(tenantId)
                .orElseThrow(() -> new LicenseRequiredException(
                        "no license on file for tenant " + tenantId));
        entitlementsCache.put(tenantId, fresh);
        return fresh;
    }

    /**
     * Read-only-mode gate. Every mutating service path calls this first.
     */
    public void requireMutationsAllowed(AuthenticatedUser caller) {
        LicenseEntitlements e = entitlementsFor(caller.tenantId());
        if (!e.allowsMutations()) {
            throw new LicenseExpiredException(
                    "tenant license is " + e.status() + (e.expiresAt() != null
                            ? " (expired at " + e.expiresAt() + ")"
                            : "") + "; mutations not allowed");
        }
    }

    /** Quota gate for member additions. Caller has already passed {@code requireMutationsAllowed}. */
    public void requireWithinUserLimit(AuthenticatedUser caller) {
        requireWithinUserLimit(caller.tenantId());
    }

    /**
     * Tenant-scoped overload for admin / system paths that don't act under
     * an {@link AuthenticatedUser} (e.g. the platform-admin tenant creator).
     */
    public void requireWithinUserLimit(UUID tenantId) {
        LicenseEntitlements e = entitlementsFor(tenantId);
        long current = usage.countUsers(tenantId);
        if (current >= e.maxUsers()) {
            throw new LicenseLimitExceededException("maxUsers", current, e.maxUsers());
        }
    }

    /** Quota gate for project creation. */
    public void requireWithinProjectLimit(AuthenticatedUser caller) {
        LicenseEntitlements e = entitlementsFor(caller.tenantId());
        long current = usage.countProjects(caller.tenantId());
        if (current >= e.maxProjects()) {
            throw new LicenseLimitExceededException("maxProjects", current, e.maxProjects());
        }
    }

    /**
     * Storage ceiling.
     *
     * <p>A circuit breaker, not a meter. On a local install this is what stops a runaway
     * materialization from filling the boot disk; in cloud the same field becomes a plan limit.
     * Unset by default, which is the OSS default and has to be, because a fresh install must not
     * refuse its first upload.
     *
     * <p>Checked <em>before</em> the bytes are accepted, and against the incoming size, so the
     * limit is a limit rather than a thing you discover having already crossed.
     *
     * @param incomingBytes what this operation is about to add; 0 to ask only whether the tenant
     *                      is already over.
     */
    public void requireWithinStorageLimit(UUID tenantId, long incomingBytes) {
        LicenseEntitlements e = entitlementsFor(tenantId);
        Long ceiling = e.maxStorageBytes();
        if (ceiling == null) {
            return;
        }
        long projected = usage.estimatedStorageBytes(tenantId) + incomingBytes;
        if (projected > ceiling) {
            throw new LicenseLimitExceededException("maxStorageBytes", projected, ceiling);
        }
    }

    public void requireWithinStorageLimit(AuthenticatedUser caller, long incomingBytes) {
        requireWithinStorageLimit(caller.tenantId(), incomingBytes);
    }

    /**
     * Egress ceiling over a rolling 30 days.
     *
     * <p>Rolling rather than calendar-month: a monthly reset gives a tenant a cliff to game and
     * gives an operator a day of the month when everything breaks at once. Rolling degrades
     * gradually and recovers gradually, which is what a circuit breaker should do.
     *
     * <p>Checked before the export runs but measured after previous ones completed, so the
     * request that crosses the line is allowed to finish. Refusing mid-stream would leave a
     * client with a truncated file and no way to tell it apart from a complete one.
     */
    public void requireWithinEgressLimit(AuthenticatedUser caller) {
        requireWithinEgressLimit(caller.tenantId());
    }

    public void requireWithinEgressLimit(UUID tenantId) {
        LicenseEntitlements e = entitlementsFor(tenantId);
        Long ceiling = e.maxMonthlyEgressBytes();
        if (ceiling == null) {
            return;
        }
        long used = usage.egressBytesSince(tenantId, Instant.now().minus(EGRESS_WINDOW));
        if (used > ceiling) {
            throw new LicenseLimitExceededException("maxMonthlyEgressBytes", used, ceiling);
        }
    }

    /**
     * Feature-flag gate. Use sparingly — most surface should be open to all
     * tenants, with paywalled features the exception, not the norm.
     */
    public void requireFeature(AuthenticatedUser caller, LicenseFeature feature) {
        LicenseEntitlements e = entitlementsFor(caller.tenantId());
        if (!e.has(feature)) {
            throw new LicenseLimitExceededException(
                    "feature." + feature.name().toLowerCase(), 0, 0);
        }
    }

    /**
     * Invalidate the cached entitlements for a tenant. Called by the admin
     * endpoints after a license change so the new entitlements take effect
     * immediately instead of after the TTL.
     */
    public void invalidate(UUID tenantId) {
        entitlementsCache.invalidate(tenantId);
        log.debug("invalidated cached entitlements for tenant {}", tenantId);
    }
}

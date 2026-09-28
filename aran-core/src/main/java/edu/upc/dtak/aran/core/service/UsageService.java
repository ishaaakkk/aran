package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.UsageEvent;
import edu.upc.dtak.aran.core.domain.UsageEventRepository;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Records what a tenant consumed, attributed to the person who asked for it.
 *
 * <h2>Recording never fails the thing it measures</h2>
 *
 * <p>Every method swallows its own failures. A metering write that took a query down with it
 * would be a self-inflicted outage in service of a dashboard, and the dashboard being slightly
 * wrong is strictly better than the query not running. This is the one place in the codebase
 * where catching {@code Exception} and moving on is the correct behaviour rather than a smell,
 * which is why it is said out loud here.
 *
 * <p>{@code REQUIRES_NEW} for the same reason: a usage row must not be rolled back by a
 * transaction that fails afterwards. The work still happened, and a tenant whose failed query
 * cost real compute should still show it.
 */
@Service
@NullMarked
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final UsageEventRepository events;

    public UsageService(UsageEventRepository events) {
        this.events = events;
    }

    /**
     * Record a query-shaped action performed by a known caller.
     *
     * <p>Carries {@code REQUIRES_NEW} itself rather than relying on the overload it delegates
     * to. That delegation is a plain {@code this} call, so it never crosses the proxy and the
     * annotation on the target is not applied — the usage row silently joined the caller's
     * transaction instead of getting its own. {@code DatasetService} calls this from inside a
     * class-level {@code @Transactional}, so a later failure there rolled the usage row back,
     * which is exactly what this class exists to prevent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthenticatedUser actor, UsageKind kind, @Nullable Long durationMs,
                       @Nullable Long rowCount, @Nullable Long byteCount) {
        record(actor.tenantId(), actor.userId(), kind, durationMs, rowCount, byteCount);
    }

    /**
     * @param userId null for work no human asked for. Do not substitute a service account:
     *               "nobody" and "a robot acting for nobody" are the same thing, and inventing
     *               an actor corrupts exactly the attribution this exists to provide.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, @Nullable UUID userId, UsageKind kind,
                       @Nullable Long durationMs, @Nullable Long rowCount,
                       @Nullable Long byteCount) {
        try {
            events.save(new UsageEvent(UUID.randomUUID(), tenantId, userId, kind, Instant.now(),
                    durationMs, rowCount, byteCount, Map.of()));
        } catch (Exception e) {
            log.warn("could not record {} usage for tenant {} — the work itself was unaffected",
                    kind, tenantId, e);
        }
    }
}

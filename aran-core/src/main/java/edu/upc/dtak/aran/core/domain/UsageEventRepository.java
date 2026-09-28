package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence port for {@link UsageEvent}. */
@NullMarked
public interface UsageEventRepository {

    UsageEvent save(UsageEvent event);

    /**
     * Consumption per user since {@code since}, for the admin dashboard.
     *
     * <p>Aggregated in the database rather than by loading events: a busy tenant produces far
     * more rows than a dashboard should ever pull into memory, and this is the query that would
     * be the first thing to go wrong at scale.
     */
    List<UsagePerUser> usagePerUser(UUID tenantId, Instant since);

    /** Totals for the tenant since {@code since}, including work with no attributable user. */
    UsageTotals totals(UUID tenantId, Instant since);

    /**
     * @param userId    null groups everything the tenant did without an attributable human.
     * @param bytesIn   bytes this person sent to Aran (uploads).
     * @param bytesOut  bytes Aran sent back to them (exports, JDBC results) — the egress a cloud
     *                  bill charges for, and the number worth looking at per person.
     */
    record UsagePerUser(
            @org.jspecify.annotations.Nullable UUID userId,
            long events,
            long rows,
            long bytesIn,
            long bytesOut,
            @org.jspecify.annotations.Nullable Long p95DurationMs,
            @org.jspecify.annotations.Nullable Instant lastActivityAt
    ) {}

    /**
     * @param bytesStored bytes Aran wrote to the lake on the tenant's behalf. Not movement — this
     *                    is the flow that accumulates into the storage bill, which is why it is
     *                    counted apart from the two that actually cross the network boundary.
     */
    record UsageTotals(long events, long rows, long bytesIn, long bytesOut, long bytesStored) {}
}

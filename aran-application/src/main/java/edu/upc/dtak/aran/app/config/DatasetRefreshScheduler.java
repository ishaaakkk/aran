package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.service.DatasetRefreshService;
import java.time.Instant;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticks {@link DatasetRefreshService#runDue} so {@code RefreshPolicy.Every} means something.
 *
 * <p>The only scheduled task in the application, and deliberately the whole of the scheduling: all
 * the decisions — which rows are due, who a refresh runs as, what happens when one fails — are in
 * the service, where they can be tested without waiting for a clock. This class is a timer.
 *
 * <h2>Why a poll rather than a timer per policy</h2>
 *
 * <p>Because the state lives in the database and Aran can run more than one instance. Per-policy
 * timers would live in one JVM's heap, be lost on restart, and fire N times across N instances;
 * {@code claimDue} makes the database the single arbiter, so any number of instances can poll and
 * each due refresh is started exactly once.
 *
 * <h2>Why fixed delay rather than fixed rate</h2>
 *
 * <p>{@code fixedRate} would queue another tick while one is still running, which on a slow
 * database means the ticks pile up. The interval here is not a freshness guarantee — the policy's
 * interval is — it is only how often Aran looks, so drifting by the duration of a poll costs
 * nothing.
 */
@Component
@NullMarked
@EnableScheduling
@ConditionalOnProperty(name = "aran.refresh.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class DatasetRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(DatasetRefreshScheduler.class);

    private final DatasetRefreshService refreshes;

    public DatasetRefreshScheduler(DatasetRefreshService refreshes) {
        this.refreshes = refreshes;
    }

    /**
     * Look for due refreshes.
     *
     * <p>{@code initialDelay} keeps this out of startup: a restart should not begin extracting
     * tables while the lake, freyja and Fuseki are still coming up, and everything overdue stays
     * overdue for another minute without harm.
     *
     * <p>Nothing is rethrown. An exception escaping a {@code @Scheduled} method cancels nothing in
     * Spring's default scheduler, but it does log a stack trace per tick, and the useful reason has
     * already been recorded on the refresh row by the service.
     */
    @Scheduled(initialDelayString = "${aran.refresh.scheduler.initial-delay:60s}",
               fixedDelayString = "${aran.refresh.scheduler.poll-interval:60s}")
    public void pollForDueRefreshes() {
        try {
            refreshes.runDue(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Dataset refresh poll failed; the next tick will retry", e);
        }
    }
}

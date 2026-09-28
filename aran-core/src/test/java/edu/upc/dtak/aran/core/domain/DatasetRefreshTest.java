package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.source.RefreshPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariants that keep a schedule from silently doing nothing.
 *
 * <p>Two of them, and both are failures that look like success from the outside: a scheduled
 * refresh with no {@code nextRunAt} is never picked up, and a manual one with a {@code nextRunAt}
 * fires on a schedule nobody asked for. Neither throws at the moment it is created if the record
 * permits it, so the record does not permit it.
 */
class DatasetRefreshTest {

    private static final UUID DATASET = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-15T10:00:00Z");

    private static DatasetRefresh manual() {
        return DatasetRefresh.manual(DATASET, TENANT, REPOSITORY, PROJECT, CREATOR, "orders");
    }

    @Test
    @DisplayName("a scheduled refresh with no next run is rejected, not quietly inert")
    void scheduled_without_next_run_is_rejected() {
        assertThatThrownBy(() -> new DatasetRefresh(
                DATASET, TENANT, REPOSITORY, PROJECT, CREATOR, "orders",
                new RefreshPolicy.Every(Duration.ofHours(1)), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never pick it up");
    }

    @Test
    @DisplayName("a manual refresh with a next run is rejected, not quietly scheduled")
    void manual_with_next_run_is_rejected() {
        assertThatThrownBy(() -> new DatasetRefresh(
                DATASET, TENANT, REPOSITORY, PROJECT, CREATOR, "orders",
                new RefreshPolicy.Manual(), null, NOW.plusSeconds(60), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("switching to a schedule anchors the first run to now, not to the last refresh")
    void schedule_is_anchored_to_now() {
        // A dataset last refreshed a week ago, set to hourly. Anchoring to lastRefreshAt would make
        // it due immediately — turning a settings change into a refresh the user did not request.
        DatasetRefresh stale = manual().succeeded(NOW.minus(Duration.ofDays(7)));

        DatasetRefresh scheduled = stale.withPolicy(new RefreshPolicy.Every(Duration.ofHours(1)), NOW);

        assertThat(scheduled.nextRunAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(scheduled.lastRefreshAt()).isEqualTo(stale.lastRefreshAt());
    }

    @Test
    @DisplayName("switching back to manual clears the next run, so the scheduler stops seeing it")
    void manual_clears_the_schedule() {
        DatasetRefresh scheduled = manual().withPolicy(new RefreshPolicy.Every(Duration.ofHours(1)), NOW);

        assertThat(scheduled.withPolicy(new RefreshPolicy.Manual(), NOW).nextRunAt()).isNull();
    }

    @Test
    @DisplayName("claiming advances the next run before the work runs, so a long refresh is skipped not doubled")
    void claim_advances_before_running() {
        DatasetRefresh due = manual().withPolicy(new RefreshPolicy.Every(Duration.ofMinutes(30)), NOW);

        DatasetRefresh claimed = due.claimed(NOW.plus(Duration.ofMinutes(30)));

        assertThat(claimed.nextRunAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        // The claim is not an outcome: nothing has run yet.
        assertThat(claimed.lastRefreshAt()).isNull();
    }

    @Test
    @DisplayName("claiming a manual refresh changes nothing — it was never due")
    void claiming_manual_is_a_no_op() {
        assertThat(manual().claimed(NOW)).isEqualTo(manual());
    }

    @Test
    @DisplayName("a failure keeps lastRefreshAt pointing at the copy still being served")
    void failure_does_not_move_the_freshness_clock() {
        // The snapshot from the last SUCCESSFUL refresh is what queries read. Moving the clock on
        // failure would report data as fresher than it is — the one lie a freshness field can tell.
        Instant landed = NOW.minus(Duration.ofHours(2));
        DatasetRefresh after = manual().succeeded(landed).failed("connection refused");

        assertThat(after.lastRefreshAt()).isEqualTo(landed);
        assertThat(after.lastError()).isEqualTo("connection refused");
        assertThat(after.stalenessAt(NOW)).contains(Duration.ofHours(2));
    }

    @Test
    @DisplayName("a success clears the previous failure")
    void success_clears_the_error() {
        DatasetRefresh recovered = manual().failed("connection refused").succeeded(NOW);

        assertThat(recovered.lastError()).isNull();
        assertThat(recovered.stalenessAt(NOW)).contains(Duration.ZERO);
    }

    @Test
    @DisplayName("staleness is unknown, not zero, before the first refresh")
    void never_refreshed_has_no_staleness() {
        // Zero would read as "just refreshed", which is the opposite of the truth.
        assertThat(manual().stalenessAt(NOW)).isEmpty();
    }

    @Test
    @DisplayName("an interval below the floor is refused, with a reason a user can act on")
    void interval_floor() {
        assertThatThrownBy(() -> new RefreshPolicy.Every(Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 minutes");
        assertThatThrownBy(() -> new RefreshPolicy.Every(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> new RefreshPolicy.Every(RefreshPolicy.Every.MINIMUM))
                .doesNotThrowAnyException();
    }
}

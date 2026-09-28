package edu.upc.dtak.aran.core.source;

import java.time.Duration;
import org.jspecify.annotations.NullMarked;

/**
 * How often a {@link AccessMode#MATERIALIZED} copy is remade.
 *
 * <p>The second axis of source access: {@link AccessMode} says whether Aran
 * copies the data or reads it in place, and this says how often the copy is refreshed. The two are
 * separate questions and were previously one confused one — {@code BindingStrategy} had both a
 * {@code LAKE_FILE} and a {@code MATERIALIZE_TO_LAKE} value that read identically at query time,
 * because what actually differed between them was freshness, not access.
 *
 * <p>Meaningful <em>only</em> under {@code MATERIALIZED}. A {@code FEDERATED} source has no copy to
 * remake, so it carries no policy at all rather than carrying a policy that means nothing — which
 * is why this type is not a field on every source, and why {@link Never} is represented by the
 * absence of a refresh record rather than a value in it.
 *
 * <h2>The continuum</h2>
 *
 * <p>Freshness and cost are one axis. {@code FEDERATED} sits at the good end: always fresh, zero
 * copy, pushdown applies. {@link Every} trades bounded staleness for predictable cost.
 * {@link Manual} is cheapest and stalest.
 *
 * <p>{@code OnQuery} — re-extract before every query — is deliberately <b>not</b> here yet. It
 * lands at the same freshness as {@code FEDERATED} for wildly more cost (copy the whole table,
 * then filter, versus push the predicate down), so it is only ever right for a source with no
 * pushdown available at all. Adding it before such a source exists would mean shipping a mode
 * whose only correct use case is hypothetical, and a join across two of them re-extracts both on
 * every query. It is in §3.1 of the design with the conditions it needs.
 */
@NullMarked
public sealed interface RefreshPolicy {

    /**
     * Refresh only when a human asks.
     *
     * <p>Today's behaviour for everything, and the default for a newly imported table: a schedule
     * nobody chose is a surprising thing to start running against someone's production database.
     */
    record Manual() implements RefreshPolicy {

        @Override
        public String describe() {
            return "on request";
        }
    }

    /**
     * Re-extract on a fixed interval.
     *
     * <p>The interval is a floor, not a guarantee: the scheduler polls, and a refresh that is
     * still running when the next one is due is skipped rather than queued. Overlapping extracts
     * of the same table would race for the same dataset's snapshot pointer, and the loser's work
     * would be discarded — expensively.
     */
    record Every(Duration interval) implements RefreshPolicy {

        /** The shortest interval accepted, so a typo cannot turn into a tight extract loop. */
        public static final Duration MINIMUM = Duration.ofMinutes(5);

        public Every {
            if (interval == null || interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("refresh interval must be positive");
            }
            if (interval.compareTo(MINIMUM) < 0) {
                throw new IllegalArgumentException(
                        "refresh interval must be at least " + MINIMUM.toMinutes()
                        + " minutes (asked for " + interval + "); an extract copies the whole "
                        + "table, so a short interval is a load generator, not a freshness setting");
            }
        }

        @Override
        public String describe() {
            return "every " + interval;
        }
    }

    /** How to say this policy to a user. */
    String describe();
}

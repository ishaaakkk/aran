package edu.upc.dtak.aran.operators.queryengine.plan;

import java.time.Duration;

/**
 * How much planning a query may buy before the optimizer is told to stop and answer with what it
 * has (D3, stage 1).
 *
 * <p>The primary budget is a <b>count of rule productions</b> — successful rewrites the Volcano
 * planner added to the memo — because that is a function of the catalog and the query alone.
 * PERF-P1 is planning that grows as {@code branches^joins} ({@code JoinUnionTransposeRule} ×
 * {@code JOIN_COMMUTE} × {@code JoinPushThroughJoinRule}): a six-hop chain over two-source classes
 * exhausts a 512 MB heap and the JVM does not recover, inside the same process that serves every
 * other request. A deterministic budget makes the memo bounded on every machine and keeps the
 * chosen plan shape identical across machines, which the plan-shape harness relies on. The
 * wall-clock guard is a <em>second</em> guard only: it exists so a pathological case still ends,
 * and when it is the one that trips the plan shape is machine-dependent — the report says which
 * guard fired precisely so that difference is visible. It must never become the primary budget
 * ("Do not" list of D3).
 *
 * @param productionBudget productions after which the planner is cancelled; {@code <= 0} disables
 *                         the count (the wall-clock guard still applies)
 * @param wallClockGuard   elapsed planning time after which the planner is cancelled regardless
 */
public record PlannerBudget(int productionBudget, Duration wallClockGuard) {

    /**
     * The shipped default. The number is chosen from measured per-rule production counts over
     * the plan corpus and the perf goldens (recorded in {@code build/perf/report.md}): every
     * corpus entry must plan without touching it, and a six-hop chain over two-source classes
     * must plan at the default heap with it — {@code PerfGateTest} asserts both.
     */
    public static final PlannerBudget DEFAULT = new PlannerBudget(50_000, Duration.ofSeconds(20));

    public PlannerBudget {
        if (wallClockGuard == null || wallClockGuard.isNegative() || wallClockGuard.isZero()) {
            throw new IllegalArgumentException("wallClockGuard must be positive: " + wallClockGuard);
        }
    }

    /** No limit at all — for tests that measure how far a shape goes, never for production. */
    public static PlannerBudget unbounded() {
        return new PlannerBudget(0, Duration.ofDays(1));
    }
}

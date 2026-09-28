package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.operators.queryengine.plan.PlannerBudget;
import java.time.Duration;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The planner's budget ({@link PlannerBudget}), as deployment configuration.
 *
 * <p>Shaped like {@link PlacementConfig} for the same reason: the number is declared here, its
 * justification lives with the tests that pin it ({@code PerfGateTest}: no corpus entry trips it,
 * a six-hop chain over two-source classes plans under it at the default heap), and
 * {@code build/perf/report.md} carries the per-rule counts that would justify moving it.
 */
@NullMarked
@Component
public class PlannerConfig {

    private final PlannerBudget budget;

    public PlannerConfig(
            // Rule productions, not seconds: a count is a function of the catalog and the query,
            // so the plan a query gets is the same on every machine. 0 disables the count.
            @Value("${aran.query.planner.production-budget:50000}") int productionBudget,
            // The second guard, for the pathological case the count did not foresee. When THIS
            // is the one that trips, the plan shape is machine-dependent — the report says so.
            @Value("${aran.query.planner.wall-clock-guard:20s}") Duration wallClockGuard) {
        this.budget = new PlannerBudget(productionBudget, wallClockGuard);
    }

    /** The shipped defaults — what the convenience constructors and tests wire. */
    public static PlannerConfig defaults() {
        return new PlannerConfig(PlannerBudget.DEFAULT.productionBudget(),
                PlannerBudget.DEFAULT.wallClockGuard());
    }

    public PlannerBudget budget() {
        return budget;
    }
}

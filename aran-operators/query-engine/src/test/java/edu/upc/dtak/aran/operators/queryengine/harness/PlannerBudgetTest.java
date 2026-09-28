package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import edu.upc.dtak.aran.operators.queryengine.plan.OqlPlanner;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanExecutor;
import edu.upc.dtak.aran.operators.queryengine.plan.PlannerBudget;
import edu.upc.dtak.aran.operators.queryengine.plan.Planning;
import java.time.Duration;
import org.apache.calcite.rel.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3 stage 1: the planner is bounded by a deterministic rule-production budget and answers with
 * its best plan so far when the budget trips — the structural fallback only when no implemented
 * plan exists yet. Both outcomes are reported, never silent.
 *
 * <p>PERF-P1 is the reason. Measured on the chain workload over two-source classes: 1 hop 104
 * productions, 2 hops 1,857, 3 hops 21,870, 4 hops 169,681 — roughly ×8 per hop, three quarters
 * of it {@code ProjectMergeRule} — and a six-hop chain exhausted a 512 MB heap and took the JVM
 * with it. The default budget of 50,000 lets every corpus entry plan untouched ({@code
 * PlanCorpusTest} and {@code PerfGoldenTest} assert that) and cuts the six-hop chain at 50,001.
 * The count, not the clock, is the primary budget so the plan a query gets is the same on every
 * machine.
 */
class PlannerBudgetTest {

    private static final long BLOW_UP_CEILING_MS = 10_000L;

    private static String chainQuery(int depth) {
        StringBuilder q = new StringBuilder("SELECT C0.key, C").append(depth).append(".grp FROM C0");
        for (int i = 1; i <= depth; i++) {
            q.append(" -[j").append(i).append("]-> C").append(i);
        }
        return q.toString();
    }

    private static RelNode lower(PerfCatalogs.Built b, String oql) {
        return new OqlPlanner(b.catalog(), b.enforced()).lower(OqlParser.parse(oql));
    }

    @Test
    @DisplayName("PERF-P1: a six-hop chain over two-source classes plans at the default budget, inside the ceiling")
    void sixHopChainPlansUnderTheDefaultBudget() throws Exception {
        var b = PerfCatalogs.chainPlanOnly(PerfCatalogs.Knobs.smoke().withSources(2), 6);
        long started = System.nanoTime();
        PlanExecutor.Optimized o = PlanExecutor.optimize(lower(b, chainQuery(6)), PlannerBudget.DEFAULT);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).as("the case that used to OOM the JVM").isLessThan(BLOW_UP_CEILING_MS);
        assertThat(o.planning().tripped()).isEqualTo(Planning.Tripped.PRODUCTION_BUDGET);
        assertThat(o.planning().productions()).isEqualTo(PlannerBudget.DEFAULT.productionBudget() + 1);
        assertThat(o.planning().degradation()).isEqualTo(Planning.Degradation.BEST_SO_FAR);
        assertThat(PlanExecutor.toSqlFromOptimized(o.plan())).startsWith("SELECT");
    }

    @Test
    @DisplayName("a tripped budget answers with the best plan so far, and says so")
    void budgetYieldsBestSoFar() throws Exception {
        var b = PerfCatalogs.chainPlanOnly(PerfCatalogs.Knobs.smoke().withSources(2), 3);
        PlanExecutor.Optimized o = PlanExecutor.optimize(lower(b, chainQuery(3)),
                new PlannerBudget(200, Duration.ofSeconds(20)));

        assertThat(o.planning().tripped()).isEqualTo(Planning.Tripped.PRODUCTION_BUDGET);
        assertThat(o.planning().productions()).isEqualTo(201);
        assertThat(o.planning().degradation()).isEqualTo(Planning.Degradation.BEST_SO_FAR);
        assertThat(o.planning().productionsByRule().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(201);
        assertThat(PlanExecutor.toSqlFromOptimized(o.plan())).startsWith("SELECT");
    }

    @Test
    @DisplayName("a budget cut before any implemented plan exists falls back to the structural plan")
    void structuralFallbackWhenNothingIsImplemented() throws Exception {
        var b = PerfCatalogs.chainPlanOnly(PerfCatalogs.Knobs.smoke().withSources(2), 2);
        PlanExecutor.Optimized o = PlanExecutor.optimize(lower(b, chainQuery(2)),
                new PlannerBudget(1, Duration.ofSeconds(20)));

        assertThat(o.planning().tripped()).isEqualTo(Planning.Tripped.PRODUCTION_BUDGET);
        assertThat(o.planning().degradation()).isEqualTo(Planning.Degradation.STRUCTURAL_FALLBACK);
        // Correct, uncosted: the query's own join order with predicates placed — and it unparses.
        assertThat(PlanExecutor.toSqlFromOptimized(o.plan())).startsWith("SELECT");
    }

    @Test
    @DisplayName("an unbounded budget reports NONE and the per-rule counts that decide the phase split")
    void unboundedReportsCounts() throws Exception {
        var b = PerfCatalogs.chainPlanOnly(PerfCatalogs.Knobs.smoke().withSources(2), 2);
        PlanExecutor.Optimized o = PlanExecutor.optimize(lower(b, chainQuery(2)), PlannerBudget.unbounded());

        assertThat(o.planning().degradation()).isEqualTo(Planning.Degradation.NONE);
        assertThat(o.planning().tripped()).isNull();
        assertThat(o.planning().productions()).isGreaterThan(1_000);
        assertThat(o.planning().productionsByRule()).containsKey("ProjectMergeRule");
    }
}

package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.RelOptListener;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.CancelFlag;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 12: optimize a logical {@link RelNode} from {@link OqlPlanner} and convert it to DuckDB SQL.
 *
 * <p>Optimization is a <b>single {@link VolcanoPlanner} pass</b> over the rule set in
 * {@link AranRules}: logical rewrites and physical conversion together, every rewrite explored as
 * a costed alternative rather than applied unconditionally. Cost comes from
 * {@link AranRelMdRowCount} over the per-source row counts and keys in {@link WrapperStatistic},
 * which is freyja profile data.
 *
 * <p>There is no heuristic pre-pass. A {@code HepPlanner} phase used to run first because the
 * logical rewrites could not survive in Volcano alongside the Enumerable rules — they were being
 * handed physical nodes and {@code JoinUnionTransposeRule} aborted planning outright. That turned
 * out to be a property of <em>which</em> rules the planner was carrying, not of Volcano: the
 * planner arrived from {@code RelBuilder.create} preloaded with Calcite's whole default
 * preparation set, and the node that seeded the bad match only existed because a rule in it
 * (merge join) asked for a collation nothing else wanted. Owning the rule set removes it.
 * {@link AranRules} documents the mechanism and what else it buys.
 *
 * <p>Losing the Hep phase is what makes the join-below-union rewrite a real decision. Under Hep it
 * fired whenever it matched, so it needed an arbitrary cap on how many branches it was allowed to
 * produce; in Volcano both shapes stay in the {@code RelSet} and the cheaper one is chosen.
 *
 * <p>The chosen physical plan is walked back to logical form by {@code toLogical} so
 * {@code RelToSqlConverter} can unparse it.
 */
public final class PlanExecutor {

    private PlanExecutor() {}

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    /** An optimized plan and the account of how it was reached. */
    public record Optimized(RelNode plan, Planning planning) {}

    /**
     * Optimize {@code rel} with the VolcanoPlanner CBO under the {@linkplain PlannerBudget#DEFAULT
     * default budget}, then return it in logical form — the plan alone. Callers that show a plan to
     * a person use {@link #optimize(RelNode, PlannerBudget)} so they can show the
     * {@link Planning} beside it.
     */
    public static RelNode optimize(RelNode rel) {
        return optimize(rel, PlannerBudget.DEFAULT).plan();
    }

    /**
     * Optimize {@code rel} with the VolcanoPlanner CBO, bounded by {@code budget}, then return it
     * in logical form together with what planning cost and whether it was cut short.
     *
     * <p>{@link AranRules#install} is what makes "the rule set" a meaningful phrase here: the
     * cluster's planner arrives from {@code RelBuilder.create} preloaded with Calcite's 113
     * default preparation rules, so it is emptied first.
     *
     * <p><b>The budget (D3, PERF-P1).</b> A {@link RelOptListener} counts successful rule
     * productions and, past the budget, raises Calcite's own {@link CancelFlag}. That flag is
     * checked by {@code VolcanoRuleCall.onMatch} before every rule fires, and when set the planner
     * throws {@code VolcanoTimeoutException} — which {@code IterativeRuleDriver} catches, logs,
     * canonizes the memo, and returns from, so {@code findBestExp} then builds the cheapest plan the
     * memo already holds: <em>best-so-far</em>, not nothing. That path is Calcite's, not ours; what
     * is ours is deciding when to raise the flag, and counting productions rather than seconds is
     * what keeps the answer the same on every machine. Only when the memo holds no implemented plan
     * at all ({@link RelOptPlanner.CannotPlanException}) does the query fall back to the
     * {@linkplain AranRules#STRUCTURAL structural Hep program}. Both outcomes are reported, never
     * silent: the plan a person is shown says whether it is the plan the optimizer would have
     * chosen.
     */
    public static Optimized optimize(RelNode rel, PlannerBudget budget) {
        VolcanoPlanner planner = (VolcanoPlanner) rel.getCluster().getPlanner();
        AranRules.install(planner);

        // The flag Volcano actually consults is the one in the Context the planner was built
        // with (OqlPlanner puts it there); setCancelFlag(...) is a documented no-op. A planner
        // built without one — a hand-rolled RelBuilder in a test — can be counted but not
        // stopped, and the report says so rather than pretending a budget applied.
        CancelFlag cancel = planner.getContext().unwrap(CancelFlag.class);
        if (cancel == null) {
            log.debug("Planner context carries no CancelFlag; the production budget cannot cancel");
        }
        ProductionCounter counter = new ProductionCounter(budget, cancel);
        planner.addListener(counter);

        RelTraitSet desiredTraits =
                rel.getCluster().traitSet().replace(EnumerableConvention.INSTANCE);
        planner.setRoot(planner.changeTraits(rel, desiredTraits));
        try {
            RelNode best = planner.findBestExp();
            Planning.Degradation degradation = counter.tripped() == null
                    ? Planning.Degradation.NONE : Planning.Degradation.BEST_SO_FAR;
            if (degradation != Planning.Degradation.NONE) {
                log.warn("Planning cut short by {} after {} productions ({} ms); answering with "
                        + "the best plan found so far", counter.tripped(), counter.productions(),
                        counter.elapsedMillis());
            }
            return new Optimized(withNamesOf(rel, toLogical(best)),
                    counter.report(budget, degradation));
        } catch (RelOptPlanner.CannotPlanException e) {
            if (counter.tripped() == null) {
                throw e;   // a genuine planning failure, not the budget's doing
            }
            log.warn("Planning cut short by {} after {} productions ({} ms) before any implemented "
                    + "plan existed; answering with the structural plan", counter.tripped(),
                    counter.productions(), counter.elapsedMillis());
            return new Optimized(withNamesOf(rel, structural(rel)),
                    counter.report(budget, Planning.Degradation.STRUCTURAL_FALLBACK));
        }
    }

    /**
     * The structural plan: {@link AranRules#STRUCTURAL} applied by a {@link HepPlanner} until it
     * has nothing left to apply. Logical in, logical out — no physical convention is involved, so
     * there is nothing to convert back and no cost to compare. Correct by construction of the
     * rules it uses; not chosen, and flagged as such by the caller.
     */
    private static RelNode structural(RelNode logical) {
        HepProgramBuilder program = new HepProgramBuilder();
        AranRules.STRUCTURAL.forEach(program::addRuleInstance);
        HepPlanner hep = new HepPlanner(program.build());
        hep.setRoot(logical);
        return toLogical(hep.findBestExp());
    }

    /**
     * {@code plan} answering under {@code requested}'s column names — what Calcite's own
     * {@code RelRoot} does with its {@code fields}. Both planners register an equivalent by
     * position and type, never by name, so the plan they return may carry any member's names:
     * {@code ProjectMergeRule}'s permutation branch rebuilds two projections that compose to the
     * identity without forcing their names, which over an EXPANDED class under a WHERE handed
     * back the wrapper's projection and answered under raw source IRIs (V70 residue). The rename
     * goes on the top projection when there is one and under any sort, so the unparse neither
     * wraps an extra sub-query nor moves an ORDER BY into one that DuckDB need not honour.
     */
    private static RelNode withNamesOf(RelNode requested, RelNode plan) {
        List<String> names = requested.getRowType().getFieldNames();
        if (plan.getRowType().getFieldNames().equals(names)) {
            return plan;
        }
        if (plan instanceof org.apache.calcite.rel.core.Sort s) {
            return org.apache.calcite.rel.logical.LogicalSort.create(
                    withNamesOf(requested, s.getInput()), s.getCollation(), s.offset, s.fetch);
        }
        if (plan instanceof org.apache.calcite.rel.core.Project p) {
            return org.apache.calcite.rel.logical.LogicalProject.create(
                    p.getInput(), p.getHints(), p.getProjects(), names, p.getVariablesSet());
        }
        List<RexNode> identity = new ArrayList<>(plan.getRowType().getFieldCount());
        RexBuilder rex = plan.getCluster().getRexBuilder();
        for (int i = 0; i < plan.getRowType().getFieldCount(); i++) {
            identity.add(rex.makeInputRef(plan, i));
        }
        return org.apache.calcite.rel.logical.LogicalProject.create(
                plan, List.of(), identity, names, java.util.Set.of());
    }

    /**
     * Counts productions and raises the cancel flag at the budget. Productions are counted on the
     * <em>after</em> event only — Calcite fires {@code ruleProductionSucceeded} twice per
     * production, before and after the memo is updated.
     */
    private static final class ProductionCounter implements RelOptListener {
        private final PlannerBudget budget;
        private final @Nullable CancelFlag cancel;
        private final long startedNanos = System.nanoTime();
        private final Map<String, Integer> byRule = new LinkedHashMap<>();
        private int productions;
        private Planning.@Nullable Tripped tripped;

        ProductionCounter(PlannerBudget budget, @Nullable CancelFlag cancel) {
            this.budget = budget;
            this.cancel = cancel;
        }

        @Override public void ruleProductionSucceeded(RuleProductionEvent event) {
            if (event.isBefore()) {
                return;
            }
            productions++;
            byRule.merge(event.getRuleCall().getRule().toString(), 1, Integer::sum);
            if (tripped != null || cancel == null) {
                return;
            }
            if (budget.productionBudget() > 0 && productions > budget.productionBudget()) {
                tripped = Planning.Tripped.PRODUCTION_BUDGET;
                cancel.requestCancel();
            } else if (elapsedMillis() > budget.wallClockGuard().toMillis()) {
                tripped = Planning.Tripped.WALL_CLOCK;
                cancel.requestCancel();
            }
        }

        @Override public void relEquivalenceFound(RelEquivalenceEvent event) {}
        @Override public void ruleAttempted(RuleAttemptedEvent event) {}
        @Override public void relDiscarded(RelDiscardedEvent event) {}
        @Override public void relChosen(RelChosenEvent event) {}

        int productions() { return productions; }
        Planning.@Nullable Tripped tripped() { return tripped; }
        long elapsedMillis() { return (System.nanoTime() - startedNanos) / 1_000_000L; }

        Planning report(PlannerBudget budget, Planning.Degradation degradation) {
            return new Planning(productions, byRule, budget.productionBudget(), elapsedMillis(),
                    degradation, tripped);
        }
    }

    private static RelNode toLogical(RelNode rel) {
        return rel.accept(new org.apache.calcite.rel.RelShuttleImpl() {
            @Override public RelNode visit(org.apache.calcite.rel.core.TableScan scan) {
                return org.apache.calcite.rel.logical.LogicalTableScan.create(scan.getCluster(), scan.getTable(), scan.getHints());
            }
            @Override public RelNode visit(RelNode other) {
                if (other instanceof org.apache.calcite.rel.core.Filter) {
                    org.apache.calcite.rel.core.Filter f = (org.apache.calcite.rel.core.Filter) other;
                    return org.apache.calcite.rel.logical.LogicalFilter.create(f.getInput().accept(this), f.getCondition());
                } else if (other instanceof org.apache.calcite.rel.core.Project) {
                    org.apache.calcite.rel.core.Project p = (org.apache.calcite.rel.core.Project) other;
                    return org.apache.calcite.rel.logical.LogicalProject.create(p.getInput().accept(this), p.getHints(), p.getProjects(), p.getRowType());
                } else if (other instanceof org.apache.calcite.rel.core.Join) {
                    org.apache.calcite.rel.core.Join j = (org.apache.calcite.rel.core.Join) other;
                    return org.apache.calcite.rel.logical.LogicalJoin.create(j.getLeft().accept(this), j.getRight().accept(this), j.getHints(), j.getCondition(), j.getVariablesSet(), j.getJoinType());
                } else if (other instanceof org.apache.calcite.rel.core.Aggregate) {
                    org.apache.calcite.rel.core.Aggregate a = (org.apache.calcite.rel.core.Aggregate) other;
                    return org.apache.calcite.rel.logical.LogicalAggregate.create(a.getInput().accept(this), a.getHints(), a.getGroupSet(), a.getGroupSets(), a.getAggCallList());
                } else if (other instanceof org.apache.calcite.rel.core.Union) {
                    org.apache.calcite.rel.core.Union u = (org.apache.calcite.rel.core.Union) other;
                    return org.apache.calcite.rel.logical.LogicalUnion.create(
                            u.getInputs().stream().map(i -> i.accept(this)).toList(), u.all);
                } else if (other instanceof org.apache.calcite.rel.core.Sort) {
                    org.apache.calcite.rel.core.Sort s = (org.apache.calcite.rel.core.Sort) other;
                    return org.apache.calcite.rel.logical.LogicalSort.create(s.getInput().accept(this), s.getCollation(), s.offset, s.fetch);
                } else if (other instanceof org.apache.calcite.rel.core.Values) {
                    // Reached only via FILTER_REDUCE_EXPRESSIONS: an enforced denial constraint
                    // proved a filter unsatisfiable and it became an empty Values. Normalised to
                    // logical form like everything else here so PlanShape reports one node kind
                    // for it, and so RelToSqlConverter unparses it through its Values path
                    // (DuckDB does not support aliased VALUES, so that emits
                    // "SELECT ... FROM (SELECT NULL AS ...) AS t WHERE 1 = 0" — a legal empty
                    // relation with the right row type, not a table reference).
                    org.apache.calcite.rel.core.Values v = (org.apache.calcite.rel.core.Values) other;
                    return org.apache.calcite.rel.logical.LogicalValues.create(
                            v.getCluster(), v.getRowType(), v.getTuples());
                } else if (other instanceof org.apache.calcite.adapter.enumerable.EnumerableLimit) {
                    org.apache.calcite.adapter.enumerable.EnumerableLimit limit = (org.apache.calcite.adapter.enumerable.EnumerableLimit) other;
                    return org.apache.calcite.rel.logical.LogicalSort.create(limit.getInput().accept(this), org.apache.calcite.rel.RelCollationTraitDef.INSTANCE.getDefault(), limit.offset, limit.fetch);
                }
                return super.visit(other);
            }
        });
    }

    /**
     * Optimize {@code rel} and unparse to a DuckDB SQL string via {@link RelToSqlConverter}
     * and {@link DuckDbDialect}.
     */
    public static String toSql(RelNode rel) {
        RelNode optimized = optimize(rel);
        return toSqlFromOptimized(optimized);
    }

    /**
     * Convert an already-optimized {@link RelNode} to DuckDB SQL, skipping the optimize step.
     * Useful when the caller wants both the optimized {@link RelNode} and the SQL string.
     */
    public static String toSqlFromOptimized(RelNode optimized) {
        RelToSqlConverter converter = new AranRelToSql();
        SqlNode sqlNode = converter.visitRoot(optimized).asStatement();
        return sqlNode.toSqlString(DuckDbDialect.DEFAULT).getSql();
    }

    /**
     * {@link RelToSqlConverter} that never reads a {@code src_} relation as {@code *}.
     *
     * <p>{@link SrcTable} declares a wrapper's raw columns sorted by name; the {@code src_} view
     * DuckDB binds is {@code SELECT * FROM <reader>}, in the file's own column order — and for a
     * dataset several classes read, with columns this class never names. Wherever the unparse
     * would take the relation whole ({@code SELECT *}: an identity projection whose names match,
     * a filter at the root, a union branch), DuckDB answered in physical order, so values landed
     * under the wrong positions (V70). Listing the declared columns makes the relation exactly
     * what the planner typed.
     *
     * <p>Public only because Calcite dispatches {@code visit} reflectively and cannot invoke a
     * method of an inaccessible class; nothing outside this file constructs it.
     */
    public static final class AranRelToSql extends RelToSqlConverter {
        private AranRelToSql() {
            super(DuckDbDialect.DEFAULT);
        }

        @Override
        public Result visit(TableScan e) {
            Result scan = super.visit(e);
            if (e.getTable().unwrap(SrcTable.class) == null) {
                return scan;
            }
            SqlSelect select = scan.asSelect();
            List<SqlNode> columns = new ArrayList<>(e.getRowType().getFieldCount());
            for (String column : e.getRowType().getFieldNames()) {
                columns.add(new SqlIdentifier(column, SqlParserPos.ZERO));
            }
            select.setSelectList(new SqlNodeList(columns, SqlParserPos.ZERO));
            return result(select, List.of(Clause.SELECT), e, null);
        }
    }

    /** Return the plan tree as a human-readable string (via {@link RelOptUtil#toString}). */
    public static String planString(RelNode rel) {
        return RelOptUtil.toString(rel, org.apache.calcite.sql.SqlExplainLevel.ALL_ATTRIBUTES);
    }
}

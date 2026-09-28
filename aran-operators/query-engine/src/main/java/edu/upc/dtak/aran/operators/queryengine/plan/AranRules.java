package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.adapter.enumerable.EnumerableTableScan;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.volcano.AbstractConverter;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.rules.FilterJoinRule;
import org.apache.calcite.rel.rules.FilterMergeRule;
import org.apache.calcite.rel.rules.FilterProjectTransposeRule;
import org.apache.calcite.rel.rules.FilterSetOpTransposeRule;
import org.apache.calcite.rel.rules.JoinPushThroughJoinRule;
import org.apache.calcite.rel.rules.JoinUnionTransposeRule;
import org.apache.calcite.rel.rules.ProjectMergeRule;
import org.apache.calcite.rel.rules.ReduceExpressionsRule;
import org.apache.calcite.rex.RexUtil;

import java.util.List;

/**
 * The rule set {@link PlanExecutor} plans with: a curated, deliberately small set registered on a
 * planner that has been emptied first.
 *
 * <h2>Why the planner is emptied</h2>
 *
 * <p>{@link OqlPlanner} obtains its cluster from {@code RelBuilder.create(FrameworkConfig)}, which
 * routes through Calcite's {@code CalcitePrepareImpl} and hands back a {@link VolcanoPlanner}
 * already carrying <b>113 rules</b> — the whole default preparation set: materialized-view
 * matching, streaming {@code Delta*} rules, the Bindable convention, {@code SemiJoinRule},
 * {@code EnumerableMergeJoinRule}, and so on. Everything {@code PlanExecutor} used to register by
 * hand was therefore a no-op: {@code addRule} returns {@code false} for a rule already present, and
 * for {@code FILTER_INTO_JOIN}, {@code PROJECT_MERGE}, {@code JOIN_COMMUTE} and every Enumerable
 * rule it did exactly that. The comment describing a hand-picked rule set was aspirational.
 *
 * <p>Two concrete problems followed, both fixed by owning the set:
 *
 * <ul>
 *   <li><b>Physical rules we never asked for.</b> {@code EnumerableMergeJoinRule} was winning on
 *       cost and demanding sorted inputs, so the chosen plan carried collation-only
 *       {@code EnumerableSort} nodes. {@code PlanExecutor.toLogical} turned those into
 *       {@code LogicalSort}, and {@code RelToSqlConverter} unparsed them as real {@code ORDER BY}
 *       clauses inside subqueries — sorts DuckDB then had to perform for a plan that never needed
 *       them. Merge join is not registered here.</li>
 *   <li><b>Unrestricted logical rules.</b> See below.</li>
 * </ul>
 *
 * <h2>Why the logical rules are restricted to {@code Logical*} classes</h2>
 *
 * <p>A {@code TransformationRule} may not produce a {@link org.apache.calcite.rel.PhysicalNode}:
 * {@code VolcanoRuleCall.transformTo} throws {@code "… is a PhysicalNode, which is not allowed in
 * …"}. Rules that rebuild their match with {@code node.copy(node.getTraitSet(), …)} therefore fail
 * outright if they matched a physical node, because the copy inherits the physical trait. This is
 * what {@code JoinUnionTransposeRule} did on the stock rule set, and it is the reason a
 * {@code HepPlanner} pre-pass existed at all.
 *
 * <p>{@link VolcanoPlanner} does guard the <em>seed</em> of a match — it refuses to index a
 * physical class against a transformation rule's operand — but the guard is escapable. A rule whose
 * operand tree contains a bare {@code RelNode.class} input (as {@link JoinUnionTransposeRule} does
 * for its non-union side) can be seeded at that operand from a node that is <em>not</em> a
 * {@code PhysicalNode} and then walk <em>up</em> to its parent, and
 * {@code RelOptRuleOperand.matches} applies no convention test on the way. On the stock set the
 * seed was an {@code AbstractConverter} carrying a collation trait — a trait nothing demanded
 * except {@code EnumerableMergeJoinRule} — and the parent it reached was an
 * {@code EnumerableHashJoin} over an {@code EnumerableUnion}.
 *
 * <p><b>Emptying the planner is, on its own, enough to stop that today.</b> With merge join gone
 * nothing requests a collation, so no {@code AbstractConverter} is created, so nothing seeds the
 * walk: the unrestricted {@code CoreRules} plan every query shape probed without error (there is a
 * test that says exactly this). Pinning the operands is kept anyway, because "no converter happens
 * to be created" is a property of the current rule set and query shapes, not an invariant —
 * whereas an operand that only matches {@link LogicalJoin} cannot be handed a physical node at all.
 * Calcite makes the same choice in the rules that never had this problem: {@code JoinCommuteRule}
 * and {@code JoinPushThroughJoinRule} already pin {@link LogicalJoin}, not {@code Join}.
 */
final class AranRules {

    private AranRules() {}

    /**
     * Logical rewrites. Each is a {@code TransformationRule}: it adds an alternative to the
     * {@code RelSet} rather than replacing anything, so Volcano keeps both shapes and the cost
     * model — {@link AranRelMdRowCount} over {@link WrapperStatistic} row counts and keys — picks.
     */
    static final List<RelOptRule> LOGICAL = List.of(
            // ── Predicate placement ──────────────────────────────────────────
            // Push a WHERE predicate into the join condition, then through the project below it,
            // then into each branch of the integrated class's UNION ALL. The last step is the one
            // that matters for GAV: without it a filter on an integrated class is evaluated above
            // the union, after every source's rows have been read and joined.
            FilterJoinRule.FilterIntoJoinRule.FilterIntoJoinRuleConfig.DEFAULT
                    .withOperandSupplier(b0 -> b0.operand(LogicalFilter.class)
                            .oneInput(b1 -> b1.operand(LogicalJoin.class).anyInputs()))
                    .as(FilterJoinRule.FilterIntoJoinRule.FilterIntoJoinRuleConfig.class)
                    .toRule(),
            FilterProjectTransposeRule.Config.DEFAULT
                    .withOperandFor(LogicalFilter.class,
                            f -> !RexUtil.containsCorrelation(f.getCondition()),
                            LogicalProject.class, p -> true)
                    .toRule(),
            FilterSetOpTransposeRule.Config.DEFAULT
                    .withOperandSupplier(b0 -> b0.operand(LogicalFilter.class)
                            .oneInput(b1 -> b1.operand(LogicalUnion.class).anyInputs()))
                    .as(FilterSetOpTransposeRule.Config.class)
                    .toRule(),
            FilterMergeRule.Config.DEFAULT.withOperandFor(LogicalFilter.class).toRule(),

            // ── Predicate reduction against what the source already guarantees ───
            // Consumes the pulled-up predicates AranRelMdPredicates derives from a class's
            // ENFORCED denial constraints. A query predicate implied by one is weakened away
            // (the u_ view body already checked it — re-checking it per row is dead work); one
            // that contradicts it collapses the branch to empty VALUES, so a query whose answer
            // is provably empty never opens a snapshot. Without this rule the Predicates handler
            // is inert: the predicates would refine estimates and change nothing else.
            //
            // Registered even though nothing else here reduces constants: this is the only rule
            // in the set that reads RelOptPredicateList at all.
            ReduceExpressionsRule.FilterReduceExpressionsRule.FilterReduceExpressionsRuleConfig.DEFAULT
                    .withOperandFor(LogicalFilter.class)
                    .as(ReduceExpressionsRule.FilterReduceExpressionsRule
                            .FilterReduceExpressionsRuleConfig.class)
                    .toRule(),

            // ── Projection cleanup ───────────────────────────────────────────
            // Collapses the Project the OQL SELECT emits into the one under it, which for a
            // single-source query removes an entire level of subquery from the emitted SQL.
            ProjectMergeRule.Config.DEFAULT.withOperandFor(LogicalProject.class).toRule(),

            // ── Join shape ───────────────────────────────────────────────────
            // Which side becomes the build side, and in what order a multi-class query joins:
            // genuine cost decisions, driven by the freyja row counts behind WrapperStatistic.
            // Both already pin LogicalJoin in Calcite's own default config.
            CoreRules.JOIN_COMMUTE,
            JoinPushThroughJoinRule.LEFT,
            JoinPushThroughJoinRule.RIGHT,

            // ── Join below the union ─────────────────────────────────────────
            // A ⋈ (B₁ ∪ B₂) → (A ⋈ B₁) ∪ (A ⋈ B₂). UNION ALL does not preserve uniqueness, so
            // above the union an integrated class can never declare a key and AranRelMdRowCount's
            // many-to-one estimate can never fire; below it, every branch is single-source and
            // WrapperStatistic supplies freyja-verified keys. Whether the tighter estimate is
            // worth N×M joins is now a cost question, which is the whole point of it being here.
            JoinUnionTransposeRule.Config.LEFT
                    .withOperandFor(LogicalJoin.class, LogicalUnion.class, true).toRule(),
            JoinUnionTransposeRule.Config.RIGHT
                    .withOperandFor(LogicalJoin.class, LogicalUnion.class, false).toRule(),

            // ── Trust-tier-gated join elimination (Phase 4B) ─────────────────
            // A ⋈ B → σ(fk NOT NULL)(A), but ONLY when a DECLARED/VERIFIED referential edge backs
            // the join, B's referenced key is a verified (exact) unique key, and no B column is
            // projected above the join. Never fires on a HYPOTHESIS (accepted-but-unverified join):
            // that is the correctness spine, not a heuristic. Calcite core has no inner-join-removal
            // rule and its outer-join ones carry no tier, so this is bespoke. Every non-eliminable
            // case returns without transforming, leaving the plan byte-identical.
            AranJoinRemoveRule.INSTANCE);

    /**
     * The structural subset of {@link #LOGICAL} — what a query is planned with when the budget
     * cancels Volcano before any implemented plan exists ({@code PlanExecutor}'s fallback, D3).
     *
     * <p>Predicate placement and projection cleanup only. Deliberately none of the join-shape
     * rules: {@code JOIN_COMMUTE}, {@code JoinPushThroughJoinRule} and the two
     * {@code JoinUnionTransposeRule}s are exactly the rules whose product is the PERF-P1 explosion,
     * and a Hep program applies every match it finds with no cost to stop it. The output is the
     * query's own join order with filters pushed as far as they go — never costed, always correct,
     * and always reported as {@code STRUCTURAL_FALLBACK} so nobody mistakes it for a chosen plan.
     * {@code AranJoinRemoveRule} is left out too: elimination is a costed decision by design.
     */
    static final List<RelOptRule> STRUCTURAL = LOGICAL.stream()
            .filter(r -> !(r == CoreRules.JOIN_COMMUTE
                    || r == JoinPushThroughJoinRule.LEFT
                    || r == JoinPushThroughJoinRule.RIGHT
                    || r instanceof JoinUnionTransposeRule
                    || r == AranJoinRemoveRule.INSTANCE))
            .toList();

    /**
     * Physical conversion — the only other thing the planner is allowed to do.
     *
     * <p>One rule per operator that can appear in a plan: those {@link OqlPlanner} emits, plus
     * {@code Values} — which it never emits but {@code FILTER_REDUCE_EXPRESSIONS} creates, as an
     * empty one, when an enforced denial constraint proves a filter unsatisfiable. Plus the
     * trait-conversion rule Volcano needs to satisfy the ENUMERABLE root. Deliberately absent:
     * merge join (see the class javadoc), {@code EnumerableCalc}, the Bindable convention, and
     * everything else in Calcite's default preparation set.
     *
     * <p>The test for whether a rule belongs here is therefore "can this operator reach the
     * physical stage", not "does the OQL lowerer produce it" — a logical rewrite that introduces
     * an operator has to bring its conversion rule with it, or planning fails outright rather
     * than falling back.
     */
    static final List<RelOptRule> PHYSICAL = List.of(
            EnumerableRules.ENUMERABLE_PROJECT_RULE,
            EnumerableRules.ENUMERABLE_FILTER_RULE,
            EnumerableRules.ENUMERABLE_JOIN_RULE,
            // Not ENUMERABLE_AGGREGATE_RULE: it declines AVG, which Calcite cannot execute in
            // Java but DuckDB can. AranAggregate's javadoc has the whole argument.
            AranAggregate.RULE,
            EnumerableRules.ENUMERABLE_SORT_RULE,
            EnumerableRules.ENUMERABLE_LIMIT_RULE,
            EnumerableRules.ENUMERABLE_UNION_RULE,
            // OqlPlanner emits no Values, but FILTER_REDUCE_EXPRESSIONS above does — an empty
            // one, when an enforced constraint proves a filter unsatisfiable. That rule also
            // calls planner.prune(filter) on its way out, so the Filter alternative stops being
            // explored; with no physical rule for the Values that replaced it the subset has no
            // implementation at all, and planning fails with CannotPlanException rather than
            // quietly keeping the old plan. Verified by deleting this line: two tests in
            // AranRelMdPredicatesTest go from green to CannotPlanException.
            EnumerableRules.ENUMERABLE_VALUES_RULE,
            AbstractConverter.ExpandConversionRule.INSTANCE,
            tableScanConverter());

    /**
     * {@link LogicalTableScan} → {@link EnumerableTableScan}, unconditionally. Carried over from
     * the inline definition {@code PlanExecutor.optimize} used to hold; it has to be listed here
     * now that the planner no longer arrives with Calcite's scan rules already registered.
     */
    private static ConverterRule tableScanConverter() {
        return new ConverterRule(ConverterRule.Config.INSTANCE.withConversion(
                LogicalTableScan.class, Convention.NONE, EnumerableConvention.INSTANCE,
                "LogicalToEnumerableTableScan")) {
            @Override public RelNode convert(RelNode rel) {
                return EnumerableTableScan.create(rel.getCluster(), rel.getTable());
            }
        };
    }

    /**
     * Empties {@code planner} of Calcite's default preparation rules and installs
     * {@link #LOGICAL} + {@link #PHYSICAL}. Must be called before {@code setRoot}: {@code clear()}
     * discards registered {@code RelSet}s as well as rules (it leaves the planner's trait defs
     * alone, which is what keeps the converter rule registrable).
     */
    static void install(VolcanoPlanner planner) {
        planner.clear();
        LOGICAL.forEach(planner::addRule);
        PHYSICAL.forEach(planner::addRule);
    }
}

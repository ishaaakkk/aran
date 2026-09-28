package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.util.ImmutableBitSet;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The physical aggregate this planner costs with — in place of Calcite's
 * {@code EnumerableAggregate}, which refused {@code AVG}.
 *
 * <p>{@code EnumerableAggregate}'s constructor accepts only the aggregate functions Calcite can
 * itself <em>execute</em> in generated Java ({@code RexImpTable}); {@code AVG} is not one of them,
 * because Calcite expects {@code AggregateReduceFunctionsRule} to have rewritten it into
 * {@code SUM/COUNT} first. So {@code EnumerableAggregateRule} declined every {@code AVG} and the
 * query died with {@code CannotPlanException}. Neither half of that applies here: the ENUMERABLE
 * plan Volcano picks is only a costing vehicle — {@link PlanExecutor} walks it back to logical form
 * and unparses it for DuckDB, which computes {@code AVG} natively — and reducing {@code AVG} would
 * be wrong, not just unnecessary: the reduction casts {@code SUM/COUNT} back to {@code AVG}'s
 * Calcite type, the argument's own integer type, so an average of 3 and 4 would come back as 3 or
 * 4 instead of DuckDB's 3.5.
 *
 * <p>Cost is {@link Aggregate}'s own — {@code EnumerableAggregate} does not override it — so
 * swapping the physical class changes no plan choice. {@link #implement} is unreachable: nothing
 * in this engine executes an Enumerable plan.
 */
final class AranAggregate extends Aggregate implements EnumerableRel {

    AranAggregate(RelOptCluster cluster, RelTraitSet traits, List<RelHint> hints, RelNode input,
                  ImmutableBitSet groupSet, @Nullable List<ImmutableBitSet> groupSets,
                  List<AggregateCall> aggCalls) {
        super(cluster, traits, hints, input, groupSet, groupSets, aggCalls);
    }

    @Override
    public Aggregate copy(RelTraitSet traitSet, RelNode input, ImmutableBitSet groupSet,
                          @Nullable List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
        return new AranAggregate(getCluster(), traitSet, hints, input, groupSet, groupSets, aggCalls);
    }

    @Override
    public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
        throw new UnsupportedOperationException(
                "AranAggregate is a costing vehicle; its plan is unparsed for DuckDB, never run");
    }

    /** {@link LogicalAggregate} → {@link AranAggregate}, for every aggregate function. */
    static final ConverterRule RULE = new ConverterRule(ConverterRule.Config.INSTANCE.withConversion(
            LogicalAggregate.class, Convention.NONE, EnumerableConvention.INSTANCE,
            "AranAggregateRule")) {
        @Override public RelNode convert(RelNode rel) {
            // Traits exactly as EnumerableAggregateRule derives them, so the memo is unchanged.
            LogicalAggregate agg = (LogicalAggregate) rel;
            RelTraitSet traits = rel.getCluster().traitSetOf(EnumerableConvention.INSTANCE);
            return new AranAggregate(agg.getCluster(), traits, agg.getHints(),
                    convert(agg.getInput(), traits),
                    agg.getGroupSet(), agg.getGroupSets(), agg.getAggCallList());
        }
    };
}

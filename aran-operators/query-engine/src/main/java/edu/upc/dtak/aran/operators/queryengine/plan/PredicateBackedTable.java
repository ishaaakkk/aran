package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.rex.RexNode;

import java.util.List;

/**
 * Predicates a {@link org.apache.calcite.schema.Table} can guarantee about every row it yields,
 * unwrapped from a {@link org.apache.calcite.plan.RelOptTable} via
 * {@code scan.getTable().unwrap(PredicateBackedTable.class)} — see {@link AranRelMdPredicates},
 * the only caller. Sibling of {@link ProfileBackedTable}, same unwrap contract.
 *
 * <p>Today the only source is the class's <em>enforced</em> denial constraints: the view body
 * {@code DuckDbExecutor.buildDdl} generates already carries them as a {@code WHERE} conjunction,
 * so a row violating one cannot be read. That makes them a guarantee rather than an estimate,
 * which is what {@link org.apache.calcite.plan.RelOptPredicateList} means.
 *
 * <p>{@link org.apache.calcite.rex.RexInputRef} ordinals index the table's own
 * {@code getRowType()} order, exactly as {@link ProfileBackedTable}'s do. {@link OqlPlanner}
 * guarantees this by planning the constraint over the same {@link AranSchema} the query will
 * plan over.
 */
interface PredicateBackedTable {

    /**
     * Predicates every row of this table satisfies. Empty means "nothing is guaranteed" — never
     * {@code null}, and never a partially-parsed constraint: a constraint the planner could not
     * read is dropped whole rather than weakened.
     */
    List<RexNode> pulledUpPredicates();
}

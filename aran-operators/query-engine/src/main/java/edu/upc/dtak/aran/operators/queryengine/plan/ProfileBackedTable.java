package edu.upc.dtak.aran.operators.queryengine.plan;

import org.jspecify.annotations.Nullable;

/**
 * Per-ordinal profile evidence a {@link org.apache.calcite.schema.Table} can offer the
 * metadata layer, unwrapped from a {@link org.apache.calcite.plan.RelOptTable} via
 * {@code scan.getTable().unwrap(ProfileBackedTable.class)} — see {@link AranRelMdDistinctRowCount}
 * and {@link AranRelMdSelectivity}, the only callers.
 *
 * <p>Ordinals index the table's own {@code getRowType()} order (the class's global property
 * IRIs, sorted), exactly as {@link WrapperStatistic} and {@link AranStatistic} already index
 * their key bitsets. Both {@code null} for an ordinal that is out of range, unmapped in this
 * source, or has no {@code ColumnProfile} at all — evidence is opt-in, never guessed.
 */
interface ProfileBackedTable {

    /** Exact distinct-value count ({@code ColumnProfile.cardinality}), or {@code null} if unknown. */
    @Nullable Double columnNdv(int ordinal);

    /** Null fraction ({@code ColumnProfile.incompleteness}), or {@code null} if unknown. */
    @Nullable Double columnNullFraction(int ordinal);
}

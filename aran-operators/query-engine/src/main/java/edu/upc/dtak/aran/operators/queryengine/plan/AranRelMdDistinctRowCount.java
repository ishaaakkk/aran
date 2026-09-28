package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.BuiltInMetadata;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.MetadataHandler;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.NumberUtil;
import org.jspecify.annotations.Nullable;

/**
 * Profile-backed {@code DistinctRowCount} metadata handler for {@link TableScan}.
 *
 * <p>Adds one source of evidence Calcite's own default lacks: freyja's exact per-column NDV
 * ({@link ProfileBackedTable#columnNdv}), for the single-column, unfiltered case — the shape
 * {@code GROUP BY} cardinality estimation asks for most often.
 *
 * <p><b>Safety property — read before touching the fallback branch.</b> Calcite's own default
 * ({@code RelMdDistinctRowCount}) registers its own {@code getDistinctRowCount(TableScan, ...)}
 * method. Once this class is chained ahead of it in {@code OqlPlanner.ARAN_METADATA_PROVIDER}
 * for the identical {@code TableScan} parameter type, <em>this</em> method is dispatched
 * exclusively for every table scan — Calcite's own {@code TableScan} method, and by extension
 * its generic {@code RelNode} fallback, are never reached again for that node
 * (verified by decompiling {@code ReflectiveRelMetadataProvider}/{@code DispatchGenerator} in
 * calcite-core 1.41.0: the generated dispatcher picks exactly one handler per concrete rel
 * class — whichever provider registered it first in chain order — so a competing registration
 * for the same class is dead code regardless of what this method returns, including
 * {@code null}; there is no "return null to fall through to the same class in the next
 * provider"). Concretely, Calcite's own {@code TableScan} method is:
 * {@code unwrap(BuiltInMetadata.DistinctRowCount.Handler) → if present, delegate; else
 * areColumnsDefinitelyUnique(mq, rel, groupKey) ? multiply(getRowCount, getSelectivity) :
 * null}. The "no evidence" branch below replicates exactly that formula (same public
 * {@link RelMdUtil}/{@link NumberUtil} calls) rather than returning a bare {@code null}, so a
 * verified unique key that isn't also profiled keeps getting Calcite's existing exact answer
 * instead of silently regressing to "unknown". {@link AranRelMdRowCount} follows the identical
 * pattern for {@code Join} and for the identical reason.
 */
public final class AranRelMdDistinctRowCount
        implements MetadataHandler<BuiltInMetadata.DistinctRowCount> {

    /** Reflective provider bound to the {@code getDistinctRowCount(TableScan, ...)} method below. */
    public static final RelMetadataProvider SOURCE =
            ReflectiveRelMetadataProvider.reflectiveSource(
                    new AranRelMdDistinctRowCount(),
                    BuiltInMetadata.DistinctRowCount.Handler.class);

    @Override
    public MetadataDef<BuiltInMetadata.DistinctRowCount> getDef() {
        return BuiltInMetadata.DistinctRowCount.DEF;
    }

    /**
     * Distinct-row-count estimate for a {@link TableScan}. Calcite's reflective provider binds
     * this {@code TableScan} overload for every scan node; there is no other rel type registered
     * here, so nothing else is affected.
     */
    public @Nullable Double getDistinctRowCount(TableScan scan, RelMetadataQuery mq,
            ImmutableBitSet groupKey, @Nullable RexNode predicate) {
        if (predicate == null && groupKey.cardinality() == 1) {
            ProfileBackedTable table = scan.getTable().unwrap(ProfileBackedTable.class);
            if (table != null) {
                Double ndv = table.columnNdv(groupKey.nextSetBit(0));
                if (ndv != null) {
                    Double rowCount = mq.getRowCount(scan);
                    // A stale/inconsistent profile snapshot could record more distinct values
                    // than rows; cap so the estimate stays physically sane either way.
                    return rowCount == null ? ndv : Math.min(ndv, rowCount);
                }
            }
        }
        // No profile evidence — byte-identical to Calcite's default TableScan handler (see the
        // class javadoc for why this must be computed here rather than returned as null).
        return RelMdUtil.areColumnsDefinitelyUnique(mq, scan, groupKey)
                ? NumberUtil.multiply(mq.getRowCount(scan), mq.getSelectivity(scan, predicate))
                : null;
    }
}

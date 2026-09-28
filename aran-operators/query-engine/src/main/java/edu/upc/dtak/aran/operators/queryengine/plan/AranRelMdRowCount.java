package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.BuiltInMetadata;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.MetadataHandler;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.util.ImmutableBitSet;
import org.jspecify.annotations.Nullable;

/**
 * Key-aware {@code RowCount} metadata handler for {@link Join}.
 *
 * <p>Calcite's default join-cardinality estimate is {@code left × right × selectivity}
 * (0.15 for a single equi-predicate) and never consults {@code getKeys()}/{@code isKey()}, so
 * a declared unique key on a join column is inert for join ordering. This handler special-cases
 * an INNER equi-join where one side's join key is a verified single-column key
 * ({@link AranStatistic#isKey}) and produces the standard many-to-one (PK–FK) estimate:
 *
 * <ul>
 *   <li>right key unique, left not → each left row matches ≤ 1 right row → {@code leftRows}</li>
 *   <li>left key unique, right not → {@code rightRows}</li>
 *   <li>both unique (one-to-one)   → {@code min(leftRows, rightRows)}</li>
 *   <li>neither unique, single-column key, both sides profiled → the classic equi-join estimate
 *       {@code leftRows * rightRows / max(V(L,k), V(R,k))}, where {@code V} is each side's
 *       distinct-value count for the key ({@link AranRelMdDistinctRowCount})</li>
 * </ul>
 *
 * <p><b>Safety property:</b> every case that is NOT an INNER equi-join with a one-sided unique
 * key or single-column NDV evidence on both sides defers to {@link RelMdUtil#getJoinRowCount}, so
 * non-key, non-evidenced join estimates are byte-identical to Calcite's default. Only {@code Join}
 * is handled here; the chained provider falls through to the default provider for every other rel
 * type.
 */
public final class AranRelMdRowCount implements MetadataHandler<BuiltInMetadata.RowCount> {

    /** Reflective provider bound to the {@code getRowCount(Join, mq)} method below. */
    public static final RelMetadataProvider SOURCE =
            ReflectiveRelMetadataProvider.reflectiveSource(
                    new AranRelMdRowCount(),
                    BuiltInMetadata.RowCount.Handler.class);

    @Override
    public MetadataDef<BuiltInMetadata.RowCount> getDef() {
        return BuiltInMetadata.RowCount.DEF;
    }

    /**
     * Row-count estimate for a {@link Join}. Calcite's reflective provider binds this
     * {@code Join} overload for join nodes; everything else defers to the default provider.
     */
    public @Nullable Double getRowCount(Join join, RelMetadataQuery mq) {
        // Default fallback — byte-identical to Calcite's default for every non-special case.
        final Double fallback = RelMdUtil.getJoinRowCount(mq, join, join.getCondition());

        if (join.getJoinType() != JoinRelType.INNER) {
            return fallback;
        }
        JoinInfo info = join.analyzeCondition();
        if (!info.isEqui() || info.leftKeys.isEmpty()) {
            return fallback;
        }

        Double leftRows = mq.getRowCount(join.getLeft());
        Double rightRows = mq.getRowCount(join.getRight());
        if (leftRows == null || rightRows == null) {
            return fallback;
        }

        boolean leftUnique = Boolean.TRUE.equals(
                mq.areColumnsUnique(join.getLeft(), ImmutableBitSet.of(info.leftKeys)));
        boolean rightUnique = Boolean.TRUE.equals(
                mq.areColumnsUnique(join.getRight(), ImmutableBitSet.of(info.rightKeys)));

        if (rightUnique && !leftUnique) {
            return leftRows;       // right is the "one" side
        }
        if (leftUnique && !rightUnique) {
            return rightRows;      // left is the "one" side
        }
        if (leftUnique && rightUnique) {
            return Math.min(leftRows, rightRows);  // one-to-one
        }

        // Neither side is a verified key. A single-column equi-join key's own NDV is still
        // evidence: |L ⋈ R| = |L|·|R| / max(V(L,k), V(R,k)) is the classic estimate for that
        // shape. Multi-key joins fall back — NDV independence across columns is a lie we don't
        // tell (the true joint NDV is bounded by, not equal to, the product of per-column NDVs).
        if (info.leftKeys.size() == 1) {
            Double ndvLeft = mq.getDistinctRowCount(join.getLeft(), ImmutableBitSet.of(info.leftKeys), null);
            Double ndvRight = mq.getDistinctRowCount(join.getRight(), ImmutableBitSet.of(info.rightKeys), null);
            if (ndvLeft != null && ndvRight != null && Math.max(ndvLeft, ndvRight) > 0) {
                return leftRows * rightRows / Math.max(ndvLeft, ndvRight);
            }
        }
        return fallback;           // no unique key, no usable NDV evidence
    }
}

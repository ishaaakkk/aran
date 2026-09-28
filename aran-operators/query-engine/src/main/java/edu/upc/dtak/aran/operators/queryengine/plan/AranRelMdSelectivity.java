package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.BuiltInMetadata;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.MetadataHandler;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.jspecify.annotations.Nullable;

/**
 * Profile-backed {@code Selectivity} metadata handler for {@link TableScan}.
 *
 * <p>Calcite's own default for a bare predicate is a fixed per-{@code SqlKind} guess
 * ({@code RelMdUtil#guessSelectivity}: 0.15 for {@code =}, 0.5 for a range comparison, 0.25
 * otherwise, 0.9 per {@code IS NOT NULL} — notably it has no {@code IS NULL} case at all, so an
 * {@code IS NULL} predicate silently falls into the 0.25 default). This handler replaces each
 * conjunct's guess with freyja's exact evidence where it exists — {@code 1/NDV} for an equality
 * against a literal, the profiled null fraction for {@code IS NULL} (and its complement for
 * {@code IS NOT NULL}) — and defers to {@link RelMdUtil#guessSelectivity} term-by-term otherwise.
 *
 * <p><b>Safety property.</b> Unlike {@link AranRelMdDistinctRowCount}, this method never returns
 * {@code null}: a {@code null} predicate is selectivity 1.0, and every conjunct resolves to
 * either profiled evidence or {@code guessSelectivity(term)}. For an all-unprofiled predicate
 * this is byte-identical to Calcite's own default, because {@code guessSelectivity(RexNode)}
 * itself decomposes {@link RelOptUtil#conjunctions} and multiplies the same per-term heuristic
 * (confirmed by decompiling {@code RelMdUtil} in calcite-core 1.41.0) — decomposing once here
 * and guessing each already-atomic term individually reproduces exactly the same product. This
 * class is also chained ahead of Calcite's own {@code TableScan} selectivity method, which is
 * therefore dead code once this exists (same dispatch mechanics as documented on
 * {@link AranRelMdDistinctRowCount}) — safe here specifically because this method is
 * self-sufficient rather than relying on a null return to reach it.
 */
public final class AranRelMdSelectivity implements MetadataHandler<BuiltInMetadata.Selectivity> {

    /** Reflective provider bound to the {@code getSelectivity(TableScan, ...)} method below. */
    public static final RelMetadataProvider SOURCE =
            ReflectiveRelMetadataProvider.reflectiveSource(
                    new AranRelMdSelectivity(),
                    BuiltInMetadata.Selectivity.Handler.class);

    @Override
    public MetadataDef<BuiltInMetadata.Selectivity> getDef() {
        return BuiltInMetadata.Selectivity.DEF;
    }

    /**
     * Selectivity estimate for a {@link TableScan}. Calcite's reflective provider binds this
     * {@code TableScan} overload for every scan node; a {@code Filter} above a scan reaches it
     * through Calcite's own default {@code Filter} row-count handler, which pushes its condition
     * down as exactly this call.
     */
    public Double getSelectivity(TableScan scan, RelMetadataQuery mq, @Nullable RexNode predicate) {
        if (predicate == null) {
            return 1.0;
        }
        ProfileBackedTable table = scan.getTable().unwrap(ProfileBackedTable.class);
        double selectivity = 1.0;
        for (RexNode term : RelOptUtil.conjunctions(predicate)) {
            selectivity *= termSelectivity(term, table);
        }
        return Math.max(0.0, Math.min(1.0, selectivity));
    }

    /** One conjunct's selectivity: profiled evidence if this term offers it, else Calcite's guess. */
    private static double termSelectivity(RexNode term, @Nullable ProfileBackedTable table) {
        if (table != null && term instanceof RexCall call) {
            Double evidence = profiledEvidence(call, table);
            if (evidence != null) {
                return evidence;
            }
        }
        // No evidence for this term — Calcite's own per-SqlKind guess (see class javadoc for why
        // this is byte-identical to the default rather than merely "close").
        return RelMdUtil.guessSelectivity(term);
    }

    /** Profiled evidence for one call, or {@code null} if this shape or column offers none. */
    private static @Nullable Double profiledEvidence(RexCall call, ProfileBackedTable table) {
        switch (call.getKind()) {
            case EQUALS: {
                RexInputRef ref = equalityRef(call);
                if (ref == null) {
                    return null;
                }
                Double ndv = table.columnNdv(ref.getIndex());
                // ndv <= 0 would divide to Infinity/NaN — not usable evidence either way.
                return ndv != null && ndv > 0 ? 1.0 / ndv : null;
            }
            case IS_NULL: {
                RexInputRef ref = asInputRef(call.getOperands().get(0));
                return ref == null ? null : table.columnNullFraction(ref.getIndex());
            }
            case IS_NOT_NULL: {
                RexInputRef ref = asInputRef(call.getOperands().get(0));
                if (ref == null) {
                    return null;
                }
                Double fraction = table.columnNullFraction(ref.getIndex());
                return fraction == null ? null : 1.0 - fraction;
            }
            default:
                return null;
        }
    }

    /** The {@link RexInputRef} side of an equality against a literal, in either operand order. */
    private static @Nullable RexInputRef equalityRef(RexCall call) {
        RexNode a = call.getOperands().get(0);
        RexNode b = call.getOperands().get(1);
        if (a instanceof RexInputRef ref && b instanceof RexLiteral) {
            return ref;
        }
        if (b instanceof RexInputRef ref && a instanceof RexLiteral) {
            return ref;
        }
        return null;
    }

    private static @Nullable RexInputRef asInputRef(RexNode node) {
        return node instanceof RexInputRef ref ? ref : null;
    }
}

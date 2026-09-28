package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptCostFactory;
import org.apache.calcite.plan.RelOptUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The engine's cost carrier — three components carried honestly, <b>comparison unchanged</b>
 * (rows only), which is the deliberate heart of the physical-placement design (A1).
 *
 * <p>The factory this replaces delegated to {@code RelOptCostImpl}, which does not merely ignore
 * cpu/io when comparing — it <em>discards</em> them at construction ({@code getCpu()}/{@code
 * getIo()} return 0), so nothing downstream of the planner could ever read a boundary cost off a
 * plan. This class keeps all three components so the placement machinery (and, in the spec's
 * phase 6, per-convention boundary costing) has a real carrier — while {@link #isLe}/{@link #isLt}
 * still order by {@code rows} alone, exactly as before.
 *
 * <p><b>Why comparison stays rows-only, stated once</b>: within DuckDB reach (T1/T2) the
 * snapshot-vs-live choice cannot change the plan's <em>shape</em> — both leaves produce identical
 * rows, join order is cardinality-driven, and the emitted SQL text is the same either way — so
 * folding a transfer term into Volcano's comparison would churn every plan golden to reorder
 * nothing. The transfer term instead prices the per-leaf placement decision
 * ({@link PlacementDecider}) computed from the <em>optimized</em> plan. The comparison flips to a
 * weighted form only when engine boundaries make placement shape-relevant (phase 6), behind a
 * deliberate golden re-derivation, not as a side effect.
 *
 * <p>{@code makeInfiniteCost} preserves the pre-existing 1e20 hack verbatim: a genuinely infinite
 * cost makes VolcanoPlanner discard Convention.NONE subtrees, and infinities NaN when added.
 */
@NullMarked
public final class AranCost implements RelOptCost {

    /** The factory {@code OqlPlanner} installs. One instance; costs are immutable values. */
    public static final RelOptCostFactory FACTORY = new Factory();

    private final double rows;
    private final double cpu;
    private final double io;

    private AranCost(double rows, double cpu, double io) {
        this.rows = rows;
        this.cpu = cpu;
        this.io = io;
    }

    @Override public double getRows() {
        return rows;
    }

    @Override public double getCpu() {
        return cpu;
    }

    @Override public double getIo() {
        return io;
    }

    @Override public boolean isInfinite() {
        return Double.isInfinite(rows) || Double.isInfinite(cpu) || Double.isInfinite(io);
    }

    // ── ordering: rows only, matching the RelOptCostImpl semantics this replaced ──

    @Override public boolean isLe(RelOptCost other) {
        return this == other || rows <= other.getRows();
    }

    @Override public boolean isLt(RelOptCost other) {
        return rows < other.getRows();
    }

    @Override public boolean equals(@Nullable RelOptCost other) {
        return other != null && rows == other.getRows();
    }

    @Override public boolean isEqWithEpsilon(RelOptCost other) {
        return Math.abs(rows - other.getRows()) < RelOptUtil.EPSILON;
    }

    @Override public boolean equals(@Nullable Object obj) {
        return obj instanceof AranCost that && rows == that.rows;
    }

    @Override public int hashCode() {
        return Double.hashCode(rows);
    }

    // ── arithmetic: all three components carried through ──

    @Override public RelOptCost plus(RelOptCost other) {
        return new AranCost(rows + other.getRows(), cpu + other.getCpu(), io + other.getIo());
    }

    @Override public RelOptCost minus(RelOptCost other) {
        return new AranCost(rows - other.getRows(), cpu - other.getCpu(), io - other.getIo());
    }

    @Override public RelOptCost multiplyBy(double factor) {
        return new AranCost(rows * factor, cpu * factor, io * factor);
    }

    @Override public double divideBy(RelOptCost cost) {
        // Same convention as RelOptCostImpl: the ratio of the ordering component.
        if (cost.getRows() != 0) {
            return rows / cost.getRows();
        }
        return 1.0;
    }

    @Override public String toString() {
        return "{" + rows + " rows, " + cpu + " cpu, " + io + " io}";
    }

    private static final class Factory implements RelOptCostFactory {
        @Override public RelOptCost makeCost(double rowCount, double cpu, double io) {
            return new AranCost(rowCount, cpu, io);
        }

        @Override public RelOptCost makeHugeCost() {
            return new AranCost(Double.MAX_VALUE / 4, 0, 0);
        }

        @Override public RelOptCost makeInfiniteCost() {
            // 1e20, not infinity, so VolcanoPlanner doesn't discard Convention.NONE and sums
            // never overflow to Infinity — the load-bearing hack the anonymous factory carried.
            return new AranCost(1e20, 1e20, 1e20);
        }

        @Override public RelOptCost makeTinyCost() {
            return new AranCost(1.0, 1.0, 0.0);
        }

        @Override public RelOptCost makeZeroCost() {
            return new AranCost(0.0, 0.0, 0.0);
        }
    }
}

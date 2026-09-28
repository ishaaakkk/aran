package edu.upc.dtak.aran.operators.queryengine.plan;

import com.google.common.collect.ImmutableList;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.util.ImmutableBitSet;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Calcite {@link Statistic} for a <em>single wrapper</em>, backed by that wrapper's own
 * freyja profile.
 *
 * <p>This is the statistic {@link AranStatistic} cannot be. An integrated class is a
 * UNION ALL over its wrappers, so {@code AranStatistic} has to sum row counts and — as its
 * own javadoc explains — must refuse to declare any key at all once a class has more than
 * one source, because the union can introduce duplicates even when a column is unique
 * within each source. The practical effect is that key-aware join estimation switches off
 * for precisely the classes integration exists to produce.
 *
 * <p>A wrapper has no such problem: it is one source by construction, so
 * {@code cardinality/uniqueness} row counts and exact single-column keys are both
 * meaningful. Attaching statistics here — rather than only above the union — is what lets
 * the planner reason about the union's members individually.
 *
 * <p>Ordinals index {@link WrapperTable#getRowType}'s column order, i.e. the class's global
 * property IRIs. A column the class declares but this source does not supply is NULL-padded in
 * the view body, so it resolves to no profile and can never be reported as a key.
 */
final class WrapperStatistic implements Statistic {

    private final MappingCatalog.SourceView source;
    /** The class's global property IRIs, in row-type order. */
    private final List<String> globalColumns;
    /** This member's class and the whole catalog — for {@link #getReferentialConstraints()}. */
    private final MappingCatalog.@Nullable IntegratedClass cls;
    private final @Nullable MappingCatalog catalog;

    WrapperStatistic(MappingCatalog.SourceView source, List<String> globalColumns) {
        this(source, globalColumns, null, null);
    }

    /**
     * As above, plus this member's class and catalog, so {@link #getReferentialConstraints()} can
     * expose the accepted joins as below-union {@code u_A → u_B} constraints. A {@code null}
     * class/catalog yields none.
     */
    WrapperStatistic(MappingCatalog.SourceView source, List<String> globalColumns,
                     MappingCatalog.@Nullable IntegratedClass cls, @Nullable MappingCatalog catalog) {
        this.source = source;
        this.globalColumns = globalColumns;
        this.cls = cls;
        this.catalog = catalog;
    }

    /** {@code null} (Calcite's "unknown") when the wrapper has not been profiled. */
    @Override
    public @Nullable Double getRowCount() {
        return source.rowCount() > 0 ? (double) source.rowCount() : null;
    }

    @Override
    public boolean isKey(ImmutableBitSet columns) {
        if (columns.isEmpty()) {
            return false;
        }
        for (int i : columns) {
            if (!isSingleColumnKey(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<ImmutableBitSet> getKeys() {
        List<ImmutableBitSet> keys = new java.util.ArrayList<>();
        for (int i = 0; i < globalColumns.size(); i++) {
            if (isSingleColumnKey(i)) {
                keys.add(ImmutableBitSet.of(i));
            }
        }
        return ImmutableList.copyOf(keys);
    }

    /**
     * Key evidence must be exact ({@link ColumnProfile#exactlyUnique()} — the single exactness
     * test shared with {@code AranStatistic}, so the bar cannot drift between the two). A
     * {@code HARVESTED}-subject profile (Phase 4B: PostgreSQL's approximate
     * {@code pg_stats.n_distinct}) never testifies to a key regardless of its value; its
     * cardinality/NDV still feed {@link WrapperTable#columnNdv} unchanged — only key detection is
     * gated. The loop {@code continue}s past a HARVESTED row rather than returning, because since
     * V36 a HARVESTED and a SNAPSHOT generation can coexist for one physical column and list
     * order must not decide whether the exact evidence is seen.
     */
    private boolean isSingleColumnKey(int ordinal) {
        if (ordinal < 0 || ordinal >= globalColumns.size()) {
            return false;
        }
        // A global column this source does not supply is a NULL literal in the view body —
        // no profile, and certainly not a key.
        String sourceColumn = source.columnMap().get(globalColumns.get(ordinal));
        if (sourceColumn == null) {
            return false;
        }
        for (ColumnProfile cp : source.profiles()) {
            if (!cp.columnName().equals(sourceColumn)) {
                continue;
            }
            if (!cp.admissibleAsKeyEvidence()) {
                continue; // an estimate (HARVESTED / FEDERATED_LIVE) — keep scanning for exact evidence
            }
            return cp.exactlyUnique();
        }
        return false;
    }

    /**
     * The accepted joins whose referencing side is this member's class, as below-union
     * {@code u_A → u_B} constraints — but only to a target class with a single source, the one
     * shape where a {@code u_} view is a sound referent (see {@link ReferentialConstraints}).
     * Tier-tagged {@code HYPOTHESIS}; empty when class or catalog is absent. Same estimate-safety
     * as {@link AranStatistic#getReferentialConstraints()}.
     */
    @Override
    public List<RelReferentialConstraint> getReferentialConstraints() {
        if (cls == null || catalog == null) {
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(ReferentialConstraints.forWrapperMember(catalog, cls, source));
    }

    @Override
    public List<RelCollation> getCollations() {
        return ImmutableList.of();
    }
}

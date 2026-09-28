package edu.upc.dtak.aran.operators.queryengine.plan;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.util.ImmutableBitSet;
import org.jspecify.annotations.Nullable;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;

import java.util.List;
import java.util.Map;

/**
 * Calcite {@link Statistic} backed by Aran profiling data.
 */
public final class AranStatistic implements Statistic {

    private final @Nullable Double rowCount;
    private final MappingCatalog.IntegratedClass cls;
    /** The whole catalog — needed to resolve a referential edge's target class; null → no RI. */
    private final @Nullable MappingCatalog catalog;

    AranStatistic(long rowCount, MappingCatalog.IntegratedClass cls) {
        this(rowCount, cls, null);
    }

    /**
     * As above, plus the catalog the class came from, so {@link #getReferentialConstraints()} can
     * resolve each accepted join's target class. A {@code null} catalog (or class) yields no
     * constraints.
     */
    AranStatistic(long rowCount, MappingCatalog.IntegratedClass cls,
                  @Nullable MappingCatalog catalog) {
        this.rowCount = rowCount > 0 ? (double) rowCount : null;
        this.cls = cls;
        this.catalog = catalog;
    }

    /** Returns {@code Statistics.UNKNOWN}-equivalent (row count not profiled). */
    static AranStatistic unknown() {
        return new AranStatistic(0L, null);
    }

    @Override
    public @Nullable Double getRowCount() {
        return rowCount;
    }

    /**
     * Returns {@code true} if every column in {@code columns} is a mathematically verified
     * single-column key. Freyja uses exact COUNT(DISTINCT col), so uniqueness == 1.0 is exact —
     * a {@code HARVESTED}-subject profile (Phase 4B: {@code pg_stats}' approximate
     * {@code n_distinct}) is excluded from this check entirely, regardless of its uniqueness
     * value, precisely because it is <em>not</em> exact (see {@link #isSingleColumnKey}).
     * Multi-source classes never declare a key: the UNION ALL can introduce duplicates even
     * when the column is unique within each source.
     */
    @Override
    public boolean isKey(ImmutableBitSet columns) {
        List<String> colNames = keyColumnOrder();
        if (colNames.isEmpty()) {
            return false;
        }
        for (int i : columns) {
            if (i < 0 || i >= colNames.size() || !isSingleColumnKey(i, colNames)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<ImmutableBitSet> getKeys() {
        List<String> colNames = keyColumnOrder();
        List<ImmutableBitSet> keys = new java.util.ArrayList<>();
        for (int i = 0; i < colNames.size(); i++) {
            if (isSingleColumnKey(i, colNames)) {
                keys.add(ImmutableBitSet.of(i));
            }
        }
        return ImmutableList.copyOf(keys);
    }

    /**
     * The class row-type column order (global-IRI names) — the same order
     * {@link IntegratedClassTable#getRowType} builds, which the bitset ordinals index.
     * Empty when the class is absent or has more than one source (no declarable key).
     */
    private List<String> keyColumnOrder() {
        if (cls == null || cls.sources().size() != 1) {
            return List.of();
        }
        MappingCatalog.SourceView source = cls.sources().get(0);
        if (source.profiles() == null || source.profiles().isEmpty()) {
            return List.of();
        }
        return cls.sources().stream()
                .flatMap(s -> s.columnMap().keySet().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Whether the column at ordinal {@code i} (in {@code colNames} order) maps to a source
     * column with exact freyja uniqueness ({@link ColumnProfile#exactlyUnique()} — the single
     * exactness test both statistics share, so the bar cannot drift between them). Assumes a
     * single-source class (callers use {@link #keyColumnOrder()}, which returns empty otherwise).
     *
     * <p>A {@code HARVESTED}-subject profile (Phase 4B: {@code HarvestedColumnProfiles} derives
     * {@code uniqueness} from PostgreSQL's {@code pg_stats.n_distinct}, an ANALYZE-based estimate
     * that over-reports distinctness on skewed/large tables) is never eligible here, regardless of
     * its own uniqueness value — only freyja's exact {@code COUNT(DISTINCT)} subjects
     * (SNAPSHOT/WRAPPER_OUTPUT) can testify to a key. Without this guard a genuinely non-unique
     * HARVESTED column could read as a key by estimation error — harmless while keys are
     * cost-input-only, but join elimination needs a referenced-side key that is actually exact,
     * and a false key plus a correct DECLARED edge would eliminate a join unsafely (wrong
     * results, not just a worse plan). HARVESTED profiles stay estimate-only: their
     * cardinality/NDV still feed {@link AranRelMdDistinctRowCount}/{@link WrapperTable#columnNdv}
     * unchanged — only key detection is gated. The loop {@code continue}s past a HARVESTED row
     * rather than returning, because since V36 a HARVESTED and a SNAPSHOT generation can coexist
     * for one physical column and list order must not decide whether the exact evidence is seen.
     */
    private boolean isSingleColumnKey(int i, List<String> colNames) {
        MappingCatalog.SourceView source = cls.sources().get(0);
        String sourceCol = source.columnMap().get(colNames.get(i));
        if (sourceCol == null) {
            return false;
        }
        for (ColumnProfile cp : source.profiles()) {
            if (!cp.columnName().equals(sourceCol)) {
                continue;
            }
            if (!cp.admissibleAsKeyEvidence()) {
                // HARVESTED (pg_stats) or FEDERATED_LIVE (exact at profile time, decaying after):
                // their NDV/row-count evidence flows, key testimony never. Keep scanning — since
                // V36 an estimate and an exact SNAPSHOT generation can coexist for one column, and
                // list order must not decide whether the exact evidence is seen.
                continue;
            }
            return cp.exactlyUnique();
        }
        return false;
    }

    /**
     * The accepted joins whose referencing side is this class, as {@code int_A → int_B}
     * referential constraints tier-tagged {@code HYPOTHESIS} (see {@link ReferentialConstraints}).
     * Empty when the class or catalog is absent. Exposing these does not, on its own, change any
     * estimate — no metadata handler in the chain reads referential constraints — and cannot drive
     * join elimination: the tier keeps a hypothesis estimate-only until a rule opts to trust it.
     */
    @Override
    public List<RelReferentialConstraint> getReferentialConstraints() {
        if (cls == null || catalog == null) {
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(ReferentialConstraints.forIntegratedClass(catalog, cls));
    }

    @Override
    public List<RelCollation> getCollations() {
        return ImmutableList.of();
    }
}

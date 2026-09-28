package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.impl.AbstractTable;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Calcite {@link org.apache.calcite.schema.Table} for one wrapper's <em>raw</em>
 * {@code src_<sanitize(datasetId)>} relation — the physical snapshot columns the wrapper's
 * {@code ${SNAPSHOT}} placeholder stands for, before the wrapper's own SELECT renames and
 * projects them into the class's global-property-IRI-named output.
 *
 * <p>Exists only for {@link WrapperExpansion#probe}: that probe plans a wrapper's full SELECT
 * statement, so the one-table schema it plans against must expose the columns the wrapper text
 * itself references (raw physical names, e.g. {@code "amount_usd"}) — not the class's global
 * property IRIs {@link WrapperTable}/{@link IntegratedClassTable} expose. Those two tables
 * model what a wrapper <em>produces</em>; this one models what its {@code FROM} clause reads.
 *
 * <p>Columns come from {@link MappingCatalog.SourceView#sourceRefs()} (raw column name →
 * source-property IRI, recorded since V31) with a pre-V31 fallback: any
 * {@link MappingCatalog.SourceView#columnMap()} value {@code sourceRefs} does not already
 * account for is added as-is, on the assumption a graph old enough to have no {@code sourceRefs}
 * at all used {@code columnMap}'s value as the raw name directly (see that record's javadoc).
 * Never fatal either way — a name that turns out wrong simply fails the probe's validation,
 * which is the correct "stays OPAQUE" outcome for a wrapper this catalog cannot fully explain.
 */
final class SrcTable extends AbstractTable implements ScannableTable, ProfileBackedTable {

    private final MappingCatalog.SourceView source;

    /** As {@code WrapperTable.source()}: leaf identity for {@code PlacementDecider}'s unwrap —
     * an EXPANDED member's plan bottoms out here rather than in a {@code WrapperTable}. */
    MappingCatalog.SourceView source() {
        return source;
    }

    /** Raw physical column names, sorted for a stable, deterministic row type. */
    private final List<String> columns;

    SrcTable(MappingCatalog.SourceView source) {
        this.source = source;
        this.columns = rawColumns(source);
    }

    private static List<String> rawColumns(MappingCatalog.SourceView source) {
        Set<String> names = new TreeSet<>(source.sourceRefs().keySet());
        Set<String> coveredBySourceRef = new HashSet<>(source.sourceRefs().values());
        for (String columnMapValue : source.columnMap().values()) {
            if (!coveredBySourceRef.contains(columnMapValue)) {
                names.add(columnMapValue);
            }
        }
        return List.copyOf(names);
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (String column : columns) {
            String propertyIri = source.sourceRefs().get(column);
            String xsdRange = propertyIri == null ? null : source.columnTypes().get(propertyIri);
            builder.add(column, typeFactory.createTypeWithNullability(
                    typeFactory.createSqlType(XsdTypes.fromXsd(xsdRange)), true));
        }
        return builder.build();
    }

    /**
     * Row count only — a {@code src_} relation is a raw snapshot, not a class with keys to
     * declare, so {@link Statistics#of} rather than a bespoke {@link Statistic} implementation.
     * {@code rowCount() <= 0} means "not profiled" ({@link MappingCatalog.SourceView#rowCount()}),
     * which Calcite reads as "unknown" via a {@code null} row count, not zero rows — the same
     * guard {@link WrapperTable}/{@link IntegratedClassTable} apply.
     */
    @Override
    public Statistic getStatistic() {
        Double rowCount = source.rowCount() > 0 ? (double) source.rowCount() : null;
        return Statistics.of(rowCount, List.of(), List.of(), List.of());
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        throw new UnsupportedOperationException("Not executed by Calcite");
    }

    /**
     * Exact NDV for the raw column at {@code ordinal}, or {@code null}.
     *
     * <p>{@code cardinality <= 0} is treated as no evidence at all, not "zero distinct values" —
     * the same reading {@link WrapperTable#columnNdv} and {@link IntegratedClassTable#columnNdv}
     * give it.
     */
    @Override
    public @Nullable Double columnNdv(int ordinal) {
        ColumnProfile profile = profileAt(ordinal);
        return profile == null || profile.cardinality() <= 0 ? null : (double) profile.cardinality();
    }

    /** Null fraction for the raw column at {@code ordinal}, or {@code null} — see {@link #columnNdv}. */
    @Override
    public @Nullable Double columnNullFraction(int ordinal) {
        ColumnProfile profile = profileAt(ordinal);
        return profile == null ? null : profile.incompleteness();
    }

    /**
     * Ordinal (in {@link #columns} order) → this source's own {@link ColumnProfile}.
     *
     * <p>Resolves raw name → {@code sourceRefs} → source-property IRI → profile, because a
     * freyja profile's {@code columnName} is renamed into that same IRI namespace by
     * {@code MappingCatalogOperator.remapProfiles} once a graph has {@code nx:sourceRef}
     * triples at all (post-V31) — mirroring {@link WrapperTable#profileAt}'s resolution path,
     * one hop earlier (raw name rather than global column).
     *
     * <p>Matches {@code SNAPSHOT}-subject profiles only (Phase 3B): {@link MappingCatalog.SourceView#profiles()}
     * can now hold an OPAQUE class's own {@code WRAPPER_OUTPUT} rows instead
     * ({@code MappingCatalogOperator}'s selection rule), which are evidence about the wrapper's
     * <em>output</em> relation and are already named by property IRI — the exact shape that could
     * coincidentally equal {@code propertyIri} here and be mistaken for evidence about this table's
     * raw {@code src_} relation, which is what {@link WrapperExpansion#probe} needs to trust this
     * class for.
     */
    private @Nullable ColumnProfile profileAt(int ordinal) {
        if (ordinal < 0 || ordinal >= columns.size()) {
            return null;
        }
        String propertyIri = source.sourceRefs().get(columns.get(ordinal));
        if (propertyIri == null) {
            return null;
        }
        for (ColumnProfile cp : source.profiles()) {
            if ("SNAPSHOT".equals(cp.subject()) && cp.columnName().equals(propertyIri)) {
                return cp;
            }
        }
        return null;
    }
}

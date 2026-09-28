package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.rel.type.RelDataType;

import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.impl.AbstractTable;
import org.jspecify.annotations.Nullable;

import java.util.List;

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.DataContext;

/**
 * Calcite {@link org.apache.calcite.schema.Table} for one integrated class.
 *
 * Column names are the global property IRIs (same as the {@code int_} view column names in
 * DuckDB, which are always IRI strings quoted with {@code "}). Column types come from
 * {@link XsdTypes#widenedType}, which folds every source's recorded {@code rdfs:range} across
 * the whole class and falls back to VARCHAR on a missing or disagreeing range; every column
 * stays nullable regardless of type, since a union member NULL- (or typed-NULL-) pads any
 * property it does not itself supply.
 *
 * Statistics are {@link Statistics#UNKNOWN} until Phase 11 wires in the profiling operator.
 */
final class IntegratedClassTable extends AbstractTable
        implements ScannableTable, ProfileBackedTable, PredicateBackedTable {

    private final MappingCatalog.IntegratedClass cls;
    private final List<RexNode> pulledUpPredicates;
    /** The catalog this class came from — threaded to {@link AranStatistic} for referential
     *  constraints; null when a caller builds a table without one (no constraints then). */
    private final @Nullable MappingCatalog catalog;

    IntegratedClassTable(MappingCatalog.IntegratedClass cls) {
        this(cls, List.of());
    }

    /** @param pulledUpPredicates the class's enforced denial constraints, parsed — the same
     *         conjunction {@code DuckDbExecutor.buildDdl} puts in this view's own body. */
    IntegratedClassTable(MappingCatalog.IntegratedClass cls, List<RexNode> pulledUpPredicates) {
        this(cls, pulledUpPredicates, null);
    }

    /** @param catalog the catalog {@code cls} belongs to, so the statistic can expose accepted
     *         joins as referential constraints; null builds a table with none. */
    IntegratedClassTable(MappingCatalog.IntegratedClass cls, List<RexNode> pulledUpPredicates,
                         @Nullable MappingCatalog catalog) {
        this.cls = cls;
        this.pulledUpPredicates = List.copyOf(pulledUpPredicates);
        this.catalog = catalog;
    }

    @Override
    public List<RexNode> pulledUpPredicates() {
        return pulledUpPredicates;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        List<String> columns = cls.sources().stream()
                .flatMap(s -> s.columnMap().keySet().stream())
                .distinct()
                .sorted()
                .toList();

        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (String iri : columns) {
            builder.add(iri, typeFactory.createTypeWithNullability(
                    typeFactory.createSqlType(XsdTypes.widenedType(cls, iri)), true));
        }
        return builder.build();
    }

    @Override
    public Statistic getStatistic() {
        long totalRows = cls.sources().stream()
                .mapToLong(MappingCatalog.SourceView::rowCount)
                .sum();
        return new AranStatistic(totalRows, cls, catalog);
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        throw new UnsupportedOperationException("Not executed by Calcite");
    }

    /**
     * Exact NDV for a single-source class's column, or {@code null}.
     *
     * <p>Deliberately {@code null} once a class has more than one source: a UNION ALL can
     * introduce or remove duplicates that neither source's own profile accounts for, so this
     * class's NDV is not the sum, the max, nor either source's own count — it is simply not
     * knowable from per-source profiles alone. Same safety property {@link AranStatistic}
     * already enforces for keys, for the identical reason.
     */
    @Override
    public @Nullable Double columnNdv(int ordinal) {
        ColumnProfile profile = soleSourceProfileAt(ordinal);
        // cardinality <= 0 is what an all-NULL (or never-profiled) column reports — "no
        // evidence", not "zero distinct rows" — so it is dropped exactly like a missing
        // profile, same rule and reason as WrapperTable.columnNdv.
        return profile == null || profile.cardinality() <= 0 ? null : (double) profile.cardinality();
    }

    /** Null fraction for a single-source class's column, or {@code null} — see {@link #columnNdv}. */
    @Override
    public @Nullable Double columnNullFraction(int ordinal) {
        ColumnProfile profile = soleSourceProfileAt(ordinal);
        return profile == null ? null : profile.incompleteness();
    }

    /**
     * Ordinal → this class's sole source's {@link ColumnProfile}, mirroring
     * {@link WrapperStatistic#isSingleColumnKey}'s resolution path. Returns {@code null}
     * unconditionally once {@code cls} has more than one source (the safety property above).
     */
    private @Nullable ColumnProfile soleSourceProfileAt(int ordinal) {
        List<MappingCatalog.SourceView> sources = cls.sources();
        if (sources.size() != 1) {
            return null;
        }
        MappingCatalog.SourceView source = sources.get(0);
        List<String> globalColumns = WrapperTable.globalColumns(cls);
        if (ordinal < 0 || ordinal >= globalColumns.size()) {
            return null;
        }
        String sourceColumn = source.columnMap().get(globalColumns.get(ordinal));
        if (sourceColumn == null) {
            return null;
        }
        for (ColumnProfile cp : source.profiles()) {
            if (cp.columnName().equals(sourceColumn)) {
                return cp;
            }
        }
        return null;
    }
}

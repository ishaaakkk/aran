package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.impl.AbstractTable;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Calcite {@link org.apache.calcite.schema.Table} for one <em>union member</em> — a single
 * source's projected contribution to an integrated class, materialised in DuckDB as
 * {@code u_<sanitize(datasetId)>_<sanitize(classIri)>}.
 *
 * <p>Exists so the UNION ALL can live in the RelNode tree rather than only inside the
 * {@code int_} view definition. While an integrated class is one opaque
 * {@link IntegratedClassTable} scan, the planner cannot attribute cost to individual
 * sources, cannot push a predicate into one source but not another, and cannot see a key
 * that holds per source — every per-wrapper profile freyja computes is aggregated away
 * before Calcite sees it.
 *
 * <p><b>Columns are the class's full global-IRI vector</b>, identical to
 * {@link IntegratedClassTable#getRowType}, including properties this source does not
 * supply (NULL-padded in the {@code u_} view body). That makes every member of the union
 * type-aligned by construction, which is what {@code LogicalUnion} requires.
 *
 * <p>Statistics, by contrast, are emphatically <em>not</em> shared with the class: they are
 * this source's own (see {@link WrapperStatistic}).
 */
final class WrapperTable extends AbstractTable
        implements ScannableTable, ProfileBackedTable, PredicateBackedTable {

    private final MappingCatalog.SourceView source;
    private final MappingCatalog.IntegratedClass cls;
    private final List<RexNode> pulledUpPredicates;

    /**
     * The member's catalog identity, for {@code RelOptTable.unwrap(WrapperTable.class)} — the
     * same recovery pattern {@code ProfileBackedTable}/{@code PredicateBackedTable} established.
     * {@link PlacementDecider} walks the optimized plan's leaves with it; parsing the identity
     * back out of the lossy {@code u_…} view name is exactly what this avoids.
     */
    MappingCatalog.SourceView source() {
        return source;
    }

    /**
     * The class's global column vector and this member's statistic, computed once.
     *
     * <p>Calcite asks a table for its statistic repeatedly while planning — every
     * {@code getRowCount} and {@code areColumnsUnique} call on a scan goes through it — and both
     * {@link #getRowType} and {@link #getStatistic} were re-deriving the vector by streaming,
     * de-duplicating and sorting every source's key set on each call.
     */
    private final List<String> globalColumns;
    private final Statistic statistic;

    WrapperTable(MappingCatalog.SourceView source, MappingCatalog.IntegratedClass cls) {
        this(source, cls, List.of());
    }

    WrapperTable(MappingCatalog.SourceView source, MappingCatalog.IntegratedClass cls,
                 List<RexNode> pulledUpPredicates) {
        this(source, cls, pulledUpPredicates, null);
    }

    /**
     * @param pulledUpPredicates the class's enforced denial constraints, parsed. Correct on a
     *        <em>member</em> and not only on {@code int_} because {@code DuckDbExecutor.buildDdl}
     *        applies the same conjunction to each member's body: selection distributes over union,
     *        σ(A ∪ B) = σ(A) ∪ σ(B), bag semantics included.
     * @param catalog the catalog {@code cls} belongs to, so {@link WrapperStatistic} can expose the
     *        accepted joins as below-union referential constraints; null builds a member with none.
     */
    WrapperTable(MappingCatalog.SourceView source, MappingCatalog.IntegratedClass cls,
                 List<RexNode> pulledUpPredicates, @Nullable MappingCatalog catalog) {
        this.source = source;
        this.cls = cls;
        this.globalColumns = globalColumns(cls);
        this.statistic = new WrapperStatistic(source, this.globalColumns, cls, catalog);
        this.pulledUpPredicates = List.copyOf(pulledUpPredicates);
    }

    @Override
    public List<RexNode> pulledUpPredicates() {
        return pulledUpPredicates;
    }

    /**
     * The DuckDB view name for this union member, which must match
     * {@code DuckDbExecutor.buildDdl}'s {@code "u_" + dsHash + "_" + classHash} exactly —
     * {@code RelToSqlConverter} emits this name verbatim, so a mismatch produces SQL
     * referencing a view that does not exist.
     */
    static String viewName(MappingCatalog.SourceView source, MappingCatalog.IntegratedClass cls) {
        return "u_" + Names.sqlName(source.datasetId()) + "_" + Names.sqlName(cls.iri());
    }

    /**
     * The class's global property IRIs, sorted — the same order
     * {@link IntegratedClassTable#getRowType} and {@code DuckDbExecutor}'s projection use.
     * Every member of a class shares this vector.
     */
    static List<String> globalColumns(MappingCatalog.IntegratedClass cls) {
        return cls.sources().stream()
                .flatMap(s -> s.columnMap().keySet().stream())
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        return rowType(cls, globalColumns, typeFactory);
    }

    /**
     * The row type every member of {@code cls} exposes, without needing a member instance.
     *
     * <p>Exists because {@code OqlPlanner} builds a member that is <em>not</em> a scan of this
     * table — an EXPANDED wrapper's own plan over its {@code src_} relation, with a renaming
     * projection on top — and that projection's row type has to be this one exactly, or the
     * UNION ALL over the class's members is not type-aligned. One implementation, two callers,
     * rather than two that can drift apart.
     */
    static RelDataType rowType(MappingCatalog.IntegratedClass cls, RelDataTypeFactory typeFactory) {
        return rowType(cls, globalColumns(cls), typeFactory);
    }

    private static RelDataType rowType(MappingCatalog.IntegratedClass cls,
                                       List<String> globalColumns, RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (String globalIri : globalColumns) {
            builder.add(globalIri, typeFactory.createTypeWithNullability(
                    typeFactory.createSqlType(XsdTypes.widenedType(cls, globalIri)), true));
        }
        return builder.build();
    }

    /** Per-source statistics — the point of this class. */
    @Override
    public Statistic getStatistic() {
        return statistic;
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        throw new UnsupportedOperationException("Not executed by Calcite");
    }

    /**
     * Exact NDV ({@code ColumnProfile.cardinality}) for the column at {@code ordinal}, or null.
     *
     * <p>{@code cardinality <= 0} is treated the same as no profile at all: it is what an
     * all-NULL (or never-profiled) column reports, not "zero distinct rows" — handing it to
     * Calcite as a literal 0/negative NDV would fabricate a precise-looking fact ("exactly this
     * many distinct values") the profile does not actually support.
     */
    @Override
    public @Nullable Double columnNdv(int ordinal) {
        ColumnProfile profile = profileAt(ordinal);
        return profile == null || profile.cardinality() <= 0 ? null : (double) profile.cardinality();
    }

    /** Null fraction ({@code ColumnProfile.incompleteness}) for {@code ordinal}, or null. */
    @Override
    public @Nullable Double columnNullFraction(int ordinal) {
        ColumnProfile profile = profileAt(ordinal);
        return profile == null ? null : profile.incompleteness();
    }

    /**
     * Ordinal (in {@link #globalColumns} order) → this source's own {@link ColumnProfile},
     * mirroring {@link WrapperStatistic#isSingleColumnKey}'s resolution path exactly: a global
     * column this source does not supply is NULL-padded in the view body, and a mapped column
     * this source has never been profiled for has no {@link ColumnProfile} entry — both are
     * "no evidence", not zero.
     */
    private @Nullable ColumnProfile profileAt(int ordinal) {
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

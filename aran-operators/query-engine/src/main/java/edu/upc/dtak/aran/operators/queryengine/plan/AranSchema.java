package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.tools.Frameworks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcite schema derived from a {@link MappingCatalog}.
 *
 * One table per integrated class, named {@code int_<sanitize(classIri)>} — exactly the
 * same names {@code DuckDbExecutor} materialises, so {@code RelToSqlConverter} references
 * views that actually exist.
 *
 * Tables are registered directly on the root SchemaPlus (not in a named sub-schema) so
 * {@code RelToSqlConverter} emits {@code "int_Artworks"} rather than
 * {@code "aran"."int_Artworks"}.
 */
public final class AranSchema extends AbstractSchema {

    private AranSchema() {}

    public static SchemaPlus create(MappingCatalog catalog) {
        return create(catalog, Map.of());
    }

    /**
     * As above, but each class named in {@code predicatesByClass} hands its tables the predicates
     * every row they yield is known to satisfy — the class's enforced denial constraints, already
     * parsed by {@link OqlPlanner} (see {@link AranRelMdPredicates}).
     *
     * <p>Keyed by integrated-class IRI, and applied to <em>both</em> levels of the unfolding
     * below: the constraint is spliced into each {@code u_} member's body as well as into
     * {@code int_}, because selection distributes over union.
     */
    public static SchemaPlus create(MappingCatalog catalog,
                                    Map<String, List<RexNode>> predicatesByClass) {
        SchemaPlus root = Frameworks.createRootSchema(false);
        buildTableMap(catalog, predicatesByClass).forEach((name, table) -> root.add(name, table));
        return root;
    }

    /** Package-visible so tests can call it without a schema instance. */
    static Map<String, Table> buildTableMap(MappingCatalog catalog) {
        return buildTableMap(catalog, Map.of());
    }

    /**
     * Registers both levels of the GAV unfolding:
     * <ul>
     *   <li>{@code int_<class>} — the integrated class, statistics summed across its sources.</li>
     *   <li>{@code u_<dataset>_<class>} — one per union member, each carrying that source's
     *       own freyja-derived row count and keys ({@link WrapperStatistic}).</li>
     * </ul>
     *
     * <p>Both are registered because both are real DuckDB views, and a plan may reference
     * either. The wrapper tables are what allow the UNION ALL to be expressed in the RelNode
     * tree instead of hidden inside the {@code int_} view definition, which is the only way
     * per-source statistics can reach the cost model.
     */
    static Map<String, Table> buildTableMap(MappingCatalog catalog,
                                            Map<String, List<RexNode>> predicatesByClass) {
        Map<String, Table> tables = new LinkedHashMap<>();
        for (MappingCatalog.IntegratedClass cls : catalog.classes().values()) {
            List<RexNode> predicates = predicatesByClass.getOrDefault(cls.iri(), List.of());
            tables.put("int_" + Names.sqlName(cls.iri()),
                    new IntegratedClassTable(cls, predicates, catalog));
            for (MappingCatalog.SourceView source : cls.sources()) {
                tables.put(WrapperTable.viewName(source, cls),
                        new WrapperTable(source, cls, predicates, catalog));
            }
        }
        return tables;
    }
}

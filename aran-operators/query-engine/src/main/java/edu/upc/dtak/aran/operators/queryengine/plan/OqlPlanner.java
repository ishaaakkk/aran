package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.util.ConstraintRewriter;
import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.AliasedQuery;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlLowerer;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlQuery;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.TimestampString;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lowers an {@link OqlQuery} to a Calcite {@link RelNode} logical plan.
 *
 * <p>Non-aggregate queries produce: Scan → [Join] → [Filter] → Project → [Limit]
 * <p>Aggregate queries produce:     Scan → [Join] → [Filter] → Aggregate → [HavingFilter] → [Limit]
 *
 * <p>Optimization and SQL emission happen in {@link PlanExecutor}.
 */
public final class OqlPlanner {

    private static final Logger log = LoggerFactory.getLogger(OqlPlanner.class);

    public static final class OqlPlanException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public OqlPlanException(String message) { super(message); }
        public OqlPlanException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * What an output column <em>is</em>, as the query itself declares it.
     *
     * <p>Not a guess from the values. A chart that infers "this column is a category" by looking
     * at types cannot tell {@code SELECT Paper.title, Paper.year} (a list of papers) from
     * {@code SELECT Paper.venue, COUNT(*) … GROUP BY} (a comparison across venues) — both are
     * text-then-number — and drawing the first as a bar chart asserts a comparison that does not
     * exist. The query knows the difference; this carries it downstream.
     */
    public enum ColumnRole {
        /** A GROUP BY key: the thing being compared across. */
        KEY,
        /** An aggregate: the magnitude being compared. */
        MEASURE,
        /** A plain projected column — one row per source row, no grouping. */
        VALUE
    }

    private List<ColumnRole> columnRoles = List.of();

    /**
     * The role of each output column of the last {@link #lower} call, in output order.
     *
     * <p>Populated during lowering rather than re-derived from the AST afterwards, so it cannot
     * drift from the projection the planner actually built — the aggregate branch emits
     * {@code [group keys…, aggregates…]}, which is <em>not</em> the SELECT list's order.
     */
    public List<ColumnRole> columnRoles() {
        return columnRoles;
    }

    private final MappingCatalog catalog;
    private final SchemaPlus schema;
    /**
     * Every catalog entry under its local name — a list, because two may share one. A shared
     * name is refused when a query <em>names</em> it ({@link #requireClass},
     * {@link #requireJoin}), never at construction: refusing there made one ambiguous name
     * anywhere in the tenant fail every query, including those that never mention it (S6-16).
     * Same rule {@link #requireProperty} already applies to properties (F12).
     */
    private final Map<String, List<MappingCatalog.IntegratedClass>> classesByLocal = new LinkedHashMap<>();
    private final Map<String, List<MappingCatalog.JoinProperty>>   joinsByLocal   = new LinkedHashMap<>();

    /**
     * The enforced denial constraints <em>as written</em>, kept rather than only parsed.
     *
     * <p>{@link #parseEnforced} drops any constraint it cannot lower, by design — a dropped
     * constraint costs estimate quality and nothing else, because enforcement lives in the view
     * bodies. {@link #pushExpandedMember} needs the opposite question answered: <em>does this
     * class have any enforced constraint at all</em>, parseable or not. Asking the parsed map
     * would answer "no" for a class whose only constraint failed to parse, and expansion would
     * then quietly read past a filter that is genuinely being enforced.
     */
    private final Map<String, List<String>> enforcedByClass;

    /** No enforced constraints reach the planner — estimates and rewrites as before. */
    public OqlPlanner(MappingCatalog catalog) {
        this(catalog, Map.of());
    }

    /**
     * @param enforcedByClass integrated-class IRI → that class's enforced denial-constraint
     *        expressions, authored against global-property <em>local</em> names. Exactly the map
     *        {@code QueryEngineOperator} already computes for
     *        {@code DuckDbExecutor.buildDdl} — one computation, two consumers, so the planner
     *        cannot come to believe a predicate the view bodies do not enforce.
     */
    public OqlPlanner(MappingCatalog catalog, Map<String, List<String>> enforcedByClass) {
        this.catalog = catalog;
        this.enforcedByClass = Map.copyOf(enforcedByClass);
        this.schema  = AranSchema.create(catalog, parseEnforced(catalog, enforcedByClass));
        for (MappingCatalog.IntegratedClass c : catalog.classes().values()) {
            classesByLocal.computeIfAbsent(Names.localName(c.iri()), k -> new ArrayList<>()).add(c);
        }
        for (MappingCatalog.JoinProperty j : catalog.joinProperties()) {
            joinsByLocal.computeIfAbsent(Names.localName(j.iri()), k -> new ArrayList<>()).add(j);
        }
    }

    public RelNode lower(OqlQuery q) {
        try {
            return lowerAliased(OqlLowerer.lower(q));
        } catch (OqlLowerer.OqlLowerException e) {
            throw new OqlPlanException(e.getMessage(), e);
        }
    }

    // ── Enforced denial constraints → planner predicates ──────────────────────

    /**
     * Parses each class's enforced constraints into {@link RexNode}s the metadata layer can hand
     * to the planner ({@link AranRelMdPredicates}), keyed by integrated-class IRI.
     *
     * <p><b>How, and why this way.</b> A constraint is a SQL boolean expression written against
     * local property names; the planner needs it as a {@code RexNode} over a specific row type.
     * Rather than build one by hand — which would mean reimplementing operator resolution, type
     * coercion and literal parsing, and getting a slightly different answer than the SQL that
     * actually runs — each constraint is planned as a throwaway probe query,
     * {@code SELECT * FROM "int_<Class>" WHERE <rewritten>}, over a schema built from the same
     * catalog. The {@code LogicalFilter}'s condition <em>is</em> the answer, with the ordinals
     * already indexing the row type the real scan will have.
     *
     * <p>The probe runs against its own {@link AranSchema} because the real one cannot exist yet
     * — it is what these predicates are being attached to. That is safe: the two schemas are
     * built from the same catalog, so the row types are identical, and Calcite interns
     * {@code RelDataType} instances in a static cache shared by every type factory, so the types
     * inside the {@code RexNode} are the very objects the query's own cluster will use.
     *
     * <p><b>A constraint that cannot be parsed is dropped, never fatal.</b> Enforcement lives in
     * the view bodies and is untouched by anything here, so a dropped constraint costs estimate
     * quality and nothing else. The failures are real: a DuckDB-specific function Calcite's
     * parser does not know, a local name the catalog no longer has, or simply invalid SQL a user
     * saved. None of them is a reason to fail a query that would otherwise answer correctly, so
     * each is a WARN and one fewer predicate.
     */
    private static Map<String, List<RexNode>> parseEnforced(
            MappingCatalog catalog, Map<String, List<String>> enforcedByClass) {
        if (enforcedByClass.isEmpty()) {
            return Map.of();
        }
        SchemaPlus probeSchema = AranSchema.create(catalog);
        Map<String, List<RexNode>> byClass = new LinkedHashMap<>();
        for (MappingCatalog.IntegratedClass cls : catalog.classes().values()) {
            List<String> expressions = enforcedByClass.getOrDefault(cls.iri(), List.of());
            List<String> columns = WrapperTable.globalColumns(cls);
            if (expressions.isEmpty() || columns.isEmpty()) {
                continue;
            }
            Map<String, String> localToIri = ConstraintRewriter.localNameToIri(columns);
            String viewName = "int_" + Names.sqlName(cls.iri());
            List<RexNode> parsed = new ArrayList<>();
            for (String expression : expressions) {
                RexNode predicate = probeConstraint(probeSchema, viewName, expression, localToIri);
                if (predicate != null) {
                    parsed.add(predicate);
                }
            }
            if (!parsed.isEmpty()) {
                byClass.put(cls.iri(), List.copyOf(parsed));
            }
        }
        return Map.copyOf(byClass);
    }

    /**
     * One constraint through the probe. {@code null} — always logged — means "drop it".
     *
     * <p>One throwaway {@link Planner} per constraint rather than one per class: a class's
     * constraints could be ANDed into a single probe and split again with
     * {@code RelOptUtil.conjunctions}, but then one unparseable constraint would drop the
     * class's others with it. Per-constraint degradation is worth the extra parse.
     *
     * <p>Nothing here executes: the probe is parsed, validated and converted, never planned or
     * run. A constraint that reaches this method has already passed
     * {@code QueryEngineOperator.rejectConstraintExpression}, and anything that somehow had not
     * would fail validation against a schema holding nothing but this catalog's own views.
     */
    private static @Nullable RexNode probeConstraint(
            SchemaPlus probeSchema, String viewName, String expression,
            Map<String, String> localToIri) {
        String sql = "SELECT * FROM \"" + viewName.replace("\"", "\"\"") + "\" WHERE "
                + ConstraintRewriter.rewriteToIriColumns(expression, localToIri);
        try {
            // Calcite's default parser config quotes identifiers with `"` and leaves a quoted
            // identifier's case alone, which is what makes the rewritten IRI columns resolve:
            // they are quoted, so they match the row type's field names character for character.
            // (An identifier the rewriter left bare — a function name, or a local name this class
            // does not have — is upper-cased and resolved as SQL, which is the intent.)
            Planner probe = Frameworks.getPlanner(
                    Frameworks.newConfigBuilder().defaultSchema(probeSchema).build());
            try {
                RexNode condition = firstFilterCondition(
                        probe.rel(probe.validate(probe.parse(sql))).rel);
                if (condition == null) {
                    log.warn("Enforced constraint did not lower to a filter, so the planner will"
                            + " not reason with it (it is still enforced in the view bodies): {}",
                            expression);
                    return null;
                }
                String unusable = unusablePart(condition);
                if (unusable != null) {
                    log.warn("Enforced constraint lowers to a condition containing a {}, which"
                            + " cannot be carried as a guarantee, so the planner will not reason"
                            + " with it (it is still enforced in the view bodies): {}",
                            unusable, expression);
                    return null;
                }
                return condition;
            } finally {
                probe.close();
            }
        } catch (Exception e) {
            // Every way Calcite reports "this text is not a predicate over this row type" —
            // parse, validation and conversion failures — is an Exception, and every one of them
            // means the same thing here: drop this constraint. An Error is deliberately not
            // caught: it is not a statement about the constraint.
            log.warn("Enforced constraint could not be parsed for planning, so the planner will"
                    + " not reason with it (it is still enforced in the view bodies): {}",
                    expression, e);
            return null;
        }
    }

    /**
     * Names the first thing in {@code condition} that stops it being a portable guarantee, or
     * {@code null} if there is nothing.
     *
     * <p><b>Catching a parse failure is not enough.</b> A constraint can lower cleanly and still
     * produce a condition this planner must not hold. {@code rejectConstraintExpression} screens
     * for readers and statement chaining, not for sub-queries, so an enforced
     * {@code year IN (SELECT …)} reaches the probe and — Calcite 1.41 leaves {@code IN} unexpanded
     * by default — comes back as a {@link org.apache.calcite.rex.RexSubQuery} wrapping a
     * {@code RelNode} built in the <em>probe's</em> cluster. Handing that to the query's planner
     * as a pulled-up guarantee puts a foreign rel tree inside its {@code RelOptPredicateList},
     * and anything that then throws throws where nothing is catching — a failed query, which is
     * exactly what the drop-on-failure contract promises cannot happen. A correlation variable
     * ({@code $cor0}) and a dynamic parameter ({@code ?}) are the same problem by a different
     * route: neither can be bound in the query's own cluster.
     *
     * <p>So the check is at the same site as the WARN, and its outcome is identical to an
     * unreadable constraint: drop the predicate, keep the query.
     *
     * <p>Package-private (not {@code private}) so {@link WrapperExpansion} can run the same
     * check on each Rex expression it pulls out of a wrapper's planned tree — the identical
     * three constructs are exactly as unusable there, and for the same reason: neither probe's
     * planner cluster can bind a sub-query/correlation variable/dynamic parameter belonging to
     * a different cluster.
     */
    static @Nullable String unusablePart(RexNode condition) {
        final String[] found = {null};
        condition.accept(new org.apache.calcite.rex.RexVisitorImpl<Void>(true) {
            @Override
            public Void visitSubQuery(org.apache.calcite.rex.RexSubQuery subQuery) {
                found[0] = "sub-query";
                return null;   // no need to descend: one is enough to drop the constraint
            }

            @Override
            public Void visitCorrelVariable(org.apache.calcite.rex.RexCorrelVariable variable) {
                found[0] = "correlation variable";
                return null;
            }

            @Override
            public Void visitDynamicParam(org.apache.calcite.rex.RexDynamicParam param) {
                found[0] = "dynamic parameter";
                return null;
            }
        });
        return found[0];
    }

    private static @Nullable RexNode firstFilterCondition(RelNode rel) {
        if (rel instanceof Filter filter) {
            return filter.getCondition();
        }
        for (RelNode input : rel.getInputs()) {
            RexNode found = firstFilterCondition(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ── Core lowering ─────────────────────────────────────────────────────────

    private RelNode lowerAliased(AliasedQuery q) {
        // AranCost: three components carried (its javadoc says why comparison stays rows-only —
        // the placement design's deliberate A1 shape). Replaces an anonymous factory that
        // delegated to RelOptCostImpl, which DISCARDS cpu/io at construction.
        // The CancelFlag is how PlanExecutor's production budget stops Volcano (D3). It has to
        // be HERE, in the config's Context: AbstractRelOptPlanner reads its flag from the Context
        // at construction and its setCancelFlag(...) is documented as ignored, so a flag handed
        // to the planner later is never consulted.
        RelBuilder builder = RelBuilder.create(
                Frameworks.newConfigBuilder()
                        .defaultSchema(schema)
                        .typeSystem(new AranTypeSystem())
                        .costFactory(AranCost.FACTORY)
                        .traitDefs(java.util.Collections.emptyList())
                        .context(org.apache.calcite.plan.Contexts.of(
                                new org.apache.calcite.util.CancelFlag(
                                        new java.util.concurrent.atomic.AtomicBoolean())))
                        .build());

        installKeyAwareMetadata(builder.getCluster());

        Map<String, MappingCatalog.IntegratedClass> scope = buildScope(builder, q);

        if (!q.where().isEmpty()) {
            builder.filter(buildWhereNode(builder, scope, q.where()));
        }

        // An aggregate without GROUP BY is a grand total, not a projection: routing on GROUP BY
        // alone lowered `SELECT AVG(C.p) FROM C` to every row's raw p, named as the average.
        if (q.groupBy().isEmpty()
                && q.select().stream().noneMatch(AliasedQuery.Projection::isAggregate)) {
            // W-B5 / J5-F2: this path never reads HAVING, so it used to be dropped silently and
            // every row came back under a query that asked for a filter. There are no groups to
            // filter without an aggregate or a GROUP BY; say so instead.
            if (!q.having().isEmpty()) {
                throw new OqlPlanException("HAVING needs an aggregate or GROUP BY — without one"
                        + " there are no groups to filter. Use WHERE to filter rows.");
            }
            buildProject(builder, scope, q.select());
            if (q.distinct()) builder.distinct();
        } else {
            buildAggregate(builder, scope, q);
        }

        if (!q.orderBy().isEmpty()) {
            List<org.apache.calcite.rex.RexNode> sortKeys = new ArrayList<>();
            for (AliasedQuery.OrderKey ok : q.orderBy()) {
                // After project/aggregate the output field has the local property name (or AS alias).
                // Search the SELECT list for the matching projected output name.
                String outName = resolveOrderOutputName(q, ok, scope);
                try {
                    org.apache.calcite.rex.RexNode field = builder.field(outName);
                    sortKeys.add(ok.desc() ? builder.desc(field) : field);
                } catch (Exception e) {
                    throw new OqlPlanException("ORDER BY field '" + ok.property()
                            + "' not found in SELECT output — ensure it appears in SELECT");
                }
            }
            if (q.limit() != null) builder.sortLimit(0, q.limit(), sortKeys);
            else                   builder.sort(sortKeys);
        } else if (q.limit() != null) {
            builder.limit(0, q.limit());
        }
        return builder.build();
    }

    // ── Key-aware join-cardinality metadata ─────────────────────────────────────

    /**
     * Chains {@link AranRelMdRowCount}, {@link AranRelMdDistinctRowCount},
     * {@link AranRelMdSelectivity} and {@link AranRelMdPredicates} ahead of Calcite's default
     * provider, in that order. Stateless and stable (a {@code private static final} singleton),
     * so every {@link org.apache.calcite.rel.metadata.JaninoRelMetadataProvider#of} call below
     * wraps the same underlying provider instance and hits Janino's handler cache instead of
     * re-generating it per query.
     */
    private static final org.apache.calcite.rel.metadata.RelMetadataProvider ARAN_METADATA_PROVIDER =
            org.apache.calcite.rel.metadata.ChainedRelMetadataProvider.of(
                    com.google.common.collect.ImmutableList.of(
                            AranRelMdRowCount.SOURCE,
                            AranRelMdDistinctRowCount.SOURCE,
                            AranRelMdSelectivity.SOURCE,
                            AranRelMdPredicates.SOURCE,
                            org.apache.calcite.rel.metadata.DefaultRelMetadataProvider.INSTANCE));

    /**
     * Installs {@link AranRelMdRowCount}, {@link AranRelMdDistinctRowCount},
     * {@link AranRelMdSelectivity} and {@link AranRelMdPredicates} ahead of Calcite's default
     * provider on {@code cluster}, so a declared unique key on a join column lowers the
     * join-cardinality estimate, a profiled column's exact NDV / null fraction sharpens
     * distinct-row-count and filter selectivity, and an enforced denial constraint becomes a
     * predicate the rewrites can use. This is the same cluster {@link PlanExecutor#optimize}
     * plans on, so the provider governs both the VolcanoPlanner's cost computation and the
     * post-optimize {@code mq.getRowCount(...)} used to render the plan string.
     *
     * <p>Wiring is cluster-scoped only: {@code RelMetadataQuery}'s public
     * {@code RelMetadataQuery(MetadataHandlerProvider)} constructor binds a fresh query instance
     * directly to {@link org.apache.calcite.rel.metadata.JaninoRelMetadataProvider#of
     * ARAN_METADATA_PROVIDER} without touching {@link org.apache.calcite.rel.metadata
     * .RelMetadataQueryBase#THREAD_PROVIDERS}. That static, JVM-thread-global {@code ThreadLocal}
     * is only populated by {@code RelOptCluster#setMetadataProvider}, which this method
     * deliberately does not call — so no cluster built on this thread leaks our provider into
     * unrelated planning.
     */
    // Package-private so AranRelMdRowCountTest and AranRelMdSelectivityTest can install the same
    // provider chain the planner uses, rather than reconstructing it and testing a different wiring.
    static void installKeyAwareMetadata(org.apache.calcite.plan.RelOptCluster cluster) {
        cluster.setMetadataQuerySupplier(() ->
                new org.apache.calcite.rel.metadata.RelMetadataQuery(
                        org.apache.calcite.rel.metadata.JaninoRelMetadataProvider.of(ARAN_METADATA_PROVIDER)));
        cluster.invalidateMetadataQuery();
    }

    // ── Scan + Join ───────────────────────────────────────────────────────────

    /**
     * Pushes one integrated class onto the builder stack as an explicit UNION ALL over its
     * per-source members ({@code u_<dataset>_<class>}), rather than as a single opaque scan
     * of {@code int_}.
     *
     * <p>This is what lets the cost model see the GAV unfolding. Scanning {@code int_} hides
     * the union inside a DuckDB view definition, so Calcite gets one summed row count per
     * class and — because {@link AranStatistic} must refuse to declare a key across a UNION
     * ALL — no usable key at all for any multi-source class. With the members in the plan,
     * each branch carries its own freyja-derived row count and keys via
     * {@link WrapperStatistic}, and a join can be pushed below the union onto single-source
     * branches where those keys are valid.
     *
     * <p>Every member exposes the class's full global-column vector (NULL-padded in the view
     * body for properties it does not supply), so the branches are union-compatible and the
     * resulting row type is identical to {@link IntegratedClassTable}'s. Everything
     * downstream — field resolution, joins, projection — is unaffected.
     *
     * <p>A single-source class produces a bare scan with no Union node, which keeps the
     * common case's plan shape and emitted SQL unchanged.
     *
     * <p>A member whose wrapper the catalog records as {@code EXPANDED} is not scanned at all —
     * see {@link #pushExpandedMember}. Every other member takes the {@code u_} scan above,
     * unchanged.
     */
    private void scanIntegratedClass(RelBuilder builder, MappingCatalog.IntegratedClass cls) {
        List<MappingCatalog.SourceView> sources = cls.sources();
        if (sources.isEmpty()) {
            throw new OqlPlanException("Integrated class '" + Names.localName(cls.iri())
                    + "' has no sources to scan.");
        }
        for (MappingCatalog.SourceView source : sources) {
            if (!pushExpandedMember(builder, source, cls)) {
                builder.scan(WrapperTable.viewName(source, cls));
            }
        }
        if (sources.size() > 1) {
            // UNION ALL, never UNION: set semantics would silently drop genuinely duplicate
            // tuples arriving from overlapping sources, which is the same reason
            // DuckDbExecutor builds int_ with UNION ALL.
            builder.union(true, sources.size());
        }
    }

    // ── Wrapper expansion: a member planned through its own src_ relation ─────

    /** The catalog's word for a wrapper whose SQL is verified to plan over its {@code src_} relation. */
    private static final String EXPANDED = "EXPANDED";

    /**
     * Pushes {@code source} as the wrapper's <em>own</em> plan over its raw
     * {@code src_<dataset>} relation, topped by the projection that renames its columns to the
     * class's global-property-IRI vector — or returns {@code false}, leaving the caller to scan
     * the opaque {@code u_} view exactly as it always has.
     *
     * <p><b>Why this is worth doing.</b> A {@code u_} scan is a black box: the wrapper's own
     * projection and any filter it applies are hidden inside a DuckDB view body, so the planner
     * cannot push a predicate through them, cannot see which raw column a global column really
     * is, and gets its statistics only at the granularity of the whole member. With the wrapper
     * in the tree the leaf is {@link SrcTable} — the raw snapshot relation, whose freyja profiles
     * are per raw column — and every rewrite in {@link AranRules} applies to the wrapper's
     * internals like any other subtree.
     *
     * <p><b>The projection reproduces {@code DuckDbExecutor.buildDdl}'s {@code u_} view body
     * exactly</b>, as RexNodes rather than text: a mapped column is referenced and, where the
     * class's widened type differs from the type the wrapper hands over, {@code TRY_CAST} to it
     * ({@link AranSqlOperators#TRY_CAST} — never a plain CAST: a dirty cell must degrade to NULL,
     * never fail a query that used to run); a global column this source does not supply becomes a
     * typed NULL literal, the same padding the view body writes. <em>Exactly</em> is meant
     * literally, and it is the constraint that decides what can be expanded at all — see
     * {@link #REPRODUCIBLE_CAST_TYPES}. The result's row type is {@link WrapperTable#rowType} by
     * construction, which is what keeps a mixed expanded/opaque class union-compatible.
     *
     * <p><b>Four things make it give up, and all four end the same way</b> — this member is
     * scanned as {@code u_} and the query plans exactly as before, because a member the planner
     * cannot see into is the state every member was in until this phase:
     * <ul>
     *   <li>The probe fails now even though the graph recorded {@code EXPANDED}. That verdict was
     *       stamped at compile time; a wrapper edited since, or a Calcite upgrade, can invalidate
     *       it, and only a plan-time probe notices.</li>
     *   <li>The probe's tree contains a node kind {@link #adopt} cannot rebuild
     *       ({@link #unrebuildablePart}).</li>
     *   <li>The wrapper does not output a column the mapping promises. The compile-time
     *       round-trip check cannot see this: the wrapper is internally consistent, it simply
     *       does not agree with {@code columnMap}.</li>
     *   <li>A column's projection would not be byte-identical to the one {@code buildDdl} writes
     *       ({@link #REPRODUCIBLE_CAST_TYPES}). Being <em>equivalent</em> is not enough: an
     *       expanded member and an opaque one meet in the same {@code UNION ALL}, and DuckDB
     *       resolves that union's column types from the SQL it is given.</li>
     *   <li>The class has an enforced denial constraint. This one is a correctness gate rather
     *       than a fidelity one — see below.</li>
     * </ul>
     *
     * <p><b>Why an enforced denial constraint refuses expansion outright.</b> A class's constraints
     * are enforced <em>inside each {@code u_} member's body</em>, not above the union
     * ({@code buildDdl} relies on selection distributing over union, σ(A ∪ B) = σ(A) ∪ σ(B), bag
     * semantics included). Replacing that scan with the wrapper's own plan over {@code src_}
     * removes the only place the filter exists, and nothing in the RelNode tree puts it back: the
     * quarantined rows simply reappear in the answer. That is a silently changed result, not a
     * degraded estimate, so it is refused rather than approximated. The check counts the
     * constraints <em>as written</em> ({@link #enforcedByClass}), never the parsed ones, because a
     * constraint the planner failed to parse is still enforced in the view body.
     *
     * <p>The designed way to lift this is to push the class's parsed constraints as a
     * {@link org.apache.calcite.rel.logical.LogicalFilter} on top of the expanded member —
     * {@link #parseEnforced} already produces exactly those {@code RexNode}s over exactly this row
     * type — <em>guarded by a parity check</em>: expansion may only proceed when the number of
     * parsed constraints equals the number of enforced ones for the class. Without that guard the
     * drop-on-failure contract that makes an unparseable constraint harmless today would silently
     * become a correctness hole, because here a missing predicate is a missing filter rather than
     * a missing estimate. That is a capability, and it is deliberately not this task's.
     */
    private boolean pushExpandedMember(RelBuilder builder,
                                       MappingCatalog.SourceView source,
                                       MappingCatalog.IntegratedClass cls) {
        if (!EXPANDED.equals(source.expansionStatus())) {
            return false;
        }
        String className = Names.localName(cls.iri());
        int enforcedCount = enforcedByClass.getOrDefault(cls.iri(), List.of()).size();
        if (enforcedCount > 0) {
            return notExpanded(source, className, "the class enforces " + enforcedCount
                    + " denial constraint(s), which buildDdl applies inside the u_ view body and"
                    + " an expansion would read straight past");
        }
        try {
            RelNode probed = WrapperExpansion.probe(source).orElse(null);
            if (probed == null) {
                return notExpanded(source, className, "could not be expanded at plan time");
            }
            String unrebuildable = WrapperExpansion.unrebuildablePart(probed);
            if (unrebuildable != null) {
                return notExpanded(source, className, "it plans to a " + unrebuildable
                        + ", which the planner cannot rebuild in its own cluster");
            }

            RelNode wrapper = adopt(probed, builder.getCluster());
            List<String> wrapperColumns = wrapper.getRowType().getFieldNames();
            RelDataType memberRowType = WrapperTable.rowType(cls, builder.getTypeFactory());
            RexBuilder rex = builder.getRexBuilder();
            List<RexNode> projection = new ArrayList<>(memberRowType.getFieldCount());
            for (RelDataTypeField field : memberRowType.getFieldList()) {
                SqlTypeName target = field.getType().getSqlTypeName();
                String sourceProperty = source.columnMap().get(field.getName());

                if (target != SqlTypeName.VARCHAR && !REPRODUCIBLE_CAST_TYPES.contains(target)) {
                    return notExpanded(source, className, "the class types column '" + field.getName()
                            + "' as " + target + ", which this projection cannot reproduce");
                }
                if (sourceProperty == null) {
                    if (target == SqlTypeName.VARCHAR) {
                        return notExpanded(source, className, "it does not supply column '"
                                + field.getName() + "', whose type the class does not know");
                    }
                    projection.add(rex.makeNullLiteral(field.getType()));
                    continue;
                }
                int ordinal = wrapperOutputOrdinal(source, wrapperColumns, sourceProperty);
                if (ordinal < 0) {
                    return notExpanded(source, className, "it outputs no column '" + sourceProperty
                            + "', which its mapping promises — the wrapper text and the mapping"
                            + " disagree");
                }
                RexNode ref = rex.makeInputRef(wrapper, ordinal);
                boolean alreadyThatType = field.getType().equals(ref.getType());
                if (target == SqlTypeName.VARCHAR) {
                    // buildDdl writes a bare reference for a text column, so the expansion must too —
                    // which it can only do when the wrapper already hands the column over as exactly
                    // this type. Anything else would need a cast buildDdl does not write.
                    if (!alreadyThatType) {
                        return notExpanded(source, className, "column '" + field.getName()
                                + "' arrives as " + ref.getType().getSqlTypeName()
                                + " and the class does not know its type");
                    }
                    projection.add(ref);
                    continue;
                }
                if (alreadyThatType) {
                    return notExpanded(source, className, "the wrapper's own plan already carries"
                            + " column '" + field.getName() + "' as " + target + ", so the TRY_CAST"
                            + " buildDdl writes would be optimized away as redundant");
                }
                projection.add(rex.makeCall(field.getType(), AranSqlOperators.TRY_CAST, List.of(ref)));
            }
            builder.push(LogicalProject.create(
                    wrapper, List.of(), projection, memberRowType, Set.<CorrelationId>of()));
            return true;
        } catch (Exception e) {
            // Everything above touches Calcite internals this method does not control (adopt's
            // cross-cluster rebuild, RexBuilder, LogicalProject.create) on the strength of
            // invariants that hold for every fixture this suite knows about — but a "surprise at
            // plan time" is one of the named failure modes RoundTrip's class javadoc already
            // promises degrades to the opaque path rather than failing the query, and that
            // promise cannot depend on which cases were anticipated when this method was written.
            // An Error is deliberately not caught, matching WrapperExpansion.probe's own
            // discipline: it is not a statement about the wrapper.
            return notExpanded(source, className, "plan-time expansion failed: " + e);
        }
    }

    /**
     * The class column types whose cast this projection renders <em>byte-identically</em> to the
     * text {@code DuckDbExecutor.buildDdl} writes into the {@code u_} view body.
     *
     * <p>This list is measured, not assumed, and the two that are missing are why it has to exist
     * at all. {@code buildDdl} names its cast target with {@link XsdTypes#duckType}; the expansion
     * names it by unparsing the Calcite type, and for four of the six they agree exactly
     * ({@code BIGINT}, {@code DOUBLE}, {@code BOOLEAN}, {@code DATE}). The other two do not:
     * Calcite's default {@code DECIMAL} unparses as {@code DECIMAL(19, 0)} where {@code buildDdl}
     * writes {@code DECIMAL(38,9)} — nine digits of scale silently truncated — and its default
     * {@code TIMESTAMP} unparses as {@code TIMESTAMP(0)}, a precision {@code buildDdl} never asks
     * for and DuckDB need not even accept. A member with such a column stays opaque.
     *
     * <p>{@code VARCHAR} is deliberately absent and handled separately, because there
     * {@code buildDdl} emits no cast at all: a mapped column is a bare reference and an unmapped
     * one a bare {@code NULL}, both of which leave the physical DuckDB type untouched. The
     * expansion can reproduce the bare reference and cannot reproduce the bare {@code NULL} —
     * Calcite refuses a {@code NULL}-typed expression in a {@code VARCHAR} field, and the
     * {@code CAST(NULL AS VARCHAR)} it does allow changes what a {@code UNION ALL} with a numeric
     * sibling resolves to. Found by the executed differential
     * ({@code PlanShapeHarnessTest}): the same query returned {@code 1872} through the opaque
     * path and {@code "1872"} through the expanded one.
     *
     * <p>Since {@code XsdTypes.widenedType} folds an <em>absent</em> range toward VARCHAR exactly
     * as it folds an unknown one, a column no member supplies a mapping for also has no recorded
     * range, so in a real catalog "unmapped" and "VARCHAR" coincide: a member of a multi-source
     * class that does not supply every column of the class does not expand. The typed-NULL branch
     * above is reachable only from a hand-built catalog — the same standing that
     * {@code buildDdl}'s own {@code CAST(NULL AS …)} branch has, and it is kept for the same
     * reason: the two projections must stay recognisably one design.
     *
     * <p><b>A cast Calcite thinks is redundant is refused too</b>, which is the same rule read
     * from the other side. {@link SrcTable} gives a raw snapshot column the type the graph's
     * {@code rdfs:range} claims for it; where that claim happens to equal the class's own type —
     * an unaligned property, whose global and source-property IRIs are one string — the
     * {@code TRY_CAST} becomes a no-op in Calcite's model, {@code RexSimplify} drops it, the
     * projection reduces to the identity and the optimizer deletes the whole node. The plan then
     * reads the raw {@code src_} columns with no cast at all. That range is a claim about the
     * <em>graph</em>, never verified against the snapshot, so the cast is exactly what must not be
     * skipped: a file holding {@code "1937"} for a column the graph calls an integer came back as
     * {@code 1937} through the opaque path and {@code "1937"} through the expanded one until this
     * screen existed ({@code PlanShapeHarnessTest} pins it). Expansion is faithful precisely when
     * Calcite's type model and {@code buildDdl}'s text agree on whether a cast is needed.
     */
    static final Set<SqlTypeName> REPRODUCIBLE_CAST_TYPES = Set.of(
            SqlTypeName.BIGINT, SqlTypeName.DOUBLE, SqlTypeName.BOOLEAN, SqlTypeName.DATE);

    /**
     * (datasetId, reason) pairs already reported at WARN.
     *
     * <p><b>Static on purpose.</b> An {@link OqlPlanner} is built per query, so a per-instance set
     * would deduplicate nothing: the same permanently-broken wrapper would WARN on every query in
     * a hot path, which is the noise this exists to stop. The trade is that the state outlives any
     * one query — acceptable for a log-throttle, and the reason {@link #resetExpansionWarningDedup}
     * exists so tests do not depend on each other's order.
     *
     * <p><b>Bounded, and it fails towards noise.</b> Past {@link #REPORTED_FAILURES_CAP} distinct
     * pairs the set stops growing and every occurrence WARNs again. A repeated diagnostic is
     * cheap; a diagnostic silently suppressed by an unbounded cache that quietly stopped
     * admitting entries is not.
     */
    private static final java.util.Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private static final int REPORTED_FAILURES_CAP = 512;

    /**
     * WARNs why {@code source} stays opaque — once per (dataset, reason), DEBUG thereafter — and
     * returns {@code false}, which is the caller's signal to scan the {@code u_} view instead.
     */
    private static boolean notExpanded(MappingCatalog.SourceView source, String className,
                                       String reason) {
        String template = "Wrapper for dataset '{}' is recorded EXPANDED but {}, so class '{}'"
                + " scans it as an opaque view instead: {}";
        if (firstReportOf(source.datasetId(), reason)) {
            log.warn(template, source.datasetId(), reason, className, source.wrapperSql());
        } else {
            log.debug(template, source.datasetId(), reason, className, source.wrapperSql());
        }
        return false;
    }

    /** Whether this (dataset, reason) has not been WARNed yet — always true once at capacity. */
    private static boolean firstReportOf(String datasetId, String reason) {
        return REPORTED_FAILURES.size() >= REPORTED_FAILURES_CAP
                || REPORTED_FAILURES.add(datasetId + '\0' + reason);
    }

    /**
     * Forgets which expansion failures have been WARNed. Package-private, and only tests call it:
     * {@link #REPORTED_FAILURES} is static, so without this a test asserting a WARN would pass or
     * fail depending on whether another test had already triggered the same (dataset, reason).
     */
    static void resetExpansionWarningDedup() {
        REPORTED_FAILURES.clear();
    }

    /**
     * Where the wrapper's output carries {@code sourceProperty}, or {@code -1}.
     *
     * <p>Matched by <em>name</em> against the wrapper's own output aliases, because that is the
     * only thing the two ends share: {@code columnMap} names a source-property IRI, and a
     * generated wrapper aliases each raw column to exactly that IRI
     * ({@code WrapperSqlBuilder}), so the direct hit is the normal case. The
     * {@code sourceRefs} inverse is the fallback for a hand-edited wrapper that projects its raw
     * column names through unaliased — legal, and previously invisible because nothing but the
     * {@code w_} view ever read a wrapper's output names.
     */
    private static int wrapperOutputOrdinal(MappingCatalog.SourceView source,
                                            List<String> wrapperColumns, String sourceProperty) {
        int aliased = wrapperColumns.indexOf(sourceProperty);
        if (aliased >= 0) {
            return aliased;
        }
        for (Map.Entry<String, String> ref : source.sourceRefs().entrySet()) {
            if (ref.getValue().equals(sourceProperty)) {
                int raw = wrapperColumns.indexOf(ref.getKey());
                if (raw >= 0) {
                    return raw;
                }
            }
        }
        return -1;
    }

    
    /**
     * Rebuilds {@code rel} — a tree {@link WrapperExpansion#probe} planned in a throwaway cluster
     * of its own — node by node in {@code cluster}, this query's cluster.
     *
     * <p><b>Why a rebuild and not a splice.</b> Calcite interns {@code RelDataType} in a cache
     * shared by every type factory, which is what lets {@code probeConstraint} above carry a
     * {@code RexNode} out of a probe and into the query's planner. A {@code RelNode} is not the
     * same: it holds its {@link org.apache.calcite.plan.RelOptCluster}, and the cluster holds the
     * {@code VolcanoPlanner} that {@link PlanExecutor#optimize} will register the tree into. A
     * foreign-cluster node in the plan would be registered into a planner that is not its own.
     * There is no Calcite API to re-home a tree, and none is needed: the parts that matter cross
     * cleanly on their own. The Rex expressions are cluster-free (the same fact
     * {@code probeConstraint} relies on, and {@code WrapperExpansion} has already screened out
     * the three constructs that are not — sub-query, correlation variable, dynamic parameter),
     * the field names are strings, and the leaf's {@link org.apache.calcite.plan.RelOptTable} is a
     * catalog entry rather than a plan node, so it can be re-scanned in any cluster.
     *
     * <p>Structurally this is {@code PlanExecutor.toLogical}: the same node-by-node reconstruction
     * over the same closed operator set, with a different destination.
     */
    private static RelNode adopt(RelNode rel, RelOptCluster cluster) {
        if (rel instanceof TableScan scan) {
            return LogicalTableScan.create(
                    cluster, scan.getTable(), scan.getHints());
        }
        RelNode input = adopt(rel.getInput(0), cluster);
        if (rel instanceof Project project) {
            return LogicalProject.create(input, project.getHints(), project.getProjects(),
                    project.getRowType(), project.getVariablesSet());
        }
        if (rel instanceof Filter filter) {
            return LogicalFilter.create(input, filter.getCondition());
        }
        Sort sort = (Sort) rel;
        return LogicalSort.create(
                input, sort.getCollation(), sort.offset, sort.fetch);
    }

    private Map<String, MappingCatalog.IntegratedClass> buildScope(RelBuilder builder, AliasedQuery q) {
        Map<String, MappingCatalog.IntegratedClass> scope = new LinkedHashMap<>();

        MappingCatalog.IntegratedClass fromClass = requireClass(q.from().className());
        scanIntegratedClass(builder, fromClass);
        scope.put(q.from().alias(), fromClass);

        for (AliasedQuery.Join j : q.joins()) {
            MappingCatalog.IntegratedClass newClass = requireClass(j.className());
            if (scope.containsKey(j.alias()))
                throw new OqlPlanException("Duplicate alias '" + j.alias() + "'");

            scanIntegratedClass(builder, newClass);

            MappingCatalog.JoinProperty jp = requireJoin(j.joinProperty());

            boolean newIsRight = newClass.iri().equals(jp.rightClass());
            boolean newIsLeft  = newClass.iri().equals(jp.leftClass());
            if (!newIsRight && !newIsLeft)
                throw new OqlPlanException("Join property '" + j.joinProperty()
                        + "' connects " + Names.localName(jp.leftClass()) + " → " + Names.localName(jp.rightClass())
                        + " but '" + j.className() + "' is on neither side.");

            // left input (0) = accumulated scan so far; right input (1) = newly scanned table
            String leftCol  = newIsRight ? jp.leftCol()  : jp.rightCol();  // in accumulated left
            String rightCol = newIsRight ? jp.rightCol() : jp.leftCol();   // in new right
            RexNode cond = builder.call(SqlStdOperatorTable.EQUALS,
                    builder.field(2, 0, leftCol),
                    builder.field(2, 1, rightCol));
            builder.join(JoinRelType.INNER, cond);

            scope.put(j.alias(), newClass);
        }
        return scope;
    }

    // ── Plain projection ──────────────────────────────────────────────────────

    private void buildProject(RelBuilder builder,
                              Map<String, MappingCatalog.IntegratedClass> scope,
                              List<AliasedQuery.Projection> select) {
        List<RexNode> exprs = new ArrayList<>();
        List<String>  names = new ArrayList<>();
        boolean multiAlias  = scope.size() > 1;

        for (AliasedQuery.Projection p : select) {
            if (p.star()) {
                if (p.alias() == null) {
                    scope.forEach((al, cls) -> expandStar(builder, exprs, names, al, cls, scope, multiAlias));
                } else {
                    expandStar(builder, exprs, names, p.alias(),
                            requireAlias(scope, p.alias()), scope, multiAlias);
                }
            } else {
                MappingCatalog.IntegratedClass cls = requireAlias(scope, p.alias());
                String iri = requireProperty(cls, p.alias(), p.property());
                exprs.add(fieldInScope(builder, scope, p.alias(), iri));
                names.add(p.as() != null ? p.as() : p.property());
            }
        }
        if (exprs.isEmpty()) throw new OqlPlanException("SELECT projected no columns.");
        // Forced: RelBuilder turns an identity projection into a rename that lives only in the
        // builder's frame, so build() handed back the input under ITS names — raw src_ columns
        // over an EXPANDED class, full property IRIs over an OPAQUE one (V70).
        builder.project(exprs, names, true);
        // No GROUP BY: every column is one row's own value, so the result is a list rather than
        // a comparison. Star expansion means the column count is names.size(), not select.size().
        columnRoles = java.util.Collections.nCopies(names.size(), ColumnRole.VALUE);
    }

    // ── Aggregate ─────────────────────────────────────────────────────────────

    private void buildAggregate(RelBuilder builder,
                                Map<String, MappingCatalog.IntegratedClass> scope,
                                AliasedQuery q) {
        List<RexNode> groupKeyExprs = new ArrayList<>();
        for (AliasedQuery.GroupKey gk : q.groupBy()) {
            MappingCatalog.IntegratedClass cls = requireAlias(scope, gk.alias());
            String iri = requireProperty(cls, gk.alias(), gk.property());
            groupKeyExprs.add(fieldInScope(builder, scope, gk.alias(), iri));
        }
        RelBuilder.GroupKey groupKey = builder.groupKey(groupKeyExprs);

        List<RelBuilder.AggCall> aggCalls = new ArrayList<>();
        for (AliasedQuery.Projection p : q.select()) {
            if (!p.isAggregate()) {
                // Group-key refs are emitted by groupKey above. Anything else has no single
                // value per group, and skipping it silently returned fewer columns than asked.
                if (p.star() || findGroupKeyOrdinal(q.groupBy(), p.alias(), p.property()) < 0) {
                    String column = p.alias() == null ? "*"
                            : Names.localName(requireAlias(scope, p.alias()).iri()) + "."
                                    + (p.star() ? "*" : p.property());
                    throw new OqlPlanException("SELECT " + column
                            + " is neither aggregated nor in the GROUP BY list.");
                }
                continue;
            }
            String outAlias = p.as() != null ? p.as() : p.aggFn().display();
            RelBuilder.AggCall call;
            if (p.aggFn() == OqlQuery.AggFn.COUNT_STAR) {
                call = builder.count(false, outAlias);
            } else {
                MappingCatalog.IntegratedClass cls = requireAlias(scope, p.alias());
                String iri = requireProperty(cls, p.alias(), p.property());
                RexNode arg = fieldInScope(builder, scope, p.alias(), iri);
                call = switch (p.aggFn()) {
                    case COUNT -> builder.count(false, outAlias, arg);
                    case SUM   -> builder.sum(false, outAlias, arg);
                    case AVG   -> builder.avg(false, outAlias, arg);
                    case MIN   -> builder.min(outAlias, arg);
                    case MAX   -> builder.max(outAlias, arg);
                    default    -> throw new OqlPlanException("Unexpected aggregate: " + p.aggFn());
                };
            }
            aggCalls.add(call);
        }

        builder.aggregate(groupKey, aggCalls);
        // RelBuilder.aggregate emits [group keys…, aggregates…] — the SELECT list's order is not
        // preserved, which is exactly why this is recorded here rather than read off the AST.
        List<ColumnRole> roles = new ArrayList<>(
                java.util.Collections.nCopies(groupKeyExprs.size(), ColumnRole.KEY));
        roles.addAll(java.util.Collections.nCopies(aggCalls.size(), ColumnRole.MEASURE));
        columnRoles = List.copyOf(roles);

        // HAVING filter (applied to aggregate output)
        if (!q.having().isEmpty()) {
            // After aggregate(), output fields are: [groupKeys..., aggOutputs...], the aggregates
            // in SELECT order — so an aggregate's ordinal is its position in this list.
            //
            // S6-3: this used to be a Map keyed by the aggregate's OUTPUT NAME, which is the
            // function's display name whenever the projection has no AS. Two un-aliased SUM(...)
            // projections therefore shared the key "SUM" and the second overwrote the first, so
            // `HAVING SUM(A.x) > n` filtered on SUM(A.y); and an unmatched predicate fell back to
            // that same display name, found an entry, and bound to an aggregate nobody asked for
            // instead of reaching the guard below. A name is not an identity here — (function,
            // alias, property) is.
            List<AliasedQuery.Projection> aggProjections = new ArrayList<>();
            for (AliasedQuery.Projection p : q.select()) {
                if (p.isAggregate()) aggProjections.add(p);
            }
            int base = q.groupBy().size();

            List<RexNode> havingPreds = new ArrayList<>();
            for (AliasedQuery.HavingPredicate hp : q.having()) {
                RexNode field;
                String columnLabel;
                if (hp.aggFn() != null) {
                    int position = indexOfAggregate(aggProjections, hp);
                    if (position < 0)
                        throw new OqlPlanException("HAVING references aggregate '"
                                + describeAggregate(hp) + "' that is not in the SELECT list.");
                    AliasedQuery.Projection matched = aggProjections.get(position);
                    field = builder.field(base + position);
                    columnLabel = matched.as() != null ? matched.as() : describeAggregate(hp);
                } else {
                    // Filter on a group-by key: find its ordinal in the aggregate output
                    MappingCatalog.IntegratedClass cls = requireAlias(scope, hp.alias());
                    String iri = requireProperty(cls, hp.alias(), hp.property());
                    // Group keys appear in output at their position in groupBy list
                    int keyOrdinal = findGroupKeyOrdinal(q.groupBy(), hp.alias(), hp.property());
                    if (keyOrdinal < 0)
                        throw new OqlPlanException("HAVING property '"
                                + hp.property() + "' is not in the GROUP BY list.");
                    field = builder.field(keyOrdinal);
                    columnLabel = hp.alias() + "." + hp.property();
                }
                havingPreds.add(builder.call(toHavingOp(hp.op()), field,
                        literal(builder, field.getType(), columnLabel, hp.literal())));
            }
            builder.filter(havingPreds);
        }
    }

    /**
     * Position, among the SELECT list's aggregates, of the one a HAVING predicate names — or
     * {@code -1} when the SELECT list does not compute it.
     *
     * <p>There is deliberately no fallback: an aggregate the query never projects cannot be
     * filtered on, and answering with a near-miss is how S6-3 returned wrong rows. The grammar
     * gives a HAVING aggregate no way to name a projection's {@code AS} alias, so the identity
     * matched here is the argument itself — {@code COUNT(*)} having no argument to match on.
     */
    private static int indexOfAggregate(List<AliasedQuery.Projection> aggregates,
                                        AliasedQuery.HavingPredicate hp) {
        for (int i = 0; i < aggregates.size(); i++) {
            AliasedQuery.Projection p = aggregates.get(i);
            if (p.aggFn() != hp.aggFn()) continue;
            if (hp.aggFn() == OqlQuery.AggFn.COUNT_STAR) return i;
            if (Objects.equals(hp.alias(), p.alias())
                    && Objects.equals(hp.property(), p.property())) {
                return i;
            }
        }
        return -1;
    }

    /** How a HAVING aggregate reads back in an error message: {@code SUM(Sales.amount)}. */
    private static String describeAggregate(AliasedQuery.HavingPredicate hp) {
        if (hp.aggFn() == OqlQuery.AggFn.COUNT_STAR || hp.property() == null) {
            return hp.aggFn().display() + "(*)";
        }
        return hp.aggFn().display() + "(" + hp.alias() + "." + hp.property() + ")";
    }

    private static int findGroupKeyOrdinal(List<AliasedQuery.GroupKey> groupBy,
                                           String alias, String property) {
        for (int i = 0; i < groupBy.size(); i++) {
            AliasedQuery.GroupKey gk = groupBy.get(i);
            if (gk.alias().equals(alias) && gk.property().equals(property)) return i;
        }
        return -1;
    }

    // ── Field resolution ──────────────────────────────────────────────────────

    private RexNode fieldInScope(RelBuilder builder,
                                 Map<String, MappingCatalog.IntegratedClass> scope,
                                 String alias, String iri) {
        int offset = 0;
        for (Map.Entry<String, MappingCatalog.IntegratedClass> e : scope.entrySet()) {
            List<String> cols = sortedColumns(e.getValue());
            if (e.getKey().equals(alias)) {
                int i = cols.indexOf(iri);
                if (i < 0) throw new OqlPlanException(
                        "Internal: field '" + Names.localName(iri) + "' not found in alias '" + alias + "'");
                return builder.field(offset + i);
            }
            offset += cols.size();
        }
        throw new OqlPlanException("Internal: alias '" + alias + "' not in scope");
    }

    private void expandStar(RelBuilder builder, List<RexNode> exprs, List<String> names,
                            String alias, MappingCatalog.IntegratedClass cls,
                            Map<String, MappingCatalog.IntegratedClass> scope, boolean multiAlias) {
        for (Map.Entry<String, String> e : localToGlobal(cls).entrySet()) {
            exprs.add(fieldInScope(builder, scope, alias, e.getValue()));
            // A lone class's star keeps the full property IRIs it has always answered with
            // (ProductColumns records them as a product's columns). They used to arrive only
            // because the whole-class projection is an identity RelBuilder did not build (V70);
            // now that projections are forced, they are named here on purpose.
            names.add(multiAlias ? alias + "_" + e.getKey() : e.getValue());
        }
    }

    private static List<String> sortedColumns(MappingCatalog.IntegratedClass cls) {
        return cls.sources().stream()
                .flatMap(s -> s.columnMap().keySet().stream())
                .distinct().sorted().toList();
    }

    // ── Catalog resolution ────────────────────────────────────────────────────

    private MappingCatalog.IntegratedClass requireClass(String localName) {
        List<MappingCatalog.IntegratedClass> matches = classesByLocal.get(localName);
        if (matches == null) throw new OqlPlanException("Unknown class '" + localName
                + "'. Available: " + String.join(", ", classesByLocal.keySet()));
        if (matches.size() > 1) throw new OqlPlanException("Ambiguous class name '" + localName
                + "': " + matches.size() + " integrated classes share it ("
                + String.join(", ", matches.stream().map(MappingCatalog.IntegratedClass::iri).toList())
                + ").");
        return matches.get(0);
    }

    private MappingCatalog.JoinProperty requireJoin(String localName) {
        List<MappingCatalog.JoinProperty> matches = joinsByLocal.get(localName);
        if (matches == null) throw new OqlPlanException("Unknown join property '" + localName
                + "'. Available: " + String.join(", ", joinsByLocal.keySet()));
        if (matches.size() > 1) throw new OqlPlanException("Ambiguous join-property name '"
                + localName + "': " + matches.size() + " join properties share it.");
        return matches.get(0);
    }

    private static MappingCatalog.IntegratedClass requireAlias(
            Map<String, MappingCatalog.IntegratedClass> scope, String alias) {
        MappingCatalog.IntegratedClass c = scope.get(alias);
        if (c == null) throw new OqlPlanException("Unknown alias '" + alias
                + "'. Declared: " + String.join(", ", scope.keySet()));
        return c;
    }

    /**
     * Resolves a single property by local name without validating the whole class's
     * property set for collisions — only the requested name's own ambiguity (if any)
     * is checked. Building the full {@link #localToGlobal} map here would fail eagerly
     * on any two unrelated, unaligned same-named columns anywhere in the class, even
     * when the query never references them (F12).
     */
    private String requireProperty(MappingCatalog.IntegratedClass cls, String alias, String prop) {
        String iri = null;
        for (String candidate : allPropertyIris(cls)) {
            if (!Names.localName(candidate).equals(prop)) continue;
            if (iri != null && !iri.equals(candidate))
                throw new OqlPlanException("Class '" + Names.localName(cls.iri())
                        + "' has two properties sharing local name '" + prop + "'.");
            iri = candidate;
        }
        if (iri == null) throw new OqlPlanException("Class '" + Names.localName(cls.iri())
                + "' (alias '" + alias + "') has no property '" + prop
                + "'. Available: " + String.join(", ", availableLocalNames(cls)));
        return iri;
    }

    private static List<String> allPropertyIris(MappingCatalog.IntegratedClass cls) {
        return cls.sources().stream()
                .flatMap(s -> s.columnMap().keySet().stream())
                .distinct().sorted().toList();
    }

    private static List<String> availableLocalNames(MappingCatalog.IntegratedClass cls) {
        return allPropertyIris(cls).stream().map(Names::localName).distinct().sorted().toList();
    }

    private Map<String, String> localToGlobal(MappingCatalog.IntegratedClass cls) {
        Map<String, String> map = new LinkedHashMap<>();
        allPropertyIris(cls).forEach(iri -> {
            String ln = Names.localName(iri);
            String existing = map.putIfAbsent(ln, iri);
            if (existing != null && !existing.equals(iri))
                throw new OqlPlanException("Class '" + Names.localName(cls.iri())
                        + "' has two properties sharing local name '" + ln + "'.");
        });
        return map;
    }

    // ── Operator / literal helpers ────────────────────────────────────────────

    private static SqlOperator toOp(AliasedQuery.Op op) {
        return switch (op) {
            case EQ          -> SqlStdOperatorTable.EQUALS;
            case NE          -> SqlStdOperatorTable.NOT_EQUALS;
            case LT          -> SqlStdOperatorTable.LESS_THAN;
            case LE          -> SqlStdOperatorTable.LESS_THAN_OR_EQUAL;
            case GT          -> SqlStdOperatorTable.GREATER_THAN;
            case GE          -> SqlStdOperatorTable.GREATER_THAN_OR_EQUAL;
            case IS_NULL, IS_NOT_NULL ->
                throw new OqlPlanException("IS NULL / IS NOT NULL handled by buildPredicateNode");
        };
    }

    private static SqlOperator toHavingOp(AliasedQuery.Op op) { return toOp(op); }

    /** Every whole number below this magnitude is exact as a DOUBLE; see {@link #literal}. */
    private static final BigDecimal TWO_POW_53 = new BigDecimal("9007199254740992");

    /**
     * S6-4: an unquoted number stays a number except against a DATE or TIMESTAMP column, where
     * {@link #typedTextLiteral} turns it into a plan-time error naming the column — DuckDB refuses
     * {@code date > 2015} anyway, so the only change is a clearer message.
     *
     * <p>It is deliberately <em>not</em> typed as text against a VARCHAR column. A Calcite VARCHAR
     * says nothing about the DuckDB type: the {@code u_} view projects a VARCHAR column uncast, and
     * CsvBootstrap types every CSV column {@code xsd:string}, so a CSV column of numbers is
     * whatever {@code read_csv_auto} sniffed — BIGINT, DOUBLE. As text, {@code n > 9.5} reaches
     * DuckDB as {@code n > '9.5'}, the string is cast to BIGINT and rounds to 10, and the query
     * returns wrong rows with no error. Casting the column instead is ruled out by D6: it would
     * hide the column's input ref from selectivity estimation.
     *
     * <p>Against a VARCHAR column the number is a DOUBLE rather than Calcite's exact literal.
     * DuckDB compares a column that really holds text with a number by casting the text to the
     * number's type, and an exact {@code 2016} is an INTEGER: {@code '2016.4'} rounds to 2016, so
     * {@code = 2016} matches it and {@code <> 2016} drops it, with no error; an exact {@code 9.99}
     * is a DECIMAL(3,2) that {@code '10'} overflows. Cast to DOUBLE, each cell compares at its own
     * value. Against a column DuckDB reads as BIGINT or DOUBLE the answer is the exact one. A cell
     * that is not a number at all ({@code 'n/a'}) still fails the query, since D6's typing work is
     * what will let the plan know the physical type. The DOUBLE is used only below 2^53, where
     * every whole number is exact: past it a DOUBLE would equate neighbouring BIGINT ids, so the
     * exact literal stays and a fractional string that large still rounds to its scale.
     *
     * <p>BOOLEAN keeps the bare number too: DuckDB reads {@code flag = 1} as
     * {@code flag = true}, and {@link #typedTextLiteral}'s BOOLEAN branch would refuse it.
     */
    private static RexNode literal(RelBuilder builder, RelDataType fieldType, String columnLabel, String text) {
        if (text.startsWith("'") && text.endsWith("'")) {
            String value = text.substring(1, text.length() - 1).replace("''", "'");
            return typedTextLiteral(builder, fieldType, columnLabel, text, value);
        }
        if (text.equalsIgnoreCase("TRUE"))  return builder.literal(true);
        if (text.equalsIgnoreCase("FALSE")) return builder.literal(false);
        if (text.equalsIgnoreCase("NULL"))
            return builder.getRexBuilder().makeNullLiteral(
                    builder.getTypeFactory().createSqlType(SqlTypeName.VARCHAR));
        BigDecimal number;
        try { number = new BigDecimal(text); }
        catch (NumberFormatException e) {
            throw new OqlPlanException("Unparseable numeric literal: " + text);
        }
        return switch (fieldType.getSqlTypeName()) {
            case DATE, TIMESTAMP -> typedTextLiteral(builder, fieldType, columnLabel, text, text);
            case VARCHAR, CHAR -> number.abs().compareTo(TWO_POW_53) < 0
                    ? builder.getRexBuilder().makeApproxLiteral(number)
                    : builder.literal(number);
            default -> builder.literal(number);
        };
    }

    /**
     * V38: a WHERE literal that arrived quoted used to become an untyped CHAR {@link RexNode}
     * regardless of the column it is compared against — harmless on its own (DuckDB coerces
     * {@code WHERE year = '30'} against a numeric column at execution time), but once a denial
     * constraint is enforced on the same class, {@code RexSimplify.simplifyUsingPredicates}
     * merges the WHERE literal into a numeric range built from the constraint's own literal and
     * casts the {@code NlsString} straight to {@code BigDecimal} — a rule crash, not a bind
     * error (docs/PENDING_VALIDATION.md V38).
     *
     * <p>The fix types the literal against {@code fieldType} instead: for a numeric, DATE or
     * TIMESTAMP field — every non-VARCHAR type {@link XsdTypes#widenedType} can produce — a
     * parseable literal becomes the same properly typed {@link RexNode} the unquoted branches
     * above already build, so it survives predicate simplification like any other typed literal.
     * A literal that does not parse for that type is a clear {@link OqlPlanException} naming the
     * column, the literal and the expected type: never a crash, and never silently wrong (a
     * quietly-mistyped comparison would fail on some rows and not others depending on whether the
     * class happens to carry an enforced constraint). Every other field type — VARCHAR foremost —
     * keeps exactly today's untyped-CHAR behaviour.
     */
    private static RexNode typedTextLiteral(RelBuilder builder, RelDataType fieldType,
                                            String columnLabel, String originalText, String value) {
        RexBuilder rexBuilder = builder.getRexBuilder();
        switch (fieldType.getSqlTypeName()) {
            case BIGINT, INTEGER, SMALLINT, TINYINT, DECIMAL, DOUBLE, FLOAT, REAL -> {
                try {
                    return builder.literal(new BigDecimal(value));
                } catch (NumberFormatException e) {
                    throw new OqlPlanException("Column '" + columnLabel + "': literal " + originalText
                            + " is not a valid " + fieldType.getSqlTypeName() + " value.");
                }
            }
            case DATE -> {
                // DateString's constructor validates via Guava Preconditions.checkArgument
                // (confirmed by reading the decompiled bytecode), which throws
                // IllegalArgumentException — narrowed from RuntimeException so an unrelated bug
                // elsewhere in Calcite's own DateString/RexBuilder code cannot be silently
                // reclassified as a user-facing "bad literal" plan error.
                try {
                    return rexBuilder.makeDateLiteral(new DateString(value));
                } catch (IllegalArgumentException e) {
                    throw new OqlPlanException("Column '" + columnLabel + "': literal " + originalText
                            + " is not a valid DATE value (expected yyyy-MM-dd).");
                }
            }
            case TIMESTAMP -> {
                // Same narrowing as DATE above — TimestampString's format validation also throws
                // IllegalArgumentException via Preconditions.checkArgument, confirmed the same way.
                try {
                    return rexBuilder.makeTimestampLiteral(new TimestampString(value), fieldType.getPrecision());
                } catch (IllegalArgumentException e) {
                    throw new OqlPlanException("Column '" + columnLabel + "': literal " + originalText
                            + " is not a valid TIMESTAMP value (expected yyyy-MM-dd HH:mm:ss).");
                }
            }
            case BOOLEAN -> {
                if (value.equalsIgnoreCase("true"))  return builder.literal(true);
                if (value.equalsIgnoreCase("false")) return builder.literal(false);
                throw new OqlPlanException("Column '" + columnLabel + "': literal " + originalText
                        + " is not a valid BOOLEAN value (expected true or false).");
            }
            default -> {
                return builder.literal(value);
            }
        }
    }

    // ── WHERE tree builder ────────────────────────────────────────────────────

    /**
     * Builds a single {@link RexNode} for the WHERE clause, respecting AND/OR
     * precedence. Predicates with {@code or=true} start a new OR group;
     * consecutive non-OR predicates are AND'd within their group.
     */
    private RexNode buildWhereNode(RelBuilder builder,
                                   Map<String, MappingCatalog.IntegratedClass> scope,
                                   List<AliasedQuery.Predicate> preds) {
        List<List<AliasedQuery.Predicate>> orGroups = new ArrayList<>();
        List<AliasedQuery.Predicate> cur = new ArrayList<>();
        for (AliasedQuery.Predicate pr : preds) {
            if (pr.or() && !cur.isEmpty()) { orGroups.add(List.copyOf(cur)); cur.clear(); }
            cur.add(pr);
        }
        if (!cur.isEmpty()) orGroups.add(cur);

        List<RexNode> orNodes = new ArrayList<>();
        for (List<AliasedQuery.Predicate> group : orGroups) {
            List<RexNode> andNodes = new ArrayList<>();
            for (AliasedQuery.Predicate pr : group) andNodes.add(buildPredicateNode(builder, scope, pr));
            orNodes.add(andNodes.size() == 1 ? andNodes.get(0) : builder.and(andNodes));
        }
        return orNodes.size() == 1 ? orNodes.get(0) : builder.or(orNodes);
    }

    private RexNode buildPredicateNode(RelBuilder builder,
                                       Map<String, MappingCatalog.IntegratedClass> scope,
                                       AliasedQuery.Predicate pr) {
        MappingCatalog.IntegratedClass cls = requireAlias(scope, pr.alias());
        String iri = requireProperty(cls, pr.alias(), pr.property());
        RexNode field = fieldInScope(builder, scope, pr.alias(), iri);
        if (pr.op() == AliasedQuery.Op.IS_NULL)     return builder.isNull(field);
        if (pr.op() == AliasedQuery.Op.IS_NOT_NULL) return builder.isNotNull(field);
        String columnLabel = pr.alias() + "." + pr.property();
        return builder.call(toOp(pr.op()), field, literal(builder, field.getType(), columnLabel, pr.literal()));
    }

    /**
     * Finds the projected output column name for an ORDER BY key.
     *
     * <p>A grouped query is the awkward case, and it was broken. {@code buildAggregate} builds its
     * group keys from bare field references, and {@code RelBuilder.groupKey} keeps the input
     * field's name — which in an {@code int_}/{@code u_} view is the <em>full global property
     * IRI</em>, not the local name. So {@code SELECT Paper.venue, COUNT(*) … GROUP BY Paper.venue
     * ORDER BY Paper.venue} resolved to {@code "venue"}, {@code builder.field("venue")} found
     * nothing, and the query failed with "ORDER BY field not found in SELECT output" while the
     * field was plainly in the SELECT output. Ordering a grouped result — "top venues by paper
     * count", the single most chartable shape there is — was therefore impossible.
     *
     * <p>The grouped branch runs first: when the key is a GROUP BY key, the output name is the
     * global IRI, and that is what the caller must ask the builder for.
     *
     * <p>A null alias is a bare output name — {@code ORDER BY n} after {@code COUNT(*) AS n}.
     * It resolves against the SELECT list's output names only, and fails here rather than at
     * {@code builder.field()} so the message can list what the names actually are.
     */
    private String resolveOrderOutputName(AliasedQuery q, AliasedQuery.OrderKey ok,
                                          Map<String, MappingCatalog.IntegratedClass> scope) {
        if (ok.alias() == null) {
            return resolveBareOrderAlias(q, ok.property(), scope);
        }
        if (!q.groupBy().isEmpty()) {
            for (AliasedQuery.GroupKey gk : q.groupBy()) {
                if (ok.alias().equals(gk.alias()) && ok.property().equals(gk.property())) {
                    MappingCatalog.IntegratedClass cls = requireAlias(scope, gk.alias());
                    return requireProperty(cls, gk.alias(), gk.property());
                }
            }
        }
        for (AliasedQuery.Projection p : q.select()) {
            if (p.star() || p.isAggregate()) continue;
            if (ok.alias().equals(p.alias()) && ok.property().equals(p.property()))
                return p.as() != null ? p.as() : p.property();
        }
        // Aggregate output: match by alias (for ORDER BY on aggregate results)
        for (AliasedQuery.Projection p : q.select()) {
            if (!p.isAggregate()) continue;
            String outName = p.as() != null ? p.as() : p.aggFn().display();
            if (ok.property().equals(outName)) return outName;
        }
        // Group key output
        for (AliasedQuery.GroupKey gk : q.groupBy()) {
            if (ok.alias().equals(gk.alias()) && ok.property().equals(gk.property()))
                return ok.property();
        }
        // A star over the key's class names the column as expandStar does.
        for (AliasedQuery.Projection p : q.select()) {
            if (!p.star() || (p.alias() != null && !p.alias().equals(ok.alias()))) continue;
            MappingCatalog.IntegratedClass cls = requireAlias(scope, ok.alias());
            String iri = requireProperty(cls, ok.alias(), ok.property());
            return scope.size() > 1 ? ok.alias() + "_" + ok.property() : iri;
        }
        return ok.property(); // fallback — builder.field() will throw a clear error if missing
    }

    /**
     * Resolves a bare {@code ORDER BY <name>} against the SELECT list's output names.
     *
     * <p>Grouped and ungrouped queries name their outputs differently: a group key's output is the
     * global property IRI (see above), while a projection's is its {@code AS} alias or local
     * property name. Both are offered, so {@code ORDER BY venue} works next to {@code ORDER BY n}.
     */
    private String resolveBareOrderAlias(AliasedQuery q, String name,
                                         Map<String, MappingCatalog.IntegratedClass> scope) {
        List<String> available = new ArrayList<>();
        for (AliasedQuery.Projection p : q.select()) {
            if (p.star()) continue;
            if (p.isAggregate()) {
                String outName = p.as() != null ? p.as() : p.aggFn().display();
                if (name.equals(outName)) return outName;
                available.add(outName);
                continue;
            }
            String outName = p.as() != null ? p.as() : p.property();
            if (name.equals(outName)) {
                // A projected group key keeps the global IRI as its output name.
                if (!q.groupBy().isEmpty()) {
                    for (AliasedQuery.GroupKey gk : q.groupBy()) {
                        if (gk.alias().equals(p.alias()) && gk.property().equals(p.property())) {
                            MappingCatalog.IntegratedClass cls = requireAlias(scope, gk.alias());
                            return requireProperty(cls, gk.alias(), gk.property());
                        }
                    }
                }
                return outName;
            }
            available.add(outName);
        }
        throw new OqlPlanException("ORDER BY '" + name
                + "' does not name a SELECT output; available: " + available);
    }

}

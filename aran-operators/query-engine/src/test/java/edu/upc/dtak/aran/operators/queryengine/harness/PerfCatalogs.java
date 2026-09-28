package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.WrapperOutputCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Catalogs of a stated size, over data that really is that size.
 *
 * <h2>The property that makes this different from every other fixture here</h2>
 *
 * <p>{@code rowCount}, and each {@link ColumnProfile}'s {@code cardinality},
 * {@code uniqueness} and {@code incompleteness}, are computed from {@link PerfData} rather than
 * typed by hand. Everywhere else in this module the declared numbers exist to steer the cost
 * model and the data underneath is three rows. That is fine for "does the rule fire"; it is not
 * fine for "does the optimizer make the right call at scale", because a golden minted that way
 * pins a decision the planner made on a lie.
 *
 * <h2>Two wrapper shapes, and why both are needed</h2>
 *
 * <p>{@link WrapperShape#PLAIN} is the fixture shape used across this module:
 * {@code "SELECT * FROM ${SNAPSHOT}"}, {@code columnMap} values are physical column names, and
 * {@code srcClass} is empty.
 *
 * <p>{@link WrapperShape#WRAPPED} is what {@code MappingCatalogOperator} actually builds from a
 * bootstrapped graph: the wrapper aliases each physical column to its source-property IRI,
 * {@code columnMap} values are those IRIs, and {@code srcClass} is recorded. It matters because
 * {@code srcClass} is what makes a source claim a sources-tier alias, and that is the only path
 * that reaches {@code SourceCatalog.of} and {@code DuckDbExecutor.aliasProjection} at all. A
 * suite that only ever built PLAIN catalogs would measure a code path production does not take.
 * The same asymmetry is why {@code DdlPruningTest} carries its own {@code aliasedCatalog}.
 */
final class PerfCatalogs {

    private PerfCatalogs() {}

    static final String NS = "http://perf.aran.test/";
    static final String INT = NS + "integration/";
    static final String SRC = NS + "source/";

    static final String XSD_STRING  = "http://www.w3.org/2001/XMLSchema#string";
    static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";
    static final String XSD_DOUBLE  = "http://www.w3.org/2001/XMLSchema#double";

    /** See the class javadoc — PLAIN is the fixture shape, WRAPPED is production's. */
    enum WrapperShape { PLAIN, WRAPPED }

    /**
     * The knobs the workloads vary. Every one of them is a dimension something in the engine
     * scales with; the ones with quadratic targets are {@code sourcesPerClass} and
     * {@code columnsPerClass}, not {@code classes}.
     *
     * @param classes            integrated classes in the catalog (schema width)
     * @param sourcesPerClass    sources unioned into each class — the GAV fan-in
     * @param columnsPerClass    mapped global properties per class
     * @param rowsPerSource      real rows generated per source
     * @param groupCardinality   distinct values of the GROUP BY column
     * @param constraintsPerClass enforced denial constraints applied to each class
     * @param dirtyEveryNth      one uncastable cell every N rows in the dirty column; 0 = clean
     * @param wrapperShape       see {@link WrapperShape}
     */
    record Knobs(int classes, int sourcesPerClass, int columnsPerClass, long rowsPerSource,
                 int groupCardinality, int constraintsPerClass, int dirtyEveryNth,
                 WrapperShape wrapperShape) {

        /** What the gate runs: small enough to stay inside its budget, real enough to count rows. */
        static Knobs smoke() {
            return new Knobs(2, 2, 6, 10_000L, 1_000, 0, 10, WrapperShape.PLAIN);
        }

        /** The benchmark tier's starting point; the workloads vary one knob at a time from here. */
        static Knobs benchmark() {
            return new Knobs(2, 2, 6, 100_000L, 1_000, 0, 10, WrapperShape.WRAPPED);
        }

        Knobs withClasses(int n)          { return new Knobs(n, sourcesPerClass, columnsPerClass, rowsPerSource, groupCardinality, constraintsPerClass, dirtyEveryNth, wrapperShape); }
        Knobs withSources(int n)          { return new Knobs(classes, n, columnsPerClass, rowsPerSource, groupCardinality, constraintsPerClass, dirtyEveryNth, wrapperShape); }
        Knobs withColumns(int n)          { return new Knobs(classes, sourcesPerClass, n, rowsPerSource, groupCardinality, constraintsPerClass, dirtyEveryNth, wrapperShape); }
        Knobs withRows(long n)            { return new Knobs(classes, sourcesPerClass, columnsPerClass, n, groupCardinality, constraintsPerClass, dirtyEveryNth, wrapperShape); }
        Knobs withGroupCardinality(int n) { return new Knobs(classes, sourcesPerClass, columnsPerClass, rowsPerSource, n, constraintsPerClass, dirtyEveryNth, wrapperShape); }
        Knobs withConstraints(int n)      { return new Knobs(classes, sourcesPerClass, columnsPerClass, rowsPerSource, groupCardinality, n, dirtyEveryNth, wrapperShape); }
        Knobs withDirtyEveryNth(int n)    { return new Knobs(classes, sourcesPerClass, columnsPerClass, rowsPerSource, groupCardinality, constraintsPerClass, n, wrapperShape); }
        Knobs withWrapperShape(WrapperShape s) { return new Knobs(classes, sourcesPerClass, columnsPerClass, rowsPerSource, groupCardinality, constraintsPerClass, dirtyEveryNth, s); }
    }

    /**
     * A built catalog and everything needed to run against it.
     *
     * @param askedDatasets live and appended to by {@code resolver} — the honest proxy for
     *                      "work that would have crossed a boundary", since in production the
     *                      resolver is what downloads a snapshot. Same device as
     *                      {@code DdlPruningTest.recording}.
     * @param expectedUnionRows   rows a bare projection of one class must return
     * @param expectedGroups      groups a GROUP BY on the group column must return
     * @param expectedCastableRows rows of one source surviving TRY_CAST on the dirty column
     */
    record Built(MappingCatalog catalog,
                 Map<String, List<String>> enforced,
                 DuckDbExecutor.SnapshotResolver resolver,
                 List<String> askedDatasets,
                 Map<String, PerfData.Spec> specsByDataset,
                 Knobs knobs,
                 long expectedUnionRows,
                 long expectedGroups,
                 long expectedCastableRows,
                 List<String> connectionSetupSql) {

        /**
         * No connection setup: every snapshot-backed catalog. {@code connectionSetupSql} exists
         * for the federated tier, whose live read is an attached catalog that the connection must
         * ATTACH before any view over it binds ({@code FederatedScan.setupSql()}).
         */
        Built(MappingCatalog catalog, Map<String, List<String>> enforced,
              DuckDbExecutor.SnapshotResolver resolver, List<String> askedDatasets,
              Map<String, PerfData.Spec> specsByDataset, Knobs knobs, long expectedUnionRows,
              long expectedGroups, long expectedCastableRows) {
            this(catalog, enforced, resolver, askedDatasets, specsByDataset, knobs,
                    expectedUnionRows, expectedGroups, expectedCastableRows, List.of());
        }

        /** Local names, as OQL must spell them. */
        static String className(int c)   { return "C" + c; }
        static String keyProp()          { return "key"; }
        static String fkProp()           { return "fk"; }
        static String groupProp()        { return "grp"; }
        static String measureProp()      { return "amount"; }
        static String textProp(int n)    { return "t" + n; }
        static String dirtyProp()        { return "year"; }
    }

    // ── Column layout ────────────────────────────────────────────────────────────────
    //
    // Fixed leading columns so every workload can name the same properties; the
    // columnsPerClass knob pads with free text after them.

    private record ColSpec(String local, PerfData.Shape shape, long param, String xsd) {}

    private static List<ColSpec> columnLayout(Knobs k) {
        List<ColSpec> cols = new ArrayList<>();
        cols.add(new ColSpec(Built.keyProp(),     PerfData.Shape.UNIQUE_KEY,     0, XSD_STRING));
        cols.add(new ColSpec(Built.fkProp(),      PerfData.Shape.FK,             Math.max(1, k.rowsPerSource() / 10), XSD_STRING));
        cols.add(new ColSpec(Built.groupProp(),   PerfData.Shape.GROUP_KEY,      Math.max(1, k.groupCardinality()), XSD_STRING));
        cols.add(new ColSpec(Built.measureProp(), PerfData.Shape.MEASURE_INT,    997, XSD_INTEGER));
        cols.add(new ColSpec(Built.dirtyProp(),   PerfData.Shape.DIRTY_INT_TEXT, k.dirtyEveryNth(), XSD_INTEGER));
        cols.add(new ColSpec("ratio",             PerfData.Shape.MEASURE_DOUBLE, 0, XSD_DOUBLE));
        for (int n = cols.size(); n < k.columnsPerClass(); n++) {
            cols.add(new ColSpec(Built.textProp(n), PerfData.Shape.TEXT, 0, XSD_STRING));
        }
        return cols.subList(0, Math.max(1, Math.min(cols.size(), k.columnsPerClass())));
    }

    // ── Builders ─────────────────────────────────────────────────────────────────────

    /**
     * {@code classes} independent classes, each unioning {@code sourcesPerClass} sources, with a
     * join property from each class to the next so a chain query can be written over any prefix.
     */
    static Built star(Knobs k) throws Exception {
        return build(k, k.classes(), true);
    }

    /**
     * As {@link #star}, but no Parquet is generated and the resolver refuses to answer.
     *
     * <p>Data is generated lazily either way — a source costs a file only when the resolver is
     * asked for it — so this is not about cost. It is about intent: {@code buildDdl} and
     * {@code onlyClassesReferencedBy} are pure text functions of the catalog, so a plan-shape
     * assertion needs no rows at all, and a workload that quietly started executing one would
     * be measuring something other than what it claims. The declared {@code rowCount} and the
     * profiles are still the ones {@link PerfData} computes, so the cost model sees exactly
     * what it would see with the data present.
     */
    static Built starPlanOnly(Knobs k) throws Exception {
        return build(k, k.classes(), false);
    }

    /**
     * A catalog sized for a {@code depth}-hop path: {@code depth + 1} <em>distinct</em> classes.
     *
     * <p>Distinct is not a stylistic choice — {@code OqlLowerer.lower} rejects a self-join and any
     * class appearing twice in a FROM path, so the depth knob is hard-capped by the class count.
     */
    static Built chain(Knobs k, int depth) throws Exception {
        return build(k, depth + 1, true);
    }

    /** {@link #chain} without data — see {@link #starPlanOnly}. */
    static Built chainPlanOnly(Knobs k, int depth) throws Exception {
        return build(k, depth + 1, false);
    }

    /**
     * The join-below-union decision, isolated.
     *
     * <p>A fact class of one source joined to a dimension class of {@code dimSources} sources.
     * When each dimension source's key is genuinely unique, {@code AranRelMdRowCount} prices the
     * join off that key and distributing it over the union pays; when it is not, it does not.
     * Same fixture idea as {@code JoinUnionTransposeTest}, but over data whose uniqueness is
     * the measured truth rather than a declared one.
     */
    static Built transposeProbe(long factRows, long dimRowsPerSource, int dimSources,
                                boolean dimKeyIsUnique) throws Exception {
        Knobs k = new Knobs(2, 1, 3, factRows, 1_000, 0, 0, WrapperShape.PLAIN);

        Map<String, PerfData.Spec> specs = new LinkedHashMap<>();
        List<String> asked = new CopyOnWriteArrayList<>();

        // Fact: one source, a non-unique FK into the dimension.
        List<PerfData.Column> factCols = List.of(
                new PerfData.Column("fk", PerfData.Shape.FK, Math.max(1, dimRowsPerSource)),
                new PerfData.Column("t", PerfData.Shape.TEXT, 0));
        specs.put("fact", new PerfData.Spec("fact", factRows, factCols));

        List<MappingCatalog.SourceView> dimViews = new ArrayList<>();
        for (int s = 0; s < dimSources; s++) {
            // A unique key is UNIQUE_KEY; a non-unique one repeats every tenth of the source.
            PerfData.Column keyCol = dimKeyIsUnique
                    ? new PerfData.Column("id", PerfData.Shape.UNIQUE_KEY, 0)
                    : new PerfData.Column("id", PerfData.Shape.FK, Math.max(1, dimRowsPerSource / 10));
            PerfData.Spec spec = new PerfData.Spec("dim" + s, dimRowsPerSource,
                    List.of(keyCol, new PerfData.Column("nm", PerfData.Shape.TEXT, 0)));
            specs.put(spec.datasetId(), spec);
            dimViews.add(new MappingCatalog.SourceView(spec.datasetId(),
                    "SELECT * FROM ${SNAPSHOT}",
                    Map.of(INT + "dimKey", "id", INT + "dimName", "nm"),
                    dimRowsPerSource,
                    List.of(profileFor(keyCol, dimRowsPerSource, "id", 0),
                            profileFor(spec.columns().get(1), dimRowsPerSource, "nm", 1)),
                    "", Map.of(INT + "dimKey", XSD_STRING, INT + "dimName", XSD_STRING)));
        }

        var fact = new MappingCatalog.IntegratedClass(INT + "Fact", List.of(
                new MappingCatalog.SourceView("fact", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(INT + "factKey", "fk", INT + "factText", "t"),
                        factRows,
                        // The FK carries NO profile, deliberately. Since AranRelMdRowCount's NDV
                        // branch a profile's cardinality is live evidence, so a profile here
                        // would price the join off the FK and the decision under test would
                        // never be reached. Same premise, and the same reason, as
                        // JoinUnionTransposeTest.catalog().
                        List.of(profileFor(factCols.get(1), factRows, "t", 1)),
                        "", Map.of(INT + "factKey", XSD_STRING, INT + "factText", XSD_STRING))));
        var dim = new MappingCatalog.IntegratedClass(INT + "Dim", dimViews);
        var join = new MappingCatalog.JoinProperty(
                INT + "toDim", INT + "Fact", INT + "Dim", INT + "factKey", INT + "dimKey");

        MappingCatalog catalog = new MappingCatalog(
                Map.of(INT + "Fact", fact, INT + "Dim", dim), List.of(join), List.of(), "v1");

        return new Built(catalog, Map.of(), resolver(specs, asked), asked, specs, k,
                factRows, 0L, 0L);
    }

    // ── The shared builder ───────────────────────────────────────────────────────────

    private static Built build(Knobs k, int classCount, boolean withData) throws Exception {
        List<ColSpec> layout = columnLayout(k);
        Map<String, MappingCatalog.IntegratedClass> classes = new LinkedHashMap<>();
        List<MappingCatalog.JoinProperty> joins = new ArrayList<>();
        Map<String, List<String>> enforced = new LinkedHashMap<>();
        Map<String, PerfData.Spec> specs = new LinkedHashMap<>();
        List<String> asked = new CopyOnWriteArrayList<>();

        for (int c = 0; c < classCount; c++) {
            String classIri = INT + Built.className(c);
            List<MappingCatalog.SourceView> views = new ArrayList<>();

            for (int s = 0; s < k.sourcesPerClass(); s++) {
                String datasetId = "c" + c + "s" + s;
                // Each source's own key space ("k<c>_<s>_"), so the integrated class's key is
                // unique across the union and not merely within a source. Every foreign key
                // targets the NEXT class's source 0, which makes the edge genuinely many-to-one:
                // one matching row, not one per source. Without both halves the join fans out by
                // the right side's source count, and every row-count assertion downstream would
                // be measuring that accident instead of the join.
                String keyPrefix = "k" + c + "_" + s + "_";
                String fkPrefix = "k" + (c + 1) + "_0_";
                List<PerfData.Column> physical = layout.stream()
                        .map(col -> new PerfData.Column(col.local(), col.shape(), col.param(),
                                switch (col.shape()) {
                                    case UNIQUE_KEY -> keyPrefix;
                                    case FK -> fkPrefix;
                                    default -> "k";
                                }))
                        .toList();
                specs.put(datasetId, new PerfData.Spec(datasetId, k.rowsPerSource(), physical));

                Map<String, String> columnMap = new LinkedHashMap<>();
                Map<String, String> columnTypes = new LinkedHashMap<>();
                List<ColumnProfile> profiles = new ArrayList<>();
                for (int ci = 0; ci < layout.size(); ci++) {
                    ColSpec col = layout.get(ci);
                    String globalIri = INT + Built.className(c) + "/" + col.local();
                    // The columnMap VALUE namespace is what a ColumnProfile is keyed by
                    // (MappingCatalog.SourceView's javadoc), so it differs per wrapper shape:
                    // physical names under PLAIN, source-property IRIs under WRAPPED. Getting
                    // this wrong yields no evidence rather than an error — the exact failure the
                    // nx:sourceRef remap exists to prevent — so it is derived, never repeated.
                    String mapped = k.wrapperShape() == WrapperShape.PLAIN
                            ? col.local()
                            : SRC + datasetId + "/" + col.local();
                    columnMap.put(globalIri, mapped);
                    columnTypes.put(globalIri, col.xsd());
                    profiles.add(profileFor(physical.get(ci), k.rowsPerSource(), mapped, ci));
                }

                String wrapperSql = k.wrapperShape() == WrapperShape.PLAIN
                        ? "SELECT * FROM ${SNAPSHOT}"
                        : "SELECT " + layout.stream()
                                .map(col -> "\"" + col.local() + "\" AS \""
                                        + SRC + datasetId + "/" + col.local() + "\"")
                                .collect(Collectors.joining(", "))
                          + " FROM ${SNAPSHOT}";
                String srcClass = k.wrapperShape() == WrapperShape.PLAIN
                        ? ""
                        : SRC + "class/" + datasetId;

                views.add(new MappingCatalog.SourceView(datasetId, wrapperSql, columnMap,
                        k.rowsPerSource(), profiles, srcClass, columnTypes));
            }

            classes.put(classIri, new MappingCatalog.IntegratedClass(classIri, views));

            if (k.constraintsPerClass() > 0) {
                // Constraints are authored in LOCAL names, as the workbench writes them and as
                // both OqlPlanner.parseEnforced and DuckDbExecutor.rewriteConstraint expect.
                // Every one of them is implied by the generated data, so enforcing them removes
                // no rows — the row-count assertions stay exact while the constraint channel is
                // genuinely exercised.
                List<String> cs = new ArrayList<>();
                for (int n = 0; n < k.constraintsPerClass(); n++) {
                    cs.add(Built.measureProp() + " >= " + (-1 - n));
                }
                enforced.put(classIri, List.copyOf(cs));
            }

            if (c > 0) {
                // Cn-1.fk -> Cn.key: the FK column repeats every rowsPerSource/10 values and the
                // key is unique, so this is a genuine many-to-one edge, not a cross product.
                joins.add(new MappingCatalog.JoinProperty(
                        INT + "j" + c,
                        INT + Built.className(c - 1), INT + Built.className(c),
                        INT + Built.className(c - 1) + "/" + Built.fkProp(),
                        INT + Built.className(c) + "/" + Built.keyProp()));
            }
        }

        MappingCatalog catalog = new MappingCatalog(classes, joins, List.of(), "v1");

        ColSpec dirty = layout.stream().filter(c -> c.local().equals(Built.dirtyProp()))
                .findFirst().orElse(null);
        long castable = dirty == null ? 0L : PerfData.castableRows(
                new PerfData.Column(dirty.local(), dirty.shape(), dirty.param()), k.rowsPerSource());

        return new Built(catalog, Map.copyOf(enforced),
                withData ? resolver(specs, asked) : planOnlyResolver(asked),
                asked, specs,
                k,
                k.rowsPerSource() * k.sourcesPerClass(),
                Math.min(k.groupCardinality(), k.rowsPerSource()),
                castable);
    }


    /**
     * The same catalog with every accepted join ALSO present as a {@code DECLARED} referential
     * edge — the {@code cbo-join-elimination} dimension's B side. Mirrors what the Phase 4B
     * harvest persists for a source-declared foreign key ({@code DeclaredEdgeResolver}); the
     * profiles are untouched, so the key evidence is whatever {@link PerfData} computed
     * (a {@code UNIQUE_KEY} column really is exactly unique, which is what
     * {@code AranStatistic.isSingleColumnKey} demands before {@code AranJoinRemoveRule} may
     * fire). Note elimination additionally needs single-source classes
     * ({@code keyColumnOrder} declares no key over a union) — callers pass
     * {@code knobs.withSources(1)}.
     */
    static Built withDeclaredJoinEdges(Built b) {
        List<MappingCatalog.ReferentialEdge> declared = b.catalog().joinProperties().stream()
                .map(jp -> new MappingCatalog.ReferentialEdge(
                        jp.leftClass(), List.of(jp.leftCol()),
                        jp.rightClass(), List.of(jp.rightCol()),
                        MappingCatalog.Tier.DECLARED))
                .toList();
        MappingCatalog cat = new MappingCatalog(b.catalog().classes(),
                b.catalog().joinProperties(), b.catalog().objectProperties(), declared,
                b.catalog().schemaVersionId());
        return new Built(cat, b.enforced(), b.resolver(), b.askedDatasets(), b.specsByDataset(),
                b.knobs(), b.expectedUnionRows(), b.expectedGroups(), b.expectedCastableRows());
    }

    /**
     * The {@link WrapperOutputCache} participation map for a built catalog — the harness twin of
     * {@code QueryEngineOperator.wrapperCacheSpecs}, with the same skip rules (identity wrapper
     * with no join keys, EXPANDED classes — none exist here — and the sort-column derivation
     * through each class's own {@code columnMap}, capped at three).
     *
     * <p>The benchmark has no schema graph, so the content-addressed snapshot id is stood in for
     * by {@code datasetId + ":" + rowsPerSource}: {@link PerfData} generates deterministically per
     * (dataset, rows), so two cells at different row scales must not share a cache entry, and the
     * row count is the only knob that changes a given dataset's bytes within one JVM.
     */
    static Map<String, WrapperOutputCache.Spec> wrapperCacheSpecs(Built b) {
        UUID tenant = new UUID(0L, 0L);
        Map<String, WrapperOutputCache.Spec> specs = new LinkedHashMap<>();
        for (MappingCatalog.IntegratedClass cls : b.catalog().classes().values()) {
            String classHash = Names.sqlName(cls.iri());
            for (MappingCatalog.SourceView sv : cls.sources()) {
                List<String> sortColumns = new ArrayList<>();
                for (MappingCatalog.JoinProperty jp : b.catalog().joinProperties()) {
                    String keyProperty = cls.iri().equals(jp.leftClass()) ? jp.leftCol()
                            : cls.iri().equals(jp.rightClass()) ? jp.rightCol() : null;
                    if (keyProperty == null) {
                        continue;
                    }
                    String sourceColumn = sv.columnMap().get(keyProperty);
                    if (sourceColumn != null && !sortColumns.contains(sourceColumn)) {
                        sortColumns.add(sourceColumn);
                        if (sortColumns.size() == 3) {
                            break;
                        }
                    }
                }
                if (sortColumns.isEmpty()
                        && "select * from ${snapshot}".equalsIgnoreCase(sv.wrapperSql().trim())) {
                    continue;
                }
                String surrogateSnapshotId = sv.datasetId() + ":" + b.knobs().rowsPerSource();
                String wView = "w_" + Names.sqlName(sv.datasetId()) + "_" + classHash;
                specs.put(wView, new WrapperOutputCache.Spec(sv.datasetId(),
                        WrapperOutputCache.key(tenant, surrogateSnapshotId, sv.wrapperSql(),
                                sortColumns),
                        sortColumns));
            }
        }
        return specs;
    }

    /**
     * Refuses rather than returning something plausible. A plan-only catalog that quietly
     * resolved would let an execution slip into a workload that has no data behind it, and the
     * failure would surface later as an empty result rather than here as a mistake.
     */
    private static DuckDbExecutor.SnapshotResolver planOnlyResolver(List<String> asked) {
        return ds -> {
            asked.add(ds);
            throw new IllegalStateException(
                    "plan-only catalog has no data for " + ds + " — use star(), not starPlanOnly()");
        };
    }

    /**
     * Generates a source's Parquet the first time it is asked for, and never otherwise.
     *
     * <p>Lazy rather than eager because that is what production does: the resolver is the thing
     * that fetches a snapshot, and a pruned query fetches only what it names. Eager generation
     * would also make a fifty-class catalog cost a hundred files to answer a one-class query —
     * the very asymmetry {@code DdlPruningTest} exists to prevent, reintroduced in the fixture.
     */
    private static DuckDbExecutor.SnapshotResolver resolver(Map<String, PerfData.Spec> specs,
                                                            List<String> asked) {
        return ds -> {
            asked.add(ds);
            PerfData.Spec spec = specs.get(ds);
            if (spec == null) {
                throw new IllegalArgumentException("Unknown dataset: " + ds);
            }
            try {
                return PerfData.readExpression(PerfData.parquetFor(spec));
            } catch (Exception e) {
                throw new IllegalStateException("Could not generate data for " + ds, e);
            }
        };
    }

    /**
     * A profile whose numbers are the data's, not a guess.
     *
     * <p>Only four of the record's thirty-two components are ever read by the query engine
     * ({@code columnName}, {@code cardinality}, {@code uniqueness}, {@code incompleteness}); the
     * rest are padded, as every profile fixture in this module pads them. The ids are fixed
     * rather than random so two runs of the same workload build byte-identical catalogs.
     */
    private static ColumnProfile profileFor(PerfData.Column col, long rows, String name, int index) {
        return new ColumnProfile(
                new UUID(0L, index), new UUID(0L, 0L), name, index, "VARCHAR",
                PerfData.ndv(col, rows), null,
                PerfData.uniqueness(col, rows), PerfData.nullFraction(col, rows),
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }
}

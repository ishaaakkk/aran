package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.CatalogCacheInvalidator;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKeyRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import edu.upc.dtak.aran.operators.queryengine.plan.ProfileStatsSource;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@NullMarked
public class MappingCatalogOperator implements Operator, CatalogCacheInvalidator {

    private final GraphStore graphStore;
    private final @Nullable ProfileStatsSource profileStats;
    /**
     * Phase 4B Task 3's DECLARED-tier feed. {@code null} in any wiring that predates this task
     * (mirroring {@code profileStats}'s own nullability) — {@link #buildCatalog} then adds no
     * DECLARED edges, which is exactly today's behaviour: {@code MappingCatalog.declaredEdges}
     * defaults to empty, so a catalog built with no harvest is byte-identical to before this field
     * existed.
     */
    private final @Nullable DeclaredForeignKeyRepository declaredForeignKeys;
    /**
     * One entry per tenant, holding the catalog for that tenant's currently-active schema
     * version.
     *
     * <p>Previously keyed by {@code tenant:versionId}, which never evicted: every promotion
     * added a new entry and left the superseded catalog resident for the life of the process,
     * so memory grew without bound with promotion count × tenant count. Keying by tenant and
     * carrying the version id in the value keeps the invalidate-on-promote behaviour — a
     * version change is a miss — while bounding the map at the number of tenants.
     */
    private record CachedCatalog(String versionId, MappingCatalog catalog) {}

    private final ConcurrentHashMap<String, CachedCatalog> cache = new ConcurrentHashMap<>();

    /**
     * Pre-Task-3 shape, kept for the module's many direct-construction tests (Spring never sees
     * this one — see {@link #MappingCatalogOperator(GraphStore, ProfileStatsSource,
     * DeclaredForeignKeyRepository)}).
     */
    public MappingCatalogOperator(GraphStore graphStore,
                                   @Nullable ProfileStatsSource profileStats) {
        this(graphStore, profileStats, null);
    }

    /**
     * {@code @Autowired} is required once a second constructor exists: with none marked, Spring
     * cannot pick between two candidate constructors and fails bean creation. Marking this one
     * (the full arity) is what keeps the container wiring the same three components it always
     * has — {@code declaredForeignKeys} simply joins {@code profileStats} as a normal,
     * {@code @Nullable}-optional autowired dependency — while leaving the 2-arg constructor above
     * as plain Java, usable directly by tests that predate Task 3 and never touch Spring.
     */
    @Autowired
    public MappingCatalogOperator(GraphStore graphStore,
                                   @Nullable ProfileStatsSource profileStats,
                                   @Nullable DeclaredForeignKeyRepository declaredForeignKeys) {
        this.graphStore = graphStore;
        this.profileStats = profileStats;
        this.declaredForeignKeys = declaredForeignKeys;
    }

    @Override
    public String getOperatorId() {
        return "mapping-catalog";
    }

    @Override
    public String getDisplayName() {
        return "Mapping Catalog Extraction";
    }

    @Override
    public OperatorManifest manifest() {
        return new OperatorManifest(getOperatorId(), getDisplayName(), List.of(
                new OperationSpec("default", "Compute and return the full mapping catalog", false, List.of())
        ));
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        TenantContext tenant = new TenantContext(request.tenantId());
        try {
            MappingCatalog catalog = getCatalog(tenant);
            return OperatorResult.success(Map.of(
                    "status", "ok",
                    "classesCount", String.valueOf(catalog.classes().size()),
                    "joinPropertiesCount", String.valueOf(catalog.joinProperties().size()),
                    "objectPropertiesCount", String.valueOf(catalog.objectProperties().size())
            ));
        } catch (Exception e) {
            return OperatorResult.failure("CATALOG_ERROR",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public MappingCatalog getCatalog(TenantContext tenant) {
        GraphVersion current = graphStore.currentVersion(tenant);
        if (current == null) {
            return new MappingCatalog(Map.of(), List.of(), List.of(), null);
        }
        String versionId = String.valueOf(current.id());
        // compute() rather than computeIfAbsent(): a promoted version must replace the
        // superseded entry rather than sit alongside it.
        return cache.compute(tenant.value(), (k, cached) ->
                cached != null && cached.versionId().equals(versionId)
                        ? cached
                        : new CachedCatalog(versionId, buildCatalog(tenant, current))
        ).catalog();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Evicts the tenant's entry so the next {@link #getCatalog} rebuilds even though the graph
     * version is unchanged. The cache is keyed by version specifically so a promotion is a miss;
     * this is the one other thing that must force a rebuild — a relational catalog harvest writing
     * {@code declared_foreign_key} rows or HARVESTED profiles without promoting the graph, including
     * a harvest that <em>removes</em> a foreign key. Without this, a dropped source FK's stale
     * {@code DECLARED} edge would stay baked into the cached catalog and keep driving join
     * elimination until the next unrelated promotion. Called from the harvest-persistence path (see
     * {@code HarvestPersistenceService} / {@link CatalogCacheInvalidator}).
     */
    @Override
    public void invalidate(TenantContext tenant) {
        cache.remove(tenant.value());
    }

    private MappingCatalog buildCatalog(TenantContext tenant, GraphVersion version) {
        // The promoted G_I lives in urn:aran:schema (promote COPYs the draft into it).
        // Read it explicitly; cache remains keyed by version id so a new promotion invalidates.
        Model model = graphStore.query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:schema> { ?s ?p ?o } }");

        Map<String, MappingCatalog.IntegratedClass> classesMap = new HashMap<>();
        List<MappingCatalog.JoinProperty> joinProperties = new ArrayList<>();
        List<MappingCatalog.ObjectProperty> objectProperties = new ArrayList<>();

        // QUERY 1: Integrated classes and their source wrappers + property alias map.
        //
        // F13: the `?srcProp rdfs:range ?range`
        // triple pattern and its xsd: filter below are load-bearing, not decoration.
        // JsonBootstrap gives EVERY property — including a nested object's own
        // object-property (whose rdfs:range is another rdfs:Class, not an xsd:
        // datatype) — an rdfs:domain triple on its parent class. Without this
        // filter, an unaligned nested-object property (e.g. a JSON source's
        // `venue: {code, label}`) was pulled into columnMap here even though
        // WrapperSqlBuilder.leafPropertiesOf (the bootstrapping module) already
        // excludes exactly the same shape from the wrapper SQL itself — so the
        // int_ view's UNION ALL projection (DuckDbExecutor) ended up referencing
        // a column the wrapper view never defines, and DuckDB's prepare() failed
        // for the WHOLE class, not just a query touching that property. Mirrors
        // WrapperSqlBuilder.leafPropertiesOf's own xsd: filter exactly, so the two
        // no longer disagree about what counts as a flat data column.
        String query1 = """
            PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>
            PREFIX nx:   <http://www.essi.upc.edu/DTIM/NextiaDI/>

            SELECT ?intClass ?srcClass ?datasetId ?wrapperSql ?srcProp ?targetProp ?range ?sourceRef ?expansion WHERE {
              ?intClass a nx:IntegratedResource ; a rdfs:Class .
              ?srcClass (rdfs:subClassOf|nx:subClassOf) ?intClass ;
                        nx:wrapperSql ?wrapperSql .
              OPTIONAL { ?srcClass nx:datasetId  ?dsId . }
              OPTIONAL { ?srcClass nx:provenance ?prov . }
              BIND(COALESCE(?dsId, STR(?prov)) AS ?datasetId)
              # Per SOURCE CLASS (unlike ?sourceRef below, which is per srcProp): whether Phase
              # 3A's WrapperExpansion probe has verified this wrapper plans over its src_
              # relation. Absent on every pre-Phase-3A graph and on any wrapper never (re)probed
              # since — the read loop below defaults that to "OPAQUE", never an error.
              OPTIONAL { ?srcClass nx:wrapperExpansion ?expansion . }

              OPTIONAL {
                  ?srcProp rdfs:domain ?srcClass ;
                           rdfs:range  ?range .
                  FILTER ( STRSTARTS(STR(?range), STR(xsd:)) )
                  OPTIONAL {
                    ?srcProp (rdfs:subPropertyOf|nx:subPropertyOf) ?ip .
                    ?ip a nx:IntegratedResource .
                  }
                  BIND(COALESCE(?ip, ?srcProp) AS ?targetProp)
                  OPTIONAL { ?srcProp nx:sourceRef ?sourceRef . }
              }
            }
            ORDER BY ?intClass ?srcClass
            """;

        Map<String, Map<String, MappingCatalog.SourceView>> tempViews = new HashMap<>();
        // Per source class: raw physical column name (nx:sourceRef) → source property IRI.
        // Needed because freyja profiles are keyed by the raw name while columnMap values —
        // the namespace every plan-time profile lookup resolves through — are property IRIs.
        Map<String, Map<String, String>> refsBySrcClass = new HashMap<>();
        // Task 3: dataset id (the same string QUERY 1 binds as ?datasetId, below) -> source class
        // IRI. A harvested foreign key names its endpoint by Aran dataset id, never by source
        // class IRI, so DeclaredEdgeResolver needs this reverse index to place it at all.
        Map<String, String> srcClassByDatasetId = new HashMap<>();

        try (QueryExecution qexec = QueryExecutionFactory.create(query1, model)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                String intClass = qs.getResource("intClass").getURI();
                String srcClass = qs.getResource("srcClass").getURI();
                String wrapperSql = qs.getLiteral("wrapperSql").getString();
                String datasetId = "unknown";
                if (qs.contains("datasetId")) {
                    var node = qs.get("datasetId");
                    if (node != null && node.isLiteral()) {
                        datasetId = node.asLiteral().getString();
                    } else if (node != null && node.isResource()) {
                        datasetId = node.asResource().getURI();
                    }
                }
                // Per srcClass, like datasetId/wrapperSql above — every row for this srcClass
                // binds the same value (or none), so reading it here and using it only on the
                // putIfAbsent below (first row wins) is correct.
                String expansionStatus = "OPAQUE";
                if (qs.contains("expansion")) {
                    var expNode = qs.get("expansion");
                    if (expNode != null && expNode.isLiteral()) {
                        expansionStatus = expNode.asLiteral().getString();
                    }
                }

                tempViews.putIfAbsent(intClass, new HashMap<>());
                Map<String, MappingCatalog.SourceView> viewsForInt = tempViews.get(intClass);

                viewsForInt.putIfAbsent(srcClass, new MappingCatalog.SourceView(
                        datasetId, wrapperSql, new HashMap<>(), 0L, List.of(), srcClass,
                        new HashMap<>(), new HashMap<>(), expansionStatus));
                MappingCatalog.SourceView view = viewsForInt.get(srcClass);
                // Same idempotence as the putIfAbsent above: one dataset backs one source class in
                // every shape this schema produces, so first-row-wins is correct here too.
                srcClassByDatasetId.putIfAbsent(datasetId, srcClass);

                if (qs.contains("srcProp") && qs.contains("targetProp")) {
                    String srcProp = qs.getResource("srcProp").getURI();
                    String targetProp = qs.getResource("targetProp").getURI();
                    view.columnMap().put(targetProp, srcProp);
                    if (qs.contains("range")) {
                        view.columnTypes().put(targetProp, qs.getResource("range").getURI());
                    }
                    if (qs.contains("sourceRef") && qs.get("sourceRef").isLiteral()) {
                        String sourceRef = qs.getLiteral("sourceRef").getString();
                        refsBySrcClass.computeIfAbsent(srcClass, k -> new HashMap<>())
                                .putIfAbsent(sourceRef, srcProp);
                        // The same fact, stored directly on the view (Task 1, phase 3A) rather
                        // than only feeding remapProfiles below — SrcTable (Task 2) reads it
                        // from here.
                        view.sourceRefs().putIfAbsent(sourceRef, srcProp);
                    }
                }
            }
        }

        // Populate row counts from the profiling store (skipped when profileStats is not wired).
        // Memoised per dataset: one dataset commonly backs several integrated classes, and the
        // loop below would otherwise re-read it once per (class, source) pair.
        if (profileStats != null) {
            Map<String, ProfileStatsSource.DatasetStats> statsCache = new HashMap<>();
            for (Map<String, MappingCatalog.SourceView> viewsForInt : tempViews.values()) {
                for (Map.Entry<String, MappingCatalog.SourceView> e : viewsForInt.entrySet()) {
                    MappingCatalog.SourceView sv = e.getValue();
                    ProfileStatsSource.DatasetStats stats = statsCache.computeIfAbsent(
                            sv.datasetId(), ds -> profileStats.statsFor(tenant, ds));
                    if (stats.isEmpty()) {
                        continue;
                    }

                    // Phase 3B selection rule:
                    // an OPAQUE view prefers its OWN WRAPPER_OUTPUT evidence when present — the
                    // wrapper could not be seen through to its physical columns, so freyja profiled
                    // its resolved output directly instead, and that evidence is used as-is (no
                    // remap: WRAPPER_OUTPUT rows are already named in columnMap-value namespace).
                    // An EXPANDED view always takes the SNAPSHOT path below, even if a stale
                    // WRAPPER_OUTPUT row for it exists — it already plans over its own src_
                    // relation (WrapperExpansion), which SrcTable (Phase 3A) must keep reading
                    // SNAPSHOT-subject evidence for.
                    if ("OPAQUE".equals(sv.expansionStatus())) {
                        ProfileStatsSource.DatasetStats wrapperOutput =
                                ProfileStatsSource.wrapperOutputFor(stats.profiles(), sv.srcClass());
                        if (wrapperOutput.isEmpty()) {
                            // Gap 2 follow-up: a FEDERATED class has no lake snapshot for freyja
                            // to profile, so its class-scoped evidence is the profiling suite
                            // executed over its live binding instead — same wrapper-shaped rows,
                            // second in preference so a snapshot-backed class's evidence (which
                            // does not decay under source commits) always wins.
                            wrapperOutput = ProfileStatsSource
                                    .federatedLiveFor(stats.profiles(), sv.srcClass());
                        }
                        if (!wrapperOutput.isEmpty()) {
                            e.setValue(new MappingCatalog.SourceView(
                                    sv.datasetId(), sv.wrapperSql(), sv.columnMap(),
                                    wrapperOutput.rowCount(), wrapperOutput.profiles(),
                                    sv.srcClass(), sv.columnTypes(),
                                    sv.sourceRefs(), sv.expansionStatus()));
                            continue;
                        }
                    }

                    // Rebuilds the view to carry the fresh row count/profiles, so it must
                    // pass columnTypes/sourceRefs/expansionStatus through explicitly too —
                    // the 7-arg constructor is a convenience that defaults the latter two
                    // (and an even shorter one columnTypes too), which would silently discard
                    // every range/raw-ref/verified-expansion fact already harvested for this
                    // source (the exact phase-2 wipe bug — see
                    // sourceMetadataSurvivesProfileStatsReconstruction).
                    //
                    // Profiles arrive keyed by the RAW physical column name (freyja profiles
                    // the snapshot, not the wrapper view), but every plan-time lookup
                    // (WrapperTable.profileAt and its three siblings) resolves ordinal →
                    // columnMap VALUE → profile by name equality — and columnMap values are
                    // source-property IRIs, the wrapper view's own column names. Without the
                    // rename below no profile ever matched on a real bootstrapped catalog,
                    // so NDV/null-fraction/key evidence silently never reached the planner.
                    // A profile with no nx:sourceRef match passes through unchanged — same
                    // no-evidence outcome as before, never an error — and any WRAPPER_OUTPUT row
                    // in this dataset's full profile list (this class's own, if it fell through
                    // above with no match — impossible — or another class's, sharing the same
                    // dataset) is excluded rather than renamed: it is not evidence about the
                    // physical snapshot this path describes. The row count is re-derived from
                    // exactly that remapped/filtered set, not stats.rowCount()'s unfiltered one,
                    // so a foreign WRAPPER_OUTPUT profile on the same dataset cannot skew it.
                    List<ColumnProfile> remapped = remapProfiles(stats.profiles(),
                            refsBySrcClass.getOrDefault(e.getKey(), Map.of()));
                    if (remapped.isEmpty()) {
                        continue;
                    }
                    e.setValue(new MappingCatalog.SourceView(
                            sv.datasetId(), sv.wrapperSql(), sv.columnMap(),
                            ProfileStatsSource.estimateRowCount(remapped), remapped,
                            sv.srcClass(), sv.columnTypes(),
                            sv.sourceRefs(), sv.expansionStatus()));
                }
            }
        }

        for (Map.Entry<String, Map<String, MappingCatalog.SourceView>> entry : tempViews.entrySet()) {
            // Freeze each source view's columnMap/columnTypes/sourceRefs so the cached catalog
            // is fully immutable.
            List<MappingCatalog.SourceView> sources = entry.getValue().values().stream()
                    .map(sv -> new MappingCatalog.SourceView(
                            sv.datasetId(), sv.wrapperSql(), Map.copyOf(sv.columnMap()),
                            sv.rowCount(), List.copyOf(sv.profiles()), sv.srcClass(),
                            Map.copyOf(sv.columnTypes()), Map.copyOf(sv.sourceRefs()),
                            sv.expansionStatus()))
                    .toList();
            classesMap.put(entry.getKey(), new MappingCatalog.IntegratedClass(entry.getKey(), sources));
        }

        // QUERY 2: Join properties
        String query2 = """
            PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX nx:   <http://www.essi.upc.edu/DTIM/NextiaDI/>
            
            # DISTINCT: one P_s accumulates an nx:joinOnLeft per contributing source (N sources
            # aligned onto the same join edge), which this pattern would cross-product into N×M
            # rows. Once each side resolves to its P_l below those rows are identical, and the
            # planner rejects the repeated join-property name as ambiguous. Collapsing them
            # yields the single edge OQL refers to by name.
            SELECT DISTINCT ?joinProp ?leftClass ?rightClass ?leftCol ?rightCol WHERE {
              ?joinProp a nx:JoinProperty ;
                        rdfs:domain ?leftClass ;
                        rdfs:range  ?rightClass ;
                        nx:joinOnLeft  ?rawLeft ;
                        nx:joinOnRight ?rawRight .
              # joinOnLeft/joinOnRight name the *source* properties. In the two-node join form
              # (Fig. 14) those are sub-properties of P_l, so QUERY 1 names the int_ view's
              # column after P_l, not after the source property. Resolve through the same
              # COALESCE here or the planner looks for a column the int_ view does not expose.
              # Falls back to the raw property when it has no integrated parent.
              #
              # `nx:JoinProperty ?joinProp` pins the parent to THIS edge's own P_l, and is
              # load-bearing rather than decorative. A join column often has more than one
              # integrated parent: aligning two sources' key columns to each other as a
              # datatype — which any union of two sources does — makes that column a
              # sub-property of the datatype IR, and the join then makes it a sub-property of
              # P_l as well. Unconstrained, this OPTIONAL matches BOTH, so the same edge comes
              # back once per parent with a different leftCol/rightCol, DISTINCT has nothing
              # to collapse, and the planner rejects the repeated name as an ambiguous
              # join-property. SchemaIntegrator links P_l to its P_s (`m.add(pL, joinPropPred,
              # pS)`) precisely so the right parent can be identified here.
              OPTIONAL {
                ?rawLeft (rdfs:subPropertyOf|nx:subPropertyOf) ?ipLeft .
                ?ipLeft a nx:IntegratedResource ;
                        nx:JoinProperty ?joinProp .
              }
              OPTIONAL {
                ?rawRight (rdfs:subPropertyOf|nx:subPropertyOf) ?ipRight .
                ?ipRight a nx:IntegratedResource ;
                         nx:JoinProperty ?joinProp .
              }
              BIND(COALESCE(?ipLeft,  ?rawLeft)  AS ?leftCol)
              BIND(COALESCE(?ipRight, ?rawRight) AS ?rightCol)
            }
            """;

        try (QueryExecution qexec = QueryExecutionFactory.create(query2, model)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                joinProperties.add(new MappingCatalog.JoinProperty(
                        qs.getResource("joinProp").getURI(),
                        qs.getResource("leftClass").getURI(),
                        qs.getResource("rightClass").getURI(),
                        qs.getResource("leftCol").getURI(),
                        qs.getResource("rightCol").getURI()
                ));
            }
        }

        // QUERY 3: Object properties
        String query3 = """
            PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX nx:   <http://www.essi.upc.edu/DTIM/NextiaDI/>
            
            SELECT ?objProp ?domainClass ?rangeClass WHERE {
              ?objProp a nx:IntegratedResource ; a rdf:Property ;
                       rdfs:domain ?domainClass ;
                       rdfs:range  ?rangeClass .
              ?rangeClass a rdfs:Class .
              # A JoinProperty also matches the shape above (it is an IntegratedResource
              # of type Property whose domain/range are classes). It is NOT a regular
              # object property — it is handled by QUERY 2 — so it must be excluded here,
              # otherwise every join is double-counted as an object property.
              FILTER NOT EXISTS { ?objProp a nx:JoinProperty }
            }
            """;

        try (QueryExecution qexec = QueryExecutionFactory.create(query3, model)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                objectProperties.add(new MappingCatalog.ObjectProperty(
                        qs.getResource("objProp").getURI(),
                        qs.getResource("domainClass").getURI(),
                        qs.getResource("rangeClass").getURI()
                ));
            }
        }

        // DECLARED edges (Task 3): resolved fresh at every catalog build, never persisted
        // pre-resolved — the same discipline QUERY 1/2 already apply to every other source→
        // integrated fact, since a re-alignment can change what a source class/column resolves to
        // between one build and the next. Skipped entirely (declaredForeignKeys == null) in any
        // wiring that predates this task, or when nothing has been harvested yet — either way
        // MappingCatalog.declaredEdges defaults to empty and referentialEdges() yields exactly the
        // HYPOTHESIS edges it always did.
        List<MappingCatalog.ReferentialEdge> declaredEdges = List.of();
        if (declaredForeignKeys != null) {
            List<DeclaredForeignKey> harvested = declaredForeignKeys.findAllByTenant(tenant);
            if (!harvested.isEmpty()) {
                declaredEdges = DeclaredEdgeResolver.resolve(
                        model, harvested, srcClassByDatasetId, refsBySrcClass);
            }
        }

        return new MappingCatalog(Map.copyOf(classesMap), List.copyOf(joinProperties),
                List.copyOf(objectProperties), declaredEdges, version.id().toString());
    }

    /**
     * Renames each SNAPSHOT profile from its raw physical column name into the source-property
     * IRI the wrapper view exposes it as — the namespace {@code columnMap}'s values are in, which
     * is the namespace every plan-time profile lookup matches against. A profile whose name has no
     * {@code nx:sourceRef} entry passes through unchanged.
     *
     * <p>A {@code WRAPPER_OUTPUT}-subject profile is excluded entirely (Phase 3B), never renamed
     * and never passed through: it is already in columnMap-value namespace (evidence about the
     * wrapper's <em>output</em>, not the physical snapshot this path describes), so it is either
     * this class's own — impossible, the caller only reaches this path when
     * {@code ProfileStatsSource.wrapperOutputFor} found none — or another class's, sharing the
     * same dataset, and must not leak into this {@code SourceView} either way.
     *
     * <p>A {@code HARVESTED}-subject profile is renamed and passed through like a SNAPSHOT one —
     * both describe the same physical relation — <em>unless</em> a SNAPSHOT profile for that same
     * column is present, in which case the harvest's estimate is dropped in favour of freyja's
     * exact measurement. See the inline comment for why order-independence here is a correctness
     * property, not a preference.
     */
    private static List<ColumnProfile> remapProfiles(List<ColumnProfile> profiles,
                                                     Map<String, String> refToPropIri) {
        if (profiles.isEmpty()) {
            return profiles;
        }
        // Exact evidence wins over an ANALYZE estimate for the same physical column.
        //
        // Flyway V36 (V40/V44) widened column_profile's uniqueness key to include `subject`, which
        // is what finally lets one dataset carry BOTH freyja's SNAPSHOT profile and the relational
        // catalog harvest's HARVESTED one for a column — the coexistence both writers' merge
        // routines were already written for, and which the schema alone forbade. It also makes the
        // pair reachable here for the first time, and every plan-time lookup below this line
        // resolves a column to a profile by FIRST NAME MATCH (WrapperTable.profileAt,
        // WrapperStatistic/AranStatistic.isSingleColumnKey). Leaving both in would make NDV — and
        // whether the column is reported as a verified key at all — depend on row order, which is
        // not something a plan may depend on. Dropping the estimate wherever an exact measurement
        // of the same column exists makes the preference explicit, and it is the one AranStatistic
        // already encodes by refusing to derive a key from a HARVESTED row at all. A HARVESTED row
        // for a column freyja never profiled passes through untouched: a federated dataset with no
        // snapshot still costs off its harvest.
        Set<String> exactlyProfiled = profiles.stream()
                .filter(p -> "SNAPSHOT".equals(p.subject()))
                .map(ColumnProfile::columnName)
                .collect(Collectors.toSet());
        return profiles.stream()
                // Class-scoped, wrapper-shaped subjects are not evidence about the physical
                // snapshot this path describes — FEDERATED_LIVE for the same reason as
                // WRAPPER_OUTPUT (Gap 2 follow-up: its column names are property IRIs too).
                .filter(p -> !"WRAPPER_OUTPUT".equals(p.subject())
                        && !ColumnProfile.SUBJECT_FEDERATED_LIVE.equals(p.subject()))
                .filter(p -> !(HarvestedColumnProfiles.SUBJECT.equals(p.subject())
                        && exactlyProfiled.contains(p.columnName())))
                .map(p -> {
                    String propIri = refToPropIri.get(p.columnName());
                    return propIri == null ? p : withColumnName(p, propIri);
                })
                .toList();
    }

    /** Copies every component, subject/classIri included — a rename must never reset either. */
    private static ColumnProfile withColumnName(ColumnProfile p, String columnName) {
        return new ColumnProfile(
                p.id(), p.datasetId(), columnName, p.columnIndex(), p.sourceType(),
                p.cardinality(), p.entropy(), p.uniqueness(), p.incompleteness(),
                p.frequencyAvg(), p.frequencyMin(), p.frequencyMax(), p.frequencySd(),
                p.frequencyIqr(),
                p.valPctMin(), p.valPctMax(), p.valPctStd(), p.constancy(),
                p.lenMaxWord(), p.lenMinWord(), p.lenAvgWord(),
                p.numberWords(), p.wordsCntMax(), p.wordsCntMin(), p.wordsCntAvg(),
                p.wordsCntSd(),
                p.firstWord(), p.lastWord(),
                p.isEmpty(), p.isBinary(),
                p.commonValues(), p.commonValuesSoundex(),
                p.subject(), p.classIri(),
                p.valueHashSample());
    }
}

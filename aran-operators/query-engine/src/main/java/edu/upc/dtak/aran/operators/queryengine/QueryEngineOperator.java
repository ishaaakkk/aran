package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.util.Credentials;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.WrapperSqlValidator;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.domain.ProductTable;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ParameterSpec;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlQuery;
import edu.upc.dtak.aran.operators.queryengine.plan.OqlPlanner;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanExecutor;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanShape;
import edu.upc.dtak.aran.operators.queryengine.plan.ProductColumns;
import edu.upc.dtak.aran.operators.queryengine.plan.WrapperExpansion;
import org.apache.calcite.rel.RelNode;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * QueryEngineOperator — the read surface over the GAV mediator (phases 5–6).
 *
 * <p>This is the operator the frontend Query view talks to. It does <em>not</em> implement OQL
 * (the OQL parser + rewriter are phases 7–8 and are not built yet); instead it exposes the three
 * capabilities the mediator already has, so the UI can be honest about what exists:
 *
 * <ul>
 *   <li><b>catalog</b> — the derived GAV mapping catalog for the active global schema G:
 *       every integrated class, the source views it unfolds to, and the per-source
 *       global-property → source-property column maps. This is {@link MappingCatalogOperator}'s
 *       output, serialised for the UI.</li>
 *   <li><b>explain</b> — the GAV unfolding as DuckDB DDL: the {@code src_*}, {@code w_*_*} and
 *       {@code int_*} view definitions the engine materialises. Generated via
 *       {@link DuckDbExecutor#buildDdl} so it is byte-for-byte what {@code run} executes.</li>
 *   <li><b>run</b> — execute a read-only SQL statement over the materialised {@code int_*} views
 *       and return the rows. Snapshots are resolved from the lake on demand.</li>
 * </ul>
 *
 * <p>Column names in the {@code int_*} views are the <em>global property IRIs</em> (that is what the
 * wrapper SQL aliases them to), so a hand-written query references them by their full IRI. The UI
 * scaffolds {@code SELECT * FROM int_<Class>} from the catalog so users need not type IRIs by hand.
 *
 * <p>Registered automatically by Spring component scanning, like every other {@link Operator}.
 */
@Component
@NullMarked
public class QueryEngineOperator implements Operator {

    private static final Logger log = LoggerFactory.getLogger(QueryEngineOperator.class);
    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SCHEMA_GRAPH = "urn:aran:schema";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
    /** The XSD numeric datatypes {@code cube-evidence} calls a measure candidate — see {@link #isNumericXsd}. */
    private static final Set<String> NUMERIC_XSD = Set.of(
            "decimal", "integer", "int", "long", "short", "byte", "double", "float",
            "negativeInteger", "nonNegativeInteger", "nonPositiveInteger", "positiveInteger",
            "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte");
    /** Cap returned rows so a broad SELECT cannot produce an unbounded JSON payload. */
    private static final int MAX_ROWS = 1000;

    private final GraphStore graphStore;
    private final MappingCatalogOperator catalogOperator;
    private final LakeClient lake;
    private final ObjectMapper mapper;
    private final IntegrationModelRepository modelRepository;
    private final DatasetRepository datasetRepository;
    private final DataRepositoryRepository dataRepositoryRepository;
    private final SnapshotCache snapshotCache;
    private final DataProductRepository dataProductRepository;
    /** Gap 2: fetches an Iceberg table's fresh metadata location at query time; null in a build
     * without the iceberg adapter on the classpath (a federated Iceberg read then fails loudly). */
    private final @Nullable IcebergCatalogClient icebergCatalog;
    private final WrapperOutputCache wrapperOutputCache;
    private final PlacementConfig placementConfig;
    private final PlannerConfig plannerConfig;
    private final edu.upc.dtak.aran.core.domain.@Nullable DatasetRefreshRepository datasetRefreshRepository;

    /** Test-friendly constructor without the Iceberg client — every existing call site's shape. */
    public QueryEngineOperator(GraphStore graphStore,
                               MappingCatalogOperator catalogOperator,
                               LakeClient lake,
                               ObjectMapper mapper,
                               IntegrationModelRepository modelRepository,
                               DatasetRepository datasetRepository,
                               DataRepositoryRepository dataRepositoryRepository,
                               SnapshotCache snapshotCache,
                               DataProductRepository dataProductRepository) {
        this(graphStore, catalogOperator, lake, mapper, modelRepository, datasetRepository,
                dataRepositoryRepository, snapshotCache, dataProductRepository, Optional.empty(),
                WrapperOutputCache.disabled());
    }

    /** As above with an Iceberg client, wrapper-output caching off — the pre-cache shape. */
    public QueryEngineOperator(GraphStore graphStore,
                               MappingCatalogOperator catalogOperator,
                               LakeClient lake,
                               ObjectMapper mapper,
                               IntegrationModelRepository modelRepository,
                               DatasetRepository datasetRepository,
                               DataRepositoryRepository dataRepositoryRepository,
                               SnapshotCache snapshotCache,
                               DataProductRepository dataProductRepository,
                               Optional<IcebergCatalogClient> icebergCatalog) {
        this(graphStore, catalogOperator, lake, mapper, modelRepository, datasetRepository,
                dataRepositoryRepository, snapshotCache, dataProductRepository, icebergCatalog,
                WrapperOutputCache.disabled());
    }

    /** As above with a wrapper cache, placement at defaults with no refresh store — placement
     * then finds no legal snapshot leaf and every dual-access dataset keeps today's behavior. */
    public QueryEngineOperator(GraphStore graphStore,
                               MappingCatalogOperator catalogOperator,
                               LakeClient lake,
                               ObjectMapper mapper,
                               IntegrationModelRepository modelRepository,
                               DatasetRepository datasetRepository,
                               DataRepositoryRepository dataRepositoryRepository,
                               SnapshotCache snapshotCache,
                               DataProductRepository dataProductRepository,
                               Optional<IcebergCatalogClient> icebergCatalog,
                               WrapperOutputCache wrapperOutputCache) {
        this(graphStore, catalogOperator, lake, mapper, modelRepository, datasetRepository,
                dataRepositoryRepository, snapshotCache, dataProductRepository, icebergCatalog,
                wrapperOutputCache, PlacementConfig.defaults(), PlannerConfig.defaults(),
                Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QueryEngineOperator(GraphStore graphStore,
                               MappingCatalogOperator catalogOperator,
                               LakeClient lake,
                               ObjectMapper mapper,
                               IntegrationModelRepository modelRepository,
                               DatasetRepository datasetRepository,
                               DataRepositoryRepository dataRepositoryRepository,
                               SnapshotCache snapshotCache,
                               DataProductRepository dataProductRepository,
                               Optional<IcebergCatalogClient> icebergCatalog,
                               WrapperOutputCache wrapperOutputCache,
                               PlacementConfig placementConfig,
                               PlannerConfig plannerConfig,
                               // Optional: legality of a snapshot leaf needs the refresh row's
                               // policy + last-refresh time; a build without the store simply
                               // never finds a legal snapshot leaf (live-only, today's behavior).
                               Optional<edu.upc.dtak.aran.core.domain.DatasetRefreshRepository>
                                       datasetRefreshRepository) {
        this.graphStore = graphStore;
        this.catalogOperator = catalogOperator;
        this.lake = lake;
        this.mapper = mapper;
        this.modelRepository = modelRepository;
        this.datasetRepository = datasetRepository;
        this.dataRepositoryRepository = dataRepositoryRepository;
        this.snapshotCache = snapshotCache;
        this.dataProductRepository = dataProductRepository;
        this.icebergCatalog = icebergCatalog.orElse(null);
        this.wrapperOutputCache = wrapperOutputCache;
        this.placementConfig = placementConfig;
        this.plannerConfig = plannerConfig;
        this.datasetRefreshRepository = datasetRefreshRepository.orElse(null);
    }

    @Override
    public String getOperatorId() {
        return "query-engine";
    }

    @Override
    public String getDisplayName() {
        return "Query Engine (GAV)";
    }

    @Override
    public OperatorManifest manifest() {
        return new OperatorManifest(getOperatorId(), getDisplayName(), List.of(
                new OperationSpec("catalog", "Return the GAV mapping catalog for the active schema", false, List.of()),
                new OperationSpec("cube-evidence",
                        "Return dimensional-modeling evidence for the active schema: full IRIs, XSD ranges, "
                                + "profile statistics and tier-tagged referential edges",
                        false, List.of()),
                new OperationSpec("explain", "Return DuckDB DDL statements for the active schema views", false, List.of()),
                new OperationSpec("run", "Execute raw SQL against the snapshot views", false, List.of(
                        ParameterSpec.required("sql", "SQL SELECT query"),
                        ParameterSpec.optional("offset", "Row offset of the page to return", "0")
                )),
                new OperationSpec("rewrite-oql", "Parse OQL and return the generated SQL without executing", false, List.of(
                        ParameterSpec.required("oql", "OQL query string")
                )),
                new OperationSpec("run-oql", "Parse, optimize, and execute an OQL query", false, List.of(
                        ParameterSpec.required("oql", "OQL query string"),
                        ParameterSpec.optional("offset", "Row offset of the page to return", "0"),
                        ParameterSpec.optional("limit", "Page size (max 1000)", "1000")
                )),
                new OperationSpec("validate-constraint", "Validate a SQL constraint expression against a class", false, List.of(
                        ParameterSpec.required("expression", "SQL boolean predicate expression"),
                        ParameterSpec.optional("classIri", "Integrated class IRI to validate against", null)
                )),
                new OperationSpec("verify-wrapper",
                        "Compile-time round-trip check: does a candidate wrapper SQL text expand safely?",
                        false, List.of(
                        ParameterSpec.required("datasetId", "Dataset id the wrapper reads from"),
                        ParameterSpec.required("classIri", "Integrated class IRI this wrapper is a source of"),
                        ParameterSpec.required("sql", "Candidate wrapper SQL text (references ${SNAPSHOT})")
                )),
                new OperationSpec("materialize-oql", "Execute OQL and write the full, uncapped result to a file", true, List.of(
                        ParameterSpec.required("oql", "OQL query string"),
                        ParameterSpec.optional("format", "PARQUET (default) or CSV", "PARQUET")
                )),
                new OperationSpec("preview-parquet", "Preview rows from a materialized parquet snapshot", false, List.of(
                        ParameterSpec.required("snapshotId", "Lake snapshot UUID"),
                        ParameterSpec.optional("limit", "Max rows to return (max 1000)", "100")
                )),
                new OperationSpec("materialize-product-sql",
                        "Execute plain SQL over materialized data products — no GAV rewrite — and write the full result to a file",
                        true, List.of(
                        ParameterSpec.required("sql", "SQL SELECT over the products schema")
                )),
                new OperationSpec("materialize-source-sql",
                        "Execute plain SQL over the sources-tier alias views — no GAV rewrite — and write the full result to a file",
                        true, List.of(
                        ParameterSpec.required("sql", "SQL SELECT over the sources schema")
                ))
        ));
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        TenantContext tenant = new TenantContext(request.tenantId());
        try {
            return switch (request.operation()) {
                case "catalog"      -> catalog(tenant);
                case "cube-evidence" -> cubeEvidence(tenant);
                case "explain"      -> explain(tenant);
                case "run"          -> run(tenant, request);
                case "rewrite-oql"  -> rewriteOql(tenant, request);
                case "run-oql"      -> runOql(tenant, request);
                case "validate-constraint" -> validateConstraint(tenant, request);
                case "verify-wrapper" -> verifyWrapper(tenant, request);
                case "materialize-oql" -> materializeOql(tenant, request);
                case "preview-parquet" -> previewParquet(tenant, request);
                case "materialize-product-sql" -> materializeProductSql(tenant, request);
                case "materialize-source-sql" -> materializeSourceSql(tenant, request);
                default -> OperatorResult.failure(
                        "UNKNOWN_OPERATION",
                        "query-engine has no operation '" + request.operation()
                                + "' (expected: catalog | cube-evidence | explain | run | rewrite-oql | run-oql"
                                + " | validate-constraint | verify-wrapper | materialize-oql"
                                + " | preview-parquet | materialize-product-sql | materialize-source-sql)");
            };
        } catch (Exception e) {
            // The password lives INSIDE a federated scan expression (see rootMessage's javadoc),
            // so the raw cause chain must not reach log storage either: log the scrubbed message
            // with the stack frames, not the exception's own toString chain.
            log.error("query-engine '{}' failed: {}\n{}", request.operation(),
                    rootMessage(e), scrubbedStackTrace(e));
            return OperatorResult.failure("QUERY_ENGINE_ERROR", rootMessage(e));
        }
    }

    // ── catalog ──────────────────────────────────────────────────────────────

    private OperatorResult catalog(TenantContext tenant) throws Exception {
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        GraphVersion version = graphStore.currentVersion(tenant);
        Map<String, List<String>> enforced = enforcedByClass(tenant, cat);

        // Sources-tier authoring names, keyed by (datasetId, srcClass) — what SQL mode types.
        Map<String, String> sourceTables = new LinkedHashMap<>();
        for (edu.upc.dtak.aran.core.domain.SourceCatalog.SourceTable t
                : cat.sourceCatalog().tables()) {
            sourceTables.put(t.datasetId() + " " + t.sourceClassIri(), t.sqlName());
        }

        List<Map<String, Object>> classes = new ArrayList<>();
        for (MappingCatalog.IntegratedClass ic : cat.classes().values()) {
            List<Map<String, Object>> sources = new ArrayList<>();
            for (MappingCatalog.SourceView sv : ic.sources()) {
                List<Map<String, String>> columns = new ArrayList<>();
                sv.columnMap().forEach((global, source) -> {
                    Map<String, String> col = new LinkedHashMap<>();
                    col.put("global", global);
                    col.put("globalName", Names.localName(global));
                    col.put("source", source);
                    columns.add(col);
                });
                columns.sort((a, b) -> a.get("globalName").compareToIgnoreCase(b.get("globalName")));

                Map<String, Object> source = new LinkedHashMap<>();
                source.put("datasetId", sv.datasetId());
                source.put("wrapperSql", sv.wrapperSql());
                source.put("intView", "int_" + Names.sqlName(ic.iri()));
                source.put("columns", columns);
                source.put("rowCount", sv.rowCount());
                // The sources-tier table and its column names as the alias view exposes them —
                // absent for a SourceView that recorded no source class (pre-tier catalogs).
                String sourceTable = sourceTables.get(sv.datasetId() + " " + sv.srcClass());
                if (sourceTable != null) {
                    source.put("sourceTable", sourceTable);
                    source.put("sourceColumns", sv.columnMap().values().stream()
                            .distinct()
                            .map(Names::localName)
                            .sorted(String::compareTo)
                            .toList());
                }
                sources.add(source);
            }
            long classRowCount = ic.sources().stream().mapToLong(MappingCatalog.SourceView::rowCount).sum();
            Map<String, Object> cls = new LinkedHashMap<>();
            cls.put("iri", ic.iri());
            cls.put("name", Names.localName(ic.iri()));
            cls.put("intView", "int_" + Names.sqlName(ic.iri()));
            cls.put("rowCount", classRowCount);
            cls.put("enforcedConstraintCount", enforced.getOrDefault(ic.iri(), List.of()).size());
            cls.put("sources", sources);
            classes.add(cls);
        }
        classes.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));

        List<Map<String, Object>> joins = new ArrayList<>();
        for (MappingCatalog.JoinProperty jp : cat.joinProperties()) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("iri", jp.iri());
            j.put("name", Names.localName(jp.iri()));
            j.put("leftClass", Names.localName(jp.leftClass()));
            j.put("rightClass", Names.localName(jp.rightClass()));
            j.put("leftCol", Names.localName(jp.leftCol()));
            j.put("rightCol", Names.localName(jp.rightCol()));
            joins.add(j);
        }

        List<Map<String, Object>> objects = new ArrayList<>();
        for (MappingCatalog.ObjectProperty op : cat.objectProperties()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("iri", op.iri());
            o.put("name", Names.localName(op.iri()));
            o.put("domainClass", Names.localName(op.domainClass()));
            o.put("rangeClass", Names.localName(op.rangeClass()));
            objects.add(o);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("empty", cat.schemaVersionId() == null);
        payload.put("schemaVersionId", cat.schemaVersionId());
        payload.put("versionNumber", version != null ? version.versionNumber() : null);
        payload.put("classes", classes);
        payload.put("joinProperties", joins);
        payload.put("objectProperties", objects);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", cat.schemaVersionId() == null ? "empty" : "ok");
        data.put("catalog", mapper.writeValueAsString(payload));
        return OperatorResult.success(data);
    }

    // ── cube-evidence ────────────────────────────────────────────────────────

    /**
     * The evidence a dimensional-modeling wizard needs and {@link #catalog} deliberately omits.
     *
     * <p>{@code catalog} is the authoring surface: it reports <em>local names</em> (a join's
     * columns, a class, a property) because that is what a user types into SQL mode, and it says
     * nothing about types, trust tiers or profile statistics. Every one of those is load-bearing
     * for "shape as cube": a measure must be numeric, a dimension link's trust tier decides
     * whether it may be offered unflagged, and hierarchy-level candidates are an NDV comparison.
     * A local name is also not addressable — two classes in different namespaces share one — so
     * this payload carries full IRIs throughout, most importantly on the join-key columns.
     *
     * <p>Read-only and derived entirely from the same cached {@link MappingCatalog} the catalog
     * operation reads: no lake call, no snapshot, no second SPARQL pass.
     */
    private OperatorResult cubeEvidence(TenantContext tenant) throws Exception {
        MappingCatalog cat = catalogOperator.getCatalog(tenant);

        List<Map<String, Object>> classes = new ArrayList<>();
        for (MappingCatalog.IntegratedClass ic : cat.classes().values()) {
            List<Map<String, Object>> properties = new ArrayList<>();
            for (String globalIri : globalColumnsOf(ic)) {
                String xsd = agreedRange(ic, globalIri);
                ColumnProfile profile = soleSourceProfile(ic, globalIri);
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("iri", globalIri);
                property.put("name", Names.localName(globalIri));
                property.put("xsd", xsd);
                property.put("numeric", isNumericXsd(xsd));
                property.put("ndv", ndvOf(profile));
                property.put("nullFraction", nullFractionOf(profile));
                properties.add(property);
            }

            // Mirrors IntegratedClassTable.getStatistic: the class's row count is the sum of its
            // sources' profile-derived counts (ProfileStatsSource.estimateRowCount, already folded
            // into SourceView.rowCount at catalog build), and a non-positive sum is AranStatistic's
            // "unknown", not a measured zero — so it is null here rather than 0.
            long totalRows = ic.sources().stream()
                    .mapToLong(MappingCatalog.SourceView::rowCount)
                    .sum();

            Map<String, Object> cls = new LinkedHashMap<>();
            cls.put("iri", ic.iri());
            cls.put("name", Names.localName(ic.iri()));
            cls.put("rowCount", totalRows > 0 ? totalRows : null);
            cls.put("properties", properties);
            classes.add(cls);
        }
        classes.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("classes", classes);
        payload.put("edges", edges(cat));

        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", cat.schemaVersionId() == null ? "empty" : "ok");
        data.put("evidence", mapper.writeValueAsString(payload));
        return OperatorResult.success(data);
    }

    /**
     * The catalog's referential edges, tier-tagged and carrying full IRIs, one entry per
     * relationship.
     *
     * <p>Two shaping rules, both about not overstating what the catalog knows:
     *
     * <ul>
     *   <li><b>A composite (multi-column) edge is dropped.</b> This payload's join key is one
     *       column pair, and a single pair of a composite foreign key is not a weaker version of
     *       that key — it is a different, false one, which a wizard would happily generate a join
     *       from. Same reading {@code DeclaredEdgeResolver} applies when it drops a whole harvested
     *       FK rather than a partially-resolved one.</li>
     *   <li><b>One relationship appears once, at its strongest tier.</b> A harvested FK that also
     *       became an accepted join yields both a {@code HYPOTHESIS} and a {@code DECLARED} edge
     *       from {@link MappingCatalog#referentialEdges()}; offering the same link twice at two
     *       trust levels is a UI defect, and {@code Tier}'s ordering is deliberate (HYPOTHESIS
     *       least trusted), so the stronger evidence wins.</li>
     * </ul>
     *
     * <p>{@code propertyIri}/{@code propertyName} are the accepted join's — empty when a
     * {@code DECLARED} edge has no matching {@link MappingCatalog.JoinProperty}. Empty rather than
     * invented: without a join property there is no OQL {@code -[p]->} step to traverse the edge
     * with, and a synthesised name would hide that.
     */
    private static List<Map<String, Object>> edges(MappingCatalog cat) {
        Map<String, MappingCatalog.JoinProperty> byColumns = new HashMap<>();
        for (MappingCatalog.JoinProperty jp : cat.joinProperties()) {
            byColumns.putIfAbsent(
                    edgeKey(jp.leftClass(), jp.leftCol(), jp.rightClass(), jp.rightCol()), jp);
        }

        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        Map<String, MappingCatalog.Tier> tiers = new HashMap<>();
        for (MappingCatalog.ReferentialEdge edge : cat.referentialEdges()) {
            if (edge.leftCols().size() != 1) {
                continue;
            }
            String leftCol = edge.leftCols().get(0);
            String rightCol = edge.rightCols().get(0);
            String key = edgeKey(edge.leftClass(), leftCol, edge.rightClass(), rightCol);
            MappingCatalog.Tier seen = tiers.get(key);
            if (seen != null && seen.compareTo(edge.tier()) >= 0) {
                continue;
            }
            tiers.put(key, edge.tier());

            MappingCatalog.JoinProperty jp = byColumns.get(key);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("propertyIri", jp != null ? jp.iri() : "");
            e.put("propertyName", jp != null ? Names.localName(jp.iri()) : "");
            e.put("leftClassIri", edge.leftClass());
            e.put("rightClassIri", edge.rightClass());
            e.put("leftColIri", leftCol);
            e.put("rightColIri", rightCol);
            e.put("tier", edge.tier().name());
            byKey.put(key, e);
        }
        return new ArrayList<>(byKey.values());
    }

    private static String edgeKey(String leftClass, String leftCol, String rightClass, String rightCol) {
        return leftClass + " " + leftCol + " -> " + rightClass + " " + rightCol;
    }

    /**
     * The class's global property IRIs, sorted — the same vector {@code WrapperTable.globalColumns}
     * builds, so this payload lists a class's properties in the order the {@code int_} view's row
     * type declares them. Mirrored rather than shared because the plan package's copy is
     * package-private, and nothing here is ordinal-addressed: order is presentation only.
     */
    private static List<String> globalColumnsOf(MappingCatalog.IntegratedClass cls) {
        return cls.sources().stream()
                .flatMap(s -> s.columnMap().keySet().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The XSD range IRI every source of {@code cls} agrees on for {@code globalIri}, else null.
     *
     * <p>{@code XsdTypes.widenedType}'s fold, in range-IRI space: a source that records no range
     * for the column — including one that does not supply the column at all, and so NULL-pads it —
     * is a disagreement, not an abstention, because absence must never sharpen a type. The answer
     * degrades to null exactly where the widened SQL type degrades to VARCHAR, so a property this
     * payload calls numeric is one the {@code int_} view really does hand back as a number.
     */
    private static @Nullable String agreedRange(MappingCatalog.IntegratedClass cls, String globalIri) {
        String agreed = null;
        for (MappingCatalog.SourceView sv : cls.sources()) {
            String range = sv.columnTypes().get(globalIri);
            if (range == null) {
                return null;
            }
            if (agreed == null) {
                agreed = range;
            } else if (!agreed.equals(range)) {
                return null;
            }
        }
        return agreed;
    }

    /**
     * Whether an XSD range IRI is a numeric datatype — the "tick this as a measure" test.
     *
     * <p>The reference is {@code XsdTypes.fromXsd}, and this predicate is deliberately the wider
     * of the two: it counts the {@code unsigned*} and {@code *negativeInteger} ranges that
     * {@code XsdTypes} folds to VARCHAR for want of a DuckDB type to widen them into. Those are
     * numeric in XSD, and this payload answers a modeling question ("is this a candidate
     * measure?"), not a physical one. Every range {@code XsdTypes} maps to a numeric
     * {@code SqlTypeName} must stay true here.
     */
    private static boolean isNumericXsd(@Nullable String xsdIri) {
        if (xsdIri == null) {
            return false;
        }
        String local = xsdIri.startsWith(XSD_NS) ? xsdIri.substring(XSD_NS.length()) : xsdIri;
        return NUMERIC_XSD.contains(local);
    }

    /**
     * The profile evidence for {@code globalIri}, or null when there is none.
     *
     * <p>Mirrors {@code IntegratedClassTable.soleSourceProfileAt} exactly, including its refusal to
     * answer for a multi-source class: a UNION ALL can introduce or remove duplicates that no
     * single source's profile accounts for, so the class's NDV is neither the sum, the max, nor
     * either source's own — it is not knowable from per-source profiles at all.
     */
    private static @Nullable ColumnProfile soleSourceProfile(MappingCatalog.IntegratedClass cls,
                                                             String globalIri) {
        if (cls.sources().size() != 1) {
            return null;
        }
        MappingCatalog.SourceView source = cls.sources().get(0);
        String sourceColumn = source.columnMap().get(globalIri);
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

    /**
     * Exact NDV, or null. {@code cardinality <= 0} is what an all-NULL or never-profiled column
     * reports — "no evidence", not "zero distinct values" — and is dropped like a missing profile,
     * same rule and reason as {@code IntegratedClassTable.columnNdv}.
     */
    private static @Nullable Long ndvOf(@Nullable ColumnProfile profile) {
        return profile == null || profile.cardinality() <= 0 ? null : profile.cardinality();
    }

    /**
     * Null fraction, or null — with one screen {@code IntegratedClassTable.columnNullFraction}
     * does not apply: a {@code HARVESTED} profile's {@code incompleteness} is {@code
     * pg_stats.null_frac} where the catalog had one and a default 0.0 where it had none (a column
     * {@code ANALYZE} never sampled, an engine whose harvest reads no NULL fraction), and the row
     * does not say which. The planner may cost off that default; a wizard may not show it, since
     * a zero there reads as evidence the column is complete. The harvest's NDV, which
     * {@link #ndvOf} still takes, carries no such ambiguity.
     */
    private static @Nullable Double nullFractionOf(@Nullable ColumnProfile profile) {
        if (profile == null || HarvestedColumnProfiles.SUBJECT.equals(profile.subject())) {
            return null;
        }
        return profile.incompleteness();
    }

    // ── explain ──────────────────────────────────────────────────────────────

    private OperatorResult explain(TenantContext tenant) throws Exception {
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        if (cat.schemaVersionId() == null) {
            return OperatorResult.success(Map.of(
                    "status", "empty",
                    "ddl", mapper.writeValueAsString(List.of())));
        }
        // Symbolic resolver: render the read function with the snapshot id rather than a live
        // (expiring) presigned URL, so explain is stable, readable, and does not touch the lake.
        Map<String, Snapshot> snapshots = snapshotMap(tenant);
        DuckDbExecutor.SnapshotResolver symbolic = datasetId -> {
            Snapshot s = snapshots.get(datasetId);
            String fmt = s != null ? s.format() : "";
            String ref = s != null ? s.snapshotId() : datasetId;
            return SnapshotFormats.readExpression(fmt, "snapshot://" + ref);
        };
        List<String> ddl = DuckDbExecutor.buildDdl(cat, symbolic, enforcedByClass(tenant, cat));

        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("ddl", mapper.writeValueAsString(ddl));
        return OperatorResult.success(data);
    }

    // ── run (raw SQL over int_ views) ──────────────────────────────────────────

    private OperatorResult run(TenantContext tenant, OperatorRequest request) throws Exception {
        String sql = request.parameters().getOrDefault("sql", "").trim();
        String guard = rejectIfNotReadOnly(sql);
        if (guard != null) {
            return OperatorResult.failure("INVALID_SQL", guard);
        }
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        if (cat.schemaVersionId() == null) {
            return OperatorResult.failure("NO_SCHEMA",
                    "No active global schema — promote an integrated schema version first.");
        }
        Map<String, Object> payload =
                executeSql(tenant, request.tenantId(), cat, sql, parseOffset(request));
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("result", mapper.writeValueAsString(payload));
        return OperatorResult.success(data);
    }

    // ── rewrite-oql (OQL → SQL, no execution) ─────────────────────────────────

    private OperatorResult rewriteOql(TenantContext tenant, OperatorRequest request) throws Exception {
        String oql = request.parameters().getOrDefault("oql", "").trim();
        if (oql.isBlank()) {
            return OperatorResult.failure("INVALID_OQL", "OQL query is empty.");
        }
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        if (cat.schemaVersionId() == null) {
            return OperatorResult.failure("NO_SCHEMA",
                    "No active global schema — promote an integrated schema version first.");
        }
        try {
            OqlQuery oqlQuery = OqlParser.parse(oql);
            RelNode logical = new OqlPlanner(cat, enforcedByClass(tenant, cat)).lower(oqlQuery);
            PlanExecutor.Optimized planned = PlanExecutor.optimize(logical, plannerConfig.budget());
            RelNode optimized = planned.plan();
            String sql = PlanExecutor.toSqlFromOptimized(optimized);
            // The same placement decision run-oql executes under, so "explain" (the UI's
            // rewrite-oql) shows the chosen access mode, not the pre-placement default.
            Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Decision>
                    placement = decidePlacement(tenant, optimized);
            PlanShape shape = PlanShape.of(optimized,
                    PlanShape.accessAnnotations(cat, accessLabels(tenant, placement)));
            Map<String, String> data = new LinkedHashMap<>();
            data.put("status", "ok");
            data.put("sql", sql);
            data.put("logicalPlan", PlanExecutor.planString(logical));
            data.put("optimizedPlan", PlanExecutor.planString(optimized));
            data.put("planShape", mapper.writeValueAsString(shape.root()));
            data.put("planShapeText", shape.toCompactString());
            // Always present, degraded or not: a plan shown to a person says whether it is the
            // plan the optimizer would have chosen (D3's degradation ladder).
            data.put("planning", mapper.writeValueAsString(planned.planning().toMap()));
            if (!placement.isEmpty()) {
                data.put("placement", mapper.writeValueAsString(
                        edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider
                                .report(placement)));
            }
            // The row shape a VIRTUAL data product records as its output_schema (V29): derived
            // straight from the optimizer's own row type, no execution. DataProductService reads
            // this key exactly the way it already reads materialize-oql's "columns" — one shape,
            // two ways to arrive at it (planned vs. described off a real file).
            data.put("columns", ProductColumn.toJson(ProductColumns.of(optimized.getRowType())));
            return OperatorResult.success(data);
        } catch (OqlParser.OqlParseException | OqlPlanner.OqlPlanException e) {
            return OperatorResult.failure("INVALID_OQL", e.getMessage());
        }
    }

    // ── run-oql (OQL → SQL → rows) ────────────────────────────────────────────

    private OperatorResult runOql(TenantContext tenant, OperatorRequest request) throws Exception {
        String oql = request.parameters().getOrDefault("oql", "").trim();
        if (oql.isBlank()) {
            return OperatorResult.failure("INVALID_OQL", "OQL query is empty.");
        }
        int pageSize;
        try {
            pageSize = parsePageSize(request);
        } catch (NumberFormatException e) {
            return OperatorResult.failure("INVALID_LIMIT",
                    "limit must be an integer: " + request.parameters().get("limit"));
        }
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        if (cat.schemaVersionId() == null) {
            return OperatorResult.failure("NO_SCHEMA",
                    "No active global schema — promote an integrated schema version first.");
        }
        try {
            OqlQuery oqlQuery = OqlParser.parse(oql);
            // One read, two consumers: the planner must reason with exactly the constraints the
            // view bodies are about to enforce. Two independent reads would usually agree, and
            // the once they did not — a constraint un-enforced between them — the planner would
            // have dropped rows the views then returned.
            Map<String, List<String>> enforced = enforcedByClass(tenant, cat);
            OqlPlanner planner = new OqlPlanner(cat, enforced);
            RelNode logical = planner.lower(oqlQuery);
            PlanExecutor.Optimized planned = PlanExecutor.optimize(logical, plannerConfig.budget());
            RelNode optimized = planned.plan();
            String sql = PlanExecutor.toSqlFromOptimized(optimized);
            // Physical placement (λ): decided from the optimized plan — the pushed predicates
            // determine each dual-access leaf's effective transfer — then handed to the resolver
            // as overrides so what runs IS what was decided.
            Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Decision>
                    placement = decidePlacement(tenant, optimized);
            Map<String, Object> payload =
                    executeSql(tenant, request.tenantId(), cat, sql, parseOffset(request),
                            pageSize, enforced, accessOverrides(placement));
            payload.put("sql", sql);
            // What each column IS, as the query declares it — KEY / MEASURE / VALUE. Only the
            // OQL path can supply this; raw `run` has no query semantics to report, and its
            // payload simply omits the field. See OqlPlanner.ColumnRole.
            payload.put("columnRoles", planner.columnRoles().stream().map(Enum::name).toList());
            payload.put("logicalPlan", PlanExecutor.planString(logical));
            payload.put("optimizedPlan", PlanExecutor.planString(optimized));
            PlanShape shape = PlanShape.of(optimized,
                    PlanShape.accessAnnotations(cat, accessLabels(tenant, placement)));
            payload.put("planShape", shape.root());
            payload.put("planShapeText", shape.toCompactString());
            payload.put("planning", planned.planning().toMap());
            if (!placement.isEmpty()) {
                payload.put("placement", edu.upc.dtak.aran.operators.queryengine.plan
                        .PlacementDecider.report(placement));
            }
            Map<String, String> data = new LinkedHashMap<>();
            data.put("status", "ok");
            data.put("sql", sql);
            data.put("result", mapper.writeValueAsString(payload));
            return OperatorResult.success(data);
        } catch (OqlParser.OqlParseException | OqlPlanner.OqlPlanException e) {
            return OperatorResult.failure("INVALID_OQL", e.getMessage());
        }
    }

    // ── materialize-oql (OQL → COPY … TO parquet, no row cap) ─────────────────

    /**
     * Execute a product's OQL through the exact {@code run-oql} pipeline but with the
     * row cap disabled at <em>both</em> of its layers (no {@code LIMIT MAX_ROWS+1}
     * appended, no post-fetch cap) and the final SELECT wrapped in a DuckDB
     * {@code COPY (…) TO '<tmp>.parquet' (FORMAT PARQUET)} on the same connection,
     * inside the {@code try} that owns the snapshot temp files.
     *
     * <p>An <em>internal</em> operation: {@code DataProductService} drives it through
     * the orchestrator (core cannot depend on DuckDB), then uploads the parquet to the
     * lake and deletes it. The result carries the temp path, not the bytes.
     * {@code WrapperSqlValidator} only guards user wrappers; this COPY is built here,
     * around planner-generated SQL, so it is not subject to it. A product over a
     * federated dataset materializes from the <em>live</em> source (the resolver's
     * attached-catalog branch) — this couples only to
     * executeSql's inputs/outputs, not to how the resolver reaches the source.
     */
    private OperatorResult materializeOql(TenantContext tenant, OperatorRequest request) throws Exception {
        String oql = request.parameters().getOrDefault("oql", "").trim();
        if (oql.isBlank()) {
            return OperatorResult.failure("INVALID_OQL", "OQL query is empty.");
        }
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        if (cat.schemaVersionId() == null) {
            return OperatorResult.failure("NO_SCHEMA",
                    "No active global schema — promote an integrated schema version first.");
        }
        Map<String, List<String>> enforced = enforcedByClass(tenant, cat);
        String sql;
        Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Decision>
                placement;
        try {
            OqlQuery oqlQuery = OqlParser.parse(oql);
            RelNode logical = new OqlPlanner(cat, enforced).lower(oqlQuery);
            PlanExecutor.Optimized planned = PlanExecutor.optimize(logical, plannerConfig.budget());
            RelNode optimized = planned.plan();
            if (planned.planning().degraded()) {
                // No payload to carry it on — a materialization has no plan view — so the log is
                // where a product built on a best-so-far plan is recorded.
                log.warn("materialize-oql planned under a cut budget ({}, {} productions): {}",
                        planned.planning().degradation(), planned.planning().productions(), oql);
            }
            sql = PlanExecutor.toSqlFromOptimized(optimized);
            // Same placement rule as run-oql: a product materialization over a dual-access
            // dataset reads the leaf the cost decision picked, legality-gated identically.
            placement = decidePlacement(tenant, optimized);
        } catch (OqlParser.OqlParseException | OqlPlanner.OqlPlanException e) {
            return OperatorResult.failure("INVALID_OQL", e.getMessage());
        }

        // PARQUET for a data product's stored snapshot; CSV for an ad-hoc export, because that is
        // what lands in a spreadsheet without a second tool. Same query, same uncapped path.
        String format = request.parameters().getOrDefault("format", "PARQUET").trim().toUpperCase(Locale.ROOT);
        if (!format.equals("PARQUET") && !format.equals("CSV")) {
            return OperatorResult.failure("INVALID_FORMAT", "format must be PARQUET or CSV, got: " + format);
        }
        boolean csv = format.equals("CSV");

        UUID tenantUuid = parseTenantId(request.tenantId());
        String suffix = csv ? ".csv" : ".parquet";
        long rowCount;
        Path target;
        try (QueryWorkspace workspace = QueryWorkspace.create()) {
            ResolutionContext ctx = snapshotResolver(tenant, tenantUuid, accessOverrides(placement),
                    workspace.dir());
            // Written inside the workspace, the one directory this connection may touch; handed
            // off below, once the connection is closed, because the caller streams or uploads it
            // after this call and then deletes it.
            Path staged = workspace.createFile("aran-product-", suffix);
            Files.deleteIfExists(staged); // COPY TO creates the file itself
            String stagedLiteral = Names.sqlLiteral(staged.toAbsolutePath().toString());
            try (DuckDbExecutor exec = new DuckDbExecutor(cat, ctx.resolver(), enforced)) {
                exec.onlyClassesReferencedBy(sql)
                        .restrictFilesystemTo(queryFileRoots(tenantUuid, workspace.dir()))
                        .withConnectionSetup(ctx.connectionSetup())
                        .withWrapperOutputCache(wrapperOutputCache,
                                wrapperCacheSpecs(tenantUuid, cat, ctx.snapshots(),
                                        accessOverrides(placement)))
                        .prepare();
                exec.execute("COPY (" + sql + ") TO '" + stagedLiteral + "' ("
                        + (csv ? "FORMAT CSV, HEADER" : "FORMAT PARQUET") + ")");
                String reader = csv ? "read_csv_auto" : "read_parquet";
                DuckDbExecutor.QueryResult count =
                        exec.run("SELECT count(*) FROM " + reader + "('" + stagedLiteral + "')");
                rowCount = Long.parseLong(String.valueOf(count.rows().get(0).get(0)));
            }
            target = workspace.handOff(staged, "aran-product-", suffix);
        }

        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "ok");
        // Kept as `parquetPath` regardless of format: DataProductService reads this key, and it
        // only ever asks for PARQUET. `path` is the format-neutral name for new callers.
        data.put("parquetPath", target.toAbsolutePath().toString());
        data.put("path", target.toAbsolutePath().toString());
        data.put("format", format);
        data.put("rowCount", Long.toString(rowCount));
        data.put("sql", sql);
        // The version these rows mean (UI-OD12): the catalog this build planned against — which a
        // product refresh cannot choose, since materialize-oql always reads the ACTIVE catalog.
        data.put("schemaVersionId", cat.schemaVersionId());
        if (!csv) {
            // The result's own columns, for the caller to record on the product: the JDBC
            // `products` schema describes its tables from the registry, and asking the lake for
            // a snapshot's header once per browsed table is the cost that buys.
            data.put("columns", ProductColumn.toJson(ProductSqlExecutor.describeParquet(target)));
        }
        return OperatorResult.success(data);
    }

    // ── materialize-source-sql (client SQL over sources-tier aliases, no rewrite) ──

    /**
     * Execute a client's own SQL over the sources-tier alias views and write the whole result
     * to a parquet file — the sources analogue of {@code materialize-product-sql}, with the
     * same contract ({@code data.path} to a temp parquet the caller streams and deletes).
     *
     * <p>Unlike the products half this runs on the snapshot view stack, so it goes through
     * {@code snapshotResolver} exactly as {@code run} and {@code materialize-oql} do: pruning
     * decides which snapshots are fetched (alias names count as references —
     * {@link DuckDbExecutor#referencedClasses}), and the same filesystem lockdown applies. The
     * SQL screen is load-bearing here for the same reason as the products path: this is
     * arbitrary client SQL inside Aran's process.
     */
    private OperatorResult materializeSourceSql(TenantContext tenant, OperatorRequest request)
            throws Exception {
        String sql = request.parameters().getOrDefault("sql", "").trim();
        String guard = rejectIfNotReadOnly(sql);
        if (guard != null) {
            return OperatorResult.failure("INVALID_SQL", guard);
        }
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        if (cat.schemaVersionId() == null) {
            return OperatorResult.failure("NO_SCHEMA",
                    "No active global schema — promote an integrated schema version first.");
        }

        UUID tenantUuid = parseTenantId(request.tenantId());
        long rowCount;
        List<ProductColumn> columns;
        Path target;
        try (QueryWorkspace workspace = QueryWorkspace.create()) {
            ResolutionContext ctx = snapshotResolver(tenant, tenantUuid, workspace.dir());
            // Written inside the workspace this connection is confined to, and handed off once it
            // is closed: the caller streams the result after this call, then deletes it.
            Path staged = workspace.createFile("aran-source-sql-", ".parquet");
            Files.deleteIfExists(staged); // COPY TO creates the file itself
            String stagedLiteral = Names.sqlLiteral(staged.toAbsolutePath().toString());
            try (DuckDbExecutor exec =
                         new DuckDbExecutor(cat, ctx.resolver(), enforcedByClass(tenant, cat))) {
                exec.onlyClassesReferencedBy(sql)
                        .restrictFilesystemTo(queryFileRoots(tenantUuid, workspace.dir()))
                        .withConnectionSetup(ctx.connectionSetup())
                        .withWrapperOutputCache(wrapperOutputCache,
                                wrapperCacheSpecs(tenantUuid, cat, ctx.snapshots()))
                        .prepare();
                try {
                    exec.execute("COPY (" + sql + ") TO '" + stagedLiteral + "' (FORMAT PARQUET)");
                } catch (Exception e) {
                    // The client's own statement failed. Its error to fix — hand back the names
                    // it could have used, as the products path does.
                    return OperatorResult.failure("INVALID_SQL", sourceSqlMessage(e, cat));
                }
                DuckDbExecutor.QueryResult count =
                        exec.run("SELECT count(*) FROM read_parquet('" + stagedLiteral + "')");
                rowCount = Long.parseLong(String.valueOf(count.rows().get(0).get(0)));
                columns = ProductSqlExecutor.describeParquet(staged);
            }
            target = workspace.handOff(staged, "aran-source-sql-", ".parquet");
        }

        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("path", target.toAbsolutePath().toString());
        data.put("parquetPath", target.toAbsolutePath().toString());
        data.put("format", "PARQUET");
        data.put("rowCount", Long.toString(rowCount));
        // Echoed back unchanged — byte-identical to what the client sent is the whole claim.
        data.put("sql", sql);
        data.put("columns", ProductColumn.toJson(columns));
        return OperatorResult.success(data);
    }

    /** As {@link #productSqlMessage}, for the sources tier: DuckDB's message plus the names. */
    private static String sourceSqlMessage(Exception failure, MappingCatalog cat) {
        String message = rootMessage(failure);
        if (!message.toLowerCase(Locale.ROOT).contains("does not exist")) {
            return message;
        }
        List<String> names = cat.sourceCatalog().tables().stream()
                .map(edu.upc.dtak.aran.core.domain.SourceCatalog.SourceTable::sqlName).toList();
        if (names.isEmpty()) {
            return message + " — no bootstrapped source of this tenant is in the active schema,"
                    + " so the sources schema is empty.";
        }
        return message + " — available source tables: " + String.join(", ", names);
    }

    // ── materialize-product-sql (client SQL over products, no rewrite) ─────────

    /**
     * Execute a client's own SQL over the tenant's materialized data products and write the whole
     * result to a parquet file — the passthrough half of the JDBC catalog split.
     *
     * <p>Nothing about the mediated schema is consulted: no mapping catalog, no wrapper views, no
     * planner, no OQL. A data product <em>is</em> a materialized query result, so the statement
     * runs against its parquet the way it would against any warehouse table, and the only thing
     * this method decides is which product views exist while it runs.
     *
     * <p>Guarded by the same screen {@code run} applies — SELECT/WITH only, and no file, HTTP or
     * scan function. That screen is load-bearing here rather than defence in depth: the statement
     * is arbitrary client SQL executing inside Aran's own process, so {@code read_csv_auto} would
     * be a server-file read and {@code postgres_scan}, or another tenant's attached catalog, a
     * cross-tenant one.
     */
    private OperatorResult materializeProductSql(TenantContext tenant, OperatorRequest request)
            throws Exception {
        String sql = request.parameters().getOrDefault("sql", "").trim();
        String rejection = rejectIfNotReadOnly(sql);
        if (rejection != null) {
            return OperatorResult.failure("INVALID_SQL", rejection);
        }

        UUID tenantUuid = parseTenantId(request.tenantId());
        ProductCatalog products = ProductCatalog.of(dataProductRepository.findAll(tenant));

        // Does this statement name any VIRTUAL product? Only then is the graph-tier view context an
        // OQL expansion needs built. A purely-materialized statement takes exactly today's path —
        // no catalog fetch, no buildDdl — which is the byte-identical regression guarantee.
        List<ProductTable> referencedVirtual = products.tables().stream()
                .filter(t -> t.product().accessMode() == ProductAccessMode.VIRTUAL
                        && ProductSqlExecutor.references(sql, t.sqlName()))
                .toList();

        long rowCount;
        List<ProductColumn> columns;
        Set<String> referenced;
        Path target;
        // One workspace for everything this statement touches: product snapshots the executor
        // downloads, the graph-tier snapshots a virtual expansion reads, and the result. It is the
        // one part of the working area the products-tier lockdown allowlists (T3.5), and closing
        // it deletes whatever the query left there.
        try (QueryWorkspace workspace = QueryWorkspace.create()) {
            // Handed off below, once the connection is closed: the caller streams it, then
            // deletes it.
            Path staged = workspace.createFile("aran-product-sql-", ".parquet");
            Files.deleteIfExists(staged);   // COPY TO creates the file itself

            VirtualExecutionContext virtual =
                    referencedVirtual.isEmpty() ? null
                            : virtualExecutionContext(tenant, tenantUuid, referencedVirtual,
                                    workspace.dir());

            try (ProductSqlExecutor exec = new ProductSqlExecutor(tenantUuid, products,
                    snapshotCache, lake, workspace.dir())) {
                if (virtual != null) {
                    exec.withVirtualContext(virtual.graphSetupDdl(), virtual.bodyProvider(),
                            virtual.connectionSetup());
                }
                referenced = exec.registerReferencedBy(sql);
                // T3.5 (audit JDBC_AVATICA-1): the client's SQL runs only behind the same
                // filesystem lockdown every sibling SQL-accepting path installs; the token screen
                // stops being the sole layer on the most hostile input in the system.
                exec.lockDownUnlessFederated(queryFileRoots(tenantUuid, workspace.dir()));
                try {
                    exec.copyToParquet(sql, staged);
                } catch (Exception e) {
                    // The client's own statement failed to run. That is its error to fix, not an
                    // engine fault, so it comes back as INVALID_SQL with the names it could have
                    // used. First: was the failure a "table not found" for a virtual product whose
                    // view was skipped because its OQL no longer expands? Then say exactly that,
                    // naming the product — clearer than the generic "available products" list,
                    // which would even list the broken product as if it were selectable.
                    String brokenExplanation = explainBrokenVirtual(e, virtual);
                    if (brokenExplanation != null) {
                        return OperatorResult.failure("INVALID_SQL", brokenExplanation);
                    }
                    return OperatorResult.failure("INVALID_SQL", productSqlMessage(e, products));
                }
                rowCount = exec.rowCountOf(staged);
                columns = ProductSqlExecutor.describeParquet(staged);
            }
            target = workspace.handOff(staged, "aran-product-sql-", ".parquet");
        }

        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("path", target.toAbsolutePath().toString());
        data.put("parquetPath", target.toAbsolutePath().toString());
        data.put("format", "PARQUET");
        data.put("rowCount", Long.toString(rowCount));
        // Echoed back unchanged. That it is byte-identical to what the client sent is the whole
        // claim this operation makes, so it is worth being able to assert on.
        data.put("sql", sql);
        data.put("products", String.join(",", referenced));
        data.put("columns", ProductColumn.toJson(columns));
        return OperatorResult.success(data);
    }

    /**
     * The graph-tier DDL, the per-product OQL→SQL bodies, and the connection setup a
     * {@link ProductSqlExecutor} needs to bind virtual products. The snapshots the resolver
     * downloaded building it sit in the caller's {@link QueryWorkspace}, which deletes them once
     * the query has run.
     *
     * <p>{@code bodyProvider} returns {@code null} for a product whose OQL did not expand against the
     * active schema; {@code brokenByName} records those (sqlName → reason) so a statement that truly
     * selected from one can be told which product failed and why. See {@code #virtualExecutionContext}.
     */
    private record VirtualExecutionContext(List<String> graphSetupDdl,
                                           Function<DataProduct, @Nullable String> bodyProvider,
                                           Map<String, String> brokenByName,
                                           @Nullable ConnectionSetup connectionSetup) {}

    /**
     * Build the read-time expansion context for the VIRTUAL products a products-tier statement
     * names.
     *
     * <p>Expansion is against the <b>ACTIVE</b> catalog (ruling R3): {@code catalogOperator
     * .getCatalog} is the tenant's ACTIVE mapping catalog, and the enforced constraints are the
     * ones currently enforced — the same two reads {@code run-oql} makes — which is precisely what
     * "always fresh + live denial-constraint enforcement" means. A product's pinned
     * {@code schemaVersionId} is deliberately not consulted here; if the active schema no longer
     * supports the product's OQL, {@link #expandVirtualOql} fails with an honest error rather than
     * silently serving stale semantics.
     *
     * <p>Each product's OQL is planned once, and the union of the classes those plans touch drives
     * a pruned {@code buildDdl} — so a virtual read downloads only the snapshots its own classes
     * need, never the whole catalog, into {@code workDir}. Resolving each dataset is also what
     * fills the {@link ConnectionSetup} (secrets, attachments), so it is complete here.
     */
    private VirtualExecutionContext virtualExecutionContext(TenantContext tenant, UUID tenantUuid,
                                                            List<ProductTable> referencedVirtual,
                                                            Path workDir) {
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        if (cat.schemaVersionId() == null) {
            throw new IllegalStateException("No active global schema — a virtual data product"
                    + " expands its OQL against the active schema, so there must be one. Promote an"
                    + " integrated schema version first.");
        }
        Map<String, List<String>> enforced = enforcedByClass(tenant, cat);

        // Plan each referenced virtual product's OQL once, indexed by product id, and collect the
        // union of classes the plans read so buildDdl is pruned to exactly them.
        //
        // A per-product expansion failure is CAUGHT and isolated here: a broken/unplannable virtual
        // product (its OQL no longer plans against the active schema — a state the design permits,
        // "re-pin = edit the product") must never fail a statement that only word-matched its name.
        // It is recorded in brokenByName and its view is later skipped; a statement that genuinely
        // selects from it fails at execution with a message naming it (see explainBrokenVirtual).
        Map<UUID, String> bodyByProduct = new LinkedHashMap<>();
        Map<String, String> brokenByName = new LinkedHashMap<>();
        Set<String> requiredClasses = new LinkedHashSet<>();
        for (ProductTable t : referencedVirtual) {
            try {
                String body = expandVirtualOql(t.product(), cat, enforced);
                bodyByProduct.put(t.product().id(), body);
                requiredClasses.addAll(DuckDbExecutor.referencedClasses(cat, body));
            } catch (RuntimeException e) {
                brokenByName.put(t.sqlName(), rootMessage(e));
                log.warn("Virtual data product '{}' does not expand against the active schema — its"
                        + " view will be skipped; only a statement that selects from it will fail.",
                        t.sqlName(), e);
            }
        }

        // buildDdl calls the resolver per source, which downloads snapshots into workDir; the
        // caller's workspace deletes them, a failure here included.
        ResolutionContext ctx = snapshotResolver(tenant, tenantUuid, workDir);
        List<String> graphDdl = DuckDbExecutor.buildDdl(cat, ctx.resolver(), enforced, requiredClasses);

        // Null for a product that did not expand (recorded in brokenByName) — the executor reads
        // this as "skip this view", not "fail the statement".
        Function<DataProduct, @Nullable String> provider = product -> bodyByProduct.get(product.id());
        // "null when none" is withVirtualContext's contract, and the executor reads a non-null
        // setup as external access in play; the resolver always builds one.
        @Nullable ConnectionSetup setup = ctx.connectionSetup().isEmpty() ? null : ctx.connectionSetup();
        return new VirtualExecutionContext(graphDdl, provider, brokenByName, setup);
    }

    /**
     * A virtual product's stored OQL, expanded to SQL through the exact GAV pipeline
     * {@code run-oql}/{@code materialize-oql} use (parse → plan → optimize → unparse). The enforced
     * constraints reach both the planner and, via {@code buildDdl}, the view bodies — one read, so
     * the plan reasons with exactly the constraints the views will apply.
     *
     * <p>A product whose OQL no longer plans against the ACTIVE catalog fails here with a clear
     * message (ruling R3: re-pinning a virtual product to a schema its OQL still fits is editing
     * the product, not something a read silently papers over).
     */
    private String expandVirtualOql(DataProduct product, MappingCatalog cat,
                                    Map<String, List<String>> enforced) {
        try {
            OqlQuery oqlQuery = OqlParser.parse(product.oql());
            RelNode logical = new OqlPlanner(cat, enforced).lower(oqlQuery);
            PlanExecutor.Optimized planned = PlanExecutor.optimize(logical, plannerConfig.budget());
            if (planned.planning().degraded()) {
                log.warn("Virtual product '{}' expanded under a cut budget ({}, {} productions)",
                        product.name(), planned.planning().degradation(),
                        planned.planning().productions());
            }
            return PlanExecutor.toSqlFromOptimized(planned.plan());
        } catch (OqlParser.OqlParseException | OqlPlanner.OqlPlanException e) {
            throw new IllegalStateException("Virtual data product '" + product.name()
                    + "' no longer expands against the active schema: " + e.getMessage(), e);
        }
    }

    /**
     * If a statement failed with a "table not found" for a virtual product whose view was skipped
     * (its OQL no longer expands), a message naming that product and why — otherwise {@code null}.
     *
     * <p>This is the self-scoped half of the failure-isolation contract: a broken virtual product
     * never poisons an unrelated statement (its view is simply absent), and the one statement that
     * genuinely selected from it gets a clear reason instead of the generic "available products"
     * list — which would otherwise even list the broken product as if it were selectable, since it
     * is still {@code queryable()}. Only "does not exist"/"not found" errors are considered, and only
     * a broken product whose name the error text actually carries, so an unrelated failure falls
     * through to {@link #productSqlMessage}.
     */
    private static @Nullable String explainBrokenVirtual(Exception failure,
                                                         @Nullable VirtualExecutionContext virtual) {
        if (virtual == null || virtual.brokenByName().isEmpty()) {
            return null;
        }
        String message = rootMessage(failure);
        String lower = message.toLowerCase(Locale.ROOT);
        if (!lower.contains("does not exist") && !lower.contains("not found")) {
            return null;
        }
        for (Map.Entry<String, String> broken : virtual.brokenByName().entrySet()) {
            if (ProductSqlExecutor.references(message, broken.getKey())) {
                return "Virtual data product '" + broken.getKey() + "' cannot be queried: its stored"
                        + " OQL no longer expands against the active schema (" + broken.getValue()
                        + "). Recreate the product against the current schema.";
            }
        }
        return null;
    }

    /**
     * DuckDB's message, plus the names it would have accepted.
     *
     * <p>A client that types the product's <em>display</em> name, or one that is on the wrong
     * schema and has sent OQL down this path, gets "Table with name X does not exist" from a
     * catalog it cannot browse from the error. Listing the registry's table names turns that into
     * a correctable mistake.
     */
    private static String productSqlMessage(Exception failure, ProductCatalog products) {
        String message = rootMessage(failure);
        if (!message.toLowerCase(Locale.ROOT).contains("does not exist")) {
            return message;
        }
        List<String> names = products.tables().stream().map(ProductTable::sqlName).toList();
        if (names.isEmpty()) {
            return message + " — no data product of this tenant has been materialized yet, so the"
                    + " products schema is empty. Materialize a product first, or query the"
                    + " integrated schema with OQL on the graph schema.";
        }
        return message + " — materialized data products are: " + String.join(", ", names)
                + " (and " + ProductSqlExecutor.REGISTRY_TABLE + ", the registry itself).";
    }

    // ── preview-parquet (lake snapshot → first rows) ───────────────────────────

    /**
     * Preview a materialized parquet snapshot: download it from the lake, read the
     * first {@code limit} rows via an in-memory DuckDB, and shape the payload exactly
     * like {@link #executeSql}. Internal operation backing {@code DataProductService.preview}.
     */
    private OperatorResult previewParquet(TenantContext tenant, OperatorRequest request) throws Exception {
        String snapshotId = request.parameters().getOrDefault("snapshotId", "").trim();
        if (snapshotId.isBlank()) {
            return OperatorResult.failure("INVALID_SNAPSHOT", "snapshotId is required.");
        }
        int limit;
        try {
            limit = Integer.parseInt(request.parameters().getOrDefault("limit", "100"));
        } catch (NumberFormatException e) {
            return OperatorResult.failure("INVALID_LIMIT", "limit must be an integer.");
        }
        limit = Math.max(1, Math.min(limit, MAX_ROWS));

        long started = System.currentTimeMillis();
        UUID tenantUuid = parseTenantId(request.tenantId());
        LakeClient.SnapshotBytes bytes = lake.downloadSnapshot(tenantUuid, snapshotId);
        Path tmp = QueryTempDir.createFile("aran-product-preview-", ".parquet");
        try {
            Files.write(tmp, bytes.bytes());
            String pathLiteral = Names.sqlLiteral(tmp.toAbsolutePath().toString());
            try (Connection c = DuckDbSession.open();
                 java.sql.Statement st = c.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(
                         "SELECT * FROM read_parquet('" + pathLiteral + "') LIMIT " + limit)) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                List<String> headers = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    headers.add(meta.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        // Same conversion run/run-oql use. This path was still calling
                        // toString(), so a data-product preview handed the browser "42" where
                        // the workbench handed it 42 — and the shared ResultTable sorted two
                        // identically-shaped payloads differently.
                        row.add(DuckDbExecutor.jsonSafe(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("headers", headers);
                payload.put("headerNames", headers.stream().map(Names::localName).toList());
                payload.put("rows", rows);
                payload.put("rowCount", rows.size());
                payload.put("truncated", rows.size() >= limit);
                payload.put("elapsedMs", System.currentTimeMillis() - started);
                Map<String, String> data = new LinkedHashMap<>();
                data.put("status", "ok");
                data.put("result", mapper.writeValueAsString(payload));
                return OperatorResult.success(data);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignore) { /* best effort */ }
        }
    }

    // ── shared executor: materialise snapshots, run SQL over int_ views ─────────

    private Map<String, Object> executeSql(TenantContext tenant, String tenantId,
                                           MappingCatalog cat, String sql) throws Exception {
        return executeSql(tenant, tenantId, cat, sql, 0L);
    }

    private Map<String, Object> executeSql(TenantContext tenant, String tenantId,
                                           MappingCatalog cat, String sql, long offset)
            throws Exception {
        return executeSql(tenant, tenantId, cat, sql, offset, enforcedByClass(tenant, cat));
    }

    /**
     * One page of {@code sql}'s result, starting at {@code offset}.
     *
     * <p>{@code rowCount} is the number of rows <em>in this page</em>. It used to be the row
     * count of the capped query, which for an over-cap result was {@code MAX_ROWS + 1} — so the
     * workbench displayed "1,001 rows" above a table containing 1,000.
     *
     * <p>{@code enforced} is passed in rather than read here so an OQL caller can hand the
     * <em>same</em> constraint set to {@link OqlPlanner} and to the view bodies. The planner now
     * treats an enforced constraint as a fact about the rows it will read, so the two must be one
     * read: a plan built on a constraint the DDL then does not apply is a wrong answer, not a
     * worse estimate. The raw-SQL path has no planner and uses the overload above.
     */
    private Map<String, Object> executeSql(TenantContext tenant, String tenantId,
                                           MappingCatalog cat, String sql, long offset,
                                           Map<String, List<String>> enforced)
            throws Exception {
        return executeSql(tenant, tenantId, cat, sql, offset, enforced, Map.of());
    }

    private Map<String, Object> executeSql(TenantContext tenant, String tenantId,
                                           MappingCatalog cat, String sql, long offset,
                                           Map<String, List<String>> enforced,
                                           Map<String, String> accessOverrides)
            throws Exception {
        return executeSql(tenant, tenantId, cat, sql, offset, MAX_ROWS, enforced, accessOverrides);
    }

    /**
     * {@code pageSize} is the caller's own bound, at most {@link #MAX_ROWS}: a data product's reader
     * (an MCP agent asking for 20 rows) passes it through {@code run-oql}'s {@code limit}, and it is
     * applied inside DuckDB exactly as the engine page is, with the same one-row sentinel.
     */
    private Map<String, Object> executeSql(TenantContext tenant, String tenantId,
                                           MappingCatalog cat, String sql, long offset, int pageSize,
                                           Map<String, List<String>> enforced,
                                           Map<String, String> accessOverrides)
            throws Exception {
        UUID tenantUuid = parseTenantId(tenantId);
        String limitedSql = capRows(sql, offset, pageSize);
        long started;
        long elapsed;
        DuckDbExecutor.QueryResult result;
        try (QueryWorkspace workspace = QueryWorkspace.create()) {
            ResolutionContext ctx = snapshotResolver(tenant, tenantUuid, accessOverrides,
                    workspace.dir());
            started = System.currentTimeMillis();
            try (DuckDbExecutor exec = new DuckDbExecutor(cat, ctx.resolver(), enforced)) {
                // Materialise only the classes this statement names. Without it, prepare()
                // resolves — and therefore downloads — every source of every class in the catalog.
                exec.onlyClassesReferencedBy(limitedSql)
                        .restrictFilesystemTo(queryFileRoots(tenantUuid, workspace.dir()))
                        .withConnectionSetup(ctx.connectionSetup())
                        .withWrapperOutputCache(wrapperOutputCache,
                                wrapperCacheSpecs(tenantUuid, cat, ctx.snapshots(), accessOverrides))
                        .prepare();
                result = exec.run(limitedSql);
            }
            elapsed = System.currentTimeMillis() - started;
        }

        int fetched = result.rows().size();
        boolean hasMore = fetched > pageSize;                    // the sentinel row came back
        List<List<Object>> page = hasMore ? result.rows().subList(0, pageSize) : result.rows();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("headers", result.headers());
        payload.put("headerNames", result.headers().stream().map(Names::localName).toList());
        payload.put("rows", page);
        payload.put("rowCount", page.size());
        payload.put("offset", offset);
        payload.put("pageSize", pageSize);
        payload.put("hasMore", hasMore);
        // Retained under its old name for clients that predate paging; same meaning.
        payload.put("truncated", hasMore);
        payload.put("elapsedMs", elapsed);
        return payload;
    }

    // ── validate-constraint (save-time expression check) ───────────────────────

    /**
     * Validate a denial-constraint expression against a class's global columns without
     * persisting anything. Guards against multi-statement / comment injection (the
     * expression is later spliced into view DDL), then EXPLAINs the predicate over a
     * throwaway in-memory DuckDB view exposing the class's local column names — DuckDB
     * reports unknown columns or syntax errors, surfaced verbatim as the failure detail.
     *
     * <p>Called by {@code IntegrationModelService.addConstraint} through the orchestrator,
     * the same way compile/suggest reach their operators (core cannot depend on DuckDB).
     */
    private OperatorResult validateConstraint(TenantContext tenant, OperatorRequest request) {
        String classIri = request.parameters().getOrDefault("classIri", "").trim();
        String expression = request.parameters().getOrDefault("expression", "").trim();
        String guard = rejectConstraintExpression(expression);
        if (guard != null) {
            return OperatorResult.failure("INVALID_CONSTRAINT", guard);
        }
        MappingCatalog cat = catalogOperator.getCatalog(tenant);
        MappingCatalog.IntegratedClass ic = cat.classes().get(classIri);
        if (ic == null) {
            // No active schema, or the class is not compiled/promoted yet — its columns are
            // unknown, so we cannot check identifiers. Accept (the injection guard passed);
            // a genuine error would surface when the class is next materialised.
            return OperatorResult.success(Map.of("status", "ok", "validated", "false"));
        }
        List<String> locals = ic.sources().stream()
                .flatMap(s -> s.columnMap().keySet().stream())
                .map(Names::localName).distinct().sorted().toList();
        String cols = locals.isEmpty() ? "NULL AS _dummy"
                : locals.stream()
                        .map(l -> "NULL AS \"" + l.replace("\"", "\"\"") + "\"")
                        .collect(Collectors.joining(", "));
        try {
            try (Connection c = DuckDbSession.open();
                 java.sql.Statement st = c.createStatement()) {
                st.execute("CREATE TEMP VIEW _chk AS SELECT " + cols);
                st.execute("EXPLAIN SELECT * FROM _chk WHERE " + expression);
            }
            return OperatorResult.success(Map.of("status", "ok", "validated", "true"));
        } catch (Exception e) {
            return OperatorResult.failure("INVALID_CONSTRAINT",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // ── verify-wrapper (compile-time round-trip check) ─────────────────────────

    /**
     * Verify a candidate wrapper SQL text against the real snapshot before it is saved: does the
     * text plan cleanly over its {@code src_} relation ({@link WrapperExpansion#probe}), and if
     * so, do the user's own text and Calcite's unparse of the probed tree produce the same rows
     * when both are run against the data ({@link RoundTrip#verify})? {@code EXPANDED} only when
     * both hold; {@code OPAQUE} with a reason otherwise.
     *
     * <p>Internal, like {@code materialize-oql}: {@code IntegrationModelService}'s wrapper save
     * path drives this through the orchestrator (core cannot depend on Calcite or DuckDB), the
     * same way {@code validate-constraint} backs the constraint editor.
     *
     * <p>The probe runs first and touches only Calcite, over an ad-hoc {@link MappingCatalog.SourceView}
     * built from the catalog's own entry for {@code (datasetId, classIri)} — its
     * {@code columnMap}/{@code columnTypes}/{@code sourceRefs}/{@code profiles}, but the
     * <em>request's</em> {@code sql}, since that is the text under test and may not be saved
     * anywhere yet. An empty probe means the text cannot possibly round-trip, so it opens a real
     * DuckDB connection — a snapshot resolve, a lake download on a cache miss — only when the
     * class maps columns out of the wrapper: whether the text still outputs them is a question
     * only DuckDB can answer for SQL Calcite cannot plan, and a wrapper that drops one saves
     * fine and then fails every query over its class. A missing column comes back as an
     * {@code OPAQUE} verdict with a {@code missingColumns} entry, and a text that does not bind at
     * all (a misspelt raw column) with a {@code bindError} entry — see {@link #checkColumns} for
     * when that is provably the text's — and the save path refuses both.
     *
     * <p>Every operational failure — blank parameters, no such class/dataset in the active
     * schema, no snapshot recorded, a resolver error, a DuckDB error opening the fresh connection
     * or creating its {@code src_} view — is folded into an {@code OPAQUE} verdict carrying a
     * {@code "verify unavailable: "}-prefixed reason, exactly like a probe that does not plan:
     * the caller wants an answer to "can this wrapper be optimized", and "the check itself could
     * not run" is a "not right now", not a different kind of response the UI has to special-case
     * as an operator failure.
     *
     * <p>{@code sql} is attacker-reachable text that runs on a real, unrestricted DuckDB
     * connection whenever the probe succeeds or the class maps columns out of it — the column
     * check runs it even when Calcite cannot plan it — so it gets the same two layers every other
     * SQL-accepting path in this class applies, in the same order: {@link WrapperSqlValidator}'s
     * token screen first (the fail-closed gate — checked before the catalog is even read), then
     * the connection's own filesystem lockdown ({@link DuckDbExecutor#lockDownFilesystem}) once
     * the {@code src_} view exists, for a non-federated dataset.
     */
    private OperatorResult verifyWrapper(TenantContext tenant, OperatorRequest request) {
        String datasetId = request.parameters().getOrDefault("datasetId", "").trim();
        String classIri = request.parameters().getOrDefault("classIri", "").trim();
        String sql = request.parameters().getOrDefault("sql", "").trim();
        try {
            if (datasetId.isEmpty() || classIri.isEmpty() || sql.isEmpty()) {
                return verifyWrapperVerdict("OPAQUE",
                        "verify unavailable: datasetId, classIri and sql are all required.");
            }

            // The same fail-closed token screen every other SQL-accepting path in this operator
            // applies (rejectIfNotReadOnly, rejectConstraintExpression) — load-bearing here, not
            // defence in depth. WrapperExpansion.probe's narrow one-table Calcite schema happens
            // to reject read_csv_auto and friends too, but only because Calcite's default operator
            // table does not recognise DuckDB's readers/scanners as functions at all — an accident
            // of what Calcite's parser knows, not a security boundary this class asserts or tests,
            // and spec §2.5 is explicit that the token screen must stay the fail-closed gate
            // regardless of parseability, not a downstream consequence of it. It is also the only
            // layer for a federated dataset's src_ relation, whose read_expr is a table of an
                // attached Postgres or MySQL catalog (external network access), which the filesystem lockdown
            // below cannot confine — see DuckDbExecutor.lockDown's own javadoc. Checked before the
            // catalog is even read, same discipline as rejectConstraintExpression: a screen
            // skippable by naming an unknown class/dataset is not a screen (see
            // ConstraintInjectionTest.screenIsNotSkippedForUnknownClasses).
            String forbidden = WrapperSqlValidator.findForbiddenConstruct(sql);
            if (forbidden != null) {
                return verifyWrapperVerdict("OPAQUE", "forbidden construct: " + forbidden);
            }

            // The caller's classIri is the SOURCE class IRI — ModelSource.classIri, what the
            // Wrapper tab and IntegrationModelService.setWrapper hold — but classes() is keyed
            // by the INTEGRATED class IRI, which is what every source class becomes on a
            // compiled model. Trying only the map key made this lookup miss for every
            // normally-enrolled source, so the badge sat on "verify unavailable" forever and no
            // wrapper could ever earn EXPANDED in a real deployment (V41). sourceViewFor
            // is the single lookup that accepts either namespace — see its javadoc for why the
            // match is on the (datasetId, srcClass) PAIR and not the class IRI alone.
            MappingCatalog cat = catalogOperator.getCatalog(tenant);
            MappingCatalog.SourceView catalogEntry = sourceViewFor(cat, datasetId, classIri);
            if (catalogEntry == null) {
                return verifyWrapperVerdict("OPAQUE", "verify unavailable: no source view found for"
                        + " dataset '" + datasetId + "' and class '" + classIri
                        + "' in the active schema.");
            }

            // Same metadata the catalog already recorded for this dataset+class; only the SQL
            // text itself is the request's, since it may be an unsaved edit.
            MappingCatalog.SourceView candidate = new MappingCatalog.SourceView(
                    catalogEntry.datasetId(), sql, catalogEntry.columnMap(), catalogEntry.rowCount(),
                    catalogEntry.profiles(), catalogEntry.srcClass(), catalogEntry.columnTypes(),
                    catalogEntry.sourceRefs(), catalogEntry.expansionStatus());

            Optional<RelNode> probed = WrapperExpansion.probe(candidate);
            // The columns the class's u_ view projects out of this wrapper by name. A wrapper
            // that stops emitting one still saves and still earns a verdict, then fails every
            // query over the class with a binder error — so coverage is checked before any
            // verdict, and only DuckDB can say what a wrapper Calcite cannot plan outputs.
            List<String> mapped = catalogEntry.columnMap().values().stream()
                    .distinct().sorted().toList();
            if (probed.isEmpty() && mapped.isEmpty()) {
                return notPlannableVerdict();
            }
            // The planner's structural gate, mirrored here so the persisted verdict predicts it
            // (audit WRAPPER_VERIFY-9, structural half): an aggregate round-trips its own unparse
            // — COUNT(*) equals COUNT(*) — so the fingerprint alone would say EXPANDED for a
            // shape OqlPlanner.pushExpandedMember refuses forever. One shared definition, so the
            // badge and the planner cannot disagree. The planner's remaining gates (enforced
            // denial constraints, the DECIMAL/TIMESTAMP cast ladder) are class-shape-dependent
            // and still only checked at plan time — registered residue, not mirrored here.
            String unrebuildable = probed.map(WrapperExpansion::unrebuildablePart).orElse(null);
            if (unrebuildable != null && mapped.isEmpty()) {
                return notShapePreservingVerdict(unrebuildable);
            }

            UUID tenantUuid = parseTenantId(request.tenantId());
            try {
                return verifyOverSnapshot(tenant, tenantUuid, datasetId, sql, mapped,
                        catalogEntry.sourceRefs(), probed, unrebuildable);
            } catch (Exception e) {
                // The coverage check is what asked for the snapshot; a verdict the text alone
                // already determines stands rather than turning into "verify unavailable".
                if (probed.isEmpty()) return notPlannableVerdict();
                if (unrebuildable != null) return notShapePreservingVerdict(unrebuildable);
                throw e;
            }
        } catch (Exception e) {
            return verifyWrapperVerdict("OPAQUE", "verify unavailable: " + rootMessage(e));
        }
    }

    /**
     * verify-wrapper's DuckDB half: bind the dataset's {@code src_} view on a fresh connection,
     * check the wrapper still outputs every column its class reads, then — for a text Calcite
     * planned into a shape the planner may expand — fingerprint it against its own unparse.
     */
    private OperatorResult verifyOverSnapshot(TenantContext tenant, UUID tenantUuid,
                                              String datasetId, String sql, List<String> mapped,
                                              Map<String, String> sourceRefs,
                                              Optional<RelNode> probed,
                                              @Nullable String unrebuildable) throws Exception {
        try (QueryWorkspace workspace = QueryWorkspace.create()) {
            ResolutionContext ctx = snapshotResolver(tenant, tenantUuid, workspace.dir());
            String viewName = "src_" + Names.sqlName(datasetId);
            String readExpr = ctx.resolver().resolveReadExpression(datasetId);
            // Pinned to UTC by DuckDbSession, which this fingerprint depends on more than any
            // other path: the row hash below is compared against one another machine computed,
            // and a TIMESTAMPTZ rendered in the host's zone would demote the wrapper to OPAQUE
            // for a reason that has nothing to do with the wrapper.
            try (Connection conn = DuckDbSession.open()) {
                // The second layer under the SQL screen above, same idiom (and same three
                // SET statements, in the same order — see DuckDbExecutor.lockDownFilesystem)
                // as every other path that executes over a real snapshot in this class. A
                // federated dataset's readExpr is a table of an attached Postgres/MySQL catalog —
                // external network access, which enable_external_access=false would also cut off — so, exactly
                // like DuckDbExecutor.lockDown, it stays unconfined and leans on the screen
                // above as its only layer; this is deciding the same fact the same way, not a
                // second, divergent policy.
                boolean federated = DuckDbExecutor.isFederated(List.of(readExpr));
                try (java.sql.Statement st = conn.createStatement()) {
                    bindSourceView(st, viewName, readExpr, ctx.connectionSetup());
                    if (federated) {
                        log.debug("Federated scan for dataset '{}' — filesystem lockdown"
                                + " skipped for this verify-wrapper check", datasetId);
                    } else {
                        DuckDbExecutor.lockDownFilesystem(st,
                                queryFileRoots(tenantUuid, workspace.dir()));
                    }
                }
                // Unquoted substitution, matching DuckDbExecutor.buildDdl's own w_ view binding
                // (not WrapperExpansion.probe's quoted one, which targets Calcite's parser): this
                // text runs directly against DuckDB, so it needs DuckDB's own identifier rules.
                String userSql = sql.replace("${SNAPSHOT}", viewName);
                ColumnCheck check = checkColumns(conn, viewName, userSql, mapped,
                        federated || pinIsTheDatasetsCopy(tenant, datasetId, ctx.snapshots()));
                if (check.bindError() != null) {
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put("status", "OPAQUE");
                    data.put("reason", "the wrapper does not bind over its dataset's data: "
                            + check.bindError());
                    data.put("bindError", check.bindError());
                    return OperatorResult.success(data);
                }
                List<String> missing = check.missing();
                if (!missing.isEmpty()) {
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put("status", "OPAQUE");
                    data.put("reason", "the wrapper does not output column(s) its class reads: "
                            + String.join(", ", missing));
                    data.put("missingColumns", String.join("\n", missing));
                    // Each missing column's raw source column, so the save can tell a column the
                    // wrapper dropped from one the source itself has since lost — the second is
                    // the repair a WRAPPER_STALE compile refusal asks for, not a defect.
                    Map<String, String> sourceColumns = new LinkedHashMap<>();
                    sourceRefs.forEach((raw, iri) -> {
                        if (missing.contains(iri)) sourceColumns.put(iri, raw);
                    });
                    if (!sourceColumns.isEmpty()) {
                        data.put("missingSourceColumns", mapper.writeValueAsString(sourceColumns));
                    }
                    return OperatorResult.success(data);
                }
                if (probed.isEmpty()) return notPlannableVerdict();
                if (unrebuildable != null) return notShapePreservingVerdict(unrebuildable);
                String unparsedSql = PlanExecutor.toSqlFromOptimized(probed.get());
                RoundTrip.Verdict verdict = RoundTrip.verify(conn, userSql, unparsedSql);
                return verifyWrapperVerdict(verdict.status(), verdict.reason());
            }
        }
    }

    /**
     * What verify-wrapper's column check learned: the {@code mapped} columns the text does not
     * output, or — when the text itself does not bind — DuckDB's reason. At most one is non-empty.
     */
    record ColumnCheck(List<String> missing, @Nullable String bindError) {
        static final ColumnCheck UNKNOWN = new ColumnCheck(List.of(), null);
    }

    /**
     * The {@code mapped} columns {@code userSql} does not output, compared as DuckDB binds a
     * quoted identifier — case-insensitively.
     *
     * <p>When the text cannot be described at all, the check reports a {@code bindError} only
     * when the failure is provably the text's: DuckDB names it a Binder or Catalog error (an
     * unknown column, function or table — never an IO, HTTP or connection error), the
     * {@code viewName} relation still binds on its own right after, and {@code sourceIsCurrent}
     * says that relation is the data queries will read. Anything else — a federated source that
     * went away, a snapshot the active schema pins while the dataset has since been refreshed
     * (where a wrapper naming a column the refresh added cannot bind yet, and is the repair a
     * {@code WRAPPER_STALE} compile asks for) — stays unknown, so a save is never refused over the
     * check's own inability to run.
     */
    static ColumnCheck checkColumns(Connection conn, String viewName, String userSql,
                                    List<String> mapped, boolean sourceIsCurrent) {
        if (mapped.isEmpty()) return ColumnCheck.UNKNOWN;
        Set<String> output = new java.util.HashSet<>();
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT * FROM (" + userSql + "\n) AS _aran_wrapper LIMIT 0")) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                output.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
        } catch (java.sql.SQLException e) {
            // Scrubbed: a federated src_ view's connection failure echoes its DSN (rootMessage).
            log.debug("verify-wrapper could not read the wrapper's output columns: {}",
                    scrubCredentials(String.valueOf(e.getMessage())));
            String bindError = textBindError(e.getMessage());
            if (bindError == null || !sourceIsCurrent || !relationBinds(conn, viewName)) {
                return ColumnCheck.UNKNOWN;
            }
            return new ColumnCheck(List.of(), bindError);
        }
        return new ColumnCheck(
                mapped.stream().filter(c -> !output.contains(c.toLowerCase(Locale.ROOT))).toList(),
                null);
    }

    /**
     * DuckDB's own error when it is a Binder or Catalog error, trimmed to the error itself. The
     * JDBC driver can wrap it ("Invalid Input Error: … pending query result\nError: Binder
     * Error: …"), and it ends with a {@code LINE 1:} echo of the statement, which is dropped: the
     * echo is this check's wrapping of the text, not what the user wrote.
     */
    static @Nullable String textBindError(@Nullable String message) {
        if (message == null) return null;
        String error = message;
        int wrapped = error.lastIndexOf("\nError: ");
        if (wrapped >= 0) error = error.substring(wrapped + "\nError: ".length());
        if (!error.startsWith("Binder Error:") && !error.startsWith("Catalog Error:")) return null;
        int echo = error.indexOf("\n\n");
        return scrubCredentials((echo >= 0 ? error.substring(0, echo) : error).strip());
    }

    private static boolean relationBinds(Connection conn, String viewName) {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("SELECT * FROM " + viewName + " LIMIT 0");
            return true;
        } catch (java.sql.SQLException e) {
            return false;
        }
    }

    /**
     * Whether the snapshot the active schema pins for {@code datasetId} — what verify-wrapper
     * reads — is the dataset row's own copy. Between a refresh and the next promote they differ,
     * and the text under test may be written for the newer one.
     */
    private boolean pinIsTheDatasetsCopy(TenantContext tenant, String datasetId,
                                         Map<String, Snapshot> pins) {
        Snapshot pin = pins.get(datasetId);
        if (pin == null) return false;
        try {
            return datasetRepository.findById(tenant, UUID.fromString(datasetId))
                    .map(Dataset::snapshotId)
                    .filter(pin.snapshotId()::equals)
                    .isPresent();
        } catch (RuntimeException e) {
            log.debug("verify-wrapper could not read dataset {}'s current snapshot", datasetId, e);
            return false;
        }
    }

    private static OperatorResult notPlannableVerdict() {
        return verifyWrapperVerdict("OPAQUE", "wrapper does not plan cleanly over its"
                + " src_ relation — see the server log for the parse/validate/convert"
                + " failure or unusable construct.");
    }

    private static OperatorResult notShapePreservingVerdict(String unrebuildable) {
        return verifyWrapperVerdict("OPAQUE", "not a shape-preserving projection: it plans"
                + " to a " + unrebuildable + ", which the planner will never expand.");
    }

    /**
     * Bind verify-wrapper's {@code src_} view on its fresh connection by the same three steps, in
     * the same order, as {@link DuckDbExecutor#prepare}: the scanner extensions the read
     * expression needs, then the connection's secrets, then the view — which binds, and so dials a
     * federated source, the moment it is created.
     *
     * <p>Without the first step (audit S7-16) the verdict leaned on DuckDB's extension autoload:
     * on the pinned 1.5.5 it happens to load {@code iceberg} and {@code postgres_scanner} on first
     * use, but a deployment that turns {@code autoload_known_extensions} off would have earned
     * {@code OPAQUE — verify unavailable} for every federated class, for a reason that has nothing
     * to do with its wrapper, while its queries — which LOAD explicitly — kept working.
     */
    static void bindSourceView(java.sql.Statement st, String viewName, String readExpr,
                               ConnectionSetup connectionSetup) throws Exception {
        String ddl = "CREATE OR REPLACE VIEW " + viewName + " AS SELECT * FROM " + readExpr;
        DuckDbExecutor.ensureFederationExtensions(st, List.of(ddl));
        // An object-storage or Iceberg src_ view reads over httpfs, so its secrets must exist
        // before the view binds. Populated by the resolve; never logs a secret.
        connectionSetup.applyTo(st);
        st.execute(ddl);
    }

    /**
     * The catalog's own source view for {@code (datasetId, classIri)}, accepting <b>either
     * namespace</b> for the class (the fix for V41).
     *
     * <p>{@link MappingCatalog#classes()} is keyed by the <em>integrated</em> class IRI
     * ({@code IntegrationVocab.INTEGRATION_NS}), but every production caller of
     * {@code verify-wrapper} passes the <em>source</em> class IRI ({@code BootstrapEngine
     * .SOURCE_NS}) — {@code IntegrationModelService.discoverConstraints}'s javadoc is where that
     * caller convention is written down ("{@code setWrapper}/{@code verifyWrapper} … use the
     * SOURCE class IRI"), and the Wrapper tab's {@code ModelSourceDto.classIri} behind it is a
     * source IRI too. A plain {@code classes().get(classIri)} therefore missed for
     * every source promoted into an {@code IntegratedResource} — which is every source in a
     * compiled model — so every wrapper save persisted {@code OPAQUE} with
     * "verify unavailable: no source view found …" and {@code EXPANDED} was unreachable in any
     * real deployment. Translating here rather than at the call sites is deliberate: the catalog
     * is what knows its own source membership, and {@code IntegrationModelService
     * .discoverConstraints} already resolves the same two-namespace mismatch the same way, from
     * the same caller convention.
     *
     * <p>Membership is matched on the <b>pair</b> {@code (datasetId, srcClass)}, not on the class
     * IRI alone: one integrated class can be fed by several source classes and one dataset can
     * back several classes, so a name-only match could hand back another dataset's view — and the
     * SQL under test would then be fingerprinted against the wrong snapshot. The two namespaces
     * are disjoint by construction, so an IRI can satisfy at most one of the two branches below
     * and the order between them is not a precedence rule; the integrated-key branch stays first
     * only because it is the cheap map lookup.
     *
     * <p>The one shape that can match more than once is a source class that is
     * {@code subClassOf} <em>several</em> integrated classes: QUERY 1 then emits it under each,
     * and it appears as one {@link MappingCatalog.SourceView} per integrated class. Those views
     * are <b>value-identical</b> — every field of a source view is derived per {@code srcClass}
     * ({@code wrapperSql}, {@code columnMap}, {@code columnTypes}, {@code sourceRefs},
     * {@code expansionStatus}, and the profiles/row count keyed off the same dataset), never per
     * integrated parent — so the choice between them cannot change the verdict, and
     * {@code findFirst} over a key-ordered stream is <em>indifferent</em> here, not merely
     * deterministic. The ordering is kept anyway so that the same input yields the same answer
     * across JVMs instead of following {@code HashMap} iteration order, which is what makes a
     * future divergence in those fields reproducible rather than intermittent.
     *
     * @return the matching view, or {@code null} when the active schema has no such membership —
     *         the genuine miss the caller still reports as "verify unavailable".
     */
    private static MappingCatalog.@Nullable SourceView sourceViewFor(
            MappingCatalog cat, String datasetId, String classIri) {
        MappingCatalog.IntegratedClass ic = cat.classes().get(classIri);
        if (ic != null) {
            MappingCatalog.SourceView direct = ic.sources().stream()
                    .filter(sv -> sv.datasetId().equals(datasetId))
                    .findFirst().orElse(null);
            if (direct != null) {
                return direct;
            }
        }
        return cat.classes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(e -> e.getValue().sources().stream())
                .filter(sv -> sv.datasetId().equals(datasetId) && sv.srcClass().equals(classIri))
                .findFirst().orElse(null);
    }

    /** {@code {status, reason}}, always both keys — {@code reason} is {@code ""} when there is none. */
    private static OperatorResult verifyWrapperVerdict(String status, @Nullable String reason) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("reason", reason == null ? "" : reason);
        return OperatorResult.success(data);
    }

    /**
     * Bound the result set inside DuckDB, so the engine stops early instead of materialising
     * everything and being trimmed afterwards.
     *
     * <p>This used to append {@code " LIMIT " + (MAX_ROWS + 1)} unless the statement already
     * {@code contains(" limit ")}. Textual append onto a statement the user typed is wrong three
     * ways, all reproducible against DuckDB:
     * <ul>
     *   <li><b>A trailing semicolon breaks the query outright.</b> The read-only guard strips one
     *       for its own check but hands the statement on unchanged, so
     *       {@code SELECT * FROM int_Paper;} became {@code SELECT … ; LIMIT 1001} — a parser
     *       error, on the single most habitual thing a user can type.</li>
     *   <li><b>The substring test matches string literals.</b>
     *       {@code WHERE title = 'no limit here'} looked like it already carried a LIMIT, so none
     *       was pushed and the whole result set came back to be trimmed in Java.</li>
     *   <li><b>A trailing {@code --} comment swallows the appended clause,</b> same effect.</li>
     * </ul>
     *
     * <p>Wrapping instead of appending is immune to all three: the user's statement becomes a
     * subquery, so its literals, comments and its own LIMIT stay inside it and the cap applies to
     * whatever it produces. The newline before the closing paren is what stops a trailing comment
     * from commenting the paren out.
     *
     * <p>One visible difference: a statement projecting the same column name twice now gets
     * DuckDB's de-duplicated labels ({@code name}, {@code name_1}), because a subquery may not
     * have duplicate output names.
     */
    static String capRows(String sql) {
        return capRows(sql, 0);
    }

    /**
     * As above, starting at {@code offset} — one page of the result.
     *
     * <p>Paging lives here rather than in OQL because the wrap already exists and is the only
     * place that can bound a hand-written statement too. The user's own {@code LIMIT} stays inside
     * the subquery and still applies; this pages over whatever it produces.
     */
    static String capRows(String sql, long offset) {
        return capRows(sql, offset, MAX_ROWS);
    }

    /** As above, with a page smaller than the engine's — a data product read's own {@code limit}. */
    static String capRows(String sql, long offset, int pageSize) {
        String stripped = sql.strip();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        // pageSize + 1: the extra row is how the caller learns another page exists without
        // counting the whole result.
        String limit = " LIMIT " + (pageSize + 1);
        return "SELECT * FROM (\n" + stripped + "\n) AS _aran_capped"
                + limit + (offset > 0 ? " OFFSET " + offset : "");
    }

    /**
     * The optional {@code limit} parameter: absent means the engine page ({@link #MAX_ROWS}); a
     * value is clamped to 1..MAX_ROWS, like {@code preview-parquet}'s; a non-number is refused.
     */
    private static int parsePageSize(OperatorRequest request) {
        String raw = request.parameters().getOrDefault("limit", "").trim();
        if (raw.isEmpty()) {
            return MAX_ROWS;
        }
        // A NumberFormatException propagates: runOql answers it as INVALID_LIMIT, before planning.
        return Math.max(1, Math.min(Integer.parseInt(raw), MAX_ROWS));
    }

    /** Parses the {@code offset} parameter, rejecting anything that is not a whole page start. */
    private static long parseOffset(OperatorRequest request) {
        String raw = request.parameters().getOrDefault("offset", "0").trim();
        if (raw.isEmpty()) {
            return 0L;
        }
        long offset;
        try {
            offset = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("offset must be a non-negative integer: " + raw);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be a non-negative integer: " + raw);
        }
        return offset;
    }

    /**
     * Screen a constraint expression before it is persisted.
     *
     * <p>A constraint is not evaluated in isolation: {@code DuckDbExecutor.buildDdl} splices it
     * verbatim into the {@code WHERE} clause of three view definitions per class
     * ({@code u_}, {@code int_}, {@code q_}), and those views are what every query then reads.
     * The expression is therefore user-supplied SQL executing inside the engine, and it needs the
     * same screen wrapper SQL gets.
     *
     * <p>It did not have one. The only guard was the punctuation check below, which blocks
     * statement chaining and says nothing about what a single predicate may call. So
     * <pre>{@code name <> (SELECT col FROM read_csv_auto('/etc/passwd') LIMIT 1)}</pre>
     * passed validation, reached the view body, and DuckDB executed it — an arbitrary server-file
     * read, and with {@code postgres_scan} pointed at Aran's own database, a cross-tenant read.
     * It leaks through row counts one predicate at a time rather than returning bytes directly,
     * which makes it slow, not safe. {@code ConstraintInjectionTest} demonstrates it working.
     *
     * <p>The EXPLAIN check that follows in {@link #validateConstraint} does catch malformed
     * expressions and unknown columns, but a {@code read_csv_auto} call is perfectly well-formed
     * SQL — it EXPLAINs clean. Only a function screen rejects it.
     *
     * <p>Returns {@code null} when the expression is acceptable, otherwise the reason.
     */
    // Package-private, like rejectIfNotReadOnly and for the same reason: this is a security
    // boundary and its attack strings must be exercisable without a live catalog.
    static @Nullable String rejectConstraintExpression(String expression) {
        if (expression.isBlank()) {
            return "Constraint expression is empty.";
        }
        if (expression.contains(";") || expression.contains("--") || expression.contains("/*")) {
            return "Constraint may not contain ';', '--' or '/*'.";
        }
        String forbidden = WrapperSqlValidator.findForbiddenConstruct(expression);
        if (forbidden != null) {
            return "Forbidden construct — " + forbidden
                    + ". A constraint may only reference the class's own columns.";
        }
        return null;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * The deepest cause's message — what actually went wrong.
     *
     * <p>{@link DuckDbExecutor} wraps JDBC failures in a {@code RuntimeException} whose own
     * message is a fixed string, so this operator's catch-all reported {@code "Query execution
     * failed"} and dropped DuckDB's actual diagnostic ("Binder Error: Referenced column … not
     * found"). On a surface where users hand-write SQL, that is the difference between a usable
     * error and none.
     */
    /**
     * The one funnel every client-facing failure string passes through — and therefore where
     * credentials are scrubbed (audit QUERY_EXECUTION-1): {@code PostgresEngine}/{@code
     * MySqlEngine} embed the repository password inside the scan expression
     * ({@code postgres_scan('dbname=… password=… host=…')}), which becomes {@code src_} view
     * DDL, and the DuckDB extensions echo the DSN in their connection-failure messages — text
     * that would otherwise reach every tenant member and log storage verbatim. The S3 path
     * already suppresses driver messages for the same reason ({@code ConnectionSetup}); the
     * relational path keeps the diagnostic and redacts the secret tokens instead, because
     * "which host/database refused" is exactly what a user can act on.
     */
    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return scrubCredentials(root == t ? message : t.getMessage() + ": " + message);
    }

    /** One scrubber for every relay of DuckDB text; its javadoc says why it is quote-aware. */
    private static String scrubCredentials(String s) {
        return Credentials.scrub(s);
    }

    /** Stack frames with scrubbed messages — what the catch-all logs instead of the raw chain. */
    private static String scrubbedStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
            if (sb.length() > 0) {
                sb.append("Caused by: ");
            }
            sb.append(cur.getClass().getName()).append(": ")
                    .append(cur.getMessage() == null ? "" : scrubCredentials(cur.getMessage()))
                    .append('\n');
            for (StackTraceElement el : cur.getStackTrace()) {
                sb.append("\tat ").append(el).append('\n');
            }
        }
        return sb.toString();
    }

    private static UUID parseTenantId(String tenantId) {
        try {
            return UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid tenant identifier: " + tenantId);
        }
    }

    /**
     * A snapshot resolver plus the connection setup it accrues (the executor runs it before the
     * query). Its downloads land in the caller's {@link QueryWorkspace}, which owns their cleanup.
     * {@code connectionSetup} carries secrets, so this record is never logged —
     * {@link ConnectionSetup} redacts its own {@code toString}, which is what keeps this record's
     * default {@code toString} safe.
     */
    private record ResolutionContext(DuckDbExecutor.SnapshotResolver resolver,
                                     ConnectionSetup connectionSetup,
                                     Map<String, Snapshot> snapshots) {}

    // ── physical placement (λ, Option A) ──

    /**
     * The per-query placement decision for every dual-access dataset the optimized plan touches:
     * snapshot leaf or live leaf, chosen by transfer-aware cost ({@link PlacementDecider}),
     * legality decided here first — <b>freshness gates legality, cost picks among legal
     * plans</b>. Fail-open: any error returns the empty map and every dataset keeps its
     * {@code SourceAccess} default, so placement can degrade a plan, never break a query.
     */
    private Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Decision>
            decidePlacement(TenantContext tenant, RelNode optimized) {
        if (!placementConfig.enabled()) {
            return Map.of();
        }
        try {
            Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Candidate>
                    candidates = dualAccessCandidates(tenant,
                            edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider
                                    .leafDatasets(optimized));
            if (candidates.isEmpty()) {
                return Map.of();
            }
            return edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.decide(
                    optimized, candidates,
                    placementConfig.liveLambda(), placementConfig.localLambda());
        } catch (Exception e) {
            log.warn("Placement decision failed — every dataset keeps its default access mode", e);
            return Map.of();
        }
    }

    /**
     * Which of the PLAN's datasets hold both paths: a maintained lake copy on the dataset ROW —
     * the artifact a forced snapshot leaf actually serves and the legality gate measures — AND a
     * live upstream the resolver can dial. Today that is exactly one shape: a table imported
     * from a relational repository whose {@code federated} flag is on and whose engine has a
     * scanner (the import keeps {@code dataset.name()} = the remote relation). Object-storage
     * and Iceberg copies are NOT candidates: their bootstrap-over-the-copy overwrites the
     * live-path graph facts ({@code nx:iterator} becomes {@code "row"}), so both paths never
     * coexist — registered, not forgotten. Probes only {@code datasetIds} (the plan's own
     * leaves), so the cost is proportional to the query, not the tenant.
     */
    private Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Candidate>
            dualAccessCandidates(TenantContext tenant, java.util.Set<String> datasetIds) {
        Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Candidate> out =
                new LinkedHashMap<>();
        for (String dsRaw : datasetIds) {
            try {
                UUID dsUuid = UUID.fromString(dsRaw);
                Dataset dataset = datasetRepository.findById(tenant, dsUuid).orElse(null);
                if (dataset == null
                        || dataset.snapshotId() == null || dataset.snapshotId().isBlank()) {
                    continue; // no row copy — the live path is the only path, nothing to decide
                }
                DataRepository repo = dataRepositoryRepository
                        .findById(tenant, dataset.repositoryId()).orElse(null);
                if (!(repo instanceof RelationalDatabaseRepository rel) || !rel.federated()
                        || edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression
                                .forRepository(rel, dataset.name()) == null) {
                    continue; // no live path — the copy is the only path, nothing to decide
                }
                out.put(dsRaw, snapshotLegality(tenant, dsUuid, dsRaw));
            } catch (Exception ex) {
                log.debug("Dual-access probe failed for dataset {} — default access keeps",
                        dsRaw, ex);
            }
        }
        return out;
    }

    /**
     * Whether this dataset's lake copy may legally serve a query — the freshness gate, decided
     * from the copy's own declared {@code RefreshPolicy}, never from cost:
     *
     * <ul>
     *   <li>{@code Every(interval)}, refreshed within {@code staleGraceFactor × interval} (one
     *       skipped scheduler tick tolerated — "the interval is a floor") → <b>legal</b>: the
     *       user declared a staleness bound and the system is honoring it.</li>
     *   <li>{@code Every} but stale beyond the grace bound → illegal: the promise is broken, so
     *       the copy must not silently substitute for the live source it lags.</li>
     *   <li>{@code Manual}, or no refresh row at all → illegal. On a repository whose
     *       {@code federated} flag says "serve live", a copy with no declared bound is a one-off
     *       import, not a maintained mirror — substituting it would trade declared freshness for
     *       speed without the user ever saying so. Declaring an {@code Every} policy is how a
     *       user opts a copy into placement.</li>
     * </ul>
     */
    private edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Candidate
            snapshotLegality(TenantContext tenant, UUID datasetId, String datasetIdRaw) {
        var illegal = (java.util.function.Function<String,
                edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Candidate>) reason ->
                new edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Candidate(
                        datasetIdRaw, false, reason);
        if (datasetRefreshRepository == null) {
            return illegal.apply("no refresh store wired — copy freshness unknowable");
        }
        edu.upc.dtak.aran.core.domain.DatasetRefresh refresh =
                datasetRefreshRepository.findByDataset(tenant, datasetId).orElse(null);
        if (refresh == null
                || !(refresh.policy() instanceof
                        edu.upc.dtak.aran.core.source.RefreshPolicy.Every every)) {
            return illegal.apply("copy has no Every refresh policy — no declared staleness bound");
        }
        var staleness = refresh.stalenessAt(java.time.Instant.now());
        if (staleness.isEmpty()) {
            // No successful refresh has EVER bounded this copy's age — an Every policy whose
            // scheduler has not run yet, or whose refreshes all fail. Declaring the bound is not
            // meeting it (adversarial-review finding: this case previously read as "maintained").
            return illegal.apply("no successful refresh recorded under Every("
                    + every.interval() + ") — the copy's age is unbounded");
        }
        long boundMillis = (long) (every.interval().toMillis() * placementConfig.staleGraceFactor());
        if (staleness.get().toMillis() > boundMillis) {
            return illegal.apply("copy stale: " + staleness.get() + " since last refresh exceeds "
                    + placementConfig.staleGraceFactor() + "x the declared " + every.interval());
        }
        return new edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Candidate(
                datasetIdRaw, true,
                "copy maintained within its Every(" + every.interval() + ") bound");
    }

    /** The chosen access modes as the resolver override map — datasetId → AccessMode name. */
    private static Map<String, String> accessOverrides(
            Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Decision>
                    placement) {
        Map<String, String> out = new LinkedHashMap<>();
        placement.forEach((ds, d) -> out.put(ds, d.chosen()));
        return out;
    }

    /**
     * Dataset → access-mode string for plan annotation. The same SourceAccess.resolve the
     * snapshot resolver uses at execution — two answers to "is this dataset live?" would
     * let the plan view disagree with what actually ran.
     */
    private java.util.function.Function<String, String> accessLabels(TenantContext tenant) {
        java.util.Map<String, String> memo = new java.util.HashMap<>();
        return datasetId -> memo.computeIfAbsent(datasetId, ds -> {
            try {
                java.util.UUID dsUuid = java.util.UUID.fromString(ds);
                java.util.Optional<Dataset> dsOpt = datasetRepository.findById(tenant, dsUuid);
                if (dsOpt.isPresent()) {
                    Dataset dataset = dsOpt.get();
                    DataRepository repo = dataRepositoryRepository
                            .findById(tenant, dataset.repositoryId()).orElse(null);
                    // Same per-dataset signal the execution resolver uses (a copied object-storage
                    // dataset has a snapshot on its row) — so the plan view cannot annotate FEDERATED
                    // a source the resolver then reads MATERIALIZED, or vice versa.
                    return edu.upc.dtak.aran.core.source.SourceAccess
                            .resolve(repo, dataset.name(), dataset.snapshotId() != null).name();
                }
            } catch (Exception e) {
                log.warn("Access-mode lookup failed for dataset {} — annotating UNKNOWN", ds, e);
            }
            return "UNKNOWN";
        });
    }

    /**
     * As {@link #accessLabels(TenantContext)}, but a placement decision wins over the
     * {@code SourceAccess} default — the same one-decision rule the base method's javadoc pins:
     * the executor honors the chosen mode ({@link #snapshotResolver}'s override map), so the
     * annotation must say the same thing or the plan view lies about what ran.
     */
    private java.util.function.Function<String, String> accessLabels(TenantContext tenant,
            Map<String, edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider.Decision>
                    placement) {
        java.util.function.Function<String, String> defaults = accessLabels(tenant);
        return datasetId -> {
            var decision = placement.get(datasetId);
            return decision != null ? decision.chosen() : defaults.apply(datasetId);
        };
    }

    /**
     * The snapshot resolver shared by {@code run}/{@code run-oql} and
     * {@code materialize-oql}: federated Postgres/MySQL datasets resolve to attached-catalog
     * table names (their secret and {@code ATTACH} registered on the connection setup);
     * everything else downloads its lake snapshot into {@code workDir} — the caller's
     * {@link QueryWorkspace}, which deletes it — and reads it with the format-appropriate function.
     */
    private ResolutionContext snapshotResolver(TenantContext tenant, UUID tenantUuid, Path workDir) {
        return snapshotResolver(tenant, tenantUuid, Map.of(), workDir);
    }

    /**
     * The read expression for one lake snapshot — cache first (immutable, content-addressed, so
     * a hit is always valid), lake download on a miss. One helper for the two callers that must
     * not drift: the default graph-pinned path and the placement-forced row-snapshot path. A
     * download lands in the query's own {@code workDir} — the only part of the working area its
     * lockdown allowlists, and deleted with it ({@link QueryWorkspace}).
     */
    private String snapshotReadExpression(UUID tenantUuid, String ds, String snapshotId,
                                          String format, Path workDir) {
        java.util.Optional<Path> cached = snapshotCache.get(tenantUuid, snapshotId, format);
        if (cached.isPresent()) {
            // Cache-owned, in the tenant's cache directory: never deleted with the workspace.
            return "read_parquet('"
                    + Names.sqlLiteral(cached.get().toAbsolutePath().toString()) + "')";
        }
        try {
            Path tmp = Files.createTempFile(workDir,
                    "aran-snap-" + Names.sqlName(ds) + "-", SnapshotFormats.fileSuffixFor(format));
            lake.downloadSnapshotTo(tenantUuid, snapshotId, tmp);
            return SnapshotFormats.readExpression(format, tmp.toAbsolutePath().toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to materialise snapshot for dataset " + ds, e);
        }
    }

    /**
     * As above, honoring a per-query placement override: {@code accessOverrides} maps datasetId →
     * {@code AccessMode} name chosen by {@link #decidePlacement} from the optimized plan. Only
     * dual-access datasets ever appear in it (the candidate builder guarantees both paths exist),
     * and an absent entry keeps the {@code SourceAccess} default — so every pre-placement caller
     * that passes the empty map behaves exactly as before.
     */
    private ResolutionContext snapshotResolver(TenantContext tenant, UUID tenantUuid,
                                               Map<String, String> accessOverrides, Path workDir) {
        Map<String, Snapshot> snapshots = snapshotMap(tenant);
        ConnectionSetup connectionSetup = new ConnectionSetup();
        Map<String, String> readExprCache = new HashMap<>();

        DuckDbExecutor.SnapshotResolver resolver = datasetId -> readExprCache.computeIfAbsent(datasetId, ds -> {
            // The repository lookup can hit the DB; keep it in the try and fall back to the
            // materialized path on a transient failure. The federation DECISION and the scan/read
            // build are pure, so they sit outside — a misconfigured federated source must fail
            // loudly, not silently degrade to a snapshot download that then reports "no snapshot".
            Dataset dataset = null;
            DataRepository repo = null;
            try {
                java.util.UUID dsUuid = java.util.UUID.fromString(ds);
                java.util.Optional<Dataset> dsOpt = datasetRepository.findById(tenant, dsUuid);
                if (dsOpt.isPresent()) {
                    dataset = dsOpt.get();
                    repo = dataRepositoryRepository.findById(tenant, dataset.repositoryId()).orElse(null);
                }
            } catch (Exception e) {
                log.warn("Failed to check federation config for dataset {}", ds, e);
            }
            // Physical-placement override (decidePlacement, from the optimized plan). A forced
            // MATERIALIZED serves the DATASET ROW's snapshot — the maintained copy the legality
            // gate actually measured — never the active graph's pinned one, which only moves at
            // promote and whose age the gate knows nothing about. Gating one artifact while
            // serving another would make the staleness bound a fiction. Fail-open: if the row copy cannot be served (lake
            // outage, missing id), fall through to the SourceAccess default below rather than
            // failing a query the live path would have answered. A forced FEDERATED needs no
            // branch — for every dual-access candidate the SourceAccess default IS live.
            String forced = dataset == null ? null : accessOverrides.get(ds);
            if ("MATERIALIZED".equals(forced)
                    && dataset.snapshotId() != null && !dataset.snapshotId().isBlank()) {
                try {
                    // Format from the graph only when it names the SAME artifact; otherwise
                    // PARQUET — the only format the relational import/refresh extract tail
                    // writes, and the only shape that can be a dual-access candidate.
                    Snapshot graphSnap = snapshots.get(ds);
                    String fmt = graphSnap != null
                            && dataset.snapshotId().equals(graphSnap.snapshotId())
                            ? graphSnap.format() : "PARQUET";
                    return snapshotReadExpression(tenantUuid, ds, dataset.snapshotId(), fmt,
                            workDir);
                } catch (Exception e) {
                    log.warn("Forced snapshot leaf failed for dataset {} — falling back to the"
                            + " default access mode", ds, e);
                }
            }
            // Per-dataset access: an object-storage dataset that has been copied into the lake
            // (snapshotId set on its row) is served MATERIALIZED from that copy; one that has not is
            // read live over httpfs (FEDERATED). dataset.snapshotId() is only touched when dataset is
            // non-null, and only object storage consults it — relational keys off its federated flag.
            edu.upc.dtak.aran.core.source.AccessMode mode = dataset == null ? null
                    : edu.upc.dtak.aran.core.source.SourceAccess.resolve(
                            repo, dataset.name(), dataset.snapshotId() != null);
            if (dataset != null && mode == edu.upc.dtak.aran.core.source.AccessMode.FEDERATED) {
                if (repo instanceof RelationalDatabaseRepository rel) {
                    // Shared with the schema-drift probe. Two copies of this would let the probe
                    // connect somewhere subtly different from where queries go, and then report
                    // "no drift" forever — see FederatedScanExpression. An attached MySQL
                    // catalog's secret and ATTACH ride on connectionSetup, like the S3 secrets
                    // below: the expression names the attached table and carries no credential.
                    edu.upc.dtak.aran.core.source.FederatedScan scan =
                            edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression
                                    .forRepository(rel, dataset.name());
                    if (scan != null) {
                        connectionSetup.addAttachedCatalog(scan);
                        return scan.expression();
                    }
                } else if (repo instanceof ObjectStorageRepository os) {
                    // Read the object live over httpfs: a read_parquet('s3://bucket/key') scan
                    // (path only, no credential) plus a CREATE SECRET registered on the connection
                    // via connectionSetup — never in the returned expression, plan, or DDL.
                    return objectStorageReadExpression(os, ds, snapshots.get(ds), connectionSetup);
                } else if (repo instanceof IcebergRestRepository iceberg) {
                    // Read the table live via iceberg_scan on the metadata location the catalog
                    // reports NOW — re-fetched per query, never cached from bootstrap, because
                    // every commit to an Iceberg table moves it. Credentials travel like object
                    // storage's: on the connection, never in the expression.
                    return icebergReadExpression(iceberg, ds, snapshots.get(ds), connectionSetup);
                }
            }

            Snapshot snap = snapshots.get(ds);
            if (snap == null) {
                throw new IllegalStateException("No snapshot recorded for dataset " + ds
                        + " in the active schema graph");
            }
            return snapshotReadExpression(tenantUuid, ds, snap.snapshotId(), snap.format(),
                    workDir);
        });
        return new ResolutionContext(resolver, connectionSetup, snapshots);
    }

    /**
     * The {@link WrapperOutputCache} participation map for this catalog — one
     * {@code WrapperOutputCache.Spec} per wrapper view that may be served from (and filled into)
     * the cache, keyed by the {@code w_} view name {@code buildDdl} will emit.
     *
     * <p>Skipped here, so the executor never even considers them: EXPANDED classes (the planner
     * reads their {@code src_} directly), classes whose dataset has no snapshot in the active
     * graph (nothing content-addressed to key on — the federated case, which the executor
     * additionally re-checks against the resolver's own read expression), and the identity
     * wrapper with no join keys to sort by (a byte-for-byte duplicate of what {@link
     * SnapshotCache} already holds). Static and package-private so the skip rules are testable
     * without an operator.
     */
    static Map<String, WrapperOutputCache.Spec> wrapperCacheSpecs(
            UUID tenantUuid, MappingCatalog cat, Map<String, Snapshot> snapshots) {
        return wrapperCacheSpecs(tenantUuid, cat, snapshots, Map.of());
    }

    /**
     * As above, excluding any dataset under a placement override: a forced-MATERIALIZED leaf
     * serves the dataset ROW's snapshot while this spec's key names the graph-pinned one, so a
     * fill would file the row copy's rows under the wrong content address — cache poisoning, not
     * merely a miss. (A forced-FEDERATED leaf the executor's own federated re-check would skip
     * anyway; excluding both is one rule instead of two.)
     */
    static Map<String, WrapperOutputCache.Spec> wrapperCacheSpecs(
            UUID tenantUuid, MappingCatalog cat, Map<String, Snapshot> snapshots,
            Map<String, String> accessOverrides) {
        Map<String, WrapperOutputCache.Spec> specs = new LinkedHashMap<>();
        for (MappingCatalog.IntegratedClass cls : cat.classes().values()) {
            String classHash = Names.sqlName(cls.iri());
            for (MappingCatalog.SourceView sv : cls.sources()) {
                if ("EXPANDED".equals(sv.expansionStatus())
                        || accessOverrides.containsKey(sv.datasetId())) {
                    continue;
                }
                Snapshot snap = snapshots.get(sv.datasetId());
                if (snap == null || snap.snapshotId() == null || snap.snapshotId().isBlank()) {
                    continue;
                }
                List<String> sortColumns = wrapperSortColumns(cat, cls, sv);
                if (sortColumns.isEmpty() && "select * from ${snapshot}"
                        .equalsIgnoreCase(sv.wrapperSql().trim())) {
                    continue;
                }
                String wView = "w_" + Names.sqlName(sv.datasetId()) + "_" + classHash;
                specs.put(wView, new WrapperOutputCache.Spec(sv.datasetId(),
                        WrapperOutputCache.key(tenantUuid, snap.snapshotId(),
                                sv.wrapperSql(), sortColumns),
                        sortColumns));
            }
        }
        return specs;
    }

    /**
     * The wrapper-output columns to physically sort a class's cached output by: the source columns
     * behind every accepted join this class participates in ({@code JoinProperty}'s key properties
     * resolved through the class's own {@code columnMap}), in catalog order, deduplicated, capped
     * at three — beyond the leading columns, extra sort keys stop improving zone-map pruning and
     * only slow the fill.
     */
    static List<String> wrapperSortColumns(MappingCatalog cat, MappingCatalog.IntegratedClass cls,
                                           MappingCatalog.SourceView sv) {
        List<String> columns = new ArrayList<>();
        for (MappingCatalog.JoinProperty jp : cat.joinProperties()) {
            String keyProperty = cls.iri().equals(jp.leftClass()) ? jp.leftCol()
                    : cls.iri().equals(jp.rightClass()) ? jp.rightCol() : null;
            if (keyProperty == null) {
                continue;
            }
            String sourceColumn = sv.columnMap().get(keyProperty);
            if (sourceColumn != null && !columns.contains(sourceColumn)) {
                columns.add(sourceColumn);
                if (columns.size() == 3) {
                    break;
                }
            }
        }
        return columns;
    }

    /**
     * The DuckDB read expression for a federated object-storage dataset —
     * {@code read_parquet('s3://bucket/key')} or the CSV/JSON reader — and, as a side effect, the
     * {@code CREATE SECRET} that authenticates it registered on {@code connectionSetup}.
     *
     * <p>The credentials are deliberately NOT in the schema graph: the object key ({@code nx:iterator})
     * and format ({@code nx:format}) are, and this rebuilds the secret from the repository via
     * {@link edu.upc.dtak.aran.core.source.DuckDbS3Config}. The secret is scoped to the bucket and
     * named after the repository, so a query joining two object stores gets one secret each and the
     * same store referenced twice de-duplicates. The returned expression carries only the bucket
     * URL — the secret never enters it, nor any plan or DDL string built from it.
     */
    private String objectStorageReadExpression(ObjectStorageRepository repo, String datasetId,
                                               @Nullable Snapshot snap, ConnectionSetup connectionSetup) {
        String objectKey = snap == null ? "" : snap.iterator();
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalStateException("Federated object-storage dataset " + datasetId
                    + " has no object key (nx:iterator) in the active schema graph");
        }
        String s3Url = "s3://" + repo.bucketName() + "/" + objectKey;
        // Per-repository secret name (unique, so distinct repos never collide) scoped to the bucket
        // (so DuckDB routes each s3:// read to the right credentials on a multi-store join).
        String secretName = "aran_s3_" + repo.id().toString().replace('-', '_');
        connectionSetup.addObjectStorageSecret(
                edu.upc.dtak.aran.core.source.DuckDbS3Config.from(repo),
                secretName, "s3://" + repo.bucketName());
        String fmt = snap == null ? "PARQUET" : snap.format();
        return SnapshotFormats.readExpression(fmt, s3Url);
    }

    /**
     * The DuckDB read expression for a federated Iceberg dataset — {@code iceberg_scan('<metadata
     * location>')} — and, as a side effect, the data-plane {@code CREATE SECRET} registered on
     * {@code connectionSetup} when the location is an {@code s3://} one.
     *
     * <p>The metadata location is fetched from the REST catalog <em>at query time</em>: an Iceberg
     * commit replaces the location, so a location recorded at bootstrap would silently serve stale
     * data forever. The graph carries only the table identifier ({@code nx:iterator}) — the same
     * value {@code IcebergConnector} wrote — and the catalog answers with where the table is now.
     * The catalog token authenticates only that HTTP call; it never reaches DuckDB.
     */
    private String icebergReadExpression(IcebergRestRepository repo, String datasetId,
                                         @Nullable Snapshot snap, ConnectionSetup connectionSetup) {
        String tableIdentifier = snap == null ? "" : snap.iterator();
        if (tableIdentifier == null || tableIdentifier.isBlank()) {
            throw new IllegalStateException("Federated Iceberg dataset " + datasetId
                    + " has no table identifier (nx:iterator) in the active schema graph");
        }
        if (icebergCatalog == null) {
            throw new IllegalStateException("Federated Iceberg dataset " + datasetId
                    + " cannot be read: this build has no Iceberg catalog client on its classpath");
        }
        String metadataLocation =
                icebergCatalog.loadTable(repo, tableIdentifier).metadataLocation();
        String scope = edu.upc.dtak.aran.core.source.IcebergScanExpression.s3Scope(metadataLocation);
        if (scope != null) {
            // Same per-repository naming and bucket scoping as the object-storage branch, so a
            // query joining an Iceberg table and an S3 object routes each read to its own secret.
            String secretName = "aran_ice_" + repo.id().toString().replace('-', '_');
            connectionSetup.addObjectStorageSecret(
                    new edu.upc.dtak.aran.core.source.DuckDbS3Config(repo.provider(),
                            repo.endpoint(), repo.accessKey(), repo.secretKey(), repo.region()),
                    secretName, scope);
        } else if (metadataLocation.startsWith("http")
                && repo.token() != null && !repo.token().isBlank()) {
            // A catalog that serves its files over HTTP (another Aran's produce surface does)
            // authenticates file reads with the same bearer token as the catalog itself.
            String secretName = "aran_ice_http_" + repo.id().toString().replace('-', '_');
            connectionSetup.addHttpBearerSecret(repo.token(), secretName, null);
        }
        return edu.upc.dtak.aran.core.source.IcebergScanExpression
                .forMetadataLocation(metadataLocation);
    }

    /**
     * Every directory a query legitimately reads or writes — its own {@link QueryWorkspace}
     * ({@code workDir}: the snapshots the resolver downloads, the file the materialiser
     * {@code COPY}s to) and its tenant's directories in the two caches.
     *
     * <p>Handed to {@link DuckDbExecutor#restrictFilesystemTo}, which turns it into the only
     * filesystem the connection can see. Anything missing from here shows up as
     * {@code Permission Error: … file system operations are disabled by configuration} rather
     * than as a wrong answer, so the failure mode is loud.
     *
     * <p>Nothing shared is on it. Allowlisting a cache's root, or the whole working area, made the
     * lockdown a boundary between a query and the host but not between queries or tenants: a
     * statement that slipped the SQL screen could read — or glob — every tenant's cached snapshots
     * and wrapper outputs, and every other running query's downloads and pending results.
     */
    // Package-private so QueryFileRootsTest can pin the allowlist directly.
    List<Path> queryFileRoots(UUID tenantUuid, Path workDir) {
        List<Path> roots = new ArrayList<>();
        roots.add(workDir);
        snapshotCache.tenantRoot(tenantUuid).ifPresent(roots::add);
        // The wrapper-output cache is read after the lockdown (fills happen before it), so its
        // directory must be visible to the confined connection.
        wrapperOutputCache.tenantRoot(tenantUuid).ifPresent(roots::add);
        return roots;
    }

    /**
     * Enforced constraints that apply to the active catalog, keyed by integrated-class IRI.
     * v1 tenant-global rule: every enforced constraint the tenant owns whose {@code classIri}
     * names a class in the active catalog is applied — the active schema is tenant-global and
     * the query engine has no project in scope (see
     * {@link IntegrationModelRepository#findEnforcedConstraints}).
     */
    private Map<String, List<String>> enforcedByClass(TenantContext tenant, MappingCatalog cat) {
        Set<String> known = cat.classes().keySet();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (ModelConstraint c : modelRepository.findEnforcedConstraints(tenant)) {
            if (known.contains(c.classIri())) {
                out.computeIfAbsent(c.classIri(), k -> new ArrayList<>()).add(c.expression());
            }
        }
        return out;
    }

    /**
     * Per-dataset (snapshotId, format, iterator) recorded on the source classes, read from the
     * active G_I. The iterator is the object key for a federated object-storage source (what
     * {@code ObjectStorageBootstrap} put in {@code nx:iterator}); it is OPTIONAL because file and
     * relational sources use it differently or not at all in the resolver.
     */
    private Map<String, Snapshot> snapshotMap(TenantContext tenant) {
        Model model = graphStore.query(tenant, """
                PREFIX nx: <%s>
                CONSTRUCT { ?c nx:datasetId ?d ; nx:snapshotId ?s ; nx:format ?f ; nx:iterator ?it }
                WHERE { GRAPH <%s> { ?c nx:datasetId ?d ; nx:snapshotId ?s ; nx:format ?f .
                                     OPTIONAL { ?c nx:iterator ?it } } }
                """.formatted(NS, SCHEMA_GRAPH));

        Property datasetId = ResourceFactory.createProperty(NS + "datasetId");
        Property snapshotId = ResourceFactory.createProperty(NS + "snapshotId");
        Property format = ResourceFactory.createProperty(NS + "format");
        Property iterator = ResourceFactory.createProperty(NS + "iterator");

        Map<String, Snapshot> out = new HashMap<>();
        ResIterator subjects = model.listSubjectsWithProperty(datasetId);
        while (subjects.hasNext()) {
            Resource c = subjects.nextResource();
            Statement d = c.getProperty(datasetId);
            Statement s = c.getProperty(snapshotId);
            Statement f = c.getProperty(format);
            Statement it = c.getProperty(iterator);
            if (d == null || s == null) continue;
            String ds = d.getObject().isLiteral() ? d.getString() : d.getObject().toString();
            String snap = s.getObject().isLiteral() ? s.getString() : s.getObject().toString();
            String fmt = f != null && f.getObject().isLiteral() ? f.getString() : "JSON";
            String iter = it != null && it.getObject().isLiteral() ? it.getString() : "";
            out.putIfAbsent(ds, new Snapshot(snap, fmt, iter));
        }
        return out;
    }

    /**
     * Read-only guard for raw SQL submitted to the {@code run} operation.
     *
     * <p>The ephemeral in-memory DuckDB connection means a <em>write</em> cannot persist, but
     * that was never the threat: the threat is <em>reads</em>, and reads are returned to the
     * caller in the response body. Restricting the statement to SELECT/WITH is therefore not
     * sufficient on its own — {@code SELECT * FROM read_csv_auto('/etc/passwd')} and
     * {@code SELECT * FROM postgres_scan(...)} are both perfectly read-only and both exfiltrate
     * data the caller must not see (the latter reads every tenant's rows out of Aran's own
     * database). So the statement shape check below is paired with the same construct screen the
     * wrapper validator applies.
     *
     * <p>The screen has to look at <em>position</em> and not only at names, because the shortest
     * way to read a file names nothing at all: DuckDB's replacement scan makes
     * {@code SELECT * FROM '/etc/passwd.csv'} a {@code read_csv}. See
     * {@link WrapperSqlValidator#findForbiddenConstruct}.
     *
     * <p>Returns {@code null} when the statement is acceptable, otherwise the reason.
     */
    // Package-private rather than private so QueryEngineSqlGuardTest can exercise the
    // attack strings directly. This is a security boundary; it should not be reachable
    // only through a full operator invocation that needs a live catalog.
    static @Nullable String rejectIfNotReadOnly(String sql) {
        if (sql.isBlank()) return "SQL is empty.";
        String stripped = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        if (stripped.contains(";")) return "Only a single statement is allowed.";
        String lower = stripped.toLowerCase(Locale.ROOT).stripLeading();
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            return "Only read-only SELECT/WITH queries are allowed.";
        }
        String forbidden = WrapperSqlValidator.findForbiddenConstruct(stripped);
        if (forbidden != null) {
            return "Forbidden construct — " + forbidden
                    + ". Queries may only read the integrated views.";
        }
        return null;
    }

    // Package-private (not private) so wrapperCacheSpecs' skip rules are testable in-package.
    record Snapshot(String snapshotId, String format, String iterator) {}
}

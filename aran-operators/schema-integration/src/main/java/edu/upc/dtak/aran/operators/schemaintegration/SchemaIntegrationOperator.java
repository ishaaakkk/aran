package edu.upc.dtak.aran.operators.schemaintegration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.StaleGraphBaseException;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ParameterSpec;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Schema-integration operator.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@code ping} — health check.</li>
 *   <li>{@code integrate} — <em>deprecated</em>; pull a list of source schemas from Fuseki
 *       (the ones previously bootstrapped at {@code urn:aran:source:{datasetId}}), apply
 *       user-supplied alignments via the I1–I4 invariants, and write the result
 *       as a new {@code DRAFT} {@link GraphVersion}. The draft is promoted via the
 *       existing graph-version flow ({@code GraphStore.promote}).</li>
 *   <li>{@code compile} — {@code integrate} driven by the durable integration model (V16):
 *       identical inputs plus an extra {@code classWrappers} parameter whose per-class
 *       wrapper SQL overwrites the source-graph wrapper triples on the draft. Both share
 *       {@link #runIntegration}.</li>
 * </ul>
 *
 * <p>Parameters for {@code integrate} (all values are strings, JSON-encoded where they
 * are structures — this matches the {@code Map&lt;String,String&gt;} signature of
 * {@code OperatorRequest}):
 * <ul>
 *   <li>{@code sourceDatasetIds} (required) — JSON array of dataset UUIDs whose
 *       bootstrapped source graphs should be integrated, e.g.
 *       {@code ["c4f8…","d903…"]}.</li>
 *   <li>{@code alignments} (required) — JSON array of objects each shaped like
 *       {@code {"iriA":"…","iriB":"…","label":"…","type":"class|datatype|object"}}.</li>
 * </ul>
 */
@Component
@NullMarked
public final class SchemaIntegrationOperator implements Operator {

    private static final Logger log = LoggerFactory.getLogger(SchemaIntegrationOperator.class);

    private final GraphStore graphStore;
    private final ObjectMapper objectMapper;
    private final DatasetRepository datasetRepository;
    private final SchemaIntegrator integrator = new SchemaIntegrator();

    public SchemaIntegrationOperator(GraphStore graphStore, ObjectMapper objectMapper,
                                     DatasetRepository datasetRepository) {
        this.graphStore = graphStore;
        this.objectMapper = objectMapper;
        this.datasetRepository = datasetRepository;
    }

    @Override
    public String getOperatorId() {
        return "schema-integration";
    }

    @Override
    public String getDisplayName() {
        return "Schema Integration";
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        log.debug("Schema-integration handling operation '{}' for tenant '{}'",
                request.operation(), request.tenantId());

        return switch (request.operation()) {
            case "ping" -> OperatorResult.success(Map.of(
                    "status", "ok",
                    "operator", "schema-integration",
                    "message", "Schema integration service operational"
            ));
            case "compile"                -> compile(request);
            case "incremental-integrate"  -> incrementalIntegrate(request);
            case "suggest-alignments"     -> suggestAlignments(request);
            default -> OperatorResult.failure(
                    "UNSUPPORTED_OPERATION",
                    "Schema integration does not support operation '" + request.operation() + "'"
            );
        };
    }

    @Override
    public OperatorManifest manifest() {
        return new OperatorManifest(getOperatorId(), getDisplayName(), List.of(
                new OperationSpec("ping", "Health check", false, List.of()),
                new OperationSpec("compile", "Compile integration model to a DRAFT schema version", true, List.of(
                        ParameterSpec.required("sourceDatasetIds", "JSON array of dataset UUIDs"),
                        ParameterSpec.optional("alignments", "JSON array of alignment objects", "[]"),
                        ParameterSpec.optional("classWrappers",
                                "JSON object mapping class IRI to {sql, expansionStatus}", "{}"),
                        // S5-17(4): required, exactly as for incremental-integrate below. A graph
                        // compiled with no model marker can never be extended incrementally.
                        ParameterSpec.required("modelId", "Integration model identifier")
                )),
                new OperationSpec("incremental-integrate", "Add a new source onto an existing promoted schema", true, List.of(
                        ParameterSpec.required("modelId", "Integration model identifier"),
                        ParameterSpec.required("baseVersionId", "UUID of the base promoted graph version"),
                        ParameterSpec.required("newDatasetId", "UUID of the new dataset to integrate"),
                        ParameterSpec.optional("newMappings", "JSON array of new alignments", "[]"),
                        ParameterSpec.optional("deferredMappings", "JSON array of deferred alignments", "[]"),
                        ParameterSpec.optional("classWrappers",
                                "JSON object mapping class IRI to {sql, expansionStatus}", "{}")
                )),
                new OperationSpec("suggest-alignments", "Suggest property alignments between two datasets", false, List.of(
                        ParameterSpec.required("datasetIdA", "UUID of dataset A"),
                        ParameterSpec.required("datasetIdB", "UUID of dataset B"),
                        ParameterSpec.optional("threshold", "Similarity threshold", "0.3")
                ))
        ));
    }

    // ─── suggest-alignments ────────────────────────────────────────────────

    private OperatorResult suggestAlignments(OperatorRequest request) {
        String idA = request.parameters().get("datasetIdA");
        String idB = request.parameters().get("datasetIdB");
        if (idA == null || idA.isBlank() || idB == null || idB.isBlank()) {
            return OperatorResult.failure("MISSING_PARAMETER", "datasetIdA and datasetIdB are required");
        }

        java.util.UUID uuidA, uuidB;
        try {
            uuidA = java.util.UUID.fromString(idA);
            uuidB = java.util.UUID.fromString(idB);
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", "datasetIdA and datasetIdB must be valid UUIDs");
        }

        double threshold = 0.3;
        String tp = request.parameters().get("threshold");
        if (tp != null && !tp.isBlank()) {
            try { threshold = Double.parseDouble(tp); }
            catch (NumberFormatException ignored) { /* keep default */ }
        }

        TenantContext tenant = new TenantContext(request.tenantId());

        java.util.Optional<Dataset> optA = datasetRepository.findById(tenant, uuidA);
        java.util.Optional<Dataset> optB = datasetRepository.findById(tenant, uuidB);
        if (optA.isEmpty()) return OperatorResult.failure("DATASET_NOT_FOUND", "Dataset A not found: " + idA);
        if (optB.isEmpty()) return OperatorResult.failure("DATASET_NOT_FOUND", "Dataset B not found: " + idB);

        Dataset dsA = optA.get();
        Dataset dsB = optB.get();

        // Load source graphs to get the canonical property IRIs
        org.apache.jena.rdf.model.Model graphA = graphStore.query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:source:" + idA + "> { ?s ?p ?o } }");
        org.apache.jena.rdf.model.Model graphB = graphStore.query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:source:" + idB + "> { ?s ?p ?o } }");

        if (graphA.isEmpty()) return OperatorResult.failure("SOURCE_GRAPH_MISSING",
                "No bootstrapped schema for dataset A (" + idA + "). Run bootstrapping first.");
        if (graphB.isEmpty()) return OperatorResult.failure("SOURCE_GRAPH_MISSING",
                "No bootstrapped schema for dataset B (" + idB + "). Run bootstrapping first.");

        java.util.Map<String, String> propIriA = AlignmentSuggester.propertyIriMap(graphA);
        java.util.Map<String, String> propIriB = AlignmentSuggester.propertyIriMap(graphB);

        List<AlignmentSuggester.Candidate> candidates =
                AlignmentSuggester.suggest(dsA, dsB, propIriA, propIriB, threshold);

        List<java.util.Map<String, Object>> rows = new ArrayList<>();
        for (AlignmentSuggester.Candidate c : candidates) {
            java.util.Map<String, Object> row = new LinkedHashMap<>();
            row.put("iriA",            c.iriA());
            row.put("iriB",            c.iriB());
            row.put("columnA",         c.columnA());
            row.put("columnB",         c.columnB());
            row.put("label",           c.label());
            row.put("linguisticScore", Math.round(c.linguisticScore() * 1000.0) / 1000.0);
            row.put("valueScore",      Math.round(c.valueScore()      * 1000.0) / 1000.0);
            row.put("score",           Math.round(c.score()           * 1000.0) / 1000.0);
            rows.add(row);
        }

        try {
            return OperatorResult.success(Map.of("candidates", objectMapper.writeValueAsString(rows)));
        } catch (Exception e) {
            return OperatorResult.failure("JSON_ERROR", "Failed to serialize suggestions");
        }
    }

    /**
     * Compile the durable integration model into a draft integrated schema. The model's
     * per-class wrapper SQL and its verify-wrapper verdict ({@code model_source.wrapper_sql}/
     * {@code expansion_status}) are passed as the {@code classWrappers} JSON parameter
     * ({@code {classIri: {sql, expansionStatus}}}) and overwrite the source-graph
     * {@code nx:wrapperSql}/{@code nx:wrapperExpansion} triples on the draft before it is stored
     * ({@link #applyWrapperOverrides}); {@code sourceDatasetIds} and {@code alignments} drive the
     * union+invariants, returning the {@code draftVersionId}/{@code unusedAlignments}. When
     * {@code classWrappers} is absent the source-graph wrappers are used as-is.
     */
    private OperatorResult compile(OperatorRequest request) {
        Map<String, WrapperOverride> classWrappers;
        try {
            classWrappers = parseClassWrappers(request.parameters().get("classWrappers"));
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", "classWrappers: " + e.getMessage());
        }
        return runIntegration(request, classWrappers);
    }

    private OperatorResult runIntegration(OperatorRequest request, Map<String, WrapperOverride> classWrappers) {
        // Validate presence of required parameters before attempting to parse.
        String rawIds = request.parameters().get("sourceDatasetIds");
        if (rawIds == null || rawIds.isBlank()) {
            return OperatorResult.failure("MISSING_PARAMETER",
                    "'sourceDatasetIds' is required (JSON array of dataset UUIDs).");
        }

        // S5-17(4). The model marker used to be optional here and mandatory for
        // `incremental-integrate`, which reads it off the active base graph and refuses with
        // INCREMENTAL_BASE_MODEL_MISMATCH when it is absent or different. A graph compiled without
        // it is therefore permanently un-extendable — the pay-as-you-go path is closed for it and
        // the only remedy is a full rebuild — and nothing said so at the time. Refuse here, before
        // any graph is read, on the same shape incremental uses.
        String rawModelId = request.parameters().get("modelId");
        if (rawModelId == null || rawModelId.isBlank()) {
            return OperatorResult.failure("MISSING_PARAMETER",
                    "'modelId' is required — a schema compiled without the model marker can never"
                            + " be extended incrementally.");
        }

        List<String> datasetIds;
        List<SchemaIntegrator.TrackedAlignment> alignments;
        try {
            datasetIds = parseDatasetIds(rawIds);
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", "sourceDatasetIds: " + e.getMessage());
        }
        try {
            alignments = parseAlignments(request.parameters().get("alignments"));
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", "alignments: " + e.getMessage());
        }
        if (datasetIds.isEmpty()) {
            return OperatorResult.failure("MISSING_PARAMETER",
                    "Need at least one dataset to integrate (typically two or more).");
        }

        TenantContext tenant = new TenantContext(request.tenantId());

        // Load all source graphs from Fuseki via a single SPARQL CONSTRUCT per graph.
        // The pairwise integration of the paper is the same as "union all + apply
        // alignments" since the I-invariants are commutative across graphs.
        Model unioned = ModelFactory.createDefaultModel();
        List<String> missing = new ArrayList<>();
        for (String dsId : datasetIds) {
            String graphUri = "urn:aran:source:" + dsId;
            Model g = graphStore.query(tenant,
                    "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }");
            if (g.isEmpty()) {
                missing.add(dsId);
            } else {
                // Re-affirm provenance as a literal datasetId on every source class. This is
                // idempotent with the value bootstrapping already wrote; crucially it must be a
                // LITERAL (the catalog reads it as one), never the graph-URI resource.
                org.apache.jena.rdf.model.Property provProp = g.createProperty(IntegrationVocab.PROVENANCE);
                org.apache.jena.rdf.model.ResIterator classes = g.listSubjectsWithProperty(org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.RDFS.Class);
                while (classes.hasNext()) {
                    classes.nextResource().addProperty(provProp, dsId);
                }
                unioned.add(g);
            }
        }
        if (!missing.isEmpty()) {
            return OperatorResult.failure("SOURCE_GRAPH_MISSING",
                    "No bootstrapped schema for datasets: " + String.join(",", missing)
                            + ". Run the bootstrapping operator first.");
        }

        // integrate() expects two graphs, but the union is mathematically equivalent
        // for the I-invariants.
        SchemaIntegrator.IntegrationResult result;
        try {
            result = integrator.integrate(unioned, ModelFactory.createDefaultModel(),
                    alignments, List.of());
        } catch (MappingValidationException e) {
            return OperatorResult.failure("INVALID_ALIGNMENT", e.getMessage());
        }

        // Apply the model's per-class wrapper overrides onto the draft. The source-graph
        // wrapper triples survive the union unchanged (see WrapperVocab); here we replace
        // them for any class the model has an edited wrapper for, so the wrapper users edit
        // is the wrapper that executes.
        applyWrapperOverrides(result.graph(), classWrappers);

        // Stamp the owning model onto the integrated graph so an incremental step can later
        // verify the active base graph belongs to the same model. Unconditional since S5-17(4):
        // the parameter is validated at the top of this method.
        addModelMarker(result.graph(), rawModelId);

        // Persist as DRAFT against the existing schema graph (urn:aran:schema).
        if (request.userId() == null) {
            return OperatorResult.failure("UNAUTHENTICATED",
                    "Schema integration requires an authenticated user");
        }
        AuditedActor actor = new AuditedActor(request.userId());

        GraphVersion draft = graphStore.replaceDraft(tenant, result.graph(), actor);

        log.info("Schema integration produced draft v{} ({}) for tenant {} — {} triples, {} unused alignments",
                draft.versionNumber(), draft.id(), tenant.value(),
                result.graph().size(), result.unused().size());

        Map<String, String> data = new LinkedHashMap<>();
        data.put("draftVersionId", draft.id().toString());
        data.put("draftVersionNumber", String.valueOf(draft.versionNumber()));
        data.put("tripleCount", String.valueOf(result.graph().size()));
        data.put("unusedAlignments", serializeAlignments(result.unused()));
        data.put("appliedMappingIds", serializeMappingIds(result.applied()));
        data.put("deferredMappingIds", serializeMappingIds(result.deferred()));
        data.put("turtle", serializeTurtle(result.graph()));
        return OperatorResult.success(data);
    }

    // ─── Incremental integration ──────────────────────────────────────────

    /**
     * Add exactly one new source on top of a promoted base graph {@code G_I^old}, without
     * re-reading any historical source. Guards (in order): base must still be the active
     * version; the base must be owned by {@code modelId}; the new source must not already be
     * represented; the write is a compare-and-swap against the base version.
     */
    private OperatorResult incrementalIntegrate(OperatorRequest request) {
        Map<String, String> p = request.parameters();
        String modelId = p.get("modelId");
        if (modelId == null || modelId.isBlank()) {
            return OperatorResult.failure("MISSING_PARAMETER", "'modelId' is required.");
        }
        if (request.userId() == null) {
            return OperatorResult.failure("UNAUTHENTICATED",
                    "Schema integration requires an authenticated user");
        }
        UUID baseVersionId;
        UUID newDatasetId;
        try {
            baseVersionId = UUID.fromString(requireParam(p, "baseVersionId"));
            newDatasetId = UUID.fromString(requireParam(p, "newDatasetId"));
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", e.getMessage());
        }

        List<SchemaIntegrator.TrackedAlignment> newMappings;
        List<SchemaIntegrator.TrackedAlignment> deferredMappings;
        Map<String, WrapperOverride> classWrappers;
        try {
            newMappings = parseAlignments(p.get("newMappings"));
            deferredMappings = parseAlignments(p.get("deferredMappings"));
            classWrappers = parseClassWrappers(p.get("classWrappers"));
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", e.getMessage());
        }

        TenantContext tenant = new TenantContext(request.tenantId());

        // 1. The base must still be the current active version.
        GraphVersion current = graphStore.currentVersion(tenant);
        if (current == null || !current.id().equals(baseVersionId)) {
            return OperatorResult.failure("STALE_INCREMENTAL_BASE",
                    "Base version " + baseVersionId + " is not the current active version"
                            + (current == null ? " (no active version)" : " (" + current.id() + ")"));
        }

        // 2. Load G_I^old.
        Model gOld = graphStore.readVersion(tenant, baseVersionId);

        // 3. The base graph must be owned by this model.
        Resource schema = gOld.createResource(SCHEMA_GRAPH_URI);
        Property marker = gOld.createProperty(IntegrationVocab.INTEGRATION_MODEL);
        if (!gOld.contains(schema, marker, modelId)) {
            return OperatorResult.failure("INCREMENTAL_BASE_MODEL_MISMATCH",
                    "The active base graph is not owned by model " + modelId);
        }

        // 4. Read ONLY the new source graph (never the historical sources).
        Model newSource = graphStore.query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:source:" + newDatasetId + "> { ?s ?p ?o } }");
        if (newSource.isEmpty()) {
            return OperatorResult.failure("SOURCE_GRAPH_MISSING",
                    "No bootstrapped schema for dataset " + newDatasetId + ". Run bootstrapping first.");
        }
        // Re-affirm provenance as a literal datasetId on every new source class (as compile does).
        Property provProp = newSource.createProperty(IntegrationVocab.PROVENANCE);
        for (Resource cls : newSource.listSubjectsWithProperty(
                org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.RDFS.Class).toList()) {
            cls.addProperty(provProp, newDatasetId.toString());
        }

        // 5. Reject re-adding a source already represented in G_I^old.
        for (Resource cls : newSource.listSubjectsWithProperty(
                org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.RDFS.Class).toList()) {
            if (cls.isURIResource()
                    && gOld.contains(cls, (Property) null, (org.apache.jena.rdf.model.RDFNode) null)) {
                return OperatorResult.failure("SOURCE_ALREADY_INTEGRATED",
                        "Dataset " + newDatasetId + " is already represented in the base graph (" + cls + ")");
            }
        }

        // 6/7. Integrate the new source into G_I^old (only new + previously-deferred mappings).
        SchemaIntegrator.IntegrationResult result;
        try {
            result = integrator.integrate(gOld, newSource, newMappings, deferredMappings);
        } catch (MappingValidationException e) {
            return OperatorResult.failure("INVALID_ALIGNMENT", e.getMessage());
        }

        applyWrapperOverrides(result.graph(), classWrappers);

        // 8. Refresh the model marker on the output.
        addModelMarker(result.graph(), modelId);

        // 9. Compare-and-write against the base version.
        AuditedActor actor = new AuditedActor(request.userId());
        GraphVersion draft;
        try {
            draft = graphStore.replaceDraftFromBase(tenant, baseVersionId, result.graph(), actor);
        } catch (StaleGraphBaseException e) {
            return OperatorResult.failure("STALE_INCREMENTAL_BASE", e.getMessage());
        }

        log.info("Incremental integration produced draft v{} ({}) for tenant {} model {} — {} triples, "
                        + "{} applied, {} deferred",
                draft.versionNumber(), draft.id(), tenant.value(), modelId,
                result.graph().size(), result.applied().size(), result.deferred().size());

        // 10. Return applied/deferred + the draft.
        Map<String, String> data = new LinkedHashMap<>();
        data.put("draftVersionId", draft.id().toString());
        data.put("draftVersionNumber", String.valueOf(draft.versionNumber()));
        data.put("tripleCount", String.valueOf(result.graph().size()));
        data.put("unusedAlignments", serializeAlignments(result.unused()));
        data.put("appliedMappingIds", serializeMappingIds(result.applied()));
        data.put("deferredMappingIds", serializeMappingIds(result.deferred()));
        data.put("turtle", serializeTurtle(result.graph()));
        return OperatorResult.success(data);
    }

    private static String requireParam(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required.");
        }
        return v;
    }

    /** The canonical active integrated-graph IRI (promote COPYs the draft here). */
    private static final String SCHEMA_GRAPH_URI = "urn:aran:schema";

    /** Stamp {@code <urn:aran:schema> nx:integrationModel "{modelId}"}, refreshing any prior value. */
    private static void addModelMarker(Model graph, String modelId) {
        Resource schema = graph.createResource(SCHEMA_GRAPH_URI);
        Property marker = graph.createProperty(IntegrationVocab.INTEGRATION_MODEL);
        graph.removeAll(schema, marker, null);
        graph.add(schema, marker, modelId);
    }

    // ─── Wrapper overrides (compile) ───────────────────────────────────────

    /**
     * The per-class wrapper-SQL predicate. Same IRI as
     * {@code WrapperVocab.WRAPPER_SQL} in the bootstrapping module (this module does
     * not depend on it, so the constant is inlined — {@code IntegrationVocab.NS}
     * is the same namespace, cf. {@code SchemaIntegrator.assertDepthOne}).
     */
    private static final String WRAPPER_SQL_IRI = IntegrationVocab.NS + "wrapperSql";

    /**
     * Same namespace as {@link #WRAPPER_SQL_IRI} above, same reason it is inlined rather than a
     * shared constant: this module does not depend on the query-engine module that reads it back
     * ({@code MappingCatalogOperator}'s QUERY 1, {@code OPTIONAL { ?srcClass nx:wrapperExpansion
     * ?expansion . }}). Phase 3A's compile-time verify-wrapper verdict for {@link #WRAPPER_SQL_IRI},
     * stamped next to it so a consumer never has to join across two separate writes to see both.
     */
    private static final String WRAPPER_EXPANSION_IRI = IntegrationVocab.NS + "wrapperExpansion";

    /**
     * A class's wrapper-SQL override plus the compile-time verify-wrapper verdict
     * {@code IntegrationModelService.setWrapper} persisted for it. {@code expansionStatus} is
     * {@code null} exactly when the wrapper was never verified — {@link #applyWrapperOverrides}
     * stamps {@link #WRAPPER_EXPANSION_IRI} only when it is present, and removes any triple
     * already there otherwise, so an edit that has no fresh verdict cannot leave a stale one
     * behind.
     */
    private record WrapperOverride(String sql, @org.jspecify.annotations.Nullable String expansionStatus) {
    }

    private void applyWrapperOverrides(Model graph, Map<String, WrapperOverride> classWrappers) {
        if (classWrappers.isEmpty()) {
            return;
        }
        org.apache.jena.rdf.model.Property wrapperSql = graph.createProperty(WRAPPER_SQL_IRI);
        org.apache.jena.rdf.model.Property wrapperExpansion = graph.createProperty(WRAPPER_EXPANSION_IRI);
        for (Map.Entry<String, WrapperOverride> e : classWrappers.entrySet()) {
            String sql = e.getValue().sql();
            if (sql == null || sql.isBlank()) {
                continue;
            }
            org.apache.jena.rdf.model.Resource cls = graph.createResource(e.getKey());
            graph.removeAll(cls, wrapperSql, null);
            graph.add(cls, wrapperSql, sql);

            // The verdict travels with the sql it was computed for. Always clear first: a status
            // carried over from a stale prior compile must not survive an edit whose own verdict
            // is absent/null — MappingCatalogOperator's QUERY 1 already defaults an absent triple
            // to OPAQUE, so removing it here is exactly as safe as never having verified at all.
            graph.removeAll(cls, wrapperExpansion, null);
            String status = e.getValue().expansionStatus();
            if (status != null && !status.isBlank()) {
                graph.add(cls, wrapperExpansion, status);
            }
        }
    }

    private Map<String, WrapperOverride> parseClassWrappers(@org.jspecify.annotations.Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode obj = objectMapper.readTree(json);
            if (!obj.isObject()) {
                throw new IllegalArgumentException("must be a JSON object {classIri: {sql, expansionStatus}}");
            }
            Map<String, WrapperOverride> out = new LinkedHashMap<>();
            java.util.Iterator<String> names = obj.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                JsonNode value = obj.get(key);
                if (value == null || !value.isObject()) {
                    log.warn("classWrappers entry for '{}' is not a JSON object ({sql,"
                            + " expansionStatus}) — skipping it", key);
                    continue;
                }
                JsonNode sqlNode = value.get("sql");
                if (sqlNode == null || sqlNode.isNull()) {
                    continue;
                }
                JsonNode statusNode = value.get("expansionStatus");
                String expansionStatus = statusNode != null && !statusNode.isNull() ? statusNode.asText() : null;
                out.put(key, new WrapperOverride(sqlNode.asText(), expansionStatus));
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid JSON — " + e.getMessage());
        }
    }

    // ─── Parameter parsing ─────────────────────────────────────────────────

    private List<String> parseDatasetIds(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("required");
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) throw new IllegalArgumentException("must be a JSON array");
            List<String> ids = new ArrayList<>();
            for (JsonNode n : arr) {
                String s = n.asText();
                // Validate it parses as a UUID — fail fast on garbage.
                UUID.fromString(s);
                ids.add(s);
            }
            return ids;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid JSON — " + e.getMessage());
        }
    }

    private List<SchemaIntegrator.TrackedAlignment> parseAlignments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) throw new IllegalArgumentException("must be a JSON array");
            List<SchemaIntegrator.TrackedAlignment> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String iriA = n.path("iriA").asText("");
                String iriB = n.path("iriB").asText("");
                String label = n.path("label").asText("");
                String type = n.path("type").asText("");
                Alignment alignment = new Alignment(iriA, iriB, label, Alignment.Type.from(type));
                // mappingId is optional and backward-compatible (absent → null).
                UUID mappingId = null;
                JsonNode idNode = n.get("mappingId");
                if (idNode != null && !idNode.isNull() && !idNode.asText().isBlank()) {
                    try {
                        mappingId = UUID.fromString(idNode.asText());
                    } catch (IllegalArgumentException ignored) {
                        // Malformed id — treat as untracked rather than failing the whole compile.
                    }
                }
                out.add(new SchemaIntegrator.TrackedAlignment(mappingId, alignment));
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid JSON — " + e.getMessage());
        }
    }

    // ─── Serialization helpers ─────────────────────────────────────────────

    /** Comma-separated non-null mappingIds from a tracked-alignment list. */
    private static String serializeMappingIds(List<SchemaIntegrator.TrackedAlignment> tracked) {
        return tracked.stream()
                .map(SchemaIntegrator.TrackedAlignment::mappingId)
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String serializeAlignments(List<Alignment> als) {
        try {
            List<Map<String, String>> rows = new ArrayList<>(als.size());
            for (Alignment a : als) {
                rows.add(Map.of(
                        "iriA", a.iriA(),
                        "iriB", a.iriB(),
                        "label", a.label(),
                        "type", a.type().name().toLowerCase()));
            }
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String serializeTurtle(Model model) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            RDFDataMgr.write(out, model, Lang.TURTLE);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}

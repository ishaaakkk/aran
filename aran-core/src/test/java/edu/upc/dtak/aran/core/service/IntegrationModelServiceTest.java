package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.fake.FakeGraphStore;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.IntegrationLinks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link IntegrationModelService} with in-memory fakes.
 *
 * <p>The load-bearing assertion is that {@code rebuildToDraft} reconstructs the operator's
 * alignment payload <em>byte-for-byte</em> as {@code AlignmentsView} serialises it today
 * ({@code {"iriA":…,"iriB":…,"label":…,"type":…}}), so the model compiler round-trips
 * losslessly through the existing {@code integrate}/{@code compile} code path.
 */
class IntegrationModelServiceTest {

    private static final String WRAPPER_SQL_IRI = "http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql";

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final FakeGraphStore graphStore = new FakeGraphStore();
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Never meaningfully consulted by this file's scenarios: none of them stage a draft graph
    // carrying nx:wrapperSql facts, so the Task 3 compile tail finds nothing to profile and never
    // calls it. See IntegrationModelServiceWrapperOutputProfilingTest for the behaviour itself.
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);

    /** Captures the request the service hands the schema-integration operator. */
    private final AtomicReference<OperatorRequest> captured = new AtomicReference<>();

    private OperatorOrchestrator orchestratorReturning(OperatorResult result) {
        Operator capturing = new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "Schema Integration (capturing)"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                captured.set(request);
                return result;
            }
        };
        return new OperatorOrchestrator(List.of(capturing));
    }

    private IntegrationModelService serviceReturning(OperatorResult result) {
        return new IntegrationModelService(
                repo, graphStore, orchestratorReturning(result), objectMapper, datasetRepository,
                mock(DataProductService.class));
    }

    /** A service whose query-engine {@code validate-constraint} returns the given result. */
    private IntegrationModelService serviceValidatingWith(OperatorResult validation) {
        Operator queryEngine = new Operator() {
            @Override public String getOperatorId() { return "query-engine"; }
            @Override public String getDisplayName() { return "Query Engine (validating)"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                captured.set(request);
                return validation;
            }
        };
        return new IntegrationModelService(repo, graphStore,
                new OperatorOrchestrator(List.of(queryEngine)), objectMapper, datasetRepository,
                mock(DataProductService.class));
    }

    @Test
    @DisplayName("compile reconstructs the exact alignment payload AlignmentsView sends, N-ary sources, and wrapper overrides")
    void compile_reconstructs_payload_byte_for_byte() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID tenantId = UUID.fromString(tenant.value());
        UUID datasetA = UUID.randomUUID();
        UUID datasetB = UUID.randomUUID();

        // Two enrolled sources; only A carries an edited wrapper.
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), tenantId, model.id(),
                datasetA, "http://ex/A", "SELECT 1", null, null, false));
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), tenantId, model.id(),
                datasetB, "http://ex/B", null, null, null, false));

        // One ACCEPTED class alignment (fed to compile) + one SUGGESTED (must be excluded).
        UUID classMappingId = UUID.randomUUID();
        repo.addMapping(tenant, new ModelMapping(classMappingId, tenantId, model.id(),
                MappingKinds.CLASS, "http://ex/A", "http://ex/B", "Paper",
                ModelMapping.ACCEPTED, null, actor, null, Instant.now()));
        repo.addMapping(tenant, new ModelMapping(UUID.randomUUID(), tenantId, model.id(),
                MappingKinds.DATATYPE, "http://ex/A#t", "http://ex/B#t", "title",
                ModelMapping.SUGGESTED, "{\"score\":0.9}", actor, null, Instant.now()));

        UUID draftVersionId = UUID.randomUUID();
        IntegrationModelService service = serviceReturning(
                OperatorResult.success(Map.of("draftVersionId", draftVersionId.toString(),
                        "unusedAlignments", "[]")));

        Map<String, String> result = service.rebuildToDraft(tenant, projectId, actor);

        OperatorRequest req = captured.get();
        assertThat(req.operation()).isEqualTo("compile");

        // Byte-for-byte identical to JSON.stringify([{mappingId, iriA, iriB, label, type}]) — only the ACCEPTED row.
        // mappingId is carried so the operator can report per-mapping applied/deferred outcomes.
        assertThat(req.parameters().get("alignments"))
                .isEqualTo("[{\"mappingId\":\"" + classMappingId + "\",\"iriA\":\"http://ex/A\",\"iriB\":\"http://ex/B\",\"label\":\"Paper\",\"type\":\"class\"}]");

        // N-ary: every distinct enrolled dataset, not just a pair.
        assertThat(req.parameters().get("sourceDatasetIds"))
                .isEqualTo("[\"" + datasetA + "\",\"" + datasetB + "\"]");

        // Only the edited wrapper is passed as an override, sql paired with its expansion
        // status — see SchemaIntegrationOperator.WrapperOverride. Since T2 a never-verified
        // wrapper is verified AT COMPILE (refreshWrapperVerdicts); this fixture has no
        // query-engine operator, so the attempt folds into the retryable OPAQUE/"verify
        // unavailable" verdict rather than riding through as null.
        assertThat(req.parameters().get("classWrappers"))
                .isEqualTo("{\"http://ex/A\":{\"sql\":\"SELECT 1\",\"expansionStatus\":\"OPAQUE\"}}");

        assertThat(result).containsEntry("draftVersionId", draftVersionId.toString());
    }

    @Test
    @DisplayName("a failing operator result fails compile with the operator's error code")
    void compile_propagates_operator_failure() {
        IntegrationModelService service = serviceReturning(
                OperatorResult.failure("SOURCE_GRAPH_MISSING", "no graph"));

        assertThatThrownBy(() -> service.rebuildToDraft(tenant, projectId, actor))
                .isInstanceOf(JobFailureException.class)
                .hasMessageContaining("no graph");
    }

    @Test
    @DisplayName("addSource seeds one row per rdfs:Class, carrying nx:wrapperSql, and 404s when the graph is missing")
    void addSource_seeds_classes_and_wrappers() {
        // Missing source graph → not found.
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));
        assertThatThrownBy(() -> service.addSource(tenant, projectId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Two classes; only ClassA has a wrapper.
        Model g = ModelFactory.createDefaultModel();
        Resource clsA = g.createResource("http://ex/ClassA");
        g.add(clsA, RDF.type, RDFS.Class);
        g.add(clsA, g.createProperty(WRAPPER_SQL_IRI), "SELECT * FROM ${SNAPSHOT}");
        Resource clsB = g.createResource("http://ex/ClassB");
        g.add(clsB, RDF.type, RDFS.Class);
        graphStore.stage(tenant, g);

        List<ModelSource> seeded = service.addSource(tenant, projectId, UUID.randomUUID());

        assertThat(seeded).hasSize(2);
        assertThat(seeded).extracting(ModelSource::classIri)
                .containsExactlyInAnyOrder("http://ex/ClassA", "http://ex/ClassB");
        assertThat(seeded).filteredOn(s -> s.classIri().equals("http://ex/ClassA"))
                .singleElement()
                .extracting(ModelSource::wrapperSql)
                .isEqualTo("SELECT * FROM ${SNAPSHOT}");
        assertThat(seeded).filteredOn(s -> s.classIri().equals("http://ex/ClassB"))
                .singleElement()
                .extracting(ModelSource::wrapperSql)
                .isNull();
    }

    @Test
    @DisplayName("setWrapper verifies the sql via query-engine's verify-wrapper and persists sql+verdict together")
    void setWrapper_persists_verify_wrapper_verdict() {
        IntegrationModelService service = serviceValidatingWith(
                OperatorResult.success(Map.of("status", "EXPANDED", "reason", "")));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID datasetId = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/Paper", null, null, null, false));

        service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper", "SELECT * FROM ${SNAPSHOT}");

        ModelSource saved = onlySource(service, projectId, "http://ex/Paper");
        assertThat(saved.wrapperSql()).isEqualTo("SELECT * FROM ${SNAPSHOT}");
        assertThat(saved.expansionStatus()).isEqualTo("EXPANDED");
        assertThat(saved.expansionReason()).as("EXPANDED carries no reason to explain").isNull();

        assertThat(captured.get().operation()).isEqualTo("verify-wrapper");
        assertThat(captured.get().parameters())
                .containsEntry("datasetId", datasetId.toString())
                .containsEntry("classIri", "http://ex/Paper")
                .containsEntry("sql", "SELECT * FROM ${SNAPSHOT}");
    }

    @Test
    @DisplayName("setWrapper refuses a wrapper that no longer outputs a column its class reads, naming it")
    void setWrapper_refuses_a_wrapper_missing_a_mapped_column() {
        IntegrationModelService service = serviceValidatingWith(OperatorResult.success(Map.of(
                "status", "OPAQUE", "reason", "the wrapper does not output column(s) …",
                "missingColumns", "http://src/ds/Paper/title\nhttp://src/ds/Paper/year")));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID datasetId = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/Paper", "SELECT 1", null, null, false));

        assertThatThrownBy(() -> service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper",
                "SELECT \"title\" AS renamed FROM ${SNAPSHOT}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http://src/ds/Paper/title, http://src/ds/Paper/year");

        assertThat(onlySource(service, projectId, "http://ex/Paper").wrapperSql())
                .as("the refused text was not saved over the working one")
                .isEqualTo("SELECT 1");
    }

    @Test
    @DisplayName("setWrapper refuses a wrapper that does not bind over its dataset's data, naming DuckDB's error")
    void setWrapper_refuses_a_wrapper_that_does_not_bind() {
        String binder = "Binder Error: Referenced column \"titel\" not found in FROM clause!\n"
                + "Candidate bindings: \"title\"";
        IntegrationModelService service = serviceValidatingWith(OperatorResult.success(Map.of(
                "status", "OPAQUE", "reason", "the wrapper does not bind over its dataset's data: " + binder,
                "bindError", binder)));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID datasetId = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/Paper", "SELECT 1", null, null, false));

        assertThatThrownBy(() -> service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper",
                "SELECT \"titel\" AS \"http://src/ds/Paper/title\" FROM ${SNAPSHOT}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not run over its dataset's data")
                .hasMessageContaining("Referenced column \"titel\" not found");

        assertThat(onlySource(service, projectId, "http://ex/Paper").wrapperSql())
                .as("the refused text was not saved over the working one")
                .isEqualTo("SELECT 1");
    }

    @Test
    @DisplayName("setWrapper saves a wrapper whose bind check could not run — no bindError, no refusal")
    void setWrapper_saves_when_the_bind_check_could_not_run() {
        // What verify-wrapper answers when its column check could not describe the text for a
        // reason that is not provably the text's (a federated source that went away, a refreshed
        // dataset the active schema has not caught up with): an ordinary OPAQUE verdict.
        IntegrationModelService service = serviceValidatingWith(OperatorResult.success(Map.of(
                "status", "OPAQUE", "reason", "wrapper does not plan cleanly over its src_ relation")));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID datasetId = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/Paper", "SELECT 1", null, null, false));

        service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper",
                "SELECT \"venue\" AS \"http://src/ds/Paper/venue\" FROM ${SNAPSHOT}");

        ModelSource saved = onlySource(service, projectId, "http://ex/Paper");
        assertThat(saved.wrapperSql()).contains("\"venue\"");
        assertThat(saved.expansionStatus()).isEqualTo("OPAQUE");
        assertThat(saved.expansionReason()).contains("does not plan cleanly");
    }

    @Test
    @DisplayName("setWrapper degrades to OPAQUE (never fails the save) when verify-wrapper's own result fails")
    void setWrapper_degrades_to_opaque_when_verify_result_fails() {
        IntegrationModelService service = serviceValidatingWith(
                OperatorResult.failure("QUERY_ENGINE_ERROR", "kaboom"));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID datasetId = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/Paper", null, null, null, false));

        service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper", "SELECT * FROM ${SNAPSHOT}");

        ModelSource saved = onlySource(service, projectId, "http://ex/Paper");
        assertThat(saved.wrapperSql()).as("the save must succeed even though verification failed")
                .isEqualTo("SELECT * FROM ${SNAPSHOT}");
        assertThat(saved.expansionStatus()).isEqualTo("OPAQUE");
        assertThat(saved.expansionReason()).contains("verify unavailable:").contains("kaboom");
    }

    @Test
    @DisplayName("setWrapper caps an oversized reason to expansion_reason's column width — the save must still succeed")
    void setWrapper_caps_an_oversized_reason_to_the_column_width() {
        String oversizedReason = "x".repeat(2000);
        IntegrationModelService service = serviceValidatingWith(
                OperatorResult.success(Map.of("status", "OPAQUE", "reason", oversizedReason)));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID datasetId = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/Paper", null, null, null, false));

        // Must not throw — a 2000-char diagnostic must never make the row (and therefore the sql
        // edit it rides with) fail to save, e.g. Postgres rejecting VARCHAR(512) overflow.
        service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper", "SELECT * FROM ${SNAPSHOT}");

        ModelSource saved = onlySource(service, projectId, "http://ex/Paper");
        assertThat(saved.wrapperSql()).as("the save must succeed despite the oversized reason")
                .isEqualTo("SELECT * FROM ${SNAPSHOT}");
        assertThat(saved.expansionStatus()).isEqualTo("OPAQUE");
        assertThat(saved.expansionReason())
                .as("must fit V30's expansion_reason VARCHAR(512)")
                .hasSizeLessThanOrEqualTo(512)
                .endsWith("…")
                .startsWith("x".repeat(100));
    }

    @Test
    @DisplayName("setWrapper degrades to OPAQUE and still saves when the verify-wrapper invocation itself throws")
    void setWrapper_degrades_to_opaque_when_verify_invocation_throws() {
        Operator throwing = new Operator() {
            @Override public String getOperatorId() { return "query-engine"; }
            @Override public String getDisplayName() { return "Query Engine (throwing)"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        IntegrationModelService service = new IntegrationModelService(
                repo, graphStore, new OperatorOrchestrator(List.of(throwing)), objectMapper, datasetRepository,
                mock(DataProductService.class));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID datasetId = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/Paper", null, null, null, false));

        // Must not throw — a verify invocation exception must never turn a valid save into a
        // failed request.
        service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper", "SELECT * FROM ${SNAPSHOT}");

        ModelSource saved = onlySource(service, projectId, "http://ex/Paper");
        assertThat(saved.wrapperSql()).isEqualTo("SELECT * FROM ${SNAPSHOT}");
        assertThat(saved.expansionStatus()).isEqualTo("OPAQUE");
        assertThat(saved.expansionReason()).contains("verify unavailable:");
    }

    @Test
    @DisplayName("setWrapper clears any prior verdict when the wrapper override itself is cleared (sql=null)")
    void setWrapper_clearing_sql_clears_the_verdict_too() {
        IntegrationModelService service = serviceValidatingWith(
                OperatorResult.success(Map.of("status", "EXPANDED", "reason", "")));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID datasetId = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/Paper", null, null, null, false));
        service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper", "SELECT * FROM ${SNAPSHOT}");

        service.setWrapper(tenant, projectId, datasetId, "http://ex/Paper", null);

        ModelSource saved = onlySource(service, projectId, "http://ex/Paper");
        assertThat(saved.wrapperSql()).isNull();
        assertThat(saved.expansionStatus()).as("nothing is left to have been verified").isNull();
        assertThat(saved.expansionReason()).isNull();
    }

    // ── V42: which integrated classes the ACTIVE catalog actually serves ──────────────────

    /**
     * The set the constraints payload marks {@code orphaned} against. It must be the catalog's
     * OWN rule ({@code MappingCatalogOperator}'s QUERY 1: an integrated class reaches the catalog
     * only via a wrappered source class beneath it), not "every IntegratedResource in the graph" —
     * a class the merge retired keeps its {@code a nx:IntegratedResource} triple in some graphs,
     * and a looser test would report a dead class as live, which is the V42 lie itself.
     */
    @Test
    @DisplayName("activeIntegratedClassIris reports only classes with a wrappered source beneath them")
    void activeIntegratedClassIris_mirrors_the_catalogs_own_join() {
        Model g = ModelFactory.createDefaultModel();
        Resource integrated = g.createResource("http://ex/integration/Merged");
        g.add(integrated, RDF.type, g.createResource(
                "http://www.essi.upc.edu/DTIM/NextiaDI/IntegratedResource"));
        g.add(integrated, RDF.type, RDFS.Class);
        Resource src = g.createResource("http://ex/source/ds1/authors");
        g.add(src, RDF.type, RDFS.Class);
        g.add(src, RDFS.subClassOf, integrated);
        g.add(src, g.createProperty(WRAPPER_SQL_IRI), "SELECT * FROM ${SNAPSHOT}");

        // Retired by an alignment merge: still typed IntegratedResource, but nothing wrappered
        // is subClassOf it any more — exactly the Int_j_authors shape V42 was found on.
        Resource retired = g.createResource("http://ex/integration/Int_j_authors");
        g.add(retired, RDF.type, g.createResource(
                "http://www.essi.upc.edu/DTIM/NextiaDI/IntegratedResource"));
        g.add(retired, RDF.type, RDFS.Class);
        graphStore.stage(tenant, g);

        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        assertThat(service.activeIntegratedClassIris(tenant))
                .containsExactly("http://ex/integration/Merged");
    }

    /**
     * The narrowed CONSTRUCT is real SPARQL that only Fuseki ever executes: both test graph stores
     * ({@code FakeGraphStore} here, {@code BaseIntegrationTest.InMemoryGraphStore} in the app
     * module) answer a query by pattern-matching its graph URI and never parse it. So a syntax
     * error, or a predicate missing from the FILTER, would pass every other test in the repo and
     * fail only against a live backend. This runs it for real.
     */
    @Test
    @DisplayName("the active-class query is valid SPARQL and keeps exactly the predicates the walk needs")
    void activeClassQueryExecutesForRealAndKeepsWhatTheWalkReads() {
        Model schema = ModelFactory.createDefaultModel();
        Resource integrated = schema.createResource("http://ex/integration/Merged");
        schema.add(integrated, RDF.type, schema.createResource(
                "http://www.essi.upc.edu/DTIM/NextiaDI/IntegratedResource"));
        // BOTH types, as SchemaIntegrator writes them and as QUERY 1 requires them.
        schema.add(integrated, RDF.type, RDFS.Class);
        Resource src = schema.createResource("http://ex/source/ds1/authors");
        schema.add(src, RDFS.subClassOf, integrated);
        schema.add(src, schema.createProperty(WRAPPER_SQL_IRI), "SELECT * FROM ${SNAPSHOT}");
        // Bulk the graph out with the kind of triple the narrowing exists to leave behind.
        schema.add(src, RDFS.label, "authors");
        schema.add(src, schema.createProperty(
                "http://www.essi.upc.edu/DTIM/NextiaDI/snapshotId"), "snap-1");

        org.apache.jena.query.Dataset ds = org.apache.jena.query.DatasetFactory.create();
        ds.addNamedModel("urn:aran:schema", schema);
        Model constructed;
        try (org.apache.jena.query.QueryExecution qexec = org.apache.jena.query.QueryExecutionFactory
                .create(IntegrationModelService.ACTIVE_CLASS_QUERY, ds)) {
            constructed = qexec.execConstruct();
        }

        assertThat(IntegrationLinks.servedIntegratedClasses(constructed))
                .as("the narrowed read must still answer the question the full graph answered")
                .containsExactly("http://ex/integration/Merged");
        assertThat(constructed.contains(src, RDFS.label))
                .as("...while leaving the triples the walk never reads behind").isFalse();
    }

    @Test
    @DisplayName("an IntegratedResource that is not also an rdfs:Class is not served — QUERY 1 binds both types")
    void activeIntegratedClassIrisRequiresBothTypesLikeQuery1() {
        // QUERY 1 is `?intClass a nx:IntegratedResource ; a rdfs:Class`, and
        // reporting a class the catalog does not serve is V42's own lie in miniature: the payload
        // would call a constraint live while `enforcedByClass` silently skips it.
        Model g = ModelFactory.createDefaultModel();
        Resource halfTyped = g.createResource("http://ex/integration/HalfTyped");
        g.add(halfTyped, RDF.type, g.createResource(
                "http://www.essi.upc.edu/DTIM/NextiaDI/IntegratedResource"));
        Resource src = g.createResource("http://ex/source/ds1/authors");
        g.add(src, RDFS.subClassOf, halfTyped);
        g.add(src, g.createProperty(WRAPPER_SQL_IRI), "SELECT * FROM ${SNAPSHOT}");
        graphStore.stage(tenant, g);

        assertThat(serviceReturning(OperatorResult.success(Map.of()))
                .activeIntegratedClassIris(tenant)).isEmpty();
    }

    @Test
    @DisplayName("activeIntegratedClassIris is empty when nothing has been promoted — 'no catalog', not 'no classes'")
    void activeIntegratedClassIris_is_empty_without_an_active_schema() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        assertThat(service.activeIntegratedClassIris(tenant)).isEmpty();
    }

    private ModelSource onlySource(IntegrationModelService service, UUID projectId, String classIri) {
        return service.getBundle(tenant, projectId).sources().stream()
                .filter(s -> s.classIri().equals(classIri))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("addConstraint rejects an invalid expression with the engine's parse message (400)")
    void addConstraint_rejects_invalid_expression() {
        IntegrationModelService service = serviceValidatingWith(
                OperatorResult.failure("INVALID_CONSTRAINT", "Parser Error: syntax error at or near \"=!\""));

        assertThatThrownBy(() -> service.addConstraint(tenant, projectId, "urn:IResearcher", "h_index =! 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Parser Error");

        // The engine was asked to validate exactly this expression.
        assertThat(captured.get().operation()).isEqualTo("validate-constraint");
        assertThat(captured.get().parameters()).containsEntry("expression", "h_index =! 0");
    }

    @Test
    @DisplayName("addConstraint persists an unenforced row when the expression validates")
    void addConstraint_persists_valid_expression() {
        IntegrationModelService service = serviceValidatingWith(
                OperatorResult.success(Map.of("status", "ok", "validated", "true")));

        ModelConstraint saved = service.addConstraint(tenant, projectId, "urn:IResearcher", "h_index >= 0");

        assertThat(saved.classIri()).isEqualTo("urn:IResearcher");
        assertThat(saved.expression()).isEqualTo("h_index >= 0");
        assertThat(saved.enforced()).isFalse();
        assertThat(saved.origin()).as("hand-authored through this path, never DISCOVERED").isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("integratedDatasetIds reads the active graph's provenance, not the query catalog")
    void integrated_dataset_ids_from_active_graph() {
        UUID dsA = UUID.randomUUID();
        UUID dsB = UUID.randomUUID();

        var g = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        var provenance = g.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/provenance");
        var datasetId = g.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/datasetId");
        // A source that ended up under an integrated class carries provenance…
        g.add(g.createResource("urn:src:a"), provenance, dsA.toString());
        // …and one integrated with NO accepted alignment still carries its bootstrap datasetId.
        // The query catalog omits that second case entirely — which is exactly why it cannot be
        // the source of truth for "already integrated", and why this reads the graph instead.
        g.add(g.createResource("urn:src:b"), datasetId, dsB.toString());
        // A non-uuid literal must be skipped, not blow up the whole bundle load.
        g.add(g.createResource("urn:src:c"), provenance, "not-a-uuid");
        graphStore.stage(tenant, g);

        assertThat(serviceReturning(OperatorResult.success(Map.of())).integratedDatasetIds(tenant))
                .containsExactlyInAnyOrder(dsA, dsB);
    }

    @Test
    @DisplayName("integratedDatasetIds is empty when nothing has been promoted")
    void integrated_dataset_ids_empty_without_a_graph() {
        assertThat(serviceReturning(OperatorResult.success(Map.of())).integratedDatasetIds(tenant))
                .isEmpty();
    }

    // ── Audit JOIN_DISCOVERY-4: a label-less mapping bricks every future compile ──
    //
    // acceptedAlignmentsJson serialises a null label as "", and the schema-integration
    // operator's Alignment constructor throws on a blank one — failing the WHOLE compile with
    // an error that names no mapping. The row is only reachable through the API (the UI's Add
    // button is disabled while the label box is empty), and once written nothing but a hand
    // DELETE clears it, so the cost of accepting it is unbounded. Reject at the door instead.

    @Test
    @DisplayName("addMapping rejects a null label with a 400-mapped IllegalArgumentException")
    void add_mapping_rejects_null_label() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        assertThatThrownBy(() -> service.addMapping(tenant, projectId, MappingKinds.JOIN,
                "http://ex/A", "http://ex/B", null, actor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label");

        assertThat(service.getBundle(tenant, projectId).mappings())
                .as("nothing was persisted")
                .isEmpty();
    }

    @Test
    @DisplayName("addMapping rejects a blank label for every kind, not just join")
    void add_mapping_rejects_blank_label_for_every_kind() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        for (String kind : List.of(MappingKinds.CLASS, MappingKinds.DATATYPE,
                MappingKinds.OBJECT, MappingKinds.JOIN)) {
            assertThatThrownBy(() -> service.addMapping(tenant, projectId, kind,
                    "http://ex/A", "http://ex/B", "   ", actor, null))
                    .as("kind %s", kind)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("label");
        }

        assertThat(service.getBundle(tenant, projectId).mappings()).isEmpty();
    }

    @Test
    @DisplayName("addMapping still accepts a labelled mapping unchanged")
    void add_mapping_accepts_a_labelled_mapping() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        ModelMapping saved = service.addMapping(tenant, projectId, MappingKinds.JOIN,
                "http://ex/A", "http://ex/B", "wrote", actor, null);

        assertThat(saved.label()).isEqualTo("wrote");
        assertThat(saved.status()).isEqualTo(ModelMapping.ACCEPTED);
        assertThat(repo.findMappingApplication(tenant, saved.id())).isPresent();
    }

    @Test
    @DisplayName("addMapping refuses a join whose label and relationship name mint one IRI, naming both")
    void add_mapping_refuses_a_join_whose_label_equals_its_relationship() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        assertThatThrownBy(() -> service.addMapping(tenant, projectId, MappingKinds.JOIN,
                "http://ex/A", "http://ex/B", "wrote|wrote|forward", actor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("join label 'wrote'")
                .hasMessageContaining("relationship name 'wrote'");
        // Only characters Names.iriFragment rewrites differ: still one IRI.
        assertThatThrownBy(() -> service.addMapping(tenant, projectId, MappingKinds.JOIN,
                "http://ex/A", "http://ex/B", "wrote by| wrote_by |backward", actor, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.getBundle(tenant, projectId).mappings()).isEmpty();

        ModelMapping distinct = service.addMapping(tenant, projectId, MappingKinds.JOIN,
                "http://ex/A", "http://ex/B", "authorKey|wrote|forward", actor, null);
        assertThat(distinct.label()).isEqualTo("authorKey|wrote|forward");
    }

    // ── J4-F6: an alignment's two ends must have the shape its kind needs ───────────
    // The Alignments view let a class node and a property node be aligned as "datatype" (and an
    // xsd:string node be picked at all); every rebuild from then on failed INVALID_ALIGNMENT
    // naming a full IRI. The shape is knowable at the door from the enrolled source graphs.

    private static final String SRC = "http://www.essi.upc.edu/DTIM/NextiaDI/source/";

    /** Enrol one dataset whose source graph has class {@code cls} with datatype property {@code prop}. */
    private void enrolWithGraph(UUID datasetId, String cls, String prop) {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, cls, null, null, null, false));
        Model g = ModelFactory.createDefaultModel();
        Resource c = g.createResource(cls);
        c.addProperty(RDF.type, RDFS.Class);
        Resource p = g.createResource(prop);
        p.addProperty(RDF.type, RDF.Property);
        p.addProperty(RDFS.domain, c);
        p.addProperty(RDFS.range, g.createResource("http://www.w3.org/2001/XMLSchema#string"));
        graphStore.stageGraph(tenant, "urn:aran:source:" + datasetId, g);
    }

    @Test
    @DisplayName("J4-F6: addMapping refuses a class aligned with a property, naming which end is which")
    void add_mapping_refuses_a_class_aligned_with_a_property() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // Minted as bootstrap mints them: SOURCE_NS + datasetId + "/" + class[/property].
        String acm = SRC + a + "/acm";
        String semantic = SRC + b + "/semantic";
        enrolWithGraph(a, acm, acm + "/doi");
        enrolWithGraph(b, semantic, semantic + "/doi");
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        assertThatThrownBy(() -> service.addMapping(tenant, projectId, MappingKinds.DATATYPE,
                acm, semantic + "/doi", "doi", actor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(acm + " is a class")
                .hasMessageContaining("a property on both sides");
        assertThatThrownBy(() -> service.addMapping(tenant, projectId, MappingKinds.CLASS,
                acm, semantic + "/doi", "Paper", actor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(semantic + "/doi is a property")
                .hasMessageContaining("a class on both sides");
        assertThat(service.getBundle(tenant, projectId).mappings()).isEmpty();

        // The well-shaped alignments still land.
        service.addMapping(tenant, projectId, MappingKinds.CLASS, acm, semantic, "Paper", actor, null);
        service.addMapping(tenant, projectId, MappingKinds.DATATYPE, acm + "/doi", semantic + "/doi", "doi", actor, null);
        assertThat(service.getBundle(tenant, projectId).mappings()).hasSize(2);
    }

    @Test
    @DisplayName("J4-F6: the shape check reads only the source graphs that can hold an end, not every enrolled one")
    void add_mapping_shape_check_reads_only_the_ends_graphs() {
        // Discovery's accept loop and SDK scripts call addMapping once per candidate; reading every
        // enrolled graph each time cost sources × mappings whole-graph CONSTRUCTs.
        List<UUID> datasets = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            UUID d = UUID.randomUUID();
            datasets.add(d);
            enrolWithGraph(d, SRC + d + "/c" + i, SRC + d + "/c" + i + "/p");
        }
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));
        UUID first = datasets.get(0);
        UUID second = datasets.get(1);

        graphStore.queries().clear();
        service.addMapping(tenant, projectId, MappingKinds.DATATYPE,
                SRC + first + "/c0/p", SRC + second + "/c1/p", "p", actor, null);
        assertThat(graphStore.queries().stream().filter(q -> q.contains("urn:aran:source:")))
                .as("one read per end's dataset")
                .hasSize(2)
                .anyMatch(q -> q.contains("urn:aran:source:" + first))
                .anyMatch(q -> q.contains("urn:aran:source:" + second));

        graphStore.queries().clear();
        service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/integration/Paper", SRC + first + "/c0", "Paper", actor, null);
        assertThat(graphStore.queries().stream().filter(q -> q.contains("urn:aran:source:")))
                .as("an integrated end has no source graph to read")
                .hasSize(1);
    }

    @Test
    @DisplayName("J4-F6: an xsd datatype is never an end of an alignment")
    void add_mapping_refuses_an_xsd_node() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        assertThatThrownBy(() -> service.addMapping(tenant, projectId, MappingKinds.DATATYPE,
                "http://www.w3.org/2001/XMLSchema#string", SRC + "semantic/doi", "doi", actor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XMLSchema#string is a datatype");
    }

    @Test
    @DisplayName("J4-F6: an end no enrolled source graph describes is left to compile — an integrated IRI, or a source not yet bootstrapped")
    void add_mapping_leaves_unknown_ends_to_compile() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));

        ModelMapping saved = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/integration/Paper", "http://ex/B", "Paper", actor, null);
        assertThat(saved.status()).isEqualTo(ModelMapping.ACCEPTED);
    }

    // ── S5-3: the label IS the integrated IRI, so it is a key ────────────────────
    // SchemaIntegrator.mintIri is INTEGRATION_NS + iriFragment(label), and the mint now refuses
    // to land on an IntegratedResource it does not own. Without this pre-check the refusal only
    // arrives at compile time, as a failed run over a model the user has already saved; the
    // duplicate is knowable at the door, so it is a 400 there.

    @Test
    @DisplayName("addMapping refuses a second mapping of the same kind carrying an existing label")
    void add_mapping_refuses_a_duplicate_label_within_a_kind() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));
        service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/A", "http://ex/B", "Customer", actor, null);

        assertThatThrownBy(() -> service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/C", "http://ex/D", "Customer", actor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer");

        assertThat(service.getBundle(tenant, projectId).mappings())
                .as("the duplicate was not persisted")
                .hasSize(1);
    }

    @Test
    @DisplayName("reusing a label to fold a THIRD source into the same concept is allowed — that is I2/I3 reuse, not a mint")
    void add_mapping_allows_a_same_label_mapping_that_shares_an_endpoint() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));
        service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://b/Worker", "Person", actor, null);

        // The incremental path's own shape: a new source joins the concept "Person" already names.
        ModelMapping third = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://c/Staff", "Person", actor, null);

        assertThat(third.label()).isEqualTo("Person");
        assertThat(service.getBundle(tenant, projectId).mappings()).hasSize(2);
    }

    @Test
    @DisplayName("addMapping compares labels after trimming, as mintIri does")
    void add_mapping_duplicate_label_check_trims() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));
        service.addMapping(tenant, projectId, MappingKinds.DATATYPE,
                "http://ex/a1", "http://ex/b1", "name", actor, null);

        assertThatThrownBy(() -> service.addMapping(tenant, projectId, MappingKinds.DATATYPE,
                "http://ex/a2", "http://ex/b2", "  name  ", actor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("the same label under a different kind is allowed — classes and properties mint into disjoint uses")
    void add_mapping_allows_the_same_label_under_another_kind() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));
        service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/A", "http://ex/B", "Name", actor, null);

        ModelMapping second = service.addMapping(tenant, projectId, MappingKinds.DATATYPE,
                "http://ex/a1", "http://ex/b1", "Name", actor, null);

        assertThat(second.label()).isEqualTo("Name");
        assertThat(service.getBundle(tenant, projectId).mappings()).hasSize(2);
    }

    @Test
    @DisplayName("a REJECTED mapping's label is free again — only compiled (ACCEPTED) rows mint an IRI")
    void add_mapping_ignores_rejected_rows_when_checking_labels() {
        IntegrationModelService service = serviceReturning(OperatorResult.success(Map.of()));
        ModelMapping first = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/A", "http://ex/B", "Customer", actor, null);
        service.setMappingStatus(tenant, projectId, first.id(), ModelMapping.REJECTED);

        ModelMapping second = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/C", "http://ex/D", "Customer", actor, null);

        assertThat(second.label()).isEqualTo("Customer");
    }
}

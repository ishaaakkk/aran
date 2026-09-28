package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.fake.FakeGraphStore;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Task 5 (phase 3B): {@link IntegrationModelService#discoverConstraints} drives LIMA denial
 * constraint discovery for one enrolled class through the orchestrator, translates each result
 * from wrapper-output-column namespace (source-property IRIs) into the class's local-name
 * authoring convention, screens and de-duplicates the candidates, and persists survivors as
 * unenforced, DISCOVERED-origin {@link ModelConstraint} rows.
 *
 * <p>Reads the ACTIVE graph ({@code GraphStore.query}), unlike Task 3's compile-tail profiling
 * which reads the just-compiled draft — this is a user-triggered, on-demand action over a class
 * presumably already live, not a step inside compile itself. {@link FakeGraphStore#query} and
 * {@code #readVersion} both simply return whatever was staged, so {@code stage} serves either.
 *
 * <p><b>Every fixture below stages a REAL source/integrated split</b> — a distinct {@code
 * classIri} (source) and {@code INTEGRATED_CLASS_IRI} (integrated), linked by {@code
 * rdfs:subClassOf}, exactly as {@code SchemaIntegrator} produces and {@code
 * MappingCatalogOperator}'s QUERY 1 requires. An earlier version of this fixture used one
 * synthetic class as both, which made the classIri-confusion and property-alignment bugs (fixed
 * after review) invisible to every test here; {@link #resolvesAlignedPropertyToIntegratedLocalName}
 * additionally aligns one property to a differently-named integrated property, so the
 * source/target local-name mixup cannot pass by coincidence. The full cross-module proof —
 * persist under the integrated class AND enforce AND build real DDL referencing the right column
 * — is {@code DiscoveredConstraintRoundTripIT} (aran-application), which this module cannot host
 * ({@code MappingCatalogOperator}/{@code DuckDbExecutor} live in query-engine, which aran-core
 * does not depend on).
 */
class IntegrationModelServiceDiscoverConstraintsTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String INTEGRATED_RESOURCE = NS + "IntegratedResource";
    private static final String SUB_PROPERTY_OF = NS + "subPropertyOf";
    private static final String WRAPPER_SQL =
            "SELECT colA AS \"http://ex/ds1/colA\", colB AS \"http://ex/ds1/colB\" FROM ${SNAPSHOT}";
    /**
     * The exact shape edu.upc.data.schema.Predicate/Scheduler.populatePredicates emits, and the
     * exact candidate an end-to-end run persisted before V43. Every term is a SELF comparison because
     * {@code Schema.buildColumnPairs} only ever builds {@code ColumnPair(col, col)} — so since
     * V43 this is the shape that is refused rather than the shape that persists.
     */
    private static final String LIMA_FD =
            "!(http://ex/ds1/colA EQ http://ex/ds1/colA & http://ex/ds1/colB NE http://ex/ds1/colB)";
    /**
     * The persisted text — <b>display evidence, not a filter</b> (V43).
     * LIMA states its constraint over a PAIR of tuples and a {@code
     * ModelConstraint} is a predicate over ONE row, so the single-row instantiation below compares
     * each column with itself. It is pinned because it is what a reviewer is shown, NOT because it
     * is a correct row filter — {@link #refusesToEnforceADiscoveredCandidate} is the assertion that
     * keeps it out of any {@code WHERE}.
     */
    private static final String TRANSLATED_FD = "NOT (colA = colA AND colB <> colB)";

    /**
     * A candidate comparing two DIFFERENT columns — a genuine single-row predicate.
     *
     * <p>No LIMA build in this repo can emit it today ({@code buildColumnPairs} has no cross-column
     * pair), so it is a <em>hypothetical</em>, and that is exactly its job here: it keeps every
     * test about translation, namespace resolution and dedup exercising the real persist path,
     * and it proves V43's filter is not a blanket "reject everything discovery produces". When
     * tuple-aware storage lands, this is the shape that should already work.
     */
    private static final String LIMA_CROSS = "!(http://ex/ds1/colA EQ http://ex/ds1/colB)";
    private static final String TRANSLATED_CROSS = "NOT (colA = colB)";

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
    private final String classIri = "http://ex/source/ds1/Opaque";
    private final String integratedClassIri = "http://ex/integration/Opaque";

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final FakeGraphStore graphStore = new FakeGraphStore();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);

    /** Every request the fake "constraint-discovery" operator received, in order. */
    private final List<OperatorRequest> discoveryRequests = new CopyOnWriteArrayList<>();

    private Operator capturingDiscoveryOperator(OperatorResult toReturn) {
        return new Operator() {
            @Override public String getOperatorId() { return "constraint-discovery"; }
            @Override public String getDisplayName() { return "capturing"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                discoveryRequests.add(request);
                return toReturn;
            }
        };
    }

    private IntegrationModelService service(OperatorResult discoveryResult) {
        OperatorOrchestrator orchestrator = new OperatorOrchestrator(
                List.of(capturingDiscoveryOperator(discoveryResult)));
        return new IntegrationModelService(repo, graphStore, orchestrator, objectMapper, datasetRepository,
                org.mockito.Mockito.mock(DataProductService.class));
    }

    /** Runs {@code body} with {@link IntegrationModelService}'s WARNs captured. */
    private static List<ILoggingEvent> capturingWarnings(Runnable body) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IntegrationModelService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            body.run();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        return captured.list;
    }

    /**
     * Like {@link #capturingWarnings}, but with the logger's level forced to INFO for the
     * duration. V43's refusal is logged at INFO on purpose — a candidate that states a real
     * dependency the product cannot yet express is not a fault, and WARNing it would train
     * operators to ignore the level that carries genuine problems — and logback filters by level
     * before it ever reaches an appender, so capturing it needs the level set rather than only
     * the appender attached.
     */
    private static List<ILoggingEvent> capturingLogs(Runnable body) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IntegrationModelService.class);
        ch.qos.logback.classic.Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(captured);
        try {
            body.run();
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(previous);
            captured.stop();
        }
        return captured.list;
    }

    private static void assertWarned(List<ILoggingEvent> events, String... fragments) {
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(fragments);
        });
    }

    private void stageActiveClass(String wrapperSql, @Nullable String expansionStatus) {
        stageActiveClass(wrapperSql, expansionStatus, Map.of());
    }

    /**
     * @param alignedProperties source-property IRI → integrated-property IRI, for wrapper columns
     *         that should carry {@code nx:subPropertyOf} to an {@code IntegratedResource} property
     *         (mirrors {@code SchemaIntegrator}'s I1/I4 alignment output). A source property absent
     *         here is left unaligned — QUERY 1's {@code COALESCE(?ip, ?srcProp)} then falls back to
     *         the source property itself as its own target.
     */
    private void stageActiveClass(String wrapperSql, @Nullable String expansionStatus,
                                  Map<String, String> alignedProperties) {
        Model active = ModelFactory.createDefaultModel();
        Resource cls = active.createResource(classIri);
        active.add(cls, active.createProperty(NS + "wrapperSql"), wrapperSql);
        active.add(cls, active.createProperty(NS + "provenance"), datasetId.toString());
        if (expansionStatus != null) {
            active.add(cls, active.createProperty(NS + "wrapperExpansion"), expansionStatus);
        }

        // The integrated class every test's source class is subClassOf, exactly like a real
        // compiled+promoted schema — MappingCatalogOperator's QUERY 1 requires this triple to see
        // the class at all, and IntegrationModelService.discoverConstraints now resolves it the
        // same way to decide what classIri to persist under.
        Resource intCls = active.createResource(integratedClassIri);
        active.add(intCls, RDF.type, active.createResource(INTEGRATED_RESOURCE));
        active.add(intCls, RDF.type, RDFS.Class);
        active.add(cls, RDFS.subClassOf, intCls);

        for (Map.Entry<String, String> alignment : alignedProperties.entrySet()) {
            Resource srcProp = active.createResource(alignment.getKey());
            Resource integratedProp = active.createResource(alignment.getValue());
            active.add(integratedProp, RDF.type, active.createResource(INTEGRATED_RESOURCE));
            active.add(srcProp, active.createProperty(SUB_PROPERTY_OF), integratedProp);
        }

        graphStore.stage(tenant, active);
    }

    private void stageDatasetWithSnapshot() {
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "ds", "", "snap-1", List.of());
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(dataset));
    }

    private static String constraintsJson(String... limaExpressions) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < limaExpressions.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(limaExpressions[i].replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("persists a discovered candidate translated into the local-name authoring convention, unenforced")
    void persistsCandidateWithCorrectNamespaceMappingUnenforcedAndDiscovered() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_CROSS))));

        Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(summary).containsEntry("discovered", "1").containsEntry("persisted", "1")
                .containsEntry("screened", "0");
        assertThat(discoveryRequests).hasSize(1);
        assertThat(discoveryRequests.get(0).operation()).isEqualTo("discover");
        assertThat(discoveryRequests.get(0).parameters())
                .containsEntry("datasetId", datasetId.toString())
                .containsEntry("wrapperSql", WRAPPER_SQL)
                .containsEntry("classIri", classIri);

        List<ModelConstraint> persisted = repo.load(tenant, projectId).constraints();
        assertThat(persisted).singleElement().satisfies(c -> {
            assertThat(c.expression()).as("EQ/NE/& and the IRI columns are all rewritten to local names")
                    .isEqualTo(TRANSLATED_CROSS);
            assertThat(c.classIri())
                    .as("persisted under the INTEGRATED class, never the source classIri parameter"
                            + " — enforcedByClass/the constraints UI key on integrated IRIs only")
                    .isEqualTo(integratedClassIri);
            assertThat(c.enforced()).isFalse();
            assertThat(c.origin()).isEqualTo("DISCOVERED");
        });
    }

    @Test
    @DisplayName("the INTEGRATED class IRI — what the constraints tab's Discover-constraints button"
            + " actually sends — resolves to the same source wrapper and persists correctly (whole-branch"
            + " review Critical: the source-IRI-only filter made the UI's own button a guaranteed no-op)")
    void acceptsTheIntegratedClassIriTheUiActuallySends() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_CROSS))));

        // The UI never has the source classIri to send — DatasetConstraintsTab.tsx passes
        // selCls.iri, populated from MappingCatalog.IntegratedClass.iri (QueryEngineOperator's
        // class listing exposes only that). Calling with THAT namespace is the real production path.
        Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, integratedClassIri);

        assertThat(summary)
                .as("must not be the silent {0,0,0} the pre-fix source-only filter always returned here")
                .containsEntry("discovered", "1").containsEntry("persisted", "1")
                .containsEntry("screened", "0");
        // The orchestrator still receives the source class's own wrapperSql/classIri — the
        // operator's contract (and CompiledWrapperFact) never changes, only how a fact is found.
        assertThat(discoveryRequests).singleElement().satisfies(req ->
                assertThat(req.parameters()).containsEntry("classIri", classIri));

        assertThat(repo.load(tenant, projectId).constraints()).singleElement().satisfies(c -> {
            assertThat(c.classIri())
                    .as("persisted under the integrated class — same IRI the caller passed in, here")
                    .isEqualTo(integratedClassIri);
            assertThat(c.expression()).isEqualTo(TRANSLATED_CROSS);
            assertThat(c.origin()).isEqualTo("DISCOVERED");
        });
    }

    @Test
    @DisplayName("an aligned property's discovered candidate is expressed with the INTEGRATED property's local name")
    void resolvesAlignedPropertyToIntegratedLocalName() {
        // colA is aligned to a DIFFERENTLY-NAMED integrated property; colB is left unaligned.
        // Deliberately different local names so the source-vs-target mixup cannot pass by luck.
        stageActiveClass(WRAPPER_SQL, null,
                Map.of("http://ex/ds1/colA", "http://ex/integration/Opaque/hIndex"));
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_CROSS))));

        service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(repo.load(tenant, projectId).constraints()).singleElement().satisfies(c ->
                assertThat(c.expression())
                        .as("colA's OWN (source) local name must not appear — only the integrated"
                                + " property's ('hIndex') — and the unaligned colB is untouched")
                        .isEqualTo("NOT (hIndex = colB)"));
    }

    @Test
    @DisplayName("a source class with wrapperSql but no subClassOf link to any integrated class is a WARNed no-op")
    void warnsAndSkipsWhenSourceClassIsNotIntegrated() {
        // wrapperSql present, but stage the class directly (no rdfs:subClassOf / IntegratedResource
        // at all) rather than through stageActiveClass, which always wires that link in.
        Model active = ModelFactory.createDefaultModel();
        Resource cls = active.createResource(classIri);
        active.add(cls, active.createProperty(NS + "wrapperSql"), WRAPPER_SQL);
        active.add(cls, active.createProperty(NS + "provenance"), datasetId.toString());
        graphStore.stage(tenant, active);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_FD))));

        List<ILoggingEvent> events = capturingWarnings(() -> {
            Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);
            assertThat(summary).containsEntry("discovered", "0").containsEntry("persisted", "0");
        });

        assertThat(discoveryRequests).as("never reaches the orchestrator").isEmpty();
        assertThat(repo.load(tenant, projectId).constraints()).isEmpty();
        assertWarned(events, classIri, "not subClassOf any integrated class");
    }

    @Test
    @DisplayName("dedupes a candidate against an existing expression already recorded for that class")
    void dedupesAgainstExistingExpressionForTheClass() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        var model = repo.getOrCreate(tenant, projectId);
        repo.addConstraint(tenant, new ModelConstraint(UUID.randomUUID(),
                UUID.fromString(tenant.value()), model.id(), integratedClassIri, TRANSLATED_CROSS,
                false, Instant.now(), "MANUAL"));

        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_CROSS))));
        Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(summary).containsEntry("discovered", "1").containsEntry("persisted", "0");
        assertThat(repo.load(tenant, projectId).constraints())
                .as("no second row for the same expression").hasSize(1);
    }

    @Test
    @DisplayName("a candidate with a forbidden construct is dropped and WARNed, not persisted")
    void dropsForbiddenConstructCandidateWithWarn() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        String malicious = "!(read_csv_auto('/etc/passwd') EQ 1)";
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(malicious))));

        List<ILoggingEvent> events = capturingWarnings(() -> {
            Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);
            assertThat(summary).containsEntry("screened", "1").containsEntry("persisted", "0");
        });

        assertThat(repo.load(tenant, projectId).constraints()).isEmpty();
        assertWarned(events, "Dropped a discovered constraint candidate", "forbidden");
    }

    @Test
    @DisplayName("a candidate mentioning a column the wrapper never declared is dropped as an incomplete translation")
    void dropsIncompleteTranslationWithWarn() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        String unknownColumn = "!(http://ex/other/unknownCol EQ http://ex/other/unknownCol)";
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(unknownColumn))));

        List<ILoggingEvent> events = capturingWarnings(() -> {
            Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);
            assertThat(summary).containsEntry("screened", "1").containsEntry("persisted", "0");
        });

        assertThat(repo.load(tenant, projectId).constraints()).isEmpty();
        assertWarned(events, "Dropped a discovered constraint candidate", "could not translate");
    }

    @Test
    @DisplayName("an EXPANDED class is skipped — the operator is never called")
    void skipsExpandedClass() {
        stageActiveClass(WRAPPER_SQL, "EXPANDED");
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_FD))));

        Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(summary).containsEntry("discovered", "0").containsEntry("persisted", "0");
        assertThat(discoveryRequests).isEmpty();
    }

    @Test
    @DisplayName("no wrapperSql for the requested (dataset, class) in the active graph is a WARNed no-op")
    void warnsAndSkipsWhenNoWrapperSqlFound() {
        // Nothing staged for this (datasetId, classIri) — the active graph is empty.
        IntegrationModelService service = service(OperatorResult.success(Map.of()));

        List<ILoggingEvent> events = capturingWarnings(() -> {
            Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);
            assertThat(summary).containsEntry("discovered", "0").containsEntry("persisted", "0");
        });

        assertThat(discoveryRequests).isEmpty();
        assertWarned(events, classIri, "no wrapperSql");
    }

    @Test
    @DisplayName("a dataset with no recorded snapshot is skipped rather than sent to the operator")
    void warnsAndSkipsWhenDatasetHasNoSnapshot() {
        stageActiveClass(WRAPPER_SQL, null);
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "ds", "", null, List.of());
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(dataset));
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_FD))));

        List<ILoggingEvent> events = capturingWarnings(() -> {
            Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);
            assertThat(summary).containsEntry("persisted", "0");
        });

        assertThat(discoveryRequests).isEmpty();
        assertWarned(events, classIri, "no recorded snapshot");
    }

    @Test
    @DisplayName("a forbidden wrapperSql already sitting in the active graph is screened before the orchestrator is called")
    void screensForbiddenWrapperSqlBeforeCallingOperator() {
        stageActiveClass("SELECT * FROM read_csv_auto('/etc/passwd')", null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_FD))));

        List<ILoggingEvent> events = capturingWarnings(
                () -> service.discoverConstraints(tenant, projectId, datasetId, classIri));

        assertThat(discoveryRequests).isEmpty();
        assertWarned(events, classIri, "forbidden");
    }

    @Test
    @DisplayName("the discover request caps the rows LIMA samples — an uncapped pull is an OOM risk"
            + " on a large wrapper output (audit CONSTRAINT_DISCOVERY-3)")
    void capsTheRowsSentToTheSampler() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_FD))));

        service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(discoveryRequests).singleElement().satisfies(req ->
                assertThat(req.parameters())
                        .as("the operator's own `limit` parameter, not a name it ignores")
                        .containsEntry("limit", "10000"));
    }

    // ── V43 / CONSTRAINT_DISCOVERY-1: a DISCOVERED candidate is evidence, never a filter ────

    @Test
    @DisplayName("enforcing a DISCOVERED candidate is refused — its single-row translation is not the"
            + " inter-tuple constraint LIMA stated (V43)")
    void refusesToEnforceADiscoveredCandidate() {
        // Seeded directly rather than run through discovery. V43's two screens COMPOSE, and the
        // persist screen (rejectSelfComparisonCandidate) fires first, so discovery can no longer
        // produce a DISCOVERED row for this one to refuse — an earlier version of this test drove
        // it through discoverConstraints and then read constraints().get(0). That is not a
        // weakening: the enforce gate is class-wide and holds for EVERY DISCOVERED row, including
        // the cross-column shapes the persist screen deliberately lets through, which is exactly
        // what a directly-seeded row stands in for. See the two tests below, seeded the same way.
        var model = repo.getOrCreate(tenant, projectId);
        UUID candidateId = UUID.randomUUID();
        repo.addConstraint(tenant, new ModelConstraint(candidateId, UUID.fromString(tenant.value()),
                model.id(), integratedClassIri, TRANSLATED_FD, false, Instant.now(), "DISCOVERED"));
        IntegrationModelService service = service(OperatorResult.success(Map.of()));

        assertThatThrownBy(() ->
                service.setConstraintEnforced(tenant, projectId, candidateId, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence only")
                .hasMessageContaining("Inter-tuple enforcement is not implemented");

        assertThat(repo.load(tenant, projectId).constraints()).singleElement().satisfies(c -> {
            assertThat(c.enforced()).as("the refusal is not a partial write").isFalse();
            assertThat(c.expression()).as("still listed as evidence").isEqualTo(TRANSLATED_FD);
        });
    }

    @Test
    @DisplayName("a DISCOVERED constraint that is somehow already enforced can still be DISABLED —"
            + " the block is one-directional, so an existing row is never stuck on")
    void allowsDisablingADiscoveredConstraint() {
        var model = repo.getOrCreate(tenant, projectId);
        UUID id = UUID.randomUUID();
        repo.addConstraint(tenant, new ModelConstraint(id, UUID.fromString(tenant.value()),
                model.id(), integratedClassIri, TRANSLATED_FD, true, Instant.now(), "DISCOVERED"));
        IntegrationModelService service = service(OperatorResult.success(Map.of()));

        service.setConstraintEnforced(tenant, projectId, id, false);

        assertThat(repo.load(tenant, projectId).constraints()).singleElement()
                .satisfies(c -> assertThat(c.enforced()).isFalse());
    }

    @Test
    @DisplayName("a MANUAL constraint still enforces — the block is scoped to DISCOVERED origin only")
    void stillEnforcesAManualConstraint() {
        var model = repo.getOrCreate(tenant, projectId);
        UUID id = UUID.randomUUID();
        repo.addConstraint(tenant, new ModelConstraint(id, UUID.fromString(tenant.value()),
                model.id(), integratedClassIri, "h_index >= 0", false, Instant.now(), "MANUAL"));
        IntegrationModelService service = service(OperatorResult.success(Map.of()));

        service.setConstraintEnforced(tenant, projectId, id, true);

        assertThat(repo.load(tenant, projectId).constraints()).singleElement()
                .satisfies(c -> assertThat(c.enforced()).isTrue());
    }

    @Test
    @DisplayName("an operator failure is logged and swallowed into a zero-candidate summary — but the"
            + " summary says it DEGRADED, so an outage is not reported as a clean zero")
    void warnsAndSkipsWhenOperatorFails() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.failure("DISCOVERY_FAILED", "LIMA blew up"));

        List<ILoggingEvent> events = capturingWarnings(() -> {
            Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);
            assertThat(summary).containsEntry("persisted", "0")
                    .containsEntry("degraded", "true");
            assertThat(summary.get("reason")).contains("LIMA blew up");
        });

        assertThat(repo.load(tenant, projectId).constraints()).isEmpty();
        assertWarned(events, classIri, "LIMA blew up");
    }

    @Test
    @DisplayName("the operator's own degraded verdict reaches the job result — a lake outage must not"
            + " read as 'discovery complete, nothing found' (audit CONSTRAINT_DISCOVERY-12)")
    void carriesTheOperatorsDegradedVerdictIntoTheSummary() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(OperatorResult.success(Map.of(
                "constraints", "[]", "subject", "WRAPPER_OUTPUT",
                "degraded", "true", "reason", "lake unreachable")));

        Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(summary).containsEntry("discovered", "0").containsEntry("persisted", "0")
                .containsEntry("degraded", "true");
        assertThat(summary.get("reason")).contains("lake unreachable");
        assertThat(summary.get("summary"))
                .as("a degraded run outranks every count — the sentence must not read as an answer")
                .contains("could not complete").contains("lake unreachable");
    }

    @Test
    @DisplayName("a genuinely constraint-free class is a CLEAN zero — no degraded flag, or the flag"
            + " would mean nothing")
    void doesNotMarkACleanZeroAsDegraded() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", "[]", "subject", "WRAPPER_OUTPUT")));

        Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(summary).containsEntry("discovered", "0").doesNotContainKey("degraded");
    }

    // ── V43: a cross-tuple statement is not a row constraint ──────────────────────────────

    /** The converse candidate, also persisted before V43: the same self-paired shape. */
    private static final String LIMA_FD_CONVERSE =
            "!(http://ex/ds1/colA NE http://ex/ds1/colA & http://ex/ds1/colB EQ http://ex/ds1/colB)";

    @Test
    @DisplayName("V43: a self-compared tautology candidate is refused, and the run says why")
    void selfComparisonCandidateIsNeverPersisted() {
        // The exact pair the V34 e2e run persisted on curated_papers.csv, in this fixture's
        // namespace. Before V43 both landed in the review list with an enforce checkbox next to
        // them; enforcing one changed nothing (and, on a NULL row, silently quarantined it).
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(OperatorResult.success(
                Map.of("constraints", constraintsJson(LIMA_FD, LIMA_FD_CONVERSE))));

        Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(repo.load(tenant, projectId).constraints())
                .as("a predicate that cannot filter a row must never reach the review list")
                .isEmpty();
        assertThat(summary)
                .containsEntry("discovered", "2")
                .containsEntry("persisted", "0")
                .containsEntry("pairOnly", "2")
                .as("not counted as `screened` — nothing was wrong with the candidate, it is the"
                        + " tuple-pair/single-row mismatch")
                .containsEntry("screened", "0");
    }

    @Test
    @DisplayName("V43: a run that persists nothing says pair-based dependencies were found — never an empty success")
    void honestEmptinessIsReportedRatherThanASilentZero() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_FD))));

        String message = service.discoverConstraints(tenant, projectId, datasetId, classIri).get("summary");

        assertThat(message)
                .as("the user must learn that something WAS found and why it cannot be used")
                .contains("PAIRS of rows")
                .contains("single row")
                .contains("nothing was added");
        assertThat(message).doesNotContain("review candidates");
    }

    @Test
    @DisplayName("V43: a run with genuinely nothing to report is not dressed up as a pair-only outcome")
    void nothingFoundIsNotReportedAsPairOnly() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", "[]")));

        Map<String, String> summary = service.discoverConstraints(tenant, projectId, datasetId, classIri);

        assertThat(summary).containsEntry("pairOnly", "0").containsEntry("discovered", "0");
        assertThat(summary.get("summary")).isEqualTo("Discovery found no candidates for this class.");
    }

    @Test
    @DisplayName("V43: the filter is not total — it rejects only what it can prove constant on one row")
    void theFilterRejectsOnlyProvablyTrivialCandidates() {
        // Directly on the predicate, because this is the property that keeps V43 a filter rather
        // than a switch that turns discovery off: everything LIMA emits TODAY is self-paired, but
        // the moment cross-column pairs exist those candidates must sail straight through.
        assertThat(IntegrationModelService.rejectSelfComparisonCandidate(
                "NOT (colA = colA AND colB <> colB)"))
                .as("every term self-paired — constant on one row").isNotNull();
        assertThat(IntegrationModelService.rejectSelfComparisonCandidate("NOT (paper_id = paper_id)"))
                .as("the single-term contradiction shape — would quarantine the whole class")
                .isNotNull();

        assertThat(IntegrationModelService.rejectSelfComparisonCandidate("NOT (colA = colB)"))
                .as("two different columns — a real row predicate").isNull();
        assertThat(IntegrationModelService.rejectSelfComparisonCandidate(
                "NOT (colA = colA AND colB <> colC)"))
                .as("ONE non-trivial term is enough to make the conjunction meaningful").isNull();
        assertThat(IntegrationModelService.rejectSelfComparisonCandidate("h_index > 0"))
                .as("a hand-authored predicate must never be caught by this").isNull();
        assertThat(IntegrationModelService.rejectSelfComparisonCandidate("colA IS NOT NULL"))
                .as("a shape this cannot read is kept, not rejected — the screen never guesses")
                .isNull();
    }

    @Test
    @DisplayName("V43: the refusal reason is recorded against the class, not swallowed")
    void theRefusalReasonIsLogged() {
        stageActiveClass(WRAPPER_SQL, null);
        stageDatasetWithSnapshot();
        IntegrationModelService service = service(
                OperatorResult.success(Map.of("constraints", constraintsJson(LIMA_FD))));

        List<ILoggingEvent> events = capturingLogs(() ->
                service.discoverConstraints(tenant, projectId, datasetId, classIri));

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getFormattedMessage()).contains(classIri);
            assertThat(e.getFormattedMessage()).contains("not expressible as a row constraint");
            assertThat(e.getFormattedMessage()).contains(TRANSLATED_FD);
        });
    }
}

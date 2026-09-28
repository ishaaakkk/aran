package edu.upc.dtak.aran.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalogOperator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mandatory round trip a code review demanded after two Criticals in the first cut of Task 5
 * (phase 3B): every prior fixture used one synthetic class as both the source and the "integrated"
 * class, which hid (1) discovered constraints persisting under the SOURCE class IRI — a namespace
 * {@code enforcedByClass}/the constraints UI never key on, so every discovered row was silently
 * unenforceable and invisible — and (2) an ALIGNED property's discovered candidate being expressed
 * with the source property's own (pre-alignment) local name instead of the integrated property's,
 * which either breaks the enforced view's DDL or silently collides with an unrelated column.
 *
 * <p>This fixture stages a REAL split: a source class {@code subClassOf} a distinct integrated
 * class, with one property ALIGNED to a differently-named integrated property and one left
 * unaligned — exactly the shape {@code SchemaIntegrator}'s I1/I4 rules and {@code
 * MappingCatalogOperator}'s QUERY 1 produce/require. It drives the real {@link
 * IntegrationModelService#discoverConstraints}, the real enforce path
 * ({@link IntegrationModelService#setConstraintEnforced} — which now REFUSES a {@code DISCOVERED}
 * row (V43), so the DDL half runs through a MANUAL row carrying the same expression), the
 * real {@link MappingCatalogOperator}
 * catalog build, and the real {@link DuckDbExecutor#buildDdl} — over the SAME staged graph — so a
 * regression of either Critical shows up as a assertion failure here, not just in an isolated fake.
 *
 * <p>Lives in {@code aran-application} (not {@code aran-core}, where the rest of Task 5's tests
 * live) because it needs {@code MappingCatalogOperator}/{@code DuckDbExecutor}
 * (aran-core cannot depend on query-engine) alongside real Postgres-backed
 * {@code IntegrationModelRepository}/{@code DatasetRepository} — the one module that sees both.
 * {@code IntegrationModelService} and {@code MappingCatalogOperator} are constructed directly
 * (not autowired): both are plain constructor-injectable classes, and building them by hand keeps
 * the discovery operator a canned, in-test fake rather than needing a real lake/LIMA round trip.
 */
@DisplayName("Discovered constraint round trip: integrated class + integrated-property local names (Task 5 fix)")
class DiscoveredConstraintRoundTripIT extends BaseIntegrationTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private static final String SOURCE_CLASS = "http://ex/source/ds1/Paper";
    private static final String INTEGRATED_CLASS = "http://ex/integration/Paper";
    /** Aligned: a differently-named integrated property, so a source/target mixup cannot pass by luck. */
    private static final String SRC_PROP_A = "http://ex/source/ds1/Paper/colA";
    private static final String INTEGRATED_PROP_A = "http://ex/integration/Paper/hIndex";
    /** Left unaligned: COALESCE falls back to the source property itself as its own target. */
    private static final String SRC_PROP_B = "http://ex/source/ds1/Paper/colB";

    private static final String WRAPPER_SQL = "SELECT colA AS \"" + SRC_PROP_A
            + "\", colB AS \"" + SRC_PROP_B + "\" FROM ${SNAPSHOT}";
    /**
     * A candidate comparing the class's two DIFFERENT properties.
     *
     * <p>This fixture is about NAMESPACES — that a discovered row lands under the integrated
     * class and names the integrated property — so it needs a candidate that actually reaches
     * persistence. The self-paired shape LIMA emits today
     * ({@code !(A EQ A & B NE B)}, {@code Schema.buildColumnPairs} builds only self pairs) is
     * refused before persistence since **V43**, because it is constant on a single row and cannot
     * filter anything. Using it here would test the refusal, not the round trip, and the refusal
     * has its own tests in {@code IntegrationModelServiceDiscoverConstraintsTest}.
     *
     * <p>So this is a hypothetical: no LIMA build in this repo can emit it yet. That is precisely
     * why it is the right fixture — it exercises the persist/enforce/DDL path that must keep
     * working for the day cross-column pairs land, and it keeps this IT testing the two Criticals
     * it was written for rather than V43's screen.
     */
    private static final String LIMA_FD = "!(" + SRC_PROP_A + " EQ " + SRC_PROP_B + ")";
    private static final String EXPECTED_EXPRESSION = "NOT (hIndex = colB)";

    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired IntegrationModelRepository modelRepository;
    @Autowired InMemoryGraphStore graphStore;

    private TenantContext ctx;
    private UUID userId;
    private UUID projectId;
    private UUID datasetId;

    @BeforeEach
    @Transactional
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));

        UUID tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "tenant-" + suffix, "Tenant", Instant.now()));
        ctx = new TenantContext(tenantId.toString());

        Project project = projects.save(ctx, new Project(
                UUID.randomUUID(), tenantId, "Project-" + suffix, "", userId, Instant.now()));
        projectId = project.id();

        DataRepository repo = repositories.save(ctx, new FileSystemRepository(
                UUID.randomUUID(), projectId, tenantId, "repo-" + suffix, ""));

        datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repo.id(), "ds", "", "snap-1", List.of()));
    }

    /** The active schema graph: source class subClassOf integrated class, one aligned property. */
    private Model activeGraph() {
        Model m = ModelFactory.createDefaultModel();

        Resource srcClass = m.createResource(SOURCE_CLASS);
        m.add(srcClass, m.createProperty(NS + "wrapperSql"), WRAPPER_SQL);
        m.add(srcClass, m.createProperty(NS + "provenance"), datasetId.toString());
        // No nx:wrapperExpansion — QUERY 1 (and discoverConstraints) default that to OPAQUE.

        Resource intClass = m.createResource(INTEGRATED_CLASS);
        m.add(intClass, RDF.type, m.createResource(NS + "IntegratedResource"));
        m.add(intClass, RDF.type, RDFS.Class);
        m.add(srcClass, RDFS.subClassOf, intClass);

        Resource srcPropA = m.createResource(SRC_PROP_A);
        Resource integratedPropA = m.createResource(INTEGRATED_PROP_A);
        m.add(integratedPropA, RDF.type, m.createResource(NS + "IntegratedResource"));
        m.add(srcPropA, RDFS.domain, srcClass);
        m.add(srcPropA, RDFS.range, m.createResource(XSD_INTEGER));
        m.add(srcPropA, m.createProperty(NS + "subPropertyOf"), integratedPropA);

        Resource srcPropB = m.createResource(SRC_PROP_B);
        m.add(srcPropB, RDFS.domain, srcClass);
        m.add(srcPropB, RDFS.range, m.createResource(XSD_STRING));
        // srcPropB carries no subPropertyOf link — COALESCE resolves its target to itself.

        return m;
    }

    private void promoteActiveGraph() {
        AuditedActor actor = new AuditedActor(userId);
        GraphVersion draft = graphStore.replaceDraft(ctx, activeGraph(), actor);
        graphStore.promote(ctx, draft.id(), actor);
    }

    /** A fake "constraint-discovery" operator returning one canned LIMA result — no real lake/LIMA. */
    private static OperatorOrchestrator discoveryOrchestratorReturning(String limaExpression) {
        Operator fakeDiscovery = new Operator() {
            @Override public String getOperatorId() { return "constraint-discovery"; }
            @Override public String getDisplayName() { return "fake"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                String json = "[\"" + limaExpression.replace("\"", "\\\"") + "\"]";
                return OperatorResult.success(Map.of("constraints", json));
            }
        };
        return new OperatorOrchestrator(List.of(fakeDiscovery));
    }

    /**
     * Run twice, over two otherwise-identical fixtures ({@code @BeforeEach} gives each {@code
     * @Test} its own fresh tenant/project/dataset): once passing the SOURCE class IRI ({@code
     * setWrapper}/{@code verifyWrapper}'s convention, and every earlier test's namespace), once
     * passing the INTEGRATED class IRI — what {@code DatasetConstraintsTab.tsx}'s "Discover
     * constraints" button actually sends, since the catalog payload it reads from
     * ({@code QueryEngineOperator}'s class listing) exposes only {@code MappingCatalog
     * .IntegratedClass.iri}, never a source class IRI. The service once matched only the
     * source namespace, which made the UI's own button a {@code 0/0/0} no-op on every real call. Both must reach the identical
     * outcome: same persisted classIri, same expression, same enforced DDL.
     */
    @Test
    @Transactional
    @DisplayName("discover via the SOURCE class IRI (setWrapper/verifyWrapper's own convention) persists"
            + " under the integrated class with integrated-property local names; the enforce PATCH"
            + " refuses it (V43); the real DDL's WHERE clause names the right columns")
    void discoverPersistEnforceAndBuildRealDdl_viaSourceClassIri() {
        discoverPersistEnforceAndBuildRealDdl(SOURCE_CLASS);
    }

    @Test
    @Transactional
    @DisplayName("discover via the INTEGRATED class IRI — what the constraints tab's Discover-constraints"
            + " button actually sends (once a no-op), reaches the identical outcome as the source-IRI path")
    void discoverPersistEnforceAndBuildRealDdl_viaIntegratedClassIri() {
        discoverPersistEnforceAndBuildRealDdl(INTEGRATED_CLASS);
    }

    private void discoverPersistEnforceAndBuildRealDdl(String discoverClassIriArgument) {
        promoteActiveGraph();

        IntegrationModelService modelService = new IntegrationModelService(
                modelRepository, graphStore, discoveryOrchestratorReturning(LIMA_FD),
                new ObjectMapper(), datasets,
                org.mockito.Mockito.mock(edu.upc.dtak.aran.core.service.DataProductService.class));

        // ── discover + persist ──────────────────────────────────────────────
        Map<String, String> summary =
                modelService.discoverConstraints(ctx, projectId, datasetId, discoverClassIriArgument);
        assertThat(summary)
                .as("must not be the silent {0,0,0} the pre-fix source-only filter always returned"
                        + " for the integrated-IRI caller")
                .containsEntry("discovered", "1").containsEntry("persisted", "1");

        List<ModelConstraint> afterDiscover = modelRepository.load(ctx, projectId).constraints();
        assertThat(afterDiscover).singleElement().satisfies(c -> {
            assertThat(c.classIri())
                    .as("Critical 1: persisted under the INTEGRATED class, not the source classIri argument")
                    .isEqualTo(INTEGRATED_CLASS);
            assertThat(c.expression())
                    .as("Critical 2: the aligned property (colA) is named by the INTEGRATED"
                            + " property's local name (hIndex); the unaligned one (colB) is untouched")
                    .isEqualTo(EXPECTED_EXPRESSION);
            assertThat(c.enforced()).isFalse();
            assertThat(c.origin()).isEqualTo("DISCOVERED");
        });
        UUID constraintId = afterDiscover.get(0).id();

        // ── the enforce PATCH REFUSES a discovered row (V43) ───────────────────────────────────
        // It used to accept one, which is what made the whole loop hollow: the persisted text is
        // an inter-tuple constraint collapsed to a single row, so enforcing it filters nothing (or
        // everything) and drops NULL-bearing rows from both the int_ and q_ views.
        assertThatThrownBy(() -> modelService.setConstraintEnforced(ctx, projectId, constraintId, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence only");
        assertThat(modelRepository.load(ctx, projectId).constraints())
                .singleElement()
                .satisfies(c -> assertThat(c.enforced())
                        .as("the refusal leaves the candidate exactly as discovery persisted it")
                        .isFalse());

        // The DDL half of this round trip is about ConstraintRewriter inverting the INTEGRATED
        // property's local name back to its quoted IRI column — a claim about the expression's
        // vocabulary, not about its origin. So it is now proved through a MANUAL constraint
        // carrying the SAME expression discovery produced, which is exactly the path a reviewer
        // who agrees with a candidate has to take.
        UUID manualId = UUID.randomUUID();
        modelRepository.addConstraint(ctx, new ModelConstraint(
                manualId, UUID.fromString(ctx.value()), modelRepository.getOrCreate(ctx, projectId).id(),
                INTEGRATED_CLASS, EXPECTED_EXPRESSION, false, Instant.now(), "MANUAL"));
        modelService.setConstraintEnforced(ctx, projectId, manualId, true);
        assertThat(modelRepository.findEnforcedConstraints(ctx))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.origin()).as("only the MANUAL row ever enforces").isEqualTo("MANUAL");
                    assertThat(c.expression()).isEqualTo(EXPECTED_EXPRESSION);
                });

        // ── build the REAL catalog + REAL DDL over the SAME active graph ────────────────────────
        MappingCatalogOperator catalogOperator = new MappingCatalogOperator(graphStore, null);
        MappingCatalog catalog = catalogOperator.getCatalog(ctx);

        assertThat(catalog.classes())
                .as("the catalog is keyed by the INTEGRATED class IRI, confirming the namespace"
                        + " discoverConstraints must persist under")
                .containsKey(INTEGRATED_CLASS);
        MappingCatalog.SourceView sourceView = catalog.classes().get(INTEGRATED_CLASS).sources().get(0);
        assertThat(sourceView.columnMap())
                .as("columnMap keys on the INTEGRATED property for the aligned column"
                        + " (COALESCE(?ip, ?srcProp)), and on the source property itself when unaligned")
                .containsEntry(INTEGRATED_PROP_A, SRC_PROP_A)
                .containsEntry(SRC_PROP_B, SRC_PROP_B);

        Map<String, List<String>> enforcedByClass = Map.of(INTEGRATED_CLASS,
                modelRepository.findEnforcedConstraints(ctx).stream().map(ModelConstraint::expression).toList());
        List<String> ddl = DuckDbExecutor.buildDdl(
                catalog, dsId -> "read_csv_auto('/dev/null')", enforcedByClass);

        String intViewName = "int_" + Names.sqlName(INTEGRATED_CLASS);
        String intViewDdl = ddl.stream()
                .filter(stmt -> stmt.startsWith("CREATE OR REPLACE VIEW " + intViewName + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CREATE VIEW statement for " + intViewName
                        + " in generated DDL: " + ddl));

        assertThat(intViewDdl)
                .as("the enforced view's WHERE clause references the INTEGRATED property's quoted"
                        + " IRI column for the aligned term, and the unaligned property's own for"
                        + " the other — proving ConstraintRewriter round-tripped both of the"
                        + " persisted expression's local names correctly, each through its own rule")
                .contains("\"" + INTEGRATED_PROP_A + "\" = \"" + SRC_PROP_B + "\"")
                .as("the pre-fix bug's local name ('colA', the SOURCE property's own) must never"
                        + " appear bare and unrewritten in the view body")
                .doesNotContain(" colA ")
                .doesNotContain("(colA ");
    }
}

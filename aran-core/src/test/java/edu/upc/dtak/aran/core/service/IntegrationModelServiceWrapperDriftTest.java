package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.fake.FakeGraphStore;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V47 — a source-schema change must not leave the enrolled wrapper stale.
 *
 * <p>The defect, end to end: {@code addSource} seeds {@code model_source.wrapperSql} from the
 * class's {@code nx:wrapperSql} once, at enrolment; a refresh regenerates that graph value for the
 * new columns; and the next compile pushes the enrolment-time copy straight back over it
 * ({@code SchemaIntegrationOperator.applyWrapperOverrides}). Every query over the class then died
 * on {@code Binder Error: Referenced column "city" not found in FROM clause!}.
 *
 * <p>The fix splits on ONE fact the loop never carried — did a person write this wrapper?
 * Unedited: re-seed it, it was only ever a copy. Edited: never touch it, but refuse to compile it
 * against a schema it can no longer read, and say exactly which column went away.
 *
 * <p>The scenario every test here shares is V47's own: {@code venue} with
 * {@code (venue_id, name, city, capacity)} is enrolled and promoted, then the source is altered to
 * {@code (venue_id, name, city_name, country, capacity)} and re-bootstrapped.
 */
class IntegrationModelServiceWrapperDriftTest {

    private static final String NX = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String WRAPPER_SQL_IRI = NX + "wrapperSql";
    private static final String SOURCE_REF_IRI = NX + "sourceRef";
    private static final String VENUE = "http://ex/source/ds1/venue";

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final FakeGraphStore graphStore = new FakeGraphStore();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);

    private final IntegrationModel model = repo.getOrCreate(tenant, projectId);

    /** The wrapper bootstrap generates for a flat source: one quoted column, aliased to its IRI. */
    private static String wrapperFor(String... columns) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) sql.append(", ");
            sql.append('"').append(columns[i]).append("\" AS \"http://ex/source/ds1/venue/")
                    .append(columns[i]).append('"');
        }
        return sql.append(" FROM ${SNAPSHOT}").toString();
    }

    /** A bootstrapped source graph for {@link #VENUE} carrying those physical columns. */
    private static Model graphWith(String... columns) {
        Model g = ModelFactory.createDefaultModel();
        Resource cls = g.createResource(VENUE);
        g.add(cls, RDF.type, RDFS.Class);
        g.add(cls, g.createProperty(WRAPPER_SQL_IRI), wrapperFor(columns));
        Property sourceRef = g.createProperty(SOURCE_REF_IRI);
        for (String col : columns) {
            Resource prop = g.createResource(VENUE + "/" + col);
            g.add(prop, RDFS.domain, cls);
            g.add(prop, sourceRef, col);
        }
        return g;
    }

    private IntegrationModelService service() {
        Operator compiler = new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "Schema Integration (stub)"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                compiled = true;
                return OperatorResult.success(Map.of("draftVersionId", UUID.randomUUID().toString()));
            }
        };
        return new IntegrationModelService(repo, graphStore,
                new OperatorOrchestrator(List.of(compiler)), objectMapper, datasetRepository,
                org.mockito.Mockito.mock(DataProductService.class));
    }

    private boolean compiled;

    private void enrol(String wrapperSql, boolean edited) {
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, VENUE, wrapperSql, null, null, edited));
    }

    private ModelSource storedSource() {
        return repo.load(tenant, projectId).sources().stream()
                .filter(s -> s.classIri().equals(VENUE)).findFirst().orElseThrow();
    }

    /** The promoted schema (pre-drift) and the freshly bootstrapped source graph (post-drift). */
    private void driftTheSource() {
        graphStore.stageGraph(tenant, "urn:aran:schema",
                graphWith("venue_id", "name", "city", "capacity"));
        graphStore.stageGraph(tenant, "urn:aran:source:" + datasetId,
                graphWith("venue_id", "name", "city_name", "country", "capacity"));
    }

    // ── re-seeding an UNEDITED wrapper ────────────────────────────────────────────────────

    @Test
    @DisplayName("a refresh re-seeds an unedited wrapper, so the class follows its source's new columns")
    void unedited_wrapper_follows_the_source() {
        enrol(wrapperFor("venue_id", "name", "city", "capacity"), false);
        driftTheSource();

        assertThat(service().reseedUneditedWrappers(tenant, datasetId)).isEqualTo(1);

        ModelSource after = storedSource();
        assertThat(after.wrapperSql())
                .as("the stale seed is gone — this is what stopped the class answering")
                .doesNotContain("\"city\"")
                .contains("\"city_name\"")
                .contains("\"country\"");
        assertThat(after.wrapperEdited()).as("re-seeding does not make it a user edit").isFalse();
    }

    @Test
    @DisplayName("an EDITED wrapper is never re-seeded — a person's SQL is not silently replaced")
    void edited_wrapper_is_never_reseeded() {
        String handWritten = "SELECT \"city\" AS \"http://ex/source/ds1/venue/city\" FROM ${SNAPSHOT}";
        enrol(handWritten, true);
        driftTheSource();

        assertThat(service().reseedUneditedWrappers(tenant, datasetId))
                .as("the edited row is not counted, because it is not touched").isZero();
        assertThat(storedSource().wrapperSql()).isEqualTo(handWritten);
    }

    @Test
    @DisplayName("saving a wrapper through setWrapper is what marks it edited")
    void setWrapper_marks_the_row_edited() {
        enrol(wrapperFor("venue_id", "name", "city", "capacity"), false);
        Operator queryEngine = new Operator() {
            @Override public String getOperatorId() { return "query-engine"; }
            @Override public String getDisplayName() { return "Query Engine (stub)"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                return OperatorResult.success(Map.of("status", "OPAQUE", "reason", "stub"));
            }
        };
        IntegrationModelService service = new IntegrationModelService(repo, graphStore,
                new OperatorOrchestrator(List.of(queryEngine)), objectMapper, datasetRepository,
                org.mockito.Mockito.mock(DataProductService.class));

        assertThat(storedSource().wrapperEdited()).as("an enrolment seed is nobody's edit").isFalse();
        service.setWrapper(tenant, projectId, datasetId, VENUE,
                "SELECT \"name\" AS \"http://ex/source/ds1/venue/name\" FROM ${SNAPSHOT}");

        assertThat(storedSource().wrapperEdited()).isTrue();

        // ...and being edited is what now protects it from the re-seed.
        driftTheSource();
        assertThat(service.reseedUneditedWrappers(tenant, datasetId)).isZero();
    }

    @Test
    @DisplayName("re-seeding leaves a source class the new bootstrap says nothing about alone")
    void reseed_ignores_classes_the_new_graph_does_not_mention() {
        String other = "SELECT 1 FROM ${SNAPSHOT}";
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, "http://ex/source/ds1/other", other, null, null, false));
        enrol(wrapperFor("venue_id", "name", "city", "capacity"), false);
        driftTheSource();

        assertThat(service().reseedUneditedWrappers(tenant, datasetId)).isEqualTo(1);
        assertThat(repo.load(tenant, projectId).sources().stream()
                .filter(s -> s.classIri().endsWith("/other")).findFirst().orElseThrow().wrapperSql())
                .as("silence about a class is not an instruction to blank its wrapper")
                .isEqualTo(other);
    }

    // ── refusing to compile a stale EDITED wrapper ────────────────────────────────────────

    @Test
    @DisplayName("compiling an edited wrapper that names a dropped column fails with the dataset, class and column")
    void compile_refuses_a_stale_edited_wrapper() {
        enrol("SELECT \"city\" AS \"http://ex/source/ds1/venue/city\" FROM ${SNAPSHOT}", true);
        driftTheSource();

        assertThatThrownBy(() -> service().rebuildToDraft(tenant, projectId, null))
                .isInstanceOf(JobFailureException.class)
                .hasMessageContaining(datasetId.toString())
                .hasMessageContaining(VENUE)
                .hasMessageContaining("city")
                .hasMessageContaining("city_name")
                .as("the message must name the repair, not just the failure")
                .hasMessageContaining("Wrapper tab");

        assertThat(compiled)
                .as("nothing may be drafted or promoted — the active schema keeps serving queries")
                .isFalse();
        assertThat(repo.latestPromotedRun(tenant, model.id()))
                .as("and no run is recorded at all: the refusal is before startRun")
                .isEmpty();
    }

    @Test
    @DisplayName("a refused compile leaves an existing DRAFT promotable — the check runs before the supersede")
    void refusedCompileDoesNotDestroyAnExistingDraft() {
        // The refusal used to run AFTER repository.supersedeDraftRuns, so a compile
        // rejected for a stale wrapper still retired the user's compiled-but-unpromoted draft to
        // SUPERSEDED and returned its mappings to PENDING — destroying work they could have
        // promoted, over an unrelated wrapper edit, while the javadoc claimed "nothing is
        // recorded". Every earlier test here had no draft in the fixture, so none of them could
        // see it. This one does.
        edu.upc.dtak.aran.core.domain.IntegrationRun draft = repo.startRun(
                tenant, model.id(), edu.upc.dtak.aran.core.domain.IntegrationRunMode.REBUILD,
                null, null);
        UUID draftVersionId = UUID.randomUUID();
        repo.recordDraft(tenant, draft.id(), draftVersionId);

        enrol("SELECT \"city\" AS \"http://ex/source/ds1/venue/city\" FROM ${SNAPSHOT}", true);
        driftTheSource();

        assertThatThrownBy(() -> service().rebuildToDraft(tenant, projectId, null))
                .isInstanceOf(JobFailureException.class);

        assertThat(repo.findRun(tenant, draft.id())).get().satisfies(run -> {
            assertThat(run.status())
                    .as("the refused compile must not have superseded a draft the user could still"
                            + " promote — the wrapper is stale, their draft is not")
                    .isEqualTo(edu.upc.dtak.aran.core.domain.IntegrationRunStatus.DRAFT);
            assertThat(run.draftVersionId()).isEqualTo(draftVersionId);
        });
    }

    @Test
    @DisplayName("an edited wrapper that is still valid after the drift compiles untouched")
    void compile_accepts_a_still_valid_edited_wrapper() {
        String stillValid = "SELECT \"name\" AS \"http://ex/source/ds1/venue/name\" FROM ${SNAPSHOT}";
        enrol(stillValid, true);
        driftTheSource();

        service().rebuildToDraft(tenant, projectId, null);

        assertThat(compiled).isTrue();
        assertThat(storedSource().wrapperSql()).isEqualTo(stillValid);
    }

    @Test
    @DisplayName("an UNEDITED wrapper is never checked — it is re-seeded, not judged")
    void compile_does_not_check_unedited_wrappers() {
        // Deliberately still holding the pre-drift text: in production the re-seed at refresh has
        // already replaced it, but a compile must not fail on a row it does not own. Failing here
        // would turn the "unedited follows the source" promise into a hard stop the user cannot
        // clear, since there is no user edit to fix.
        enrol("SELECT \"city\" AS \"http://ex/source/ds1/venue/city\" FROM ${SNAPSHOT}", false);
        driftTheSource();

        service().rebuildToDraft(tenant, projectId, null);

        assertThat(compiled).isTrue();
    }

    @Test
    @DisplayName("a class with no promoted history is skipped — nothing can have been lost")
    void compile_skips_a_class_the_active_schema_never_had() {
        enrol("SELECT \"city\" AS \"http://ex/source/ds1/venue/city\" FROM ${SNAPSHOT}", true);
        // Only the new source graph is staged: the active schema knows nothing about this class,
        // so there is no "before" to diff against and the check must stay silent rather than guess.
        graphStore.stageGraph(tenant, "urn:aran:source:" + datasetId,
                graphWith("venue_id", "name", "city_name", "country", "capacity"));

        service().rebuildToDraft(tenant, projectId, null);

        assertThat(compiled).isTrue();
    }

    // ── repairing a stale EDITED wrapper through the Wrapper tab ─────────────────────────

    /** The columns the ACTIVE (pre-drift) schema maps out of {@link #VENUE}'s wrapper. */
    private static final List<String> ACTIVE_COLUMNS = List.of("venue_id", "name", "city", "capacity");

    /**
     * Compile + a {@code verify-wrapper} that answers as the real operator does: from the ACTIVE
     * class's columns, reporting each one the saved text no longer aliases as missing, with the
     * raw source column the ACTIVE schema records for it.
     */
    private IntegrationModelService serviceVerifyingAgainstTheActiveSchema() {
        Operator queryEngine = new Operator() {
            @Override public String getOperatorId() { return "query-engine"; }
            @Override public String getDisplayName() { return "Query Engine (stub)"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                String sql = request.parameters().get("sql");
                Map<String, String> missing = new java.util.LinkedHashMap<>();
                for (String col : ACTIVE_COLUMNS) {
                    String iri = VENUE + "/" + col;
                    if (!sql.contains("AS \"" + iri + "\"")) missing.put(iri, col);
                }
                if (missing.isEmpty()) {
                    return OperatorResult.success(Map.of("status", "OPAQUE", "reason", "stub"));
                }
                try {
                    return OperatorResult.success(Map.of("status", "OPAQUE",
                            "reason", "the wrapper does not output column(s) its class reads: "
                                    + String.join(", ", missing.keySet()),
                            "missingColumns", String.join("\n", missing.keySet()),
                            "missingSourceColumns", objectMapper.writeValueAsString(missing)));
                } catch (com.fasterxml.jackson.core.JacksonException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        Operator compiler = new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "Schema Integration (stub)"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                compiled = true;
                return OperatorResult.success(Map.of("draftVersionId", UUID.randomUUID().toString()));
            }
        };
        return new IntegrationModelService(repo, graphStore,
                new OperatorOrchestrator(List.of(queryEngine, compiler)), objectMapper,
                datasetRepository, org.mockito.Mockito.mock(DataProductService.class));
    }

    @Test
    @DisplayName("the repair WRAPPER_STALE asks for saves: a column the source dropped is not 'missing'")
    void the_stale_wrapper_repair_saves_and_compiles() {
        // The save-time column check read the class from the ACTIVE schema,
        // which after the drift still maps `city` — so it refused the one edit the compile's own
        // refusal tells the user to make, and the wrapper could neither compile nor be repaired.
        IntegrationModelService service = serviceVerifyingAgainstTheActiveSchema();
        enrol(wrapperFor("venue_id", "name", "city", "capacity"), true);
        driftTheSource();
        assertThatThrownBy(() -> service.rebuildToDraft(tenant, projectId, null))
                .isInstanceOf(JobFailureException.class)
                .hasMessageContaining("Wrapper tab");

        String repaired = wrapperFor("venue_id", "name", "city_name", "country", "capacity");
        service.setWrapper(tenant, projectId, datasetId, VENUE, repaired);

        assertThat(storedSource().wrapperSql()).isEqualTo(repaired);
        assertThat(storedSource().expansionReason())
                .as("computed against a class shape the rebuild replaces, so it must be retried")
                .startsWith("stale:")
                .contains("city");

        service.rebuildToDraft(tenant, projectId, null);

        assertThat(compiled).isTrue();
        assertThat(storedSource().expansionReason())
                .as("the rebuild's re-verify still runs against the pre-rebuild ACTIVE schema, and"
                        + " must not replace the retryable verdict with a permanent 'does not output'")
                .startsWith("stale:");
    }

    @Test
    @DisplayName("after the drift, renaming a column the source still has is still refused, naming only it")
    void a_rename_of_a_still_present_column_is_refused_after_the_drift() {
        String edited = wrapperFor("venue_id", "name", "city", "capacity");
        enrol(edited, true);
        driftTheSource();

        String renamesName = wrapperFor("venue_id", "city_name", "capacity")
                .replace("SELECT ", "SELECT \"name\" AS label, ");
        assertThatThrownBy(() -> serviceVerifyingAgainstTheActiveSchema()
                .setWrapper(tenant, projectId, datasetId, VENUE, renamesName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(VENUE + "/name")
                .as("city is going away with the source; only the rename is the user's to fix")
                .hasMessageNotContaining(VENUE + "/city");

        assertThat(storedSource().wrapperSql()).isEqualTo(edited);
    }

    @Test
    @DisplayName("a renamed column that the edited wrapper never mentions does not fail the compile")
    void compile_does_not_fail_on_a_dropped_column_the_wrapper_ignores() {
        // The conservative half of the rule: only a column that provably existed, provably went
        // away, AND is still named by this wrapper is a stale reference. A false positive here
        // would fail a legitimate compile, which is worse than the defect being fixed.
        enrol("SELECT \"capacity\" AS \"http://ex/source/ds1/venue/capacity\" FROM ${SNAPSHOT}", true);
        driftTheSource();

        service().rebuildToDraft(tenant, projectId, null);

        assertThat(compiled).isTrue();
    }
}

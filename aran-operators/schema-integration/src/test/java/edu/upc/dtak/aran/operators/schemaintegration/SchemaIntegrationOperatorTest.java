package edu.upc.dtak.aran.operators.schemaintegration;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SchemaIntegrationOperator. Mocks GraphStore; verifies
 * parameter parsing, error routing, and that replaceDraft is called with
 * a non-empty model when source graphs are found.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchemaIntegrationOperator")
class SchemaIntegrationOperatorTest {

    @Mock GraphStore graphStore;
    @Mock edu.upc.dtak.aran.core.domain.DatasetRepository datasetRepository;

    private SchemaIntegrationOperator operator;
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = UUID.randomUUID().toString();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DS_A = UUID.randomUUID();
    private static final UUID DS_B = UUID.randomUUID();
    /** S5-17(4): `compile` requires the model marker, as `incremental-integrate` always has —
     *  a graph compiled without it can never be extended incrementally. */
    private static final UUID MODEL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        operator = new SchemaIntegrationOperator(graphStore, objectMapper, datasetRepository);
    }

    @Nested
    @DisplayName("ping")
    class Ping {
        @Test
        @DisplayName("returns success with status=ok")
        void ping_ok() {
            OperatorResult r = operator.execute(req("ping", Map.of()));
            assertThat(r.success()).isTrue();
            assertThat(r.data().get("operator")).isEqualTo("schema-integration");
        }

        @Test
        @DisplayName("S5-8: projectGlobalSchema is gone — unsupported, unlisted, and it writes no graph")
        void project_global_schema_is_removed() {
            OperatorResult r = operator.execute(req("projectGlobalSchema", Map.of()));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("UNSUPPORTED_OPERATION");
            assertThat(operator.manifest().operations())
                    .noneMatch(o -> o.name().equals("projectGlobalSchema"));
            verifyNoInteractions(graphStore);
        }
    }

    @Nested
    @DisplayName("compile operation — parameter validation")
    class ParameterValidation {

        @Test
        @DisplayName("missing sourceDatasetIds returns MISSING_PARAMETER")
        void missing_source_ids() {
            OperatorResult r = operator.execute(req("compile", Map.of(
                    "alignments", "[]"
            )));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("MISSING_PARAMETER");
        }

        @Test
        @DisplayName("non-UUID in sourceDatasetIds returns INVALID_PARAMETER")
        void non_uuid_in_source_ids() {
            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"not-a-uuid\"]",
                    "alignments", "[]",
                    "modelId", MODEL_ID.toString()
            )));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("INVALID_PARAMETER");
        }

        @Test
        @DisplayName("malformed JSON in alignments returns INVALID_PARAMETER")
        void malformed_alignments_json() {
            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\"]",
                    "alignments", "not-json",
                    "modelId", MODEL_ID.toString()
            )));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("INVALID_PARAMETER");
        }

        @Test
        @DisplayName("missing modelId returns MISSING_PARAMETER, as incremental-integrate already did")
        void missing_model_id() {
            // S5-17(4). `modelId` was optional here and mandatory for `incremental-integrate`, so a
            // graph compiled without the marker could never be extended incrementally: the
            // incremental guard reads the marker off the active graph and refuses with
            // INCREMENTAL_BASE_MODEL_MISMATCH. The refusal now arrives on the compile that would
            // create such a graph, not on the first incremental step months later.
            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\"]",
                    "alignments", "[]"
            )));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("MISSING_PARAMETER");
            assertThat(r.errorMessage()).contains("modelId");
            verifyNoInteractions(graphStore);
        }

        @Test
        @DisplayName("blank modelId is refused too, not stamped as a marker")
        void blank_model_id() {
            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\"]",
                    "alignments", "[]",
                    "modelId", "   "
            )));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("MISSING_PARAMETER");
        }

        @Test
        @DisplayName("the manifest declares modelId required for compile, so the controller enforces it too")
        void manifest_marks_model_id_required() {
            var compile = operator.manifest().operations().stream()
                    .filter(o -> o.name().equals("compile")).findFirst().orElseThrow();
            assertThat(compile.parameters().stream()
                    .filter(p -> p.name().equals("modelId"))
                    .allMatch(p -> p.required()))
                    .as("OperatorInvocationController enforces required parameters from the manifest")
                    .isTrue();
        }

        @Test
        @DisplayName("no userId on request returns UNAUTHENTICATED")
        void no_user_id_returns_unauthenticated() {
            Model sourceGraph = sourceClassModel("http://src-a/Person");
            when(graphStore.query(any(), any())).thenReturn(sourceGraph);

            OperatorRequest noUser = new OperatorRequest(
                    TENANT_ID, "compile",
                    Map.of("sourceDatasetIds", "[\"" + DS_A + "\"]", "alignments", "[]",
                            "modelId", MODEL_ID.toString()));
            OperatorResult r = operator.execute(noUser);
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Nested
    @DisplayName("compile operation — source graph resolution")
    class SourceGraphResolution {

        @Test
        @DisplayName("returns SOURCE_GRAPH_MISSING when a dataset has no bootstrapped schema")
        void source_graph_missing() {
            when(graphStore.query(any(), any())).thenReturn(ModelFactory.createDefaultModel());

            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\",\"" + DS_B + "\"]",
                    "alignments", "[]",
                    "modelId", MODEL_ID.toString()
            )));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("SOURCE_GRAPH_MISSING");
        }
    }

    @Nested
    @DisplayName("compile operation — successful integration")
    class SuccessfulIntegration {

        @Test
        @DisplayName("calls replaceDraft and returns draftVersionId when sources are found")
        void calls_replace_draft_with_non_empty_model() {
            Model sourceA = sourceClassModel("http://src-a/Person");
            Model sourceB = sourceClassModel("http://src-b/Employee");
            when(graphStore.query(any(), any()))
                    .thenReturn(sourceA)
                    .thenReturn(sourceB);

            GraphVersion draft = new GraphVersion(
                    UUID.randomUUID(), UUID.fromString(TENANT_ID), 1, "DRAFT", Instant.now(), USER_ID, null, null);
            when(graphStore.replaceDraft(any(), any(Model.class), any(AuditedActor.class)))
                    .thenReturn(draft);

            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\",\"" + DS_B + "\"]",
                    "alignments", "[]",
                    "modelId", MODEL_ID.toString()
            )));

            assertThat(r.success()).isTrue();
            assertThat(r.data()).containsKey("draftVersionId");
            assertThat(r.data().get("draftVersionId")).isEqualTo(draft.id().toString());
            assertThat(r.data()).containsKey("tripleCount");
            verify(graphStore).replaceDraft(any(), any(Model.class), any(AuditedActor.class));
        }

        @Test
        @DisplayName("unused alignments are serialised in the response")
        void unused_alignments_in_response() throws Exception {
            Model sourceA = sourceClassModel("http://src-a/Person");
            Model sourceB = sourceClassModel("http://src-b/Employee");
            when(graphStore.query(any(), any()))
                    .thenReturn(sourceA)
                    .thenReturn(sourceB);

            GraphVersion draft = new GraphVersion(
                    UUID.randomUUID(), UUID.fromString(TENANT_ID), 1, "DRAFT", Instant.now(), USER_ID, null, null);
            when(graphStore.replaceDraft(any(), any(Model.class), any(AuditedActor.class)))
                    .thenReturn(draft);

            // An alignment that will go unused because IRIs don't exist in source graphs
            String alignments = objectMapper.writeValueAsString(new Object[]{
                    Map.of("iriA", "http://ghost/A", "iriB", "http://ghost/B",
                           "label", "Ghost", "type", "class")
            });

            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\",\"" + DS_B + "\"]",
                    "alignments", alignments,
                    "modelId", MODEL_ID.toString()
            )));

            assertThat(r.success()).isTrue();
            assertThat(r.data().get("unusedAlignments")).contains("Ghost");
        }

        @Test
        @DisplayName("class alignment is applied and produces IntegratedResource in the draft")
        void class_alignment_is_applied() throws Exception {
            Model sourceA = sourceClassModel("http://src-a/Person");
            Model sourceB = sourceClassModel("http://src-b/Employee");
            when(graphStore.query(any(), any()))
                    .thenReturn(sourceA)
                    .thenReturn(sourceB);

            ArgumentCaptor<Model> modelCaptor = ArgumentCaptor.forClass(Model.class);
            GraphVersion draft = new GraphVersion(
                    UUID.randomUUID(), UUID.fromString(TENANT_ID), 1, "DRAFT", Instant.now(), USER_ID, null, null);
            when(graphStore.replaceDraft(any(), modelCaptor.capture(), any())).thenReturn(draft);

            String alignments = objectMapper.writeValueAsString(new Object[]{
                    Map.of("iriA", "http://src-a/Person", "iriB", "http://src-b/Employee",
                           "label", "Person", "type", "class")
            });

            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\",\"" + DS_B + "\"]",
                    "alignments", alignments,
                    "modelId", MODEL_ID.toString()
            )));

            assertThat(r.success()).isTrue();
            Model integrated = modelCaptor.getValue();
            Resource personIR = integrated.createResource(IntegrationVocab.INTEGRATION_NS + "Person");
            assertThat(integrated.contains(personIR, RDF.type,
                    integrated.createResource(IntegrationVocab.INTEGRATED_RESOURCE))).isTrue();
        }
    }

    @Nested
    @DisplayName("compile operation — wrapper expansion stamp (Phase 3A)")
    class WrapperExpansionStamp {

        private static final String NX_WRAPPER_EXPANSION =
                "http://www.essi.upc.edu/DTIM/NextiaDI/wrapperExpansion";

        @Test
        @DisplayName("a classWrappers entry with expansionStatus stamps nx:wrapperExpansion next to nx:wrapperSql")
        void stamps_expansion_status_alongside_wrapper_sql() throws Exception {
            Model sourceA = sourceClassModel("http://src-a/Person");
            Model sourceB = sourceClassModel("http://src-b/Employee");
            when(graphStore.query(any(), any())).thenReturn(sourceA).thenReturn(sourceB);

            ArgumentCaptor<Model> modelCaptor = ArgumentCaptor.forClass(Model.class);
            GraphVersion draft = new GraphVersion(
                    UUID.randomUUID(), UUID.fromString(TENANT_ID), 1, "DRAFT", Instant.now(), USER_ID, null, null);
            when(graphStore.replaceDraft(any(), modelCaptor.capture(), any())).thenReturn(draft);

            String classWrappers = objectMapper.writeValueAsString(Map.of(
                    "http://src-a/Person", Map.of(
                            "sql", "SELECT * FROM ${SNAPSHOT}",
                            "expansionStatus", "EXPANDED")));

            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\",\"" + DS_B + "\"]",
                    "alignments", "[]",
                    "classWrappers", classWrappers,
                    "modelId", MODEL_ID.toString()
            )));

            assertThat(r.success()).isTrue();
            Model integrated = modelCaptor.getValue();
            Resource person = integrated.createResource("http://src-a/Person");

            // The wrapper text itself still lands (existing M6 behaviour)...
            assertThat(integrated.contains(person,
                    integrated.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"),
                    "SELECT * FROM ${SNAPSHOT}")).isTrue();
            // ...and the verdict rides alongside it, so the catalog's QUERY 1 (nx:wrapperExpansion)
            // sees this source as EXPANDED rather than defaulting it to OPAQUE.
            assertThat(integrated.contains(person,
                    integrated.createProperty(NX_WRAPPER_EXPANSION), "EXPANDED")).isTrue();
        }

        @Test
        @DisplayName("a classWrappers entry with no expansionStatus removes a stale nx:wrapperExpansion triple")
        void removes_stale_expansion_triple_when_status_absent() throws Exception {
            // sourceA already carries a stamp from a prior compile (e.g. the wrapper was EXPANDED
            // before, then edited again without yet being re-verified).
            Model sourceA = sourceClassModel("http://src-a/Person");
            Resource personInSource = sourceA.createResource("http://src-a/Person");
            sourceA.add(personInSource,
                    sourceA.createProperty(NX_WRAPPER_EXPANSION), "EXPANDED");
            Model sourceB = sourceClassModel("http://src-b/Employee");
            when(graphStore.query(any(), any())).thenReturn(sourceA).thenReturn(sourceB);

            ArgumentCaptor<Model> modelCaptor = ArgumentCaptor.forClass(Model.class);
            GraphVersion draft = new GraphVersion(
                    UUID.randomUUID(), UUID.fromString(TENANT_ID), 1, "DRAFT", Instant.now(), USER_ID, null, null);
            when(graphStore.replaceDraft(any(), modelCaptor.capture(), any())).thenReturn(draft);

            // The override carries a fresh sql but no expansionStatus — never (re)verified.
            String classWrappers = objectMapper.writeValueAsString(Map.of(
                    "http://src-a/Person", Map.of("sql", "SELECT * FROM ${SNAPSHOT} WHERE x > 1")));

            OperatorResult r = operator.execute(req("compile", Map.of(
                    "sourceDatasetIds", "[\"" + DS_A + "\",\"" + DS_B + "\"]",
                    "alignments", "[]",
                    "classWrappers", classWrappers,
                    "modelId", MODEL_ID.toString()
            )));

            assertThat(r.success()).isTrue();
            Model integrated = modelCaptor.getValue();
            Resource person = integrated.createResource("http://src-a/Person");

            assertThat(integrated.contains(person,
                    integrated.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"),
                    "SELECT * FROM ${SNAPSHOT} WHERE x > 1")).isTrue();
            // The stale EXPANDED stamp must not survive an edit with no fresh verdict.
            assertThat(integrated.listStatements(person,
                    integrated.createProperty(NX_WRAPPER_EXPANSION), (org.apache.jena.rdf.model.RDFNode) null)
                    .toList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("unsupported operation")
    class UnsupportedOp {
        @Test
        @DisplayName("returns UNSUPPORTED_OPERATION")
        void unknown_op() {
            OperatorResult r = operator.execute(req("not-an-op", Map.of()));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("UNSUPPORTED_OPERATION");
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private OperatorRequest req(String operation, Map<String, String> params) {
        return new OperatorRequest(TENANT_ID, USER_ID, operation, params);
    }

    private static Model sourceClassModel(String iri) {
        Model m = ModelFactory.createDefaultModel();
        Resource r = m.createResource(iri);
        m.add(r, RDF.type, RDFS.Class);
        m.add(r, RDFS.label, iri.substring(iri.lastIndexOf('/') + 1));
        return m;
    }
}

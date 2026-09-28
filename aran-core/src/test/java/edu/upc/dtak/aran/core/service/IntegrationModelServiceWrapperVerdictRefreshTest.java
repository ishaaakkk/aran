package edu.upc.dtak.aran.core.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V41, V47: a compile re-verifies wrapper expansion
 * verdicts instead of copying forward whatever was persisted at save time. An EXPANDED verdict is
 * a round-trip fingerprint of the snapshot present WHEN THE WRAPPER WAS SAVED — after a refresh
 * or an engine bump it certifies nothing, and the graph stamp (`applyWrapperOverrides`) is a
 * copy, not a re-verify. Bootstrap-seeded wrappers (status null — only an edited save ever
 * verified) additionally never earned a verdict at all.
 */
class IntegrationModelServiceWrapperVerdictRefreshTest {

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final FakeGraphStore graphStore = new FakeGraphStore();
    private final ObjectMapper om = new ObjectMapper();

    /** Scripted verify-wrapper verdicts, consumed in call order; records every classIri asked. */
    private final Deque<Map<String, String>> verdicts = new ArrayDeque<>();
    private final List<String> verifiedClassIris = new ArrayList<>();
    /** The classWrappers JSON the compile operator received. */
    private String compiledClassWrappers;

    private Operator queryEngineStub() {
        return new Operator() {
            @Override public String getOperatorId() { return "query-engine"; }
            @Override public String getDisplayName() { return "verify stub"; }
            @Override public OperatorResult execute(OperatorRequest req) {
                if (!"verify-wrapper".equals(req.operation())) {
                    return OperatorResult.failure("UNSUPPORTED", req.operation());
                }
                verifiedClassIris.add(req.parameters().get("classIri"));
                Map<String, String> v = verdicts.poll();
                if (v == null) {
                    return OperatorResult.success(Map.of("status", "EXPANDED", "reason", ""));
                }
                return OperatorResult.success(v);
            }
        };
    }

    private Operator compileStub() {
        return new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "compile stub"; }
            @Override public OperatorResult execute(OperatorRequest req) {
                compiledClassWrappers = req.parameters().get("classWrappers");
                return OperatorResult.success(Map.of(
                        "draftVersionId", UUID.randomUUID().toString(),
                        "appliedMappingIds", "",
                        "deferredMappingIds", ""));
            }
        };
    }

    private IntegrationModelService service() {
        return new IntegrationModelService(repo, graphStore,
                new OperatorOrchestrator(List.of(compileStub(), queryEngineStub())), om,
                mock(DatasetRepository.class), mock(DataProductService.class));
    }

    private ModelSource enrol(IntegrationModel model, String classIri, String sql,
                              String status, String reason) {
        return repo.addSource(tenant, new ModelSource(UUID.randomUUID(),
                UUID.fromString(tenant.value()), model.id(), UUID.randomUUID(),
                classIri, sql, status, reason, false));
    }

    private ModelSource sourceByClass(String classIri) {
        return repo.load(tenant, projectId).sources().stream()
                .filter(s -> s.classIri().equals(classIri)).findFirst().orElseThrow();
    }

    private JsonNode wrapperNode(String classIri) throws Exception {
        return om.readTree(compiledClassWrappers).get(classIri);
    }

    @Test
    @DisplayName("an EXPANDED verdict is re-verified at compile and the payload carries the FRESH verdict")
    void expandedVerdictIsReverifiedAndPayloadIsFresh() throws Exception {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        enrol(model, "http://src/A", "SELECT * FROM ${SNAPSHOT}", "EXPANDED", null);
        // The snapshot changed since the save: the round-trip no longer matches.
        verdicts.add(Map.of("status", "OPAQUE", "reason", "row hash mismatch against the snapshot"));

        service.rebuildToDraft(tenant, projectId, actor);

        assertThat(verifiedClassIris).containsExactly("http://src/A");
        assertThat(wrapperNode("http://src/A").get("expansionStatus").asText())
                .as("the stamp must be the verdict of THIS compile, not the stale copy")
                .isEqualTo("OPAQUE");
        ModelSource persisted = sourceByClass("http://src/A");
        assertThat(persisted.expansionStatus()).isEqualTo("OPAQUE");
        assertThat(persisted.expansionReason()).contains("row hash mismatch");
    }

    @Test
    @DisplayName("a bootstrap-seeded wrapper (status null — never verified) earns its verdict at compile")
    void seededWrapperIsVerifiedAtCompile() throws Exception {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        enrol(model, "http://src/B", "SELECT * FROM ${SNAPSHOT}", null, null);
        verdicts.add(Map.of("status", "EXPANDED", "reason", ""));

        service.rebuildToDraft(tenant, projectId, actor);

        assertThat(verifiedClassIris).containsExactly("http://src/B");
        assertThat(wrapperNode("http://src/B").get("expansionStatus").asText()).isEqualTo("EXPANDED");
        assertThat(sourceByClass("http://src/B").expansionStatus()).isEqualTo("EXPANDED");
    }

    @Test
    @DisplayName("'verify unavailable' is retried; a content-determined OPAQUE is not re-verified")
    void unavailableIsRetriedContentOpaqueIsNot() {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        enrol(model, "http://src/RETRY", "SELECT * FROM ${SNAPSHOT}",
                "OPAQUE", "verify unavailable: no source view found …");
        enrol(model, "http://src/SETTLED", "SELECT count(*) FROM ${SNAPSHOT}",
                "OPAQUE", "wrapper does not plan cleanly over its src_ relation");
        verdicts.add(Map.of("status", "EXPANDED", "reason", ""));

        service.rebuildToDraft(tenant, projectId, actor);

        assertThat(verifiedClassIris)
                .as("the V41 residue retries; the aggregate's verdict is about its content and stands")
                .containsExactly("http://src/RETRY");
        assertThat(sourceByClass("http://src/RETRY").expansionStatus()).isEqualTo("EXPANDED");
        assertThat(sourceByClass("http://src/SETTLED").expansionStatus()).isEqualTo("OPAQUE");
    }

    @Test
    @DisplayName("an EXPANDED row whose re-verify is unavailable is DOWNGRADED to a stale OPAQUE — never left standing")
    void expandedDowngradesWhenReverifyUnavailable() throws Exception {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        enrol(model, "http://src/C", "SELECT * FROM ${SNAPSHOT}", "EXPANDED", null);
        verdicts.add(Map.of("status", "OPAQUE", "reason", "verify unavailable: lake down"));

        service.rebuildToDraft(tenant, projectId, actor);

        ModelSource persisted = sourceByClass("http://src/C");
        assertThat(persisted.expansionStatus()).isEqualTo("OPAQUE");
        assertThat(persisted.expansionReason())
                .as("the reason must say the verdict went stale, and stay retryable")
                .startsWith("stale:");
        assertThat(wrapperNode("http://src/C").get("expansionStatus").asText()).isEqualTo("OPAQUE");
    }

    @Test
    @DisplayName("a dataset refresh downgrades its EXPANDED verdicts immediately — the badge must not certify bytes that are gone")
    void refreshDowngradesExpandedVerdictsForTheDataset() {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        UUID refreshed = UUID.randomUUID();
        UUID untouched = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), refreshed, "http://src/R", "SELECT * FROM ${SNAPSHOT}", "EXPANDED", null, false));
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), untouched, "http://src/U", "SELECT * FROM ${SNAPSHOT}", "EXPANDED", null, false));

        service.markWrapperVerdictsStale(tenant, refreshed);

        ModelSource r = repo.load(tenant, projectId).sources().stream()
                .filter(s -> s.datasetId().equals(refreshed)).findFirst().orElseThrow();
        ModelSource u = repo.load(tenant, projectId).sources().stream()
                .filter(s -> s.datasetId().equals(untouched)).findFirst().orElseThrow();
        assertThat(r.expansionStatus()).isEqualTo("OPAQUE");
        assertThat(r.expansionReason())
                .as("retryable at the next compile, and honest about why")
                .startsWith("stale:");
        assertThat(u.expansionStatus())
                .as("other datasets' verdicts are untouched")
                .isEqualTo("EXPANDED");
    }

    @Test
    @DisplayName("a verify blow-up never fails the compile — the source keeps its old verdict")
    void verifyFailureDoesNotFailTheCompile() throws Exception {
        // No query-engine operator registered at all: the orchestrator throws on dispatch.
        IntegrationModelService service = new IntegrationModelService(repo, graphStore,
                new OperatorOrchestrator(List.of(compileStub())), om,
                mock(DatasetRepository.class), mock(DataProductService.class));
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        enrol(model, "http://src/D", "SELECT * FROM ${SNAPSHOT}", "EXPANDED", null);

        Map<String, String> out = service.rebuildToDraft(tenant, projectId, actor);

        assertThat(out).containsKey("draftVersionId");
        assertThat(wrapperNode("http://src/D")).isNotNull();
    }
}

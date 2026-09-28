package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationRunStatus;
import edu.upc.dtak.aran.core.domain.MappingApplication;
import edu.upc.dtak.aran.core.domain.MappingApplicationState;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Service-orchestration proof for the incremental path: {@link IntegrationModelService}
 * drives a rebuild → promote → incremental → promote cycle and records the run + per-mapping
 * application state correctly. The schema-integration operator is stubbed (its algorithm is
 * proven separately by the operator-level equivalence oracle); this test exercises the
 * service's run bookkeeping and model-aware promotion against a faithful in-memory graph store.
 */
@DisplayName("IntegrationModelService — incremental orchestration")
class IncrementalIntegrationServiceTest {

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID datasetA = UUID.randomUUID();
    private final UUID datasetB = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final SchemaVersionServiceRollbackTest.InMemoryGraphVersionPort port =
            new SchemaVersionServiceRollbackTest.InMemoryGraphVersionPort(tenant);
    private final SchemaVersionServiceRollbackTest.InMemoryNamedGraphStore store =
            new SchemaVersionServiceRollbackTest.InMemoryNamedGraphStore(port);
    private final ObjectMapper om = new ObjectMapper();

    /** The {@code newMappings} payload each incremental call received, in order. */
    private final List<String> incrementalPayloads = new ArrayList<>();

    /**
     * A stub "schema-integration" operator: writes a draft to the store (a rebuild via
     * replaceDraft, an incremental via CAS replaceDraftFromBase) and echoes the request's
     * mapping ids back as applied — enough to drive the service's run/application bookkeeping.
     */
    private Operator stubOperator() {
        return new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "stub"; }
            @Override public OperatorResult execute(OperatorRequest req) {
                AuditedActor auditedActor = new AuditedActor(req.userId());
                Model graph = ModelFactory.createDefaultModel();
                graph.add(graph.createResource("urn:aran:schema"),
                        graph.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/integrationModel"),
                        req.parameters().get("modelId"));
                GraphVersion draft;
                String appliedIds;
                if ("incremental-integrate".equals(req.operation())) {
                    incrementalPayloads.add(req.parameters().get("newMappings"));
                    UUID base = UUID.fromString(req.parameters().get("baseVersionId"));
                    draft = store.replaceDraftFromBase(tenant, base, graph, auditedActor);
                    appliedIds = mappingIdsOf(req.parameters().get("newMappings"));
                } else {
                    draft = store.replaceDraft(tenant, graph, auditedActor);
                    appliedIds = mappingIdsOf(req.parameters().get("alignments"));
                }
                return OperatorResult.success(Map.of(
                        "draftVersionId", draft.id().toString(),
                        "appliedMappingIds", appliedIds,
                        "deferredMappingIds", ""));
            }
        };
    }

    private String mappingIdsOf(String alignmentsJson) {
        try {
            List<String> ids = new ArrayList<>();
            for (JsonNode n : om.readTree(alignmentsJson)) {
                JsonNode id = n.get("mappingId");
                if (id != null && !id.isNull() && !id.asText().isBlank()) {
                    ids.add(id.asText());
                }
            }
            return String.join(",", ids);
        } catch (Exception e) {
            return "";
        }
    }

    private IntegrationModelService service() {
        return new IntegrationModelService(repo, store,
                new OperatorOrchestrator(List.of(stubOperator())), om, mock(DatasetRepository.class),
                mock(DataProductService.class));
    }

    @Test
    @DisplayName("rebuild → promote → incremental → promote records run + application state end to end")
    void full_incremental_cycle() {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);

        // Enroll source A and accept a mapping (→ PENDING application).
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetA, "http://a/Person", null, null, null, false));
        ModelMapping mA = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://a/Person", "Person", actor, null);

        // ── Rebuild → creates a DRAFT run; the mapping is recorded APPLIED against the draft. ──
        Map<String, String> rebuild = service.rebuildToDraft(tenant, projectId, actor);
        UUID rebuildRunId = UUID.fromString(rebuild.get("integrationRunId"));
        assertThat(repo.findRun(tenant, rebuildRunId).orElseThrow().status())
                .isEqualTo(IntegrationRunStatus.DRAFT);

        // ── Promote the rebuild → run PROMOTED, active run set, mapping APPLIED, no rebuild flag. ──
        GraphVersion base = service.promoteRun(tenant, projectId, rebuildRunId, actor);
        assertThat(repo.findRun(tenant, rebuildRunId).orElseThrow().status())
                .isEqualTo(IntegrationRunStatus.PROMOTED);
        assertThat(repo.getOrCreate(tenant, projectId).activeIntegrationRunId()).isEqualTo(rebuildRunId);
        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired()).isFalse();
        assertThat(repo.findMappingApplication(tenant, mA.id()).orElseThrow().state())
                .isEqualTo(MappingApplicationState.APPLIED);
        assertThat(store.currentVersion(tenant).id()).isEqualTo(base.id());

        // ── Add source B + accept a new mapping (→ PENDING), then integrate incrementally. ──
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetB, "http://b/Worker", null, null, null, false));
        ModelMapping mB = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://b/Worker", "Person", actor, null);

        Map<String, String> incr = service.incrementalIntegrateToDraft(tenant, projectId, datasetB, actor);
        UUID incrRunId = UUID.fromString(incr.get("integrationRunId"));
        assertThat(repo.findRun(tenant, incrRunId).orElseThrow().mode().name()).isEqualTo("INCREMENTAL");
        // The new mapping is recorded against the incremental run; the old one stays applied.
        assertThat(repo.findMappingApplication(tenant, mB.id()).orElseThrow().state())
                .isEqualTo(MappingApplicationState.APPLIED);

        // ── Promote the incremental run → it becomes the active run. ──
        GraphVersion promoted = service.promoteRun(tenant, projectId, incrRunId, actor);
        assertThat(repo.getOrCreate(tenant, projectId).activeIntegrationRunId()).isEqualTo(incrRunId);
        assertThat(repo.latestPromotedRun(tenant, model.id()).orElseThrow().id()).isEqualTo(incrRunId);
        assertThat(store.currentVersion(tenant).id()).isEqualTo(promoted.id());
    }

    @Test
    @DisplayName("J4-F5: after a promoted incremental add the model's baseline is the ACTIVE version, "
            + "though the baseline's application rows still name the version that compiled them")
    void incremental_promote_leaves_the_model_live() {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetA, "http://a/Person", null, null, null, false));
        ModelMapping mA = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://a/Person", "Person", actor, null);
        GraphVersion base = service.promoteRun(tenant, projectId,
                UUID.fromString(service.rebuildToDraft(tenant, projectId, actor).get("integrationRunId")), actor);

        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetB, "http://b/Worker", null, null, null, false));
        ModelMapping mB = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://b/Worker", "Person", actor, null);
        GraphVersion promoted = service.promoteRun(tenant, projectId, UUID.fromString(
                service.incrementalIntegrateToDraft(tenant, projectId, datasetB, actor).get("integrationRunId")), actor);

        IntegrationModelService.IntegrationState state = service.integrationState(tenant, projectId);
        assertThat(state.activeVersion().id()).isEqualTo(promoted.id());
        assertThat(state.baselineVersionId())
                .as("the fact the web's alignmentStanding reads 'live' from: this model's lineage is ACTIVE")
                .isEqualTo(promoted.id());
        assertThat(state.latestDraftRun()).isNull();
        Map<UUID, MappingApplication> rows = new java.util.HashMap<>();
        state.applications().forEach(a -> rows.put(a.mappingId(), a));
        assertThat(rows.get(mA.id()).state()).isEqualTo(MappingApplicationState.APPLIED);
        assertThat(rows.get(mA.id()).versionId())
                .as("an incremental run re-sends only PENDING/DEFERRED mappings, so the baseline's row keeps "
                        + "naming v1 while v2 serves it — why 'live' cannot be versionId == ACTIVE")
                .isEqualTo(base.id());
        assertThat(rows.get(mB.id()).versionId()).isEqualTo(promoted.id());
    }

    @Test
    @DisplayName("promoting a run a second time is a NOT_A_DRAFT conflict, not a generic 500 (J4-F11)")
    void promoting_twice_is_a_conflict() {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetA, "http://a/Person", null, null, null, false));
        service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://a/Person", "Person", actor, null);
        UUID runId = UUID.fromString(
                service.rebuildToDraft(tenant, projectId, actor).get("integrationRunId"));
        service.promoteRun(tenant, projectId, runId, actor);

        // The retry after a promote the browser stopped waiting for: it landed the first time.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.promoteRun(tenant, projectId, runId, actor))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("it was already promoted")
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ConflictException.NOT_A_DRAFT);
    }

    @Test
    @DisplayName("an abandoned (never-promoted) draft returns its mappings to PENDING for the next run")
    void abandoned_draft_returns_mappings_to_pending() {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);

        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetA, "http://a/Person", null, null, null, false));
        service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://a/Person", "Person", actor, null);

        // Baseline: rebuild + promote.
        Map<String, String> rebuild = service.rebuildToDraft(tenant, projectId, actor);
        service.promoteRun(tenant, projectId, UUID.fromString(rebuild.get("integrationRunId")), actor);

        // Incremental #1 for dataset B — a draft is produced but NEVER promoted.
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetB, "http://b/Worker", null, null, null, false));
        ModelMapping mB = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://b/Worker", "Person", actor, null);
        Map<String, String> abandoned = service.incrementalIntegrateToDraft(tenant, projectId, datasetB, actor);
        UUID abandonedRunId = UUID.fromString(abandoned.get("integrationRunId"));

        // Incremental #2 for dataset C, still on the same promoted baseline.
        UUID datasetC = UUID.randomUUID();
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetC, "http://c/Staff", null, null, null, false));
        ModelMapping mC = service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://c/Staff", "Person", actor, null);
        service.incrementalIntegrateToDraft(tenant, projectId, datasetC, actor);

        // The abandoned run's mapping must be re-sent — it was never durably applied.
        String secondPayload = incrementalPayloads.get(1);
        assertThat(secondPayload).as("mC must be in the second payload").contains(mC.id().toString());
        assertThat(secondPayload)
                .as("mB was never promoted, so it must be re-sent rather than stranded as APPLIED")
                .contains(mB.id().toString());

        // The abandoned run is retired, so it can no longer be promoted behind the new one.
        assertThat(repo.findRun(tenant, abandonedRunId).orElseThrow().status())
                .isEqualTo(IntegrationRunStatus.SUPERSEDED);
        // A 409 since J4-F11 (was an unmapped IllegalStateException, i.e. a 500): the state, not
        // the request, is what refuses — and the sentence names which state. A superseded draft
        // is A7's PromoteConflictException (DRAFT_SUPERSEDED, a StateConflictException); every
        // other non-DRAFT status is A3's ConflictException (NOT_A_DRAFT) — two 409 families until
        // the cleanup slice folds them (V86).
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.promoteRun(tenant, projectId, abandonedRunId, actor))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("superseded by a newer compile");
    }

    @Test
    @DisplayName("a fresh incremental request without a promoted baseline is rejected")
    void incremental_without_baseline_rejected() {
        IntegrationModelService service = service();
        repo.getOrCreate(tenant, projectId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.incrementalIntegrateToDraft(tenant, projectId, datasetB, actor))
                .isInstanceOf(JobFailureException.class);
        // The failed run is recorded, not left dangling, only if a run was started; here we
        // reject before starting a run, so no PROMOTED run exists.
        assertThat(repo.latestPromotedRun(tenant, repo.getOrCreate(tenant, projectId).id())).isEmpty();
    }
}

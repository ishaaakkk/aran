package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.IntegrationRunMode;
import edu.upc.dtak.aran.core.domain.MappingApplicationState;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.fake.FakeGraphStore;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Phase 4: integration-run state machine + mapping-application state + {@code rebuild_required}
 * triggers, against the in-memory repository and {@link IntegrationModelService}.
 */
@DisplayName("Integration run + mapping-application state")
class IntegrationRunStateTest {

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();

    private IntegrationModelService service() {
        return new IntegrationModelService(repo, new FakeGraphStore(),
                new OperatorOrchestrator(List.of()), new ObjectMapper(), mock(DatasetRepository.class),
                mock(DataProductService.class));
    }

    // ── run + application state machine (repository level) ────────────────────────

    @Test
    @DisplayName("PENDING → APPLIED: a run marks accepted-pending mappings applied against a version")
    void pending_to_applied() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        ModelMapping m = repo.addMapping(tenant, mapping(model.id()));
        repo.upsertMappingApplicationPending(tenant, model.id(), m.id());

        assertThat(repo.acceptedPendingMappings(tenant, model.id())).extracting(ModelMapping::id)
                .containsExactly(m.id());

        IntegrationRun run = repo.startRun(tenant, model.id(), IntegrationRunMode.REBUILD, null, null);
        UUID versionId = UUID.randomUUID();
        repo.markMappingsApplied(tenant, run.id(), versionId, List.of(m.id()));

        assertThat(repo.acceptedPendingMappings(tenant, model.id())).isEmpty();
        assertThat(repo.findMappingApplication(tenant, m.id()).orElseThrow().state())
                .isEqualTo(MappingApplicationState.APPLIED);
        assertThat(repo.findMappingApplication(tenant, m.id()).orElseThrow().versionId())
                .isEqualTo(versionId);
    }

    @Test
    @DisplayName("PENDING → DEFERRED → APPLIED: a deferred mapping retries and applies in a later run")
    void pending_deferred_then_applied() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        ModelMapping m = repo.addMapping(tenant, mapping(model.id()));
        repo.upsertMappingApplicationPending(tenant, model.id(), m.id());

        IntegrationRun run1 = repo.startRun(tenant, model.id(), IntegrationRunMode.REBUILD, null, null);
        repo.markMappingsDeferred(tenant, run1.id(), List.of(m.id()), "domains not integrated");
        assertThat(repo.acceptedDeferredMappings(tenant, model.id())).extracting(ModelMapping::id)
                .containsExactly(m.id());

        IntegrationRun run2 = repo.startRun(tenant, model.id(), IntegrationRunMode.INCREMENTAL, null, null);
        UUID versionId = UUID.randomUUID();
        repo.markMappingsApplied(tenant, run2.id(), versionId, List.of(m.id()));

        assertThat(repo.acceptedDeferredMappings(tenant, model.id())).isEmpty();
        assertThat(repo.findMappingApplication(tenant, m.id()).orElseThrow().state())
                .isEqualTo(MappingApplicationState.APPLIED);
    }

    @Test
    @DisplayName("latestPromotedRun and active_integration_run_id reflect promotion")
    void promotion_is_reflected() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        IntegrationRun run = repo.startRun(tenant, model.id(), IntegrationRunMode.REBUILD, null, null);
        assertThat(repo.latestPromotedRun(tenant, model.id())).isEmpty();

        UUID draftVersion = UUID.randomUUID();
        repo.recordDraft(tenant, run.id(), draftVersion);
        repo.markRunPromoted(tenant, run.id(), draftVersion);
        repo.setActiveIntegrationRun(tenant, model.id(), run.id());

        assertThat(repo.latestPromotedRun(tenant, model.id()).orElseThrow().id()).isEqualTo(run.id());
        assertThat(repo.getOrCreate(tenant, projectId).activeIntegrationRunId()).isEqualTo(run.id());
    }

    // ── rebuild_required triggers (service level) ─────────────────────────────────

    @Test
    @DisplayName("accepting a mapping upserts its application row as PENDING")
    void accept_creates_pending_application() {
        ModelMapping m = service().addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/A", "http://ex/B", "Paper", actor, null);

        assertThat(repo.findMappingApplication(tenant, m.id())).isPresent();
        assertThat(repo.findMappingApplication(tenant, m.id()).orElseThrow().state())
                .isEqualTo(MappingApplicationState.PENDING);
    }

    @Test
    @DisplayName("removing a source under a promoted baseline sets rebuildRequired")
    void remove_source_under_baseline_sets_rebuild_required() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        IntegrationRun run = repo.startRun(tenant, model.id(), IntegrationRunMode.REBUILD, null, null);
        repo.markRunPromoted(tenant, run.id(), UUID.randomUUID());
        repo.setActiveIntegrationRun(tenant, model.id(), run.id());

        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired()).isFalse();
        service().removeSource(tenant, projectId, UUID.randomUUID());
        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired()).isTrue();
    }

    @Test
    @DisplayName("rejecting an APPLIED mapping sets rebuildRequired")
    void reject_applied_mapping_sets_rebuild_required() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        ModelMapping m = service().addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/A", "http://ex/B", "Paper", actor, null);
        IntegrationRun run = repo.startRun(tenant, model.id(), IntegrationRunMode.REBUILD, null, null);
        repo.markMappingsApplied(tenant, run.id(), UUID.randomUUID(), List.of(m.id()));

        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired()).isFalse();
        service().setMappingStatus(tenant, projectId, m.id(), ModelMapping.REJECTED);
        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired()).isTrue();
    }

    @Test
    @DisplayName("deleting an APPLIED mapping sets rebuildRequired; deleting a PENDING one does not")
    void delete_applied_mapping_sets_rebuild_required() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);

        // A PENDING (never-applied) mapping: deleting it must NOT force a rebuild.
        ModelMapping pending = service().addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://ex/A", "http://ex/B", "Paper", actor, null);
        service().deleteMapping(tenant, projectId, pending.id());
        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired()).isFalse();

        // An APPLIED mapping: deleting it forces a rebuild.
        ModelMapping applied = service().addMapping(tenant, projectId, MappingKinds.DATATYPE,
                "http://ex/A#t", "http://ex/B#t", "title", actor, null);
        IntegrationRun run = repo.startRun(tenant, model.id(), IntegrationRunMode.REBUILD, null, null);
        repo.markMappingsApplied(tenant, run.id(), UUID.randomUUID(), List.of(applied.id()));
        service().deleteMapping(tenant, projectId, applied.id());
        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired()).isTrue();
    }

    @Test
    @DisplayName("incrementalIntegrateToDraft is rejected when rebuildRequired is set")
    void incremental_rejected_when_rebuild_required() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        repo.setRebuildRequired(tenant, model.id(), true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service().incrementalIntegrateToDraft(tenant, projectId, UUID.randomUUID(), actor))
                .isInstanceOf(JobFailureException.class)
                .hasMessageContaining("rebuild");
    }

    @Test
    @DisplayName("incrementalIntegrateToDraft is rejected when there is no promoted baseline")
    void incremental_rejected_without_baseline() {
        repo.getOrCreate(tenant, projectId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service().incrementalIntegrateToDraft(tenant, projectId, UUID.randomUUID(), actor))
                .isInstanceOf(JobFailureException.class);
    }

    private ModelMapping mapping(UUID modelId) {
        return new ModelMapping(UUID.randomUUID(), UUID.fromString(tenant.value()), modelId,
                MappingKinds.CLASS, "http://ex/A", "http://ex/B", "Paper",
                ModelMapping.ACCEPTED, null, actor, null, java.time.Instant.now());
    }
}

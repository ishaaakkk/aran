package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubmission;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.DiscoverConstraintsRequest;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.ModelBundleDto;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.IntegrationRunMode;
import edu.upc.dtak.aran.core.domain.IntegrationRunStatus;
import edu.upc.dtak.aran.core.domain.MappingApplication;
import edu.upc.dtak.aran.core.domain.MappingApplicationState;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.ModelBundle;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.operator.ProgressReporter;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.service.JobService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/projects/{id}/model/constraints/discover} (Task 5, phase 3B): mirrors
 * {@code compile}'s submit pattern exactly — long-running work goes on the job substrate rather
 * than blocking the request, so the controller's own job here is to submit the right job type
 * with the right task, and hand back its id. No Spring context, matching
 * {@code RelationalEngineControllerTest}'s idiom: the controller has plain collaborators and the
 * interesting assertions are about wiring, not the web layer.
 */
class IntegrationModelControllerTest {

    private final IntegrationModelService modelService = mock(IntegrationModelService.class);
    private final JobService jobService = mock(JobService.class);
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    /** Permissive by default: these tests are about job wiring, not the gate. */
    private final ProjectAccess projectAccess = mock(ProjectAccess.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GraphVersionPort versionPort = mock(GraphVersionPort.class);
    private final edu.upc.dtak.aran.core.service.AuditService audit =
            mock(edu.upc.dtak.aran.core.service.AuditService.class);

    private final IntegrationModelController controller = new IntegrationModelController(
            modelService, jobService, currentUser, projectAccess, objectMapper, versionPort, audit);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final String classIri = "http://ex/Paper";

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("submits a model:discover-constraints job carrying datasetId/classIri, and returns its id")
    void submitsDiscoverConstraintsJob() {
        when(currentUser.require())
                .thenReturn(new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER));
        Job pending = Job.pending(jobId, tenantId, projectId, "model:discover-constraints",
                Map.of(), userId, Instant.now());
        when(jobService.submit(any(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn(pending);

        ResponseEntity<JobSubmission> response =
                controller.discoverConstraints(projectId, new DiscoverConstraintsRequest(datasetId, classIri));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().jobId()).isEqualTo(jobId);

        ArgumentCaptor<TenantContext> tenantCaptor = ArgumentCaptor.forClass(TenantContext.class);
        ArgumentCaptor<String> jobTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<JobService.JobTask> taskCaptor = ArgumentCaptor.forClass(JobService.JobTask.class);
        verify(jobService).submit(tenantCaptor.capture(), eq(projectId), jobTypeCaptor.capture(),
                paramsCaptor.capture(), eq(userId), taskCaptor.capture());

        assertThat(tenantCaptor.getValue().value()).isEqualTo(tenantId.toString());
        assertThat(jobTypeCaptor.getValue()).isEqualTo("model:discover-constraints");
        assertThat(paramsCaptor.getValue())
                .containsEntry("projectId", projectId.toString())
                .containsEntry("datasetId", datasetId.toString())
                .containsEntry("classIri", classIri);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("the submitted job task calls discoverConstraints with the caller's tenant and the request body")
    void submittedTaskInvokesServiceWithTheRightArguments() throws Exception {
        when(currentUser.require())
                .thenReturn(new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER));
        when(jobService.submit(any(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn(Job.pending(jobId, tenantId, projectId, "model:discover-constraints",
                        Map.of(), userId, Instant.now()));
        when(modelService.discoverConstraints(any(), eq(projectId), eq(datasetId), eq(classIri)))
                .thenReturn(Map.of("discovered", "1", "screened", "0", "persisted", "1"));

        controller.discoverConstraints(projectId, new DiscoverConstraintsRequest(datasetId, classIri));

        ArgumentCaptor<JobService.JobTask> taskCaptor = ArgumentCaptor.forClass(JobService.JobTask.class);
        verify(jobService).submit(any(), eq(projectId), anyString(), anyMap(), eq(userId), taskCaptor.capture());

        // Run the captured task the way JobService's real executor would, and check it reached
        // the service with the tenant resolved from the caller and the request body's fields.
        Map<String, String> result = taskCaptor.getValue().run(ProgressReporter.NOOP);
        assertThat(result).containsEntry("persisted", "1");
        verify(modelService).discoverConstraints(
                argThat((TenantContext t) -> t.value().equals(tenantId.toString())),
                eq(projectId), eq(datasetId), eq(classIri));
    }

    // ── V42: a constraint whose class the active catalog no longer serves ─────────────────

    private ModelConstraint constraint(String onClassIri, boolean enforced) {
        return new ModelConstraint(UUID.randomUUID(), tenantId, UUID.randomUUID(),
                onClassIri, "h_index > 0", enforced, Instant.now(), "MANUAL");
    }

    private ModelBundleDto getBundleWith(Set<String> activeClasses, ModelConstraint... constraints) {
        return getBundleWith(activeClasses, List.of(), EMPTY_STATE, constraints);
    }

    private static final IntegrationModelService.IntegrationState EMPTY_STATE =
            new IntegrationModelService.IntegrationState(null, null, null, List.of());

    private ModelBundleDto getBundleWith(Set<String> activeClasses, List<ModelMapping> mappings,
                                         IntegrationModelService.IntegrationState state,
                                         ModelConstraint... constraints) {
        when(currentUser.require())
                .thenReturn(new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER));
        IntegrationModel model = new IntegrationModel(UUID.randomUUID(), tenantId, projectId,
                Instant.now(), Instant.now(), null, false);
        when(modelService.getBundle(any(), eq(projectId)))
                .thenReturn(new ModelBundle(model, List.of(), mappings, List.of(constraints)));
        when(modelService.integrationState(any(), eq(projectId))).thenReturn(state);
        // Both active-graph answers arrive through ONE call, from one read of the graph — V49.
        // Stubbing the two single-answer methods instead would pass here and miss the endpoint
        // going back to two Fuseki round trips, which is what widened V49's race.
        when(modelService.activeModelFacts(any()))
                .thenReturn(new IntegrationModelService.ActiveModelFacts(List.of(), activeClasses));
        return controller.get(projectId);
    }

    @Test
    @DisplayName("V42: an enforced constraint whose class the active catalog dropped is orphaned, never enforced")
    void enforcedConstraintOnARetiredClassIsReportedOrphaned() {
        // The V42 sequence: `h_index > 0` was enforced on Int_j_authors; an alignment then merged
        // that class into AuthorMerged and retired it. `enforcedByClass` stopped applying the
        // constraint at that moment — but the row still says enforced, so the API asserted a
        // guarantee that had already stopped holding.
        ModelConstraint retired = constraint("http://ex/integration/Int_j_authors", true);

        ModelBundleDto bundle = getBundleWith(Set.of("http://ex/integration/AuthorMerged"), retired);

        assertThat(bundle.constraints()).singleElement().satisfies(c -> {
            assertThat(c.orphaned()).isTrue();
            assertThat(c.enforced())
                    .as("the wire field means enforced AND live — the engine skips this one")
                    .isFalse();
            assertThat(c.classIri()).as("the dead class IRI stays, so the UI can name it")
                    .isEqualTo("http://ex/integration/Int_j_authors");
        });
    }

    @Test
    @DisplayName("V42: a constraint on a class the catalog still serves is untouched")
    void constraintOnALiveClassIsNotOrphaned() {
        ModelConstraint live = constraint("http://ex/integration/AuthorMerged", true);

        ModelBundleDto bundle = getBundleWith(Set.of("http://ex/integration/AuthorMerged"), live);

        assertThat(bundle.constraints()).singleElement().satisfies(c -> {
            assertThat(c.orphaned()).isFalse();
            assertThat(c.enforced()).isTrue();
        });
    }

    @Test
    @DisplayName("V42: with no active schema at all, nothing is orphaned — there is no catalog to be dropped from")
    void nothingIsOrphanedWhenTheActiveCatalogIsEmpty() {
        // A project that has never compiled: constraints can legitimately exist already
        // (validate-constraint accepts a class it cannot find), and calling every one of them
        // orphaned would be noise, not a warning.
        ModelConstraint pending = constraint("http://ex/integration/Paper", true);

        ModelBundleDto bundle = getBundleWith(Set.of(), pending);

        assertThat(bundle.constraints()).singleElement().satisfies(c -> {
            assertThat(c.orphaned()).isFalse();
            assertThat(c.enforced()).isTrue();
        });
    }
    // ── J4-F2 / J4-F5: where the model stands, after a reload ────────────────────────────

    private GraphVersion version(UUID id, int number, String status) {
        return new GraphVersion(id, tenantId, number, status, Instant.now(), userId, null, null);
    }

    private IntegrationRun draftRun(UUID draftVersionId) {
        return new IntegrationRun(UUID.randomUUID(), tenantId, UUID.randomUUID(), IntegrationRunMode.REBUILD,
                IntegrationRunStatus.DRAFT, null, null, draftVersionId, null, null, null,
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("J4-F2: the draft still awaiting promotion rides on the bundle, with its version number")
    void pendingDraftIsReportedSoAReloadDoesNotLoseIt() {
        UUID draftVersion = UUID.randomUUID();
        UUID activeVersion = UUID.randomUUID();
        IntegrationRun run = draftRun(draftVersion);
        when(versionPort.findById(any(), eq(draftVersion))).thenReturn(Optional.of(version(draftVersion, 7, "DRAFT")));

        ModelBundleDto bundle = getBundleWith(Set.of(), List.of(), new IntegrationModelService.IntegrationState(
                run, activeVersion, version(activeVersion, 6, "ACTIVE"), List.of()));

        assertThat(bundle.latestDraftRun()).isNotNull();
        assertThat(bundle.latestDraftRun().runId()).isEqualTo(run.id());
        assertThat(bundle.latestDraftRun().draftVersionNumber()).isEqualTo(7);
        assertThat(bundle.latestDraftRun().mode()).isEqualTo("REBUILD");
        assertThat(bundle.activeVersion().versionNumber()).isEqualTo(6);
        assertThat(bundle.baselineVersionId()).isEqualTo(activeVersion);
    }

    @Test
    @DisplayName("J4-F2: a DRAFT run whose version is no longer a draft is not offered for promotion")
    void aDraftRunWhoseVersionWasPromotedElsewhereIsNotOffered() {
        UUID draftVersion = UUID.randomUUID();
        // Promoted through the generic path before J4-F2, which left the run in DRAFT.
        when(versionPort.findById(any(), eq(draftVersion))).thenReturn(Optional.of(version(draftVersion, 7, "ACTIVE")));

        ModelBundleDto bundle = getBundleWith(Set.of(), List.of(), new IntegrationModelService.IntegrationState(
                draftRun(draftVersion), null, version(draftVersion, 7, "ACTIVE"), List.of()));

        assertThat(bundle.latestDraftRun()).isNull();
    }

    @Test
    @DisplayName("J4-F5: each mapping carries its application state — live, in a draft, pending or deferred")
    void mappingsCarryTheirApplicationState() {
        UUID modelId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        ModelMapping applied = new ModelMapping(UUID.randomUUID(), tenantId, modelId, "class",
                "http://ex/a", "http://ex/b", "Author", ModelMapping.ACCEPTED, null, userId, null, Instant.now());
        ModelMapping deferred = new ModelMapping(UUID.randomUUID(), tenantId, modelId, "join",
                "http://ex/c", "http://ex/d", "k|rel|forward", ModelMapping.ACCEPTED, null, userId, null, Instant.now());
        ModelMapping untouched = new ModelMapping(UUID.randomUUID(), tenantId, modelId, "class",
                "http://ex/e", "http://ex/f", "Venue", ModelMapping.SUGGESTED, null, userId, null, Instant.now());
        List<MappingApplication> applications = List.of(
                new MappingApplication(UUID.randomUUID(), tenantId, modelId, applied.id(),
                        MappingApplicationState.APPLIED, UUID.randomUUID(), versionId, null, Instant.now()),
                new MappingApplication(UUID.randomUUID(), tenantId, modelId, deferred.id(),
                        MappingApplicationState.DEFERRED, UUID.randomUUID(), null, "no join key", Instant.now()));

        ModelBundleDto bundle = getBundleWith(Set.of(), List.of(applied, deferred, untouched),
                new IntegrationModelService.IntegrationState(null, null, null, applications));

        assertThat(bundle.mappings()).satisfiesExactly(
                m -> {
                    assertThat(m.application().state()).isEqualTo("APPLIED");
                    assertThat(m.application().versionId()).isEqualTo(versionId);
                },
                m -> {
                    assertThat(m.application().state()).isEqualTo("DEFERRED");
                    assertThat(m.application().reason()).isEqualTo("no join key");
                },
                m -> assertThat(m.application()).isNull());
    }

    private Job job(String type, edu.upc.dtak.aran.core.domain.JobStatus status) {
        Job pending = Job.pending(UUID.randomUUID(), tenantId, projectId, type, Map.of(), userId, Instant.now());
        return new Job(pending.id(), tenantId, projectId, type, status, Map.of(), null, null, null, null, null,
                userId, pending.createdAt(), null, null);
    }

    @Test
    @DisplayName("review UA5: a compile still running rides on the bundle, so a reload re-attaches instead of starting another")
    void aRunningCompileRidesOnTheBundle() {
        Job running = job("model:incremental-integrate", edu.upc.dtak.aran.core.domain.JobStatus.RUNNING);
        when(jobService.listRecent(any(), eq(projectId), anyInt())).thenReturn(List.of(
                job("bootstrap", edu.upc.dtak.aran.core.domain.JobStatus.RUNNING),
                running,
                job("model:compile", edu.upc.dtak.aran.core.domain.JobStatus.SUCCEEDED)));

        ModelBundleDto bundle = getBundleWith(Set.of());

        assertThat(bundle.compileJob()).isNotNull();
        assertThat(bundle.compileJob().jobId()).isEqualTo(running.id());
        assertThat(bundle.compileJob().jobType()).isEqualTo("model:incremental-integrate");
        assertThat(bundle.compileJob().status()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("review UA5: a finished compile is not re-attached — only PENDING or RUNNING ones are")
    void aFinishedCompileIsNotReattached() {
        when(jobService.listRecent(any(), eq(projectId), anyInt())).thenReturn(List.of(
                job("model:compile", edu.upc.dtak.aran.core.domain.JobStatus.FAILED),
                job("model:compile", edu.upc.dtak.aran.core.domain.JobStatus.SUCCEEDED)));

        assertThat(getBundleWith(Set.of()).compileJob()).isNull();
    }

    @Test
    @DisplayName("S2-1: a project the caller cannot see is refused at submission — no job, no model read")
    void invisible_project_is_refused_before_any_job_is_submitted() {
        when(currentUser.require())
                .thenReturn(new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER));
        when(projectAccess.getVisibleProject(any(), eq(projectId)))
                .thenThrow(new ResourceNotFoundException("project not found: " + projectId));

        assertThatThrownBy(() -> controller.get(projectId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.compile(projectId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.discoverConstraints(projectId,
                new DiscoverConstraintsRequest(datasetId, classIri)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(jobService, modelService);
    }

    @Test
    @DisplayName("J7-F1: a promote is recorded with the version and the project it came from")
    void promoteIsAudited() {
        AuthenticatedUser caller = new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER);
        when(currentUser.require()).thenReturn(caller);
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(modelService.promoteRun(any(), eq(projectId), eq(runId), eq(userId)))
                .thenReturn(new edu.upc.dtak.aran.core.domain.GraphVersion(versionId, tenantId, 3,
                        "ACTIVE", Instant.now(), userId, Instant.now(), null));

        controller.promoteRun(projectId, runId);

        verify(audit).schemaPromoted(caller, versionId, 3, projectId);
    }

    @Test
    @DisplayName("J7-F1: a promote whose audit write fails still answers the promote it made")
    void promoteAuditFailureIsNotFatal() {
        AuthenticatedUser caller = new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER);
        when(currentUser.require()).thenReturn(caller);
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(modelService.promoteRun(any(), eq(projectId), eq(runId), eq(userId)))
                .thenReturn(new edu.upc.dtak.aran.core.domain.GraphVersion(versionId, tenantId, 3,
                        "ACTIVE", Instant.now(), userId, Instant.now(), null));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit insert failed"))
                .when(audit).schemaPromoted(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                        any());

        var response = controller.promoteRun(projectId, runId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("J7-F1: a promote that fails records nothing")
    void failedPromoteIsNotAudited() {
        when(currentUser.require())
                .thenReturn(new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER));
        UUID runId = UUID.randomUUID();
        when(modelService.promoteRun(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("only a DRAFT run can be promoted"));

        assertThatThrownBy(() -> controller.promoteRun(projectId, runId))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
    }
}

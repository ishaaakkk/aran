package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubmission;
import edu.upc.dtak.aran.app.web.dto.ModelDtos;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.AddConstraintRequest;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.AddMappingRequest;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.AddSourceRequest;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.ConstraintEnforcedRequest;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.MappingStatusRequest;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.ModelBundleDto;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.ModelConstraintDto;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.ModelMappingDto;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.ModelSourceDto;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.SuggestRequest;
import edu.upc.dtak.aran.app.web.dto.ModelDtos.WrapperRequest;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.ModelBundle;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.AuditService;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.service.JobService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * REST controller for a project's durable integration model (V16).
 *
 * <p>The model is the single owner of a project's alignments, per-class wrappers and
 * constraints. Integration is {@code POST /compile}, which runs on the job substrate and
 * produces a DRAFT {@code GraphVersion} — the request-body {@code integrate} operator path
 * is deprecated in favour of this.
 *
 * <p>Every endpoint asks {@link ProjectAccess} first (S2-1). The gate sits here, at the only
 * entry into {@link IntegrationModelService} that carries a caller, because the service's
 * project-scoped methods double as job bodies and internal dataset-lifecycle calls that run with
 * a tenant and no person; moving the check into them is D11 tranche 1's remaining half. For the
 * job endpoints this means the check runs at submission — a caller who cannot see the project
 * never gets a job.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/model")
public class IntegrationModelController {

    private final IntegrationModelService modelService;
    private final JobService jobService;
    private final CurrentUserResolver currentUser;
    private final ProjectAccess projectAccess;
    private final ObjectMapper objectMapper;
    private final GraphVersionPort versionPort;
    private final AuditService audit;

    public IntegrationModelController(IntegrationModelService modelService,
                                      JobService jobService,
                                      CurrentUserResolver currentUser,
                                      ProjectAccess projectAccess,
                                      ObjectMapper objectMapper,
                                      GraphVersionPort versionPort,
                                      AuditService audit) {
        this.modelService = modelService;
        this.jobService = jobService;
        this.currentUser = currentUser;
        this.projectAccess = projectAccess;
        this.objectMapper = objectMapper;
        this.versionPort = versionPort;
        this.audit = audit;
    }

    @GetMapping
    public ModelBundleDto get(@PathVariable UUID projectId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        TenantContext tenant = tenant(caller);
        // One read of the active graph for both answers, not one each — V49: this endpoint loads
        // a page, and the second round trip V42 added to it widened a frontend race far enough to
        // lose typed wrapper SQL. See IntegrationModelService.activeModelFacts.
        //
        // The bundle is read FIRST, keeping the order the two separate calls had. The two reads are
        // not in one transaction, so a promote landing between them is possible either way — and
        // this order is the one that errs safe. Graph-after-bundle can only make the active class
        // set NEWER than the constraint rows, so a class the promote just started serving is
        // reported live; graph-first could mark a constraint orphaned against a catalog that had
        // already moved on, which is exactly the false "this is not enforced" V42 exists to stop.
        ModelBundle bundle = modelService.getBundle(tenant, projectId);
        var active = modelService.activeModelFacts(tenant);
        var state = modelService.integrationState(tenant, projectId);
        return ModelBundleDto.from(bundle, objectMapper,
                active.integratedDatasetIds(), active.activeClassIris(),
                state, versionId -> versionPort.findById(tenant, versionId),
                runningCompile(tenant, projectId));
    }

    /** The job types {@link #compile} and {@link #incrementalIntegrate} submit. */
    static final Set<String> COMPILE_JOB_TYPES = Set.of("model:compile", "model:incremental-integrate");

    /**
     * How far back the bundle looks for a compile still in flight. A project's jobs are few and a
     * compile is long; a running one buried under this many newer jobs is not re-attached, and
     * the page then offers Rebuild as it did before (review UA5).
     */
    private static final int RUNNING_COMPILE_LOOKBACK = 50;

    /**
     * The project's compile still in flight, newest first — so a reload re-attaches to it instead
     * of re-enabling Rebuild and starting a second one (review UA5, audit J4-F5). The single
     * backend instance (D7) sweeps a dead instance's PENDING/RUNNING jobs at startup, so a
     * non-terminal job here is a live one.
     */
    private @org.jspecify.annotations.Nullable Job runningCompile(TenantContext tenant, UUID projectId) {
        return jobService.listRecent(tenant, projectId, RUNNING_COMPILE_LOOKBACK).stream()
                .filter(j -> COMPILE_JOB_TYPES.contains(j.jobType()) && !j.isTerminal())
                .findFirst()
                .orElse(null);
    }

    // ── sources ───────────────────────────────────────────────────────────────

    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ModelSourceDto> addSource(@PathVariable UUID projectId,
                                          @RequestBody AddSourceRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        return modelService.addSource(tenant(caller), projectId, body.datasetId())
                .stream().map(ModelSourceDto::from).toList();
    }

    @DeleteMapping("/sources/{datasetId}")
    public ResponseEntity<Void> removeSource(@PathVariable UUID projectId,
                                             @PathVariable UUID datasetId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        modelService.removeSource(tenant(caller), projectId, datasetId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/sources/{datasetId}/wrappers")
    public ResponseEntity<Void> setWrapper(@PathVariable UUID projectId,
                                           @PathVariable UUID datasetId,
                                           @RequestBody WrapperRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        modelService.setWrapper(tenant(caller), projectId, datasetId, body.classIri(), body.sql());
        return ResponseEntity.noContent().build();
    }

    // ── mappings ──────────────────────────────────────────────────────────────

    @PostMapping("/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelMappingDto addMapping(@PathVariable UUID projectId,
                                      @RequestBody AddMappingRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        return ModelMappingDto.from(
                modelService.addMapping(tenant(caller), projectId, body.kind().name().toLowerCase(),
                        body.sourceIri(), body.targetIri(), body.label(),
                        caller.userId(), body.comment()),
                objectMapper);
    }

    @PostMapping("/suggest")
    public List<ModelMappingDto> suggest(@PathVariable UUID projectId,
                                         @RequestBody SuggestRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        return modelService.suggest(tenant(caller), projectId,
                        body.leftDatasetId(), body.rightDatasetId(), caller.userId())
                .stream().map(m -> ModelMappingDto.from(m, objectMapper)).toList();
    }

    @PatchMapping("/mappings/{mappingId}")
    public ResponseEntity<Void> updateMapping(@PathVariable UUID projectId,
                                              @PathVariable UUID mappingId,
                                              @RequestBody MappingStatusRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        modelService.setMappingStatus(tenant(caller), projectId, mappingId, body.status().name());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/mappings/{mappingId}")
    public ResponseEntity<Void> deleteMapping(@PathVariable UUID projectId,
                                              @PathVariable UUID mappingId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        modelService.deleteMapping(tenant(caller), projectId, mappingId);
        return ResponseEntity.noContent().build();
    }

    // ── constraints ─────────────────────────────────────────────────────────────

    @PostMapping("/constraints")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelConstraintDto addConstraint(@PathVariable UUID projectId,
                                            @RequestBody AddConstraintRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        TenantContext tenant = tenant(caller);
        // Marked against the same active-catalog set the bundle read uses, rather than assumed
        // live: a constraint may legitimately be authored against a class the active schema does
        // not serve (validate-constraint accepts one it cannot find), and the created row must
        // describe itself the same way the next GET will.
        return ModelConstraintDto.from(
                modelService.addConstraint(tenant, projectId, body.classIri(), body.expression()),
                modelService.activeIntegratedClassIris(tenant));
    }

    @PatchMapping("/constraints/{constraintId}")
    public ResponseEntity<Void> updateConstraint(@PathVariable UUID projectId,
                                                 @PathVariable UUID constraintId,
                                                 @RequestBody ConstraintEnforcedRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        modelService.setConstraintEnforced(tenant(caller), projectId, constraintId, body.enforced());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/constraints/{constraintId}")
    public ResponseEntity<Void> deleteConstraint(@PathVariable UUID projectId,
                                                 @PathVariable UUID constraintId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        modelService.deleteConstraint(tenant(caller), projectId, constraintId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Run LIMA denial-constraint discovery for one enrolled class and persist the survivors as
     * unenforced, DISCOVERED-origin constraints (Phase 3B). Long-running like {@code compile} —
     * it downloads a snapshot and samples it — so it runs on the same job substrate rather than
     * blocking the request; there is no separate accept endpoint, a candidate is reviewed and
     * enforced through the existing {@code PATCH /constraints/{id}} above.
     */
    @PostMapping("/constraints/discover")
    public ResponseEntity<JobSubmission> discoverConstraints(@PathVariable UUID projectId,
                                                             @RequestBody ModelDtos.DiscoverConstraintsRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        TenantContext tenant = tenant(caller);
        UUID actor = caller.userId();
        Job job = jobService.submit(
                tenant, projectId, "model:discover-constraints",
                Map.of("projectId", projectId.toString(), "datasetId", body.datasetId().toString(),
                        "classIri", body.classIri()),
                actor,
                progress -> modelService.discoverConstraints(tenant, projectId, body.datasetId(), body.classIri()));
        return ResponseEntity.accepted().body(new JobSubmission(job.id()));
    }

    // ── compile ──────────────────────────────────────────────────────────────

    /** Rebuild the integration model — full re-integration of every enrolled source. */
    @PostMapping("/compile")
    public ResponseEntity<JobSubmission> compile(@PathVariable UUID projectId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        TenantContext tenant = tenant(caller);
        UUID actor = caller.userId();
        Job job = jobService.submit(
                tenant, projectId, "model:compile",
                Map.of("projectId", projectId.toString()), actor,
                progress -> modelService.rebuildToDraft(tenant, projectId, actor, progress));
        return ResponseEntity.accepted().body(new JobSubmission(job.id()));
    }

    /** Add one source incrementally on top of the model's promoted baseline. */
    @PostMapping("/incremental-integrate")
    public ResponseEntity<JobSubmission> incrementalIntegrate(@PathVariable UUID projectId,
                                                              @RequestBody AddSourceRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        TenantContext tenant = tenant(caller);
        UUID actor = caller.userId();
        UUID datasetId = body.datasetId();
        Job job = jobService.submit(
                tenant, projectId, "model:incremental-integrate",
                Map.of("projectId", projectId.toString(), "datasetId", datasetId.toString()), actor,
                progress -> modelService.incrementalIntegrateToDraft(tenant, projectId, datasetId, actor, progress));
        return ResponseEntity.accepted().body(new JobSubmission(job.id()));
    }

    /**
     * Model-aware promotion of a DRAFT integration run's draft version. Unlike the generic
     * schema-version promote, this updates run + application state and clears the rebuild flag.
     */
    @PostMapping("/runs/{runId}/promote")
    public ResponseEntity<ModelDtos.PromoteRunResponse> promoteRun(@PathVariable UUID projectId,
                                                          @PathVariable UUID runId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        GraphVersion promoted = modelService.promoteRun(
                tenant(caller), projectId, runId, caller.userId());
        // Audit J7-F1. Recorded here because promotion has three doors and no single owner yet
        // (D5 part A) — after promoteRun's own stale-marking, and never fatal (AfterTheFactAudit).
        // The project id is what lets this line reach the project's members' dashboards (UI-OD11).
        AfterTheFactAudit.record("promote of schema version " + promoted.versionNumber(),
                () -> audit.schemaPromoted(caller, promoted.id(), promoted.versionNumber(),
                        projectId));
        return ResponseEntity.ok(new ModelDtos.PromoteRunResponse(
                promoted.id().toString(),
                String.valueOf(promoted.versionNumber())));
    }

    private static TenantContext tenant(AuthenticatedUser caller) {
        return new TenantContext(caller.tenantId().toString());
    }
}

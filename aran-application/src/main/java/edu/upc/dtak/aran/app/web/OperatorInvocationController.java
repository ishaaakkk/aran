package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.security.TokenScopePolicy;
import edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubmission;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ParameterSpec;
import edu.upc.dtak.aran.core.service.JobFailureException;
import edu.upc.dtak.aran.core.service.JobService;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import edu.upc.dtak.aran.core.service.UsageService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for listing and invoking platform capability operators.
 *
 * <p>Acts as the secure, authenticated boundary mapping OIDC-resolved tenant contexts
 * to core Spring DI {@link OperatorOrchestrator} executions.
 */
@RestController
@RequestMapping("/api/operators")
public class OperatorInvocationController {

    private final OperatorOrchestrator orchestrator;
    private final CurrentUserResolver currentUser;
    private final JobService jobService;
    private final UsageService usage;

    /**
     * The query-engine operations that are a user running a query, as opposed to the catalog and
     * explain lookups the UI makes to render itself. Only these are metered: counting every
     * catalog fetch as an operation would make idle browsing look like consumption.
     */
    private static final java.util.Set<String> METERED_QUERY_OPERATIONS =
            java.util.Set.of("run", "run-oql");

    public OperatorInvocationController(OperatorOrchestrator orchestrator,
                                         CurrentUserResolver currentUser,
                                         JobService jobService,
                                         UsageService usage) {
        this.orchestrator = orchestrator;
        this.currentUser = currentUser;
        this.jobService = jobService;
        this.usage = usage;
    }

    /** Lists active operators as self-describing manifests. Any authenticated, tenant-resolved caller may read this. */
    @GetMapping
    public ResponseEntity<List<OperatorManifest>> listOperators() {
        currentUser.require(); // enforces JWT validity + tenant membership
        List<OperatorManifest> manifests = orchestrator.activeOperators().stream()
                .map(Operator::manifest)
                .toList();
        return ResponseEntity.ok(manifests);
    }

    /**
     * Invokes an operator capability. The tenant and authenticated user-id both come
     * from the caller's resolved identity (never from the request body).
     */
    @PostMapping("/{operatorId}/invoke")
    public ResponseEntity<OperatorResult> invoke(
            @PathVariable String operatorId,
            @RequestBody InvocationRequest body) {

        AuthenticatedUser caller = currentUser.require();
        // The scope check in the authentication filter can only see the path, and this one path
        // carries every query-engine operation. This is where the operation is known.
        TokenScopePolicy.requireOperationAllowed(operatorId, body.operation());

        OperatorRequest request = new OperatorRequest(
                caller.tenantId().toString(),
                caller.userId(),
                body.operation(),
                body.parameters());

        orchestrator.find(operatorId).ifPresent(op -> validateParameters(op, body));

        long started = System.currentTimeMillis();
        OperatorResult result = orchestrator.execute(operatorId, request);

        // The workbench's read path. Recorded here rather than inside the operator because the
        // operator is also what QueryExportService and AranMeta drive, and those record their own
        // kinds — metering in the operator would count one export as both an EXPORT and a QUERY.
        if ("query-engine".equals(operatorId)
                && METERED_QUERY_OPERATIONS.contains(body.operation())
                && result.success()) {
            usage.record(caller, UsageKind.QUERY, System.currentTimeMillis() - started,
                    rowCountOrNull(result), null);
        }

        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(422).body(result);
    }

    /**
     * The row count an operator reported, or null when it reported none.
     *
     * <p>Null rather than zero, so "this operation does not count rows" cannot be averaged in
     * with "this query matched nothing".
     */
    private static @Nullable Long rowCountOrNull(OperatorResult result) {
        String raw = result.data().get("rowCount");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Submits the same invocation as a durable job and returns immediately.
     *
     * <p>Same body as {@code /invoke}, same tenant and actor resolution — the only
     * difference is that the work runs on the job substrate, so its stages, result
     * and error survive the request. Poll {@code GET /api/jobs/{id}} or subscribe
     * to {@code GET /api/jobs/{id}/events}.
     *
     * <p>An operator that fails (a non-success {@link OperatorResult}) fails the
     * job, carrying its {@code errorCode} onto the row rather than burying it.
     */
    @PostMapping("/{operatorId}/submit")
    public ResponseEntity<JobSubmission> submit(
            @PathVariable String operatorId,
            @RequestBody InvocationRequest body) {

        AuthenticatedUser caller = currentUser.require();
        TokenScopePolicy.requireOperationAllowed(operatorId, body.operation());

        OperatorRequest request = new OperatorRequest(
                caller.tenantId().toString(),
                caller.userId(),
                body.operation(),
                body.parameters());

        Operator operator = orchestrator.find(operatorId)
                .orElseThrow(() -> new ResourceNotFoundException("No active operator with id '" + operatorId + "'"));
        validateParameters(operator, body);

        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        UUID projectId = parseProjectId(body.parameters().get("projectId"));

        Job job = jobService.submit(
                tenant,
                projectId,
                "operator:" + operatorId + ":" + body.operation(),
                body.parameters(),
                caller.userId(),
                progress -> {
                    OperatorResult result = orchestrator.execute(operatorId, request, progress);
                    if (!result.success()) {
                        throw new JobFailureException(
                                result.errorCode() != null ? result.errorCode() : "OPERATOR_FAILED",
                                result.errorMessage() != null ? result.errorMessage() : "operator reported failure");
                    }
                    return result.data();
                });

        return ResponseEntity.accepted().body(new JobSubmission(job.id()));
    }

    /**
     * Rejects a request that omits a required parameter for the operation named in
     * the body, per the operator's own {@link OperatorManifest}. An operation name
     * the manifest does not list is left to the operator itself (e.g. the
     * {@code UNSUPPORTED_OPERATION} failure), since the manifest is documentation,
     * not an exhaustive gate.
     */
    private void validateParameters(Operator operator, InvocationRequest body) {
        operator.manifest().operations().stream()
                .filter(op -> op.name().equals(body.operation()))
                .findFirst()
                .ifPresent(spec -> {
                    for (ParameterSpec param : spec.parameters()) {
                        if (param.required()) {
                            String value = body.parameters().get(param.name());
                            if (value == null || value.isBlank()) {
                                throw new IllegalArgumentException(
                                        "Required parameter '" + param.name() + "' is missing for operation '"
                                                + body.operation() + "'");
                            }
                        }
                    }
                });
    }

    /**
     * Operator parameters are untyped strings; a {@code projectId} among them is
     * what scopes the job to a project. Anything unparseable is simply not a
     * project id — the job still runs, it just is not filed under one.
     */
    private static @Nullable UUID parseProjectId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Request body for an operator invocation. */
    public record InvocationRequest(String operation, Map<String, String> parameters) {
    }
}

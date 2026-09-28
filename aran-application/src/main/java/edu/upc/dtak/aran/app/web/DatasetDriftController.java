package edu.upc.dtak.aran.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.drift.DriftReport;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.service.SchemaDriftService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Schema drift for one dataset: what would change, what it breaks, and repairing renames.
 *
 * <p>The read endpoint runs the bootstrapping operator's {@code drift-check}, which introspects
 * the current snapshot and compares it against the recorded baseline <em>without writing
 * anything</em>. Safe to call from a list view.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/repositories/{repositoryId}/datasets/{datasetId}/drift")
public class DatasetDriftController {

    private final SchemaDriftService driftService;
    private final DataRepositoryService repositoryService;
    private final OperatorOrchestrator orchestrator;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper objectMapper;

    public DatasetDriftController(SchemaDriftService driftService,
                                  DataRepositoryService repositoryService,
                                  OperatorOrchestrator orchestrator,
                                  CurrentUserResolver currentUser,
                                  ObjectMapper objectMapper) {
        this.driftService = driftService;
        this.repositoryService = repositoryService;
        this.orchestrator = orchestrator;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    /** One IRI that moved, as confirmed by the user. */
    public record RenameRequest(@NotBlank String fromIri, @NotBlank String toIri) {}

    /** The renames to apply. Deliberately explicit — the server never repairs on its own. */
    public record RepairRequest(@Valid List<RenameRequest> renames) {}

    public record RepairResponse(int rewritten, List<UUID> models) {}

    public record FingerprintCapture(UUID id, String hash, String snapshotId,
                                     String capturedVia, Instant capturedAt) {}

    /**
     * Check the dataset's current snapshot against its recorded baseline.
     *
     * <p>Writes no graph and no fingerprint, but a BREAKING report marks the dataset
     * {@code DRIFTED} (the operator's {@code markDrifted}) — the UI folds that into the row it
     * shows. Costs a snapshot download and an introspection, so it is a request the UI makes on
     * demand rather than on every list render.
     */
    @GetMapping
    public DriftReport check(@PathVariable UUID projectId,
                             @PathVariable UUID repositoryId,
                             @PathVariable UUID datasetId,
                             @RequestParam(required = false) String filenameHint) {
        AuthenticatedUser caller = currentUser.require();
        repositoryService.getRepository(caller, projectId, repositoryId);

        Map<String, String> params = filenameHint == null || filenameHint.isBlank()
                ? Map.of("datasetId", datasetId.toString())
                : Map.of("datasetId", datasetId.toString(), "filenameHint", filenameHint);
        OperatorResult result = orchestrator.execute("bootstrapping", new OperatorRequest(
                caller.tenantId().toString(), caller.userId(), "drift-check", params));

        if (!result.success()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    result.errorMessage() != null ? result.errorMessage() : "drift check failed");
        }
        String reportJson = result.data().get("driftReport");
        if (reportJson == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "drift check returned no report");
        }
        try {
            return objectMapper.readValue(reportJson, DriftReport.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "drift report could not be read");
        }
    }

    /** The dataset's recent fingerprint captures, newest first. */
    @GetMapping("/history")
    public List<FingerprintCapture> history(@PathVariable UUID projectId,
                                            @PathVariable UUID repositoryId,
                                            @PathVariable UUID datasetId) {
        AuthenticatedUser caller = currentUser.require();
        repositoryService.getRepository(caller, projectId, repositoryId);
        return driftService.history(tenantOf(caller), datasetId).stream()
                .map(f -> new FingerprintCapture(f.id(), f.hash(), f.snapshotId(),
                        f.capturedVia().name(), f.capturedAt()))
                .toList();
    }

    /**
     * Repoint mappings at renamed IRIs.
     *
     * <p>The renames come from the client because they are a <em>decision</em>: the diff proposes
     * them, a human confirms them. Repointing a mapping at the wrong column is worse than the
     * dangling reference it would be fixing, so this is never applied automatically.
     */
    @PostMapping("/repair")
    @ResponseStatus(HttpStatus.OK)
    public RepairResponse repair(@PathVariable UUID projectId,
                                 @PathVariable UUID repositoryId,
                                 @PathVariable UUID datasetId,
                                 @Valid @RequestBody RepairRequest request) {
        AuthenticatedUser caller = currentUser.require();
        repositoryService.getRepository(caller, projectId, repositoryId);

        if (request.renames() == null || request.renames().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no renames supplied");
        }
        List<SchemaDriftService.IriRename> renames = request.renames().stream()
                .map(r -> new SchemaDriftService.IriRename(r.fromIri(), r.toIri()))
                .toList();

        SchemaDriftService.RepairResult result =
                driftService.repairRenames(tenantOf(caller), renames);
        return new RepairResponse(result.rewritten(), result.models());
    }

    private static TenantContext tenantOf(AuthenticatedUser caller) {
        return new TenantContext(caller.tenantId().toString());
    }
}

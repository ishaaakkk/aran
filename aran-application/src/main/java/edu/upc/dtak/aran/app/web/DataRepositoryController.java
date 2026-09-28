package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.DataRepositoryDtos.DataRepositoryRequest;
import edu.upc.dtak.aran.app.web.dto.DataRepositoryDtos.DataRepositoryResponse;
import edu.upc.dtak.aran.core.domain.ConnectionCheck;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/repositories")
public class DataRepositoryController {

    private final DataRepositoryService repositoryService;
    private final CurrentUserResolver currentUser;
    /** T6: only the catalogue knows which of a tap's settings are secret, and so must be masked. */
    private final SingerTapCatalog taps;

    public DataRepositoryController(DataRepositoryService repositoryService,
                                    CurrentUserResolver currentUser,
                                    SingerTapCatalog taps) {
        this.repositoryService = repositoryService;
        this.currentUser = currentUser;
        this.taps = taps;
    }

    /** Every repository of the project, each with its last connection check — none is dialed. */
    @GetMapping
    public List<DataRepositoryResponse> listRepositories(
            @PathVariable UUID projectId) {
        AuthenticatedUser caller = currentUser.require();
        return repositoryService.listRepositoriesWithLastCheck(caller, projectId).stream()
                .map(listed -> DataRepositoryResponse.from(listed.repository(), taps, listed.lastCheck()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<DataRepositoryResponse> createRepository(
            @PathVariable UUID projectId,
            @Valid @RequestBody DataRepositoryRequest request) {
        AuthenticatedUser caller = currentUser.require();
        UUID repoId = UUID.randomUUID();
        DataRepository repository = request.toDomain(repoId, projectId, caller.tenantId());
        DataRepository created = repositoryService.createRepository(caller, projectId, repository);

        return ResponseEntity
                .created(URI.create("/api/projects/" + projectId + "/repositories/" + created.id()))
                .body(DataRepositoryResponse.from(created, taps));
    }

    @PutMapping("/{repositoryId}")
    public ResponseEntity<DataRepositoryResponse> updateRepository(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody DataRepositoryRequest request) {
        AuthenticatedUser caller = currentUser.require();
        DataRepository repository = request.toDomain(repositoryId, projectId, caller.tenantId());
        DataRepository updated = repositoryService.updateRepository(caller, projectId, repository);
        return ResponseEntity.ok(DataRepositoryResponse.from(updated, taps));
    }

    @DeleteMapping("/{repositoryId}")
    public ResponseEntity<Void> deleteRepository(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId) {
        AuthenticatedUser caller = currentUser.require();
        repositoryService.deleteRepository(caller, projectId, repositoryId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Test settings that are not stored yet — the register dialog's test. A masked credential is
     * refused rather than dialed; a stored repository is tested through the route below.
     */
    @PostMapping("/test-connection")
    public ConnectionTestResponse testConnection(
            @PathVariable UUID projectId,
            @Valid @RequestBody DataRepositoryRequest request) {
        AuthenticatedUser caller = currentUser.require();
        // Since it's a test connection, we use a random UUID for the domain mapping
        DataRepository repository = request.toDomain(UUID.randomUUID(), projectId, caller.tenantId());
        DataRepositoryService.ConnectionTestResult result = repositoryService.testConnection(caller, projectId, repository);
        return new ConnectionTestResponse(result.success(), result.message(), result.details(), Instant.now());
    }

    /**
     * Test a stored repository with its stored credentials (UI audit J2-F1).
     *
     * <p>Without a body: the repository exactly as stored — the repositories list's test, recorded
     * as its last check. With a body: the edit dialog's unsaved settings, each masked credential
     * taken from the stored repository under the same redirect rule an update applies (a kept
     * credential may not travel to a new host); not recorded, since nothing was saved.
     */
    @PostMapping("/{repositoryId}/test-connection")
    public ConnectionTestResponse testStoredConnection(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody(required = false) @Nullable DataRepositoryRequest edits) {
        AuthenticatedUser caller = currentUser.require();
        DataRepository edited = edits == null ? null : edits.toDomain(repositoryId, projectId, caller.tenantId());
        ConnectionCheck check = repositoryService.testStoredConnection(caller, projectId, repositoryId, edited);
        return new ConnectionTestResponse(check.success(), check.message(), check.details(), check.testedAt());
    }

    /** @param testedAt when the probe ran */
    public record ConnectionTestResponse(boolean success, String message, @Nullable String details,
                                         Instant testedAt) {
    }
}

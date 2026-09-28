package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.DatasetRefreshService;
import edu.upc.dtak.aran.core.source.RefreshPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How often a materialized dataset is re-copied from its upstream, and refreshing one now.
 *
 * <p>Separate from {@code DatasetController} because it answers a different question about a
 * dataset — freshness rather than definition — and because a dataset with no upstream has no
 * representation here at all, which is the honest wire shape for "an uploaded file cannot be
 * refreshed".
 */
@RestController
@NullMarked
@RequestMapping("/api/projects/{projectId}/repositories/{repositoryId}/datasets")
public class DatasetRefreshController {

    private final DatasetRefreshService refreshes;
    private final CurrentUserResolver currentUser;

    public DatasetRefreshController(DatasetRefreshService refreshes, CurrentUserResolver currentUser) {
        this.refreshes = refreshes;
        this.currentUser = currentUser;
    }

    /**
     * Every refresh policy under this repository.
     *
     * <p>Keyed by dataset id and returned as a list rather than merged into the dataset payload:
     * most datasets have no entry, and adding a null-heavy block to every dataset response to say
     * so would make "not refreshable" and "refreshable, never refreshed" look the same.
     */
    @GetMapping("/refresh-policies")
    public List<RefreshResponse> listPolicies(@PathVariable UUID projectId,
                                              @PathVariable UUID repositoryId) {
        AuthenticatedUser caller = currentUser.require();
        Instant now = Instant.now();
        return refreshes.findAll(caller, projectId, repositoryId).stream()
                .map(r -> RefreshResponse.from(r, now))
                .toList();
    }

    /** This dataset's policy, or 404 if it has no upstream to refresh from. */
    @GetMapping("/{datasetId}/refresh-policy")
    public ResponseEntity<RefreshResponse> getPolicy(@PathVariable UUID projectId,
                                                     @PathVariable UUID repositoryId,
                                                     @PathVariable UUID datasetId) {
        AuthenticatedUser caller = currentUser.require();
        return refreshes.find(caller, projectId, repositoryId, datasetId)
                .map(r -> ResponseEntity.ok(RefreshResponse.from(r, Instant.now())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{datasetId}/refresh-policy")
    public RefreshResponse setPolicy(@PathVariable UUID projectId,
                                     @PathVariable UUID repositoryId,
                                     @PathVariable UUID datasetId,
                                     @Valid @RequestBody RefreshPolicyRequest request) {
        AuthenticatedUser caller = currentUser.require();
        return RefreshResponse.from(
                refreshes.setPolicy(caller, projectId, repositoryId, datasetId, request.toDomain()),
                Instant.now());
    }

    /** Refresh now. Returns the job doing the work — the copy and bootstrap take a while. */
    @PostMapping("/{datasetId}/refresh")
    public ResponseEntity<RefreshStartedResponse> refreshNow(@PathVariable UUID projectId,
                                                             @PathVariable UUID repositoryId,
                                                             @PathVariable UUID datasetId) {
        AuthenticatedUser caller = currentUser.require();
        Job job = refreshes.refreshNow(caller, projectId, repositoryId, datasetId);
        return ResponseEntity.accepted().body(new RefreshStartedResponse(job.id()));
    }

    /**
     * @param policy         {@code MANUAL} or {@code EVERY}
     * @param intervalSeconds required for {@code EVERY}, ignored otherwise. Seconds rather than a
     *                        cron expression: an interval is what the domain models, and accepting
     *                        cron would promise alignment to wall-clock times that a poll-and-claim
     *                        scheduler does not provide
     */
    public record RefreshPolicyRequest(@NotBlank String policy, @Nullable Long intervalSeconds) {

        RefreshPolicy toDomain() {
            return switch (policy.toUpperCase(java.util.Locale.ROOT)) {
                case "MANUAL" -> new RefreshPolicy.Manual();
                case "EVERY" -> new RefreshPolicy.Every(Duration.ofSeconds(
                        intervalSeconds == null
                                ? throwMissingInterval()
                                : intervalSeconds));
                default -> throw new IllegalArgumentException(
                        "unknown refresh policy '" + policy + "' (expected MANUAL or EVERY)");
            };
        }

        private static long throwMissingInterval() {
            throw new IllegalArgumentException("intervalSeconds is required when policy is EVERY");
        }
    }

    /**
     * @param stalenessSeconds how old the copy currently being served is, or null if it has never
     *                         been refreshed. Computed here rather than left to the client so that
     *                         "never" and "zero seconds old" cannot be confused by a UI that
     *                         defaults a missing number to 0
     */
    public record RefreshResponse(
            UUID datasetId,
            String sourceTable,
            String policy,
            @Nullable Long intervalSeconds,
            @Nullable Instant lastRefreshAt,
            @Nullable Instant nextRunAt,
            @Nullable Long stalenessSeconds,
            @Nullable String lastError) {

        static RefreshResponse from(DatasetRefresh refresh, Instant now) {
            boolean scheduled = refresh.policy() instanceof RefreshPolicy.Every;
            return new RefreshResponse(
                    refresh.datasetId(),
                    refresh.sourceTable(),
                    scheduled ? "EVERY" : "MANUAL",
                    scheduled ? ((RefreshPolicy.Every) refresh.policy()).interval().toSeconds() : null,
                    refresh.lastRefreshAt(),
                    refresh.nextRunAt(),
                    refresh.stalenessAt(now).map(Duration::toSeconds).orElse(null),
                    refresh.lastError());
        }
    }

    public record RefreshStartedResponse(UUID jobId) {}
}

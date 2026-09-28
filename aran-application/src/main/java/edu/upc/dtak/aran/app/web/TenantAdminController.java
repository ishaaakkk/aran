package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.AuditEvent;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import edu.upc.dtak.aran.core.service.StorageService;
import edu.upc.dtak.aran.core.service.TenantAdminOverviewService;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The tenant owner's admin surface — read-only over the records it displays.
 *
 * <p>Member writes live on {@code /api/tenant/members}; this is what an owner *looks at*. Keeping
 * the two apart is the same discipline the service documents: an admin screen that can edit the
 * history it displays has lost the property that made it worth having.
 *
 * <p>The one write here, revoking a token, is not an edit of history: a credential is a live
 * authority, not a record of what happened, and the revocation itself is appended to Activity
 * (audit J7-F8). Nothing here can alter or remove an audit row, a usage figure or a sample.
 *
 * <p>No route takes a tenant id, because an owner only ever administers their own.
 */
@RestController
@RequestMapping("/api/tenant/admin")
@NullMarked
public class TenantAdminController {

    private final TenantAdminOverviewService overview;
    private final StorageService storage;
    private final ApiTokenService tokens;
    private final CurrentUserResolver currentUser;

    public TenantAdminController(TenantAdminOverviewService overview,
                                 StorageService storage,
                                 ApiTokenService tokens,
                                 CurrentUserResolver currentUser) {
        this.overview = overview;
        this.storage = storage;
        this.tokens = tokens;
        this.currentUser = currentUser;
    }

    /**
     * @param days the window every rate is measured over. Clamped to a year: a longer window
     *             is a reporting question, not a dashboard one, and would scan the whole table.
     */
    @GetMapping("/overview")
    public TenantAdminOverviewService.Overview overview(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        int clamped = Math.max(1, Math.min(days, 365));
        return overview.overview(currentUser.require(), Duration.ofDays(clamped));
    }

    @GetMapping("/tokens")
    public List<TenantAdminOverviewService.TokenSummary> tokens() {
        return overview.tokens(currentUser.require());
    }

    /**
     * Revoke one member's token — the owner's door for a credential leaked while its holder is
     * away (audit J7-F8). Owner-gated and audited in {@link ApiTokenService#revokeAsOwner};
     * idempotent, so a second click answers 204 like the first.
     */
    @DeleteMapping("/tokens/{tokenId}")
    public ResponseEntity<Void> revokeToken(@PathVariable UUID tokenId) {
        tokens.revokeAsOwner(currentUser.require(), tokenId);
        return ResponseEntity.noContent().build();
    }

    /**
     * What the tenant is holding, read live from the lake.
     *
     * <p>Not served from the newest sample: someone opening this is usually asking whether they
     * are about to fill the disk, and yesterday's number is the wrong answer — especially right
     * after the upload that made them look.
     */
    @GetMapping("/storage")
    public StorageService.StorageReport storage() {
        return storage.read(currentUser.require());
    }

    @GetMapping("/activity")
    public List<AuditEvent> activity(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return overview.activity(currentUser.require(), limit);
    }
}

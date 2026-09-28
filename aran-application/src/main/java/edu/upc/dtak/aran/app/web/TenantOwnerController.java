package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.AdminDtos.AddTenantMemberRequest;
import edu.upc.dtak.aran.app.web.dto.AdminDtos.LicenseSummaryResponse;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.licensing.UsageCounter;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import edu.upc.dtak.aran.core.service.TenantOwnerService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-owner endpoints — the user-facing counterpart to
 * {@link PlatformAdminController}. These exist in both deployment modes.
 *
 * <p>Authorization runs through {@link CurrentUserResolver#require()} like
 * every other tenant-scoped controller; the tenant-OWNER role check itself
 * lives in {@link TenantOwnerService}, not here, so it applies regardless of
 * transport.
 *
 * <p>Note the path: {@code /api/tenants/{id}} where {@code id} must match
 * the caller's resolved tenant id. The id in the URL is required for HATEOAS
 * cleanliness, but it is not the source of truth — the JWT is. A mismatch
 * yields 403.
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantOwnerController {

    private final TenantOwnerService tenantOwner;
    private final LicenseService licensing;
    private final UsageCounter usage;
    private final CurrentUserResolver currentUser;

    public TenantOwnerController(TenantOwnerService tenantOwner,
                                 LicenseService licensing,
                                 UsageCounter usage,
                                 CurrentUserResolver currentUser) {
        this.tenantOwner = tenantOwner;
        this.licensing = licensing;
        this.usage = usage;
        this.currentUser = currentUser;
    }

    /**
     * {@code GET /api/tenants/{id}/license} — the active license + current
     * usage. Visible to any tenant member, not just OWNERs: knowing how many
     * users / projects exist and what the limits are is operational info,
     * not privileged.
     */
    @GetMapping("/{tenantId}/license")
    public LicenseSummaryResponse getLicense(@PathVariable UUID tenantId) {
        AuthenticatedUser caller = requireMatchingTenant(tenantId);
        var entitlements = licensing.entitlementsFor(caller.tenantId());
        return LicenseSummaryResponse.from(
                entitlements,
                usage.countUsers(caller.tenantId()),
                usage.countProjects(caller.tenantId())
        );
    }

    /**
     * {@code POST /api/tenants/{id}/members} — tenant {@code OWNER}s add new
     * members. The role check lives in the service.
     */
    @PostMapping("/{tenantId}/members")
    public ResponseEntity<Membership> addMember(@PathVariable UUID tenantId,
                                                @Valid @RequestBody AddTenantMemberRequest body) {
        AuthenticatedUser caller = requireMatchingTenant(tenantId);
        Membership m = tenantOwner.addMember(caller, body.subject(), body.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(m);
    }

    /**
     * {@code DELETE /api/tenants/{id}/membership/me} — the "leave my own
     * membership" path. Refuses if the caller is the last OWNER (handled in
     * the service).
     */
    @DeleteMapping("/{tenantId}/membership/me")
    public ResponseEntity<Void> leaveTenant(@PathVariable UUID tenantId) {
        AuthenticatedUser caller = requireMatchingTenant(tenantId);
        tenantOwner.leaveCurrentTenant(caller);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedUser requireMatchingTenant(UUID urlTenantId) {
        AuthenticatedUser caller = currentUser.require();
        if (!caller.tenantId().equals(urlTenantId)) {
            // URL tenant id differs from the JWT's tenant — refuse rather
            // than silently letting the URL win. The JWT is authoritative.
            throw new AccessDeniedException(
                    "tenant in URL does not match the tenant in your session");
        }
        return caller;
    }
}

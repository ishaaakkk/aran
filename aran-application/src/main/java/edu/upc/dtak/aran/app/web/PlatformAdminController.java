package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.web.dto.AdminDtos.AdminAddMemberRequest;
import edu.upc.dtak.aran.app.web.dto.AdminDtos.CreateTenantRequest;
import edu.upc.dtak.aran.app.web.dto.AdminDtos.TenantResponse;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.service.TenantAdminService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-administration API — the cloud-only paths for onboarding tenants
 * and seeding them with their initial OWNER.
 *
 * <p>Exposed only when a {@link LicenseAdminPort} bean is present (i.e. cloud
 * mode). In self-hosted mode there is no license-write side, so this
 * controller does not load and the endpoints return 404 — exactly the
 * right behaviour: tenant creation is not a customer-running-instance
 * operation.
 *
 * <p>Authorization: every endpoint requires the {@code platform-admin}
 * Keycloak realm role. The {@code KeycloakRoleConverter} flattens
 * {@code realm_access.roles} into {@code ROLE_} + the <em>upper-cased</em> role
 * name, and {@code hasRole} compares exactly, so the expression must name
 * {@code PLATFORM-ADMIN}: written as {@code 'platform-admin'} it looked for
 * {@code ROLE_platform-admin} and could never pass (S9-3).
 * {@code PlatformAdminAuthorizationTest} evaluates this annotation against the
 * converter's real output, so the two cannot drift apart again.
 *
 * <p>The controller does <em>not</em> go through
 * {@code CurrentUserResolver.require()}: platform admins act <em>across</em>
 * tenants, so requiring a tenant membership would be wrong. The {@code @PreAuthorize}
 * is the gate.
 */
@Configuration
@ConditionalOnBean(LicenseAdminPort.class)
public class PlatformAdminController {

    @RestController
    @RequestMapping("/api/admin/tenants")
    @PreAuthorize("hasRole('PLATFORM-ADMIN')")
    static class TenantsAdminEndpoints {

        private final TenantAdminService tenantAdmin;

        TenantsAdminEndpoints(TenantAdminService tenantAdmin) {
            this.tenantAdmin = tenantAdmin;
        }

        /** {@code POST /api/admin/tenants} — create a tenant + license + first owner. */
        @PostMapping
        ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody CreateTenantRequest body) {
            Tenant tenant = tenantAdmin.createTenant(
                    body.slug(), body.name(), body.plan(), body.firstOwnerSubject());
            return ResponseEntity
                    .created(URI.create("/api/admin/tenants/" + tenant.id()))
                    .body(TenantResponse.from(tenant));
        }

        /** {@code POST /api/admin/tenants/{id}/members} — admin-driven member add. */
        @PostMapping("/{tenantId}/members")
        ResponseEntity<Membership> addMember(@PathVariable UUID tenantId,
                                             @Valid @RequestBody AdminAddMemberRequest body) {
            Membership m = tenantAdmin.addMember(tenantId, body.subject(), body.role());
            return ResponseEntity.created(URI.create(
                    "/api/admin/tenants/" + tenantId + "/members/" + m.userId())).body(m);
        }
    }
}

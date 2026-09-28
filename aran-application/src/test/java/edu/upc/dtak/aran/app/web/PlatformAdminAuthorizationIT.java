package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S9-3 end to end: {@code POST /api/admin/tenants} through the real filter chain and method
 * security, with the caller's authorities produced by the production {@code KeycloakRoleConverter}
 * (see {@link JwtTestHelper}). The route had only ever been tested in the refusing direction,
 * which a gate that can never open passes. {@code PlatformAdminAuthorizationTest} pins the same
 * gate without Docker.
 */
@DisplayName("Platform-admin gate — the realm role opens it, a tenant OWNER does not")
class PlatformAdminAuthorizationIT extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired LicenseAdminPort licenseAdmin;

    /** A signed-in user with no membership yet — the only kind that can become a first owner. */
    private String signedInUserWithoutTenant(String suffix) {
        String subject = "first-owner-" + suffix;
        users.save(new User(UUID.randomUUID(), subject, subject + "@example.com",
                "First Owner", Instant.now()));
        return subject;
    }

    private static String createTenantBody(String slug, String firstOwnerSubject) {
        return """
                {"slug": "%s", "name": "Tenant %s", "plan": "FREE", "firstOwnerSubject": "%s"}
                """.formatted(slug, slug, firstOwnerSubject);
    }

    @Test
    @DisplayName("a caller with the platform-admin realm role creates a tenant (201)")
    void platformAdminReachesTheEndpoint() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = "onboarded-" + suffix;
        String firstOwner = signedInUserWithoutTenant(suffix);

        mockMvc.perform(post("/api/admin/tenants")
                        .with(JwtTestHelper.platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantBody(slug, firstOwner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug));

        Tenant created = tenants.findBySlug(slug).orElseThrow();
        assertThat(tenants.findMembershipsForTenant(created.id()))
                .extracting(Membership::role)
                .containsExactly(TenantRole.OWNER);
    }

    @Test
    @DisplayName("a tenant OWNER without the realm role is refused (403) and nothing is created")
    void plainOwnerIsRefused() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "owned-" + suffix, "Owned " + suffix, Instant.now()));
        licenseAdmin.issueInitialLicense(tenantId, LicensePlan.PRO);
        String ownerSubject = "owner-" + suffix;
        User owner = users.save(new User(UUID.randomUUID(), ownerSubject,
                ownerSubject + "@example.com", "Owner", Instant.now()));
        tenants.saveMembership(new Membership(
                UUID.randomUUID(), owner.id(), tenantId, TenantRole.OWNER, Instant.now()));

        String slug = "refused-" + suffix;
        mockMvc.perform(post("/api/admin/tenants")
                        .with(JwtTestHelper.subject(ownerSubject, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantBody(slug, signedInUserWithoutTenant(suffix))))
                .andExpect(status().isForbidden());

        assertThat(tenants.findBySlug(slug)).isEmpty();
    }
}

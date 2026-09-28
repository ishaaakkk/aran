package edu.upc.dtak.aran.app;

import edu.upc.dtak.aran.core.domain.TenantRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.security.KeycloakRoleConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/**
 * Test helper for building mock JWTs that satisfy {@link CurrentUserResolver}.
 *
 * <p>The resolver requires three claims:
 * <ul>
 *   <li>{@code sub} — the Keycloak user subject</li>
 *   <li>{@code email} — the user's email</li>
 *   <li>{@code name} — the user's display name</li>
 *   <li>{@code tenant_id} — the active tenant UUID (as a string)</li>
 *   <li>{@code realm_access.roles} — for {@code @PreAuthorize} checks</li>
 * </ul>
 *
 * <p>Usage in a MockMvc test:
 * <pre>{@code
 * mockMvc.perform(
 *     get("/api/projects")
 *         .with(JwtTestHelper.alice(tenantId))
 * )
 * }</pre>
 */
public final class JwtTestHelper {

    public static final String ALICE_SUBJECT   = "00000000-0000-0000-0000-000000000002";
    public static final String ALICE_EMAIL     = "alice@example.com";
    public static final String ALICE_NAME      = "Alice Example";

    private JwtTestHelper() {
    }

    /**
     * A JWT for alice in the given tenant, with the OWNER role.
     * Use for any tenant-scoped request that requires a valid caller.
     */
    public static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            alice(UUID tenantId) {
        return jwt(ALICE_SUBJECT, ALICE_EMAIL, ALICE_NAME,
                tenantId, TenantRole.OWNER, List.of("platform-admin"));
    }

    /**
     * A JWT for a generic member (not owner, not platform-admin).
     * Use for requests that should succeed for regular members.
     *
     * <p>The subject is random, so the caller has <em>no</em> membership row and is rejected at
     * {@code CurrentUserResolver}'s membership check — before any role gate is reached. To test a
     * role gate, seed a user + MEMBER membership and use {@link #subject(String, UUID)} instead.
     */
    public static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            memberUser(UUID tenantId) {
        String subject = "member-" + UUID.randomUUID();
        return jwt(subject, subject + "@example.com", "Test Member",
                tenantId, TenantRole.MEMBER, List.of());
    }

    /**
     * A JWT for a specific, already-seeded subject in the given tenant, with no realm roles.
     *
     * <p>The caller's tenant role comes from the {@code membership} row that
     * {@code CurrentUserResolver} looks up — never from the token — so the seeded membership is
     * what decides whether an OWNER-only endpoint accepts this caller.
     */
    public static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            subject(String subject, UUID tenantId) {
        return jwt(subject, subject + "@example.com", "Seeded User",
                tenantId, TenantRole.MEMBER, List.of());
    }

    /**
     * A JWT for a user with no tenant membership.
     * Use for requests that should fail with 403 at the membership check.
     */
    public static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            noTenantUser() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(builder -> builder
                        .subject("no-tenant-subject")
                        .claim("email", "nobody@example.com")
                        .claim("name", "No Tenant")
                        // No tenant_id claim → CurrentUserResolver throws AccessDeniedException
                        .expiresAt(Instant.now().plusSeconds(3600)));
    }

    /**
     * A JWT for a caller holding the {@code platform-admin} realm role and no tenant membership —
     * the shape of an operator who onboards tenants rather than working inside one.
     */
    public static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            platformAdmin() {
        String subject = "platform-admin-" + UUID.randomUUID();
        return jwt(subject, subject + "@example.com", "Platform Admin",
                UUID.randomUUID(), TenantRole.MEMBER, List.of("platform-admin"));
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            jwt(String subject, String email, String name,
                UUID tenantId, TenantRole tenantRole, List<String> realmRoles) {

        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(builder -> builder
                        .subject(subject)
                        .claim("email", email)
                        .claim("name", name)
                        .claim("tenant_id", tenantId.toString())
                        // realm_access.roles mirrors the Keycloak token structure
                        // that KeycloakRoleConverter reads.
                        .claim("realm_access", Map.of("roles", realmRoles))
                        .expiresAt(Instant.now().plusSeconds(3600)))
                // The post-processor builds its own authentication and, left to its default,
                // derives authorities from the `scope` claim — realm roles were silently ignored,
                // which is how a platform-admin gate that could never pass went unnoticed (S9-3).
                // Running the production converter makes a test's authorities the ones a real
                // Keycloak token would carry.
                .authorities(new KeycloakRoleConverter());
    }
}

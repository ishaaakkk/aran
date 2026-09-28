package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import edu.upc.dtak.aran.operators.queryengine.QueryEngineOperator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two limits on an API token that the path-and-method scope check cannot express.
 */
class TokenScopePolicyTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setRequestURI(uri);
        return r;
    }

    private static void authenticateWithToken(ApiTokenScope scope) {
        AuthenticatedUser owner = new AuthenticatedUser(
                UUID.randomUUID(), "sub", UUID.randomUUID(), TenantRole.OWNER);
        SecurityContextHolder.getContext().setAuthentication(
                new ApiTokenAuthentication(owner, scope, UUID.randomUUID()));
    }

    // ── Administration is closed to tokens, at every scope ────────────────────

    @ParameterizedTest(name = "no token may reach {0}")
    @ValueSource(strings = {
            "/api/tenant/members",
            "/api/tenant/members/11111111-1111-1111-1111-111111111111",
            "/api/tenant/admin/overview",
            "/api/tenant/admin/tokens",
            // J7-F8's owner revoke: a leaked token must not be able to revoke a colleague's.
            "/api/tenant/admin/tokens/11111111-1111-1111-1111-111111111111",
            "/api/admin/tenants",
            "/api/tokens",
            "/api/tokens/11111111-1111-1111-1111-111111111111",
            // TenantOwnerController's mutating half: POST /{id}/members, DELETE /{id}/membership/me.
            "/api/tenants/11111111-1111-1111-1111-111111111111/members",
            "/api/tenants/11111111-1111-1111-1111-111111111111/membership/me",
    })
    @DisplayName("administrative routes are closed to API tokens")
    void adminRoutesAreClosed(String uri) {
        assertThat(TokenScopePolicy.isAdminRoute(request(uri))).isTrue();
    }

    @ParameterizedTest(name = "no token may reach {0} (percent-encoded)")
    @ValueSource(strings = {
            // StrictHttpFirewall lets an encoded letter through and MVC routes on the decoded
            // segment, so each of these reaches the administrative controller. A raw-URI match
            // read them as unrelated routes (F-AUTHN-3).
            "/api/%74okens",
            "/api/tokens/%31%31111111-1111-1111-1111-111111111111",
            "/api/tenant/%6Dembers",
            "/api/%61dmin/tenants",
            "/api/tenants/11111111-1111-1111-1111-111111111111/%6Dembers",
            "/api/tenant%73/11111111-1111-1111-1111-111111111111/membership/me",
    })
    @DisplayName("the admin block matches decoded segments, as MVC routing does")
    void encodedAdminRoutesAreClosed(String uri) {
        assertThat(TokenScopePolicy.isAdminRoute(request(uri))).isTrue();
    }

    @Test
    @DisplayName("POST /api/tenants/{id}/members and DELETE …/membership/me are closed to tokens")
    void tenantOwnerMutationsAreClosed() {
        MockHttpServletRequest add = new MockHttpServletRequest(
                "POST", "/api/tenants/11111111-1111-1111-1111-111111111111/members");
        MockHttpServletRequest leave = new MockHttpServletRequest(
                "DELETE", "/api/tenants/11111111-1111-1111-1111-111111111111/membership/me");
        assertThat(TokenScopePolicy.isAdminRoute(add)).isTrue();
        assertThat(TokenScopePolicy.isAdminRoute(leave)).isTrue();
    }

    @ParameterizedTest(name = "{0} is ordinary")
    @ValueSource(strings = {
            "/api/projects",
            "/api/query/export",
            "/api/jdbc",
            "/api/operators/query-engine/invoke",
            // Near-misses: the prefix match must not be so loose that it swallows these.
            // The license read is any member's, and a token-driven client reads its own usage.
            "/api/tenants/11111111-1111-1111-1111-111111111111/license",
            "/api/tenants/11111111-1111-1111-1111-111111111111/%6Cicense",
            "/api/tenants",
            "/api/tenants/11111111-1111-1111-1111-111111111111/membersx",
            "/api/administration-guide",
            "/api/tokensmith",
    })
    @DisplayName("ordinary routes are not treated as administrative")
    void ordinaryRoutesAreOpen(String uri) {
        assertThat(TokenScopePolicy.isAdminRoute(request(uri))).isFalse();
    }

    @Test
    @DisplayName("a FULL token is still barred — the point is the credential, not the scope")
    void fullScopeDoesNotUnlockAdministration() {
        // A FULL token belongs to a user whose AuthenticatedUser carries their tenant role, so
        // without this rule an OWNER's token could create and delete users.
        assertThat(TokenScopePolicy.isAdminRoute(request("/api/tenant/members"))).isTrue();
    }

    // ── READ scope is limited by operation, not only by path ─────────────────

    @Test
    @DisplayName("a READ token may run a query")
    void readTokenMayQuery() {
        authenticateWithToken(ApiTokenScope.READ);
        assertThatCode(() -> TokenScopePolicy.requireOperationAllowed("query-engine", "run-oql"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a READ token may not drive another operator through the same path")
    void readTokenMayNotDriveOtherOperators() {
        authenticateWithToken(ApiTokenScope.READ);
        assertThatThrownBy(() ->
                TokenScopePolicy.requireOperationAllowed("schema-integration", "compile"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a READ token may not invoke an unrecognised query-engine operation")
    void readTokenMayNotInvokeUnknownOperation() {
        authenticateWithToken(ApiTokenScope.READ);
        assertThatThrownBy(() ->
                TokenScopePolicy.requireOperationAllowed("query-engine", "drop-everything"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a FULL token is not restricted by operation")
    void fullTokenUnrestricted() {
        authenticateWithToken(ApiTokenScope.FULL);
        assertThatCode(() ->
                TokenScopePolicy.requireOperationAllowed("schema-integration", "compile"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an interactive session is bounded by its role, not by a token scope")
    void interactiveSessionIsUnaffected() {
        // No ApiTokenAuthentication in the context — an OIDC caller.
        assertThatCode(() ->
                TokenScopePolicy.requireOperationAllowed("schema-integration", "compile"))
                .doesNotThrowAnyException();
    }

    // ── The guard that keeps the allowlist honest ────────────────────────────

    /**
     * Operations that write, and are therefore deliberately absent from the read-only set.
     *
     * <p>Kept here rather than in the policy because the policy only needs to know what is
     * allowed; this list exists so the assertion below can tell "classified as writing" apart
     * from "nobody looked at it yet".
     */
    private static final Set<String> KNOWN_WRITING_OPERATIONS = Set.of();

    @Test
    @DisplayName("every query-engine operation is classified — adding one fails this test")
    void everyOperationIsClassified() {
        // `manifest()` only builds records, so the collaborators are never touched. Constructing
        // the operator this way keeps the assertion against the real manifest rather than a copy
        // of it, which is the entire point: a copy would drift silently.
        List<String> operations = new QueryEngineOperator(null, null, null, null, null, null, null, null, null)
                .manifest().operations().stream()
                .map(OperationSpec::name)
                .toList();

        assertThat(operations)
                .describedAs("""
                        A query-engine operation is neither in TokenScopePolicy's read-only set nor \
                        in this test's KNOWN_WRITING_OPERATIONS. /api/operators/query-engine/invoke \
                        is allowlisted for READ-scope tokens on the strength of every operation \
                        behind it being a read — decide which this one is and say so in whichever \
                        set applies.""")
                .allMatch(op -> TokenScopePolicy.READ_ONLY_QUERY_ENGINE_OPERATIONS.contains(op)
                        || KNOWN_WRITING_OPERATIONS.contains(op));
    }
}

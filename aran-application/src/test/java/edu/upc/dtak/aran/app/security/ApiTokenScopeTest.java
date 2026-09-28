package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a READ-scoped token is allowed to do.
 *
 * <p>This is the whole value of the scope: a credential that lives for months and may sit in a
 * notebook, a CI secret or a cron environment must not be able to promote a schema or delete a
 * dataset. The allowlist is short and explicit, so the tests enumerate both sides of it.
 */
class ApiTokenScopeTest {

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        return r;
    }

    private static boolean allowed(ApiTokenScope scope, String method, String uri) {
        return ApiTokenAuthenticationFilter.isAllowedByScope(scope, request(method, uri));
    }

    // ── READ ─────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "READ allows {0} {1}")
    @CsvSource({
            "GET,     /api/projects",
            "GET,     /api/projects/11111111-1111-1111-1111-111111111111/products",
            // Deliberately not /api/tokens: the scope would allow it, but the filter
            // closes every administrative route to tokens first. See TokenScopePolicyTest.
            "GET,     /api/operators",
            "HEAD,    /api/projects",
            "OPTIONS, /api/projects",
    })
    @DisplayName("safe methods are allowed")
    void safeMethodsAllowed(String method, String uri) {
        assertThat(allowed(ApiTokenScope.READ, method, uri)).isTrue();
    }

    @ParameterizedTest(name = "READ allows POST {0}")
    @CsvSource({
            "/api/query/export",
            "/api/operators/query-engine/invoke",
            "/api/jdbc",
    })
    @DisplayName("the POST routes that read despite the verb are allowed")
    void readOnlyPostsAllowed(String uri) {
        // The first two are POST only because their input — an OQL query — is unbounded in length
        // and has no business in a URL or an access log. Without these a read-only token could not
        // run a query, which is the main thing anyone wants one for.
        //
        // /api/jdbc is POST because Avatica's protocol has one route for every operation; the
        // operations behind it run OQL or answer catalog questions, and AranMeta refuses writes.
        assertThat(allowed(ApiTokenScope.READ, "POST", uri)).isTrue();
    }

    @ParameterizedTest(name = "READ refuses {0} {1}")
    @CsvSource({
            "POST,   /api/projects",
            "POST,   /api/projects/11111111-1111-1111-1111-111111111111/products",
            "POST,   /api/operators/bootstrapping/submit",
            "POST,   /api/operators/schema-integration/invoke",
            "PUT,    /api/projects/11111111-1111-1111-1111-111111111111",
            "PATCH,  /api/projects/11111111-1111-1111-1111-111111111111",
            "DELETE, /api/tokens/11111111-1111-1111-1111-111111111111",
            "DELETE, /api/projects/11111111-1111-1111-1111-111111111111",
    })
    @DisplayName("anything that changes state is refused")
    void mutationsRefused(String method, String uri) {
        assertThat(allowed(ApiTokenScope.READ, method, uri)).isFalse();
    }

    @Test
    @DisplayName("the allowlist is exact paths, not a prefix over /api/operators")
    void operatorAllowlistIsNotAPrefix() {
        // A prefix pattern would hand a read-only token the bootstrapping and schema-integration
        // operators, which re-extract schemas and rewrite the integrated graph. This is the
        // single assertion that keeps the allowlist honest as routes are added.
        assertThat(allowed(ApiTokenScope.READ, "POST", "/api/operators/query-engine/invoke")).isTrue();
        assertThat(allowed(ApiTokenScope.READ, "POST", "/api/operators/bootstrapping/invoke")).isFalse();
        assertThat(allowed(ApiTokenScope.READ, "POST", "/api/operators/join-discovery/invoke")).isFalse();
    }

    @Test
    @DisplayName("a near-miss path does not slip through")
    void noPartialMatches() {
        assertThat(allowed(ApiTokenScope.READ, "POST", "/api/query/export/../projects")).isFalse();
        assertThat(allowed(ApiTokenScope.READ, "POST", "/api/query/exportx")).isFalse();
        assertThat(allowed(ApiTokenScope.READ, "POST", "/api/query")).isFalse();
    }

    // ── FULL ─────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "FULL allows {0} {1}")
    @CsvSource({
            "POST,   /api/projects",
            "DELETE, /api/projects/11111111-1111-1111-1111-111111111111",
            "PUT,    /api/projects/11111111-1111-1111-1111-111111111111",
            "GET,    /api/projects",
    })
    @DisplayName("FULL is everything the owning user could do")
    void fullAllowsEverything(String method, String uri) {
        assertThat(allowed(ApiTokenScope.FULL, method, uri)).isTrue();
    }
}

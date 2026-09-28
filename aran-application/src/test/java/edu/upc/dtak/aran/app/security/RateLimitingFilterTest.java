package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link RateLimitingFilter}.
 *
 * <p>Each test constructs the specific {@link RateLimitingFilter.Mode} instance under test —
 * {@code PRE_AUTH} or {@code POST_AUTH} — mirroring exactly how {@code SecurityConfig} wires the
 * two passes, rather than relying on any implicit default. Full end-to-end wiring (both passes
 * registered together on the real chain) is covered by {@code RateLimitingIntegrationIT}.
 */
@DisplayName("RateLimitingFilter")
class RateLimitingFilterTest {

    RateLimitingFilter preAuthFilter;
    RateLimitingFilter postAuthFilter;

    @BeforeEach
    void setUp() {
        preAuthFilter = new RateLimitingFilter(RateLimitingFilter.Mode.PRE_AUTH);
        postAuthFilter = new RateLimitingFilter(RateLimitingFilter.Mode.POST_AUTH);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest apiRequest(String remoteAddr) {
        var request = new MockHttpServletRequest("GET", "/api/projects");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static void authenticateAs(String subject) {
        var jwt = Jwt.withTokenValue("mock-token-" + subject)
                .header("alg", "none")
                .claim("sub", subject)
                .build();
        SecurityContextHolder.setContext(new SecurityContextImpl(new JwtAuthenticationToken(jwt)));
    }

    // ---- PRE_AUTH pass ---------------------------------------------------

    @Test
    @DisplayName("PRE_AUTH allows requests below the per-IP limit")
    void allows_requests_below_limit() throws Exception {
        var chain = mock(FilterChain.class);
        var request = apiRequest("10.0.0.1");
        var response = new MockHttpServletResponse();

        preAuthFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("PRE_AUTH returns 429 after the per-IP limit is exhausted")
    void returns_429_after_limit() throws Exception {
        var request = apiRequest("10.0.0.2");

        // Exhaust the bucket (60 requests for anonymous IP)
        for (int i = 0; i < 60; i++) {
            var response = new MockHttpServletResponse();
            preAuthFilter.doFilterInternal(request, response, mock(FilterChain.class));
        }

        // The 61st should be rate-limited
        var overLimitResponse = new MockHttpServletResponse();
        preAuthFilter.doFilterInternal(request, overLimitResponse, mock(FilterChain.class));

        assertThat(overLimitResponse.getStatus()).isEqualTo(429);
        assertThat(overLimitResponse.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("PRE_AUTH different IPs have separate buckets")
    void different_ips_have_separate_buckets() throws Exception {
        // Exhaust the bucket for IP A
        for (int i = 0; i < 60; i++) {
            preAuthFilter.doFilterInternal(apiRequest("10.0.0.3"), new MockHttpServletResponse(), mock(FilterChain.class));
        }

        // IP B should still be allowed
        var chain = mock(FilterChain.class);
        var request = apiRequest("10.0.0.4");
        var response = new MockHttpServletResponse();
        preAuthFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("PRE_AUTH keys by IP even when the SecurityContext is already authenticated")
    void pre_auth_ignores_security_context() throws Exception {
        // Guards the PRE_AUTH/POST_AUTH split itself: even if a JwtAuthenticationToken is
        // already on the context (it shouldn't be, this early in the real chain, but nothing
        // stops a test — or a future refactor — from getting the order wrong), PRE_AUTH must
        // still key strictly by IP, never by subject.
        authenticateAs("user-123");
        var request = apiRequest("10.0.0.9");

        for (int i = 0; i < 60; i++) {
            preAuthFilter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));
        }
        var overLimitResponse = new MockHttpServletResponse();
        preAuthFilter.doFilterInternal(request, overLimitResponse, mock(FilterChain.class));

        assertThat(overLimitResponse.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("does not rate-limit non-API paths")
    void skips_non_api_paths() throws Exception {
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("10.0.0.5");

        // shouldNotFilter returns true for non-/api/ paths — the filter
        // shouldn't execute at all. Test by verifying the request passes through.
        // Note: doFilterInternal is called directly here; in a real context
        // OncePerRequestFilter.doFilter() would check shouldNotFilter first.
        assertThat(preAuthFilter.shouldNotFilter(request)).isTrue();
    }

    // ---- POST_AUTH pass ----------------------------------------------------

    @Test
    @DisplayName("POST_AUTH allows requests below the per-user limit when authenticated")
    void allows_requests_below_user_limit_when_authenticated() throws Exception {
        authenticateAs("user-123");
        var request = apiRequest("10.0.0.1");

        // Exhaust up to the authenticated limit (300 requests + 50 burst = 350)
        for (int i = 0; i < 350; i++) {
            var response = new MockHttpServletResponse();
            postAuthFilter.doFilterInternal(request, response, mock(FilterChain.class));
            assertThat(response.getStatus()).isNotEqualTo(429);
        }

        // The 351st should be rate-limited
        var overLimitResponse = new MockHttpServletResponse();
        postAuthFilter.doFilterInternal(request, overLimitResponse, mock(FilterChain.class));
        assertThat(overLimitResponse.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("POST_AUTH different authenticated users have separate buckets, even from the same IP")
    void different_users_have_separate_buckets() throws Exception {
        // Exhaust bucket for user-123
        authenticateAs("user-123");
        for (int i = 0; i < 350; i++) {
            postAuthFilter.doFilterInternal(apiRequest("10.0.0.1"), new MockHttpServletResponse(), mock(FilterChain.class));
        }
        var exhaustedResponse = new MockHttpServletResponse();
        postAuthFilter.doFilterInternal(apiRequest("10.0.0.1"), exhaustedResponse, mock(FilterChain.class));
        assertThat(exhaustedResponse.getStatus()).isEqualTo(429);

        // user-456, same IP, should still be allowed — proves the bug this whole design fixed:
        // two different authenticated users never share a bucket, even behind one address.
        authenticateAs("user-456");
        var chain = mock(FilterChain.class);
        var request = apiRequest("10.0.0.1");
        var response = new MockHttpServletResponse();
        postAuthFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("POST_AUTH is a no-op for unauthenticated requests: consumes no bucket, never rate-limits")
    void post_auth_is_noop_when_unauthenticated() throws Exception {
        // No SecurityContext authentication set — simulates every unauthenticated request
        // (anonymous, or one carrying an invalid token that never got this far anyway).
        var request = apiRequest("10.0.0.7");

        // Run far past both the anonymous (60) and authenticated (350) limits. If POST_AUTH
        // consumed anything here, this would eventually 429; it must never do so, because
        // accounting for anonymous requests is exclusively the PRE_AUTH pass's job.
        for (int i = 0; i < 500; i++) {
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            postAuthFilter.doFilterInternal(request, response, chain);
            assertThat(response.getStatus()).isNotEqualTo(429);
            verify(chain, times(1)).doFilter(request, response);
        }
    }

    // ---- Programmatic clients (API tokens) -------------------------------

    private static void authenticateAsApiToken(UUID tokenId) {
        var user = new AuthenticatedUser(UUID.randomUUID(), "subject", UUID.randomUUID(),
                TenantRole.OWNER);
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new ApiTokenAuthentication(user, ApiTokenScope.READ, tokenId)));
    }

    @Test
    @DisplayName("PRE_AUTH puts a Basic-auth request in the credential tier, not the anonymous one")
    void basic_auth_is_credentialled_traffic() throws Exception {
        // JDBC clients send their API token in the password field, so their requests carry Basic
        // rather than Bearer. In the anonymous tier (60/min) a single large JDBC result would
        // 429 partway through, because one HTTP request is one fetched frame.
        var request = apiRequest("10.0.0.20");
        request.addHeader("Authorization", "Basic b2Rpbjpvc.…");

        for (int i = 0; i < 200; i++) {
            var response = new MockHttpServletResponse();
            preAuthFilter.doFilterInternal(request, response, mock(FilterChain.class));
            assertThat(response.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    @DisplayName("POST_AUTH gives an API token the authenticated allowance, not nothing")
    void api_token_consumes_an_authenticated_bucket() throws Exception {
        // Without this the SDK and the JDBC endpoint — whose only credential is an API token —
        // would skip per-identity limiting entirely and be governed only by the shared per-IP
        // bucket, which is the opposite of what the two-pass split exists to achieve.
        authenticateAsApiToken(UUID.randomUUID());
        var request = apiRequest("10.0.0.21");

        MockHttpServletResponse last = null;
        for (int i = 0; i < 400; i++) {
            last = new MockHttpServletResponse();
            postAuthFilter.doFilterInternal(request, last, mock(FilterChain.class));
        }

        assertThat(last.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("two API tokens do not share a bucket")
    void api_tokens_have_separate_buckets() throws Exception {
        // A notebook pulling a large result must not exhaust the allowance its owner's other
        // credentials are using.
        UUID exhausted = UUID.randomUUID();
        authenticateAsApiToken(exhausted);
        var request = apiRequest("10.0.0.22");
        for (int i = 0; i < 400; i++) {
            postAuthFilter.doFilterInternal(request, new MockHttpServletResponse(),
                    mock(FilterChain.class));
        }

        authenticateAsApiToken(UUID.randomUUID());
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        postAuthFilter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        verify(chain).doFilter(request, response);
    }
}

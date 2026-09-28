package edu.upc.dtak.aran.app.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The deployment's authentication policy: which credential presentations are accepted, and
 * whether TLS is insisted on.
 *
 * <p>The point of the policy living here rather than in each client is that a client cannot opt
 * out of it, so these tests are written from the outside — a request arrives shaped a certain way,
 * and the filter either passes it on or refuses it before any verifier runs.
 */
class AuthPolicyTest {

    private static final String TOKEN = "aran_pat_HZ3xQq0Ls9";
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.e30.sig";

    private static MockHttpServletRequest request(String authorization) {
        var request = new MockHttpServletRequest("GET", "/api/projects");
        request.setRequestURI("/api/projects");
        request.setRemoteAddr("203.0.113.10");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("classifying how a credential was presented")
    class Classification {

        @Test
        @DisplayName("an Aran token in a bearer header is the bearer method")
        void bearerToken() {
            assertThat(AuthMethod.of(request("Bearer " + TOKEN)))
                    .isEqualTo(AuthMethod.API_TOKEN_BEARER);
        }

        @Test
        @DisplayName("a JWT in a bearer header is OIDC")
        void jwt() {
            assertThat(AuthMethod.of(request("Bearer " + JWT))).isEqualTo(AuthMethod.OIDC);
        }

        @Test
        @DisplayName("HTTP Basic is its own method, whichever field carries the token")
        void basicIsItsOwnMethod() {
            assertThat(AuthMethod.of(request(basic("aran", TOKEN))))
                    .isEqualTo(AuthMethod.API_TOKEN_BASIC);
            assertThat(AuthMethod.of(request(basic(TOKEN, ""))))
                    .isEqualTo(AuthMethod.API_TOKEN_BASIC);
        }

        @Test
        @DisplayName("no header, or an unknown scheme, presents nothing")
        void noCredential() {
            assertThat(AuthMethod.of(request(null))).isNull();
            assertThat(AuthMethod.of(request("Digest username=\"aran\""))).isNull();
        }
    }

    @Nested
    @DisplayName("parsing the configured allowlist")
    class Parsing {

        @Test
        @DisplayName("blank accepts every method, which is the local default")
        void blankAcceptsAll() {
            assertThat(AuthPolicy.parse("", false).methods())
                    .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(AuthMethod.class));
        }

        @Test
        @DisplayName("a subset is respected, and names are case- and space-insensitive")
        void subset() {
            AuthPolicy policy = AuthPolicy.parse(" OIDC , api-token-bearer ", false);

            assertThat(policy.allows(AuthMethod.OIDC)).isTrue();
            assertThat(policy.allows(AuthMethod.API_TOKEN_BEARER)).isTrue();
            assertThat(policy.allows(AuthMethod.API_TOKEN_BASIC)).isFalse();
        }

        @Test
        @DisplayName("an unknown name fails startup rather than silently narrowing the allowlist")
        void unknownNameFailsFast() {
            // A typo in an authentication allowlist must not be survivable: "api-token-bsic"
            // would otherwise quietly disable JDBC and look like a client bug.
            assertThatThrownBy(() -> AuthPolicy.parse("oidc,api-token-bsic", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("api-token-bsic")
                    .hasMessageContaining("api-token-basic");
        }

        @Test
        @DisplayName("a list of only separators is refused rather than locking everyone out")
        void emptyListRefused() {
            assertThatThrownBy(() -> AuthPolicy.parse(",,", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lock the deployment out");
        }
    }

    @Nested
    @DisplayName("enforcement")
    class Enforcement {

        private final AuthPolicyFilter tokensOnly = new AuthPolicyFilter(
                AuthPolicy.parse("oidc,api-token-bearer", false));

        @Test
        @DisplayName("a forbidden presentation is refused before any verifier sees it")
        void refusesDisabledMethod() throws Exception {
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            tokensOnly.doFilterInternal(request(basic("aran", TOKEN)), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("api-token-basic");
            // The whole point: the chain never runs, so no token lookup and no signature
            // verification happens for a method the operator turned off.
            verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("the refusal names what is accepted, so a client can say why")
        void refusalIsActionable() throws Exception {
            var response = new MockHttpServletResponse();

            tokensOnly.doFilterInternal(request(basic("aran", TOKEN)), response,
                    mock(FilterChain.class));

            assertThat(response.getContentType()).contains("application/problem+json");
            assertThat(response.getContentAsString()).contains("/api/auth/policy");
        }

        @Test
        @DisplayName("an accepted presentation passes through untouched")
        void allowsEnabledMethod() throws Exception {
            var request = request("Bearer " + TOKEN);
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            tokensOnly.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("a request with no credential is left to the chain's entry point")
        void ignoresAnonymous() throws Exception {
            // Deciding what an unauthenticated request looks like belongs in one place, and it is
            // not here.
            var request = request(null);
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            tokensOnly.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("requiring TLS")
    class Tls {

        private final AuthPolicyFilter strict =
                new AuthPolicyFilter(AuthPolicy.parse("", true));

        @Test
        @DisplayName("a credential over plain HTTP from a remote host is refused")
        void refusesPlaintextCredential() throws Exception {
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            strict.doFilterInternal(request("Bearer " + TOKEN), response, chain);

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("https");
            verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("the same credential over TLS is fine")
        void allowsTls() throws Exception {
            var request = request("Bearer " + TOKEN);
            request.setSecure(true);
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            strict.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("loopback is exempt, so switching this on does not break local development")
        void loopbackExempt() throws Exception {
            var request = request("Bearer " + TOKEN);
            request.setRemoteAddr("127.0.0.1");
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            strict.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("an anonymous request over plain HTTP is not a credential leak")
        void anonymousUnaffected() throws Exception {
            var request = request(null);
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            strict.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("the policy endpoint")
    class Discovery {

        @Test
        @DisplayName("is readable without satisfying the policy it describes")
        void notGatedByItself() {
            // Otherwise a client whose only method is disabled cannot discover why: the refusal
            // and the explanation would both be behind the same door.
            var filter = new AuthPolicyFilter(AuthPolicy.parse("oidc", false));
            var request = new MockHttpServletRequest("GET", "/api/auth/policy");
            request.setRequestURI("/api/auth/policy");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @Test
        @DisplayName("reports what the deployment accepts")
        void reportsPolicy() {
            var controller = new AuthPolicyController(
                    AuthPolicy.parse("oidc,api-token-bearer", true));

            AuthPolicyController.AuthPolicyDto dto = controller.policy();

            assertThat(dto.methods()).containsExactly("oidc", "api-token-bearer");
            assertThat(dto.requireTls()).isTrue();
        }

        @Test
        @DisplayName("non-API paths are none of the filter's business")
        void skipsNonApi() {
            var filter = new AuthPolicyFilter(AuthPolicy.permissive());
            var request = new MockHttpServletRequest("GET", "/index.html");
            request.setRequestURI("/index.html");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }
    }
}

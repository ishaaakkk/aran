package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The pre-auth token-bearing tier defaults to 3000/min in production — deliberately far above
// ordinary traffic, because one address can legitimately carry many distinct users. Pinning it
// to the same 60 the anonymous tier uses lets the flood test below prove the cap trips without
// issuing thousands of requests. Tests that send no Authorization header are unaffected: they
// key on the anonymous tier, which this property does not touch.
@TestPropertySource(properties = "aran.security.rate-limit.pre-auth-token-per-minute=60")
@DisplayName("RateLimitingIntegrationIT")
class RateLimitingIntegrationIT extends BaseIntegrationTest {

    // BaseIntegrationTest raises every ceiling for the suite (one address, one JVM); this class is
    // the one that proves them, so it puts the real ones back before its own context is created
    // and clears them afterwards. The flood test's 60 is the @TestPropertySource value above.
    private static final String[] TIERS = {"anon-per-minute", "authed-per-minute", "pre-auth-token-per-minute"};

    @org.junit.jupiter.api.BeforeAll
    static void realCeilings() {
        System.setProperty("aran.test.rate-limit.anon-per-minute", "60");
        System.setProperty("aran.test.rate-limit.authed-per-minute", "300");
        System.setProperty("aran.test.rate-limit.pre-auth-token-per-minute", "60");
    }

    @org.junit.jupiter.api.AfterAll
    static void clearCeilings() {
        for (String tier : TIERS) {
            System.clearProperty("aran.test.rate-limit." + tier);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("untrusted proxy X-Forwarded-For is ignored")
    void untrusted_proxy_x_forwarded_for_is_ignored() throws Exception {
        // Since 10.0.0.99 is not in the trusted proxies list (defaults to 127.0.0.1|::1|etc.),
        // any X-Forwarded-For header is ignored and requests are tracked under IP 10.0.0.99.
        String untrustedProxyIp = "10.0.0.99";

        // Exhaust the 60 request limit for the anonymous IP
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get("/api/projects")
                            .header("X-Forwarded-For", "203.0.113.195")
                            .with(request -> {
                                request.setRemoteAddr(untrustedProxyIp);
                                return request;
                            }))
                    .andExpect(status().isUnauthorized()); // 401 because it's unauthenticated but allowed by rate-limiter
        }

        // The 61st request, even with a different X-Forwarded-For header, is rejected with 429
        mockMvc.perform(get("/api/projects")
                        .header("X-Forwarded-For", "203.0.113.196")
                        .with(request -> {
                            request.setRemoteAddr(untrustedProxyIp);
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("trusted proxy X-Forwarded-For is respected")
    void trusted_proxy_x_forwarded_for_is_respected() throws Exception {
        // Since 127.0.0.1 is in the trusted proxies list, X-Forwarded-For is respected.
        // We exhaust the rate limit for 203.0.113.200 (spoofed via X-Forwarded-For).
        String trustedProxyIp = "127.0.0.1";

        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get("/api/projects")
                            .header("X-Forwarded-For", "203.0.113.200")
                            .with(request -> {
                                request.setRemoteAddr(trustedProxyIp);
                                return request;
                            }))
                    .andExpect(status().isUnauthorized());
        }

        // The 61st request from the same trusted proxy but with a DIFFERENT X-Forwarded-For is ALLOWED (returns 401, not 429)
        mockMvc.perform(get("/api/projects")
                        .header("X-Forwarded-For", "203.0.113.201")
                        .with(request -> {
                            request.setRemoteAddr(trustedProxyIp);
                            return request;
                        }))
                .andExpect(status().isUnauthorized());

        // However, a request from the same trusted proxy with the EXHAUSTED X-Forwarded-For is rate limited (returns 429)
        mockMvc.perform(get("/api/projects")
                        .header("X-Forwarded-For", "203.0.113.200")
                        .with(request -> {
                            request.setRemoteAddr(trustedProxyIp);
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("a flood of requests carrying an invalid bearer token is capped at 429, not an unbounded stream of 401s")
    void invalid_bearer_token_flood_is_rate_limited() throws Exception {
        // This is the whole point of the PRE_AUTH pass: it runs BEFORE
        // BearerTokenAuthenticationFilter, so it must consume the per-IP bucket for every one
        // of these requests even though each one is, on its own, ultimately rejected with 401 by
        // real JWT verification further down the chain. A unit test cannot prove this — it needs
        // the real BearerTokenAuthenticationFilter in the chain to show that signature
        // verification is actually being reached and failing, and that the limiter still catches
        // the flood despite that. Without PRE_AUTH, this loop would 401 forever: an attacker
        // could force unbounded JWT signature verification for free.
        String floodIp = "10.0.0.123";

        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get("/api/projects")
                            .header("Authorization", "Bearer not-a-real-jwt-" + i)
                            .with(request -> {
                                request.setRemoteAddr(floodIp);
                                return request;
                            }))
                    .andExpect(status().isUnauthorized()); // rejected by real JWT verification, not the limiter
        }

        // The 61st request, still carrying an invalid token, is rejected by the rate limiter
        // BEFORE authentication ever runs: 429, not another 401.
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer not-a-real-jwt-final")
                        .with(request -> {
                            request.setRemoteAddr(floodIp);
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
    }
}

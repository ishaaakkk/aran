package edu.upc.dtak.aran.app.security;

import org.apache.catalina.filters.RemoteIpFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for Aran.
 *
 * <p><strong>No Keycloak adapter library.</strong> Keycloak acts purely as a
 * standards-compliant OIDC provider; Spring Security 7 OAuth2 does the rest.
 * Swapping IdPs is a configuration change with no code impact.
 *
 * <p>Two filter chains:
 * <ol>
 *   <li><b>API chain</b> — {@code /api/**}: stateless JWT resource server.
 *       Rate-limited via two {@link RateLimitingFilter} passes (see that
 *       class's Javadoc for the full rationale). A {@code PRE_AUTH} instance
 *       runs <em>before</em> {@code BearerTokenAuthenticationFilter} and
 *       always consumes the per-IP bucket, including for requests carrying
 *       an invalid token — otherwise those are rejected with 401 before ever
 *       reaching a limiter, and a flood of invalid JWTs costs an attacker
 *       nothing. A {@code POST_AUTH} instance runs <em>after</em> that filter
 *       and, only for requests that authenticated successfully, consumes the
 *       generous per-user bucket keyed on the JWT {@code sub} — it is a
 *       deliberate no-op for anonymous requests so they are never counted
 *       against both the IP and (nonexistent) user bucket.</li>
 *   <li><b>Web chain</b> — everything else: authorization-code login for the
 *       browser/BFF surface and actuator.</li>
 * </ol>
 *
 * <p><b>Security headers</b> are applied to both chains:
 * <ul>
 *   <li>HSTS — forces HTTPS for 1 year including sub-domains.</li>
 *   <li>Content-Security-Policy — tight allow-list; adjust {@code connect-src}
 *       if the SPA calls external services.</li>
 *   <li>X-Frame-Options: DENY — clickjacking protection.</li>
 *   <li>X-Content-Type-Options: nosniff — MIME-sniffing protection.</li>
 *   <li>Referrer-Policy: strict-origin-when-cross-origin.</li>
 *   <li>Permissions-Policy — disables access to sensors, camera, mic.</li>
 * </ul>
 *
 * <p><b>PKCE</b> for the SPA is enforced by the Keycloak client configuration
 * ({@code pkce.code.challenge.method: S256} in {@code aran-realm.json}) and
 * by {@code keycloak-js}'s {@code pkceMethod: 'S256'} in the frontend.
 * Spring Security's OAuth2 client also enforces PKCE on the BFF chain
 * automatically when the {@code code_challenge_method} is advertised in the
 * authorization server's metadata — no explicit Java config needed.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public FilterRegistrationBean<RemoteIpFilter> remoteIpFilter(
            @Value("${aran.security.trusted-proxies}") String trustedProxies) {
        var filter = new RemoteIpFilter();
        filter.setInternalProxies(trustedProxies);
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Filter chain 0 — liveness/readiness probes, anonymous.
     *
     * <p>These must be reachable without authentication: the container healthcheck
     * (docker-compose.yml, {@code aran-app}) and the end-to-end suite's stack gate both
     * poll {@code /actuator/health/readiness} on the management port and match on
     * {@code "status":"UP"}. Without this chain, chain 2 below catches them — it declares
     * no {@code securityMatcher}, so it is a catch-all, and the management child context
     * inherits it — and every probe is redirected into the OAuth2 login flow. The
     * healthcheck can then never pass.
     *
     * <p>Deliberately scoped to {@code /actuator/health/**} alone. Everything else under
     * {@code /actuator} — metrics, prometheus, info — stays behind authentication via
     * chain 2. The management port is not published to the host in production compose.
     */
    @Bean
    @Order(0)
    SecurityFilterChain healthProbeSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/health/**")
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Filter chain 0.5 — the Keycloak reverse proxy, standalone topology only.
     *
     * <p>{@code /auth/**} is the login flow itself, so it cannot sit behind authentication —
     * an unauthenticated browser is precisely who it serves. CSRF is Keycloak's own concern
     * (its forms carry their own state parameters), and header writers are disabled so the
     * login page arrives with Keycloak's headers, not Aran's duplicated on top. Exists only
     * when {@code aran.auth-proxy.enabled} — in every other topology there is no {@code /auth}
     * route at all, and this chain must not exist to swallow one.
     */
    @Bean
    @Order(-1)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "aran.auth-proxy.enabled", havingValue = "true")
    SecurityFilterChain authProxySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/auth/**")
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.disable());
        return http.build();
    }

    /**
     * Filter chain 0.6 — the SPA shell, standalone topology only.
     *
     * <p>The standalone image serves the SPA itself, and the SPA is a public shell: keycloak-js
     * runs the login at {@code /auth} and every data access is an authenticated {@code /api}
     * call. Left to the catch-all chain, {@code GET /} answered a 302 into Spring's own
     * oauth2Login — a second, competing login flow on a page whose whole point is to start the
     * first one. Matches everything the other chains do not claim ({@code /api}, {@code /auth},
     * {@code /actuator} keep their chains and their rules), which in this topology is exactly
     * the static shell and the SPA's client-routed paths. Ordered ahead of the catch-all —
     * which has no matcher and would otherwise swallow every request first — and safe ahead of
     * the rest because the matcher explicitly cedes their paths.
     */
    @Bean
    @Order(-2)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "aran.auth-proxy.enabled", havingValue = "true")
    SecurityFilterChain spaShellSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(request -> {
                    String uri = request.getRequestURI();
                    return !uri.startsWith("/api") && !uri.startsWith("/auth")
                            && !uri.startsWith("/actuator");
                })
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * The deployment's authentication policy, resolved once at startup.
     *
     * <p>A bean rather than a value read where it is needed, because two places consume it — the
     * filter that enforces it and the endpoint that publishes it — and they must never be able to
     * disagree about what the policy is.
     */
    @Bean
    AuthPolicy authPolicy(
            @Value("${aran.security.auth.methods:}") String methods,
            @Value("${aran.security.auth.require-tls:false}") boolean requireTls) {
        return AuthPolicy.parse(methods, requireTls);
    }

    /**
     * Filter chain 1 — the stateless JSON API.
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ApiTokenAuthenticationFilter apiTokenAuthenticationFilter,
            AuthPolicy authPolicy,
            // The pre-auth token-bearing ceiling is a DoS backstop, not a fairness knob, so it
            // sits far above ordinary traffic. Externalised only so a test can prove the cap
            // trips without issuing thousands of requests.
            @Value("${aran.security.rate-limit.pre-auth-token-per-minute:3000}")
            int preAuthTokenPerMinute,
            // The anonymous-per-IP and authenticated-per-user ceilings: production defaults, raised
            // only by the integration suite, whose every request shares one address (see the
            // filter's field javadoc).
            @Value("${aran.security.rate-limit.anon-per-minute:60}")
            int anonPerMinute,
            @Value("${aran.security.rate-limit.authed-per-minute:300}")
            int authedPerMinute) throws Exception {
        // Constructed inline — deliberately not @Bean/@Component — so these two instances are
        // never visible to Spring Boot's automatic Filter-bean servlet registration; the security
        // chain below is the only place either one runs. See RateLimitingFilter's class Javadoc.
        var preAuthRateLimitingFilter = new RateLimitingFilter(
                RateLimitingFilter.Mode.PRE_AUTH, preAuthTokenPerMinute, anonPerMinute, authedPerMinute);
        var postAuthRateLimitingFilter = new RateLimitingFilter(
                RateLimitingFilter.Mode.POST_AUTH, preAuthTokenPerMinute, anonPerMinute, authedPerMinute);
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        // "How may I authenticate?" is the one question that cannot require an
                        // authenticated answer. It discloses a list of accepted mechanisms and
                        // nothing else — no secrets, no user or tenant information — which is what
                        // an OIDC discovery document is too.
                        .requestMatchers(HttpMethod.GET, "/api/auth/policy").permitAll()
                        // The SPA's up/down probe runs before sign-in (the offline screen) and
                        // answers statuses only — see ApiHealthController.
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .anyRequest().authenticated())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        new KeycloakJwtAuthenticationConverter())))
                // The resource server's own entry point challenges every unauthenticated /api/**
                // request with WWW-Authenticate: Bearer only — correct for the browser and the
                // SDK, but it means no client that follows the HTTP challenge-response contract
                // (send no credential, read the challenge, retry) is ever told Basic is an option
                // on this one route. Avatica's own JDBC driver is exactly such a client: it does
                // not send avatica_password preemptively, so without this it gets an unqualified
                // 401 on the very first request and never retries — found by actually connecting
                // a real driver, not by reading ApiTokenAuthenticationFilter's unit tests, which
                // only prove the header is parsed once it arrives. A dedicated filter rather than
                // exceptionHandling().defaultAuthenticationEntryPointFor(): the latter composes
                // with oauth2ResourceServer's own entry-point wiring in a way that produced a
                // session-cookied redirect to /login instead of a 401 here, and this route only
                // ever needs to intervene in the one case — no Authorization header at all.
                // Anchored to BearerTokenAuthenticationFilter, not ApiTokenAuthenticationFilter:
                // addFilterBefore needs its anchor already registered as a known filter class,
                // and apiTokenAuthenticationFilter itself is only registered further down this
                // same chain — anchoring here, ahead of that registration in source order, is
                // what actually puts this filter first among the group sharing that anchor.
                .addFilterBefore(new JdbcBasicChallengeFilter(),
                        BearerTokenAuthenticationFilter.class)
                // Rate limiting is split into two passes — see RateLimitingFilter's class
                // Javadoc for the full rationale.
                //
                // PRE_AUTH runs BEFORE bearer token authentication and always keys/consumes by
                // IP, including for requests carrying an invalid token: BearerTokenAuthenticationFilter
                // rejects those with 401 on its own, so without this pass they would never reach
                // any rate limiter at all, and flooding invalid JWTs would cost an attacker
                // nothing while forcing unbounded signature verification here.
                .addFilterBefore(preAuthRateLimitingFilter,
                        BearerTokenAuthenticationFilter.class)
                // POST_AUTH runs AFTER bearer token authentication: resolveKey() reads
                // SecurityContextHolder for a JwtAuthenticationToken, which only exists once
                // BearerTokenAuthenticationFilter has validated the token. It consumes the
                // generous per-user bucket keyed on the JWT sub, so different authenticated
                // users behind the same IP (all of localhost, for local/e2e traffic) get
                // separate budgets instead of sharing one. It is a deliberate no-op when the
                // request is not authenticated — the PRE_AUTH pass above already accounted for
                // that request against the IP bucket, so consuming again here would double-count it.
                .addFilterAfter(postAuthRateLimitingFilter,
                        BearerTokenAuthenticationFilter.class)
                // EventSource cannot send an Authorization header, so the job event stream — and
                // only that route — may carry its token as ?access_token=. The filter rewrites it
                // into the header before the JWT filter reads it, keeping the stream on exactly
                // the same authentication path as every other /api/** route.
                .addFilterBefore(new SseAccessTokenFilter(),
                        BearerTokenAuthenticationFilter.class)
                // The deployment's authentication policy, enforced ahead of BOTH verifiers: a
                // presentation an operator has forbidden must be refused before the filter that
                // would honour it runs, or disabling a method only changes the error message and
                // every refused request still costs a token lookup or a signature verification.
                //
                // ORDERING IS LOAD-BEARING, twice over. It must come AFTER SseAccessTokenFilter,
                // which is what turns the job stream's ?access_token= into an Authorization
                // header — run before it, and the one route that cannot send a header would be
                // the one route exempt from the policy. It must come BEFORE
                // apiTokenAuthenticationFilter for the reason above. Filters added before the
                // same target keep their registration order (equal orders, stable sort), so the
                // sequence here is the sequence at runtime: rate limit → SSE rewrite → policy →
                // API token → JWT.
                .addFilterBefore(new AuthPolicyFilter(authPolicy),
                        BearerTokenAuthenticationFilter.class)
                // API tokens (aran_pat_…) authenticate before the JWT filter and, on success,
                // hide the Authorization header from it — that filter does not skip an
                // already-authenticated request, so an API token left visible would be rejected
                // as a malformed JWT regardless. Placed AFTER the pre-auth rate limiter, so a
                // flood of invalid tokens is still accounted for against the IP bucket.
                .addFilterBefore(apiTokenAuthenticationFilter,
                        BearerTokenAuthenticationFilter.class)
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))   // 1 year
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(perms -> perms
                                .policy("camera=(), microphone=(), geolocation=(), " +
                                        "payment=(), usb=(), accelerometer=(), " +
                                        "gyroscope=(), magnetometer=()"))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives(
                                        // API responses are JSON — no script/image/frame
                                        // sources needed. Adjust if this endpoint ever
                                        // serves HTML or embeds third-party content.
                                        "default-src 'none'; " +
                                        "frame-ancestors 'none'")));
        return http.build();
    }

    /**
     * Filter chain 2 — the browser-facing surface and actuator.
     */
    @Bean
    @Order(2)
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // Actuator endpoints are served on management.server.port (8082), not here.
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated())
                .cors(Customizer.withDefaults())
                .oauth2Login(Customizer.withDefaults())
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(perms -> perms
                                .policy("camera=(), microphone=(), geolocation=(), " +
                                        "payment=(), usb=()"))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives(
                                        // SPA requests are proxied to the Vite dev server
                                        // (or served as static files in production). The CSP
                                        // here covers the BFF/actuator UI, not the SPA itself
                                        // — the SPA's CSP is served with its HTML by the
                                        // frontend origin (nginx/CDN/Vite).
                                        "default-src 'self'; " +
                                        "script-src 'self'; " +
                                        "style-src 'self' 'unsafe-inline'; " +
                                        "img-src 'self' data:; " +
                                        "connect-src 'self'; " +
                                        "frame-ancestors 'none'; " +
                                        "form-action 'self'")));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${aran.security.allowed-origins}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        boolean wildcard = "*".equals(allowedOrigins);
        if (wildcard) {
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Cache-Control", "X-Requested-With"));
        // Reflecting an arbitrary Origin *with* credentials lets any site make credentialed
        // cross-origin calls on behalf of a logged-in user. Spring's allowedOriginPatterns
        // happily combines the two, so refuse the combination explicitly: a wildcard origin
        // downgrades to credential-less CORS rather than silently granting the dangerous pair.
        // Credentials stay enabled for an explicit origin list, which is the intended prod shape.
        configuration.setAllowCredentials(!wildcard);
        if (wildcard) {
            log.warn("CORS is configured with a wildcard origin, so credentialed cross-origin "
                    + "requests are DISABLED. Set ARAN_ALLOWED_ORIGINS to a comma-separated "
                    + "list of exact origins for any deployment that needs them.");
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

package edu.upc.dtak.aran.app.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token-bucket rate limiting applied to the {@code /api/**} surface.
 *
 * <p>Registered as <b>two passes</b> in {@code SecurityConfig#apiSecurityFilterChain}, each
 * constructed with a different {@link Mode} so the split is explicit rather than inferred from
 * chain position or SecurityContext state:
 * <ol>
 *   <li>{@link Mode#PRE_AUTH} — registered with {@code addFilterBefore(..., BearerTokenAuthenticationFilter.class)}.
 *       Runs before the token is validated, so it always keys on the remote IP (resolved securely
 *       by {@code RemoteIpFilter}) and always consumes the tight anonymous bucket — including for
 *       requests carrying an <em>invalid</em> token. This is what throttles a flood of invalid
 *       JWTs: without this pass, {@code BearerTokenAuthenticationFilter} rejects a bad token with
 *       401 before the request ever reaches a rate limiter, so an attacker can force unbounded
 *       signature verification for free.</li>
 *   <li>{@link Mode#POST_AUTH} — registered with {@code addFilterAfter(..., BearerTokenAuthenticationFilter.class)}.
 *       Runs after the token has been validated. If — and only if — the request is authenticated
 *       (a {@link JwtAuthenticationToken} or an {@link ApiTokenAuthentication} is on the
 *       {@link SecurityContextHolder}), it consumes the generous per-caller bucket — keyed on the
 *       JWT's {@code sub} claim for a browser session, and on the token id for an API token — so
 *       different callers behind the same IP (e.g. all of localhost, for local/e2e traffic) get
 *       separate budgets instead of sharing one. If the request is <em>not</em> authenticated,
 *       this pass is a deliberate no-op —
 *       it must not consume anything, because the pre-auth pass above already accounted for that
 *       request against the IP bucket. Double-counting an anonymous request against both passes
 *       would make the anonymous bucket roughly twice as tight as intended.</li>
 * </ol>
 *
 * <p>Both instances are plain objects constructed inline in {@code SecurityConfig} — deliberately
 * <em>not</em> Spring-managed beans — so Spring Boot's automatic {@code Filter}-bean-to-servlet
 * registration cannot also mount them as container-level filters running a second time outside the
 * security chain (which would double-consume a bucket on every request that reaches the servlet
 * layer). The security chain's {@code addFilterBefore}/{@code addFilterAfter} wiring is the only
 * place either instance executes.
 *
 * <p>Buckets are held in a {@link Cache} (per instance — the two passes never share one, since
 * their key namespaces, {@code "ip:"} and {@code "user:"}, never overlap in what each pass
 * consumes). For a multi-node deployment, replace with a distributed cache (Redis via
 * {@code bucket4j-redis}) so limits are shared across pods. The swap is a single-class change with
 * no impact on the rest of the codebase.
 *
 * <p>Requests that exceed the limit receive HTTP 429 with a
 * {@code Retry-After} header and a RFC 7807 problem-detail body.
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    /** Which pass of the split this filter instance implements. See the class Javadoc. */
    public enum Mode {
        /** Runs before bearer-token authentication; always keys and consumes by IP. */
        PRE_AUTH,
        /** Runs after bearer-token authentication; keys and consumes by JWT subject, or no-ops. */
        POST_AUTH
    }

    // Authenticated users: 300 requests/minute with a burst of 50 on top.
    private static final int AUTHED_REQUESTS_PER_MINUTE = 300;
    private static final int AUTHED_BURST               = 50;

    // Unauthenticated IPs: 60 requests/minute, no burst.
    private static final int ANON_REQUESTS_PER_MINUTE   = 60;

    // Token-bearing traffic, counted per IP BEFORE the token is verified. This is a
    // denial-of-service backstop, not a fairness mechanism: its only job is to stop an
    // attacker forcing unbounded signature verification with garbage tokens. It must sit
    // far above the anonymous tier, because one address can legitimately carry many
    // distinct users — a NAT, a CI runner, or the e2e suite's fourteen loopback logins.
    // Per-user fairness is the POST_AUTH pass's job, once the subject is actually known.
    private static final int PRE_AUTH_TOKEN_REQUESTS_PER_MINUTE = 3_000;

    /** Effective ceiling for the {@code iptoken:} tier; overridable so tests can flood cheaply. */
    private final int preAuthTokenPerMinute;
    /**
     * Effective anonymous-per-IP and authenticated-per-user ceilings. Production always uses the
     * defaults; the integration suite raises them (BaseIntegrationTest), because every MockMvc
     * request in one JVM arrives from one address with no bearer header, so the whole suite shares
     * one anonymous bucket — the Tier A merge's seven new ITs pushed it past 60/min.
     */
    private final int anonPerMinute;
    private final int authedPerMinute;

    private final Mode mode;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    public RateLimitingFilter(Mode mode) {
        this(mode, PRE_AUTH_TOKEN_REQUESTS_PER_MINUTE);
    }

    /**
     * @param preAuthTokenPerMinute ceiling for the pre-auth {@code iptoken:} tier. Exists so a
     *                              test can prove the flood cap trips without issuing thousands
     *                              of requests; production always uses the default.
     */
    public RateLimitingFilter(Mode mode, int preAuthTokenPerMinute) {
        this(mode, preAuthTokenPerMinute, ANON_REQUESTS_PER_MINUTE, AUTHED_REQUESTS_PER_MINUTE);
    }

    /** Every ceiling explicit; see the field javadoc for who overrides them and why. */
    public RateLimitingFilter(Mode mode, int preAuthTokenPerMinute, int anonPerMinute, int authedPerMinute) {
        this.mode = mode;
        this.preAuthTokenPerMinute = preAuthTokenPerMinute;
        this.anonPerMinute = anonPerMinute;
        this.authedPerMinute = authedPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String key = resolveKey(request);
        if (key == null) {
            // POST_AUTH pass, request not authenticated: the PRE_AUTH pass already
            // consumed the IP bucket for this request. Consuming again here would
            // double-count it. No-op through to the next filter.
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = buckets.get(key, this::newBucket);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            rejectWith429(response, key);
        }
    }

    /**
     * Resolves the bucket key for this request, or {@code null} if this pass must not consume a
     * bucket at all (the {@code POST_AUTH} no-op case — see the class Javadoc).
     */
    private String resolveKey(HttpServletRequest request) {
        if (mode == Mode.PRE_AUTH) {
            // Authentication has not happened yet, so the most we can know is whether the
            // request even claims to carry a token. That distinction matters: keying every
            // request onto one tight per-IP bucket would throttle legitimate authenticated
            // traffic at the anonymous rate whenever several users share an address, which
            // is exactly the shared-bucket exhaustion this split exists to undo.
            //
            // IP is resolved securely by RemoteIpFilter; the header is only inspected for
            // presence, never trusted as identity.
            // Basic counts as "claims to carry a credential" alongside Bearer: JDBC clients send
            // their API token in the password field, because Avatica's driver — like every SQL
            // client driving it — offers a user and a password, not a header. Without this a JDBC
            // session lands in the anonymous tier and 429s partway through a large result, since
            // one HTTP request is one fetched frame.
            String authz = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authz != null && (authz.startsWith("Bearer ") || authz.startsWith("Basic "))) {
                return "iptoken:" + request.getRemoteAddr();
            }
            return "ip:" + request.getRemoteAddr();
        }
        // POST_AUTH: key on the caller only if the request is authenticated; otherwise this
        // pass must not consume anything (see doFilterInternal).
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return "user:" + jwt.getToken().getSubject();
        }
        // API tokens are the only credential the programmatic clients have, so without this arm
        // every request from the Python SDK and the JDBC endpoint would skip per-identity
        // limiting entirely and be governed only by the shared per-IP bucket.
        //
        // Keyed per token rather than per user on purpose: a notebook pulling a large result
        // should not be able to exhaust the bucket its owner's browser session is using, and the
        // token id is already what the audit lines name. The "user:" prefix is what sizes the
        // bucket — see newBucket — so a token gets the authenticated allowance, not the
        // anonymous one.
        if (auth instanceof ApiTokenAuthentication apiToken) {
            return "user:token-" + apiToken.tokenId();
        }
        return null;
    }

    private Bucket newBucket(String key) {
        if (key.startsWith("user:")) {
            // Authenticated: refill AUTHED_REQUESTS_PER_MINUTE tokens/min,
            // allow a burst up to that capacity + AUTHED_BURST extra.
            return Bucket.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(authedPerMinute + AUTHED_BURST)
                            .refillGreedy(authedPerMinute, Duration.ofMinutes(1))
                            .build())
                    .build();
        }
        if (key.startsWith("iptoken:")) {
            // PRE_AUTH, token present but not yet verified: a high ceiling that caps an
            // invalid-token flood without throttling many real users behind one address.
            return Bucket.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(preAuthTokenPerMinute)
                            .refillGreedy(preAuthTokenPerMinute, Duration.ofMinutes(1))
                            .build())
                    .build();
        }
        // Unauthenticated / IP keyed: tighter.
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(anonPerMinute)
                        .refillGreedy(anonPerMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    private static void rejectWith429(HttpServletResponse response, String key) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Retry-After: 60s is a safe conservative value regardless of where
        // in the refill window the request landed.
        response.setHeader("Retry-After", "60");
        response.getWriter().write("""
                {"type":"about:blank",\
                "title":"Too Many Requests",\
                "status":429,\
                "detail":"Rate limit exceeded. Retry after the Retry-After interval."}
                """);
    }
}

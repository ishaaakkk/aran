package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates {@code Authorization: Bearer aran_pat_…} requests.
 *
 * <h2>Ordering, and why the header is hidden</h2>
 *
 * <p>Registered before Spring's {@code BearerTokenAuthenticationFilter}. That filter does not skip
 * an already-authenticated request — it resolves whatever bearer token is on the request and tries
 * to validate it as a JWT — so leaving the header in place would make every API-token request fail
 * with 401 from the JWT decoder no matter what this filter decided. Once a token authenticates,
 * the request is therefore wrapped so the {@code Authorization} header is invisible downstream,
 * and the JWT filter finds nothing to do.
 *
 * <p>A token that <em>looks</em> like ours but does not resolve is left alone rather than rejected
 * here: the request continues unauthenticated and the chain's own entry point answers 401. That
 * keeps one place responsible for what an unauthenticated request looks like.
 *
 * <h2>Scope</h2>
 *
 * <p>{@link ApiTokenScope#READ} allows safe methods plus the two POST routes that read rather than
 * write. A read-scoped token cannot promote a schema, delete a dataset or edit a model, which is
 * the point of it being the default for a credential that lives for months.
 */
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthenticationFilter.class);

    private static final String BEARER = "Bearer ";
    private static final String BASIC = "Basic ";

    /** HTTP methods that cannot change state. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * POST routes a READ token may still use, because they read despite the verb.
     *
     * <p>Both are POST only because their input does not belong in a URL: an OQL query is
     * unbounded in length and has no business in an access log. Listed individually rather than by
     * prefix — {@code /api/operators/*} as a pattern would hand a read-only token the
     * bootstrapping and schema-integration operators.
     */
    private static final List<String> READ_ONLY_POSTS = List.of(
            "/api/query/export",
            "/api/operators/query-engine/invoke",
            // Avatica speaks one POST route for everything, including reads. Every operation
            // behind it runs OQL or answers a catalog question — AranMeta has no write path at
            // all — so a read-only token is the right credential for a JDBC client.
            "/api/jdbc");

    private final ApiTokenService tokens;
    private final UserRepository users;
    private final TenantRepository tenants;

    public ApiTokenAuthenticationFilter(ApiTokenService tokens, UserRepository users,
                                        TenantRepository tenants) {
        this.tokens = tokens;
        this.users = users;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = presentedToken(request);
        if (presented == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiToken> resolved = tokens.authenticate(presented);
        if (resolved.isEmpty()) {
            // Unknown, revoked or expired — indistinguishable on purpose. Fall through
            // unauthenticated; the chain answers 401.
            chain.doFilter(request, response);
            return;
        }
        ApiToken token = resolved.get();

        // Checked before the scope, because it does not depend on one: identity and tenant
        // administration are off-limits to every token, so a FULL token cannot inherit its
        // owner's authority to create users or read the tenant's usage. See TokenScopePolicy.
        if (TokenScopePolicy.isAdminRoute(request)) {
            log.warn("API token {} attempted an administrative route ({}) — rejecting",
                    token.id(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Administration requires an interactive session; API tokens cannot be used here.");
            return;
        }

        if (!isAllowedByScope(token.scope(), request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "This API token is read-only.");
            return;
        }

        Optional<AuthenticatedUser> caller = resolveCaller(token);
        if (caller.isEmpty()) {
            // The token is valid but its owner no longer belongs to the tenant — revoking access
            // by removing a membership has to invalidate the person's tokens too, or the
            // credential outlives the relationship it was issued under.
            log.warn("API token {} names a user with no membership in tenant {} — rejecting",
                    token.id(), token.tenantId());
            chain.doFilter(request, response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new ApiTokenAuthentication(caller.get(), token.scope(), token.id()));
        chain.doFilter(withoutAuthorizationHeader(request), response);
    }

    /**
     * The Aran token on the request, presented as a bearer credential or inside HTTP Basic.
     *
     * <p>Basic is here for JDBC. Avatica's remote driver — and every SQL client driving it —
     * offers a user and a password, not a header, so a JDBC user pastes the token into the
     * password field. Either field is accepted because tools disagree about which one an
     * API token belongs in, and a token is self-identifying: it is recognised by its prefix,
     * so there is no ambiguity about which half is the credential.
     */
    static @Nullable String presentedToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        if (header.startsWith(BEARER)) {
            return aranToken(header.substring(BEARER.length()).trim());
        }
        if (header.startsWith(BASIC)) {
            return basicCredential(header.substring(BASIC.length()).trim());
        }
        return null;
    }

    private static @Nullable String basicCredential(String encoded) {
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int separator = decoded.indexOf(':');
        if (separator < 0) {
            return aranToken(decoded);
        }
        String password = aranToken(decoded.substring(separator + 1));
        return password != null ? password : aranToken(decoded.substring(0, separator));
    }

    private static @Nullable String aranToken(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith(ApiTokenService.TOKEN_PREFIX) ? trimmed : null;
    }

    static boolean isAllowedByScope(ApiTokenScope scope, HttpServletRequest request) {
        if (scope == ApiTokenScope.FULL) {
            return true;
        }
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return true;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return READ_ONLY_POSTS.contains(path);
    }

    private Optional<AuthenticatedUser> resolveCaller(ApiToken token) {
        Optional<User> user = users.findById(token.userId());
        if (user.isEmpty()) {
            return Optional.empty();
        }
        return tenants.findMembership(token.userId(), token.tenantId())
                .map((Membership membership) -> new AuthenticatedUser(
                        user.get().id(), user.get().subject(),
                        token.tenantId(), membership.role()));
    }

    /**
     * The same request with {@code Authorization} hidden, so the JWT filter downstream sees an
     * unauthenticated request and leaves the security context this filter just populated alone.
     */
    private static HttpServletRequest withoutAuthorizationHeader(HttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? null : super.getHeader(name);
            }

            @Override public Enumeration<String> getHeaders(String name) {
                return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                        ? Collections.emptyEnumeration() : super.getHeaders(name);
            }

            @Override public Enumeration<String> getHeaderNames() {
                List<String> names = Collections.list(super.getHeaderNames()).stream()
                        .filter(n -> !HttpHeaders.AUTHORIZATION.equalsIgnoreCase(n))
                        .toList();
                return Collections.enumeration(names);
            }
        };
    }
}

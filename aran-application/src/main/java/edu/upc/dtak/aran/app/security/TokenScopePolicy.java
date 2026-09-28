package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * What an API token may do — the whole answer, in one file.
 *
 * <p>Split out of {@link ApiTokenAuthenticationFilter} because the question has three parts and
 * only one of them can be answered from the request line:
 *
 * <ol>
 *   <li><b>Is this an administrative route?</b> Then no token may call it, whatever its scope.
 *       See {@link #ADMIN_ROUTES}.</li>
 *   <li><b>Does the scope allow this method and path?</b> The filter's check.</li>
 *   <li><b>Does the scope allow this <em>operation</em>?</b> Answerable only in the controller,
 *       because the operator's operation is in the request body.</li>
 * </ol>
 */
@NullMarked
public final class TokenScopePolicy {

    private TokenScopePolicy() {
    }

    /**
     * Routes no API token may reach, at any scope.
     *
     * <p>Without this, a {@code FULL} token held by a tenant {@code OWNER} inherits that owner's
     * administrative authority: {@code AuthenticatedUser} carries the membership role, so the
     * token could create and delete users, reset their passwords, and read the whole tenant's
     * usage. A long-lived credential that a notebook keeps on disk should not be able to take over
     * the tenant — that is a different blast radius from the one a user consents to when they
     * generate a token to run a query.
     *
     * <p>{@code /api/tokens} is on the list for the same reason one level down: a leaked token that
     * can mint more tokens survives the revocation of the one that leaked.
     *
     * <p>These routes stay reachable exactly as before with an interactive OIDC session, which is
     * the credential an administrative action ought to require.
     *
     * <p>The two {@code /api/tenants/*} sub-routes are {@code TenantOwnerController}'s mutating
     * half (adding a member, leaving the tenant). The bare {@code /api/tenants} prefix is
     * deliberately <em>not</em> listed: {@code GET /api/tenants/{id}/license} is readable by any
     * member, and a token-driven client legitimately reads its own license and usage.
     */
    static final List<String> ADMIN_ROUTES = List.of(
            "/api/tenant/members/**",
            "/api/tenant/admin/**",
            "/api/admin/**",
            "/api/tokens/**",
            "/api/tenants/*/members/**",
            "/api/tenants/*/membership/**");

    /**
     * {@link #ADMIN_ROUTES} as Spring {@code PathPattern}s — the matcher family MVC routes with,
     * so the block sees the decoded segments the controller mapping will. A raw
     * {@code getRequestURI()} comparison does not: {@code StrictHttpFirewall} rejects an encoded
     * slash, period or percent but lets {@code %74} through, so {@code /api/%74okens} reached
     * {@code ApiTokenController} while a string check read it as some other route (F-AUTHN-3).
     */
    private static final List<RequestMatcher> ADMIN_MATCHERS = ADMIN_ROUTES.stream()
            .map(pattern -> (RequestMatcher) PathPatternRequestMatcher.pathPattern(pattern))
            .toList();

    /**
     * Query-engine operations a {@code READ} token may invoke.
     *
     * <p>{@code /api/operators/query-engine/invoke} is a single POST path carrying the operation in
     * its body, so the filter's path check cannot tell a catalog lookup from a schema mutation. It
     * is allowlisted there because every operation this operator exposes today reads — which is a
     * property of the operator right now, not a guarantee. Naming the read-only set here makes the
     * assumption explicit, and {@code TokenScopePolicyTest} fails the build if the operator grows
     * an operation that is in neither this set nor the known-writing set, so "we forgot to classify
     * it" cannot ship.
     *
     * <p>{@code materialize-oql} counts as a read: it writes a parquet file to the lake, which is
     * how {@code /api/query/export} — already allowed for {@code READ} — produces its bytes.
     * {@code materialize-product-sql} is the same shape on the products half of the JDBC split —
     * a SELECT over already-materialized snapshots, written to a temp file the caller streams.
     * {@code materialize-source-sql} is that shape again on the sources tier — a SELECT over the
     * per-source alias views, same temp-file contract.
     *
     * <p>{@code verify-wrapper} is a read like {@code validate-constraint}: it persists nothing
     * (the verdict it returns is not written back to the model by this call), and its only
     * side effect is a throwaway in-memory DuckDB connection it opens and closes within the
     * request.
     *
     * <p>{@code cube-evidence} is {@code catalog} with types, tiers and profile statistics
     * attached: it is derived entirely from the same cached mapping catalog, touches neither the
     * lake nor a snapshot, and writes nothing. Creating a cube <em>does</em> write, but that goes
     * through {@code /api/projects/&#123;id&#125;/cubes}, not through this path.
     */
    static final Set<String> READ_ONLY_QUERY_ENGINE_OPERATIONS = Set.of(
            "catalog",
            "cube-evidence",
            "explain",
            "run",
            "rewrite-oql",
            "run-oql",
            "validate-constraint",
            "verify-wrapper",
            "materialize-oql",
            "materialize-product-sql",
            "materialize-source-sql",
            "preview-parquet");

    /**
     * True if this request targets a route that no token may reach.
     *
     * <p>Matched on decoded path <em>segments</em>, not on raw string prefixes: a prefix alone
     * makes {@code /api/admin} swallow {@code /api/administration-guide}. Over-matching an
     * authorization rule is not the safe direction it looks like — it silently breaks unrelated
     * routes, and the breakage shows up as a 403 nobody can explain.
     */
    static boolean isAdminRoute(HttpServletRequest request) {
        return ADMIN_MATCHERS.stream().anyMatch(matcher -> matcher.matches(request));
    }

    /**
     * Refuse an operator invocation the caller's token scope does not cover.
     *
     * <p>A no-op for interactive sessions: an OIDC caller is bounded by their tenant role, not by
     * a token scope, so there is nothing here to check. Also a no-op for {@code FULL} tokens.
     *
     * @throws AccessDeniedException if a {@code READ}-scope token invokes an operation that is not
     *         known to be read-only — including any operation on any operator other than the query
     *         engine, since those are the ones the path allowlist never intended to expose.
     */
    public static void requireOperationAllowed(String operatorId, String operation) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof ApiTokenAuthentication token) || token.scope() == ApiTokenScope.FULL) {
            return;
        }
        String normalised = operation == null ? "" : operation.toLowerCase(Locale.ROOT);
        if ("query-engine".equals(operatorId)
                && READ_ONLY_QUERY_ENGINE_OPERATIONS.contains(normalised)) {
            return;
        }
        throw new AccessDeniedException(
                "a READ-scope API token cannot invoke '" + operation + "' on " + operatorId);
    }
}

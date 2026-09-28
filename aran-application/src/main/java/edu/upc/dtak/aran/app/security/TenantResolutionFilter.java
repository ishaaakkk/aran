package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the tenant for each request and binds it as the active
 * {@link TenantContext} for the duration of request processing.
 *
 * <p>Runs <em>after</em> Spring Security's authentication filters (hence the
 * late {@link Order}) so the authenticated caller is already known. The tenant
 * comes from Aran's own membership row — never from a request header or
 * parameter, which a caller could forge, and no longer from a JWT claim backed
 * by a Keycloak user attribute.
 *
 * <p>Binding happens via {@link TenantContextHolder#runWith}, so the scope is
 * torn down automatically when the chain returns — there is no
 * {@code ThreadLocal} to leak onto a pooled thread, which matters because the
 * platform runs request handling on virtual threads.
 *
 * <p>Unauthenticated requests (the public actuator endpoints) simply pass
 * through without a tenant bound; any tenant-scoped code they somehow reach
 * will fail loudly in {@link TenantContextHolder#require()}, which is the
 * desired behaviour.
 */
@Component
@Order(Integer.MAX_VALUE - 100)
public class TenantResolutionFilter extends OncePerRequestFilter {

    private final CurrentUserResolver currentUser;

    public TenantResolutionFilter(CurrentUserResolver currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        TenantContext tenant = resolveTenant();
        if (tenant == null) {
            // No authenticated tenant on this request (e.g. public endpoint).
            filterChain.doFilter(request, response);
            return;
        }

        try {
            TenantContextHolder.runWith(tenant, () -> {
                try {
                    filterChain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    // Re-thrown below; wrapped only to cross the Runnable boundary.
                    throw new FilterExecutionException(e);
                }
            });
        } catch (FilterExecutionException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof ServletException se) {
                throw se;
            }
            throw new ServletException(cause);
        }
    }

    /**
     * The tenant this request acts within, or {@code null} if it has no authenticated caller.
     *
     * <p><b>Both authentication paths bind.</b> This used to read the JWT's {@code tenant_id}
     * claim and return {@code null} for anything else — which meant every API-token request ran
     * with the {@code ScopedValue} unbound, and {@code TenantFilterAspect} only enables
     * Hibernate's tenant filter when it <em>is</em> bound. Endpoints that rely on the aspect
     * rather than passing a tenant explicitly were therefore unfiltered for token callers. That
     * was survivable while tokens were a rarity; the Python SDK and the JDBC endpoint made them a
     * first-class path.
     *
     * <p>Resolution is delegated so there is exactly one answer to "which tenant is this": for a
     * token, the row the filter already resolved; for a JWT, the caller's membership.
     */
    private @Nullable TenantContext resolveTenant() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof ApiTokenAuthentication apiToken) {
            return new TenantContext(apiToken.user().tenantId().toString());
        }
        if (!(authentication instanceof JwtAuthenticationToken)) {
            return null;
        }
        try {
            return new TenantContext(currentUser.require().tenantId().toString());
        } catch (AccessDeniedException | IllegalStateException e) {
            // A validated token whose owner has no membership. Binding nothing lets the request
            // continue to the endpoint, which answers 403 through the normal path — this filter
            // has no business deciding what an unauthorised request looks like.
            return null;
        }
    }

    /** Internal carrier so checked exceptions can cross the {@code Runnable}. */
    private static final class FilterExecutionException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        FilterExecutionException(Throwable cause) {
            super(cause);
        }
    }
}

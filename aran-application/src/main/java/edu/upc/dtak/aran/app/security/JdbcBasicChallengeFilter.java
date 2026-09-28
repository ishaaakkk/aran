package edu.upc.dtak.aran.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Tells a credential-less {@code /api/jdbc} request that HTTP Basic is an option here.
 *
 * <p>{@code ApiTokenAuthenticationFilter} already accepts a token in HTTP Basic — see its class
 * Javadoc — but nothing on this chain ever advertised it: the OAuth2 resource server's own entry
 * point challenges every unauthenticated {@code /api/**} request with {@code WWW-Authenticate:
 * Bearer} only. A client that follows the ordinary HTTP contract (send no credential, read the
 * challenge, retry with whichever scheme was offered) never learns Basic would have worked, and
 * for Avatica's own JDBC driver that is fatal: it does not send {@code avatica_password}
 * preemptively, so it gets an unqualified 401 on its very first request and never retries.
 *
 * <p>Scoped to exactly the one case that needs it — {@code /api/jdbc} with no
 * {@code Authorization} header at all — rather than composed through Spring Security's
 * {@code exceptionHandling().defaultAuthenticationEntryPointFor(...)}, which in this chain
 * produced a session-cookied redirect to {@code /login} instead of a 401 (its composition with
 * the OAuth2 resource server's own entry-point wiring did not resolve the way the DSL suggests
 * it should). A request that already carries a credential — including one this filter cannot
 * itself judge, like a JWT or a malformed token — passes through unchanged to the filters that
 * can.
 */
public class JdbcBasicChallengeFilter extends OncePerRequestFilter {

    private static final String JDBC_PATH = "/api/jdbc";
    private static final String REALM = "aran";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (JDBC_PATH.equals(request.getRequestURI())
                && request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            // setStatus, not sendError: sendError hands the response to the container's own
            // error-page dispatch, and that path is what produced an HTML body here before —
            // Avatica's client only throws on a 401 status, it never reads this body, so there
            // is nothing to gain from routing through /error and a real regression to repeat.
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + REALM + "\"");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        chain.doFilter(request, response);
    }
}

package edu.upc.dtak.aran.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets the job event stream authenticate with a query parameter.
 *
 * <p>The browser's {@code EventSource} cannot set request headers, so an SSE
 * subscription has no way to send {@code Authorization: Bearer …}. This filter
 * takes {@code ?access_token=} and presents it to the rest of the chain as that
 * header, so the stream is authenticated by exactly the same JWT resource-server
 * config as every other {@code /api/**} route — no second auth path, no
 * unauthenticated stream.
 *
 * <p><b>Scope is deliberately one route.</b> Tokens in URLs land in access logs,
 * browser history and {@code Referer} headers, so this applies <em>only</em> to
 * {@code /api/jobs/{id}/events} — the one endpoint that cannot use a header — and
 * only when no {@code Authorization} header is already present.
 *
 * <p><b>And deliberately one kind of token.</b> An {@code aran_pat_} personal access
 * token is not promoted (audit AUTH_IDENTITY-14). What makes the query parameter
 * tolerable for a JWT is that a leaked copy expires in minutes; a PAT is valid for
 * months, is the credential a scripted client holds, and would be sitting in every
 * proxy and access log along the way. This filter runs before
 * {@code ApiTokenAuthenticationFilter}, so without the check that filter would have
 * honoured it. Declining is silent: the request continues unauthenticated and the
 * endpoint answers 401, which is the same outcome as a bad token and needs no second
 * error path. A PAT-holding client that wants this stream sends the header — it is not
 * an {@code EventSource} and has never been unable to.
 *
 * <p>Not a {@code @Component}: it is instantiated by {@code SecurityConfig} and
 * inserted into the API filter chain immediately before
 * {@code BearerTokenAuthenticationFilter}. Registering it as a bean would also
 * auto-register it as a plain servlet filter running <em>after</em> the whole
 * security chain, which is too late to be of any use.
 */
public class SseAccessTokenFilter extends OncePerRequestFilter {

    private static final Pattern JOB_EVENTS_PATH =
            Pattern.compile("^/api/jobs/[^/]+/events$");
    private static final String TOKEN_PARAM = "access_token";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !JOB_EVENTS_PATH.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = request.getParameter(TOKEN_PARAM);
        if (token == null || token.isBlank()
                || token.startsWith(ApiTokenService.TOKEN_PREFIX)
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        filterChain.doFilter(new BearerHeaderRequest(request, "Bearer " + token), response);
    }

    /** Presents the query-parameter token as the Authorization header. */
    private static final class BearerHeaderRequest extends HttpServletRequestWrapper {

        private final String authorization;

        BearerHeaderRequest(HttpServletRequest request, String authorization) {
            super(request);
            this.authorization = authorization;
        }

        @Override
        public String getHeader(String name) {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                return authorization;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                return Collections.enumeration(java.util.List.of(authorization));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            var names = new java.util.LinkedHashSet<String>();
            names.add(HttpHeaders.AUTHORIZATION);
            super.getHeaderNames().asIterator().forEachRemaining(names::add);
            return Collections.enumeration(names);
        }
    }
}

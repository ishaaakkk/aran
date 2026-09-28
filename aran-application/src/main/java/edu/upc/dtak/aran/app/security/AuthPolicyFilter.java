package edu.upc.dtak.aran.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces {@link AuthPolicy} for every request on {@code /api/**}, before any credential is
 * validated.
 *
 * <h2>Why here, and why first</h2>
 *
 * <p>Placed ahead of both authentication filters on purpose. A presentation an operator has
 * forbidden must be refused <em>before</em> the verifier that would honour it runs — otherwise
 * disabling a method only changes the error message, and a disabled method still costs a database
 * lookup or a signature verification per request.
 *
 * <p>It is also the single point that makes the policy true for every client. The Python SDK
 * carries its own refusal to send a token over plain HTTP, which is worth having as
 * defence-in-depth, but it protects exactly one client; the JDBC path uses Avatica's driver and
 * has no such code. Enforcing here means a client that skips the check, or was written by someone
 * else, is bound by the same rules.
 *
 * <h2>TLS</h2>
 *
 * <p>{@code isSecure()} is the right signal because {@code RemoteIpFilter} — already configured
 * with {@code aran.security.trusted-proxies} — sets it from {@code X-Forwarded-Proto} for requests
 * arriving through a trusted proxy, and ignores that header from anywhere else. So a TLS-terminating
 * load balancer is handled, and a client claiming {@code X-Forwarded-Proto: https} directly is not.
 *
 * <p>Loopback is exempt: there is no network segment to intercept, and a local install served over
 * plain HTTP is the normal single-node case rather than a misconfiguration.
 */
@NullMarked
public class AuthPolicyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthPolicyFilter.class);

    private final AuthPolicy policy;

    public AuthPolicyFilter(AuthPolicy policy) {
        this.policy = policy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The policy itself has to be readable without satisfying it, or a client cannot tell a
        // user what this server accepts except by failing.
        return !request.getRequestURI().startsWith("/api/")
                || AuthPolicyController.PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AuthMethod presented = AuthMethod.of(request);
        if (presented == null) {
            // No credential presented. Not this filter's business: the chain's entry point owns
            // what an unauthenticated request looks like, and there is one such place already.
            chain.doFilter(request, response);
            return;
        }

        if (!policy.allows(presented)) {
            log.debug("refused {} on {}: not in aran.security.auth.methods",
                    presented.id(), request.getRequestURI());
            problem(response, HttpStatus.UNAUTHORIZED,
                    "Authentication method not accepted",
                    "This Aran deployment does not accept " + presented.id()
                            + " authentication. Accepted: " + String.join(", ", policy.methodIds())
                            + ". See GET " + AuthPolicyController.PATH + ".");
            return;
        }

        if (policy.requireTls() && !request.isSecure() && !isLoopback(request)) {
            log.debug("refused {} on {}: credential presented without TLS",
                    presented.id(), request.getRequestURI());
            problem(response, HttpStatus.FORBIDDEN,
                    "TLS required",
                    "This Aran deployment refuses credentials sent over an unencrypted "
                            + "connection. Use https://.");
            return;
        }

        chain.doFilter(request, response);
    }

    private static boolean isLoopback(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address) || address.startsWith("127.");
    }

    /**
     * RFC 7807, hand-written.
     *
     * <p>This filter runs before Spring MVC, so {@code ApiExceptionHandler} — which produces the
     * same shape for everything past the dispatcher — is not reachable from here. Clients parse
     * {@code detail}; answering these two refusals in a different shape would make them the only
     * errors a client had to special-case.
     */
    private static void problem(HttpServletResponse response, HttpStatus status, String title,
                                @Nullable String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(title, status.value(), detail == null ? "" : detail));
    }
}

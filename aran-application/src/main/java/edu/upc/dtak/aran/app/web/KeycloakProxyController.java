package edu.upc.dtak.aran.app.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * Same-origin reverse proxy for Keycloak: {@code /auth/**} on Aran's port forwards to the
 * Keycloak container, so the login flow never shows another origin (identity note §4.3 — the
 * themed login page must appear at Aran's own address, no port change, no Keycloak hostname).
 *
 * <p>Off unless {@code aran.auth-proxy.enabled} — the standalone topology's compose file turns
 * it on and points {@code aran.auth-proxy.target} at the Keycloak service. Other profiles are
 * untouched: the cloud/dev topologies reach Keycloak directly.
 *
 * <p>Path-preserving on purpose: Keycloak runs with {@code KC_HTTP_RELATIVE_PATH=/auth}, so the
 * URI crosses unchanged and nothing here rewrites HTML, redirects or cookie paths. The pinned
 * {@code KC_HOSTNAME} on the Keycloak side is what makes issued tokens carry the proxied URL as
 * their issuer, whichever path a request arrived by.
 */
@Controller
@NullMarked
@ConditionalOnProperty(name = "aran.auth-proxy.enabled", havingValue = "true")
public class KeycloakProxyController {

    /**
     * RFC 9110's hop-by-hop set plus the ones the servlet container or HttpClient must own:
     * Host and Content-Length are computed per hop, and forwarding either as-is is how a proxy
     * produces requests that lie about themselves.
     *
     * <p>The {@code x-forwarded-*} family is in the same category and for the same reason (audit
     * AUTH_IDENTITY-5): they describe the hop, so only the proxy that made the hop may assert
     * them. {@code HttpRequest.Builder.header} <em>appends</em>, so a client-supplied
     * X-Forwarded-For survived in front of the one added below — and the first entry is the one
     * a downstream reader treats as the client IP, which hands an attacker the choice of whose
     * brute-force counter their attempts land on. Stripping costs the real client IP behind an
     * outer reverse proxy, which is the correct default: Aran has no trusted-proxy configuration
     * with which to decide when an inbound X-Forwarded-For may be believed.
     */
    private static final Set<String> NOT_FORWARDED = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-forwarded-port");

    /**
     * Ceiling on a buffered request body.
     *
     * <p>{@code /auth/**} is {@code permitAll} and sits outside every rate limiter, so anything
     * this method does, an anonymous caller can do repeatedly and in parallel. Buffering is still
     * the right shape (see {@link #readBounded}), but unbounded buffering on that route is an
     * anonymous memory-exhaustion primitive (audit AUTH_IDENTITY-2). A megabyte is three orders of
     * magnitude above the login forms and token requests that actually cross here.
     */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            // NEVER: redirects belong to the browser. The login flow is a chain of 302s whose
            // Location values the client must see, not silently follow server-side.
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String target;

    public KeycloakProxyController(@Value("${aran.auth-proxy.target}") String target) {
        this.target = target.endsWith("/") ? target.substring(0, target.length() - 1) : target;
    }

    @RequestMapping("/auth/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] body = readBounded(request);
        if (body == null) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "authentication request body too large");
            return;
        }

        String query = request.getQueryString();
        URI upstream = URI.create(target + request.getRequestURI()
                + (query == null ? "" : "?" + query));

        HttpRequest.Builder proxied = HttpRequest.newBuilder(upstream)
                .method(request.getMethod(), bodyOf(body))
                .timeout(Duration.ofSeconds(30));
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                proxied.header(name, values.nextElement());
            }
        }
        // So Keycloak's request-derived behaviour (rate limits, logs) sees the real client.
        proxied.header("X-Forwarded-For", request.getRemoteAddr());

        HttpResponse<InputStream> answer;
        try {
            answer = client.send(proxied.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "authentication upstream interrupted");
            return;
        } catch (IOException e) {
            // The one message a user can act on: the auth service is down, not Aran.
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "authentication service unreachable");
            return;
        }

        response.setStatus(answer.statusCode());
        answer.headers().map().forEach((name, values) -> {
            // HTTP/2 pseudo-headers (":status") appear in HttpClient's header map when the
            // upstream negotiated h2; they are not headers and must not be re-emitted.
            if (!name.startsWith(":") && !NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
        try (InputStream in = answer.body(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /**
     * The whole body, or {@code null} when it exceeds {@link #MAX_BODY_BYTES}.
     *
     * <p>Buffered rather than streamed: login-flow bodies are small forms, and a replayable
     * publisher spares the container/HttpClient a chunked handshake mismatch. Reading one byte
     * past the cap is what distinguishes "exactly at the cap" from "over it" without a
     * Content-Length the client controls and chunked bodies do not carry.
     */
    private static byte @Nullable [] readBounded(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    private static HttpRequest.BodyPublisher bodyOf(byte[] body) {
        return body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
    }
}

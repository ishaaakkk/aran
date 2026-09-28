package edu.upc.dtak.aran.app.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The same-origin reverse proxy for {@code /auth/**} — what keeps Keycloak invisible in the
 * standalone topology (identity note §4.3: themed login at Aran's origin, no port change).
 *
 * <p>The upstream here is a real HTTP server (JDK built-in), not a mock of the client: the
 * claim under test is that a request crosses the proxy with its method, path, query, body and
 * material headers intact, and that is only demonstrable against a socket.
 */
class KeycloakProxyControllerTest {

    private static HttpServer upstream;
    private static MockMvc mvc;
    /** What the upstream saw, keyed by path — assertions read this. */
    private static final Map<String, String> seenBodies = new ConcurrentHashMap<>();
    private static final Map<String, String> seenMethods = new ConcurrentHashMap<>();
    private static final Map<String, String> seenQueries = new ConcurrentHashMap<>();
    private static final Map<String, String> seenContentTypes = new ConcurrentHashMap<>();
    /** Every value of every forwarded header, keyed by path then lower-cased header name. */
    private static final Map<String, Map<String, List<String>>> seenHeaders =
            new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/auth", exchange -> {
            String path = exchange.getRequestURI().getPath();
            seenMethods.put(path, exchange.getRequestMethod());
            seenQueries.put(path, String.valueOf(exchange.getRequestURI().getQuery()));
            seenContentTypes.put(path,
                    String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            Map<String, List<String>> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), List.copyOf(values)));
            seenHeaders.put(path, headers);
            seenBodies.put(path,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            if (path.endsWith("/redirect")) {
                exchange.getResponseHeaders().add("Location", "/auth/realms/aran/after");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] body = "<html>aran login</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html;charset=utf-8");
            exchange.getResponseHeaders().add("Set-Cookie", "AUTH_SESSION_ID=abc; Path=/auth");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstream.start();
        String target = "http://127.0.0.1:" + upstream.getAddress().getPort();
        mvc = MockMvcBuilders.standaloneSetup(new KeycloakProxyController(target)).build();
    }

    @AfterAll
    static void stop() {
        upstream.stop(0);
    }

    @Test
    @DisplayName("a GET crosses with path and query intact, and the response body comes back")
    void getPassesThrough() throws Exception {
        MvcResult result = mvc.perform(get("/auth/realms/aran/protocol/openid-connect/auth")
                        .queryParam("client_id", "aran-web").queryParam("state", "xyz"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("aran login");
        assertThat(result.getResponse().getContentType()).contains("text/html");
        assertThat(seenMethods).containsEntry("/auth/realms/aran/protocol/openid-connect/auth", "GET");
        assertThat(seenQueries.get("/auth/realms/aran/protocol/openid-connect/auth"))
                .contains("client_id=aran-web").contains("state=xyz");
    }

    @Test
    @DisplayName("a POST form body reaches the upstream with its content type")
    void postFormPassesThrough() throws Exception {
        mvc.perform(post("/auth/realms/aran/protocol/openid-connect/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=password&username=alice"))
                .andReturn();

        assertThat(seenBodies).containsEntry(
                "/auth/realms/aran/protocol/openid-connect/token",
                "grant_type=password&username=alice");
        assertThat(seenContentTypes.get("/auth/realms/aran/protocol/openid-connect/token"))
                .contains("application/x-www-form-urlencoded");
    }

    @Test
    @DisplayName("a redirect passes through untouched — the login flow is made of them")
    void redirectPassesThrough() throws Exception {
        MvcResult result = mvc.perform(get("/auth/realms/aran/redirect")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/auth/realms/aran/after");
    }

    @Test
    @DisplayName("cookies set by the upstream reach the browser")
    void setCookiePassesThrough() throws Exception {
        MvcResult result = mvc.perform(get("/auth/realms/aran/cookie")).andReturn();

        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).anyMatch(c -> c.contains("AUTH_SESSION_ID=abc"));
    }

    @Test
    @DisplayName("hop-by-hop headers are not forwarded back")
    void hopByHopStripped() throws Exception {
        MvcResult result = mvc.perform(get("/auth/realms/aran/cookie")).andReturn();

        assertThat(result.getResponse().getHeader("Transfer-Encoding")).isNull();
        assertThat(result.getResponse().getHeader("Connection")).isNull();
    }

    /**
     * Audit AUTH_IDENTITY-2. {@code /auth/**} is permitAll and sits outside every rate limiter,
     * so whatever this method does, an anonymous caller can do repeatedly and in parallel.
     */
    @Test
    @DisplayName("a body larger than the cap is refused with 413, and never reaches Keycloak")
    void oversizedBodyIsRefused() throws Exception {
        byte[] tooBig = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(tooBig, (byte) 'a');

        MvcResult result = mvc.perform(post("/auth/realms/aran/oversized")
                        .contentType("application/x-www-form-urlencoded")
                        .content(tooBig))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(413);
        assertThat(seenBodies).doesNotContainKey("/auth/realms/aran/oversized");
    }

    @Test
    @DisplayName("a body at the cap still crosses — the limit is a ceiling, not a new refusal")
    void bodyAtTheCapStillPasses() throws Exception {
        byte[] atCap = new byte[1024 * 1024];
        java.util.Arrays.fill(atCap, (byte) 'b');

        MvcResult result = mvc.perform(post("/auth/realms/aran/atcap")
                        .contentType("application/x-www-form-urlencoded")
                        .content(atCap))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(seenBodies.get("/auth/realms/aran/atcap")).hasSize(atCap.length);
    }

    /**
     * Audit AUTH_IDENTITY-5. Keycloak's brute-force detection keys on the first X-Forwarded-For
     * entry, so a client that supplies its own decides which "client IP" gets locked out — its
     * own attempts land on someone else's counter.
     */
    @Test
    @DisplayName("a client-supplied X-Forwarded-For is dropped; only the proxy's own is sent")
    void clientForwardedForIsNotPassedThrough() throws Exception {
        mvc.perform(get("/auth/realms/aran/xff")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Port", "8443"))
                .andReturn();

        Map<String, List<String>> headers = seenHeaders.get("/auth/realms/aran/xff");
        assertThat(headers).isNotNull();
        assertThat(headers.get("x-forwarded-for"))
                .as("only the proxy's own X-Forwarded-For may reach Keycloak")
                .containsExactly("127.0.0.1");
        assertThat(headers).doesNotContainKeys(
                "x-forwarded-proto", "x-forwarded-host", "x-forwarded-port");
    }
}

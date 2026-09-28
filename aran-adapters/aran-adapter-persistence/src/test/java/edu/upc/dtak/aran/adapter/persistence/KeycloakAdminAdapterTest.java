package edu.upc.dtak.aran.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Keycloak Admin API adapter, against a stub Keycloak on a loopback port.
 *
 * <p>A real HTTP server rather than a mocked client, for the same reason the Python SDK's tests
 * use one: everything that can go wrong here is in the seam — the service-account token being
 * fetched and reused, the new user's subject arriving only in a {@code Location} header, and a
 * failure aborting rather than degrading.
 */
class KeycloakAdminAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> paths = new ArrayList<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();

    private volatile int createStatus = 201;
    private volatile boolean sendLocation = true;
    private volatile int putStatus = 204;
    private volatile String lookupBody = "[]";
    private final List<String> queries = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/realms/aran/protocol/openid-connect/token", exchange -> {
            tokenRequests.incrementAndGet();
            respond(exchange, 200,
                    "{\"access_token\":\"stub-token\",\"expires_in\":300}", null);
        });

        server.createContext("/admin/realms/aran/users", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                queries.add(query);
            }
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            if (exchange.getRequestMethod().equals("POST")) {
                respond(exchange, createStatus, "",
                        sendLocation ? baseUrl + "/admin/realms/aran/users/new-subject-id" : null);
            } else if (path.endsWith("/reset-password") || exchange.getRequestMethod().equals("DELETE")) {
                respond(exchange, 204, "", null);
            } else if (exchange.getRequestMethod().equals("PUT")) {
                respond(exchange, putStatus, "", null);
            } else if (exchange.getRequestMethod().equals("GET") && query != null) {
                respond(exchange, 200, lookupBody, null);
            } else {
                respond(exchange, 404, "", null);
            }
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String body, String location) throws IOException {
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private KeycloakAdminAdapter adapter() {
        return new KeycloakAdminAdapter(baseUrl, "aran", "aran-backend", "secret",
                new ObjectMapper(), HttpClient.newHttpClient());
    }

    @Nested
    @DisplayName("creating a user")
    class Creating {

        @Test
        @DisplayName("returns the subject Keycloak puts in the Location header")
        void returnsSubject() {
            String subject = adapter().createUser("alice", "alice@upc.edu", "Alice");

            assertThat(subject).isEqualTo("new-subject-id");
            assertThat(paths).contains("POST /admin/realms/aran/users");
        }

        @Test
        @DisplayName("a duplicate account is reported as such, not as a generic failure")
        void duplicateIsRecognisable() {
            createStatus = 409;

            // The subtype is what lets the web boundary answer 409 instead of 500 (J7-F2).
            assertThatThrownBy(() -> adapter().createUser("alice", "a@b.c", "Alice"))
                    .isInstanceOf(IdentityProviderPort.AccountAlreadyExistsException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("a created account whose subject is unknown is an error, not a silent success")
        void missingLocationFails() {
            // The account now exists in Keycloak and Aran cannot name it. Returning normally would
            // commit an Aran row pointing at nothing.
            sendLocation = false;

            assertThatThrownBy(() -> adapter().createUser("alice", "a@b.c", "Alice"))
                    .isInstanceOf(IdentityProviderPort.IdentityProviderException.class)
                    .hasMessageContaining("subject is unknown");
        }
    }

    @Nested
    @DisplayName("the service-account token")
    class Token {

        @Test
        @DisplayName("is fetched once and reused across calls")
        void isCached() {
            KeycloakAdminAdapter adapter = adapter();

            adapter.createUser("a", "a@b.c", "A");
            adapter.setTemporaryPassword("subject-1", "pw");
            adapter.disableUser("subject-1");

            assertThat(tokenRequests.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("an unreachable Keycloak aborts rather than degrading")
        void unreachableAborts() {
            // User creation is transactional; a provider that cannot be reached must fail the
            // caller's operation, never let it continue and commit half of it.
            var unreachable = new KeycloakAdminAdapter("http://127.0.0.1:9", "aran", "id",
                    "secret", new ObjectMapper(), HttpClient.newHttpClient());

            assertThatThrownBy(() -> unreachable.createUser("a", "a@b.c", "A"))
                    .isInstanceOf(IdentityProviderPort.IdentityProviderException.class)
                    .hasMessageContaining("could not reach Keycloak");
        }
    }

    @Nested
    @DisplayName("removal")
    class Removal {

        @Test
        @DisplayName("deleting an account that is already gone is not an error")
        void deleteIsIdempotent() {
            adapter().deleteUser("unknown-subject");
        }

        @Test
        @DisplayName("setting a password sends it as temporary, so it cannot survive first login")
        void passwordIsTemporary() {
            adapter().setTemporaryPassword("subject-1", "generated");

            assertThat(paths).anyMatch(p -> p.contains("reset-password"));
        }
    }

    /** UI-OD9: re-adding a removed member's email finds and re-enables their account. */
    @Nested
    @DisplayName("reinstatement")
    class Reinstatement {

        @Test
        @DisplayName("an account is found by its exact email, whatever its username")
        void findsByEmail() {
            lookupBody = "[{\"id\":\"marta-subject\",\"username\":\"marta\",\"email\":\"Marta@UPC.edu\"}]";

            assertThat(adapter().findSubjectByEmail("marta@upc.edu")).contains("marta-subject");
            assertThat(queries).anyMatch(q -> q.contains("exact=true")
                    && q.contains("email=marta%40upc.edu"));
        }

        @Test
        @DisplayName("a lookup that matches only a different address answers empty")
        void prefixMatchIsNotAMatch() {
            lookupBody = "[{\"id\":\"other\",\"email\":\"marta@upc.edu.example\"}]";

            assertThat(adapter().findSubjectByEmail("marta@upc.edu")).isEmpty();
        }

        @Test
        @DisplayName("enabling sends enabled=true to the user")
        void enables() {
            adapter().enableUser("marta-subject");

            assertThat(paths).contains("PUT /admin/realms/aran/users/marta-subject");
            assertThat(bodies).anyMatch(b -> b.contains("\"enabled\":true"));
        }

        @Test
        @DisplayName("enabling an account that is gone fails, so the membership rolls back")
        void enablingAMissingAccountFails() {
            putStatus = 404;

            assertThatThrownBy(() -> adapter().enableUser("gone"))
                    .isInstanceOf(IdentityProviderPort.IdentityProviderException.class)
                    .hasMessageContaining("enable");
        }
    }
}

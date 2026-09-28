package edu.upc.dtak.aran.adapter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter against a real Keycloak, booted from the dev realm the stack ships
 * ({@code keycloak/import/aran-realm.json}), through the {@code aran-backend} service account and
 * nothing else.
 *
 * <p>What {@link KeycloakAdminAdapterTest}'s stub server cannot say: that Keycloak finds an account
 * by email, that the partial {@code {"enabled":…}} PUT really switches an account off and on, and
 * that it does so <em>without clearing the profile</em> — a reinstated member who came back with
 * no email would be a new defect wearing the fix for J7-F2 (UI-OD9).
 */
@Tag("docker")
@DisplayName("KeycloakAdminAdapter against a real Keycloak (UI-OD9 reinstatement)")
class KeycloakAdminAdapterIT {

    private static final String REALM = "aran";
    private static final String CLIENT_ID = "aran-backend";
    /** The dev realm's published secret — this realm is the one that ships for local use. */
    private static final String CLIENT_SECRET = "dev-backend-secret-change-in-prod";

    private static GenericContainer<?> keycloak;
    private static String baseUrl;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void startKeycloak() {
        keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.2")
                .withCommand("start-dev", "--import-realm")
                .withCopyFileToContainer(MountableFile.forHostPath(realmFile()),
                        "/opt/keycloak/data/import/aran-realm.json")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/" + REALM).forPort(8080).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        keycloak.start();
        baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    @AfterAll
    static void stopKeycloak() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    /** Found from the working directory upwards, so the IT reads the realm the stack ships. */
    private static Path realmFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("keycloak/import/aran-realm.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("keycloak/import/aran-realm.json not found above "
                + Path.of("").toAbsolutePath());
    }

    private static KeycloakAdminAdapter adapter() {
        return new KeycloakAdminAdapter(baseUrl, REALM, CLIENT_ID, CLIENT_SECRET, JSON);
    }

    /** Reads the account back with the service account's own token — the test's independent eye. */
    private static JsonNode user(String subject) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + CLIENT_ID
                + "&client_secret=" + URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8);
        HttpResponse<String> token = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        String bearer = JSON.readTree(token.body()).path("access_token").asText();
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/realms/" + REALM + "/users/" + subject))
                .header("Authorization", "Bearer " + bearer).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @Test
    @DisplayName("disable then enable round-trips the account with its profile intact")
    void disableThenEnableKeepsTheProfile() throws Exception {
        KeycloakAdminAdapter adapter = adapter();
        String email = "marta-" + UUID.randomUUID() + "@example.org";
        String subject = adapter.createUser(email, email, "Marta Pérez");

        adapter.disableUser(subject);
        assertThat(user(subject).path("enabled").asBoolean()).isFalse();

        adapter.enableUser(subject);
        JsonNode back = user(subject);
        assertThat(back.path("enabled").asBoolean()).isTrue();
        assertThat(back.path("email").asText()).isEqualTo(email);
        assertThat(back.path("firstName").asText()).isEqualTo("Marta Pérez");
        assertThat(back.path("username").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("an account is found by email — the re-add path's first question")
    void findsByEmail() {
        KeycloakAdminAdapter adapter = adapter();
        String email = "joan-" + UUID.randomUUID() + "@example.org";
        String subject = adapter.createUser(email, email, "Joan");

        assertThat(adapter.findSubjectByEmail(email)).contains(subject);
        assertThat(adapter.findSubjectByEmail(email.toUpperCase(java.util.Locale.ROOT)))
                .contains(subject);
        assertThat(adapter.findSubjectByEmail("nobody-" + UUID.randomUUID() + "@example.org"))
                .isEmpty();
    }

    @Test
    @DisplayName("a realm-seeded account is found by its email even though its username differs")
    void seededAccountByEmail() {
        assertThat(adapter().findSubjectByEmail("alice@example.com")).isPresent();
        assertThat(adapter().findSubjectByUsername("alice@example.com")).isEmpty();
    }

    @Test
    @DisplayName("creating a second account for an existing email is refused as a duplicate")
    void duplicateEmailIsRefused() {
        KeycloakAdminAdapter adapter = adapter();
        String email = "dup-" + UUID.randomUUID() + "@example.org";
        adapter.createUser(email, email, "Dup");

        // The exact refusal the reinstate branch exists to avoid reaching (J7-F2).
        assertThatThrownBy(() -> adapter.createUser(email, email, "Dup again"))
                .isInstanceOf(IdentityProviderPort.IdentityProviderException.class)
                .hasMessageContaining("already exists");
    }

    /**
     * Reinstatement's order (TenantMemberService.reinstate): a disabled account takes a new
     * temporary password, then is enabled. Proven against Keycloak's own login, not only its admin
     * read-back: the old password is refused as a bad credential, the new one is accepted and held
     * at "choose your own".
     */
    @Test
    @DisplayName("a reinstated account's old password stops working; the new temporary one is the credential")
    void reinstatementReplacesTheOldPassword() throws Exception {
        KeycloakAdminAdapter adapter = adapter();
        String email = "ana-" + UUID.randomUUID() + "@example.org";
        String subject = adapter.createUser(email, email, "Ana");
        adapter.setTemporaryPassword(subject, "OldPassw0rd-a1");
        adapter.disableUser(subject);

        adapter.setTemporaryPassword(subject, "NewPassw0rd-b2");
        adapter.enableUser(subject);

        JsonNode back = user(subject);
        assertThat(back.path("enabled").asBoolean()).isTrue();
        assertThat(back.path("requiredActions").toString()).contains("UPDATE_PASSWORD");
        assertThat(passwordGrantError(email, "OldPassw0rd-a1"))
                .isEqualTo("Invalid user credentials");
        assertThat(passwordGrantError(email, "NewPassw0rd-b2"))
                .as("the right password, held at the forced change")
                .isNotEqualTo("Invalid user credentials");
    }

    /** A password grant through the realm's public CLI client; the error_description it answers. */
    private static String passwordGrantError(String username, String password) throws Exception {
        String form = "grant_type=password&client_id=aran-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body()).path("error_description").asText();
    }

    @Test
    @DisplayName("enabling an account that does not exist fails rather than pretending")
    void enablingAMissingAccountFails() {
        assertThatThrownBy(() -> adapter().enableUser(UUID.randomUUID().toString()))
                .isInstanceOf(IdentityProviderPort.IdentityProviderException.class);
    }
}

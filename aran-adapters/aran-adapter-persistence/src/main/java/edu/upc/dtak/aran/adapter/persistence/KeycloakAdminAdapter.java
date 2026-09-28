package edu.upc.dtak.aran.adapter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * {@link IdentityProviderPort} over Keycloak's Admin REST API.
 *
 * <h2>Why a service account and not the admin user</h2>
 *
 * <p>This authenticates as a confidential client (`aran-backend`) holding only `manage-users` and
 * `view-users` from `realm-management`. The alternatives were worse: `admin-cli` with the
 * bootstrap admin password would give Aran full control of the realm, and the SPA's `aran-web`
 * client is public — it holds no secret, so it has no service account at all.
 *
 * <p>No human ever logs in through this client. It exists so Aran's backend can say "create this
 * user" on behalf of a tenant owner who is working entirely inside Aran's admin UI and never sees
 * Keycloak.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>No user attributes, no realm-role mapping, no group membership. A user's tenant and role live
 * in Aran's {@code membership} table; Keycloak is asked only who somebody is. That is why this
 * class is four operations rather than an integration.
 */
@Service
@ConditionalOnProperty(name = "aran.identity.provider", havingValue = "keycloak",
        matchIfMissing = true)
@NullMarked
public class KeycloakAdminAdapter implements IdentityProviderPort {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminAdapter.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Re-fetch the service-account token this long before it actually expires. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final String issuerUrl;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String cachedToken = "";
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    @Autowired
    public KeycloakAdminAdapter(
            @Value("${aran.identity.keycloak.base-url}") String baseUrl,
            @Value("${aran.identity.keycloak.realm}") String realm,
            @Value("${aran.identity.keycloak.client-id}") String clientId,
            @Value("${aran.identity.keycloak.client-secret}") String clientSecret,
            ObjectMapper objectMapper) {
        this(baseUrl, realm, clientId, clientSecret, objectMapper,
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
    }

    KeycloakAdminAdapter(String baseUrl, String realm, String clientId, String clientSecret,
                         ObjectMapper objectMapper, HttpClient httpClient) {
        this.issuerUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String createUser(String username, String email, String displayName) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "username", username,
                    "email", email,
                    "firstName", displayName,
                    "enabled", true,
                    "emailVerified", false));
        } catch (Exception e) {
            throw new IdentityProviderException("could not serialise the user payload", e);
        }

        HttpResponse<String> response = send(
                adminRequest("/users").POST(HttpRequest.BodyPublishers.ofString(body)),
                "create user");

        if (response.statusCode() == 409) {
            throw new IdentityProviderPort.AccountAlreadyExistsException(
                    "an account with that username or email already exists");
        }
        if (response.statusCode() != 201) {
            throw new IdentityProviderException(
                    "Keycloak refused to create the user: " + response.statusCode()
                            + " " + response.body());
        }

        // Keycloak returns the new user's id only in the Location header. Reading it back by
        // username would race a concurrent rename and is one more round trip.
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new IdentityProviderException(
                        "Keycloak created the user but returned no Location header, so its "
                                + "subject is unknown — the account exists and Aran cannot name it"));
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Override
    public java.util.Optional<String> findSubjectByUsername(String username) {
        HttpResponse<String> response = send(
                adminRequest("/users?exact=true&username=" + encode(username)).GET(),
                "look up a user");

        if (response.statusCode() != 200) {
            throw new IdentityProviderException(
                    "Keycloak refused the user lookup: " + response.statusCode());
        }
        try {
            JsonNode array = objectMapper.readTree(response.body());
            if (!array.isArray() || array.isEmpty()) {
                return java.util.Optional.empty();
            }
            String id = array.get(0).path("id").asText("");
            return id.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(id);
        } catch (Exception e) {
            throw new IdentityProviderException("could not read Keycloak's user lookup", e);
        }
    }

    @Override
    public java.util.Optional<String> findSubjectByEmail(String email) {
        HttpResponse<String> response = send(
                adminRequest("/users?exact=true&email=" + encode(email)).GET(),
                "look up a user by email");

        if (response.statusCode() != 200) {
            throw new IdentityProviderException(
                    "Keycloak refused the user lookup: " + response.statusCode());
        }
        try {
            JsonNode array = objectMapper.readTree(response.body());
            if (!array.isArray()) {
                return java.util.Optional.empty();
            }
            // exact=true already matches the whole address; the comparison is a second guard in
            // case a server version treats it as a prefix search, as older ones did.
            for (JsonNode user : array) {
                if (email.equalsIgnoreCase(user.path("email").asText(""))) {
                    String id = user.path("id").asText("");
                    if (!id.isEmpty()) {
                        return java.util.Optional.of(id);
                    }
                }
            }
            return java.util.Optional.empty();
        } catch (Exception e) {
            throw new IdentityProviderException("could not read Keycloak's user lookup", e);
        }
    }

    @Override
    public void setTemporaryPassword(String subject, String password) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "type", "password",
                    "value", password,
                    "temporary", true));
        } catch (Exception e) {
            throw new IdentityProviderException("could not serialise the credential payload", e);
        }

        HttpResponse<String> response = send(
                adminRequest("/users/" + encode(subject) + "/reset-password")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)),
                "set password");

        if (response.statusCode() != 204) {
            throw new IdentityProviderException(
                    "Keycloak refused to set the password: " + response.statusCode());
        }
    }

    @Override
    public void disableUser(String subject) {
        HttpResponse<String> response = send(
                adminRequest("/users/" + encode(subject))
                        .method("PUT", HttpRequest.BodyPublishers.ofString("{\"enabled\":false}")),
                "disable user");

        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new IdentityProviderException(
                    "Keycloak refused to disable the user: " + response.statusCode());
        }
    }

    @Override
    public void enableUser(String subject) {
        HttpResponse<String> response = send(
                adminRequest("/users/" + encode(subject))
                        .method("PUT", HttpRequest.BodyPublishers.ofString("{\"enabled\":true}")),
                "enable user");

        // Unlike disable, a 404 is a failure: reinstating an account that is gone would add a
        // member who cannot sign in, and the caller's transaction must roll the membership back.
        if (response.statusCode() != 204) {
            throw new IdentityProviderException(
                    "Keycloak refused to enable the user: " + response.statusCode());
        }
    }

    @Override
    public void deleteUser(String subject) {
        HttpResponse<String> response = send(
                adminRequest("/users/" + encode(subject)).DELETE(), "delete user");

        // 404 is success: the account is gone, which is what was asked for.
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new IdentityProviderException(
                    "Keycloak refused to delete the user: " + response.statusCode());
        }
    }

    private HttpRequest.Builder adminRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(issuerUrl + "/admin/realms/" + realm + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + serviceAccountToken())
                .header("Content-Type", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String what) {
        try {
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderException("interrupted while trying to " + what, e);
        } catch (Exception e) {
            // Unreachable Keycloak must abort the caller's transaction, not degrade quietly:
            // user creation is all-or-nothing.
            throw new IdentityProviderException("could not reach Keycloak to " + what, e);
        }
    }

    /**
     * A client-credentials token for the service account, cached until shortly before it expires.
     *
     * <p>Without the cache every admin call costs two round trips. The margin exists because a
     * token that expires in flight fails the operation it was fetched for.
     */
    private String serviceAccountToken() {
        if (Instant.now().isBefore(cachedTokenExpiry) && !cachedToken.isEmpty()) {
            return cachedToken;
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret);

        HttpResponse<String> response;
        try {
            response = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(issuerUrl + "/realms/" + realm + "/protocol/openid-connect/token"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderException("interrupted while authenticating to Keycloak", e);
        } catch (Exception e) {
            throw new IdentityProviderException("could not reach Keycloak to authenticate", e);
        }

        if (response.statusCode() != 200) {
            // Deliberately does not echo the body: it is an auth failure for a client secret.
            log.error("service-account authentication failed with status {}", response.statusCode());
            throw new IdentityProviderException(
                    "Aran could not authenticate to Keycloak as its service account. Check "
                            + "aran.identity.keycloak.client-id/client-secret and that the "
                            + "client has manage-users.");
        }
        try {
            JsonNode node = objectMapper.readTree(response.body());
            String token = node.path("access_token").asText("");
            long expiresIn = node.path("expires_in").asLong(60);
            if (token.isEmpty()) {
                throw new IdentityProviderException("Keycloak returned no access_token");
            }
            cachedToken = token;
            cachedTokenExpiry = Instant.now().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
            return token;
        } catch (IdentityProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityProviderException("could not read Keycloak's token response", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

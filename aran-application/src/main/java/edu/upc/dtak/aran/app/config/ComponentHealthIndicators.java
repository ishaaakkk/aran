package edu.upc.dtak.aran.app.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liveness checks for the services Aran depends on but does not contain.
 *
 * <p>Spring contributes indicators for what it manages — the datasource, disk, the Flyway
 * migration state — and nothing at all for Fuseki, the lake, freyja or Keycloak. So
 * {@code /actuator/health} said UP while three of the four things a query needs were down, and
 * the "ONLINE" dot in the workspace top bar inherited that: it checks the API and Keycloak's
 * discovery document and infers the rest.
 *
 * <p><b>These do not gate the probes, and must not.</b> Boot's {@code liveness} and
 * {@code readiness} groups contain only the availability-state indicators unless a group is
 * configured to include more, so these four contribute to the overall health and to nothing an
 * orchestrator acts on. That is the intent: an unreachable freyja means profiling is unavailable,
 * not that this process should be restarted, and putting it in the liveness group would turn a
 * degraded sidecar into a crash loop of the whole application. They exist to be <em>read</em>, by
 * an operator deciding which of six containers to restart.
 *
 * <p>Each check is a plain GET with a short timeout, deliberately not authenticated. The question
 * is "is this process answering", and a 401 answers it: something is listening and speaking HTTP.
 * Using the real credential would turn a health check into a second place credentials can be
 * wrong, and a rotation mistake would look like an outage.
 */
@Configuration
@NullMarked
public class ComponentHealthIndicators {

    /**
     * Short on purpose. A health check that hangs for thirty seconds is worse than one that
     * reports DOWN: it makes the screen that tells you what is broken be the thing that is broken.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Bean
    HealthIndicator fusekiHealthIndicator(
            @Value("${aran.fuseki.base-url:http://localhost:3030}") String baseUrl) {
        // Fuseki's ping endpoint is unauthenticated by design and returns 200 with a timestamp.
        return () -> probe("fuseki", baseUrl + "/$/ping", baseUrl, false);
    }

    @Bean
    HealthIndicator lakeHealthIndicator(
            @Value("${aran.lake.url:http://localhost:8085}") String baseUrl) {
        return () -> probe("aran-lake", baseUrl + "/api/health", baseUrl, false);
    }

    @Bean
    HealthIndicator freyjaHealthIndicator(
            @Value("${aran.freyja.url:http://localhost:8086}") String baseUrl) {
        // /healthz is FREYJA's only health route, and it answers 503 when the join-ranking model
        // failed to load. Probing /health got a 404, which counted as UP, so a sidecar that
        // could not rank a single join still looked healthy.
        return () -> probe("freyja", baseUrl + "/healthz", baseUrl, true);
    }

    @Bean
    HealthIndicator keycloakHealthIndicator(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        if (issuerUri.isBlank()) {
            // No issuer configured means no OIDC in this profile — reporting DOWN would be
            // announcing the absence of something nobody asked for.
            return () -> Health.up().withDetail("configured", false).build();
        }
        return () -> probe("keycloak", issuerUri + "/.well-known/openid-configuration", issuerUri, false);
    }

    /**
     * One GET, and a status code is enough.
     *
     * <p>Any HTTP response means the process is alive and listening, including 401 and 404 — the
     * component is up, Aran simply asked it something it did not want to answer. Only a transport
     * failure or a timeout is DOWN, which is the distinction an operator needs: "restart this
     * container" versus "this is fine, look elsewhere".
     *
     * <p>{@code serverErrorIsDown} is for a route that is a real health endpoint: its 5xx is the
     * component reporting itself unfit, not a question it declined.
     */
    Health probe(String component, String url, String reportedUrl, boolean serverErrorIsDown) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            Health.Builder health = serverErrorIsDown && response.statusCode() >= 500
                    ? Health.down()
                    : Health.up();
            return health
                    .withDetail("component", component)
                    .withDetail("url", reportedUrl)
                    .withDetail("status", response.statusCode())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down()
                    .withDetail("component", component)
                    .withDetail("url", reportedUrl)
                    .withDetail("error", "interrupted")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("component", component)
                    .withDetail("url", reportedUrl)
                    // The class name, not the message: messages from a connect failure routinely
                    // carry the resolved host and port, and this is rendered on a screen.
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}

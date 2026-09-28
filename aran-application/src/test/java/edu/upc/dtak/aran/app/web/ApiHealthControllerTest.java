package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;

/**
 * The SPA's same-origin health probe answers in actuator's shape — {@code status} and
 * {@code components.db.status}, the two fields the top bar and the offline screen read — with
 * statuses and nothing else, since it is public. The anonymous access itself is
 * {@code ApiHealthIT}'s to prove, against the real security chain.
 */
@DisplayName("GET /api/health — statuses only, in actuator's shape")
class ApiHealthControllerTest {

    // HealthDescriptor is sealed and its one leaf's constructor is package-private, so the leaf is
    // mocked (the inline mock maker handles final classes).
    private static HealthDescriptor status(Status status) {
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        when(descriptor.getStatus()).thenReturn(status);
        return descriptor;
    }

    @Test
    @DisplayName("readiness is the process's status, db is the datasource's, and nothing else is said")
    void readinessAndDatabaseOnly() throws Exception {
        HealthDescriptor up = status(Status.UP);
        HealthDescriptor down = status(Status.DOWN);
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.healthForPath("readiness")).thenReturn(up);
        when(endpoint.healthForPath("db")).thenReturn(down);

        ApiHealthController.ApiHealth health = new ApiHealthController(endpoint).health();

        assertThat(new ObjectMapper().writeValueAsString(health))
                .isEqualTo("{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"DOWN\"}}}");
    }

    @Test
    @DisplayName("a group the deployment does not define reads UNKNOWN, not UP")
    void missingGroupIsUnknown() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);

        ApiHealthController.ApiHealth health = new ApiHealthController(endpoint).health();

        assertThat(health.status()).isEqualTo("UNKNOWN");
        assertThat(health.components().get("db").status()).isEqualTo("UNKNOWN");
    }
}

package edu.upc.dtak.aran.app.web;

import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SPA's same-origin "is the backend up" probe, readable without signing in.
 *
 * <p>The top bar's status panel and the offline screen read {@code /actuator/health} from the page's
 * own origin, and the API port does not serve {@code /actuator} — by design: actuator lives on the
 * internal-only management port. So a healthy standalone stack showed a red OFFLINE. This answers
 * on the API port in actuator's shape, but with statuses only: the process's readiness and the
 * database, no details and no other component, because anyone can read it. {@code /actuator} stays
 * where it is; the owner-gated System screen ({@link SystemStatusController}) still relays the
 * rest.
 */
@RestController
@RequestMapping("/api/health")
@NullMarked
public class ApiHealthController {

    private final HealthEndpoint health;

    public ApiHealthController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping
    public ApiHealth health() {
        return new ApiHealth(statusOf("readiness"), Map.of("db", new ComponentHealth(statusOf("db"))));
    }

    private String statusOf(String path) {
        HealthDescriptor descriptor = health.healthForPath(path);
        return descriptor == null ? "UNKNOWN" : descriptor.getStatus().getCode();
    }

    public record ApiHealth(String status, Map<String, ComponentHealth> components) {
    }

    public record ComponentHealth(String status) {
    }
}

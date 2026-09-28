package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What version this is, and which of its parts are answering.
 *
 * <p>Aran's actuator is bound to an internal-only management port, deliberately: it is for
 * container healthchecks and sidecar monitoring, not for browsers. So the System screen cannot
 * read {@code /actuator/health} directly, and this endpoint relays it — owner-gated, on the API
 * port, through Aran like everything else.
 *
 * <p>Relaying rather than re-implementing matters. A second set of probes written for the UI would
 * drift from the ones the orchestrator acts on, and the screen would eventually disagree with the
 * thing actually restarting containers.
 *
 * <p>Every field here exists to be pasted into a bug report. "It doesn't work" plus a version, a
 * commit and four component states is a report someone can act on; without them it is a
 * conversation.
 */
@RestController
@RequestMapping("/api/tenant/admin/system")
@NullMarked
public class SystemStatusController {

    private final HealthEndpoint health;
    private final CurrentUserResolver currentUser;
    private final @Nullable BuildProperties build;

    /**
     * @param build absent when running from a classpath build that never produced
     *              {@code build-info.properties} — an IDE run, typically. Nullable rather than
     *              required, because a missing version must not stop the screen that reports
     *              which components are down.
     */
    public SystemStatusController(HealthEndpoint health, CurrentUserResolver currentUser,
                                  @Nullable BuildProperties build) {
        this.health = health;
        this.currentUser = currentUser;
        this.build = build;
    }

    @GetMapping
    public SystemStatus status() {
        if (!currentUser.require().canAdministerTenant()) {
            throw new AccessDeniedException("only a tenant owner can view system status");
        }

        HealthDescriptor root = health.health();
        List<ComponentStatus> components = new ArrayList<>();
        if (root instanceof CompositeHealthDescriptor composite) {
            composite.getComponents().forEach((name, component) ->
                    components.add(new ComponentStatus(
                            name,
                            component.getStatus().getCode(),
                            detailsOf(component))));
        }
        // Broken first: the reason to open this page is to find what is wrong, and alphabetical
        // ordering buries it under the six things that are fine.
        components.sort(Comparator
                .comparing((ComponentStatus c) -> "UP".equals(c.status()))
                .thenComparing(ComponentStatus::name));

        return new SystemStatus(
                build == null ? "unknown" : build.getVersion(),
                build == null ? null : build.get("commit"),
                root.getStatus().getCode(),
                List.copyOf(components));
    }

    /**
     * The detail map, minus anything that is not a scalar.
     *
     * <p>Actuator details are open-ended — a datasource indicator can carry a validation query, a
     * disk indicator carries paths. Flattening to strings keeps this a display surface rather than
     * a hole through which an indicator's internals reach a browser.
     */
    private static Map<String, String> detailsOf(HealthDescriptor component) {
        if (!(component instanceof IndicatedHealthDescriptor indicated)) {
            return Map.of();
        }
        Map<String, String> flat = new java.util.LinkedHashMap<>();
        indicated.getDetails().forEach((k, v) -> {
            if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                flat.put(k, String.valueOf(v));
            }
        });
        return Map.copyOf(flat);
    }

    /**
     * @param commit null when built without git available; the version alone still identifies a
     *               release, and only a development build needs the commit to be unambiguous.
     */
    public record SystemStatus(
            String version,
            @Nullable String commit,
            String status,
            List<ComponentStatus> components
    ) {
    }

    public record ComponentStatus(String name, String status, Map<String, String> details) {
    }
}

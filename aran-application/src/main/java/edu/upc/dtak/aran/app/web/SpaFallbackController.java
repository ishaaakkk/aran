package edu.upc.dtak.aran.app.web;

import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page app for any path that is not an API route.
 *
 * <p>A SPA owns its own routing, so a browser reload on {@code /projects/42} arrives here as a
 * server request for a path no controller handles. Without this it is a 404 — the app works until
 * somebody presses refresh or shares a link, which is exactly the kind of defect that survives
 * every smoke test and is reported by the first real user.
 *
 * <p>Standalone only. Every other topology serves the SPA from its own origin, and mapping
 * everything to {@code index.html} there would swallow genuine 404s from the API.
 *
 * <p>The pattern excludes {@code api}, {@code auth} (the Keycloak reverse proxy — an HTML page
 * where a login form should be is a login that cannot work), {@code actuator} and {@code v3}
 * (OpenAPI) by negative lookahead, so those keep their real responses — including their 404s,
 * which a client needs to be able to tell apart from an HTML page. It excludes {@code assets} too,
 * Vite's output directory: the deep {@code /**} form otherwise answers {@code /assets/index-*.js}
 * with {@code index.html}, the browser refuses an HTML module script, and the UI never renders —
 * V69's first browser sign-in on the standalone image hit exactly that.
 */
@Controller
@Profile("standalone")
@NullMarked
public class SpaFallbackController {

    @GetMapping({"/", "/{path:^(?!api|auth|actuator|v3|assets)[^.]*}",
            "/{path:^(?!api|auth|actuator|v3|assets)[^.]*}/**"})
    public String forward() {
        return "forward:/index.html";
    }
}

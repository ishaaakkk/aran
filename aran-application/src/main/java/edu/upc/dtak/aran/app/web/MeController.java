package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.security.ApiTokenAuthentication;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import edu.upc.dtak.aran.core.service.IdentityService;
import edu.upc.dtak.aran.core.service.IdentityService.TenantMembershipView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "who am I" endpoint.
 *
 * <p>The frontend calls {@code GET /api/me} immediately after login. Unlike
 * every other {@code /api} endpoint this one does <em>not</em> go through
 * {@code CurrentUserResolver.require()}: that requires a tenant claim, but
 * {@code /api/me} also serves users who happen to have no memberships yet
 * (they should see an empty tenant list, not a 403). It only requires a
 * valid JWT.
 *
 * <p>It depends solely on {@link IdentityService}; the service owns the
 * provisioning + tenant-enrichment logic. The controller's job is just to
 * read claims off the token and shape the response.
 *
 * <p>It also carries the deployment's {@link Features}. One frontend bundle is served by every
 * deployment, so a build-time env cannot tell the SPA which optional surfaces this server has
 * turned on — the server must. This payload is where it does: the SPA already fetches it once
 * before it renders anything, so a flag here costs no extra request and cannot go stale against
 * the session it belongs to.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private static final String EMAIL_CLAIM = "email";
    private static final String NAME_CLAIM = "name";

    private final IdentityService identity;
    private final boolean cubesEnabled;

    public MeController(IdentityService identity,
                        @Value("${aran.cubes.enabled:false}") boolean cubesEnabled) {
        this.identity = identity;
        this.cubesEnabled = cubesEnabled;
    }

    @GetMapping
    public MeResponse me() {
        User user = currentUser();

        // Tenants the user can act within, each enriched with name/slug.
        List<TenantMembershipView> memberships = identity.membershipsOf(user.id());

        return new MeResponse(
                user.id(), user.subject(), user.email(), user.displayName(),
                user.createdAt(), memberships, new Features(cubesEnabled));
    }

    private User currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // An API token already names its user — the filter resolved it and checked the membership —
        // and carries no profile claims to provision from. Asking for a JWT here made every SDK
        // "who am I" a 500.
        if (authentication instanceof ApiTokenAuthentication apiToken) {
            UUID userId = apiToken.user().userId();
            return identity.user(userId).orElseThrow(() ->
                    new AccessDeniedException("the API token's user " + userId + " no longer exists"));
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException("no authenticated JWT on /api/me request");
        }
        Jwt jwt = jwtAuth.getToken();
        // JIT provisioning — first login creates the local User row.
        return identity.resolveFromClaims(
                jwt.getSubject(),
                jwt.getClaimAsString(EMAIL_CLAIM),
                jwt.getClaimAsString(NAME_CLAIM));
    }

    /** The authenticated user, the tenants they can act within, and what this server offers. */
    public record MeResponse(
            UUID userId,
            String subject,
            String email,
            String displayName,
            Instant createdAt,
            List<TenantMembershipView> tenants,
            Features features
    ) {
    }

    /**
     * Optional surfaces this deployment has turned on. Deployment-wide, not per-user: a flag here
     * says the feature exists at all, never that this caller may use it — authorization stays
     * where it already is.
     *
     * @param cubes {@code aran.cubes.enabled} — the shape-as-cube slice. Off by default until the
     *              defects its first live run found (V62) are fixed; with it
     *              off {@link CubeController} is not even a bean, so the UI that reads this would
     *              only be offering 404s.
     */
    public record Features(boolean cubes) {
    }
}

package edu.upc.dtak.aran.app.identity;

import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.AccessDeniedException;
import edu.upc.dtak.aran.core.service.IdentityService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns the validated JWT on the current request into a fully-resolved
 * {@link AuthenticatedUser} the core service layer can trust.
 *
 * <p>This is the application-side bridge between "Spring Security says the
 * token is valid" and "the core has an identity with a known tenant role". It
 * performs, in order:
 * <ol>
 *   <li><b>JIT provisioning</b> — resolve (or create) the local {@code User}
 *       from the token's {@code sub}/{@code email}/{@code name} claims, via
 *       {@link IdentityService};</li>
 *   <li><b>tenant resolution</b> — look up the user's {@link Membership} in Aran's
 *       own database, which names both the tenant and the role. No membership
 *       means {@link AccessDeniedException}: a valid token belonging to nobody's
 *       tenant gets you nothing.</li>
 * </ol>
 *
 * <p>Keycloak answers only <em>who you are</em>. It used to answer <em>which tenant</em> too, via
 * a {@code tenant_id} claim backed by a user attribute — see {@code IdentityService.soleMembership}
 * for why that stopped.
 *
 * <p>Controllers call {@link #require()} to get the caller; they never read
 * the security context themselves.
 */
@Component
public class CurrentUserResolver {

    private static final String EMAIL_CLAIM = "email";
    private static final String NAME_CLAIM = "name";

    private final IdentityService identity;

    public CurrentUserResolver(IdentityService identity) {
        this.identity = identity;
    }

    /**
     * Resolve the caller behind the current request.
     *
     * @throws IllegalStateException if there is no authenticated JWT on the
     *         request (a programming error — such endpoints should be public
     *         or the security config is wrong)
     * @throws AccessDeniedException if the user holds no membership, and so has no tenant to
     *         act within
     */
    public AuthenticatedUser require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        // An API token has already done all three steps below: the token row names the user and
        // the tenant, and the filter checked the membership. Nothing is left to interpret, so the
        // identity it resolved is returned as-is — both authentication paths converge here.
        if (authentication instanceof edu.upc.dtak.aran.app.security.ApiTokenAuthentication apiToken) {
            return apiToken.user();
        }

        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException(
                    "no authenticated JWT on request — endpoint should not have called require()");
        }
        Jwt jwt = jwtAuth.getToken();

        // 1. JIT provisioning — local User projection, created/synced from claims.
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        String displayName = jwt.getClaimAsString(NAME_CLAIM);
        if (email == null || displayName == null) {
            throw new AccessDeniedException(
                    "token is missing required profile claims (email, name)");
        }
        User user = identity.resolveFromClaims(subject, email, displayName);

        // 2 + 3. Tenant AND membership, from one lookup in Aran's own database.
        //
        // This was two steps: read the JWT's tenant_id claim, then check the user belonged to the
        // tenant it named. The claim was a Keycloak user attribute, which made a user's tenant a
        // fact recorded in two places with nothing keeping them in agreement — and made adding a
        // colleague to a tenant an edit to their Keycloak profile. The membership row is now the
        // only answer, and Keycloak is asked only who the caller is.
        //
        // No extra query: the membership lookup was already happening as step 3.
        Membership membership = identity.soleMembership(user.id())
                .orElseThrow(() -> new AccessDeniedException(
                        "this account does not belong to any tenant"));

        return new AuthenticatedUser(
                user.id(), user.subject(), membership.tenantId(), membership.role());
    }
}

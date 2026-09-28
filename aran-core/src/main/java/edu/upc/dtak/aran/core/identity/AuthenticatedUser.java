package edu.upc.dtak.aran.core.identity;

import edu.upc.dtak.aran.core.domain.TenantRole;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * The fully-resolved identity of the caller behind the current request.
 *
 * <p>By the time a service method receives one of these, three things have
 * already been established by the application layer:
 * <ol>
 *   <li>the JWT was valid (Spring Security resource server);</li>
 *   <li>the {@code subject} was resolved to a local {@code User} — provisioned
 *       just-in-time on first login;</li>
 *   <li>the user holds a {@code Membership} in the requested tenant — so
 *       {@code tenantId} and {@code tenantRole} are present and trustworthy.</li>
 * </ol>
 *
 * <p>Service methods take this as a parameter rather than reaching into a
 * security context: it keeps the core framework-agnostic and makes every
 * method's authorization inputs explicit and testable.
 *
 * @param userId     the caller's Aran-local user id
 * @param subject    the caller's Keycloak subject (JWT {@code sub})
 * @param tenantId   the tenant this request is acting within
 * @param tenantRole the caller's role in that tenant
 */
@NullMarked
public record AuthenticatedUser(
        UUID userId,
        String subject,
        UUID tenantId,
        TenantRole tenantRole
) {
    /** @return whether the caller may administer the current tenant. */
    public boolean canAdministerTenant() {
        return tenantRole == TenantRole.OWNER;
    }
}

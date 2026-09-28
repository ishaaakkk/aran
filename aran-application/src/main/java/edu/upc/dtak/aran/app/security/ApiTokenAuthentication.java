package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

/**
 * The {@code Authentication} an API token produces.
 *
 * <p>Carries the fully-resolved {@link AuthenticatedUser} rather than a set of claims to be
 * resolved later: the token row already names the user and the tenant, and the filter has done the
 * membership lookup, so there is nothing left to interpret. {@code CurrentUserResolver} therefore
 * hands this straight back, which keeps both authentication paths converging on one identity type.
 */
public final class ApiTokenAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;
    private final ApiTokenScope scope;
    private final UUID tokenId;

    public ApiTokenAuthentication(AuthenticatedUser user, ApiTokenScope scope, UUID tokenId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_API_TOKEN"),
                new SimpleGrantedAuthority("SCOPE_" + scope.name())));
        this.user = user;
        this.scope = scope;
        this.tokenId = tokenId;
        setAuthenticated(true);
    }

    public AuthenticatedUser user() {
        return user;
    }

    public ApiTokenScope scope() {
        return scope;
    }

    /** The token's id — for audit lines. Never the secret, which is not kept anywhere. */
    public UUID tokenId() {
        return tokenId;
    }

    /** Deliberately not the token: nothing downstream needs the credential, so nothing holds it. */
    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return user.subject();
    }

    @Override
    public String getName() {
        return user.subject();
    }
}

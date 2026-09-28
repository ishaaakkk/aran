package edu.upc.dtak.aran.app.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * The {@link Converter} the resource-server filter chain uses to turn a
 * validated {@link Jwt} into an authenticated principal.
 *
 * <p>It is a thin wrapper that plugs {@link KeycloakRoleConverter} into Spring
 * Security's standard {@link JwtAuthenticationConverter}, and additionally sets
 * the principal name to Keycloak's {@code preferred_username} claim rather than
 * the opaque {@code sub} UUID — friendlier in logs and audit trails.
 */
public final class KeycloakJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String PREFERRED_USERNAME = "preferred_username";

    private final JwtAuthenticationConverter delegate;

    public KeycloakJwtAuthenticationConverter() {
        this.delegate = new JwtAuthenticationConverter();
        this.delegate.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        this.delegate.setPrincipalClaimName(PREFERRED_USERNAME);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return delegate.convert(jwt);
    }
}

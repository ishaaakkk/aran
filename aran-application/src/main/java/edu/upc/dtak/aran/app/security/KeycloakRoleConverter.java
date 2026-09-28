package edu.upc.dtak.aran.app.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Converts Keycloak's nested role claims into Spring Security authorities.
 *
 * <p>Keycloak does not put roles in a flat {@code scope}/{@code authorities}
 * claim. Realm roles live under {@code realm_access.roles} and client roles
 * under {@code resource_access.<client>.roles}. Spring Security has no way to
 * know that structure, so this converter bridges it: each role string becomes
 * a {@code ROLE_<NAME>} authority.
 *
 * <p>This converter is the <em>only</em> place that knows Keycloak's token
 * shape. If the IdP is ever swapped, this class is what changes — the rest of
 * the security config and every {@code @PreAuthorize} stays untouched.
 */
public final class KeycloakRoleConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get(REALM_ACCESS);
        if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
            return List.of();
        }

        Object roles = realmAccessMap.get(ROLES);
        if (!(roles instanceof Collection<?> roleCollection)) {
            return List.of();
        }

        return roleCollection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> ROLE_PREFIX + role.toUpperCase())
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }
}

package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.security.KeycloakJwtAuthenticationConverter;
import edu.upc.dtak.aran.app.security.KeycloakRoleConverter;
import edu.upc.dtak.aran.app.web.dto.AdminDtos.CreateTenantRequest;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.util.SimpleMethodInvocation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S9-3: the platform-admin gate is an expression on one side and a role converter on the other,
 * and for as long as they have existed they disagreed about case — the converter emits
 * {@code ROLE_PLATFORM-ADMIN}, the expression asked for {@code ROLE_platform-admin}, and the only
 * test on the route asserted the 403 direction, which a gate that never opens passes trivially.
 *
 * <p>This evaluates the controller's own {@code @PreAuthorize} string, read off the class, with
 * Spring's own method-security expression handler, against an {@link Authentication} produced by
 * the production {@link KeycloakJwtAuthenticationConverter}. Nothing is restated, so changing
 * either side without the other fails here, Docker-free. {@code PlatformAdminAuthorizationIT}
 * covers the same gate through the real filter chain.
 */
class PlatformAdminAuthorizationTest {

    private static Authentication fromKeycloak(List<String> realmRoles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .claim("preferred_username", "someone")
                .claim("realm_access", Map.of("roles", realmRoles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new KeycloakJwtAuthenticationConverter().convert(jwt);
    }

    private static boolean gateAdmits(Authentication authentication) throws Exception {
        String expression = PlatformAdminController.TenantsAdminEndpoints.class
                .getAnnotation(PreAuthorize.class).value();
        Method createTenant = PlatformAdminController.TenantsAdminEndpoints.class
                .getDeclaredMethod("createTenant", CreateTenantRequest.class);
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        EvaluationContext context = handler.createEvaluationContext(
                () -> authentication, new SimpleMethodInvocation(null, createTenant));
        return ExpressionUtils.evaluateAsBoolean(
                handler.getExpressionParser().parseExpression(expression), context);
    }

    @Test
    @DisplayName("the converter emits ROLE_ + the upper-cased realm role")
    void converterAuthorityIsPinned() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("platform-admin")))
                .build();

        assertThat(new KeycloakRoleConverter().convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PLATFORM-ADMIN");
    }

    @Test
    @DisplayName("a Keycloak token carrying the platform-admin realm role passes the gate")
    void platformAdminIsAdmitted() throws Exception {
        assertThat(gateAdmits(fromKeycloak(List.of("platform-admin")))).isTrue();
    }

    @Test
    @DisplayName("a token without the realm role is refused")
    void ordinaryCallerIsRefused() throws Exception {
        assertThat(gateAdmits(fromKeycloak(List.of()))).isFalse();
        assertThat(gateAdmits(fromKeycloak(List.of("offline_access", "uma_authorization"))))
                .isFalse();
    }
}

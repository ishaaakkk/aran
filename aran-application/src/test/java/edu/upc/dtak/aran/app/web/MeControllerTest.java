package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.security.ApiTokenAuthentication;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.IdentityService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@code GET /api/me} tells the SPA about the <em>deployment</em>, not just the user.
 *
 * <p>The same frontend bundle is served by every deployment, so a build-time env cannot decide
 * whether a feature exists here — the server has to say. {@code /api/me} is where it says it: the
 * SPA already fetches it once, before it renders anything (see {@code RequireAuth}), so a flag
 * carried here needs no second request and no second cache. The {@code features} object keeps
 * those deployment facts visibly separate from the user facts around them.
 */
@DisplayName("MeController — the deployment's feature flags ride on /api/me")
class MeControllerTest {

    private final IdentityService identity = mock(IdentityService.class);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        when(identity.resolveFromClaims(any(), any(), any())).thenReturn(
                new User(userId, "sub-1", "who@example.com", "Who Ami", Instant.now()));
        when(identity.membershipsOf(userId)).thenReturn(List.of());

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("sub-1")
                .claim("email", "who@example.com")
                .claim("name", "Who Ami")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("cubes off is what a deployment that has not enabled them is told")
    void cubesOff() {
        MeController.MeResponse me = new MeController(identity, false).me();

        assertThat(me.features().cubes()).isFalse();
        // The user half is untouched by the addition.
        assertThat(me.userId()).isEqualTo(userId);
        assertThat(me.email()).isEqualTo("who@example.com");
    }

    @Test
    @DisplayName("cubes on is reported too — the SPA renders the wizard off this and nothing else")
    void cubesOn() {
        assertThat(new MeController(identity, true).me().features().cubes()).isTrue();
    }

    @Test
    @DisplayName("an API token answers with the user it names, not a 500 for lacking a JWT")
    void apiTokenAnswersWithItsUser() {
        UUID tenantId = UUID.randomUUID();
        when(identity.user(userId)).thenReturn(Optional.of(
                new User(userId, "sub-1", "who@example.com", "Who Ami", Instant.now())));
        SecurityContextHolder.getContext().setAuthentication(new ApiTokenAuthentication(
                new AuthenticatedUser(userId, "sub-1", tenantId, TenantRole.MEMBER),
                ApiTokenScope.values()[0], UUID.randomUUID()));

        MeController.MeResponse me = new MeController(identity, false).me();

        assertThat(me.userId()).isEqualTo(userId);
        assertThat(me.email()).isEqualTo("who@example.com");
        assertThat(me.displayName()).isEqualTo("Who Ami");
    }
}

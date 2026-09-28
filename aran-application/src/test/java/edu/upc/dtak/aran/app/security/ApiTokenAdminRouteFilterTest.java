package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The admin block as the filter applies it: a {@code FULL} token held by a tenant {@code OWNER} —
 * the most authority a token can carry — is refused on the tenant-owner mutations and still reads
 * the license. {@link TokenScopePolicyTest} pins the matcher; this pins that the filter consults it
 * before anything else, so the owner's role never reaches the controller.
 */
class ApiTokenAdminRouteFilterTest {

    private static final String TOKEN = "aran_pat_owner-full";
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ApiTokenAuthenticationFilter filter;

    @BeforeEach
    void ownerHoldsAFullToken() {
        ApiTokenService tokens = mock(ApiTokenService.class);
        UserRepository users = mock(UserRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        Instant now = Instant.now();
        when(tokens.authenticate(any())).thenReturn(Optional.of(new ApiToken(
                UUID.randomUUID(), TENANT, USER, "notebook", "hash", "aran_pat_",
                ApiTokenScope.FULL, null, null, null, now)));
        when(users.findById(USER)).thenReturn(Optional.of(
                new User(USER, "sub-owner", "owner@example.org", "Owner", now)));
        when(tenants.findMembership(USER, TENANT)).thenReturn(Optional.of(
                new Membership(UUID.randomUUID(), USER, TENANT, TenantRole.OWNER, now)));
        filter = new ApiTokenAuthenticationFilter(tokens, users, tenants);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    @ParameterizedTest(name = "{0} {1} → 403")
    @CsvSource({
            "POST,   /api/tenants/11111111-1111-1111-1111-111111111111/members",
            "DELETE, /api/tenants/11111111-1111-1111-1111-111111111111/membership/me",
            "POST,   /api/tenants/11111111-1111-1111-1111-111111111111/%6Dembers",
    })
    @DisplayName("an OWNER's FULL token cannot add members or leave the tenant")
    void tenantOwnerMutationsRefused(String method, String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(method, uri), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest())
                .describedAs("the request must not continue to the controller")
                .isNull();
    }

    @Test
    @DisplayName("the same token still reads the tenant's license")
    void licenseReadStaysOpen() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET",
                "/api/tenants/11111111-1111-1111-1111-111111111111/license"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(ApiTokenAuthentication.class);
    }
}

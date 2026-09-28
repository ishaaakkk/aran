package edu.upc.dtak.aran.app.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the job event stream is allowed to authenticate with when the token rides the URL.
 *
 * <p>{@code EventSource} cannot set headers, so this one route accepts {@code ?access_token=}.
 * A URL is not a header, though: it lands in access logs, proxy logs, browser history and
 * {@code Referer}. A JWT there expires in minutes; an {@code aran_pat_} personal access token
 * is valid for months and is what a scripted client holds — the two do not deserve the same
 * treatment (audit AUTH_IDENTITY-14).
 */
@DisplayName("SSE ?access_token= promotion")
class SseAccessTokenFilterTest {

    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.sig";
    private static final String PAT = "aran_pat_HZ3xQq0Ls9";

    /** Runs the filter over a job-events request and reports the Authorization the chain saw. */
    private static @Nullable String authorizationSeenDownstream(@Nullable String accessToken)
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/jobs/2b1f/events");
        request.setRequestURI("/api/jobs/2b1f/events");
        if (accessToken != null) {
            request.setParameter("access_token", accessToken);
        }
        MockFilterChain chain = new MockFilterChain();

        new SseAccessTokenFilter().doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
        assertThat(downstream).as("the chain must always be continued").isNotNull();
        return downstream.getHeader("Authorization");
    }

    @Test
    @DisplayName("a JWT is promoted into the Authorization header — the reason this filter exists")
    void jwtIsPromoted() throws Exception {
        assertThat(authorizationSeenDownstream(JWT)).isEqualTo("Bearer " + JWT);
    }

    @Test
    @DisplayName("an aran_pat_ token is NOT promoted; the request reaches the chain unauthenticated")
    void personalAccessTokenIsNotPromoted() throws Exception {
        // Not an error response here: the filter simply declines to turn the parameter into a
        // credential, and the endpoint's own authentication then answers 401. Refusing here
        // would need a second error path for the same outcome.
        assertThat(authorizationSeenDownstream(PAT)).isNull();
    }

    @Test
    @DisplayName("no access_token leaves the request untouched")
    void missingParameterIsUntouched() throws Exception {
        assertThat(authorizationSeenDownstream(null)).isNull();
    }

    @Test
    @DisplayName("an Authorization header already present wins over the query parameter")
    void existingHeaderWins() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/jobs/2b1f/events");
        request.setRequestURI("/api/jobs/2b1f/events");
        request.addHeader("Authorization", "Bearer " + JWT);
        request.setParameter("access_token", "some-other-token");
        MockFilterChain chain = new MockFilterChain();

        new SseAccessTokenFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(((HttpServletRequest) chain.getRequest()).getHeader("Authorization"))
                .isEqualTo("Bearer " + JWT);
    }

    @Test
    @DisplayName("any other route is not filtered at all")
    void otherRoutesAreNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/datasets");
        request.setRequestURI("/api/datasets");
        request.setParameter("access_token", JWT);
        MockFilterChain chain = new MockFilterChain();

        new SseAccessTokenFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(((HttpServletRequest) chain.getRequest()).getHeader("Authorization")).isNull();
    }
}

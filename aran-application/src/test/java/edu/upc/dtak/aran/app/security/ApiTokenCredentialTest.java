package edu.upc.dtak.aran.app.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where an Aran API token is allowed to appear on a request.
 *
 * <p>Bearer is the API's own convention. HTTP Basic exists for JDBC: Avatica's remote driver, and
 * the SQL clients that drive it, offer a user and a password rather than a header, so the token
 * arrives in one of those two fields. Both are read, because tools disagree about which field an
 * API token belongs in — and a token is recognised by its prefix, so accepting either cannot make
 * a credential ambiguous.
 */
class ApiTokenCredentialTest {

    private static final String TOKEN = "aran_pat_HZ3xQq0Ls9";

    private static MockHttpServletRequest withAuthorization(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/jdbc");
        if (value != null) {
            request.addHeader("Authorization", value);
        }
        return request;
    }

    private static String basic(String user, String password) {
        String raw = user + ":" + password;
        return "Basic " + Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String presented(String header) {
        return ApiTokenAuthenticationFilter.presentedToken(withAuthorization(header));
    }

    @Nested
    @DisplayName("bearer")
    class Bearer {

        @Test
        @DisplayName("an Aran token is taken")
        void takesAranToken() {
            assertThat(presented("Bearer " + TOKEN)).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("a JWT is left for the JWT filter")
        void ignoresJwt() {
            assertThat(presented("Bearer eyJhbGciOiJSUzI1NiJ9.e30.sig")).isNull();
        }
    }

    @Nested
    @DisplayName("basic")
    class Basic {

        @Test
        @DisplayName("the token is read from the password field")
        void readsPassword() {
            assertThat(presented(basic("aran", TOKEN))).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("the token is read from the username field too")
        void readsUsername() {
            assertThat(presented(basic(TOKEN, ""))).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("a password with no username is still read")
        void readsBareCredential() {
            String encoded = Base64.getEncoder()
                    .encodeToString(TOKEN.getBytes(StandardCharsets.UTF_8));

            assertThat(presented("Basic " + encoded)).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("an ordinary username and password is not mistaken for a token")
        void ignoresRealCredentials() {
            assertThat(presented(basic("alice", "hunter2"))).isNull();
        }

        @Test
        @DisplayName("a colon inside the token survives the split")
        void tokenContainingColon() {
            String odd = "aran_pat_aa:bb";

            assertThat(presented(basic("aran", odd))).isEqualTo(odd);
        }

        @Test
        @DisplayName("undecodable base64 is refused rather than thrown")
        void malformedBase64() {
            assertThat(presented("Basic !!!not-base64!!!")).isNull();
        }
    }

    @Test
    @DisplayName("no Authorization header means no token")
    void noHeader() {
        assertThat(ApiTokenAuthenticationFilter.presentedToken(withAuthorization(null))).isNull();
    }

    @Test
    @DisplayName("an unknown scheme is ignored")
    void unknownScheme() {
        assertThat(presented("Digest username=\"aran\"")).isNull();
    }
}

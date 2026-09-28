package edu.upc.dtak.aran.app.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Which paths the standalone image answers with the SPA shell. A path the fallback does not claim
 * is left to the next handler — in the image, the static-resource handler over {@code /app/static}
 * — which here, with no other handler registered, shows up as "not forwarded".
 */
class SpaFallbackControllerTest {

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new SpaFallbackController()).build();

    private String forwardedUrl(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        return result.getResponse().getForwardedUrl();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/projects", "/projects/42", "/projects/42/datasets/7", "/login"})
    @DisplayName("an SPA route — including a deep link reloaded in the browser — gets index.html")
    void spaRoutesAreForwarded(String path) throws Exception {
        assertThat(forwardedUrl(path)).isEqualTo("/index.html");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assets/index-BazL6LZk.js", "/assets/index-BQl0aRnS.css",
            "/assets/AdminView-BsD1QDfR.js"})
    @DisplayName("V69: Vite's /assets/* bundles reach the static handler, not the index.html shell")
    void builtAssetsAreNotForwarded(String path) throws Exception {
        assertThat(forwardedUrl(path)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/projects", "/auth/realms/aran", "/actuator/health", "/v3/api-docs",
            "/favicon.svg", "/silent-check-sso.html"})
    @DisplayName("API, proxy, actuator, OpenAPI and root static files keep their real responses")
    void realRoutesAreNotForwarded(String path) throws Exception {
        assertThat(forwardedUrl(path)).isNull();
    }
}

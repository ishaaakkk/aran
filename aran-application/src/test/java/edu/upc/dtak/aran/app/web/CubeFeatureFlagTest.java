package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.CubeService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code aran.cubes.enabled} — the shape-as-cube slice is off unless a deployment asks for it.
 *
 * <p>The slice's first live run (V62) found open defects, so shipping it enabled would ship
 * those to every deployment. The gate is one {@code @ConditionalOnProperty} on
 * {@link CubeController}: with the flag off the controller is not a bean, the routes have no
 * handler, and the request dies in the DispatcherServlet before any project or cube work runs.
 *
 * <p><b>404, not 403.</b> A disabled feature does not exist for that deployment; a 403 would
 * say "this exists and you may not have it", which is both wrong and an invitation to retry.
 *
 * <p>The context here is built by hand rather than through {@code @SpringJUnitWebConfig} so both
 * halves of the flag live in one class and the property is set per case; no Spring Boot
 * application context and no Docker, so this runs in {@code validate.sh --offline}.
 */
@DisplayName("aran.cubes.enabled — the cube slice is off by default")
class CubeFeatureFlagTest {

    private static final UUID PROJECT = UUID.randomUUID();

    /**
     * Everything {@link CubeController} needs, mocked. The mocks are beans rather than fields so
     * a test can pull one back out of the context and assert it was never touched — the claim
     * that matters when the flag is off.
     */
    @Configuration
    @EnableWebMvc
    static class Collaborators {

        @Bean
        CubeService cubeService() {
            return mock(CubeService.class);
        }

        @Bean
        CurrentUserResolver currentUserResolver() {
            CurrentUserResolver resolver = mock(CurrentUserResolver.class);
            when(resolver.require()).thenReturn(new AuthenticatedUser(
                    UUID.randomUUID(), "sub", UUID.randomUUID(), TenantRole.MEMBER));
            return resolver;
        }

        @Bean
        ProjectAccess projectAccess() {
            return mock(ProjectAccess.class);
        }
    }

    /** A web context holding the collaborators, the advice, and — conditionally — the controller. */
    private static AnnotationConfigWebApplicationContext contextWith(String... properties) {
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        TestPropertyValues.of(properties).applyTo(ctx);
        ctx.register(Collaborators.class, CubeController.class, ApiExceptionHandler.class);
        ctx.refresh();
        return ctx;
    }

    private static MockMvc mvc(AnnotationConfigWebApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    // ── off: the default, and an explicit false ──────────────────────────────

    @Test
    @DisplayName("with the property unset, every cubes route answers 404 and CubeService is never "
            + "touched")
    void offByDefault() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = contextWith()) {
            assertThat(ctx.getBeanNamesForType(CubeController.class)).isEmpty();

            MockMvc mvc = mvc(ctx);
            mvc.perform(get("/api/projects/{p}/cubes", PROJECT)).andExpect(status().isNotFound());
            mvc.perform(get("/api/projects/{p}/cubes/evidence", PROJECT))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/projects/{p}/cubes", PROJECT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"sales\"}"))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(ctx.getBean(CubeService.class));
            verifyNoInteractions(ctx.getBean(ProjectAccess.class));
        }
    }

    @Test
    @DisplayName("an explicit false is the same as unset — nothing but `true` enables the slice")
    void explicitFalseIsAlsoOff() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = contextWith("aran.cubes.enabled=false")) {
            assertThat(ctx.getBeanNamesForType(CubeController.class)).isEmpty();
            mvc(ctx).perform(get("/api/projects/{p}/cubes", PROJECT))
                    .andExpect(status().isNotFound());
        }
    }

    // ── on: the existing behaviour, unchanged ────────────────────────────────

    @Test
    @DisplayName("with the property true the routes exist again: list answers 200 off the service")
    void enabledListsCubes() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = contextWith("aran.cubes.enabled=true")) {
            assertThat(ctx.getBeanNamesForType(CubeController.class)).hasSize(1);
            CubeService cubeService = ctx.getBean(CubeService.class);
            when(cubeService.list(any(), any())).thenReturn(List.of());

            mvc(ctx).perform(get("/api/projects/{p}/cubes", PROJECT))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("with the property true a bad create body is the controller's own 400, not a 404 —"
            + " the request reached the handler")
    void enabledCreateReachesTheController() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = contextWith("aran.cubes.enabled=true")) {
            mvc(ctx).perform(post("/api/projects/{p}/cubes", PROJECT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"sales\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}

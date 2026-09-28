package edu.upc.dtak.aran.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The SPA's health probe through the real security chain and the real health endpoint: readable
 * with no credential (the offline screen runs before sign-in), UP against a live database, and
 * saying nothing beyond the two statuses — while {@code /actuator} itself stays off the API port.
 */
@DisplayName("GET /api/health — anonymous, same-origin, statuses only")
class ApiHealthIT extends BaseIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("answers UP with no credential, and with no component but the database")
    void anonymousUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.length()").value(1))
                .andExpect(jsonPath("$.components.db.details").doesNotExist());
    }

    @Test
    @DisplayName("the API port still serves no /actuator — the probe replaced reading it, not exposing it")
    void actuatorStaysOffTheApiPort() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }
}

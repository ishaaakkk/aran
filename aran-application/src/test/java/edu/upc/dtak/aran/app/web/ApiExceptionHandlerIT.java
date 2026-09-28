package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("ApiExceptionHandler Integration Tests")
@Import(ApiExceptionHandlerIT.TestController.class)
class ApiExceptionHandlerIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("should map Spring Security AccessDeniedException to 403 ProblemDetail")
    void mapsSpringAccessDeniedTo403() throws Exception {
        mockMvc.perform(post("/api/admin/tenants")
                        .with(JwtTestHelper.memberUser(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "test-tenant",
                                  "name": "Test Tenant",
                                  "plan": "FREE",
                                  "firstOwnerSubject": "some-subject"
                                }
                                """))
                .andDo(print())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Access Denied"));
    }

    @Test
    @DisplayName("should map unhandled exceptions to 500 ProblemDetail and not leak stack trace")
    void mapsUnhandledExceptionTo500() throws Exception {
        mockMvc.perform(get("/api/test-error")
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Unexpected error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An internal server error occurred."));
    }

    @Test
    @DisplayName("an unmatched path answers 404 through the real dispatcher, not 500 (F26)")
    void unmatchedPathIs404() throws Exception {
        // ApiExceptionHandlerTest pins the mapping on the handler directly; this pins that a
        // request which never reaches a controller actually arrives at that handler. The
        // reported symptom was a real GET to a path this port does not serve, so the routing
        // is the half worth covering end to end.
        mockMvc.perform(get("/api/no-such-endpoint")
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("a ConflictException answers 409 with its sentence, its errorCode and the correlation header")
    void conflictIs409ThroughTheDispatcher() throws Exception {
        // The SPA quotes X-Correlation-ID as the reference on an error it cannot explain (J8-F4),
        // and sends its own id so a request that timed out in the browser can still be found.
        mockMvc.perform(get("/api/test-conflict")
                        .header("X-Correlation-ID", "spa-generated-id")
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Correlation-ID", "spa-generated-id"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("A data product named 'papers' already exists in this project."))
                .andExpect(jsonPath("$.errorCode").value("NAME_TAKEN"));
    }

    @Test
    @DisplayName("a real Postgres unique violation answers 409, never 500, and quotes no SQL")
    void integrityViolationIs409ThroughTheDispatcher() throws Exception {
        mockMvc.perform(get("/api/test-integrity")
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("uq_"))));
    }

    @Test
    @DisplayName("a real Postgres NOT NULL violation stays a 500 — a write-path bug is not a name clash")
    void notNullViolationStays500ThroughTheDispatcher() throws Exception {
        mockMvc.perform(get("/api/test-integrity-not-null")
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An internal server error occurred."));
    }

    @RestController
    static class TestController {
        private final JdbcTemplate jdbc;

        TestController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/api/test-error")
        void throwError() {
            throw new RuntimeException("Simulated unexpected database or system failure");
        }

        @GetMapping("/api/test-conflict")
        void throwConflict() {
            throw new edu.upc.dtak.aran.core.service.ConflictException("NAME_TAKEN",
                    "A data product named 'papers' already exists in this project.");
        }

        // Raised by the real database, through the real driver and Spring's real translation — the
        // SQLSTATE the handler classifies on is what Postgres sends, not a value this test chose.
        @GetMapping("/api/test-integrity")
        void throwIntegrity() {
            jdbc.execute("DO $$ BEGIN RAISE unique_violation USING MESSAGE = "
                    + "'duplicate key value violates unique constraint \"uq_dataset_repository_name\"'; END $$");
        }

        @GetMapping("/api/test-integrity-not-null")
        void throwNotNull() {
            jdbc.execute("DO $$ BEGIN RAISE not_null_violation USING MESSAGE = "
                    + "'null value in column \"name\" violates not-null constraint'; END $$");
        }
    }
}

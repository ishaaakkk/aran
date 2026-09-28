package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.web.dto.ModelDtos.MappingKindDto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V48: a malformed request body must answer 400 with a safe summary of the parse problem, not
 * 500. No Spring context, no Docker — {@link ApiExceptionHandler} wired directly as controller
 * advice over a throwaway controller, same idiom as {@code KeycloakProxyControllerTest}.
 */
@DisplayName("ApiExceptionHandler — malformed request body (V48)")
class ApiExceptionHandlerMalformedBodyTest {

    private static MockMvc mvc;

    @BeforeAll
    static void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("an enum value the DTO doesn't recognise answers 400, not 500")
    void unrecognisedEnumValueIs400() throws Exception {
        mvc.perform(post("/api/test-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DATATYPE","label":"x"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Malformed request body"))
                .andExpect(jsonPath("$.detail").value(
                        "Field 'kind' has an invalid value; accepted values: class, datatype, object, join."));
    }

    @Test
    @DisplayName("a syntactically-broken JSON body answers 400 with a generic parse message")
    void brokenJsonSyntaxIs400() throws Exception {
        mvc.perform(post("/api/test-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Malformed request body"))
                .andExpect(jsonPath("$.detail").value("The request body could not be parsed as valid JSON."))
                // The safe summary must never repeat the caller's actual submitted text.
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("this is not json");
                });
    }

    @Test
    @DisplayName("a well-formed request is untouched")
    void wellFormedRequestIsUntouched() throws Exception {
        mvc.perform(post("/api/test-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"datatype","label":"x"}
                                """))
                .andExpect(status().isOk());
    }

    @RestController
    static class TestController {
        @PostMapping("/api/test-body")
        void receive(@RequestBody TestBodyDto body) {
        }
    }

    record TestBodyDto(MappingKindDto kind, String label) {
    }
}

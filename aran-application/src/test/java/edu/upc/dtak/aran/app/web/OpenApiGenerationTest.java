package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

class OpenApiGenerationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generateOpenApiDocs() throws Exception {
        String apiDocs = mockMvc.perform(get("/v3/api-docs").with(user("probe")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Path outputPath = Paths.get("build", "openapi.json");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, apiDocs, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}

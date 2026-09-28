package edu.upc.dtak.aran.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.service.StateConflictException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * UI-OD8 (audit J2-F6) over HTTP: the server's refusal to rename a dataset read live is the UI's
 * backstop, so the route must answer it as a 409 carrying {@code errorCode} — never the generic
 * 500 a bare {@link IllegalStateException} reached before {@link StateConflictException} had a
 * handler. The refusal itself is {@code DatasetServiceRenameTest}'s; this pins the route and the
 * handler around it. No Spring context, no Docker — the {@code ApiExceptionHandlerMalformedBodyTest}
 * idiom over the real controller.
 */
@DisplayName("PUT …/datasets/{id} — a live dataset's rename answers 409 LIVE_DATASET_RENAME (UI-OD8)")
class DatasetControllerRenameConflictTest {

    private final DatasetService datasetService = mock(DatasetService.class);
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    private MockMvc mvc;

    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(
                new AuthenticatedUser(UUID.randomUUID(), "sub", UUID.randomUUID(), TenantRole.MEMBER));
        mvc = MockMvcBuilders.standaloneSetup(new DatasetController(
                        datasetService, mock(DataRepositoryService.class), currentUser, new ObjectMapper()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("the refusal leaves as 409 with its errorCode and sentence, and no promote-only properties")
    void liveRenameIs409() throws Exception {
        when(datasetService.updateDataset(any(), eq(projectId), eq(repositoryId), any()))
                .thenThrow(new StateConflictException(DatasetService.LIVE_DATASET_RENAME,
                        "'authors' is read live from its source, and its name is the table every query reads."));

        mvc.perform(put("/api/projects/{p}/repositories/{r}/datasets/{d}", projectId, repositoryId, datasetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"authors_v2\",\"description\":\"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value(DatasetService.LIVE_DATASET_RENAME))
                .andExpect(jsonPath("$.detail").value(
                        "'authors' is read live from its source, and its name is the table every query reads."))
                .andExpect(jsonPath("$.classes").doesNotExist())
                .andExpect(jsonPath("$.projects").doesNotExist());
    }
}

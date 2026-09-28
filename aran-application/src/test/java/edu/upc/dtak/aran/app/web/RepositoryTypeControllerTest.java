package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.web.RepositoryTypeController.RepositoryTypeResponse;
import edu.upc.dtak.aran.core.domain.RepositoryType;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RepositoryTypeController}.
 *
 * <p>The controller has no collaborators, so this needs no Spring context and no container.
 */
class RepositoryTypeControllerTest {

    private final RepositoryTypeController controller = new RepositoryTypeController();

    @Test
    @DisplayName("every RepositoryType is published by its constant name, in enum order")
    void mirrors_the_enum_exactly() {
        // Structural, so it cannot drift: a kind is published the moment it is declared, and one
        // that is deleted disappears, without touching this test.
        assertThat(controller.listRepositoryTypes())
                .extracting(RepositoryTypeResponse::type)
                .containsExactly(Arrays.stream(RepositoryType.values())
                        .map(RepositoryType::name)
                        .toArray(String[]::new));
    }

    @Test
    @DisplayName("the response is the kind's name alone — no capability flag, no display label")
    void publishes_the_name_only() {
        // connectorAvailable left the wire with the enum flag (D1 PR 2): every kind ingests, so it
        // could only say true. A label landing here would merge presentation into the API, and
        // the frontend could no longer be reworded without a backend release.
        assertThat(RepositoryTypeResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("type");
    }
}

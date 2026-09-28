package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.web.SingerTapController.SingerTapResponse;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import edu.upc.dtak.aran.core.source.SingerTapCatalog.Tap;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SingerTapController}.
 *
 * <p>No Spring context: the controller has one collaborator and the interesting property is what
 * it declines to publish.
 */
class SingerTapControllerTest {

    private static final Tap RATES = new Tap(
            "exchangeratesapi",
            "Exchange Rates API",
            List.of("/opt/taps/bin/tap-exchangeratesapi", "--quiet"),
            Set.of("base", "api_key", "start_date"),
            Set.of("api_key"));

    @Test
    @DisplayName("the tap's command never reaches the wire")
    void command_is_not_published() {
        // It is an absolute path on the server: it says where things are installed and confirms
        // whether a guessed path exists. A form needs the id and the settings, not the argv.
        List<SingerTapResponse> published =
                new SingerTapController(new SingerTapCatalog(List.of(RATES))).listTaps();

        assertThat(published).hasSize(1);
        assertThat(published.toString())
                .doesNotContain("/opt/taps")
                .doesNotContain("--quiet");
        assertThat(SingerTapResponse.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "label", "configKeys", "secretKeys");
    }

    @Test
    @DisplayName("settings are published, with the secret ones marked so a form can mask them")
    void settings_are_published() {
        SingerTapResponse tap =
                new SingerTapController(new SingerTapCatalog(List.of(RATES))).listTaps().getFirst();

        assertThat(tap.id()).isEqualTo("exchangeratesapi");
        assertThat(tap.label()).isEqualTo("Exchange Rates API");
        // Sorted, so the form renders the same order every time rather than the set's.
        assertThat(tap.configKeys()).containsExactly("api_key", "base", "start_date");
        assertThat(tap.secretKeys()).containsExactly("api_key");
    }

    @Test
    @DisplayName("a deployment with no taps publishes an empty list, not an error")
    void empty_when_disabled() {
        // The form should say the feature is off; an error would read as a broken server.
        assertThat(new SingerTapController(SingerTapCatalog.empty()).listTaps()).isEmpty();
    }
}

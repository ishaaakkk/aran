package edu.upc.dtak.aran.core.source;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TapConfigTest {

    @Test
    @DisplayName("a settings map round-trips through the stored JSON, order kept")
    void roundTrip() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("base", "EUR");
        config.put("api_key", "k-123");

        assertThat(TapConfig.toMap(TapConfig.toJson(config)))
                .containsExactlyEntriesOf(config);
    }

    @Test
    @DisplayName("no settings is the empty object, not null")
    void emptyIsAnObject() {
        assertThat(TapConfig.toJson(null)).isEqualTo("{}");
        assertThat(TapConfig.toJson(Map.of())).isEqualTo("{}");
        assertThat(TapConfig.toMap("{}")).isEmpty();
        assertThat(TapConfig.toMap(null)).isEmpty();
    }

    @Test
    @DisplayName("unreadable stored config lists as settingless rather than failing the read")
    void unreadableConfigIsEmpty() {
        // A GET must still answer: the repository shows no settings, which is what a user needs to
        // see in order to re-enter them.
        assertThat(TapConfig.toMap("not json at all")).isEmpty();
        assertThat(TapConfig.toMap("[1,2,3]")).isEmpty();
    }

    @Test
    @DisplayName("a non-string value reads back as its text, never as null")
    void nonStringValuesBecomeText() {
        assertThat(TapConfig.toMap("{\"page_size\":100,\"strict\":true,\"since\":null}"))
                .containsEntry("page_size", "100")
                .containsEntry("strict", "true")
                .containsEntry("since", "");
    }
}

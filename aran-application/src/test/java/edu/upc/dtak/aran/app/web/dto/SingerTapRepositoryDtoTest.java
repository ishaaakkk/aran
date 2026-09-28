package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.app.web.dto.DataRepositoryDtos.DataRepositoryResponse;
import edu.upc.dtak.aran.app.web.dto.DataRepositoryDtos.SingerTapRepositoryRequest;
import edu.upc.dtak.aran.app.web.dto.DataRepositoryDtos.SingerTapRepositoryResponse;
import edu.upc.dtak.aran.core.domain.SingerTapRepository;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import edu.upc.dtak.aran.core.source.TapConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire shape of the SINGER_TAP kind (T6): which of a tap's settings come back in the clear.
 *
 * <p>Per key, not wholesale — one map holds {@code base} (a setting a user edits) and
 * {@code api_key} (a credential), and the tap is the only thing that knows which is which.
 */
class SingerTapRepositoryDtoTest {

    private static final UUID ID = UUID.randomUUID();

    private static SingerTapCatalog catalog() {
        return new SingerTapCatalog(List.of(new SingerTapCatalog.Tap(
                "exchangeratesapi", "Exchange Rates API", List.of("/opt/taps/bin/tap"),
                Set.of("base", "start_date", "api_key"), Set.of("api_key"))));
    }

    private static SingerTapRepository repository(Map<String, String> config) {
        return new SingerTapRepository(ID, UUID.randomUUID(), UUID.randomUUID(),
                "rates", "", "exchangeratesapi", TapConfig.toJson(config));
    }

    private static Map<String, String> config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("base", "EUR");
        config.put("api_key", "k-live-9f2");
        return config;
    }

    @Test
    @DisplayName("only the tap's declared secret keys are masked; the rest stay editable")
    void secretKeysOnlyAreMasked() {
        SingerTapRepositoryResponse response = (SingerTapRepositoryResponse)
                DataRepositoryResponse.from(repository(config()), catalog());

        assertThat(response.config())
                .containsEntry("base", "EUR")
                .containsEntry("api_key", "********");
        assertThat(response.tapId()).isEqualTo("exchangeratesapi");
    }

    @Test
    @DisplayName("a tap the catalogue no longer declares has every setting masked")
    void unknownTapMasksEverything() {
        // "Which of these is a credential" has no answer once the tap is gone, so the safe answer
        // is all of them. The overload without a catalogue takes this path for the same reason.
        SingerTapRepositoryResponse withoutCatalog = (SingerTapRepositoryResponse)
                DataRepositoryResponse.from(repository(config()));

        assertThat(withoutCatalog.config())
                .containsEntry("base", "********")
                .containsEntry("api_key", "********");
    }

    @Test
    @DisplayName("an empty value stays empty rather than becoming an eight-asterisk secret")
    void emptyValuesAreNotMasked() {
        // Masking "" would tell a user a setting is filled in when it is not, and the update
        // sentinel would then preserve the emptiness forever.
        Map<String, String> config = new LinkedHashMap<>();
        config.put("api_key", "");

        SingerTapRepositoryResponse response = (SingerTapRepositoryResponse)
                DataRepositoryResponse.from(repository(config), catalog());

        assertThat(response.config()).containsEntry("api_key", "");
    }

    @Test
    @DisplayName("a request carries settings as a map and stores them as the row's JSON")
    void requestSerialisesConfig() {
        SingerTapRepositoryRequest request = new SingerTapRepositoryRequest(
                "rates", "daily rates", "exchangeratesapi", config());

        SingerTapRepository domain = (SingerTapRepository)
                request.toDomain(ID, UUID.randomUUID(), UUID.randomUUID());

        assertThat(TapConfig.toMap(domain.configJson())).containsExactlyEntriesOf(config());
        assertThat(domain.tapId()).isEqualTo("exchangeratesapi");
    }
}

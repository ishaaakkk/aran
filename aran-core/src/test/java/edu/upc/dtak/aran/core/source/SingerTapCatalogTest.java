package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.source.SingerTapCatalog.Tap;
import edu.upc.dtak.aran.core.source.SingerTapCatalog.TapNotAllowedException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The security boundary for running Singer taps.
 *
 * <p>A tap is a subprocess with the backend's privileges, so the question these tests answer is
 * not "does it work" but "can a user influence what gets executed". The answer must stay no.
 */
class SingerTapCatalogTest {

    private static final Tap RATES = new Tap(
            "exchangeratesapi",
            "Exchange Rates API",
            List.of("/opt/taps/bin/tap-exchangeratesapi"),
            Set.of("base", "start_date", "api_key"),
            Set.of("api_key"));

    private static final SingerTapCatalog CATALOG = new SingerTapCatalog(List.of(RATES));

    @Nested
    @DisplayName("what may be executed")
    class Commands {

        @Test
        @DisplayName("argv comes from configuration plus the config path — never from the caller")
        void argv_is_configuration_only() {
            List<String> argv = CATALOG.commandFor("exchangeratesapi", Path.of("/tmp/cfg.json"));

            assertThat(argv).containsExactly(
                    "/opt/taps/bin/tap-exchangeratesapi", "--config", "/tmp/cfg.json");
        }

        @Test
        @DisplayName("a tap the deployment did not configure cannot be run, and the refusal "
                + "names the asked-for tap without disclosing the allowlist")
        void unknown_tap_is_refused() {
            assertThatThrownBy(() -> CATALOG.commandFor("tap-anything", Path.of("/tmp/cfg.json")))
                    .isInstanceOf(TapNotAllowedException.class)
                    .hasMessageContaining("tap-anything")
                    // Says how to fix it, without enumerating what this deployment allows —
                    // that list is a map of the attack surface to anyone probing it.
                    .hasMessageContaining("aran.singer.taps")
                    .hasMessageNotContaining("exchangeratesapi")
                    .hasMessageNotContaining("/opt/taps");
        }

        @Test
        @DisplayName("a command injected through the tap id is just an unknown id")
        void injection_through_the_id_is_not_special() {
            // The id is a map key, never a token in a shell string — there is no shell. This
            // asserts the shape of the failure so a future refactor to `sh -c` fails here.
            assertThatThrownBy(() ->
                    CATALOG.commandFor("exchangeratesapi; rm -rf /", Path.of("/tmp/cfg.json")))
                    .isInstanceOf(TapNotAllowedException.class);
        }

        @Test
        @DisplayName("an empty catalogue can run nothing, which is the default")
        void empty_catalogue_is_off() {
            SingerTapCatalog empty = SingerTapCatalog.empty();

            assertThat(empty.isEnabled()).isFalse();
            assertThat(empty.all()).isEmpty();
            assertThatThrownBy(() -> empty.commandFor("exchangeratesapi", Path.of("/tmp/c.json")))
                    .isInstanceOf(TapNotAllowedException.class);
        }
    }

    @Nested
    @DisplayName("what may be configured")
    class Config {

        @Test
        @DisplayName("declared settings are accepted unchanged")
        void declared_keys_pass() {
            Map<String, String> supplied = Map.of("base", "EUR", "api_key", "secret");

            assertThat(CATALOG.validateConfig("exchangeratesapi", supplied)).isEqualTo(supplied);
        }

        @Test
        @DisplayName("an undeclared setting is refused, not silently dropped")
        void unknown_keys_are_refused() {
            // Dropping would leave a tap authenticating as nobody and returning an empty stream,
            // which reads as "no data" rather than as a mistake.
            assertThatThrownBy(() -> CATALOG.validateConfig(
                    "exchangeratesapi", Map.of("apiKey", "secret")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKey")
                    .hasMessageContaining("api_key");
        }

        @Test
        @DisplayName("config values are data, never arguments")
        void values_do_not_reach_argv() {
            // The defence against argument injection is structural: values go in a JSON file, so
            // this value is a string the tap reads, not an extra flag Aran passes.
            Map<String, String> hostile = Map.of("base", "--discover --config /etc/shadow");

            assertThat(CATALOG.validateConfig("exchangeratesapi", hostile)).isEqualTo(hostile);
            assertThat(CATALOG.commandFor("exchangeratesapi", Path.of("/tmp/cfg.json")))
                    .noneMatch(arg -> arg.contains("/etc/shadow"));
        }
    }

    @Nested
    @DisplayName("catalogue construction")
    class Construction {

        @Test
        @DisplayName("a tap with no command is a configuration error, caught at startup")
        void command_is_required() {
            assertThatThrownBy(() -> new Tap("x", "X", List.of(), Set.of(), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no command");
        }

        @Test
        @DisplayName("two taps cannot share an id")
        void duplicate_ids_are_rejected() {
            assertThatThrownBy(() -> new SingerTapCatalog(List.of(RATES, RATES)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("two Singer taps");
        }

        @Test
        @DisplayName("a secret key the tap does not accept is a configuration error")
        void secret_keys_must_be_config_keys() {
            assertThatThrownBy(() -> new Tap(
                    "x", "X", List.of("/bin/true"), Set.of("a"), Set.of("b")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("marks keys secret");
        }

        @Test
        @DisplayName("the published catalogue exposes key names but never values")
        void catalogue_carries_no_secrets() {
            // What a form needs is which settings exist, not what anyone set them to. There is
            // nowhere in Tap for a value to hide, and this fails if one is ever added.
            assertThat(Tap.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("id", "label", "command", "configKeys", "secretKeys");
        }
    }
}

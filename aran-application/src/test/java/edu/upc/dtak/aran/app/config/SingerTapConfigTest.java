package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the allowlist actually binds from configuration.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than the full application context: this needs a
 * real binder, which is the part that can silently be wrong, but not a database — and a check that
 * only runs when Docker is available is a check that mostly does not run.
 *
 * <p>The failure this is really guarding is a quiet one. If {@code config-keys} did not relax onto
 * {@code configKeys}, every tap would bind with an empty key set, every user setting would then be
 * refused as undeclared, and the error would blame the user's input rather than the deployment's
 * configuration.
 */
class SingerTapConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(SingerTapConfig.class);

    @Test
    @DisplayName("no configuration means an empty catalogue — the feature is off by default")
    void off_by_default() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SingerTapCatalog.class);
            SingerTapCatalog catalog = context.getBean(SingerTapCatalog.class);
            assertThat(catalog.isEnabled()).isFalse();
            assertThat(catalog.all()).isEmpty();
        });
    }

    @Test
    @DisplayName("kebab-case settings bind onto the catalogue, key sets included")
    void binds_from_properties() {
        runner.withPropertyValues(
                "aran.singer.taps[0].id=exchangeratesapi",
                "aran.singer.taps[0].label=Exchange Rates API",
                "aran.singer.taps[0].command[0]=/opt/taps/bin/tap-exchangeratesapi",
                "aran.singer.taps[0].config-keys[0]=base",
                "aran.singer.taps[0].config-keys[1]=api_key",
                "aran.singer.taps[0].secret-keys[0]=api_key")
            .run(context -> {
                SingerTapCatalog catalog = context.getBean(SingerTapCatalog.class);

                assertThat(catalog.isEnabled()).isTrue();
                assertThat(catalog.all()).singleElement().satisfies(tap -> {
                    assertThat(tap.id()).isEqualTo("exchangeratesapi");
                    assertThat(tap.label()).isEqualTo("Exchange Rates API");
                    assertThat(tap.command()).containsExactly("/opt/taps/bin/tap-exchangeratesapi");
                    assertThat(tap.configKeys()).containsExactlyInAnyOrder("base", "api_key");
                    assertThat(tap.secretKeys()).containsExactly("api_key");
                });
            });
    }

    @Test
    @DisplayName("a tap with no command fails startup rather than binding to something unrunnable")
    void bad_configuration_fails_startup() {
        // Starting anyway would give an instance that looks healthy and offers a tap that cannot
        // run, and the day anyone notices is the day they needed it.
        runner.withPropertyValues("aran.singer.taps[0].id=broken")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("two taps sharing an id fail startup")
    void duplicate_ids_fail_startup() {
        runner.withPropertyValues(
                "aran.singer.taps[0].id=dup",
                "aran.singer.taps[0].command[0]=/bin/true",
                "aran.singer.taps[1].id=dup",
                "aran.singer.taps[1].command[0]=/bin/false")
            .run(context -> assertThat(context).hasFailed());
    }
}

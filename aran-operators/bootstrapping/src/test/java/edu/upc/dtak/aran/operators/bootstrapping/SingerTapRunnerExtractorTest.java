package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.SingerTapExtractor;
import edu.upc.dtak.aran.core.domain.SingerTapRepository;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import edu.upc.dtak.aran.core.source.TapConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The extraction seam (T6), against real tap processes.
 *
 * <p>The taps are shell scripts, as in {@code SingerTapRunnerTest}, because what this class adds to
 * the runner is exactly the things a mock would hide: the catalogue building the argv, the config
 * file the tap actually reads, and the byte ceiling.
 */
class SingerTapRunnerExtractorTest {

    private static final UUID ID = UUID.randomUUID();

    /** A tap script, registered in a catalogue under {@code fake}. */
    private static SingerTapCatalog catalogFor(Path dir, String body) throws IOException {
        Path script = dir.resolve("tap.sh");
        Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new SingerTapCatalog(List.of(new SingerTapCatalog.Tap(
                "fake", "Fake Tap", List.of(script.toAbsolutePath().toString()),
                Set.of("base", "api_key"), Set.of("api_key"))));
    }

    private static SingerTapRepository repository(Map<String, String> config) {
        return new SingerTapRepository(ID, UUID.randomUUID(), UUID.randomUUID(),
                "fake source", "", "fake", TapConfig.toJson(config));
    }

    @Test
    @DisplayName("a stream's records come back as parquet bytes with their row count")
    void extractsAStream(@TempDir Path dir) throws Exception {
        SingerTapCatalog catalog = catalogFor(dir,
                "cat <<'EOF'\n"
                + "{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":1,\"name\":\"Ada\"}}\n"
                + "{\"type\":\"RECORD\",\"stream\":\"users\",\"record\":{\"id\":2,\"name\":\"Grace\"}}\n"
                + "{\"type\":\"RECORD\",\"stream\":\"orders\",\"record\":{\"id\":9}}\n"
                + "EOF\n");

        SingerTapExtractor.Extracted extracted = new SingerTapRunnerExtractor(catalog)
                .extract(repository(Map.of("base", "EUR")), "users", 10_000_000);

        assertThat(new String(extracted.parquet(), 0, 4, StandardCharsets.US_ASCII))
                .describedAs("the lake upload tail expects parquet")
                .isEqualTo("PAR1");
        assertThat(extracted.rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the tap reads Aran's config file — settings are data, never argv")
    void settingsReachTheTapAsAFile(@TempDir Path dir) throws Exception {
        // The tap echoes back what it found in --config. If a value had become an argument it
        // would not be in the file, and if the file were missing the record would be empty.
        SingerTapCatalog catalog = catalogFor(dir,
                "config=\"\"\n"
                + "while [ $# -gt 0 ]; do\n"
                + "  if [ \"$1\" = \"--config\" ]; then config=\"$2\"; fi\n"
                + "  shift\n"
                + "done\n"
                + "base=$(sed -n 's/.*\"base\":\"\\([^\"]*\\)\".*/\\1/p' \"$config\")\n"
                + "printf '{\"type\":\"RECORD\",\"stream\":\"s\",\"record\":{\"base\":\"%s\"}}\\n' \"$base\"\n");

        SingerTapExtractor.Extracted extracted = new SingerTapRunnerExtractor(catalog)
                .extract(repository(Map.of("base", "EUR", "api_key", "k-123")), "s", 10_000_000);

        assertThat(extracted.rowCount()).isEqualTo(1);
        assertThat(new String(extracted.parquet(), StandardCharsets.ISO_8859_1))
                .describedAs("the tap read the value Aran wrote for it")
                .contains("EUR");
    }

    @Test
    @DisplayName("a tap the deployment does not declare is refused before anything runs")
    void unknownTapIsRefused(@TempDir Path dir) throws Exception {
        SingerTapCatalog catalog = catalogFor(dir, "exit 0\n");
        SingerTapRepository unlisted = new SingerTapRepository(ID, UUID.randomUUID(),
                UUID.randomUUID(), "x", "", "not-in-the-catalogue", "{}");

        assertThatThrownBy(() -> new SingerTapRunnerExtractor(catalog)
                .extract(unlisted, "s", 10_000_000))
                .isInstanceOf(SingerTapCatalog.TapNotAllowedException.class);
    }

    @Test
    @DisplayName("an extract over the byte ceiling is refused rather than buffered")
    void byteCeilingIsEnforced(@TempDir Path dir) throws Exception {
        SingerTapCatalog catalog = catalogFor(dir,
                "cat <<'EOF'\n"
                + "{\"type\":\"RECORD\",\"stream\":\"s\",\"record\":{\"id\":1}}\n"
                + "EOF\n");

        assertThatThrownBy(() -> new SingerTapRunnerExtractor(catalog)
                .extract(repository(Map.of()), "s", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("import limit");
    }

    @Test
    @DisplayName("discovery lists the streams the tap declares")
    void discoversStreams(@TempDir Path dir) throws Exception {
        SingerTapCatalog catalog = catalogFor(dir,
                "cat <<'EOF'\n"
                + "{\"type\":\"SCHEMA\",\"stream\":\"users\",\"schema\":{}}\n"
                + "{\"type\":\"SCHEMA\",\"stream\":\"orders\",\"schema\":{}}\n"
                + "EOF\n");

        assertThat(new SingerTapRunnerExtractor(catalog).discoverStreams(repository(Map.of())))
                .containsExactly("users", "orders");
    }

    @Test
    @DisplayName("a failing tap surfaces its own reason, and no config value with it")
    void failureCarriesTheTapsReasonOnly(@TempDir Path dir) throws Exception {
        SingerTapCatalog catalog = catalogFor(dir, "echo 'CRITICAL auth failed' >&2\nexit 1\n");

        assertThatThrownBy(() -> new SingerTapRunnerExtractor(catalog)
                .discoverStreams(repository(Map.of("api_key", "k-live-do-not-leak"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exited with status 1")
                .hasMessageNotContaining("k-live-do-not-leak");
    }
}

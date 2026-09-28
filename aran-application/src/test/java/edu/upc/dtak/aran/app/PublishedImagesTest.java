package edu.upc.dtak.aran.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * compose.standalone.yml and the CI images job describe the same three images, and the compose
 * file pins the platform version the build declares.
 *
 * <p>Each Aran-built service carries both {@code image:} and {@code build:}, so compose pulls the
 * published image when it can and builds the checkout when it cannot. That only holds together if
 * the name compose pulls is one CI pushes, built from the same context, at the version this
 * checkout is: the compose files pin them together. Three files hold those
 * facts and nothing else relates them: a module renamed in one, or a release commit that bumps
 * build.gradle and not compose, would make a checkout pull an image built from something else.
 */
class PublishedImagesTest {

    private static final String REGISTRY = "ghcr.io/dtak-upc/";

    /** The same extraction the images job runs with sed, so the two cannot read different lines. */
    private static final Pattern GRADLE_VERSION =
            Pattern.compile("(?m)^\\s*version = '([^']+)'$");

    private static final Pattern PINNED_IMAGE =
            Pattern.compile("^" + Pattern.quote(REGISTRY) + "([a-z0-9-]+):\\$\\{ARAN_VERSION:-([^}]+)}$");

    private static Path root;

    @BeforeAll
    static void locateRepository() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above " + System.getProperty("user.dir")).isNotNull();
        root = dir;
    }

    @Test
    @DisplayName("every Aran-built compose service pins ghcr.io/dtak-upc/<image>:${ARAN_VERSION:-<build.gradle version>}")
    void composePinsThePlatformVersion() throws IOException {
        String version = platformVersion();
        assertThat(composeImages()).as("Aran-built services in compose.standalone.yml").hasSize(3);
        for (Map.Entry<String, Object> service : composeServices().entrySet()) {
            Map<?, ?> definition = (Map<?, ?>) service.getValue();
            if (!(definition.get("build") instanceof Map<?, ?> build) || build.get("context") == null) {
                continue;
            }
            String image = String.valueOf(definition.get("image"));
            Matcher m = PINNED_IMAGE.matcher(image);
            assertThat(m.matches())
                    .as("service %s must name %s<image>:${ARAN_VERSION:-<version>} beside its build:, was %s",
                            service.getKey(), REGISTRY, image)
                    .isTrue();
            assertThat(m.group(2))
                    .as("service %s's default tag must be build.gradle's version", service.getKey())
                    .isEqualTo(version);
        }
    }

    @Test
    @DisplayName("the images compose pulls are exactly the ones CI publishes, from the same build contexts")
    void composeAndCiAgreeOnImagesAndContexts() throws IOException {
        Map<String, String> ci = new TreeMap<>();
        for (Map<?, ?> entry : ciImageMatrix()) {
            String context = normalise(String.valueOf(entry.get("context")));
            ci.put(String.valueOf(entry.get("image")), context);
            assertThat(normalise(String.valueOf(entry.get("file"))))
                    .as("CI builds %s from its context's own Dockerfile, as compose does", entry.get("image"))
                    .isEqualTo(context.isEmpty() ? "Dockerfile" : context + "/Dockerfile");
        }
        assertThat(composeImages()).isEqualTo(ci);
    }

    /** image name (without registry and tag) → build context, for services built from this repo. */
    private static Map<String, String> composeImages() throws IOException {
        Map<String, String> images = new TreeMap<>();
        for (Object value : composeServices().values()) {
            Map<?, ?> definition = (Map<?, ?>) value;
            if (definition.get("build") instanceof Map<?, ?> build && build.get("context") != null) {
                Matcher m = PINNED_IMAGE.matcher(String.valueOf(definition.get("image")));
                images.put(m.matches() ? m.group(1) : String.valueOf(definition.get("image")),
                        normalise(String.valueOf(build.get("context"))));
            }
        }
        return images;
    }

    private static Map<String, Object> composeServices() throws IOException {
        Map<String, Object> compose = load("compose.standalone.yml");
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        return services;
    }

    private static List<Map<?, ?>> ciImageMatrix() throws IOException {
        Map<String, Object> workflow = load(".github/workflows/ci.yml");
        Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
        Map<?, ?> images = (Map<?, ?>) jobs.get("images");
        assertThat(images).as("ci.yml's images job").isNotNull();
        Map<?, ?> matrix = (Map<?, ?>) ((Map<?, ?>) images.get("strategy")).get("matrix");
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> include = (List<Map<?, ?>>) matrix.get("include");
        return include;
    }

    private static String platformVersion() throws IOException {
        Matcher m = GRADLE_VERSION.matcher(read("build.gradle"));
        assertThat(m.find()).as("a `version = '…'` line in build.gradle").isTrue();
        return m.group(1);
    }

    private static String normalise(String path) {
        String p = path.startsWith("./") ? path.substring(2) : path;
        return p.equals(".") ? "" : p;
    }

    private static Map<String, Object> load(String relative) throws IOException {
        return new Yaml().load(read(relative));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }
}

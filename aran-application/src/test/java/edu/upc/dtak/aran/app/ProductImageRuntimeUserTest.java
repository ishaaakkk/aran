package edu.upc.dtak.aran.app;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.util.DuckDbSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * The published product image runs the backend as a non-root user, and DuckDB installs every
 * extension a federated read needs under that user's {@code ~/.duckdb}: {@code postgres} for
 * {@code postgres_scan}, {@code mysql} for the attached MySQL/MariaDB catalog, {@code httpfs} and
 * {@code iceberg} for {@code iceberg_scan} and {@code s3://}. A user created with no home gets
 * {@code HOME=/nonexistent}, and every one of those installs fails with "Can't find the home
 * directory", while CSV, JSON and parquet keep working because the driver bundles those. The
 * standalone smoke only uploads files, so it could not notice.
 *
 * <p>Building the whole image takes the full Gradle build, so this builds the runtime stage's own
 * user and filesystem instructions from the real Dockerfile, then runs the product's DuckDB session
 * in it as that user. The COPYs from the build stages are left out; they add the jar and the SPA,
 * which do not decide the user's home.
 */
@Tag("docker")
@DisplayName("The product image's runtime user")
class ProductImageRuntimeUserTest {

    /** A repository path with nothing in it: the install needs no network and fails on purpose. */
    private static final String EMPTY_EXTENSION_REPOSITORY = "/probe/no-extension-repository";

    /** What survives from the runtime stage: the instructions that make its user and filesystem. */
    private static final Set<String> KEPT = Set.of("FROM", "ARG", "ENV", "RUN", "USER", "WORKDIR", "SHELL");

    private static final Pattern RUNTIME_STAGE =
            Pattern.compile("(?i)^FROM\\s+\\S+\\s+AS\\s+runtime$");

    @Test
    @DisplayName("can install DuckDB extensions: federated reads through postgres, mysql, httpfs and iceberg need it")
    void duckDbReachesTheExtensionLookup() throws Exception {
        ImageFromDockerfile image = new ImageFromDockerfile()
                .withFileFromString("Dockerfile", runtimeStage(Files.readString(
                        repositoryRoot().resolve("Dockerfile"), StandardCharsets.UTF_8)));
        Path driver = codeSource(Class.forName("org.duckdb.DuckDBDriver"));

        try (GenericContainer<?> probe = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(driver), "/probe/duckdb.jar")
                .withCopyToContainer(classFile(DuckDbSession.class), "/probe/classes/" + classPath(DuckDbSession.class))
                .withCopyToContainer(classFile(DuckDbExtensionInstallProbe.class),
                        "/probe/classes/" + classPath(DuckDbExtensionInstallProbe.class))
                .withCommand("java", "-cp", "/probe/duckdb.jar:/probe/classes",
                        DuckDbExtensionInstallProbe.class.getName(), EMPTY_EXTENSION_REPOSITORY)
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)))) {
            probe.start();
            String output = probe.getLogs();

            // Past the home directory, DuckDB looks for postgres_scanner's file in the repository.
            assertThat(output)
                    .as("DuckDB, run as the image's user, must reach the extension lookup instead of"
                            + " failing on its home directory. Probe output:%n%s", output)
                    .doesNotContain("home directory")
                    .contains("postgres_scanner");
        }
    }

    /**
     * The runtime stage as a Dockerfile of its own: {@code FROM … AS runtime} up to the next
     * {@code FROM}, keeping only {@link #KEPT}. The probe's command replaces the ENTRYPOINT.
     */
    static String runtimeStage(String dockerfile) {
        List<String> instructions = instructions(dockerfile);
        int start = -1;
        for (int i = 0; i < instructions.size(); i++) {
            if (RUNTIME_STAGE.matcher(instructions.get(i)).matches()) {
                start = i;
            }
        }
        assertThat(start).as("a `FROM <image> AS runtime` stage in the Dockerfile").isNotNegative();

        StringBuilder stage = new StringBuilder();
        for (int i = start; i < instructions.size(); i++) {
            String keyword = instructions.get(i).split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
            if (i > start && keyword.equals("FROM")) {
                break;
            }
            if (KEPT.contains(keyword)) {
                stage.append(instructions.get(i)).append('\n');
            }
        }
        assertThat(stage.toString()).as("the runtime stage names its user").containsPattern("(?m)^USER ");
        return stage.toString();
    }

    /** One entry per instruction, continuation lines joined. Docker drops comment lines even inside a continuation. */
    private static List<String> instructions(String dockerfile) {
        List<String> out = new ArrayList<>();
        StringBuilder current = null;
        for (String line : dockerfile.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            boolean continues = trimmed.endsWith("\\");
            String body = continues ? trimmed.substring(0, trimmed.length() - 1).strip() : trimmed;
            current = current == null ? new StringBuilder(body) : current.append(' ').append(body);
            if (!continues) {
                out.add(current.toString());
                current = null;
            }
        }
        return out;
    }

    private static Transferable classFile(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertThat(in).as("%s's class file on the test classpath", type.getName()).isNotNull();
            return Transferable.of(in.readAllBytes());
        }
    }

    private static String classPath(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    private static Path codeSource(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Path repositoryRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above " + System.getProperty("user.dir")).isNotNull();
        return dir;
    }
}

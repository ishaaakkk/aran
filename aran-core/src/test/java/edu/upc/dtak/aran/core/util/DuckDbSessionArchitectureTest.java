package edu.upc.dtak.aran.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The structural half of the UTC session pin: {@link DuckDbSession} is the only main-source file
 * in the repository that names the DuckDB JDBC URL.
 *
 * <p>{@link DuckDbSessionTest} asserts what a pinned session does, but it cannot notice a
 * <em>new</em> connection site that never reached the helper — and on a UTC-zoned CI host an
 * unpinned session gives the right answer anyway, so no value assertion anywhere would catch it.
 * This test is what does: it reads the source trees the way the grep in {@code DuckDbSession}'s
 * javadoc does, and fails with the offending paths.
 *
 * <p>Scope is {@code src/main/java} only. Test sources open their own connections freely (the
 * perf harness deliberately does, on its own connection, and says so); a test that depends on the
 * session zone should say so itself rather than be policed from here.
 */
class DuckDbSessionArchitectureTest {

    private static final String URL_TOKEN = "jdbc:duckdb";

    /** The one file allowed to name it. */
    private static final String HELPER = "DuckDbSession.java";

    /** Modules that may contain a {@code src/main/java} tree. Named, not discovered, so a new
     *  module has to be added here deliberately — the alternative silently exempts it. */
    private static final List<String> MODULE_ROOTS = List.of(
            "aran-core", "aran-application", "aran-adapters", "aran-operators", "aran-client");

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir)
                .as("repository root (the directory holding settings.gradle) above " + System.getProperty("user.dir"))
                .isNotNull();
        return dir;
    }

    @Test
    @DisplayName("DuckDbSession is the only main-source file that opens a jdbc:duckdb connection")
    void noOtherMainSourceFileNamesTheDuckDbUrl() throws IOException {
        Path root = repoRoot();
        List<Path> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        for (String module : MODULE_ROOTS) {
            Path moduleDir = root.resolve(module);
            if (!Files.isDirectory(moduleDir)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(moduleDir)) {
                List<Path> mainSources = tree
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.toString().contains("/src/main/java/"))
                        .toList();
                for (Path source : mainSources) {
                    scanned.add(source);
                    if (source.getFileName().toString().equals(HELPER)) {
                        continue;
                    }
                    String text = Files.readString(source, StandardCharsets.UTF_8);
                    if (text.contains(URL_TOKEN)) {
                        offenders.add(root.relativize(source).toString());
                    }
                }
            }
        }

        // A path bug that scanned nothing would make the assertion below pass while proving
        // nothing, which is the one way an architecture test fails silently.
        assertThat(scanned).as("main-source files scanned").hasSizeGreaterThan(200);

        assertThat(offenders)
                .as("main-source files opening a DuckDB connection outside "
                        + "edu.upc.dtak.aran.core.util.DuckDbSession — every DuckDB session must be "
                        + "pinned to UTC, so route these through DuckDbSession.open()")
                .isEmpty();
    }
}

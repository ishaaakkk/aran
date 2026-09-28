package edu.upc.dtak.aran.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Names#sqlLiteral} stays the only place a main-source file doubles a single quote.
 *
 * <p>Its javadoc already said so, and eight private copies grew back anyway (S18-2) — each one
 * correct, but a missed copy is an injection rather than a drift, so the rule is enforced here
 * rather than trusted to prose. Same scan shape as {@link DuckDbSessionArchitectureTest}.
 */
class SqlLiteralArchitectureTest {

    private static final Pattern QUOTE_DOUBLING =
            Pattern.compile("\\.replace(All)?\\(\\s*\"'\"\\s*,\\s*\"''\"\\s*\\)");

    private static final String OWNER = "Names.java";

    private static final List<String> MODULE_ROOTS = List.of(
            "aran-core", "aran-application", "aran-adapters", "aran-operators", "aran-client");

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above " + System.getProperty("user.dir")).isNotNull();
        return dir;
    }

    @Test
    @DisplayName("Names is the only main-source file that escapes a SQL string literal by hand")
    void noPrivateSqlLiteralCopies() throws IOException {
        Path root = repoRoot();
        List<Path> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        for (String module : MODULE_ROOTS) {
            Path moduleDir = root.resolve(module);
            if (!Files.isDirectory(moduleDir)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(moduleDir)) {
                for (Path source : tree
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.toString().contains("/src/main/java/"))
                        .toList()) {
                    scanned.add(source);
                    if (source.getFileName().toString().equals(OWNER)) {
                        continue;
                    }
                    if (QUOTE_DOUBLING.matcher(Files.readString(source, StandardCharsets.UTF_8)).find()) {
                        offenders.add(root.relativize(source).toString());
                    }
                }
            }
        }

        assertThat(scanned).as("main-source files scanned").hasSizeGreaterThan(200);
        assertThat(offenders)
                .as("main-source files doubling single quotes outside Names.sqlLiteral")
                .isEmpty();
    }
}

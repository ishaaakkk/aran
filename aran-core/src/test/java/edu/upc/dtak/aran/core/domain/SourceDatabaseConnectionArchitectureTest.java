package edu.upc.dtak.aran.core.domain;

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
 * The structural half of "every source-database connection comes from {@link DriverDownloader}".
 *
 * <p>{@code JdbcBootstrap} opened its own through {@code DriverManager} for months while three
 * sibling sites each carried a javadoc explaining why they must not — and the copy was invisible
 * to every behavioural test, because on a machine with the Postgres driver on the classpath (which
 * is every machine that runs this build) {@code DriverManager} answers correctly. Only a
 * federated MySQL or MariaDB repository, which nothing tests live, saw "No suitable driver"
 * (S4-4/S3-3). A grep is what notices the next copy; a comment is not.
 *
 * <p>Written as a source scan rather than with ArchUnit: aran-core's tests have no ArchUnit
 * dependency (the one ArchUnit suite, aran-application's {@code ArchitectureTest}, freezes the
 * remaining {@code DriverManager} uses), and the thing being pinned is exactly the grep those
 * javadocs already tell a reader to run.
 *
 * <p>Scope is {@code src/main/java} only. Test sources open their own connections freely — the
 * DuckDB stand-ins that play a source database do precisely that.
 */
class SourceDatabaseConnectionArchitectureTest {

    /**
     * Opening a connection, and the JVM-global login timeout. The second is not merely a weaker
     * bound but shared mutable state: {@code DriverManager.setLoginTimeout} is read by Hikari and
     * by every other JDBC user in the process.
     */
    private static final List<String> FORBIDDEN_TOKENS =
            List.of("DriverManager.getConnection", "DriverManager.setLoginTimeout");

    /**
     * The one file allowed to open a connection this way — and it opens an in-process DuckDB, not
     * a source database, so no driver control applies to it. Its own architecture test pins that.
     */
    private static final String DUCKDB_HELPER = "DuckDbSession.java";

    /** Named, not discovered, so a new module has to be added here deliberately. */
    private static final List<String> MODULE_ROOTS = List.of(
            "aran-core", "aran-application", "aran-adapters", "aran-operators", "aran-client");

    @Test
    @DisplayName("no main-source file opens a source-database connection through DriverManager")
    void everySourceDatabaseConnectionComesFromTheDownloader() throws IOException {
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
                    if (source.getFileName().toString().equals(DUCKDB_HELPER)) {
                        continue;
                    }
                    String text = Files.readString(source, StandardCharsets.UTF_8);
                    for (String token : FORBIDDEN_TOKENS) {
                        if (containsCall(text, token)) {
                            offenders.add(root.relativize(source) + " — " + token);
                        }
                    }
                }
            }
        }

        // A path bug that scanned nothing would make the assertion below pass while proving
        // nothing, which is the one way an architecture test fails silently.
        assertThat(scanned).as("main-source files scanned").hasSizeGreaterThan(200);

        assertThat(offenders)
                .as("a driver loaded by DriverDownloader lives in a child URLClassLoader that "
                        + "DriverManager cannot see, so these connections work only for the vendor "
                        + "driver that happens to be on the application classpath — obtain the "
                        + "driver from DriverDownloader and call driver.connect(url, props)")
                .isEmpty();
    }

    /**
     * Whether the token appears as a call rather than only inside prose. Javadoc is where these
     * files explain why they do <em>not</em> use {@code DriverManager}, so a scan that counted
     * that would forbid documenting the rule — the token is only an offence when a {@code (}
     * follows it.
     */
    private static boolean containsCall(String text, String token) {
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            int after = at + token.length();
            if (after < text.length() && text.charAt(after) == '(') {
                return true;
            }
            from = after;
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir)
                .as("repository root (the directory holding settings.gradle) above "
                        + System.getProperty("user.dir"))
                .isNotNull();
        return dir;
    }
}

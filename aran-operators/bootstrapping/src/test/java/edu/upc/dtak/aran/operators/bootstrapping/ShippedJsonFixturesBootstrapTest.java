package edu.upc.dtak.aran.operators.bootstrapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON files the repository hands to people — the seminar demo the docs walkthrough uploads,
 * and the e2e corpus — must bootstrap. S4-2 refuses an object with two array-of-primitive fields,
 * and {@code demo/seminar/semantic_papers.json} carried exactly that shape ({@code authors} and
 * {@code keywords}) for weeks after the refusal landed: the walkthrough's upload step failed with
 * BOOTSTRAP_FAILED and nothing but a browser would have noticed.
 */
class ShippedJsonFixturesBootstrapTest {

    /** Directories whose JSON files a user or a spec uploads as-is. */
    private static final List<String> FIXTURE_DIRS = List.of("demo/seminar", "aran-web/e2e/data");

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

    /** Repository-relative paths, so a failure names the file the way a reader would. */
    static Stream<String> shippedJsonFixtures() throws IOException {
        Path root = repoRoot();
        List<String> files = new ArrayList<>();
        for (String dir : FIXTURE_DIRS) {
            try (Stream<Path> listing = Files.list(root.resolve(dir))) {
                listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted()
                        .forEach(p -> files.add(root.relativize(p).toString()));
            }
        }
        // An empty source would pass vacuously, which is the one outcome this guard must not have.
        assertThat(files).as("JSON fixtures under " + FIXTURE_DIRS).isNotEmpty();
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shippedJsonFixtures")
    @DisplayName("every shipped JSON fixture bootstraps and gets a wrapper for each of its classes")
    void everyShippedJsonFixtureBootstraps(String relativePath) throws IOException {
        Path fixture = repoRoot().resolve(relativePath);
        String name = fixture.getFileName().toString().replace(".json", "");
        Model model = new JsonBootstrap().bootstrap("ds-" + name, name, "snap-" + name, Files.readAllBytes(fixture));

        int classes = model.listSubjectsWithProperty(WrapperVocab.ITERATOR).toList().size();
        assertThat(classes).as("classes bootstrapped from " + relativePath).isPositive();
        assertThat(WrapperGenerator.attachPerClassWrappers(model))
                .as("a class without a wrapper cannot be queried")
                .isEqualTo(classes);
    }

    /**
     * The one deliberate exception: {@code aran-web/e2e/data/refused/} holds fixtures an e2e spec
     * uploads to watch a bootstrap FAIL and say why (UI audit J2-F5, {@code a1-single-csv.spec}).
     * Kept out of the directory above, and held to the opposite rule here — if the refusal were
     * ever lifted, the spec would silently stop exercising a failure, so this goes red instead.
     */
    @Test
    @DisplayName("every fixture an e2e spec uploads to watch a bootstrap fail is still refused")
    void refusedFixturesAreStillRefused() throws IOException {
        Path dir = repoRoot().resolve("aran-web/e2e/data/refused");
        List<Path> fixtures;
        try (Stream<Path> listing = Files.list(dir)) {
            fixtures = listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertThat(fixtures).as("refused fixtures under " + dir).isNotEmpty();
        for (Path fixture : fixtures) {
            String name = fixture.getFileName().toString().replace(".json", "");
            byte[] bytes = Files.readAllBytes(fixture);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            new JsonBootstrap().bootstrap("ds-" + name, name, "snap-" + name, bytes))
                    .as(fixture + " must be refused at bootstrap")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("array-of-primitive");
        }
    }

    @Test
    @DisplayName("the walkthrough's semantic_papers.json unnests authors to one row per (paper, author)")
    void semanticPapersUnnestsAuthorsToOneRowPerPaperAuthorPair() throws Exception {
        Path fixture = repoRoot().resolve("demo/seminar/semantic_papers.json");
        byte[] bytes = Files.readAllBytes(fixture);
        Model model = new JsonBootstrap().bootstrap("ds-semantic", "semantic", "snap-semantic", bytes);
        assertThat(WrapperGenerator.attachPerClassWrappers(model)).isEqualTo(1);

        String wrapper = null;
        for (Statement s : model.listStatements(null, WrapperVocab.WRAPPER_SQL, (RDFNode) null).toList()) {
            wrapper = s.getObject().asLiteral().getString();
        }
        assertThat(wrapper).isNotNull();

        // Derived from the file, not written down: one expected row per author entry.
        List<String> expected = new ArrayList<>();
        for (JsonNode paper : new ObjectMapper().readTree(bytes)) {
            for (JsonNode author : paper.get("authors")) {
                expected.add(paper.get("doi").asText() + " " + author.asText());
            }
        }

        String doiColumn = BootstrapEngine.sourcePropertyIri("ds-semantic", "semantic", "doi");
        String authorsColumn = BootstrapEngine.sourcePropertyIri("ds-semantic", "semantic", "authors");
        String sql = "SELECT \"" + doiColumn + "\", \"" + authorsColumn + "\" FROM ("
                + wrapper.replace(WrapperVocab.SNAPSHOT_PLACEHOLDER,
                        "read_json_auto('" + fixture.toAbsolutePath() + "')") + ")";
        List<String> actual = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                actual.add(rs.getString(1) + " " + rs.getString(2));
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}

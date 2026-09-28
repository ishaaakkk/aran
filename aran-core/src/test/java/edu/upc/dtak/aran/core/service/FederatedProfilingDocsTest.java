package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.FederatedProfilingBinding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The docs must describe federated profiling the way the compile tail does it. For a
 * snapshot-less FEDERATED dataset, {@link IntegrationModelService} profiles every Opaque class
 * over the live read, and it does so for relational sources too. {@link
 * IntegrationModelServiceFederatedProfilingTest} and {@code FederatedProfilingBindingTest} pin
 * that. Six passages said such a dataset is never profiled and has only catalog statistics.
 * Someone who believed them would not expect a full profiling pass against their production
 * database on every compile, or FREYJA connecting to it with the repository's credentials.
 */
@DisplayName("The docs describe federated profiling as the compile tail performs it")
class FederatedProfilingDocsTest {

    /** Each of these was in the docs, and each is false while the relational binding exists. */
    private static final List<Pattern> RETIRED_CLAIMS = Stream.of(
                    "FREYJA cannot profile a federated",
                    "federated dataset cannot be profiled",
                    "profiling step skips a dataset with no snapshot",
                    "only statistics are harvested",
                    "only profiles are harvested",
                    "harvested profiles only",
                    "no snapshot to profile\\. Its numbers come from the database's own catalog")
            .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
            .toList();

    private static Path docs;

    @BeforeAll
    static void locateDocs() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above " + System.getProperty("user.dir")).isNotNull();
        docs = dir.resolve("docs-site/docs");
    }

    @Test
    @DisplayName("PostgreSQL and MySQL datasets have a live profiling binding, the fact the pages below describe")
    void relationalDatasetsProfileOverTheLiveRead() {
        for (String engine : List.of("POSTGRESQL", "MYSQL")) {
            RelationalDatabaseRepository repository = new RelationalDatabaseRepository(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "",
                    engine, "db-host", 5432, "db", "user", "pw", "public", true);
            assertThat(FederatedProfilingBinding.forDataset(repository, "venue", null))
                    .as("%s's live profiling binding", engine)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("no page says a federated dataset goes unprofiled or has catalog statistics only")
    void noPageMakesARetiredClaim() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> pages = Files.walk(docs)) {
            for (Path page : pages.filter(p -> p.toString().matches(".*\\.mdx?$")).sorted().toList()) {
                // Neither emphasis nor a paragraph's line breaks are part of a claim.
                String text = Files.readString(page, StandardCharsets.UTF_8)
                        .replace("*", "")
                        .replaceAll("\\s+", " ");
                for (Pattern claim : RETIRED_CLAIMS) {
                    if (claim.matcher(text).find()) {
                        found.add(docs.relativize(page) + ": /" + claim.pattern() + "/");
                    }
                }
            }
        }
        assertThat(found).as("pages that deny the compile tail's federated profiling").isEmpty();
    }

    @Test
    @DisplayName("'Query a database live' lists FREYJA profiles as working, and the snapshot-only features as not")
    void liveQueryFeatureTable() throws IOException {
        Map<String, String> rows = tableRows(docs.resolve("how-to/query-a-database-live.md"));

        assertThat(rows.get("Column profiles from FREYJA")).startsWith("Yes");
        assertThat(rows.get("The dataset tab's **Profile** action")).startsWith("**No.**");
        assertThat(rows.get("The Wrapper tab's **Validate & Preview**")).startsWith("**No.**");
        assertThat(rows.get("Constraint discovery")).startsWith("**No.**");
    }

    /** First cell to second cell, for every Markdown table row on the page. */
    private static Map<String, String> tableRows(Path page) throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(page, StandardCharsets.UTF_8)) {
            if (!line.startsWith("|")) {
                continue;
            }
            String[] cells = line.split("\\|");
            if (cells.length > 2) {
                rows.putIfAbsent(cells[1].strip(), cells[2].strip());
            }
        }
        return rows;
    }
}

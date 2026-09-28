package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paging the 1,000-row cap, and the uncapped export that replaces "increase the LIMIT".
 *
 * <p>The cap is right for a JSON payload rendered into an HTML table and wrong as the only way to
 * get data out — and it <em>was</em> the only way: the data-product path materialises without a cap
 * but then serves a 100-row preview, so a query returning 50,000 rows had no route past the first
 * thousand. Worse, the workbench told users to raise the LIMIT, which cannot work: {@code capRows}
 * wraps every statement and {@code executeSql} caps regardless.
 */
class PagingAndExportTest {

    private static final String CLS = "http://example.com/N";
    private static final String NUM = "http://example.com/n";

    /** {@code rows} numbered 0..n-1, so a page's contents identify the offset unambiguously. */
    private static Path fixture(int rows) throws Exception {
        Path p = Files.createTempFile("aran-paging-", ".json");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            sb.append("{\"n\":").append(i).append("}\n");
        }
        Files.writeString(p, sb.toString());
        return p;
    }

    private static MappingCatalog catalog() {
        var cls = new MappingCatalog.IntegratedClass(CLS, List.of(
                new MappingCatalog.SourceView("ds", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(NUM, "n"), 0)));
        return new MappingCatalog(Map.of(CLS, cls), List.of(), List.of(), "v1");
    }

    /** Run a page exactly as {@code executeSql} does, returning its first-column values. */
    private static List<Object> page(Path data, long offset) throws Exception {
        DuckDbExecutor.SnapshotResolver resolver =
                d -> "read_json_auto('" + data.toAbsolutePath() + "')";
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver)) {
            String sql = QueryEngineOperator.capRows("SELECT * FROM int_N ORDER BY 1", offset);
            exec.onlyClassesReferencedBy(sql).prepare();
            List<Object> values = new ArrayList<>();
            for (List<Object> row : exec.run(sql).rows()) {
                values.add(row.get(0));
            }
            return values;
        }
    }

    // ── paging ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("offset 0 returns the cap plus one sentinel row, which is how hasMore is known")
    void firstPageCarriesTheSentinel() throws Exception {
        Path data = fixture(2500);
        try {
            // 1001 rows come back for a 1000-row page: the extra one tells executeSql another
            // page exists without counting the whole result.
            assertThat(page(data, 0)).hasSize(1001);
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("consecutive pages are disjoint and in order")
    void pagesDoNotOverlapOrSkip() throws Exception {
        Path data = fixture(2500);
        try {
            List<Object> first = page(data, 0);
            List<Object> second = page(data, 1000);
            List<Object> third = page(data, 2000);

            assertThat(first.get(0)).isEqualTo(0L);
            assertThat(second.get(0))
                    .as("page 2 must start exactly where page 1's cap ended")
                    .isEqualTo(1000L);
            assertThat(third.get(0)).isEqualTo(2000L);
            // 2500 rows total, so the third page has 500 and no sentinel — the last page.
            assertThat(third).hasSize(500);
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("an offset past the end returns nothing rather than failing")
    void offsetBeyondTheEnd() throws Exception {
        Path data = fixture(10);
        try {
            assertThat(page(data, 5000)).isEmpty();
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("the user's own LIMIT still applies inside the page")
    void innerLimitSurvivesPaging() throws Exception {
        Path data = fixture(2500);
        try {
            DuckDbExecutor.SnapshotResolver resolver =
                    d -> "read_json_auto('" + data.toAbsolutePath() + "')";
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver)) {
                String sql = QueryEngineOperator.capRows(
                        "SELECT * FROM int_N ORDER BY 1 LIMIT 20", 10);
                exec.onlyClassesReferencedBy(sql).prepare();
                // The inner LIMIT 20 bounds the result; the page then takes rows 10..19 of it.
                assertThat(exec.run(sql).rows()).hasSize(10);
            }
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("offset 0 emits no OFFSET clause, so the common case is unchanged SQL")
    void zeroOffsetIsClean() {
        assertThat(QueryEngineOperator.capRows("SELECT 1", 0)).doesNotContain("OFFSET");
        assertThat(QueryEngineOperator.capRows("SELECT 1", 0))
                .isEqualTo(QueryEngineOperator.capRows("SELECT 1"));
        assertThat(QueryEngineOperator.capRows("SELECT 1", 3000)).endsWith("LIMIT 1001 OFFSET 3000");
    }

    @Test
    @DisplayName("paging composes with the trailing-semicolon and trailing-comment handling")
    void pagingKeepsTheWrapProperties() throws Exception {
        // Separately, not combined: `SELECT …;  -- note` is a semicolon followed by more text,
        // which rejectIfNotReadOnly refuses as "only a single statement is allowed" long before
        // capRows sees it. Asserting the combination would be testing a shape the product does
        // not accept, and it failed here for exactly that reason on the first run.
        Path data = fixture(1200);
        try {
            DuckDbExecutor.SnapshotResolver resolver =
                    d -> "read_json_auto('" + data.toAbsolutePath() + "')";
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver)) {
                for (String statement : List.of(
                        "SELECT * FROM int_N ORDER BY 1;",
                        "SELECT * FROM int_N ORDER BY 1 -- second page please")) {
                    String sql = QueryEngineOperator.capRows(statement, 1000);
                    exec.onlyClassesReferencedBy(sql).prepare();
                    assertThat(exec.run(sql).rows())
                            .as("page 2 of 1200 rows, for: %s", statement)
                            .hasSize(200);
                }
            }
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("a mid-statement semicolon is the guard's job, not the wrap's")
    void multiStatementIsRejectedUpstream() {
        // Pinning the division of responsibility the test above depends on.
        assertThat(QueryEngineOperator.rejectIfNotReadOnly(
                "SELECT * FROM int_N; -- and more"))
                .isEqualTo("Only a single statement is allowed.");
        assertThat(QueryEngineOperator.rejectIfNotReadOnly("SELECT * FROM int_N;")).isNull();
    }

    // ── uncapped export ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the export path writes every row, not a page")
    void exportIsUncapped() throws Exception {
        // materialize-oql's COPY has no cap at either layer. Asserted directly against the SQL
        // that operation runs, because that is the whole claim behind the download button.
        Path data = fixture(2500);
        Path target = Files.createTempFile("aran-export-test-", ".csv");
        Files.deleteIfExists(target);
        try {
            DuckDbExecutor.SnapshotResolver resolver =
                    d -> "read_json_auto('" + data.toAbsolutePath() + "')";
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver)) {
                String sql = "SELECT * FROM int_N";
                exec.onlyClassesReferencedBy(sql).prepare();
                exec.execute("COPY (" + sql + ") TO '" + target.toAbsolutePath()
                        + "' (FORMAT CSV, HEADER)");
                var count = exec.run("SELECT count(*) FROM read_csv_auto('"
                        + target.toAbsolutePath() + "')");
                assertThat(count.rows().get(0).get(0))
                        .as("all 2500 rows, against a 1000-row workbench cap")
                        .isEqualTo(2500L);
            }
            assertThat(Files.readString(target).lines().findFirst().orElse(""))
                    .as("CSV export must carry a header row to be usable in a spreadsheet")
                    .contains(NUM);
        } finally {
            Files.deleteIfExists(data);
            Files.deleteIfExists(target);
        }
    }
}

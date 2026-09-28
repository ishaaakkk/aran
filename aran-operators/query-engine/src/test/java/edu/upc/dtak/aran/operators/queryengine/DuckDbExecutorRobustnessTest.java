package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Failure reporting and the runaway-query ceiling. */
class DuckDbExecutorRobustnessTest {

    private static final String CLS = "http://example.com/C";
    private static final String NAME = "http://example.com/name";

    private static MappingCatalog catalog() {
        var cls = new MappingCatalog.IntegratedClass(CLS, List.of(
                new MappingCatalog.SourceView("ds", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(NAME, "n"), 1)));
        return new MappingCatalog(Map.of(CLS, cls), List.of(), List.of(), "v1");
    }

    private static Path fixture() throws Exception {
        Path p = Files.createTempFile("aran-robust-", ".json");
        Files.writeString(p, "{\"n\":\"a\"}\n");
        return p;
    }

    @Test
    @DisplayName("a bad column name reaches the caller with DuckDB's own diagnostic")
    void queryErrorCarriesTheCause() throws Exception {
        // The wrapper's message is a constant, and QueryEngineOperator's catch-all reports
        // getMessage(), so this used to surface as "Query execution failed" and nothing else.
        Path data = fixture();
        try {
            DuckDbExecutor.SnapshotResolver resolver =
                    d -> "read_json_auto('" + data.toAbsolutePath() + "')";
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver)) {
                exec.prepare();
                assertThatThrownBy(() -> exec.run("SELECT titel FROM int_C"))
                        .hasMessageContaining("Query execution failed")
                        .hasMessageContaining("titel");
            }
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("a failing prepare says which statement broke, not just that prepare failed")
    void prepareErrorCarriesTheCause() {
        // A wrapper referencing a column its source does not have is the realistic case: the
        // failure happens in DDL, long before any user query runs.
        var cls = new MappingCatalog.IntegratedClass(CLS, List.of(
                new MappingCatalog.SourceView("ds", "SELECT missing_column FROM ${SNAPSHOT}",
                        Map.of(NAME, "n"), 1)));
        var cat = new MappingCatalog(Map.of(CLS, cls), List.of(), List.of(), "v1");

        try (DuckDbExecutor exec = new DuckDbExecutor(cat, d -> "(SELECT 'a' AS n)")) {
            assertThatThrownBy(exec::prepare)
                    .hasMessageContaining("Failed to prepare DuckDB executor")
                    .hasMessageContaining("missing_column");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("an unbounded query is interrupted rather than running forever")
    void runawayQueryIsInterrupted() throws Exception {
        // Without a ceiling, a cross join over two federated scans pins the request thread and
        // its connection until the client gives up — and the server never learns to stop.
        // Verified as behaviour, because setQueryTimeout silently doing nothing would be the
        // failure mode worth catching.
        Path data = fixture();
        try {
            DuckDbExecutor.SnapshotResolver resolver =
                    d -> "read_json_auto('" + data.toAbsolutePath() + "')";
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver)) {
                exec.prepare();
                assertThat(exec.queryTimeoutSeconds())
                        .as("run() must carry a finite ceiling")
                        .isGreaterThan(0);

                // Ceiling lowered so the test does not have to wait the production two minutes.
                exec.setQueryTimeoutSeconds(1);
                long started = System.currentTimeMillis();
                assertThatThrownBy(() -> exec.run("SELECT count(*) FROM range(200000000000)"))
                        .hasMessageContaining("Query execution failed");
                assertThat(System.currentTimeMillis() - started)
                        .as("the timeout must actually fire")
                        .isLessThan(30_000L);
            }
        } finally {
            Files.deleteIfExists(data);
        }
    }
}

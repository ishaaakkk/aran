package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 1000-row cap on the interactive query surface.
 *
 * <p>It used to be applied by appending {@code " LIMIT 1001"} to whatever the user typed, unless
 * the statement already {@code contains(" limit ")}. Textual append onto user SQL fails on three
 * inputs a real user produces without trying, all reproduced below as executions against DuckDB
 * rather than string assertions — the old code passed every string-shaped test that could have
 * been written about it.
 */
class RowCapTest {

    private static final String CLS = "http://example.com/C";
    private static final String NAME = "http://example.com/name";
    private static final String NUM = "http://example.com/num";

    /** Two rows, one of which contains the word "limit" in its text. */
    private static Path fixture() throws Exception {
        Path p = Files.createTempFile("aran-rowcap-", ".json");
        Files.writeString(p, """
                {"n":"alpha","m":1}
                {"n":"no limit here","m":2}
                """);
        return p;
    }

    private static MappingCatalog catalog() {
        var cls = new MappingCatalog.IntegratedClass(CLS, List.of(
                new MappingCatalog.SourceView("ds", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(NAME, "n", NUM, "m"), 2)));
        return new MappingCatalog(Map.of(CLS, cls), List.of(), List.of(), "v1");
    }

    /** Run {@code sql} through the cap exactly as {@code executeSql} does. */
    private static DuckDbExecutor.QueryResult capped(String sql) throws Exception {
        Path data = fixture();
        try {
            DuckDbExecutor.SnapshotResolver resolver =
                    d -> "read_json_auto('" + data.toAbsolutePath() + "')";
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver)) {
                exec.prepare();
                return exec.run(QueryEngineOperator.capRows(sql));
            }
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("a trailing semicolon no longer produces a parser error")
    void trailingSemicolon() throws Exception {
        // `SELECT * FROM int_C;` + " LIMIT 1001" is `SELECT …; LIMIT 1001`, which DuckDB rejects
        // with "syntax error at or near LIMIT". The read-only guard strips a trailing semicolon
        // for its own check and passes the statement on unchanged, so this reached the engine.
        assertThat(capped("SELECT * FROM int_C;").rows()).hasSize(2);
        assertThat(capped("SELECT * FROM int_C ;  ; ").rows()).hasSize(2);
    }

    @Test
    @DisplayName("the word 'limit' inside a string literal no longer suppresses the cap")
    void limitInsideStringLiteral() throws Exception {
        // contains(" limit ") matched the literal, so no LIMIT was pushed and the engine
        // materialised the whole result set to be trimmed in Java afterwards.
        String sql = "SELECT * FROM int_C WHERE \"" + NAME + "\" = 'no limit here'";
        assertThat(QueryEngineOperator.capRows(sql))
                .as("the cap must be present regardless of what the literal says")
                .contains("LIMIT 1001");
        assertThat(capped(sql).rows()).hasSize(1);
    }

    @Test
    @DisplayName("a trailing -- comment no longer swallows the cap")
    void trailingLineComment() throws Exception {
        String sql = "SELECT * FROM int_C -- what I was trying";
        assertThat(capped(sql).rows()).hasSize(2);
        // The newline before the closing paren is the whole reason this parses.
        assertThat(QueryEngineOperator.capRows(sql)).contains("\n) AS _aran_capped");
    }

    @Test
    @DisplayName("a user's own LIMIT is respected, not overridden")
    void innerLimitWins() throws Exception {
        assertThat(capped("SELECT * FROM int_C LIMIT 1").rows()).hasSize(1);
    }

    @Test
    @DisplayName("ORDER BY, CTEs and aggregates survive being wrapped")
    void shapesThatMustStillWork() throws Exception {
        assertThat(capped("SELECT * FROM int_C ORDER BY 1").rows()).hasSize(2);
        assertThat(capped("WITH t AS (SELECT * FROM int_C) SELECT * FROM t").rows()).hasSize(2);
        assertThat(capped("SELECT count(*) FROM int_C").rows()).hasSize(1);
    }

    @Test
    @DisplayName("wrapping de-duplicates repeated output names — the one visible behaviour change")
    void duplicateColumnNamesAreRenamed() throws Exception {
        // A subquery may not have two columns of the same name, so DuckDB disambiguates. Worth
        // pinning: it is the only way the wrap is observable from the outside.
        var result = capped("SELECT \"" + NAME + "\", \"" + NAME + "\" FROM int_C");
        assertThat(result.headers()).hasSize(2);
        assertThat(result.headers().get(0)).isNotEqualTo(result.headers().get(1));
    }

    @Test
    @DisplayName("the cap is the row limit the operator promises")
    void capMatchesMaxRows() {
        // MAX_ROWS + 1: one row over the cap is what tells executeSql to report truncated=true.
        assertThat(QueryEngineOperator.capRows("SELECT 1")).endsWith("LIMIT 1001");
    }
}

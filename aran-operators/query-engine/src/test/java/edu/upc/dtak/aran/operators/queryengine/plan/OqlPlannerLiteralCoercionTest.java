package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V38: {@code OqlPlanner.literal} used to type every single-quoted WHERE literal as an untyped
 * CHAR, with no coercion against the target field's type. That survives fine on its own — DuckDB
 * coerces {@code WHERE year = '30'} against a numeric column — but once a denial constraint is
 * enforced on the same class, {@code RexSimplify.simplifyUsingPredicates} tries to merge the
 * CHAR literal's {@code NlsString} into a numeric range built from the constraint's own literal
 * and casts, crashing both {@code rewrite-oql} and {@code run-oql}
 * ({@code NlsString cannot be cast to BigDecimal}).
 *
 * <p>The fix types the WHERE literal against {@code field.getType()} at plan time: a numeric/
 * date/timestamp field with a parseable literal gets a properly typed {@link
 * org.apache.calcite.rex.RexLiteral}; an unparseable one is a clear {@link
 * OqlPlanner.OqlPlanException} naming the column, the literal and the expected type — never a
 * crash, never a silently-wrong plan. String fields are untouched.
 */
class OqlPlannerLiteralCoercionTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String ARTWORKS = NS + "integration/Artworks";
    private static final String TITLE = NS + "integration/title";
    private static final String YEAR = NS + "integration/year";
    private static final String RELEASED = NS + "integration/released";
    private static final String LOGGED_AT = NS + "integration/loggedAt";
    private static final String FEATURED = NS + "integration/featured";
    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";
    private static final String XSD_DATE = "http://www.w3.org/2001/XMLSchema#date";
    private static final String XSD_DATETIME = "http://www.w3.org/2001/XMLSchema#dateTime";
    private static final String XSD_BOOLEAN = "http://www.w3.org/2001/XMLSchema#boolean";

    /** {@code year} carries an {@code xsd:integer} range, so its row-type field is BIGINT. */
    private static MappingCatalog catalogFor(File snapshot) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", YEAR, "year"),
                        2L, List.of(), "", Map.of(YEAR, XSD_INTEGER))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    private static final Map<String, List<String>> YEAR_POSITIVE = Map.of(ARTWORKS, List.of("year > 0"));

    /** DATE, TIMESTAMP and BOOLEAN columns — the rest of the non-VARCHAR types {@link XsdTypes}
     * can produce, so the coercion switch's other branches aren't unexercised. Plan-only: these
     * tests inspect the literal {@link OqlPlanner} builds, not execution. */
    private static MappingCatalog catalogWithTemporalColumns() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", RELEASED, "released", LOGGED_AT, "loggedAt", FEATURED, "featured"),
                        2L, List.of(), "",
                        Map.of(RELEASED, XSD_DATE, LOGGED_AT, XSD_DATETIME, FEATURED, XSD_BOOLEAN))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    private static File snapshotFile() throws Exception {
        Path tempDir = Files.createTempDirectory("aran-v38-test");
        File snapshot = tempDir.resolve("artworks.json").toFile();
        try (FileWriter fw = new FileWriter(snapshot)) {
            fw.write("[{\"title\":\"A\",\"year\":2020},{\"title\":\"B\",\"year\":1990}]");
        }
        return snapshot;
    }

    /** Matches {@link #catalogWithTemporalColumns()}'s column set — real DATE/TIMESTAMP/BOOLEAN
     * values for the execution-level tests below, not just plan-shape ones. */
    private static File temporalSnapshotFile() throws Exception {
        Path tempDir = Files.createTempDirectory("aran-v38-temporal-test");
        File snapshot = tempDir.resolve("temporal.json").toFile();
        try (FileWriter fw = new FileWriter(snapshot)) {
            fw.write("[" +
                    "{\"title\":\"A\",\"released\":\"2020-01-01\",\"loggedAt\":\"2020-01-01 10:30:00\",\"featured\":true}," +
                    "{\"title\":\"B\",\"released\":\"1990-06-15\",\"loggedAt\":\"1990-06-15 08:00:00\",\"featured\":false}" +
                    "]");
        }
        return snapshot;
    }

    private static RelNode lower(MappingCatalog catalog, Map<String, List<String>> enforced, String oql) {
        return new OqlPlanner(catalog, enforced).lower(OqlParser.parse(oql));
    }

    /** First {@link Filter} in {@code rel}, depth-first — where the coerced literal lives. */
    private static Filter firstFilter(RelNode rel) {
        if (rel instanceof Filter f) {
            return f;
        }
        for (RelNode input : rel.getInputs()) {
            Filter found = firstFilter(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The literal operand of the WHERE clause's single comparison — the node {@code literal()}
     * built. Asserting its {@link SqlTypeName} directly is more robust than asserting on unparsed
     * SQL text, which the optimizer is free to simplify (e.g. {@code x = TRUE} to bare {@code x}). */
    private static RexLiteral whereLiteral(MappingCatalog catalog, Map<String, List<String>> enforced, String oql) {
        Filter filter = firstFilter(lower(catalog, enforced, oql));
        assertThat(filter).as("the plan must contain the WHERE filter").isNotNull();
        RexCall call = (RexCall) filter.getCondition();
        return (RexLiteral) call.getOperands().get(1);
    }

    // ── Test 1: the original repro, which plans AND executes ────────────────────

    @Test
    @DisplayName("an enforced numeric DC plus a string-literal predicate on that column plans and executes")
    void enforcedNumericDcWithStringLiteralPlansAndExecutes() throws Exception {
        File snapshot = snapshotFile();
        MappingCatalog catalog = catalogFor(snapshot);
        DuckDbExecutor.SnapshotResolver resolver = ds -> "read_json_auto('" + snapshot.getAbsolutePath() + "')";
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.year = '2020'";

        RelNode logical = lower(catalog, YEAR_POSITIVE, oql);
        // RED today: PlanExecutor.optimize throws ClassCastException (NlsString -> BigDecimal)
        // while RexSimplify merges the WHERE literal into the enforced constraint's range.
        RelNode optimized = PlanExecutor.optimize(logical);
        String sql = PlanExecutor.toSqlFromOptimized(optimized);

        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, YEAR_POSITIVE)) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0)).containsExactly("A");
        }
    }

    // ── Test 2: unparseable literal for the field's type is a clear plan-time error ──

    @Test
    @DisplayName("a non-numeric literal against a numeric column is a clear plan-time error, not a crash")
    void unparseableNumericLiteralIsAClearPlanError() throws Exception {
        MappingCatalog catalog = catalogFor(snapshotFile());
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.year = 'abc'";

        assertThatThrownBy(() -> lower(catalog, YEAR_POSITIVE, oql))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("year")
                .hasMessageContaining("abc")
                .hasMessageContaining("BIGINT");
    }

    // ── Test 3: a plain string-column comparison is unchanged ──────────────────

    @Test
    @DisplayName("a string-column literal comparison is unchanged")
    void stringColumnComparisonUnchanged() throws Exception {
        MappingCatalog catalog = catalogFor(snapshotFile());
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.title = 'A'";

        RelNode logical = lower(catalog, YEAR_POSITIVE, oql);
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(logical));

        assertThat(sql).containsIgnoringCase("= 'A'");
    }

    // ── Test 4: the typed literal also works with NO constraint (non-DC regression) ──

    @Test
    @DisplayName("the typed literal still works with no enforced constraint at all")
    void typedLiteralWorksWithNoConstraint() throws Exception {
        File snapshot = snapshotFile();
        MappingCatalog catalog = catalogFor(snapshot);
        DuckDbExecutor.SnapshotResolver resolver = ds -> "read_json_auto('" + snapshot.getAbsolutePath() + "')";
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.year = '2020'";

        RelNode logical = lower(catalog, Map.of(), oql);
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(logical));

        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, Map.of())) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0)).containsExactly("A");
        }
    }

    // ── Coverage: the other non-VARCHAR types XsdTypes can produce ─────────────

    @Test
    @DisplayName("a DATE column literal is coerced when parseable, and a clear error otherwise")
    void dateColumnCoercion() {
        MappingCatalog catalog = catalogWithTemporalColumns();
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.released = '2020-01-01'";

        RexLiteral literal = whereLiteral(catalog, Map.of(), oql);
        assertThat(literal.getTypeName()).isEqualTo(SqlTypeName.DATE);
        assertThat(literal.toString()).contains("2020-01-01");

        String badOql = "SELECT Artworks.title FROM Artworks WHERE Artworks.released = 'not-a-date'";
        assertThatThrownBy(() -> lower(catalog, Map.of(), badOql))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("released")
                .hasMessageContaining("not-a-date")
                .hasMessageContaining("DATE");
    }

    @Test
    @DisplayName("a TIMESTAMP column literal is coerced when parseable, and a clear error otherwise")
    void timestampColumnCoercion() {
        MappingCatalog catalog = catalogWithTemporalColumns();
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.loggedAt = '2020-01-01 10:30:00'";

        RexLiteral literal = whereLiteral(catalog, Map.of(), oql);
        assertThat(literal.getTypeName()).isEqualTo(SqlTypeName.TIMESTAMP);
        assertThat(literal.toString()).contains("2020-01-01");

        String badOql = "SELECT Artworks.title FROM Artworks WHERE Artworks.loggedAt = 'not-a-timestamp'";
        assertThatThrownBy(() -> lower(catalog, Map.of(), badOql))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("loggedAt")
                .hasMessageContaining("not-a-timestamp")
                .hasMessageContaining("TIMESTAMP");
    }

    @Test
    @DisplayName("a BOOLEAN column literal is coerced when parseable, and a clear error otherwise")
    void booleanColumnCoercion() {
        MappingCatalog catalog = catalogWithTemporalColumns();
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.featured = 'true'";

        Filter filter = firstFilter(lower(catalog, Map.of(), oql));
        assertThat(filter).isNotNull();
        // A correctly-typed BOOLEAN TRUE literal lets RelBuilder fold `featured = TRUE` down to
        // the bare field reference at build time (RexInputRef, not a RexCall to `=` any more) —
        // a leftover CHAR literal could never participate in that simplification, so this shape
        // alone is proof the literal was typed correctly, not just that nothing threw.
        assertThat(filter.getCondition()).isInstanceOf(RexInputRef.class);
        assertThat(filter.getCondition().getType().getSqlTypeName()).isEqualTo(SqlTypeName.BOOLEAN);

        String badOql = "SELECT Artworks.title FROM Artworks WHERE Artworks.featured = 'maybe'";
        assertThatThrownBy(() -> lower(catalog, Map.of(), badOql))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("featured")
                .hasMessageContaining("maybe")
                .hasMessageContaining("BOOLEAN");
    }

    // ── DATE/TIMESTAMP proven to EXECUTION, not just plan time ─────────────────
    //
    // makeDateLiteral/makeTimestampLiteral are genuinely new code, and this exact engine has a
    // documented precedent (query-processing.md §7 / pushExpandedMember) where a cast's rendering
    // diverged between the Calcite optimizer and DuckDbExecutor's own DDL. The plan-only tests
    // above prove the RexLiteral Calcite builds is correctly typed; these prove that literal
    // still means the same thing once RelToSqlConverter unparses it and DuckDB actually runs it —
    // same shape as Test 1's BIGINT repro (real snapshot, real DuckDbExecutor, real row content).

    @Test
    @DisplayName("a DATE column literal comparison executes correctly against a real snapshot")
    void dateColumnComparisonExecutesCorrectly() throws Exception {
        File snapshot = temporalSnapshotFile();
        MappingCatalog catalog = catalogWithTemporalColumns();
        DuckDbExecutor.SnapshotResolver resolver = ds -> "read_json_auto('" + snapshot.getAbsolutePath() + "')";
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.released = '2020-01-01'";

        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(lower(catalog, Map.of(), oql)));

        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, Map.of())) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0)).containsExactly("A");
        }
    }

    @Test
    @DisplayName("a TIMESTAMP column literal comparison executes correctly against a real snapshot")
    void timestampColumnComparisonExecutesCorrectly() throws Exception {
        File snapshot = temporalSnapshotFile();
        MappingCatalog catalog = catalogWithTemporalColumns();
        DuckDbExecutor.SnapshotResolver resolver = ds -> "read_json_auto('" + snapshot.getAbsolutePath() + "')";
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.loggedAt = '2020-01-01 10:30:00'";

        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(lower(catalog, Map.of(), oql)));

        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, Map.of())) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0)).containsExactly("A");
        }
    }

    // ── HAVING shares literal() with WHERE, so cover it directly ───────────────
    //
    // Every pre-existing HAVING test in the module uses an unquoted numeric literal (e.g.
    // `HAVING COUNT(*) > 1`), which never touches typedTextLiteral at all. This drives a QUOTED
    // literal through the HAVING call site added alongside the WHERE fix, mirroring Test 1's
    // idiom: a real snapshot, a real DuckDbExecutor run, actual row content asserted.

    @Test
    @DisplayName("a HAVING aggregate comparison also coerces a quoted numeric literal, and executes correctly")
    void havingAggregateComparisonCoercesQuotedLiteralAndExecutes() throws Exception {
        File snapshot = snapshotFile();
        MappingCatalog catalog = catalogFor(snapshot);
        DuckDbExecutor.SnapshotResolver resolver = ds -> "read_json_auto('" + snapshot.getAbsolutePath() + "')";
        // Rows: title "A" year=2020, title "B" year=1990 — one row per group, so SUM(year) is
        // just that row's year. The quoted threshold '2000' keeps "A" and drops "B".
        String oql = "SELECT Artworks.title, SUM(Artworks.year) AS total FROM Artworks "
                + "GROUP BY Artworks.title HAVING SUM(Artworks.year) > '2000'";

        // Plan-level: the literal the shared literal() helper builds for HAVING must be a real
        // numeric literal, not the untyped CHAR the pre-fix code built via the same helper.
        Filter havingFilter = firstFilter(lower(catalog, Map.of(), oql));
        assertThat(havingFilter).as("the plan must contain the HAVING filter").isNotNull();
        RexCall call = (RexCall) havingFilter.getCondition();
        RexLiteral literal = (RexLiteral) call.getOperands().get(1);
        assertThat(literal.getTypeName()).isNotEqualTo(SqlTypeName.CHAR);
        assertThat(literal.getValueAs(java.math.BigDecimal.class))
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(2000));

        // Execution-level: the coerced literal must also filter correctly through DuckDB.
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(lower(catalog, Map.of(), oql)));
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, Map.of())) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).get(0)).isEqualTo("A");
        }
    }

    // ── S6-4: an UNQUOTED number stays a number, except against DATE/TIMESTAMP ──
    //
    // A Calcite VARCHAR is not a DuckDB VARCHAR. The u_ view projects a VARCHAR column uncast and
    // CsvBootstrap types every CSV column xsd:string, so a CSV column of numbers is VARCHAR to the
    // planner and BIGINT to DuckDB. A first cut of S6-4 typed the number as text there, DuckDB
    // cast '9.5' to BIGINT — rounding it to 10 — and the query answered wrongly with no error.

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";
    private static final String RATING = NS + "integration/rating";

    /** What CsvBootstrap writes for a CSV: every property {@code xsd:string}, so every column is
     *  VARCHAR to the planner whatever {@code read_csv_auto} sniffs it as. */
    private static MappingCatalog csvCatalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("csv", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", RATING, "rating"), 2L, List.of(), "",
                        Map.of(TITLE, XSD_STRING, RATING, XSD_STRING))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    private static DuckDbExecutor.SnapshotResolver csvSnapshot() throws Exception {
        Path csv = Files.createTempDirectory("aran-s6-4-csv").resolve("artworks.csv");
        Files.writeString(csv, "title,rating\nA,10\nB,9\n");
        return ds -> "read_csv_auto('" + csv.toAbsolutePath() + "')";
    }

    /** {@code year} is {@code xsd:integer} in one source and {@code xsd:string} in the other, so
     *  it widens to VARCHAR; the second source carries a dirty cell. */
    private static MappingCatalog widenedCatalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("clean", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", YEAR, "year"), 2L, List.of(), "",
                        Map.of(YEAR, XSD_INTEGER)),
                new MappingCatalog.SourceView("dirty", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", YEAR, "year"), 2L, List.of(), "",
                        Map.of(YEAR, XSD_STRING))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    private static DuckDbExecutor.SnapshotResolver widenedSnapshots() throws Exception {
        return widenedSnapshots("2020", "n/a");
    }

    /** The clean source holds A=2016 and B=1999; the dirty source holds C and D as JSON strings. */
    private static DuckDbExecutor.SnapshotResolver widenedSnapshots(String cYear, String dYear) throws Exception {
        Path dir = Files.createTempDirectory("aran-s6-4");
        Path clean = dir.resolve("clean.json");
        Path dirty = dir.resolve("dirty.json");
        Files.writeString(clean, "[{\"title\":\"A\",\"year\":2016},{\"title\":\"B\",\"year\":1999}]");
        Files.writeString(dirty, "[{\"title\":\"C\",\"year\":\"" + cYear + "\"},"
                + "{\"title\":\"D\",\"year\":\"" + dYear + "\"}]");
        return ds -> "read_json_auto('" + (ds.equals("clean") ? clean : dirty).toAbsolutePath() + "')";
    }

    private static List<Object> titles(MappingCatalog catalog, DuckDbExecutor.SnapshotResolver resolver,
                                       String oql) throws Exception {
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(lower(catalog, Map.of(), oql)));
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, Map.of())) {
            exec.prepare();
            return exec.run(sql).rows().stream().map(row -> row.get(0)).toList();
        }
    }

    @Test
    @DisplayName("the premise: DuckDB casts a text literal to a numeric column's type, so '9.5' against BIGINT rounds to 10")
    void duckDbRoundsATextLiteralAgainstAnIntegerColumn() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t AS SELECT * FROM (VALUES ('a', 10::BIGINT), ('b', 9::BIGINT)) v(t, n)");
            assertThat(column(st, "SELECT t FROM t WHERE n > '9.5' ORDER BY t")).isEmpty();
            assertThat(column(st, "SELECT t FROM t WHERE n = '9.5' ORDER BY t")).containsExactly("a");
            assertThat(column(st, "SELECT t FROM t WHERE n > 9.5 ORDER BY t")).containsExactly("a");
        }
    }

    @Test
    @DisplayName("the premise: DuckDB refuses VARCHAR <, <=, >, >= a number outright, integer or DOUBLE, and VARCHAR = number dies on a dirty cell")
    void duckDbRefusesUntypedNumericComparisonOnText() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("CREATE TABLE t AS SELECT * FROM (VALUES ('2016'), ('n/a')) v(y)");
            }
            // DuckDB's JDBC driver closes a statement that failed, so each probe gets its own.
            for (String predicate : List.of("y > 2015", "y > 2.015E3", "y >= 2.015E3", "y < 2.015E3", "y <= 2.015E3")) {
                try (java.sql.Statement st = c.createStatement()) {
                    assertThatThrownBy(() -> st.executeQuery("SELECT * FROM t WHERE " + predicate))
                            .as(predicate)
                            .hasMessageContaining("Cannot compare");
                }
            }
            for (String predicate : List.of("y = 2016", "y = 2.016E3")) {
                try (java.sql.Statement st = c.createStatement()) {
                    assertThatThrownBy(() -> st.executeQuery("SELECT * FROM t WHERE " + predicate))
                            .as(predicate)
                            .hasMessageContaining("n/a");
                }
            }
        }
    }

    private static List<String> column(java.sql.Statement st, String sql) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (java.sql.ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Test
    @DisplayName("an unquoted number against a numeric column stays a typed number, never text")
    void unquotedNumberAgainstNumericColumnIsNumeric() throws Exception {
        MappingCatalog catalog = catalogFor(snapshotFile());
        RexLiteral literal = whereLiteral(catalog, Map.of(),
                "SELECT Artworks.title FROM Artworks WHERE Artworks.year > 2015");
        assertThat(literal.getTypeName()).isNotEqualTo(SqlTypeName.CHAR);
        assertThat(SqlTypeName.NUMERIC_TYPES).contains(literal.getTypeName());
        assertThat(literal.getValueAs(java.math.BigDecimal.class))
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(2015));
    }

    @Test
    @DisplayName("an unquoted number against a VARCHAR column stays a number: VARCHAR to Calcite says nothing about DuckDB's type")
    void unquotedNumberAgainstVarcharColumnStaysANumber() {
        RexLiteral literal = whereLiteral(csvCatalog(), Map.of(),
                "SELECT Artworks.title FROM Artworks WHERE Artworks.rating > 9.5");
        assertThat(SqlTypeName.NUMERIC_TYPES).contains(literal.getTypeName());
        assertThat(literal.getValueAs(java.math.BigDecimal.class))
                .isEqualByComparingTo(new java.math.BigDecimal("9.5"));
    }

    @Test
    @DisplayName("a fractional number against a CSV column of integers compares numerically: > 9.5, = 9.5, <= 9.5")
    void fractionalNumberAgainstCsvIntegerColumnComparesNumerically() throws Exception {
        MappingCatalog catalog = csvCatalog();
        DuckDbExecutor.SnapshotResolver csv = csvSnapshot();
        assertThat(titles(catalog, csv, "SELECT Artworks.title FROM Artworks WHERE Artworks.rating > 9.5"))
                .containsExactly("A");
        assertThat(titles(catalog, csv, "SELECT Artworks.title FROM Artworks WHERE Artworks.rating = 9.5"))
                .isEmpty();
        assertThat(titles(catalog, csv, "SELECT Artworks.title FROM Artworks WHERE Artworks.rating <= 9.5"))
                .containsExactly("B");
    }

    @Test
    @DisplayName("residue: a number against a text column with a cell that is not a number fails loudly, never answers in text order")
    void numberAgainstTrulyTextColumnIsLoudNotWrong() throws Exception {
        // Loud beats wrong until the plan knows the physical type (D6's post-tag typing work):
        // typing the number as text here would answer, but in text order ('n/a' > '2015'), and
        // the same typing gives wrong rows on the CSV column above. Only a cell that is not a
        // number is loud; a decimal string compares at its own value (the tests below).
        assertThatThrownBy(() -> titles(widenedCatalog(), widenedSnapshots(),
                "SELECT Artworks.title FROM Artworks WHERE Artworks.year = 2016"))
                .hasMessageContaining("n/a");
    }

    // ── S6-4: a decimal string is compared at its own value, never rounded to the literal's ──
    //
    // DuckDB binds VARCHAR = <integer literal> by casting the column's text to INTEGER, which
    // rounds '2016.4' to 2016: wrong rows, and no error, because no cell is non-numeric.

    @Test
    @DisplayName("the premise: DuckDB casts a text column to an integer literal's type, so '9.5' = 10; against a DOUBLE literal it compares exactly")
    void duckDbRoundsADecimalStringAgainstAnIntegerLiteral() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("CREATE TABLE t AS SELECT * FROM (VALUES ('10'), ('9'), ('9.5'), ('010')) v(v)");
                assertThat(column(st, "SELECT v FROM t WHERE v = 10 ORDER BY v")).containsExactly("010", "10", "9.5");
                assertThat(column(st, "SELECT v FROM t WHERE v = 1.0E1 ORDER BY v")).containsExactly("010", "10");
            }
            // A decimal literal is typed DECIMAL(2,1), and '10' does not fit it.
            try (java.sql.Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT v FROM t WHERE v = 9.9"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    @Test
    @DisplayName("a whole number against a widened text column never matches a decimal string: = 2016, <> 2016")
    void wholeNumberAgainstWidenedTextColumnDoesNotRoundDecimalStrings() throws Exception {
        MappingCatalog catalog = widenedCatalog();
        DuckDbExecutor.SnapshotResolver snapshots = widenedSnapshots("2016.4", "2015.6");
        assertThat(titles(catalog, snapshots, "SELECT Artworks.title FROM Artworks WHERE Artworks.year = 2016"))
                .containsExactly("A");
        assertThat(titles(catalog, snapshots, "SELECT Artworks.title FROM Artworks WHERE Artworks.year <> 2016"))
                .containsExactlyInAnyOrder("B", "C", "D");
    }

    private static final String PRICE = NS + "integration/price";

    /** One JSON source whose prices are numbers written as strings, so the planner and
     *  {@code read_json_auto} both see text. */
    private static MappingCatalog pricedCatalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("priced", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", PRICE, "price"), 2L, List.of(), "",
                        Map.of(TITLE, XSD_STRING, PRICE, XSD_STRING))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    private static DuckDbExecutor.SnapshotResolver pricesAsStrings() throws Exception {
        Path json = Files.createTempDirectory("aran-s6-4-json").resolve("prices.json");
        Files.writeString(json, "[{\"title\":\"A\",\"price\":\"9.99\"},{\"title\":\"B\",\"price\":\"10\"}]");
        return ds -> "read_json_auto('" + json.toAbsolutePath() + "')";
    }

    @Test
    @DisplayName("a number against a JSON column of numbers stored as strings compares each at its own value: = 10, <> 10, = 9.99")
    void numberAgainstNumbersStoredAsStringsComparesExactly() throws Exception {
        MappingCatalog catalog = pricedCatalog();
        DuckDbExecutor.SnapshotResolver json = pricesAsStrings();
        assertThat(titles(catalog, json, "SELECT Artworks.title FROM Artworks WHERE Artworks.price = 10"))
                .containsExactly("B");
        assertThat(titles(catalog, json, "SELECT Artworks.title FROM Artworks WHERE Artworks.price <> 10"))
                .containsExactly("A");
        // As DECIMAL(3,2), the literal made DuckDB cast '10' to that type, and '10' overflows it.
        assertThat(titles(catalog, json, "SELECT Artworks.title FROM Artworks WHERE Artworks.price = 9.99"))
                .containsExactly("A");
    }

    @Test
    @DisplayName("a whole number past 2^53 stays exact against a CSV integer column, where a DOUBLE would round two ids together")
    void wholeNumberPastDoublePrecisionStaysExact() throws Exception {
        String id = NS + "integration/id";
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("csv", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", id, "id"), 2L, List.of(), "",
                        Map.of(TITLE, XSD_STRING, id, XSD_STRING))));
        MappingCatalog catalog = new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
        Path csv = Files.createTempDirectory("aran-s6-4-bigint").resolve("ids.csv");
        Files.writeString(csv, "title,id\nA,9007199254740993\nB,9007199254740992\n");
        DuckDbExecutor.SnapshotResolver resolver = ds -> "read_csv_auto('" + csv.toAbsolutePath() + "')";

        assertThat(titles(catalog, resolver, "SELECT Artworks.title FROM Artworks WHERE Artworks.id = 9007199254740993"))
                .containsExactly("A");
    }

    @Test
    @DisplayName("an unquoted number against a BOOLEAN column is left as DuckDB reads it: flag = 1")
    void unquotedNumberAgainstBooleanUnchanged() {
        Filter filter = firstFilter(lower(catalogWithTemporalColumns(), Map.of(),
                "SELECT Artworks.title FROM Artworks WHERE Artworks.featured = 1"));
        RexLiteral literal = (RexLiteral) ((RexCall) filter.getCondition()).getOperands().get(1);
        assertThat(SqlTypeName.NUMERIC_TYPES).contains(literal.getTypeName());
    }

    @Test
    @DisplayName("an unquoted number against a DATE column is a plan-time error naming the column")
    void unquotedNumberAgainstDateIsAPlanError() {
        assertThatThrownBy(() -> lower(catalogWithTemporalColumns(), Map.of(),
                "SELECT Artworks.title FROM Artworks WHERE Artworks.released > 2015"))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("released")
                .hasMessageContaining("2015")
                .hasMessageContaining("DATE");
    }

    @Test
    @DisplayName("an unquoted number against a TIMESTAMP column is a plan-time error naming the column")
    void unquotedNumberAgainstTimestampIsAPlanError() {
        assertThatThrownBy(() -> lower(catalogWithTemporalColumns(), Map.of(),
                "SELECT Artworks.title FROM Artworks WHERE Artworks.loggedAt = 2015"))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("loggedAt")
                .hasMessageContaining("2015")
                .hasMessageContaining("TIMESTAMP");
    }

    @Test
    @DisplayName("the premise: DuckDB refuses a number against a DATE or TIMESTAMP too, so the plan error loses no query")
    void duckDbRefusesNumberAgainstTemporal() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
            try (java.sql.Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT DATE '2020-01-01' = 2015"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (java.sql.Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT DATE '2020-01-01' > 2015"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (java.sql.Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT TIMESTAMP '2020-01-01 10:00:00' = 2015"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }
}

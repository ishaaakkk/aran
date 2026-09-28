package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code rejectIfNotReadOnly} is the only thing between an authenticated user and raw
 * DuckDB on the {@code run} operation, so the attack strings it must refuse belong in a
 * test rather than only in a commit message.
 *
 * <p>Restricting the statement to SELECT/WITH is not the interesting part: the threat is
 * reads, and reads come back to the caller in the response body. The cases below are the
 * ones that are perfectly read-only and still exfiltrate.
 */
class QueryEngineSqlGuardTest {

    private static boolean accepted(String sql) {
        return QueryEngineOperator.rejectIfNotReadOnly(sql) == null;
    }

    // ── Must be refused ──────────────────────────────────────────────────────

    @ParameterizedTest
    @DisplayName("local filesystem reads are refused")
    @ValueSource(strings = {
            "SELECT * FROM read_csv_auto('/etc/passwd')",
            "SELECT * FROM read_csv('/etc/passwd')",
            "SELECT * FROM read_text('/etc/shadow')",
            "SELECT * FROM read_blob('/etc/shadow')",
            "SELECT * FROM read_json('/etc/passwd')",
            "SELECT * FROM read_parquet('/var/lib/aran/secret.parquet')",
    })
    void refusesFileReads(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @Test
    @DisplayName("SSRF to the cloud metadata endpoint is refused")
    void refusesMetadataSsrf() {
        assertThat(accepted(
                "SELECT * FROM read_csv_auto('http://169.254.169.254/latest/meta-data/iam/security-credentials/')"))
                .isFalse();
    }

    @Test
    @DisplayName("postgres_scan against Aran's own database is refused — it reads every tenant's rows")
    void refusesCrossTenantPostgresScan() {
        assertThat(accepted(
                "SELECT * FROM postgres_scan('host=postgres user=aran password=aran dbname=aran','public','tenant')"))
                .isFalse();
    }

    @ParameterizedTest
    @DisplayName("other cross-database scanners are refused")
    @ValueSource(strings = {
            "SELECT * FROM mysql_scan('host=mysql','db','t')",
            "SELECT * FROM sqlite_scan('/tmp/x.db','t')",
            "SELECT * FROM iceberg_scan('/tmp/x')",
            "SELECT * FROM delta_scan('/tmp/x')",
            "SELECT * FROM parquet_scan('/tmp/x.parquet')",
    })
    void refusesScanners(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @ParameterizedTest
    @DisplayName("an attached live MySQL catalog cannot be read past its one enrolled table")
    @ValueSource(strings = {
            // The sources tier runs client SQL on the very connection the resolver ATTACHed the
            // repository's database to; naming the alias would reach every table its account sees.
            "SELECT * FROM aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0.mysql.user",
            "WITH t AS (SELECT * FROM \"aran_attach_0f1e\".\"shop\".\"payroll\") SELECT * FROM t",
            "SELECT * FROM int_Paper, LATERAL mysql_query('aran_attach_0f1e', 'SELECT * FROM shop.payroll')",
    })
    void refusesAttachedCatalogAccess(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @Test
    @DisplayName("a CTE cannot smuggle a reader past the prefix check")
    void refusesReaderInsideCte() {
        // `WITH` is an allowed prefix, so the statement-shape check alone lets this through.
        assertThat(accepted(
                "WITH x AS (SELECT * FROM read_parquet('http://attacker/')) SELECT * FROM x"))
                .isFalse();
    }

    @Test
    @DisplayName("a reader nested in a subquery is refused")
    void refusesReaderInSubquery() {
        assertThat(accepted(
                "SELECT * FROM int_Paper WHERE title IN (SELECT x FROM read_csv_auto('/etc/passwd'))"))
                .isFalse();
    }

    // ── Reaching the filesystem without naming a reader ──────────────────────
    //
    // Everything above names a forbidden function, so a screen that matches names catches it.
    // The cases below do not, and each one was accepted by the regex screen that preceded the
    // tokenizer — verified against DuckDB 1.3.1, and re-verified on 1.5.5: both execute them
    // and return the file's contents. The engine's replacement scan is what makes a bare
    // string literal a reader, and it has not gone away.

    @ParameterizedTest
    @DisplayName("a string literal in table position is refused — DuckDB reads it as a file")
    @ValueSource(strings = {
            "SELECT * FROM '/etc/passwd.csv'",
            "SELECT * FROM '/etc/passwd.csv' AS t",
            "SELECT * FROM '/var/aran/*.parquet'",
            "SELECT * FROM (SELECT * FROM '/etc/passwd.csv')",
            "WITH x AS (SELECT * FROM '/etc/passwd.csv') SELECT * FROM x",
            "SELECT * FROM int_Paper JOIN '/etc/passwd.csv' t ON true",
            "SELECT (SELECT col FROM '/etc/passwd.csv' LIMIT 1) AS leaked",
            "SELECT * FROM int_Paper, '/etc/passwd.csv'",
    })
    void refusesReplacementScans(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    // A double-quoted identifier is not a string literal, so the rule above never saw it — but
    // DuckDB's replacement scan does not care how the name was spelled: when no table of that
    // name exists it reads the name as a path. Dotted names are joined back with '.', which is
    // how a bare `x.csv` and a half-quoted `"dir/y".csv` reach a file too. Every case below was
    // run against DuckDB 1.5.5 and returned the file's rows before the screen refused it.

    @ParameterizedTest
    @DisplayName("a quoted path in table position is refused — DuckDB reads a quoted identifier as a file too")
    @ValueSource(strings = {
            "SELECT * FROM \"/etc/passwd.csv\"",
            "SELECT * FROM \"/var/aran/*.csv\"",
            "SELECT * FROM \"/etc/passwd.csv\" AS t",
            "SELECT * FROM (SELECT * FROM \"/etc/passwd.csv\")",
            "WITH x AS (SELECT * FROM \"/etc/passwd.csv\") SELECT * FROM x",
            "SELECT (SELECT count(*) FROM \"/etc/passwd.csv\") AS leaked",
            "SELECT * FROM int_Paper, \"/etc/passwd.csv\"",
            "SELECT * FROM int_Paper JOIN \"/etc/passwd.csv\" t ON true",
            "SELECT * FROM \"/etc/aran/y\".csv",
            "SELECT * FROM \"https://attacker.example/x.parquet\"",
            "SELECT * FROM \"s3://other-bucket/secrets.parquet\"",
            "SELECT * FROM int_Paper WHERE title IN (SELECT a FROM \"/etc/passwd.csv\")",
            "SELECT * FROM int_Paper UNION ALL SELECT a FROM \"/etc/passwd.csv\"",
    })
    void refusesQuotedPathReplacementScans(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @ParameterizedTest
    @DisplayName("a dotted name whose qualifier is not a catalog or schema is refused — DuckDB reads it as a relative path")
    @ValueSource(strings = {
            "SELECT * FROM data.csv",
            "SELECT * FROM x . csv",
            "SELECT * FROM x/**/.csv",
            "SELECT * FROM \"x\".\"csv\"",
            "SELECT * FROM dir.y.csv",
            "SELECT * FROM x.csv.gz",
            "SELECT * FROM x.csv AS t",
            "SELECT * FROM int_Paper, data.parquet",
            // a known qualifier does not make an extension-named table safe: DuckDB tries
            // "main.csv" as a path once the catalog lookup misses
            "SELECT * FROM main.csv",
            "SELECT * FROM products.json",
            "SELECT * FROM aran.products.x.csv",
    })
    void refusesRelativePathReplacementScans(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @ParameterizedTest
    @DisplayName("every place DuckDB accepts a table reference is screened, not only FROM and JOIN")
    @ValueSource(strings = {
            // the comma after a JOIN's condition continues the FROM list
            "SELECT * FROM int_Paper a JOIN int_Author b ON a.id = b.id, '/etc/passwd.csv'",
            "SELECT * FROM int_Paper a JOIN int_Author b USING (id), \"/etc/passwd.csv\"",
            // a subquery's own FROM must not end the outer FROM list
            "SELECT * FROM (SELECT * FROM int_Paper) t, '/etc/passwd.csv'",
            "SELECT * FROM (SELECT * FROM int_Paper) t(a), \"/etc/passwd.csv\"",
            // a parenthesised join's first operand has no FROM or JOIN in front of it
            "SELECT * FROM (\"/etc/passwd.csv\" JOIN int_Paper ON true)",
            "SELECT * FROM ('/etc/passwd.csv' t JOIN int_Paper ON true)",
            // DuckDB's statement forms that take a table reference, all valid as subqueries
            "SELECT * FROM (TABLE \"/etc/passwd.csv\")",
            "SELECT * FROM (FROM \"/etc/passwd.csv\")",
            "SELECT * FROM (SUMMARIZE \"/etc/passwd.csv\")",
            "SELECT * FROM (DESCRIBE \"/etc/passwd.csv\")",
            "SELECT * FROM (DESC \"/etc/passwd.csv\")",
            "SELECT * FROM (SHOW \"/etc/passwd.csv\")",
            "SELECT * FROM (PIVOT \"/etc/passwd.csv\" ON a USING sum(b))",
            "SELECT * FROM (PIVOT (\"/etc/passwd.csv\" JOIN int_Paper USING (a)) ON b)",
            "SELECT * FROM (UNPIVOT \"/etc/passwd.csv\" ON a INTO NAME n VALUE v)",
            "SELECT * FROM int_Paper WHERE EXISTS (TABLE \"/etc/passwd.csv\")",
            "WITH c AS (TABLE \"/etc/passwd.csv\") SELECT * FROM c",
            "SELECT * FROM int_Paper UNION ALL TABLE \"/etc/passwd.csv\"",
            // LATERAL is a modifier, not the table source it modifies
            "SELECT * FROM int_Paper CROSS JOIN LATERAL some_future_reader('/etc/passwd')",
            // WITH inside a join condition (a cast's WITH TIME ZONE) does not end the FROM list
            "SELECT * FROM int_Paper a JOIN int_Author b"
                    + " ON a.ts::TIMESTAMP WITH TIME ZONE IS NOT NULL, \"/etc/passwd.csv\"",
            // a bare VALUES list is a table source too, and a FROM list continues after its alias
            "SELECT * FROM VALUES (1) AS v(a), '/etc/passwd.csv'",
            "SELECT * FROM VALUES (1) v, \"/etc/passwd.csv\"",
            "SELECT * FROM int_Paper, VALUES (1) AS v(q), \"/etc/passwd.csv\"",
            "SELECT * FROM VALUES (1) AS v(a) JOIN \"/etc/passwd.csv\" ON true",
            // a PIVOT's own list ends with its bracket or its clause, not the FROM list around it
            "SELECT * FROM (UNPIVOT int_Paper ON a, b INTO NAME n VALUE v), \"/etc/passwd.csv\"",
            "SELECT * FROM int_Paper PIVOT (sum(citations) FOR venue IN ('a')) p, \"/etc/passwd.csv\"",
    })
    void refusesEveryTableReferencePosition(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @ParameterizedTest
    @DisplayName("DuckDB's other literal syntaxes are refused in table position too")
    @ValueSource(strings = {
            "SELECT * FROM $$/etc/passwd.csv$$",
            "SELECT * FROM $tag$/etc/passwd.csv$tag$",
            "SELECT * FROM E'/etc/passwd.csv'",
    })
    void refusesAlternativeLiteralSyntaxes(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @ParameterizedTest
    @DisplayName("quoting or commenting a reader's name does not hide the call")
    @ValueSource(strings = {
            "SELECT * FROM \"read_csv_auto\"('/etc/passwd')",
            "SELECT * FROM \"read_csv_auto\" ('/etc/passwd')",
            "SELECT * FROM \"main\".\"read_csv_auto\"('/etc/passwd')",
            "SELECT * FROM main.read_csv_auto('/etc/passwd')",
            "SELECT * FROM read_csv_auto/**/('/etc/passwd')",
            "SELECT * FROM read_csv_auto --c\n('/etc/passwd')",
            "SELECT * FROM \"postgres_scan\"('host=db','public','tenant')",
    })
    void refusesObfuscatedCallSyntax(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @Test
    @DisplayName("the introspection catalogue is refused — duckdb_secrets() returns credentials")
    void refusesIntrospectionCatalogue() {
        assertThat(accepted("SELECT * FROM duckdb_secrets()")).isFalse();
        assertThat(accepted("SELECT * FROM duckdb_settings()")).isFalse();
        assertThat(accepted("SELECT * FROM duckdb_extensions()")).isFalse();
    }

    /**
     * DuckDB exposes every view's full SQL through its catalogue — {@code duckdb_views()},
     * {@code information_schema.views}, {@code sqlite_master}, {@code pg_catalog.pg_views} — and
     * a {@code src_} view's body is the resolver's read expression. The views need no call
     * syntax: {@code duckdb_views}, {@code sqlite_master} and {@code pg_views} bind bare, which is
     * why refusing {@code duckdb_*(} as a call was not enough. JDBC metadata comes from the
     * registry, so no client path needs any of these.
     */
    @ParameterizedTest
    @DisplayName("the catalogue is refused in every spelling — it returns each view's SQL, a live source's DSN included")
    @ValueSource(strings = {
            "SELECT * FROM duckdb_views",
            "SELECT sql FROM duckdb_views()",
            "SELECT sql FROM main.duckdb_views",
            "SELECT sql FROM \"duckdb_views\"",
            "SELECT sql FROM system.main.duckdb_views",
            "SELECT * FROM duckdb_tables",
            "SELECT * FROM int_Paper WHERE title IN (SELECT sql FROM duckdb_views)",
            "SELECT view_definition FROM information_schema.views",
            "SELECT view_definition FROM \"information_schema\".\"views\"",
            "SELECT sql FROM sqlite_master",
            "SELECT sql FROM main.sqlite_master",
            "SELECT sql FROM sqlite_schema",
            "SELECT sql FROM sqlite_temp_master",
            "SELECT definition FROM pg_catalog.pg_views",
            "SELECT definition FROM pg_views",
            "SELECT * FROM pg_class",
            "SELECT * FROM pragma_database_list",
            "SELECT * FROM int_Paper, pragma_table_info('int_Paper')",
    })
    void refusesCatalogueReads(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    /** The anchor for {@link #refusesCatalogueReads}: the leak is real, not hypothetical. */
    @Test
    @DisplayName("DuckDB really does hand a view's DSN back through its catalogue, bare or qualified")
    void catalogueReturnsViewBodiesForReal() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE VIEW src_x AS SELECT 'dbname=d password=HUNTER2' AS dsn");
            for (String sql : java.util.List.of(
                    "SELECT sql FROM duckdb_views WHERE view_name = 'src_x'",
                    "SELECT view_definition FROM information_schema.views WHERE table_name = 'src_x'",
                    "SELECT sql FROM sqlite_master WHERE name = 'src_x'",
                    "SELECT definition FROM pg_views WHERE viewname = 'src_x'")) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    assertThat(rs.next()).as(sql).isTrue();
                    assertThat(rs.getString(1)).as(sql).contains("password=HUNTER2");
                }
                assertThat(accepted(sql)).as(sql).isFalse();
            }
        }
    }

    @Test
    @DisplayName("a product or column that merely contains a catalogue word still passes")
    void allowsNamesThatOnlyResembleTheCatalogue() {
        assertThat(accepted("SELECT * FROM products.pg_stats_2024")).isTrue();
        assertThat(accepted("SELECT \"pg_views\" FROM int_Paper")).isTrue();
        assertThat(accepted("SELECT views FROM int_Paper")).isTrue();
    }

    @Test
    @DisplayName("case and whitespace do not evade the screen")
    void refusesObfuscatedCasing() {
        assertThat(accepted("SELECT * FROM ReAd_CsV_AuTo('/etc/passwd')")).isFalse();
        assertThat(accepted("SELECT * FROM read_csv_auto   ('/etc/passwd')")).isFalse();
        assertThat(accepted("SELECT * FROM READ_CSV_AUTO('/etc/passwd')")).isFalse();
    }

    @ParameterizedTest
    @DisplayName("non-read-only statement shapes are refused")
    @ValueSource(strings = {
            "DROP TABLE int_Paper",
            "INSERT INTO int_Paper VALUES (1)",
            "UPDATE int_Paper SET title = 'x'",
            "DELETE FROM int_Paper",
            "ATTACH '/tmp/x.db' AS x",
            "COPY int_Paper TO '/tmp/out.csv'",
            "PRAGMA database_list",
    })
    void refusesNonReadOnlyShapes(String sql) {
        assertThat(accepted(sql)).as(sql).isFalse();
    }

    @Test
    @DisplayName("statement chaining is refused")
    void refusesChaining() {
        assertThat(accepted("SELECT 1; DROP TABLE int_Paper")).isFalse();
        assertThat(accepted("SELECT 1;SELECT 2")).isFalse();
    }

    @Test
    @DisplayName("empty input is refused rather than passed to DuckDB")
    void refusesEmpty() {
        assertThat(accepted("")).isFalse();
        assertThat(accepted("   ")).isFalse();
    }

    // ── Must still be allowed ────────────────────────────────────────────────

    @ParameterizedTest
    @DisplayName("ordinary analytical queries over the integrated views still run")
    @ValueSource(strings = {
            "SELECT * FROM int_Paper",
            "SELECT title, year FROM int_Paper WHERE year > 2020",
            "SELECT COUNT(*) FROM int_Paper",
            "SELECT UPPER(title) FROM int_Paper ORDER BY title LIMIT 10",
            "SELECT a.title FROM int_Paper a JOIN int_Author b ON a.aid = b.id",
            "WITH recent AS (SELECT * FROM int_Paper WHERE year > 2020) SELECT COUNT(*) FROM recent",
            "SELECT SUM(citations) FROM int_Paper GROUP BY venue HAVING SUM(citations) > 5",
    })
    void allowsOrdinaryQueries(String sql) {
        assertThat(accepted(sql)).as(sql).isTrue();
    }

    @ParameterizedTest
    @DisplayName("string literals outside table position are ordinary data and still allowed")
    @ValueSource(strings = {
            // The replacement-scan screen keys on position, not on the literal looking like a
            // path — otherwise every query filtering on a filename would break.
            "SELECT * FROM int_Paper WHERE title = '/etc/passwd.csv'",
            "SELECT 'hello' AS greeting FROM int_Paper",
            "SELECT * FROM int_Paper WHERE title IN ('a.csv', 'b.csv')",
            "SELECT * FROM int_Paper a JOIN int_Author b ON a.src = 'x'",
            "SELECT * FROM int_Paper ORDER BY title LIMIT 5 OFFSET '2'",
            // Literals nested inside a call in FROM position are arguments, not table
            // references — they sit deeper than the FROM that introduced them.
            "SELECT * FROM unnest(['a', 'b'])",
            "SELECT * FROM (VALUES ('a'), ('b'))",
            // A forbidden name inside a string literal is data. The regex screen rejected this;
            // the tokenizer knows the difference, which removes a false positive rather than
            // widening the screen.
            "SELECT * FROM int_Paper WHERE title = 'read_csv_auto('",
    })
    void allowsLiteralsOutsideTablePosition(String sql) {
        assertThat(accepted(sql)).as(sql).isTrue();
    }

    @ParameterizedTest
    @DisplayName("every name the engine creates still passes, quoted or qualified")
    @ValueSource(strings = {
            // Names.sqlName, ProductCatalog and SourceCatalog all normalise to [A-Za-z0-9_]
            "SELECT * FROM int_Paper",
            "SELECT * FROM \"int_Paper\"",
            "SELECT * FROM products.sales",
            "SELECT * FROM products._products",
            "SELECT * FROM aran.products.\"sales\"",
            "SELECT * FROM \"aran\".\"products\".\"sales\"",
            "SELECT * FROM sources.researchers",
            "SELECT * FROM aran.sources.researchers",
            "SELECT * FROM main.int_Paper",
            // a table's alias and column list are not the table source
            "SELECT * FROM int_Paper AS \"my alias\"",
            "SELECT t.\"http://example.org/ns#title\" FROM int_Paper t",
            "SELECT * FROM int_Paper t(a, b), int_Author",
            "SELECT * FROM (SELECT 1 AS a) t(q)",
            // literals and list/struct values inside a join condition are data
            "SELECT * FROM int_Paper a JOIN int_Author b ON a.tags = ['x', 'y']",
            "SELECT * FROM int_Paper a JOIN int_Author b ON {'k': 'x', 'j': 'y'}.k = b.id",
            "SELECT * FROM int_Paper a JOIN int_Author b USING (id) WHERE a.title = 'x'",
            // benign row sources
            "SELECT * FROM unnest([1, 2]) AS u(v)",
            "SELECT * FROM generate_series(1, 3) AS g(n)",
            "SELECT * FROM range(3)",
            "SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS v(n, s)",
            "SELECT * FROM VALUES (1, 'a'), (2, 'b') AS v(n, s)",
            "SELECT * FROM int_Paper WHERE year IN (VALUES (2020), (2021))",
            "WITH recent AS (SELECT * FROM int_Paper) SELECT * FROM recent, int_Author",
            "SELECT * FROM int_Paper a JOIN int_Author b"
                    + " ON CAST(a.ts AS TIMESTAMP WITH TIME ZONE) = b.ts, int_Venue",
            "SELECT * FROM int_Paper, LATERAL (SELECT 1 AS one)",
            "SELECT * FROM int_Paper CROSS JOIN LATERAL unnest([1, 2]) AS u(v)",
            // the SQL-standard PIVOT/UNPIVOT clause over a real table
            "SELECT * FROM int_Paper PIVOT (sum(citations) FOR venue IN ('a', 'b'))",
            "SELECT * FROM int_Paper UNPIVOT (v FOR n IN (a, b))",
            // DuckDB's PIVOT statements: commas in their ON/USING list columns and aggregates
            "SELECT * FROM (PIVOT int_Paper ON venue USING sum(citations), max(citations))",
            "SELECT * FROM (PIVOT (SELECT * FROM int_Paper) ON venue USING sum(citations), max(year))",
            "SELECT * FROM (UNPIVOT int_Paper ON \"a b\", \"c d\" INTO NAME n VALUE v)",
            "SELECT * FROM int_Paper ORDER BY title DESC",
    })
    void allowsEngineNamedTables(String sql) {
        assertThat(accepted(sql)).as(sql).isTrue();
    }

    @Test
    @DisplayName("a single trailing semicolon is tolerated")
    void allowsTrailingSemicolon() {
        assertThat(accepted("SELECT * FROM int_Paper;")).isTrue();
    }

    @Test
    @DisplayName("leading whitespace is tolerated")
    void allowsLeadingWhitespace() {
        assertThat(accepted("   SELECT * FROM int_Paper")).isTrue();
    }

    // ── The primitive, against a real DuckDB ─────────────────────────────────

    /**
     * Not a guard test. It demonstrates that a bare string literal in {@code FROM} position
     * really does read a file, so the {@link #refusesReplacementScans} cases above are anchored
     * to something that happens rather than to a hypothesis — the same reason
     * {@code ConstraintInjectionTest} keeps its exploit test.
     *
     * <p>If a future DuckDB drops replacement scans this test fails, and that failure is the
     * signal to relax the screen — not the other way round.
     */
    @Test
    @DisplayName("DuckDB really does read a file from a bare string literal in FROM position")
    void replacementScanReadsAFileForReal() throws Exception {
        Path secret = Files.createTempFile("aran-guard-", ".csv");
        try {
            Files.writeString(secret, "col\nTOP_SECRET_VALUE\n");
            String sql = "SELECT * FROM '" + secret.toAbsolutePath() + "'";

            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("TOP_SECRET_VALUE");
            }

            // …and that is exactly why the screen must refuse it.
            assertThat(accepted(sql)).isFalse();
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    /**
     * The quoted-identifier twin of {@link #replacementScanReadsAFileForReal}: a double-quoted
     * path in {@code FROM} and a glob both read the file, and the lockdown does not stop it for a
     * file inside an allowlisted directory — which is why the screen has to.
     */
    @Test
    @DisplayName("DuckDB really does read a file from a quoted identifier in FROM position, even locked down")
    void quotedIdentifierReadsAFileForReal() throws Exception {
        Path dir = Files.createTempDirectory("aran-guard-quoted-");
        Path secret = dir.resolve("other-tenant.csv");
        try {
            Files.writeString(secret, "col\nTOP_SECRET_VALUE\n");
            String single = "SELECT * FROM \"" + secret.toAbsolutePath() + "\"";
            String glob = "SELECT * FROM \"" + dir.toAbsolutePath() + "/*.csv\"";

            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                 Statement stmt = conn.createStatement()) {
                DuckDbExecutor.lockDownFilesystem(stmt, java.util.List.of(dir));
                for (String sql : java.util.List.of(single, glob)) {
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        assertThat(rs.next()).as(sql).isTrue();
                        assertThat(rs.getString(1)).as(sql).isEqualTo("TOP_SECRET_VALUE");
                    }
                }
            }

            assertThat(accepted(single)).isFalse();
            assertThat(accepted(glob)).isFalse();
        } finally {
            Files.deleteIfExists(secret);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @DisplayName("a column whose name merely starts with 'read' is not mistaken for a reader")
    void doesNotFalsePositiveOnReadPrefixedColumns() {
        // The screen matches identifiers used as calls (name followed by '('), so a bare
        // column reference must not trip it.
        assertThat(accepted("SELECT read_count FROM int_Paper")).isTrue();
        assertThat(accepted("SELECT readership FROM int_Paper WHERE read_count > 0")).isTrue();
    }
}

package edu.upc.dtak.aran.core.domain;

import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wrapper SQL a user stores is concatenated verbatim into a {@code CREATE VIEW}
 * and executed by DuckDB, so this validator is a security boundary rather than a
 * convenience check. These tests pin that boundary.
 */
class WrapperSqlValidatorTest {

    // ── Legitimate wrappers must keep working ────────────────────────────────

    @Test
    @DisplayName("the default wrapper the product itself generates is accepted")
    void acceptsTheDefaultWrapper() {
        assertThatCode(() -> WrapperSqlValidator.validate("SELECT * FROM ${SNAPSHOT}"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("ordinary projections, casts and predicates are accepted")
    @ValueSource(strings = {
            "SELECT name, h_index FROM ${SNAPSHOT}",
            "SELECT CAST(h_index AS INTEGER) AS h FROM ${SNAPSHOT}",
            "SELECT UPPER(name) AS name FROM ${SNAPSHOT} WHERE h_index > 0",
            "SELECT COALESCE(name, 'unknown') AS name FROM ${SNAPSHOT}",
            "SELECT SUBSTRING(name, 1, 10) AS short_name FROM ${SNAPSHOT}",
    })
    void acceptsOrdinaryProjections(String sql) {
        assertThatCode(() -> WrapperSqlValidator.validate(sql)).doesNotThrowAnyException();
    }

    // ── The attack this validator exists to stop ─────────────────────────────

    @ParameterizedTest
    @DisplayName("DuckDB filesystem and network readers are rejected")
    @ValueSource(strings = {
            "SELECT * FROM read_csv_auto('/etc/passwd')",
            "SELECT * FROM read_csv('/etc/passwd')",
            "SELECT * FROM read_json('/etc/passwd')",
            "SELECT * FROM read_json_auto('/etc/passwd')",
            "SELECT * FROM read_parquet('/etc/shadow')",
            "SELECT * FROM read_text('/etc/passwd')",
            "SELECT * FROM read_blob('/etc/passwd')",
            "SELECT * FROM read_ndjson('/etc/passwd')",
    })
    void rejectsFileReaders(String sql) {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SSRF to the cloud metadata endpoint is rejected")
    void rejectsCloudMetadataSsrf() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(
                "SELECT * FROM read_csv_auto('http://169.254.169.254/latest/meta-data/iam/security-credentials/')"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @DisplayName("cross-database scanners are rejected — postgres_scan reads every tenant's rows")
    @ValueSource(strings = {
            "SELECT * FROM postgres_scan('host=postgres user=aran password=aran dbname=aran','public','tenant')",
            "SELECT * FROM mysql_scan('host=mysql','db','t')",
            "SELECT * FROM sqlite_scan('/tmp/x.db','t')",
            "SELECT * FROM iceberg_scan('/tmp/x')",
            "SELECT * FROM delta_scan('/tmp/x')",
            "SELECT * FROM parquet_scan('/tmp/x.parquet')",
    })
    void rejectsCrossDatabaseScanners(String sql) {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a forbidden reader hidden inside a nested subquery is still caught")
    void rejectsReaderNestedInSubquery() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(
                "SELECT * FROM ${SNAPSHOT} WHERE name IN (SELECT x FROM read_csv_auto('/etc/passwd'))"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a forbidden reader in the projection list is still caught")
    void rejectsReaderInProjection() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(
                "SELECT (SELECT x FROM read_text('/etc/passwd')) AS leaked FROM ${SNAPSHOT}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Reader indirection through query()/query_table() (sec-audit F-QUERY-5 / F-MALSRC-10) ──
    //
    // query('…') and query_table('…') are DuckDB table functions that execute a SQL string or scan
    // a table named by a string. They are pure indirection: the actual reader (read_text, …) lives
    // INSIDE the string argument, so neither the token scan (it sees no read_text token) nor the
    // AST walk (Calcite parses the argument as a string literal, not a call) catches it, and a
    // top-level FROM ${SNAPSHOT} keeps the FROM check happy while the read hides in a scalar
    // subquery. Blocking the indirection functions themselves is what closes the hole.

    @ParameterizedTest
    @DisplayName("query()/query_table()/json_execute_serialized_sql() reader indirection is rejected")
    @ValueSource(strings = {
            "SELECT (SELECT content FROM query('SELECT content FROM read_text(''/etc/passwd'')')) AS leak FROM ${SNAPSHOT}",
            "SELECT * FROM query('SELECT * FROM read_text(''/etc/passwd'')')",
            "SELECT * FROM query_table('some_other_table')",
            "SELECT (SELECT name FROM query('SELECT * FROM duckdb_secrets()')) AS s FROM ${SNAPSHOT}",
            // json_execute_serialized_sql runs the SQL encoded in a JSON string — a third indirection
            // reader, found by the sec-hardening review, reproduced against DuckDB 1.5.5.
            "SELECT (SELECT content FROM json_execute_serialized_sql('{}')) AS leak FROM ${SNAPSHOT}",
            // parquet metadata table functions probe arbitrary server paths.
            "SELECT (SELECT count(*) FROM parquet_file_metadata('/tmp/x.parquet')) c FROM ${SNAPSHOT}",
    })
    void rejectsQueryIndirection(String sql) {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ANY function call in table position is refused, even one not on the name list (structural backstop)")
    void rejectsUnknownTableFunctionByStructure() {
        // The name blocklist is a losing race against DuckDB's function surface, so the screen also
        // refuses a table-valued function in FROM position structurally. A future/unknown reader
        // used as a table source is caught without being enumerated.
        assertThatThrownBy(() -> WrapperSqlValidator.validate(
                "SELECT (SELECT x FROM some_future_reader('/etc/passwd')) AS leak FROM ${SNAPSHOT}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(WrapperSqlValidator.findForbiddenConstruct(
                "SELECT * FROM totally_unknown_tvf('x')")).isNotNull();
    }

    @Test
    @DisplayName("benign table functions (unnest, generate_series) still pass the structural screen")
    void allowsBenignTableFunctions() {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(
                "SELECT * FROM unnest(['a', 'b'])")).isNull();
        assertThat(WrapperSqlValidator.findForbiddenConstruct(
                "SELECT * FROM ${SNAPSHOT} WHERE n IN (SELECT g FROM generate_series(1, 10) AS t(g))"))
                .isNull();
    }

    @Test
    @DisplayName("the query() indirection call is caught by the token screen directly")
    void screenRejectsQueryIndirection() {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(
                "SELECT * FROM query('SELECT * FROM read_text(''/etc/passwd'')')"))
                .isNotNull()
                .contains("function call");
    }

    // ── Structural constraints ───────────────────────────────────────────────

    @Test
    @DisplayName("joining away from the snapshot is rejected")
    void rejectsJoin() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(
                "SELECT * FROM ${SNAPSHOT} JOIN other_table ON 1 = 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("join");
    }

    @Test
    @DisplayName("a FROM that does not reference the snapshot is rejected")
    void rejectsForeignFromClause() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate("SELECT * FROM some_other_table"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FROM clause");
    }

    // ── The comma-lateral shape (S4-3) ───────────────────────────────────────
    //
    // Every UNNEST wrapper WrapperSqlBuilder emits for a nested-JSON class is a comma lateral,
    // and Calcite gives a comma-separated FROM the same node type as an explicit JOIN. This
    // section pins the parser fact the fix rests on, then the behaviour it buys.

    @Test
    @DisplayName("PARSE FACT: Calcite represents a comma lateral as a SqlJoin with JoinType.COMMA")
    void commaLateralParsesAsCommaJoin() throws Exception {
        // Not a test of this class — a test of the assumption the fix below is built on. Without
        // it, "unwrap the COMMA join" is a guess about Calcite's grammar rather than a fact.
        SqlNode node = SqlParser.create(
                "SELECT _r1 FROM dummy_snapshot_table s, UNNEST(s.\"tags\") AS u1(_r1)").parseQuery();
        SqlNode from = ((SqlSelect) node).getFrom();

        assertThat(from).isInstanceOf(SqlJoin.class);
        SqlJoin join = (SqlJoin) from;
        assertThat(join.getJoinType()).isEqualTo(JoinType.COMMA);
        assertThat(join.getCondition()).isNull();
        assertThat(join.getLeft().toString().toUpperCase(java.util.Locale.ROOT))
                .contains("DUMMY_SNAPSHOT_TABLE");
        // The right operand is `UNNEST(...) AS u1(_r1)` — an AS call wrapping the UNNEST call,
        // which is why the allowance has to look through AS to find the function name.
        assertThat(join.getRight().toString().toUpperCase(java.util.Locale.ROOT)).contains("UNNEST");
    }

    @ParameterizedTest
    @DisplayName("every shape WrapperSqlBuilder emits for nested JSON is accepted")
    @ValueSource(strings = {
            // single unnest — "$.artworks[*]"
            "SELECT _r1.\"title\" AS \"t\" FROM ${SNAPSHOT} s, UNNEST(s.\"artworks\") AS u1(_r1)",
            // chained unnest — "$.artworks[*].creators[*]"
            "SELECT _r2.\"name\" AS \"n\" FROM ${SNAPSHOT} s, "
                    + "UNNEST(s.\"artworks\") AS u1(_r1), UNNEST(_r1.\"creators\") AS u2(_r2)",
            // array-of-primitives property unnested off the row expression
            "SELECT _r1 AS \"tag\" FROM ${SNAPSHOT} s, UNNEST(s.\"tags\") AS u1(_r1)",
            // the other benign table functions the structural screen already allows
            "SELECT g FROM ${SNAPSHOT} s, generate_series(1, 10) AS t(g)",
    })
    void acceptsBenignCommaLaterals(String sql) {
        assertThatCode(() -> WrapperSqlValidator.validate(sql)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("a comma lateral is not a hole: a second table, a real join and a non-benign "
            + "table function are all still refused")
    @ValueSource(strings = {
            // a second snapshot reference is still a second table
            "SELECT * FROM ${SNAPSHOT} s, ${SNAPSHOT} t",
            "SELECT * FROM ${SNAPSHOT} s, other_table t",
            "SELECT * FROM ${SNAPSHOT} s CROSS JOIN other_table t",
            "SELECT * FROM ${SNAPSHOT} s, UNNEST(s.\"tags\") AS u1(_r1), other_table t",
            // a reader comma-joined in place of the lateral
            "SELECT * FROM ${SNAPSHOT} s, read_csv('/etc/passwd')",
            "SELECT * FROM ${SNAPSHOT} s, http_get('http://169.254.169.254/')",
            // the snapshot on the right of a lateral is not "the snapshot alone" either
            "SELECT * FROM UNNEST([1, 2]) AS u1(_r1), ${SNAPSHOT} s",
    })
    void rejectsNonBenignCommaLaterals(String sql) {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a non-SELECT statement is rejected")
    void rejectsNonSelect() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate("INSERT INTO ${SNAPSHOT} VALUES (1)"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty and blank wrappers are rejected")
    void rejectsBlank() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WrapperSqlValidator.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("unparseable SQL is rejected rather than passed through")
    void rejectsGarbage() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate("SELECT FROM WHERE ("))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("forbidden-name matching is case-insensitive")
    void rejectionIsCaseInsensitive() {
        assertThatThrownBy(() -> WrapperSqlValidator.validate("SELECT * FROM ReAd_CsV_AuTo('/etc/passwd')"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── The token screen ─────────────────────────────────────────────────────
    //
    // findForbiddenConstruct is shared with the query engine's `run` and constraint paths, so
    // it is exercised here directly rather than only through validate(). Each case below was
    // accepted by the regex that preceded the tokenizer, and DuckDB executed it — on 1.3.1
    // when this was written, and still on the pinned 1.5.5.

    @ParameterizedTest
    @DisplayName("a string literal in table position is a file read, whatever it is quoted with")
    @ValueSource(strings = {
            "SELECT * FROM '/etc/passwd.csv'",
            "SELECT * FROM $$/etc/passwd.csv$$",
            "SELECT * FROM $tag$/etc/passwd.csv$tag$",
            "SELECT * FROM E'/etc/passwd.csv'",
            "SELECT * FROM (SELECT * FROM '/etc/passwd.csv')",
    })
    void screenRejectsReplacementScans(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql))
                .as(sql)
                .isNotNull()
                .contains("table reference");
    }

    @ParameterizedTest
    @DisplayName("a quoted or dotted path in table position is a file read too — DuckDB joins the parts back into a path")
    @ValueSource(strings = {
            "SELECT * FROM \"/etc/passwd.csv\"",
            "SELECT * FROM \"/var/aran/*.csv\"",
            "SELECT * FROM \"/etc/passwd.csv\" AS t",
            "SELECT * FROM (SELECT * FROM \"/etc/passwd.csv\")",
            "WITH c AS (SELECT * FROM \"/etc/passwd.csv\") SELECT * FROM c",
            "SELECT (SELECT count(*) FROM \"/etc/passwd.csv\") AS n",
            "SELECT * FROM ${SNAPSHOT} s, \"/etc/passwd.csv\"",
            "SELECT * FROM ${SNAPSHOT} s JOIN \"/etc/passwd.csv\" t ON true",
            "SELECT * FROM \"/etc/aran/y\".csv",
            "SELECT * FROM data.csv",
            "SELECT * FROM \"https://attacker.example/x.parquet\"",
    })
    void screenRejectsQuotedAndDottedPaths(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).as(sql).isNotNull();
    }

    @Test
    @DisplayName("a wrapper cannot reach a file through a quoted path in a subquery")
    void rejectsQuotedPathInWrapperSubquery() {
        // Calcite parses this happily (a quoted identifier is a table name to it) and the FROM is
        // the snapshot, so only the token screen stands between it and the file.
        assertThatThrownBy(() -> WrapperSqlValidator.validate(
                "SELECT * FROM ${SNAPSHOT} WHERE name IN (SELECT a FROM \"/etc/passwd.csv\")"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table reference");
    }

    @ParameterizedTest
    @DisplayName("names the engine creates, and data-only row sources, still pass the table-reference screen")
    @ValueSource(strings = {
            "SELECT * FROM int_X",
            "SELECT * FROM \"int_X\"",
            "SELECT * FROM aran.products.\"p\"",
            "SELECT * FROM products.p",
            "SELECT * FROM sources.src_table",
            "SELECT * FROM unnest(['a', 'b'])",
            "SELECT * FROM generate_series(1, 3)",
            "SELECT * FROM (VALUES (1), (2))",
            "SELECT * FROM ${SNAPSHOT} s, UNNEST(s.\"tags\") AS u1(_r1)",
            "SELECT \"http://example.org/ns#name\" FROM ${SNAPSHOT}",
    })
    void screenAllowsEngineNames(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).as(sql).isNull();
    }

    @ParameterizedTest
    @DisplayName("the engine's catalogue cannot be read, bare, qualified or called — it returns each view's SQL")
    @ValueSource(strings = {
            "SELECT * FROM duckdb_views",
            "SELECT * FROM main.duckdb_views",
            "SELECT (SELECT sql FROM duckdb_views LIMIT 1) AS s FROM ${SNAPSHOT}",
            "SELECT * FROM sqlite_master",
            "SELECT * FROM information_schema.views",
            "SELECT * FROM pg_catalog.pg_views",
            "SELECT * FROM pg_views",
            "SELECT * FROM pragma_database_list",
    })
    void screenRejectsCatalogueReads(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).as(sql).isNotNull();
    }

    @ParameterizedTest
    @DisplayName("a reader's name is the same call however it is spelled")
    @ValueSource(strings = {
            "SELECT * FROM \"read_csv_auto\"('/etc/passwd')",
            "SELECT * FROM \"main\".\"read_csv_auto\"('/etc/passwd')",
            "SELECT * FROM read_csv_auto/**/('/etc/passwd')",
            "SELECT * FROM read_csv_auto --x\n('/etc/passwd')",
            "SELECT * FROM \"duckdb_secrets\"()",
    })
    void screenRejectsObfuscatedCalls(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql))
                .as(sql)
                .isNotNull()
                .contains("function call");
    }

    @ParameterizedTest
    @DisplayName("literals that are data, not table references, pass the screen")
    @ValueSource(strings = {
            "SELECT * FROM ${SNAPSHOT} WHERE path = '/etc/passwd.csv'",
            "SELECT * FROM ${SNAPSHOT} WHERE name IN ('a.csv', 'b.csv')",
            "SELECT 'literal' AS c FROM ${SNAPSHOT}",
            "SELECT * FROM ${SNAPSHOT} a JOIN ${SNAPSHOT} b ON a.k = 'x'",
            "SELECT * FROM unnest(['a', 'b'])",
            "SELECT * FROM (VALUES ('a'), ('b'))",
            // A comment mentioning a reader is a comment, and a literal containing one is data.
            "SELECT * FROM ${SNAPSHOT} -- read_csv_auto('/etc/passwd')",
            "SELECT * FROM ${SNAPSHOT} WHERE c = 'read_csv_auto('",
    })
    void screenAllowsOrdinarySql(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).as(sql).isNull();
    }

    @Test
    @DisplayName("an unterminated literal is consumed to the end rather than reopening the scan")
    void screenHandlesUnterminatedLiterals() {
        // If the tokenizer ended the literal early, the trailing text would be re-read as SQL
        // and the screen would disagree with the engine about where code begins.
        assertThat(WrapperSqlValidator.findForbiddenConstruct(
                "SELECT * FROM ${SNAPSHOT} WHERE c = 'unterminated")).isNull();
    }

    // ── Phase 4A invariant: only the resolver may emit an httpfs / s3:// read ────────
    //
    // Federated object storage adds a NEW way to reach the network — read_parquet('s3://…') over
    // httpfs, authenticated by a server-side CREATE SECRET. The resolver is the sole emitter of
    // that read; a wrapper must never be able to inject one, or a user could reach an arbitrary
    // bucket (with whatever credentials the connection carries) or install/load httpfs itself.
    // These pin that a wrapper cannot, which is what lets the resolver be the only place the s3
    // read and its secret are built.

    @ParameterizedTest
    @DisplayName("a wrapper cannot inject an s3:// object-store read")
    @ValueSource(strings = {
            "SELECT * FROM read_parquet('s3://other-bucket/secrets.parquet')",
            "SELECT * FROM read_csv_auto('s3://other-bucket/secrets.csv')",
            "SELECT * FROM read_json_auto('s3://other-bucket/secrets.json')",
            // the replacement-scan form: a bare s3:// string in table position, naming no function
            "SELECT * FROM 's3://other-bucket/secrets.parquet'",
    })
    void rejectsObjectStorageReadInjection(String sql) {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @DisplayName("a wrapper cannot install or load httpfs, nor read duckdb_secrets()")
    @ValueSource(strings = {
            "INSTALL httpfs",
            "LOAD httpfs",
            "SELECT * FROM ${SNAPSHOT} WHERE k IN (SELECT name FROM duckdb_secrets())",
    })
    void rejectsHttpfsAndSecretIntrospection(String sql) {
        assertThatThrownBy(() -> WrapperSqlValidator.validate(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── A federated MySQL read ATTACHes the remote database to the connection ──────────────
    //
    // The attached catalog outlives the one view the resolver built over it: any statement on that
    // connection could name it and read every table the repository's account can see. Its alias
    // is reserved (FederatedScan.ATTACH_ALIAS_PREFIX), and the mysql_* functions that take it as a
    // string are refused by name, because LATERAL gets a table function past the structural check.

    @ParameterizedTest
    @DisplayName("an attached live catalog cannot be named, bare or quoted, in any position")
    @ValueSource(strings = {
            "SELECT * FROM aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0.mysql.user",
            "SELECT * FROM \"aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0\".\"srcdb\".\"secrets\"",
            "SELECT * FROM ARAN_ATTACH_0F1E.srcdb.t",
            "SELECT (SELECT count(*) FROM aran_attach_x.srcdb.t) AS n FROM ${SNAPSHOT}",
            "SELECT * FROM ${SNAPSHOT} s JOIN aran_attach_x.srcdb.t u ON s.k = u.k",
    })
    void screenRejectsAttachedCatalogNames(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).as(sql)
                .isNotNull()
                .contains("reserved identifier");
    }

    @ParameterizedTest
    @DisplayName("the mysql_* functions that take an attached catalog's name as a string are refused by name")
    @ValueSource(strings = {
            "SELECT * FROM ${SNAPSHOT}, LATERAL mysql_query('aran_attach_x', 'SELECT * FROM mysql.user')",
            "SELECT * FROM ${SNAPSHOT} JOIN LATERAL mysql_query('aran_attach_x', 'SELECT 1') q ON true",
            "SELECT * FROM mysql_execute('aran_attach_x', 'DROP TABLE t')",
    })
    void screenRejectsMysqlFunctions(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).as(sql)
                .isNotNull()
                .contains("forbidden function call");
    }

    @ParameterizedTest
    @DisplayName("the postgres_* functions that take an attached catalog's name as a string are refused by name")
    @ValueSource(strings = {
            // A federated Postgres read attaches its database too, and postgres_query runs any
            // SQL there with the repository's account — the same hole mysql_query is refused for.
            "SELECT * FROM ${SNAPSHOT}, LATERAL postgres_query('aran_attach_x', 'SELECT * FROM pg_shadow')",
            "SELECT postgres_query('aran_attach_x', 'SELECT 1') FROM ${SNAPSHOT}",
            "SELECT * FROM postgres_execute('aran_attach_x', 'DROP TABLE t')",
            "SELECT * FROM postgres_attach('host=db')",
    })
    void screenRejectsPostgresFunctions(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).as(sql)
                .isNotNull()
                .contains("forbidden function call");
    }

    @Test
    @DisplayName("the prefix alone is reserved — an ordinary name containing 'attach' is not")
    void ordinaryNamesPass() {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(
                "SELECT \"attachment\", aran_note AS c FROM ${SNAPSHOT} WHERE x = 'aran_attach_x'"))
                .isNull();
    }

    @Test
    @DisplayName("an s3:// literal that is data, not a table reference, still passes — it is not a read")
    void allowsS3LiteralAsData() {
        // The invariant is about emitting a READ, not about the substring. A projected or compared
        // literal is data; only a literal in table position (or a reader call) is a read.
        assertThat(WrapperSqlValidator.findForbiddenConstruct(
                "SELECT 's3://bucket/x' AS note FROM ${SNAPSHOT}")).isNull();
    }

    // ── Labels spelled like keywords must not end the FROM list ─────────────

    @ParameterizedTest
    @DisplayName("a name part after '.' or an alias after AS spelled like a clause keyword does not end the FROM list, so the path after the next comma is still in table position")
    @ValueSource(strings = {
            "SELECT * FROM int_x a JOIN (SELECT 1 AS \"where\") b ON b.where = 1, \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x a JOIN (SELECT 1 AS \"values\") b ON b.values = 1, \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x a JOIN (SELECT 1 AS \"select\") b ON b.select = 1, '/tmp/x/leak.csv'",
            "SELECT * FROM int_x a JOIN (SELECT 1 AS \"order\") b ON b.order = 1, \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x a JOIN (SELECT 1 AS \"qualify\") b ON b.qualify = 1, \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x a JOIN (SELECT {'where': 1} AS s) b ON b.s.where = 1, \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x a JOIN (SELECT 1 AS \"where\") b ON b.where = 1, \"/tmp/aran-snapshot-cache/*/*.parquet\"",
            "SELECT * FROM int_x AS values, \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x values, \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x AS values(a), \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x a JOIN int_y b ON values = 1, \"/tmp/x/leak.csv\"",
            "SELECT * FROM int_x WHERE k IN (SELECT 1 FROM int_x AS values, \"/tmp/x/leak.csv\")",
            "SELECT * FROM int_x AS select, \"/tmp/x/leak.csv\"",
    })
    void aKeywordSpelledLabelDoesNotEndTheFromList(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).isNotNull();
    }

    @ParameterizedTest
    @DisplayName("keyword-spelled column parts and aliases are still accepted where no path follows")
    @ValueSource(strings = {
            "SELECT b.where, b.select FROM int_x b WHERE b.order > 1 ORDER BY b.qualify",
            "SELECT * FROM int_x AS values",
            "SELECT * FROM int_x values JOIN int_y b ON values.k = b.k",
            "SELECT * FROM (VALUES (1), (2)) AS v(a), int_y",
            "SELECT * FROM int_x a JOIN (SELECT {'where': 1} AS s) b ON b.s.where = 1",
            "SELECT CAST(x AS INTEGER) FROM ${SNAPSHOT}",
    })
    void keywordSpelledLabelsStayLegal(String sql) {
        assertThat(WrapperSqlValidator.findForbiddenConstruct(sql)).isNull();
    }
}

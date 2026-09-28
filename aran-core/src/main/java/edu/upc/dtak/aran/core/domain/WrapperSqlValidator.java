package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.source.FederatedScan;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.SqlBasicVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Domain service to statically validate user-provided or generated wrapper SQL
 * using Apache Calcite's parser. This mitigates SSRF / LFI vulnerabilities where
 * malicious users could inject DuckDB file-reading functions into the wrapper.
 *
 * <p>The contract is deliberately narrow: a single SELECT projecting over
 * {@code ${SNAPSHOT}} and nothing else. Anything that could reach the filesystem,
 * the network, or another database is rejected.
 *
 * <p><b>Every</b> path that persists wrapper SQL must call {@link #validate};
 * the stored string is later concatenated verbatim into a {@code CREATE VIEW}
 * and executed by DuckDB, so this is the only thing standing between an
 * authenticated user and arbitrary local file reads.
 */
public class WrapperSqlValidator {

    private static final String DUMMY_TABLE = "dummy_snapshot_table";

    /**
     * Exact-match forbidden operators. Pattern-matched families (read_*, *_scan)
     * are handled separately in {@link #isForbidden} so a newly added DuckDB
     * reader does not silently become reachable.
     */
    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "HTTP_GET", "COPY", "GLOB", "ATTACH", "DETACH", "INSTALL", "LOAD",
            "PRAGMA", "EXPORT", "IMPORT", "SNIFF_CSV", "PARQUET_METADATA",
            "PARQUET_SCHEMA", "SHELL", "SYSTEM", "GETENV",
            // Reader indirection: query('<sql>') runs an arbitrary SQL string,
            // query_table('<name>') scans a table named by a string, and
            // json_execute_serialized_sql('<json>') runs the SQL encoded in a JSON string. The
            // actual reader (read_text, a *_scan, duckdb_secrets(), …) hides INSIDE the string
            // argument, so both the token scan and the AST walk see only a string literal — every
            // name-based screen above is blind to it, exactly as with the replacement scan. These
            // are listed for defence in depth, but the STRUCTURAL screen below (no function call in
            // table-reference position) is what actually closes the class: a name blocklist is a
            // losing race against DuckDB's function surface (json_execute_serialized_sql and the
            // parquet-metadata readers were the misses the sec-hardening review found).
            "QUERY", "QUERY_TABLE", "JSON_EXECUTE_SERIALIZED_SQL",
            "PARQUET_FILE_METADATA", "PARQUET_FULL_METADATA", "PARQUET_KV_METADATA",
            "PARQUET_BLOOM_PROBE", "WHICH_SECRET", "COPY_DATABASE", "IMPORT_DATABASE");

    /**
     * Table-valued functions a projection legitimately uses as a row source. Everything else in
     * table-reference position is refused structurally, so a newly added DuckDB reader cannot widen
     * the hole by name. Kept tiny and data-only on purpose: {@code unnest} expands a list column,
     * {@code generate_series}/{@code range} produce numbers — none reads a file or a database.
     */
    private static final Set<String> BENIGN_TABLE_FUNCTIONS = Set.of(
            "UNNEST", "GENERATE_SERIES", "RANGE");

    /**
     * DuckDB grows new readers and scanners over time (read_text, read_blob,
     * read_ndjson, postgres_scan, iceberg_scan, delta_scan, …). Matching the
     * family rather than enumerating names means a DuckDB upgrade cannot quietly
     * widen this hole.
     *
     * <p>The {@code DUCKDB_} family covers the introspection catalogue —
     * {@code duckdb_settings()}, {@code duckdb_extensions()} and above all
     * {@code duckdb_secrets()}, which returns the credentials a {@code CREATE SECRET}
     * installed on the connection. The lake's runner creates exactly such a secret for
     * S3, so this is the difference between a screen miss and handing over object-store
     * keys.
     */
    private static boolean isForbidden(String upperName) {
        return upperName.startsWith("READ_")
                || upperName.endsWith("_SCAN")
                || upperName.startsWith("SCAN_")
                || upperName.startsWith("DUCKDB_")
                // mysql_query/mysql_execute take an ATTACHed catalog's name as a STRING, which the
                // reserved-alias screen below cannot see — so the family goes by name too. A
                // federated Postgres read attaches its database the same way, and postgres_query/
                // postgres_execute are the same hole with the repository's account behind it.
                || upperName.startsWith("MYSQL_")
                || upperName.startsWith("POSTGRES_")
                || FORBIDDEN_NAMES.contains(upperName);
    }

    /**
     * An identifier naming a live source's attached catalog
     * ({@link FederatedScan#ATTACH_ALIAS_PREFIX}). A federated MySQL read attaches the remote
     * database to the connection the statement runs on, so {@code FROM aran_attach_….other.t}
     * would read every table the repository's account can see, not just the enrolled one. Only
     * the resolver may write that name.
     */
    private static boolean isReservedIdentifier(String identifier) {
        return identifier.toLowerCase(Locale.ROOT).startsWith(FederatedScan.ATTACH_ALIAS_PREFIX);
    }

    /**
     * A name from DuckDB's catalogue: the {@code duckdb_*} views and functions, the
     * {@code pragma_*} functions, and the SQLite/ANSI/Postgres compatibility schemas and views.
     * Every one of them can return a view's full SQL, and a {@code src_} view's body is the
     * resolver's read expression — which is why they are refused as identifiers, anywhere, and
     * not only as calls: {@code duckdb_views}, {@code sqlite_master} and {@code pragma_database_list}
     * bind bare, and {@code information_schema}/{@code pg_catalog} are schemas. JDBC metadata
     * comes from the registry, so no client path needs any of them. The bare Postgres views
     * ({@code pg_views}, {@code pg_class}, …) are refused where they would bind — a single-part
     * table source, see {@link #tableNameProblem} — because a column may well be called
     * {@code pg_something}.
     */
    private static boolean isCatalogueName(String identifier) {
        String lower = identifier.toLowerCase(Locale.ROOT);
        return lower.startsWith("duckdb_")
                || lower.startsWith("pragma_")
                || CATALOGUE_NAMES.contains(lower);
    }

    private static final Set<String> CATALOGUE_NAMES = Set.of(
            "information_schema", "pg_catalog",
            "sqlite_master", "sqlite_schema", "sqlite_temp_master", "sqlite_temp_schema");

    /**
     * Screens arbitrary SQL for a construct that reaches outside the integrated views, and
     * returns a human-readable reason, or {@code null} when the statement is clean.
     *
     * <p>Deliberately a token scan rather than an AST walk: this screens raw SQL that Calcite's
     * parser may not accept (DuckDB-specific syntax), and a parse failure must not fail
     * <em>open</em>. Sharing {@link #isForbidden} with {@link #validate} keeps one list rather
     * than two screens that drift apart.
     *
     * <p>Two constructs are refused, and the second is the one that is easy to miss (a third, a
     * name reserved for a live source's attached catalog, is {@link #isReservedIdentifier}):
     *
     * <ol>
     *   <li><b>A call to a forbidden function.</b> The name is taken from the token immediately
     *       before {@code (}, so {@code "read_csv_auto"('…')}, {@code "main"."read_csv_auto"(…)}
     *       and {@code read_csv_auto/*c*&#47;('…')} are all the same call. The regex this
     *       replaced matched {@code name\s*\(} against raw text and therefore saw none of them.</li>
     *   <li><b>A string literal in table-reference position.</b> DuckDB's replacement scan turns
     *       {@code SELECT * FROM '/etc/passwd.csv'} into a {@code read_csv} — <em>with no
     *       function call anywhere in the text</em>. Every name-based screen is blind to it. It
     *       works aliased, in a subquery, in a CTE, in a JOIN and in a scalar subquery, and it
     *       works through the constraint path too. Position, not spelling, is what makes it
     *       dangerous, so position is what is checked.</li>
     * </ol>
     *
     * <p>"Table-reference position" is tracked per bracket (see {@link #findForbiddenTableReference}):
     * {@code FROM unnest(['a','b'])} and {@code FROM (VALUES ('a'))} keep working because their
     * literals are arguments, inside a bracket the {@code FROM} did not introduce. The same
     * position is where a <em>quoted identifier</em> or a dotted name is a file read
     * ({@code FROM "/etc/passwd.csv"}, {@code FROM data.csv}): DuckDB offers any table name it
     * cannot resolve to its replacement scans, so the name itself is screened there too.
     */
    public static String findForbiddenConstruct(String sql) {
        if (sql == null) return null;
        List<Token> tokens = tokenize(sql);

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() == Kind.IDENT
                    && i + 1 < tokens.size()
                    && tokens.get(i + 1).isPunct('(')
                    && isForbidden(token.text().toUpperCase(Locale.ROOT))) {
                return "forbidden function call: " + token.text();
            }
            if (token.kind() == Kind.IDENT && isReservedIdentifier(token.text())) {
                return "a reserved identifier: " + token.text();
            }
            if (token.kind() == Kind.IDENT && isCatalogueName(token.text())) {
                return "the engine's catalogue cannot be read: " + token.text();
            }
        }

        return findForbiddenTableReference(tokens);
    }

    /**
     * One nesting level's view of where the scan is: inside a clause that takes table references
     * ({@code inFrom}), about to read one ({@code expect}), or inside a join's {@code ON}/
     * {@code USING} condition, where literals are data. Saved on every opening bracket and
     * restored on its close, so a subquery's own {@code FROM} cannot end the outer one — the flat
     * depth counter this replaced forgot the outer {@code FROM} at the first {@code )} of a
     * subquery, which let {@code FROM (SELECT …) t, '/etc/passwd.csv'} through.
     *
     * <p>{@code pivot} marks the bracket right after {@code PIVOT}/{@code UNPIVOT}: it is either
     * the SQL-standard clause ({@code t PIVOT (sum(x) FOR …)}), whose first item is an aggregate
     * call, or the statement form's parenthesised source, whose first item is a table — so its
     * first identifier is screened as a table name but may be called.
     *
     * <p>{@code inValues} is a {@code VALUES} row list, itself a table source: its commas separate
     * rows, not tables, until an identifier (the alias, or a keyword) ends it — after which a
     * comma continues the FROM list ({@code FROM VALUES (1) AS v(a), '/etc/passwd.csv'}).
     *
     * <p>{@code pivotStatement} is DuckDB's {@code PIVOT}/{@code UNPIVOT} statement, whose source is
     * one table reference and whose {@code ON}/{@code USING} lists columns and aggregates: none of
     * its commas starts a table source.
     */
    private static final class Frame {
        boolean inFrom;
        boolean expect;
        boolean inCondition;
        boolean pivot;
        boolean inValues;
        boolean pivotStatement;

        boolean expectsSource() {
            return inFrom && expect && !inCondition;
        }

        void startTableClause() {
            inFrom = true;
            expect = true;
            inCondition = false;
            inValues = false;
            pivot = false;                                   // a FROM/JOIN inside names real tables
        }

        void endTableClause() {
            inFrom = false;
            expect = false;
            inCondition = false;
            inValues = false;
            pivotStatement = false;
        }
    }

    /**
     * The table-reference half of {@link #findForbiddenConstruct}: finds every table source the
     * statement names and refuses one DuckDB could turn into a file, URL or catalogue read.
     *
     * <p>A table source is the item after {@code FROM}, {@code JOIN}, a comma in a FROM list
     * (including the comma after a join condition, which continues the list), the start of a
     * bracket that itself sits in table position ({@code FROM ("x" JOIN y …)}), and the item after
     * each DuckDB statement form that takes one — {@code TABLE}, {@code SUMMARIZE},
     * {@code DESCRIBE}/{@code DESC}, {@code SHOW}, {@code PIVOT}/{@code UNPIVOT} — all of which are
     * valid as subqueries. {@code LATERAL} is a modifier and the source is what follows it.
     *
     * <p>Three things are refused there: a string literal (the replacement scan in its original
     * form), a function call other than {@link #BENIGN_TABLE_FUNCTIONS} (the structural close on
     * reader indirection), and a name {@link #tableNameProblem} rejects — which is how a quoted
     * identifier ({@code FROM "/etc/passwd.csv"}) and a dotted one ({@code FROM data.csv}) are
     * caught: DuckDB reads either as a path once no table of that name exists.
     */
    private static String findForbiddenTableReference(List<Token> tokens) {
        java.util.ArrayDeque<Frame> outer = new java.util.ArrayDeque<>();
        Frame frame = new Frame();
        boolean frameStart = true;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            boolean atFrameStart = frameStart;
            frameStart = false;

            if (token.isOpener()) {
                boolean source = frame.expectsSource();
                boolean pivotBracket = !source && i > 0 && tokens.get(i - 1).isKeyword(PIVOT_KEYWORDS);
                if (source) {
                    frame.expect = false;                    // the bracket IS this table source
                }
                outer.push(frame);
                frame = new Frame();
                if (source || pivotBracket) {
                    frame.startTableClause();
                    frame.pivot = pivotBracket;
                }
                frameStart = true;
            } else if (token.isCloser()) {
                frame = outer.isEmpty() ? new Frame() : outer.pop();
            } else if (token.isPunct(',')) {
                if (frame.inFrom && !frame.inValues && !frame.pivotStatement) {
                    frame.expect = true;                     // the next comma-joined table source
                    frame.inCondition = false;
                }
            } else if (token.kind() == Kind.STRING) {
                if (frame.inFrom && !frame.inCondition) {
                    return "a string literal cannot be used as a table reference "
                            + "(DuckDB would read it as a file)";
                }
            } else if (token.kind() == Kind.IDENT) {
                String upper = token.text().toUpperCase(Locale.ROOT);
                Token previous = i > 0 ? tokens.get(i - 1) : null;
                if (previous != null && (previous.isPunct('.') || previous.isKeyword(ALIAS_KEYWORD))) {
                    // A label: the part after '.' ({@code b.where}, {@code s.where} — DuckDB's
                    // grammar admits any keyword there) or the alias after AS ({@code int_x AS
                    // values}). Never a clause keyword, and never a table source either: a
                    // source's dotted name is read whole below, and an alias follows one.
                    // Reading b.where as WHERE ended the FROM list early, so the comma after the
                    // join condition no longer put ", '/tmp/x.csv'" in table position.
                    frame.inValues = false;                  // an alias ends a VALUES row list
                    continue;
                }
                if (!token.quoted() && upper.equals("VALUES") && frame.expectsSource()) {
                    frame.expect = false;                    // the row list IS this table source
                    frame.inValues = true;
                    continue;
                }
                // Anywhere else VALUES is an alias (int_x values) or a column (ON values = 1):
                // a plain identifier for the path below, not the start of a row list.
                frame.inValues = false;                      // an alias or a keyword ends the rows
                if (!token.quoted()) {
                    if (TABLE_REF_INTRODUCERS.contains(upper)
                            || (atFrameStart && upper.equals("DESC"))) {
                        frame.startTableClause();
                        continue;
                    }
                    if (PIVOT_KEYWORDS.contains(upper)) {
                        // After a table source it is the SQL-standard clause, whose bracket is a
                        // pivot frame (see the opener branch). Anywhere else it is the statement,
                        // whose next item — a name or a bracketed source — is the table source.
                        boolean clauseForm = frame.inFrom && !frame.expect && !frame.inCondition;
                        if (!clauseForm) {
                            frame.startTableClause();
                            frame.pivotStatement = true;
                        }
                        continue;
                    }
                    if (frame.inFrom && (upper.equals("ON") || upper.equals("USING"))) {
                        frame.inCondition = true;            // literals here are data
                        frame.expect = false;
                        continue;
                    }
                    if (TABLE_REF_TERMINATORS.contains(upper)) {
                        frame.endTableClause();
                        continue;
                    }
                    if (upper.equals("LATERAL") && frame.expectsSource()) {
                        continue;                            // a modifier: the source follows it
                    }
                }
                if (!frame.expectsSource()) {
                    continue;
                }
                // This identifier starts the table source: read the whole dotted name.
                frame.expect = false;
                List<String> parts = new ArrayList<>();
                parts.add(token.text());
                int j = i + 1;
                while (j < tokens.size() && tokens.get(j).isPunct('.')) {
                    if (j + 1 >= tokens.size() || tokens.get(j + 1).kind() != Kind.IDENT) {
                        return "a malformed table reference";
                    }
                    parts.add(tokens.get(j + 1).text());
                    j += 2;
                }
                i = j - 1;
                boolean call = j < tokens.size() && tokens.get(j).isPunct('(');
                if (call) {
                    boolean benign = parts.size() == 1
                            && BENIGN_TABLE_FUNCTIONS.contains(parts.get(0).toUpperCase(Locale.ROOT));
                    if (!benign && !frame.pivot) {
                        // A function call in table-source position: query(), query_table(),
                        // json_execute_serialized_sql(), read_*, any *_scan, and every future reader
                        // DuckDB adds all land here. This is the structural close the name blocklist
                        // cannot keep up with — the reader that hides inside a string argument is
                        // still reached through a table function, and that function is refused.
                        return "a function call cannot be used as a table reference "
                                + "(DuckDB indirection like query()/json_execute_serialized_sql())";
                    }
                    continue;                                // its arguments open the next frame
                }
                String problem = tableNameProblem(parts);
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    /**
     * Why a (possibly qualified) table name must not reach DuckDB, or {@code null} when it is a
     * plain name the engine could have created.
     *
     * <p>Every table name the engine hands out — {@code Names.sqlName}'s {@code int_}/{@code src_}/
     * {@code w_} views, {@link ProductCatalog}'s and {@link SourceCatalog}'s tables — is
     * {@code [A-Za-z0-9_]+}, and every qualifier a client can meaningfully write is one of
     * {@link #KNOWN_CATALOGS}/{@link #KNOWN_SCHEMAS}. DuckDB, when a name does not resolve, joins
     * its parts with {@code '.'} and offers the result to its replacement scans, so a part with a
     * path character in it ({@code "/etc/passwd.csv"}, {@code "https://…"}, a glob), a qualifier
     * that is really a file stem ({@code data.csv}), or an extension in the table slot of an
     * otherwise known qualifier ({@code main.csv}) is a file read. None of those can be a name
     * the engine created, so refusing them costs no legitimate query.
     */
    private static String tableNameProblem(List<String> parts) {
        for (String part : parts) {
            if (!PLAIN_NAME.matcher(part).matches()) {
                return "a table reference must be a plain name — DuckDB reads a quoted path, URL or"
                        + " glob in table position as a file";
            }
        }
        int n = parts.size();
        boolean qualified = switch (n) {
            case 1 -> true;
            case 2 -> KNOWN_CATALOGS.contains(lower(parts.get(0)))
                    || KNOWN_SCHEMAS.contains(lower(parts.get(0)));
            case 3 -> KNOWN_CATALOGS.contains(lower(parts.get(0)))
                    && KNOWN_SCHEMAS.contains(lower(parts.get(1)));
            default -> false;
        };
        if (!qualified) {
            return "a table reference may be qualified only by the catalog '"
                    + String.join("'/'", KNOWN_CATALOGS) + "' and the schemas '"
                    + String.join("'/'", KNOWN_SCHEMAS) + "' — DuckDB reads any other dotted name"
                    + " as a relative file path";
        }
        if (n == 1 && lower(parts.get(0)).startsWith("pg_")) {
            // DuckDB searches pg_catalog, so pg_views/pg_class/… bind unqualified; a qualified
            // products.pg_x is a product and never reaches the system catalogue.
            return "the engine's catalogue cannot be read: " + parts.get(0);
        }
        if (n > 1 && FILE_SUFFIXES.contains(lower(parts.get(n - 1)))) {
            return "a qualified table name cannot end in a file extension — DuckDB reads '"
                    + String.join(".", parts) + "' as a relative file path when no such table exists";
        }
        return null;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** What every engine-created table and schema name matches; see {@link #tableNameProblem}. */
    private static final java.util.regex.Pattern PLAIN_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** The catalog a client addresses ({@code aran}); DuckDB's own are not meant to be named. */
    private static final List<String> KNOWN_CATALOGS = List.of("aran");

    /** The JDBC tree's three schemas plus DuckDB's default one, which the graph views live in. */
    private static final List<String> KNOWN_SCHEMAS = List.of("products", "sources", "graph", "main");

    /**
     * Suffixes DuckDB's replacement scans read as a file format (core CSV/Parquet/JSON, their
     * compressed forms, and the autoloadable Excel/Avro/Arrow/spatial readers). Only consulted for
     * the table slot of a <em>qualified</em> name — an unqualified plain name has no dot, so no
     * replacement scan can take it for a file.
     */
    private static final Set<String> FILE_SUFFIXES = Set.of(
            "csv", "tsv", "txt", "parquet", "json", "jsonl", "ndjson", "gz", "zst", "zstd",
            "xlsx", "avro", "arrow", "ipc", "feather", "db", "duckdb", "sqlite", "gpkg", "shp",
            "geojson", "fgb");

    /**
     * Bare keywords after which the next item is a table source. {@code FROM}/{@code JOIN} are
     * the obvious two; {@code TABLE x} is {@code SELECT * FROM x}, and {@code SUMMARIZE},
     * {@code DESCRIBE}, {@code SHOW} take a table too — all four are accepted by DuckDB 1.5.5
     * inside a subquery, a CTE, an {@code EXISTS} or after {@code UNION ALL}, and all are reserved
     * words, so a bare one is never a column. {@code DESC} is too, but only where a statement
     * starts ({@code (DESC "x.csv")}); everywhere else it is {@code ORDER BY … DESC}.
     */
    private static final Set<String> TABLE_REF_INTRODUCERS = Set.of(
            "FROM", "JOIN", "TABLE", "SUMMARIZE", "DESCRIBE", "SHOW");

    /** DuckDB's pivot statements, whose source is a table reference; see {@link Frame#pivot}. */
    private static final Set<String> PIVOT_KEYWORDS = Set.of(
            "PIVOT", "UNPIVOT", "PIVOT_WIDER", "PIVOT_LONGER");

    /**
     * Every identifier the statement mentions, lower-cased — quoted and bare alike, with a quoted
     * identifier's surrounding quotes stripped and its {@code ""} escapes undone.
     *
     * <p>Exists for V47's stale-wrapper check, which asks one narrow question: does this wrapper
     * still name a source column the snapshot has since lost? That is a membership test against a
     * <em>known set of removed column names</em>, so the answer only has to be exact about
     * identifier boundaries — which is what makes reusing this class's tokenizer the right move
     * and a fresh regex the wrong one. The set deliberately contains SQL keywords and function
     * names too: filtering them would need a keyword list that drifts, and a caller intersecting
     * with real column names does not care.
     *
     * <p>Lower-cased because DuckDB folds unquoted identifiers and the check must not miss a
     * genuine reference over the case a user happened to type.
     *
     * <p><b>Not a substitute for {@link #validate}.</b> This says nothing about safety.
     */
    public static Set<String> identifierTokens(String sql) {
        if (sql == null) {
            return Set.of();
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Token token : tokenize(sql)) {
            if (token.kind() == Kind.IDENT) {
                out.add(token.text().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * Keywords that end a FROM/JOIN clause. {@code ON} and {@code USING} are deliberately NOT
     * here: they open a join condition, where literals are data, but a comma after the condition
     * continues the same FROM list ({@code … ON a.k = b.k, '/etc/passwd.csv'} is a third table),
     * so they suspend the clause rather than end it — see {@link Frame#inCondition}. Neither
     * are {@code WITH} (a cast's {@code TIMESTAMP WITH TIME ZONE} sits inside a join condition)
     * nor {@code VALUES} (a row list is a table source a FROM list continues past — see
     * {@link Frame#inValues}).
     */
    private static final Set<String> TABLE_REF_TERMINATORS = Set.of(
            "SELECT", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "WINDOW",
            "QUALIFY", "UNION", "EXCEPT", "INTERSECT", "RETURNING");

    /**
     * What follows {@code AS} is a label — a table or column alias, a cast's target type — which
     * DuckDB's grammar lets be spelled like any keyword, so the scanner never reads it as one.
     */
    private static final Set<String> ALIAS_KEYWORD = Set.of("AS");

    // ── tokenizer ────────────────────────────────────────────────────────────────────────
    //
    // A screen that disagrees with the engine about where a string literal ends is not a
    // screen. DuckDB accepts four literal syntaxes and two comment syntaxes, and every one of
    // them was a working bypass of the regex this replaced: '…', E'…' (backslash escapes),
    // $$…$$ and $tag$…$tag$, plus -- and /* */ between a function name and its parenthesis.

    private enum Kind { IDENT, STRING, PUNCT, OTHER }

    /**
     * {@code quoted} is set only on a double-quoted identifier. A quoted name is the same function
     * name as a bare one (the call screen ignores the flag), but it is never a keyword:
     * {@code "from"} is a column, and treating it as {@code FROM} would put the next name in
     * table position.
     */
    private record Token(Kind kind, String text, boolean quoted) {
        Token(Kind kind, String text) {
            this(kind, text, false);
        }

        boolean isPunct(char c) {
            return kind == Kind.PUNCT && text.length() == 1 && text.charAt(0) == c;
        }

        /**
         * Brackets of every kind nest: a list {@code [1, 2]} or struct {@code {'k': 1, 'j': 2}}
         * in a join condition carries commas that are not FROM-list commas, and
         * {@code ${SNAPSHOT}}'s braces put the placeholder in the bracket it sits in.
         */
        boolean isOpener() {
            return isPunct('(') || isPunct('[') || isPunct('{');
        }

        boolean isCloser() {
            return isPunct(')') || isPunct(']') || isPunct('}');
        }

        boolean isKeyword(Set<String> keywords) {
            return kind == Kind.IDENT && !quoted && keywords.contains(text.toUpperCase(Locale.ROOT));
        }
    }

    private static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                // Nested, as Postgres and DuckDB both allow: /* a /* b */ c */ is one comment.
                int nesting = 0;
                while (i < n) {
                    if (sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                        nesting++;
                        i += 2;
                    } else if (sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                        nesting--;
                        i += 2;
                        if (nesting == 0) break;
                    } else {
                        i++;
                    }
                }
            } else if (c == '\'') {
                i = consumeQuoted(sql, i, '\'', false);
                out.add(new Token(Kind.STRING, ""));
            } else if ((c == 'E' || c == 'e') && i + 1 < n && sql.charAt(i + 1) == '\'') {
                // E'…' honours backslash escapes, so E'\'' is one literal, not two.
                i = consumeQuoted(sql, i + 1, '\'', true);
                out.add(new Token(Kind.STRING, ""));
            } else if ((c == 'U' || c == 'u') && i + 2 < n
                    && sql.charAt(i + 1) == '&' && sql.charAt(i + 2) == '\'') {
                i = consumeQuoted(sql, i + 2, '\'', false);
                out.add(new Token(Kind.STRING, ""));
            } else if (c == '$' && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                i = close < 0 ? n : close + tag.length();
                out.add(new Token(Kind.STRING, ""));
            } else if (c == '"') {
                int start = i + 1;
                i = consumeQuoted(sql, i, '"', false);
                // Strip the surrounding quotes and undo the "" escape: a quoted identifier is
                // the same function name as a bare one, which is the whole point of quoting it.
                String raw = sql.substring(start, Math.max(start, i - 1));
                out.add(new Token(Kind.IDENT, raw.replace("\"\"", "\""), true));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                out.add(new Token(Kind.IDENT, sql.substring(start, i)));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                out.add(new Token(Kind.OTHER, sql.substring(start, i)));
            } else {
                out.add(new Token(Kind.PUNCT, String.valueOf(c)));
                i++;
            }
        }
        return out;
    }

    /** Consumes a quoted run starting at {@code start}, returning the index just past its close. */
    private static int consumeQuoted(String sql, int start, char quote, boolean backslashEscapes) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < n) {
                i += 2;
            } else if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;          // '' / "" — an escaped quote, not the close
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return n;                    // unterminated: treat the rest as part of the literal
    }

    /**
     * If a dollar-quote tag opens at {@code i}, the index just past its opening {@code $}.
     * {@code $$} and {@code $tag$} are literals; {@code $1} is a bind parameter and is not.
     */
    private static int dollarTagEnd(String sql, int i) {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        return j < sql.length() && sql.charAt(j) == '$' ? j + 1 : -1;
    }

    /**
     * Peels the comma laterals off a FROM clause, returning the left-most table reference —
     * which the caller then holds to the "must be ${SNAPSHOT}" rule unchanged.
     *
     * <p>Exists because Calcite gives a comma-separated FROM the same node type as an explicit
     * join: {@code FROM dummy s, UNNEST(s."tags") AS u1(_r1)} is a {@link SqlJoin} with
     * {@link JoinType#COMMA} (pinned by {@code WrapperSqlValidatorTest.commaLateralParsesAsCommaJoin}).
     * Refusing every {@code SqlJoin} therefore refused <em>every</em> wrapper the bootstrapper
     * writes for a nested-JSON class, so such a class could never be previewed, re-saved or
     * verified even though the same SQL executed fine — the token screen above had already
     * allowed the shape via {@link #BENIGN_TABLE_FUNCTIONS} and only this AST check disagreed
     * (S4-3). This makes the two screens say the same thing; it does not widen either list.
     *
     * <p>What is still refused, and why the allowance is not a hole: any join that is not a
     * COMMA (INNER/LEFT/CROSS/…), any comma operand that is not a call to a
     * {@code BENIGN_TABLE_FUNCTIONS} member, and — via the unchanged check at the call site —
     * anything whose left-most leaf is not the snapshot. A second table, a second
     * {@code ${SNAPSHOT}} and a comma-joined reader all land in one of those three.
     */
    private static SqlNode stripBenignLaterals(SqlNode from) {
        while (from instanceof SqlJoin join) {
            if (join.getJoinType() != JoinType.COMMA || !isBenignTableFunctionCall(join.getRight())) {
                throw new IllegalArgumentException(
                        "Wrapper SQL must not join — FROM must be ${SNAPSHOT} alone");
            }
            from = join.getLeft();
        }
        return from;
    }

    /**
     * Whether a comma operand is a call to a benign table function, once two wrappers Calcite adds
     * are peeled off: the {@code AS alias(col)} the builder always writes, and the implicit
     * {@code TABLE(…)} the parser puts around a bare table function in FROM position. So
     * {@code UNNEST(x) AS u1(_r1)} is {@code AS(UNNEST(…), …)} while
     * {@code generate_series(1, 10) AS t(g)} is {@code AS(TABLE(GENERATE_SERIES(…)), …)} — the
     * name is one or two operands down, never at the top.
     */
    private static boolean isBenignTableFunctionCall(SqlNode node) {
        SqlNode candidate = node;
        while (candidate instanceof SqlCall call
                && (call.getOperator().getKind() == SqlKind.AS
                    || call.getOperator().getKind() == SqlKind.COLLECTION_TABLE)
                && !call.getOperandList().isEmpty()) {
            candidate = call.getOperandList().get(0);
        }
        return candidate instanceof SqlCall call
                && BENIGN_TABLE_FUNCTIONS.contains(
                        call.getOperator().getName().toUpperCase(Locale.ROOT));
    }

    /**
     * Validates that the provided wrapper SQL is a safe projection.
     * Throws an IllegalArgumentException if the SQL is malicious or malformed.
     */
    public static void validate(String wrapperSql) {
        if (wrapperSql == null || wrapperSql.isBlank()) {
            throw new IllegalArgumentException("Wrapper SQL cannot be empty");
        }

        // The token screen first, on the text as written. It is the only one of the two that
        // sees DuckDB-specific syntax Calcite cannot parse, and running it here means every
        // path into this class gets the same screen rather than the AST walk alone.
        String forbidden = findForbiddenConstruct(wrapperSql);
        if (forbidden != null) {
            throw new IllegalArgumentException("Forbidden construct in wrapper SQL: " + forbidden);
        }

        // Replace the placeholder with a valid SQL identifier to allow parsing
        String sqlToParse = wrapperSql.replace("${SNAPSHOT}", DUMMY_TABLE);

        try {
            SqlParser parser = SqlParser.create(sqlToParse);
            SqlNode node = parser.parseQuery();

            if (!(node instanceof SqlSelect)) {
                throw new IllegalArgumentException("Wrapper SQL must be a SELECT statement");
            }

            SqlSelect select = (SqlSelect) node;

            // 1. Validate FROM clause exists
            SqlNode from = select.getFrom();
            if (from == null) {
                throw new IllegalArgumentException("Wrapper SQL must have a FROM clause");
            }

            // We enforce that the FROM clause is exactly the snapshot placeholder (or an alias
            // thereof), possibly with benign laterals hanging off it. A join would let a wrapper
            // reach a second table, so reject it outright rather than relying on the substring
            // check below to notice.
            from = stripBenignLaterals(from);
            String fromStr = from.toString().toUpperCase(Locale.ROOT);
            if (!fromStr.contains(DUMMY_TABLE.toUpperCase(Locale.ROOT)) && !fromStr.startsWith("SRC_")) {
                throw new IllegalArgumentException("FROM clause must strictly reference ${SNAPSHOT}");
            }

            // 2. Walk AST to reject dangerous functions and constructs.
            //    Checked on every operator, not only SqlFunction: DuckDB's readers surface as
            //    table-valued functions in FROM, which do not always parse as SqlFunction.
            node.accept(new SqlBasicVisitor<Void>() {
                @Override
                public Void visit(SqlCall call) {
                    String name = call.getOperator().getName();
                    if (isForbidden(name.toUpperCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("Forbidden function call in wrapper SQL: " + name);
                    }
                    return super.visit(call);
                }
            });

        } catch (SqlParseException e) {
            throw new IllegalArgumentException("Invalid SQL syntax in wrapper: " + e.getMessage(), e);
        }
    }
}

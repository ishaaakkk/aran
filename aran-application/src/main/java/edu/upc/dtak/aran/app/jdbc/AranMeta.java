package edu.upc.dtak.aran.app.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.domain.ProductTable;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.ConnectionPropertiesImpl;
import org.apache.calcite.avatica.MetaImpl;
import org.apache.calcite.avatica.MissingResultsException;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.apache.calcite.avatica.remote.TypedValue;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Aran's Avatica {@link org.apache.calcite.avatica.Meta} — a JDBC face on two query paths.
 *
 * <h2>Three schemas, one connection</h2>
 *
 * <p>The tree a client browses has one catalog, {@code aran}, and three schemas:
 *
 * <ul>
 *   <li><b>{@code graph}</b> — the integrated classes of the active schema version. A statement
 *       here is <b>OQL</b>, and goes to the query engine's {@code materialize-oql}:
 *       {@code OqlParser → OqlPlanner → PlanExecutor → DuckDB}, the GAV rewrite in full.</li>
 *   <li><b>{@code products}</b> — the tenant's materialized data products, one table each. A
 *       statement here is <b>plain SQL</b> and goes to {@code materialize-product-sql}, which
 *       scans the products' parquet snapshots directly. No mapping catalog, no wrappers, no
 *       rewrite: a data product is already the answer to a query.</li>
 *   <li><b>{@code sources}</b> — the per-source wrapper views under their {@code SourceCatalog}
 *       names, one table per bootstrapped source class. A statement here is <b>plain SQL</b> and
 *       goes to {@code materialize-source-sql}: the user writes their own joins over
 *       source-native tables. Rows are as the wrapper emits them — denial constraints are
 *       enforced in the {@code graph} tier's views, not here.</li>
 * </ul>
 *
 * <p>Nothing here parses or validates any language — that is the engine's job, and duplicating
 * any of it would create a second dialect that drifts from the first. The one thing this class
 * decides is <em>which</em> engine operation a statement goes to; see {@link #routeOf}. Its one
 * edit to a statement is removing the {@code graph.} qualifier that chose the route
 * ({@link #withoutGraphQualifiers}), since OQL has no schema level to read it as.
 *
 * <p>All three languages begin with {@code SELECT}, so the routing cannot be a keyword sniff and
 * is not a guess: a statement naming the {@code products} or {@code sources} schema takes that
 * path (naming both is rejected — the routes are different engines, so the join could not
 * execute anyway), a statement naming {@code graph} takes the mediated path (and naming it
 * beside either of the others is rejected the same way), and otherwise the connection's current
 * schema decides — {@code graph} unless the client set otherwise, so a connection that never
 * mentions any schema keeps the mediated behaviour it had before the split.
 *
 * <h2>Tenancy</h2>
 *
 * <p>Every method runs on the HTTP request thread of the Avatica call that triggered it, so the
 * caller is resolved from the security context per request, exactly as a controller would. The
 * tenant that opened a statement is recorded with it and re-checked on every fetch: a statement
 * handle is a guessable integer, and without the check a leaked handle would read another tenant's
 * rows.
 *
 * <h2>Statement state, and the single-node assumption</h2>
 *
 * <p>An executed statement holds an open cursor over a temp file until the client closes it. That
 * makes this endpoint <b>stateful per node</b>: a {@code fetch} must reach the instance that ran
 * the {@code prepareAndExecute}. Behind a load balancer, that means sticky sessions. The state is
 * also capped in time — an abandoned statement is evicted and its file deleted after
 * {@link #STATEMENT_TTL}, because JDBC clients are entitled to walk away without closing.
 */
@NullMarked
public class AranMeta extends ReadOnlyMeta {

    private static final Logger log = LoggerFactory.getLogger(AranMeta.class);

    /** How long an executed statement survives without being fetched from. */
    static final Duration STATEMENT_TTL = Duration.ofMinutes(15);

    private static final String QUERY_ENGINE = "query-engine";
    private static final String MATERIALIZE = "materialize-oql";
    private static final String MATERIALIZE_PRODUCT = "materialize-product-sql";
    private static final String MATERIALIZE_SOURCE = "materialize-source-sql";
    private static final String CATALOG = "catalog";

    /** Aran exposes one catalog. It is named, and it is not selectable. */
    static final String CATALOG_NAME = "aran";
    /** The mediated schema: integrated classes, queried with OQL. */
    static final String GRAPH_SCHEMA = "graph";
    /** The passthrough schema: materialized data products, queried with SQL. */
    static final String PRODUCTS_SCHEMA = "products";
    /** The source tier: per-source wrapper views under their SourceCatalog names, queried with SQL. */
    static final String SOURCES_SCHEMA = "sources";
    static final String TABLE_TYPE = "TABLE";

    /** The registry table inside {@code products}. Its name and shape are the engine's. */
    static final String PRODUCT_REGISTRY_TABLE = "_products";

    /** {@code products._products}'s columns, in order — mirrors {@code ProductSqlExecutor}. */
    private static final Map<String, Integer> PRODUCT_REGISTRY_COLUMNS = registryColumns();

    private static Map<String, Integer> registryColumns() {
        Map<String, Integer> columns = new LinkedHashMap<>();
        columns.put("product", Types.VARCHAR);
        columns.put("status", Types.VARCHAR);
        // MATERIALIZED or VIRTUAL (Phase 5) — must stay aligned with the VALUES tuple
        // ProductSqlExecutor.createRegistryView builds; see that method's comment.
        columns.put("access", Types.VARCHAR);
        columns.put("last_refreshed", Types.TIMESTAMP);
        columns.put("row_count", Types.BIGINT);
        columns.put("project_id", Types.VARCHAR);
        columns.put("oql", Types.VARCHAR);
        // Insertion-ordered, not Map.copyOf: ORDINAL_POSITION is the order they are declared in.
        return Collections.unmodifiableMap(columns);
    }

    /**
     * Which of the two paths a statement takes. The names are the schema names a client sees,
     * because that is the only place the distinction is visible from outside.
     */
    enum Route { GRAPH, PRODUCTS, SOURCES }

    /**
     * A reference to the {@code products} schema in statement text: the word, then a dot, then an
     * identifier with nothing between them.
     *
     * <p>Matched on the text rather than on a parse, because the two languages need different
     * parsers and this decision comes before either. The lookbehind stops a longer word
     * ({@code subproducts.x}) from matching while still allowing the catalog-qualified form
     * ({@code aran.products.sales}), and requiring the identifier to follow the dot immediately
     * stops ordinary prose ({@code 'see products. it explains'}) from matching at all.
     *
     * <p>It is still a text match, so a literal spelling out {@code 'products.sales'} inside an
     * OQL statement routes that statement to the products path, where it fails to parse — and so
     * does OQL over a class genuinely called {@code products} ({@code SELECT products.price FROM
     * products}). That is the over-inclusive direction on purpose: the alternative — resolving the
     * name against the registry first — would silently send a mistyped product name to the OQL
     * parser and report it as an OQL error. Such a class is reached with the explicit
     * {@link #GRAPH_QUALIFIER}: {@code SELECT graph.products.price FROM graph.products}.
     */
    private static final Pattern PRODUCTS_QUALIFIER =
            Pattern.compile("(?<![\\w\"])\"?products\"?\\s*\\.[A-Za-z_\"]",
                    Pattern.CASE_INSENSITIVE);

    /** As {@link #PRODUCTS_QUALIFIER}, for the {@code sources} schema — same shape, same caveats. */
    private static final Pattern SOURCES_QUALIFIER =
            Pattern.compile("(?<![\\w\"])\"?sources\"?\\s*\\.[A-Za-z_\"]",
                    Pattern.CASE_INSENSITIVE);

    /** One OQL name, bare or double-quoted — what may follow a {@code graph.} qualifier. */
    private static final String OQL_NAME = "(?:[A-Za-z_]\\w*|\"(?:[^\"]|\"\")+\")";

    /**
     * A {@code graph.} or {@code aran.graph.} qualifier at the head of a dotted name — the escape
     * hatch for a class genuinely called {@code products} or {@code sources}, which the two
     * patterns above would otherwise route away from the graph tier (S10-2). The lookbehind keeps
     * it to the head of a chain, so in {@code graph.graph.x} only the first {@code graph} is a
     * qualifier. Whether a match really is one is decided by {@link #graphQualifiers}.
     */
    private static final Pattern GRAPH_QUALIFIER = Pattern.compile(
            "(?<![\\w\".])(?:\"?aran\"?\\s*\\.\\s*)?\"?graph\"?\\s*\\.\\s*(?=" + OQL_NAME + ")",
            Pattern.CASE_INSENSITIVE);

    /** What follows a qualifier when it heads a three-part {@code graph.Class.prop} reference. */
    private static final Pattern NAME_THEN_DOT =
            Pattern.compile(OQL_NAME + "\\s*\\.");

    /** The text before a qualifier that puts it in a FROM path: {@code FROM} or {@code ->}. */
    private static final Pattern FROM_POSITION =
            Pattern.compile("(?:(?<![\\w\"])from|->)\\s*$", Pattern.CASE_INSENSITIVE);

    private final OperatorOrchestrator orchestrator;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper mapper;
    private final edu.upc.dtak.aran.core.service.UsageService usage;
    private final edu.upc.dtak.aran.core.licensing.LicenseService licenses;
    private final ProductRegistry products;

    /**
     * The schema each open connection is on, when it is not the default.
     *
     * <p>Avatica connections are client-chosen ids on a stateless HTTP endpoint, so this is the
     * only place a {@code setSchema} can be remembered — and it has to be remembered, because it
     * is half of the routing decision. Absent means {@link #GRAPH_SCHEMA}: a client that never
     * sets a schema gets the mediated path, which is what every existing connection string does.
     */
    private final ConcurrentHashMap<String, String> connectionSchemas = new ConcurrentHashMap<>();

    private final Cache<StatementKey, OpenStatement> statements = Caffeine.newBuilder()
            .expireAfterAccess(STATEMENT_TTL)
            // Removal listeners run on the common pool by default, which would make
            // closeStatement return before the cursor's file was deleted — and a client that
            // closes a statement and immediately reruns it would race its own temp file.
            .executor(Runnable::run)
            .<StatementKey, OpenStatement>removalListener((key, statement, cause) -> {
                if (statement != null) {
                    if (cause == RemovalCause.EXPIRED) {
                        log.info("JDBC statement {} expired without being closed", key);
                    }
                    statement.close();
                }
            })
            .build();

    public AranMeta(OperatorOrchestrator orchestrator, CurrentUserResolver currentUser,
                    ObjectMapper mapper, edu.upc.dtak.aran.core.service.UsageService usage,
                    edu.upc.dtak.aran.core.licensing.LicenseService licenses,
                    ProductRegistry products) {
        this.orchestrator = orchestrator;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.usage = usage;
        this.licenses = licenses;
        this.products = products;
    }

    private record StatementKey(String connectionId, int statementId) {
        @Override public String toString() {
            return connectionId + "/" + statementId;
        }
    }

    /** An executed statement: its cursor, and the tenant allowed to read from it. */
    private record OpenStatement(String tenantId, @Nullable AranCursor cursor) {
        void close() {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ── Connections and statements ──────────────────────────────────────────────

    @Override
    public void openConnection(ConnectionHandle ch, Map<String, String> info) {
        // Nothing to open: the HTTP request is already authenticated, and the "connection" is a
        // client-side bookkeeping id. Rejecting unknown properties here would break drivers that
        // send their own defaults.
        //
        // The one property that is read is `schema`, from the connection string: it is how a tool
        // that cannot issue setSchema — or a user who would rather write it once — starts a
        // session on `products`.
        if (info != null) {
            rememberSchema(ch.id, info.get("schema"));
        }
    }

    @Override
    public void closeConnection(ConnectionHandle ch) {
        closeStatementsOf(ch.id);
        connectionSchemas.remove(ch.id);
    }

    /** The schema this connection is on. {@link #GRAPH_SCHEMA} unless the client changed it. */
    String schemaOf(String connectionId) {
        return connectionSchemas.getOrDefault(connectionId, GRAPH_SCHEMA);
    }

    private void rememberSchema(String connectionId, @Nullable String requested) {
        if (requested == null || requested.isBlank()) {
            return;
        }
        String schema = requested.trim();
        if (schema.equalsIgnoreCase(PRODUCTS_SCHEMA)) {
            connectionSchemas.put(connectionId, PRODUCTS_SCHEMA);
        } else if (schema.equalsIgnoreCase(SOURCES_SCHEMA)) {
            connectionSchemas.put(connectionId, SOURCES_SCHEMA);
        } else if (schema.equalsIgnoreCase(GRAPH_SCHEMA)) {
            connectionSchemas.put(connectionId, GRAPH_SCHEMA);
        } else {
            // A schema Aran does not have. Ignored rather than refused: clients set a schema from
            // a remembered workspace, and failing the connection over a stale name would lock a
            // user out of a tree they could otherwise fix from.
            log.debug("JDBC connection {} asked for unknown schema '{}' — staying on {}",
                    connectionId, schema, schemaOf(connectionId));
        }
    }

    @Override
    public StatementHandle createStatement(ConnectionHandle ch) {
        return new StatementHandle(ch.id, statementIds.incrementAndGet(), null);
    }

    @Override
    public void closeStatement(StatementHandle h) {
        // invalidate() fires the removal listener, which closes the cursor and deletes its file.
        statements.invalidate(new StatementKey(h.connectionId, h.id));
    }

    private void closeStatementsOf(String connectionId) {
        List<StatementKey> owned = statements.asMap().keySet().stream()
                .filter(key -> key.connectionId().equals(connectionId))
                .toList();
        statements.invalidateAll(owned);
    }

    /**
     * Read-only, autocommit, one isolation level — those are answered with the truth rather than
     * echoed back. The <b>schema</b> is different: it is the one connection property that changes
     * what Aran does, because it routes a statement between the mediated and passthrough paths.
     * A {@code setSchema} is therefore recorded here, and the reply says which schema the
     * connection is actually on.
     */
    @Override
    public ConnectionProperties connectionSync(ConnectionHandle ch, ConnectionProperties connProps) {
        if (connProps != null) {
            rememberSchema(ch.id, connProps.getSchema());
        }
        return new ConnectionPropertiesImpl(true, true, java.sql.Connection.TRANSACTION_NONE,
                CATALOG_NAME, schemaOf(ch.id));
    }

    // ── Execution ───────────────────────────────────────────────────────────────

    /**
     * Not supported: there is no server-side prepared statement.
     *
     * <p>OQL has no parameter markers, so a prepare would only be able to return the signature of a
     * query it had already run — which is an execution, not a preparation. Clients that call
     * {@code prepareStatement} without parameters fall back to {@code prepareAndExecute}.
     */
    @Override
    public StatementHandle prepare(ConnectionHandle ch, String sql, long maxRowCount) {
        throw noPreparedStatements();
    }

    /** @deprecated Avatica's older four-argument form; kept because the interface declares it. */
    @Deprecated
    @Override
    public ExecuteResult prepareAndExecute(StatementHandle h, String sql, long maxRowCount,
                                           PrepareCallback callback) throws NoSuchStatementException {
        return prepareAndExecute(h, sql, maxRowCount, AranCursor.DEFAULT_FETCH_SIZE, callback);
    }

    @Override
    public ExecuteResult prepareAndExecute(StatementHandle h, String sql, long maxRowCount,
                                           int maxRowsInFirstFrame, PrepareCallback callback)
            throws NoSuchStatementException {
        AuthenticatedUser caller = currentUser.require();
        String statement = sql == null ? "" : sql.trim();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("Query is empty.");
        }
        Route route = routeOf(statement, schemaOf(h.connectionId));

        Path result = materialize(caller,
                route == Route.GRAPH ? withoutGraphQualifiers(statement) : statement, route);
        AranCursor cursor;
        try {
            cursor = AranCursor.open(result, statement);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the query result: " + e.getMessage(), e);
        }

        StatementKey key = new StatementKey(h.connectionId, h.id);
        // A client that reuses a statement handle without closing it leaves the previous cursor
        // behind; invalidate first so its file is deleted rather than orphaned.
        statements.invalidate(key);
        statements.put(key, new OpenStatement(caller.tenantId().toString(), cursor));

        Frame first;
        try {
            first = cursor.nextFrame(maxRowsInFirstFrame);
        } catch (SQLException e) {
            statements.invalidate(key);
            throw new IllegalStateException("could not read the query result: " + e.getMessage(), e);
        }

        h.signature = cursor.signature();
        return new ExecuteResult(List.of(
                MetaResultSet.create(h.connectionId, h.id, false, cursor.signature(), first)));
    }

    /** @deprecated Avatica's older long-typed form; kept because the interface declares it. */
    @Deprecated
    @Override
    public ExecuteResult execute(StatementHandle h, List<TypedValue> parameterValues, long maxRowCount)
            throws NoSuchStatementException {
        throw noPreparedStatements();
    }

    @Override
    public ExecuteResult execute(StatementHandle h, List<TypedValue> parameterValues,
                                 int maxRowsInFirstFrame) throws NoSuchStatementException {
        throw noPreparedStatements();
    }

    private static UnsupportedOperationException noPreparedStatements() {
        return new UnsupportedOperationException(
                "Aran's JDBC endpoint does not support prepared statements: OQL has no parameter "
                        + "markers. Use a plain Statement.");
    }

    @Override
    public Frame fetch(StatementHandle h, long offset, int fetchMaxRowCount)
            throws NoSuchStatementException, MissingResultsException {
        StatementKey key = new StatementKey(h.connectionId, h.id);
        OpenStatement open = statements.getIfPresent(key);
        if (open == null || open.cursor() == null) {
            throw new MissingResultsException(h);
        }
        // The statement handle is a small integer the client chooses to send; without this check a
        // handle from another session would read rows the caller is not entitled to.
        AuthenticatedUser caller = currentUser.require();
        if (!open.tenantId().equals(caller.tenantId().toString())) {
            log.warn("JDBC fetch for statement {} came from tenant {}, which did not open it",
                    key, caller.tenantId());
            throw new NoSuchStatementException(h);
        }

        AranCursor cursor = open.cursor();
        if (offset != cursor.delivered()) {
            // A forward cursor cannot revisit a frame. Avatica only rewinds when it believes the
            // server lost the result, and syncResults() already told it that cannot be repaired.
            throw new MissingResultsException(h);
        }
        try {
            return cursor.nextFrame(fetchMaxRowCount);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the query result: " + e.getMessage(), e);
        }
    }

    /**
     * Which path a statement takes: the schema it names, else the schema its connection is on.
     *
     * <p>Explicit qualification wins so that a single statement can reach the products without
     * changing the session — that is what a client's own "generate SELECT" menu produces, and what
     * a person pasting one query into a tool set up for the other will type. Everything else
     * follows the connection, and the connection defaults to {@code graph}: the rule never
     * reinterprets a statement that mentions neither schema.
     */
    static Route routeOf(String statement, String connectionSchema) {
        List<int[]> graph = graphQualifiers(statement);
        boolean namesProducts = namesSchema(PRODUCTS_QUALIFIER, statement, graph);
        boolean namesSources = namesSchema(SOURCES_QUALIFIER, statement, graph);
        if (namesProducts && namesSources) {
            // The two qualifiers are text matches, not a parse, so with both present there is no
            // principled winner — and a cross-tier join could not execute anyway: the routes are
            // different engines. Rejecting beats silently picking one and reporting its parser's
            // confusion as the user's error.
            throw new IllegalArgumentException(
                    "The statement names both the sources and products schemas. The two are"
                            + " different engines, so one statement cannot join across them —"
                            + " qualify tables from one schema only.");
        }
        if (!graph.isEmpty()) {
            if (namesProducts || namesSources) {
                String other = namesProducts ? PRODUCTS_SCHEMA : SOURCES_SCHEMA;
                throw new IllegalArgumentException(
                        "The statement names both the graph and " + other + " schemas. The two are"
                                + " different engines, so one statement cannot join across them."
                                + " If '" + other + "' is a class of the graph, qualify every"
                                + " mention of it: graph." + other + ".");
            }
            // Explicit qualification wins over the connection's schema, as products./sources. do.
            return Route.GRAPH;
        }
        if (namesProducts) {
            return Route.PRODUCTS;
        }
        if (namesSources) {
            return Route.SOURCES;
        }
        return switch (connectionSchema == null ? "" : connectionSchema.toLowerCase(java.util.Locale.ROOT)) {
            case PRODUCTS_SCHEMA -> Route.PRODUCTS;
            case SOURCES_SCHEMA -> Route.SOURCES;
            default -> Route.GRAPH;
        };
    }

    /**
     * The statement the graph tier's OQL parser should see: every {@code graph.} qualifier
     * removed, and nothing else touched.
     *
     * <p>It has to happen here, before the parser: OQL reads {@code A.b} as class {@code A},
     * property {@code b}, so {@code graph.products.price} would be a class called {@code graph}.
     */
    static String withoutGraphQualifiers(String statement) {
        List<int[]> spans = graphQualifiers(statement);
        if (spans.isEmpty()) {
            return statement;
        }
        StringBuilder out = new StringBuilder(statement.length());
        int copied = 0;
        for (int[] span : spans) {
            out.append(statement, copied, span[0]);
            copied = span[1];
        }
        return out.append(statement, copied, statement.length()).toString();
    }

    /**
     * The {@code [start, end)} spans of the statement's {@code graph.} qualifiers.
     *
     * <p>A {@code graph.} is a qualifier when it heads a three-part reference
     * ({@code graph.Class.prop}), or a two-part one in a FROM path ({@code FROM graph.Class},
     * {@code -> graph.Class}) — the only places OQL writes a bare class. Anywhere else a two-part
     * {@code graph.x} is a class called {@code graph} with a property {@code x}, which is how that
     * class was written before the qualifier existed and must keep working.
     *
     * <p>Matched against {@link #maskForQualifiers}, because this is the one pattern whose match
     * <em>edits</em> the statement: {@code WHERE P.note = 'graph.x'} losing six characters of its
     * literal would be a wrong answer, not a routing error.
     */
    private static List<int[]> graphQualifiers(String statement) {
        String masked = maskForQualifiers(statement);
        List<int[]> spans = new ArrayList<>();
        java.util.regex.Matcher m = GRAPH_QUALIFIER.matcher(masked);
        while (m.find()) {
            boolean threePart = NAME_THEN_DOT.matcher(masked).region(m.end(), masked.length())
                    .lookingAt();
            boolean inFromPath = FROM_POSITION.matcher(masked.substring(0, m.start())).find();
            if (threePart || inFromPath) {
                spans.add(new int[] {m.start(), m.end()});
            }
        }
        return spans;
    }

    /**
     * Whether {@code qualifier} names its schema anywhere outside a graph-qualified name — in
     * {@code graph.products.price}, {@code products.} belongs to the graph reference.
     *
     * <p>Beside a graph qualifier the match is made on {@link #maskForQualifiers}: there a false
     * match does not pick a route that then fails in the other parser, it rejects the statement
     * with a hint to qualify a class that does not exist ({@code WHERE graph.P.note =
     * 'products.x'}). Without one the text match keeps the over-inclusive behaviour documented on
     * {@link #PRODUCTS_QUALIFIER}.
     */
    private static boolean namesSchema(Pattern qualifier, String statement, List<int[]> graph) {
        String text = graph.isEmpty() ? statement : maskForQualifiers(statement);
        java.util.regex.Matcher m = qualifier.matcher(text);
        while (m.find()) {
            int start = m.start();
            if (graph.stream().noneMatch(span -> span[1] == start)) {
                return true;
            }
        }
        return false;
    }

    /** Quoted, these still name a catalog or schema, so the mask leaves them readable. */
    private static final java.util.Set<String> QUALIFIER_NAMES =
            java.util.Set.of(CATALOG_NAME, GRAPH_SCHEMA, PRODUCTS_SCHEMA, SOURCES_SCHEMA);

    /**
     * {@code statement} with the inside of every single-quoted literal, and of every double-quoted
     * identifier other than a quoted catalog or schema name, replaced by spaces. The length is
     * unchanged, so a match's offsets are the statement's own. {@code 'graph.x.y'} is data and
     * {@code "graph.x.y"} is one column's name; neither is a qualifier.
     */
    private static String maskForQualifiers(String statement) {
        char[] chars = statement.toCharArray();
        int i = 0;
        while (i < chars.length) {
            char quote = chars[i];
            if (quote != '\'' && quote != '"') {
                i++;
                continue;
            }
            int close = i + 1;
            while (close < chars.length) {
                if (chars[close] == quote) {
                    // A doubled quote is an escaped quote, not the end, in literals and identifiers alike.
                    if (close + 1 < chars.length && chars[close + 1] == quote) {
                        close += 2;
                        continue;
                    }
                    break;
                }
                close++;
            }
            // An unterminated quote masks to the end of the statement.
            int end = Math.min(close, chars.length);
            boolean schemaName = quote == '"' && QUALIFIER_NAMES.contains(
                    statement.substring(i + 1, end).toLowerCase(java.util.Locale.ROOT));
            if (!schemaName) {
                java.util.Arrays.fill(chars, i + 1, end, ' ');
            }
            i = end + 1;
        }
        return new String(chars);
    }

    /**
     * Run the statement through the engine and return the parquet file holding its whole result.
     *
     * <p>The two routes differ only in which operation runs and what the parameter is called; both
     * are uncapped, both hand back a file, and both are metered as one JDBC event. That symmetry
     * is the point of doing the split in the engine rather than here.
     */
    private Path materialize(AuthenticatedUser caller, String statement, Route route) {
        // A JDBC statement hands its whole result to the client, so it is egress like an export.
        // Checked here, before the query runs, rather than per fetched frame: refusing halfway
        // through a result set leaves a SQL client with silently partial data.
        licenses.requireWithinEgressLimit(caller);

        long started = System.currentTimeMillis();
        Map<String, String> params = new LinkedHashMap<>();
        String operation;
        switch (route) {
            case PRODUCTS -> {
                operation = MATERIALIZE_PRODUCT;
                params.put("sql", statement);
            }
            case SOURCES -> {
                operation = MATERIALIZE_SOURCE;
                params.put("sql", statement);
            }
            default -> {
                operation = MATERIALIZE;
                params.put("oql", statement);
                params.put("format", "PARQUET");
            }
        }

        OperatorResult result = orchestrator.execute(QUERY_ENGINE, new OperatorRequest(
                caller.tenantId().toString(), caller.userId(), operation, params));

        if (!result.success()) {
            // The engine's own message — a mistyped column reads the same in DBeaver as it does in
            // the workbench.
            throw new IllegalArgumentException(result.errorMessage() != null
                    ? result.errorMessage() : "query failed");
        }
        String path = result.data().get("path");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(operation + " returned no file path");
        }
        // A JDBC statement is one usage event, not one per fetched frame: the cost is the query,
        // and counting frames would make a client's page size look like consumption.
        Path parquet = Path.of(path);
        usage.record(caller, edu.upc.dtak.aran.core.domain.UsageKind.JDBC,
                System.currentTimeMillis() - started,
                Long.parseLong(result.data().getOrDefault("rowCount", "0")), sizeOrNull(parquet));
        return parquet;
    }

    // ── Catalog ─────────────────────────────────────────────────────────────────

    @Override
    public Map<DatabaseProperty, Object> getDatabaseProperties(ConnectionHandle ch) {
        Map<DatabaseProperty, Object> properties = new LinkedHashMap<>();
        properties.put(DatabaseProperty.GET_DATABASE_PRODUCT_NAME, "Aran");
        properties.put(DatabaseProperty.GET_DATABASE_PRODUCT_VERSION, "0.1");
        properties.put(DatabaseProperty.GET_DEFAULT_TRANSACTION_ISOLATION,
                java.sql.Connection.TRANSACTION_NONE);
        // Deliberately empty rather than SQL's: the statement language here is OQL, and listing
        // SQL's function names would invite a client to offer completions that do not parse.
        properties.put(DatabaseProperty.GET_NUMERIC_FUNCTIONS, "");
        properties.put(DatabaseProperty.GET_STRING_FUNCTIONS, "");
        properties.put(DatabaseProperty.GET_SYSTEM_FUNCTIONS, "");
        properties.put(DatabaseProperty.GET_TIME_DATE_FUNCTIONS, "");
        // Empty, which is the honest answer to what this property asks: keywords Aran has that
        // SQL does not. OQL's are all SQL's own (SELECT, FROM, WHERE, GROUP BY, LIMIT …) — what
        // it adds is the path expression `A -[p]-> B`, which is punctuation, not a word, and so
        // has nowhere to be listed. It previously advertised FIND, which no Aran language has.
        properties.put(DatabaseProperty.GET_S_Q_L_KEYWORDS, "");
        return properties;
    }

    @Override
    public MetaResultSet getCatalogs(ConnectionHandle ch) {
        List<ColumnMetaData> columns = List.of(
                MetaImpl.columnMetaData("TABLE_CAT", 0, String.class, false));
        return resultSet(ch, columns, List.of(List.of(CATALOG_NAME)));
    }

    @Override
    public MetaResultSet getSchemas(ConnectionHandle ch, String catalog, Pat schemaPattern) {
        List<ColumnMetaData> columns = List.of(
                MetaImpl.columnMetaData("TABLE_SCHEM", 0, String.class, false),
                MetaImpl.columnMetaData("TABLE_CATALOG", 1, String.class, true));
        List<Object> rows = new ArrayList<>();
        for (String schema : List.of(GRAPH_SCHEMA, PRODUCTS_SCHEMA, SOURCES_SCHEMA)) {
            if (matches(schemaPattern, schema)) {
                rows.add(List.of(schema, CATALOG_NAME));
            }
        }
        return resultSet(ch, columns, rows);
    }

    @Override
    public MetaResultSet getTableTypes(ConnectionHandle ch) {
        List<ColumnMetaData> columns = List.of(
                MetaImpl.columnMetaData("TABLE_TYPE", 0, String.class, false));
        return resultSet(ch, columns, List.of(List.of(TABLE_TYPE)));
    }

    /**
     * The tables of both schemas: integrated classes under {@code graph}, data products —
     * MATERIALIZED or VIRTUAL (Phase 5) — under {@code products}.
     *
     * <p>A class is named by its <em>local</em> name, which is what OQL names it — a client that
     * double-clicks {@code Paper} in a tree and gets {@code SELECT Paper.…} to run is the point.
     * A product is named by {@link ProductCatalog}'s SQL name, for
     * the same reason: the name in the tree has to be the name that executes.
     *
     * <p>A product's REMARKS carry its access mode and freshness — when a MATERIALIZED product was
     * last refreshed, or that a VIRTUAL one is always fresh — and what OQL it came from. A
     * MATERIALIZED product is pull-refreshed — its snapshot is as fresh as its last materialization
     * and never fresher — so a client that shows only the tree must still be able to see that.
     */
    @Override
    public MetaResultSet getTables(ConnectionHandle ch, String catalog, Pat schemaPattern,
                                   Pat tableNamePattern, @Nullable List<String> typeList) {
        List<ColumnMetaData> columns = List.of(
                MetaImpl.columnMetaData("TABLE_CAT", 0, String.class, true),
                MetaImpl.columnMetaData("TABLE_SCHEM", 1, String.class, true),
                MetaImpl.columnMetaData("TABLE_NAME", 2, String.class, false),
                MetaImpl.columnMetaData("TABLE_TYPE", 3, String.class, false),
                MetaImpl.columnMetaData("REMARKS", 4, String.class, true));

        if (typeList != null && !typeList.isEmpty() && !typeList.contains(TABLE_TYPE)) {
            return resultSet(ch, columns, List.of());
        }

        List<Object> rows = new ArrayList<>();
        if (matches(schemaPattern, GRAPH_SCHEMA)) {
            for (JsonNode cls : classes()) {
                String name = cls.path("name").asText("");
                if (matches(tableNamePattern, name)) {
                    rows.add(List.of(CATALOG_NAME, GRAPH_SCHEMA, name, TABLE_TYPE,
                            cls.path("iri").asText("")));
                }
            }
        }
        if (matches(schemaPattern, PRODUCTS_SCHEMA)) {
            for (ProductTable table : productCatalog().tables()) {
                if (matches(tableNamePattern, table.sqlName())) {
                    rows.add(List.of(CATALOG_NAME, PRODUCTS_SCHEMA, table.sqlName(), TABLE_TYPE,
                            productRemarks(table)));
                }
            }
            if (matches(tableNamePattern, PRODUCT_REGISTRY_TABLE)) {
                rows.add(List.of(CATALOG_NAME, PRODUCTS_SCHEMA, PRODUCT_REGISTRY_TABLE, TABLE_TYPE,
                        "Every data product of this tenant with its status, row count, last refresh"
                                + " and originating OQL — including those never materialized, which"
                                + " have no table of their own."));
            }
        }
        if (matches(schemaPattern, SOURCES_SCHEMA)) {
            for (Map.Entry<String, JsonNode> table : sourceTables().entrySet()) {
                if (matches(tableNamePattern, table.getKey())) {
                    rows.add(List.of(CATALOG_NAME, SOURCES_SCHEMA, table.getKey(), TABLE_TYPE,
                            "Source table over dataset " + table.getValue().path("datasetId").asText("")
                                    + " — rows as the wrapper emits them; denial constraints are"
                                    + " enforced in the graph tier, not here."));
                }
            }
        }
        return resultSet(ch, columns, rows);
    }

    /**
     * The sources-tier tables, from the engine catalog's {@code sourceTable} fields: name → the
     * source node that claimed it. A source class feeding several integrated classes appears in
     * each of them with the same name, so the first claim wins; sorted so the tree's order does
     * not depend on payload order.
     */
    private Map<String, JsonNode> sourceTables() {
        Map<String, JsonNode> tables = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode cls : classes()) {
            for (JsonNode source : cls.path("sources")) {
                String name = source.path("sourceTable").asText("");
                if (!name.isEmpty()) {
                    tables.putIfAbsent(name, source);
                }
            }
        }
        return tables;
    }

    /**
     * A source table's columns exactly as its alias view projects them: the distinct source
     * property IRIs, each under its local name, sorted by local name. Derived here the same way
     * {@code DuckDbExecutor}'s alias projection derives them, from the same payload fields — the
     * name in the tree has to be the name that executes.
     */
    private static List<Map.Entry<String, String>> sourceColumnsOf(JsonNode source) {
        Map<String, String> byLocal = new java.util.TreeMap<>();
        for (JsonNode column : source.path("columns")) {
            String iri = column.path("source").asText("");
            if (!iri.isEmpty()) {
                byLocal.putIfAbsent(edu.upc.dtak.aran.core.util.Names.localName(iri), iri);
            }
        }
        return List.copyOf(byLocal.entrySet());
    }

    /**
     * Provenance and freshness, in the one place every schema browser shows.
     *
     * <p>The freshness clause is access-mode-shaped, not one wording with a null gap: a
     * MATERIALIZED product's is a real "last refreshed <em>x</em>" claim (or honestly "never" for
     * one that has not yet run), but a VIRTUAL product (Phase 5) never materializes at all, so
     * "last refreshed never" would read as a product that is behind rather than one that recomputes
     * on every read — dishonest in the opposite direction from a stale timestamp. "Always fresh" is
     * the true statement for that mode.
     */
    private static String productRemarks(ProductTable table) {
        DataProduct product = table.product();
        StringBuilder remarks = new StringBuilder("Data product '").append(product.name()).append("'");
        remarks.append(" · ").append(product.status());
        if (product.status() == DataProductStatus.STALE) {
            remarks.append(" (pinned to an older schema version; still serving its snapshot)");
        }
        if (product.rowCount() != null) {
            remarks.append(" · ").append(product.rowCount()).append(" rows");
        }
        if (product.accessMode() == ProductAccessMode.VIRTUAL) {
            remarks.append(" · access VIRTUAL · always fresh");
        } else {
            remarks.append(" · access MATERIALIZED · last refreshed ")
                    .append(product.materializedAt() == null ? "never" : product.materializedAt());
        }
        if (product.description() != null && !product.description().isBlank()) {
            remarks.append(" · ").append(product.description());
        }
        return remarks.append(" · OQL: ").append(product.oql()).toString();
    }

    /**
     * The columns of both schemas: an integrated class's global properties, a data product's
     * recorded output columns.
     *
     * <p>A <b>class</b>'s columns are all reported as VARCHAR. The catalog records which properties
     * a class has, not what type each carries — types are only known once a query runs and DuckDB
     * describes its result — so a specific type here would be a guess presented as metadata. The
     * types on an actual {@code ResultSet} are real, and come from the parquet the query wrote.
     *
     * <p>A <b>product</b>'s columns are typed, because they are recorded as {@code output_schema}
     * (V29) at definition time — described off the parquet once a MATERIALIZED product's snapshot
     * is written, or planned from the OQL's row type with no execution for a VIRTUAL one (Phase 5,
     * no lake read either way). That is metadata, not a guess, and it is the difference that lets a
     * BI tool treat {@code products} as a warehouse.
     */
    @Override
    public MetaResultSet getColumns(ConnectionHandle ch, String catalog, Pat schemaPattern,
                                    Pat tableNamePattern, Pat columnNamePattern) {
        List<ColumnMetaData> columns = List.of(
                MetaImpl.columnMetaData("TABLE_CAT", 0, String.class, true),
                MetaImpl.columnMetaData("TABLE_SCHEM", 1, String.class, true),
                MetaImpl.columnMetaData("TABLE_NAME", 2, String.class, false),
                MetaImpl.columnMetaData("COLUMN_NAME", 3, String.class, false),
                MetaImpl.columnMetaData("DATA_TYPE", 4, Integer.class, false),
                MetaImpl.columnMetaData("TYPE_NAME", 5, String.class, false),
                MetaImpl.columnMetaData("COLUMN_SIZE", 6, Integer.class, true),
                MetaImpl.columnMetaData("NULLABLE", 7, Integer.class, false),
                MetaImpl.columnMetaData("REMARKS", 8, String.class, true),
                MetaImpl.columnMetaData("ORDINAL_POSITION", 9, Integer.class, false));

        List<Object> rows = new ArrayList<>();
        if (matches(schemaPattern, GRAPH_SCHEMA)) {
            for (JsonNode cls : classes()) {
                String table = cls.path("name").asText("");
                if (!matches(tableNamePattern, table)) {
                    continue;
                }
                int ordinal = 0;
                // A property reached through more than one source is one column, not one per source.
                Map<String, String> properties = new LinkedHashMap<>();
                for (JsonNode source : cls.path("sources")) {
                    for (JsonNode column : source.path("columns")) {
                        properties.putIfAbsent(column.path("globalName").asText(""),
                                column.path("global").asText(""));
                    }
                }
                for (Map.Entry<String, String> property : properties.entrySet()) {
                    ordinal++;
                    if (!matches(columnNamePattern, property.getKey())) {
                        continue;
                    }
                    rows.add(List.of(CATALOG_NAME, GRAPH_SCHEMA, table, property.getKey(),
                            Types.VARCHAR, "VARCHAR", Integer.MAX_VALUE,
                            java.sql.DatabaseMetaData.columnNullable, property.getValue(), ordinal));
                }
            }
        }
        if (matches(schemaPattern, PRODUCTS_SCHEMA)) {
            for (ProductTable table : productCatalog().tables()) {
                if (!matches(tableNamePattern, table.sqlName())) {
                    continue;
                }
                int ordinal = 0;
                for (ProductColumn column : table.columns()) {
                    ordinal++;
                    if (!matches(columnNamePattern, column.name())) {
                        continue;
                    }
                    // Real types, unlike the graph's: a product's columns were described by DuckDB
                    // when the snapshot was written, and that description is in the registry.
                    rows.add(List.of(CATALOG_NAME, PRODUCTS_SCHEMA, table.sqlName(), column.name(),
                            column.jdbcType(), column.typeName(), Integer.MAX_VALUE,
                            java.sql.DatabaseMetaData.columnNullable, "", ordinal));
                }
            }
            if (matches(tableNamePattern, PRODUCT_REGISTRY_TABLE)) {
                int ordinal = 0;
                for (Map.Entry<String, Integer> column : PRODUCT_REGISTRY_COLUMNS.entrySet()) {
                    ordinal++;
                    if (!matches(columnNamePattern, column.getKey())) {
                        continue;
                    }
                    rows.add(List.of(CATALOG_NAME, PRODUCTS_SCHEMA, PRODUCT_REGISTRY_TABLE,
                            column.getKey(), column.getValue(),
                            typeNameOf(column.getValue()), Integer.MAX_VALUE,
                            java.sql.DatabaseMetaData.columnNullable, "", ordinal));
                }
            }
        }
        if (matches(schemaPattern, SOURCES_SCHEMA)) {
            // VARCHAR like the graph's, and for the same reason: the catalog records which
            // columns a wrapper exposes, not their types — a specific type here would be a guess
            // presented as metadata. The REMARKS carry the source property IRI, which is the
            // column's identity in the schema graph.
            for (Map.Entry<String, JsonNode> table : sourceTables().entrySet()) {
                if (!matches(tableNamePattern, table.getKey())) {
                    continue;
                }
                int ordinal = 0;
                for (Map.Entry<String, String> column : sourceColumnsOf(table.getValue())) {
                    ordinal++;
                    if (!matches(columnNamePattern, column.getKey())) {
                        continue;
                    }
                    rows.add(List.of(CATALOG_NAME, SOURCES_SCHEMA, table.getKey(), column.getKey(),
                            Types.VARCHAR, "VARCHAR", Integer.MAX_VALUE,
                            java.sql.DatabaseMetaData.columnNullable, column.getValue(), ordinal));
                }
            }
        }
        return resultSet(ch, columns, rows);
    }

    // ── Join metadata (sources tier) ────────────────────────────────────────────

    /**
     * The accepted joins as JDBC key metadata, on the sources tier only.
     *
     * <p>A tier whose purpose is "write your own joins" that cannot say what joins is strictly
     * worse than {@code graph} at its own job, so this is the one place Aran reports keys. The
     * rows come from {@code MappingCatalog.JoinProperty} via the engine catalog: for each join
     * edge, every source table of the left class references every source table of the right
     * class on the source columns mapped to the join's global key columns. These are accepted
     * <em>join</em> hypotheses, not uniqueness guarantees — which is why
     * {@code getPrimaryKeys} stays empty (see {@link ReadOnlyMeta}) while the FK shape, which
     * only claims "these columns join", is reported.
     */
    @Override
    public MetaResultSet getImportedKeys(ConnectionHandle ch, String catalog, String schema,
                                         String table) {
        return keysResultSet(ch, schema, key -> table.equals(key.get(6)));
    }

    @Override
    public MetaResultSet getExportedKeys(ConnectionHandle ch, String catalog, String schema,
                                         String table) {
        return keysResultSet(ch, schema, key -> table.equals(key.get(2)));
    }

    private MetaResultSet keysResultSet(ConnectionHandle ch, @Nullable String schema,
                                        java.util.function.Predicate<List<Object>> keep) {
        List<ColumnMetaData> columns = List.of(
                MetaImpl.columnMetaData("PKTABLE_CAT", 0, String.class, true),
                MetaImpl.columnMetaData("PKTABLE_SCHEM", 1, String.class, true),
                MetaImpl.columnMetaData("PKTABLE_NAME", 2, String.class, false),
                MetaImpl.columnMetaData("PKCOLUMN_NAME", 3, String.class, false),
                MetaImpl.columnMetaData("FKTABLE_CAT", 4, String.class, true),
                MetaImpl.columnMetaData("FKTABLE_SCHEM", 5, String.class, true),
                MetaImpl.columnMetaData("FKTABLE_NAME", 6, String.class, false),
                MetaImpl.columnMetaData("FKCOLUMN_NAME", 7, String.class, false),
                MetaImpl.columnMetaData("KEY_SEQ", 8, Integer.class, false),
                MetaImpl.columnMetaData("UPDATE_RULE", 9, Integer.class, false),
                MetaImpl.columnMetaData("DELETE_RULE", 10, Integer.class, false),
                MetaImpl.columnMetaData("FK_NAME", 11, String.class, true),
                MetaImpl.columnMetaData("PK_NAME", 12, String.class, true),
                MetaImpl.columnMetaData("DEFERRABILITY", 13, Integer.class, false));
        if (schema != null && !schema.isBlank() && !schema.equalsIgnoreCase(SOURCES_SCHEMA)) {
            // Only the sources tier reports keys; graph and products stay empty as before.
            return resultSet(ch, columns, List.of());
        }
        List<Object> rows = new ArrayList<>();
        for (List<Object> key : sourceJoinKeys()) {
            if (keep.test(key)) {
                rows.add(key);
            }
        }
        return resultSet(ch, columns, rows);
    }

    /** Every join edge as (PK side = right class's sources, FK side = left class's sources). */
    private List<List<Object>> sourceJoinKeys() {
        List<JsonNode> classes = classes();
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode cls : classes) {
            byName.putIfAbsent(cls.path("name").asText(""), cls);
        }
        // Re-read the payload for joinProperties; classes() already parsed it once per call, so
        // reuse its nodes and fetch the joins from the same catalog JSON.
        List<List<Object>> keys = new ArrayList<>();
        for (JsonNode join : joinProperties()) {
            JsonNode left = byName.get(join.path("leftClass").asText(""));
            JsonNode right = byName.get(join.path("rightClass").asText(""));
            if (left == null || right == null) {
                continue;
            }
            String joinName = join.path("name").asText("");
            String leftCol = join.path("leftCol").asText("");
            String rightCol = join.path("rightCol").asText("");
            for (JsonNode fkSource : left.path("sources")) {
                String fkTable = fkSource.path("sourceTable").asText("");
                String fkColumn = sourceColumnFor(fkSource, leftCol);
                if (fkTable.isEmpty() || fkColumn == null) {
                    continue;
                }
                for (JsonNode pkSource : right.path("sources")) {
                    String pkTable = pkSource.path("sourceTable").asText("");
                    String pkColumn = sourceColumnFor(pkSource, rightCol);
                    if (pkTable.isEmpty() || pkColumn == null) {
                        continue;
                    }
                    keys.add(List.of(CATALOG_NAME, SOURCES_SCHEMA, pkTable, pkColumn,
                            CATALOG_NAME, SOURCES_SCHEMA, fkTable, fkColumn,
                            1, java.sql.DatabaseMetaData.importedKeyNoAction,
                            java.sql.DatabaseMetaData.importedKeyNoAction, joinName, "",
                            java.sql.DatabaseMetaData.importedKeyNotDeferrable));
                }
            }
        }
        return keys;
    }

    /** The source-native column name behind a global property, for one source — or null. */
    private static @Nullable String sourceColumnFor(JsonNode source, String globalName) {
        for (JsonNode column : source.path("columns")) {
            if (globalName.equals(column.path("globalName").asText(""))) {
                String iri = column.path("source").asText("");
                if (!iri.isEmpty()) {
                    return edu.upc.dtak.aran.core.util.Names.localName(iri);
                }
            }
        }
        return null;
    }

    /** The active schema's join properties, from the same engine catalog payload as {@link #classes}. */
    private List<JsonNode> joinProperties() {
        AuthenticatedUser caller = currentUser.require();
        OperatorResult result = orchestrator.execute(QUERY_ENGINE, new OperatorRequest(
                caller.tenantId().toString(), caller.userId(), CATALOG, Map.of()));
        if (!result.success()) {
            return List.of();
        }
        String json = result.data().get("catalog");
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<JsonNode> list = new ArrayList<>();
            mapper.readTree(json).path("joinProperties").forEach(list::add);
            return list;
        } catch (Exception e) {
            log.warn("could not read the query-engine catalog payload", e);
            return List.of();
        }
    }

    private static String typeNameOf(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIGINT -> "BIGINT";
            case Types.TIMESTAMP -> "TIMESTAMP";
            default -> "VARCHAR";
        };
    }

    /**
     * The tenant's data products as SQL tables.
     *
     * <p>Read from the registry — {@code data_product} rows — and never from the lake: expanding a
     * schema tree must not download a snapshot per table. That is what the recorded output schema
     * (V29) buys. A product materialized before V29 lists with no columns rather than not at all;
     * it is still queryable, because {@code SELECT *} reads the parquet's own header.
     */
    private ProductCatalog productCatalog() {
        AuthenticatedUser caller = currentUser.require();
        try {
            return products.catalogFor(caller);
        } catch (Exception e) {
            // Same bargain the mediated catalog makes: a client asking what tables exist is
            // browsing, and an empty branch it can recover from beats a failed connection.
            log.warn("JDBC product registry lookup failed", e);
            return ProductCatalog.of(List.of());
        }
    }

    /** The active schema's integrated classes, from the query engine's own catalog view. */
    private List<JsonNode> classes() {
        AuthenticatedUser caller = currentUser.require();
        OperatorResult result = orchestrator.execute(QUERY_ENGINE, new OperatorRequest(
                caller.tenantId().toString(), caller.userId(), CATALOG, Map.of()));
        if (!result.success()) {
            // A client asking what tables exist is browsing, not querying; an empty tree it can
            // recover from beats a failed connection it cannot.
            log.warn("JDBC catalog lookup failed: {}", result.errorMessage());
            return List.of();
        }
        String json = result.data().get("catalog");
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode classes = mapper.readTree(json).path("classes");
            List<JsonNode> list = new ArrayList<>();
            classes.forEach(list::add);
            return list;
        } catch (Exception e) {
            log.warn("could not read the query-engine catalog payload", e);
            return List.of();
        }
    }

    /**
     * JDBC metadata pattern matching: {@code %} for any run of characters, {@code _} for one.
     *
     * <p>A null pattern — or a null pattern string — means "no filter", which is what JDBC
     * specifies and what every client relies on when it lists everything.
     */
    static boolean matches(@Nullable Pat pattern, String value) {
        if (pattern == null || pattern.s == null || pattern.s.equals("%")) {
            return true;
        }
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.s.toCharArray()) {
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE)
                .matcher(Objects.requireNonNullElse(value, "")).matches();
    }

    /** Test seam: how many statements are currently held open. */
    long openStatementCount() {
        statements.cleanUp();
        return statements.estimatedSize();
    }

    /**
     * The file's size, or null if it cannot be read.
     *
     * <p>Null rather than zero: "we did not measure it" and "nothing was transferred" are
     * different facts, and a stat that averages them is worse than one with a gap.
     */
    private static @Nullable Long sizeOrNull(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return null;
        }
    }
}

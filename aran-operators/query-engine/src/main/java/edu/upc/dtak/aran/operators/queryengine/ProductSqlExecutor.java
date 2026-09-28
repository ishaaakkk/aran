package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.domain.ProductTable;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The passthrough half of the JDBC split: a client's own SQL, run against materialized data
 * products with no rewriting.
 *
 * <p>Everything the mediator does — GAV unfolding, wrapper views, constraint injection, planning —
 * is absent here on purpose. A data product is already the answer to a query; its snapshot is a
 * parquet file with a fixed schema, so the only work between the client's statement and DuckDB is
 * naming: one view per referenced product, then the statement, verbatim.
 *
 * <h2>Where the names come from</h2>
 *
 * <p>{@link ProductCatalog} — the registry, not the mediated schema. Views are created in an
 * attached in-memory catalog {@code aran} under schema {@code products}, which is what makes every
 * qualification a SQL client might emit resolve against the same table:
 * {@code sales}, {@code products.sales} and {@code aran.products.sales} are one view, because
 * {@code USE aran.products} puts it on the search path as well as under its full name. Rewriting
 * the client's identifiers instead would mean parsing (and therefore re-emitting) SQL that
 * DuckDB — not Calcite — is going to execute.
 *
 * <h2>What is registered</h2>
 *
 * <p>Only the products the statement names, matched the way {@link DuckDbExecutor#referencedClasses}
 * matches classes: word-bounded, over-inclusive. A product registered needlessly costs one snapshot
 * fetch; one missed is a "table does not exist" on a table the client was told exists, so the
 * asymmetry decides the direction.
 *
 * <p>{@code products._products} — the registry itself — is always registered, and is built from
 * rows rather than a file. It is how a client sees <em>when</em> each product was last refreshed:
 * Aran's products are pull-refreshed, so a table here is as fresh as its last materialization and
 * never fresher, and that fact has to be queryable rather than implied.
 */
@NullMarked
final class ProductSqlExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProductSqlExecutor.class);

    /** The catalog and schema a client addresses. Mirrored by the JDBC metadata. */
    static final String CATALOG = "aran";
    static final String SCHEMA = "products";
    /** The registry table: one row per data product, materialized or not. */
    static final String REGISTRY_TABLE = "_products";

    /** DuckDB's TIMESTAMP literal format. UTC, because Instant has no zone to preserve. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final UUID tenantId;
    private final ProductCatalog catalog;
    private final SnapshotCache cache;
    private final LakeClient lake;
    private final Connection duckdb;
    private final Path workDir;
    /** Snapshots this executor downloaded itself — cache-owned files are never in here. */
    private final List<Path> tempFiles = new ArrayList<>();

    // ── virtual-product context (Phase 5) ──────────────────────────────────────
    // Null on a purely-materialized statement: a materialized product binds a parquet view and
    // nothing here is touched, which is what keeps that path byte-identical to before Phase 5.
    // Set by withVirtualContext when the operator has determined the statement names a VIRTUAL
    // product, so the graph-tier view context an OQL expansion reads can be installed lazily.
    private @Nullable List<String> graphSetupDdl;
    // Returns a product's expanded OQL→SQL body, or null when that product's OQL no longer plans
    // against the active schema — a null means "skip this view", not "fail the statement".
    private @Nullable Function<DataProduct, @Nullable String> virtualBodyProvider;
    private @Nullable ConnectionSetup virtualConnectionSetup;
    /** The graph-tier views are installed once, on the first VIRTUAL product this executor binds. */
    private boolean graphContextInstalled = false;

    /**
     * @param workDir where a product snapshot the cache cannot serve is downloaded — the query's
     *                own {@link QueryWorkspace}, the only part of the working area this
     *                connection's lockdown allowlists
     */
    ProductSqlExecutor(UUID tenantId, ProductCatalog catalog, SnapshotCache cache, LakeClient lake,
                       Path workDir) throws SQLException {
        this.tenantId = tenantId;
        this.catalog = catalog;
        this.cache = cache;
        this.lake = lake;
        this.workDir = workDir;
        this.duckdb = DuckDbSession.open();
        try (Statement st = duckdb.createStatement()) {
            // An attached in-memory database, not the default one, so the client's own three-part
            // names work: DuckDB's default catalog is called `memory`, and `aran.products.sales`
            // would not resolve against it.
            st.execute("ATTACH ':memory:' AS " + CATALOG);
            st.execute("CREATE SCHEMA " + CATALOG + "." + SCHEMA);
            st.execute("USE " + CATALOG + "." + SCHEMA);
        } catch (SQLException e) {
            closeQuietly();
            throw e;
        }
    }

    /**
     * Supply the context a VIRTUAL product needs to bind (Phase 5). Call before
     * {@link #registerReferencedBy} when — and only when — the statement names a virtual product;
     * a purely-materialized statement leaves this unset so its path stays byte-identical.
     *
     * <p>The operator owns the Calcite/GAV machinery core cannot depend on, so it hands this
     * executor two ready things: {@code graphSetupDdl} is {@code DuckDbExecutor.buildDdl}'s output
     * (the {@code src_}/{@code w_}/{@code u_}/{@code int_} views over the ACTIVE catalog, with
     * enforced denial constraints already spliced in), and {@code virtualBodyProvider} turns a
     * product into its OQL→SQL expansion. {@code connectionSetup} carries the httpfs + S3 secrets a
     * federated object-storage source needs before its {@code src_} view binds, or null when none.
     */
    ProductSqlExecutor withVirtualContext(List<String> graphSetupDdl,
                                          Function<DataProduct, @Nullable String> virtualBodyProvider,
                                          @Nullable ConnectionSetup connectionSetup) {
        this.graphSetupDdl = graphSetupDdl;
        this.virtualBodyProvider = virtualBodyProvider;
        this.virtualConnectionSetup = connectionSetup;
        return this;
    }

    /**
     * Register a view for every product {@code sql} names, plus the registry table.
     *
     * @return the products registered, in catalog order — the caller reports them so a client can
     *         tell which tables its statement actually read
     */
    Set<String> registerReferencedBy(String sql) throws SQLException {
        createRegistryView();
        Set<String> registered = new LinkedHashSet<>();
        for (ProductTable table : catalog.tables()) {
            if (!references(sql, table.sqlName())) {
                continue;
            }
            // A virtual product whose OQL no longer expands is skipped rather than created (see
            // createVirtualProductView), so it is not reported as registered — a statement that
            // genuinely selects from it then fails with DuckDB's own "table not found", scoped to
            // that statement, while one that only word-matched its name is unaffected.
            if (createProductView(table)) {
                registered.add(table.sqlName());
            }
        }
        return registered;
    }

    /**
     * T3.5 (audit JDBC_AVATICA-1): the second layer under the token screen, applied AFTER every
     * view this statement needs is registered and BEFORE the client's SQL executes. This tier
     * runs the most hostile input in the system — arbitrary JDBC client SQL — and was the only
     * SQL-accepting path whose connection had no filesystem lockdown; the screen was
     * load-bearing alone, which {@code DuckDbExecutor}'s own javadoc rejects for every sibling
     * path. Views bind lazily, so files under {@code roots} (the query's own workspace + its
     * tenant's cache directories) stay readable after the lockdown.
     *
     * <p>Skipped — exactly like the mediated tier's own skip rule ({@code DuckDbExecutor}) — when
     * the installed virtual context needs external access: an S3-backed source's httpfs secrets
     * ({@code virtualConnectionSetup}) or a federated read expression in the graph DDL (a table of an
     * attached Postgres/MySQL catalog, {@code iceberg_scan}), which {@code enable_external_access=false} would cut
     * off. There the screen stays the only layer, same fact decided the same way.
     */
    void lockDownUnlessFederated(List<Path> roots) throws Exception {
        // resolverSourceReads, not the whole graphSetupDdl: a per-class wrapper (w_ view) in the
        // graph DDL carries user SQL, so a wrapper projecting the literal 's3://x' would otherwise
        // trip the federation substring test and drop this tier's lockdown (sec-audit F-QUERY-2,
        // same fix as DuckDbExecutor.lockDown). This tier runs the most hostile input in the system.
        // A setup that carries nothing is not federation: the operator's resolver always builds
        // one, so testing for its presence dropped the lockdown for every statement naming a
        // VIRTUAL product, local snapshots or not. buildDdl has run the resolver by now, so the
        // setup is complete and isEmpty() is the real answer.
        if (graphSetupDdl != null
                && ((virtualConnectionSetup != null && !virtualConnectionSetup.isEmpty())
                        || DuckDbExecutor.isFederated(DuckDbExecutor.resolverSourceReads(graphSetupDdl)))) {
            log.debug("Federated/object-storage virtual context — products-tier filesystem"
                    + " lockdown skipped for this statement");
            return;
        }
        if (roots.isEmpty()) {
            // queryFileRoots' degraded mode: no working dir means no allowlist to install.
            return;
        }
        try (Statement st = duckdb.createStatement()) {
            DuckDbExecutor.lockDownFilesystem(st, roots);
        }
    }

    /** Run {@code sql} and write its whole result to {@code target} as parquet. */
    void copyToParquet(String sql, Path target) throws SQLException {
        try (Statement st = duckdb.createStatement()) {
            st.execute("COPY (" + sql + ") TO '"
                    + Names.sqlLiteral(target.toAbsolutePath().toString()) + "' (FORMAT PARQUET)");
        }
    }

    /** The row count of a parquet file this executor wrote. */
    long rowCountOf(Path parquet) throws SQLException {
        try (Statement st = duckdb.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM read_parquet('"
                             + Names.sqlLiteral(parquet.toAbsolutePath().toString()) + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * The columns of a parquet file, as the registry records them.
     *
     * <p>Static, and on its own connection: the mediated {@code materialize-oql} path calls it too,
     * so that a product's recorded output schema comes from one place regardless of which half of
     * the split produced the file.
     */
    static List<ProductColumn> describeParquet(Path parquet) throws SQLException {
        try (Connection c = DuckDbSession.open();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM read_parquet('"
                             + Names.sqlLiteral(parquet.toAbsolutePath().toString()) + "') LIMIT 0")) {
            return describe(rs.getMetaData());
        }
    }

    /** The columns a {@link ResultSetMetaData} describes, in order. */
    static List<ProductColumn> describe(ResultSetMetaData meta) throws SQLException {
        List<ProductColumn> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(new ProductColumn(meta.getColumnLabel(i),
                    meta.getColumnTypeName(i), meta.getColumnType(i)));
        }
        return columns;
    }

    /**
     * Whether {@code sql} names {@code table}.
     *
     * <p>Word-bounded, so {@code sales} does not match {@code sales_2024}, and unanchored, so it
     * matches the name however the client qualified it — {@code products.sales} and
     * {@code aran.products."sales"} both contain the word.
     */
    static boolean references(String sql, String table) {
        return Pattern.compile("\\b" + Pattern.quote(table) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(sql).find();
    }

    /** @return whether a view was actually created (a broken virtual product is skipped). */
    private boolean createProductView(ProductTable table) throws SQLException {
        if (table.product().accessMode() == ProductAccessMode.VIRTUAL) {
            return createVirtualProductView(table);
        }
        createMaterializedProductView(table);
        return true;
    }

    /**
     * Bind a MATERIALIZED product as a view over its parquet snapshot — the path that existed
     * before Phase 5, unchanged.
     */
    private void createMaterializedProductView(ProductTable table) throws SQLException {
        Path parquet = snapshotFile(table);
        try (Statement st = duckdb.createStatement()) {
            st.execute("CREATE VIEW " + qualified(table.sqlName())
                    + " AS SELECT * FROM read_parquet('"
                    + Names.sqlLiteral(parquet.toAbsolutePath().toString()) + "')");
        }
    }

    /**
     * Bind a VIRTUAL product as a live view whose body is its OQL expanded through the GAV pipeline
     * (Phase 5). No snapshot, no lake read for the data — the graph-tier views installed by
     * {@link #ensureGraphContext} are read at query time, so the product is always fresh and any
     * enforced denial constraint on an underlying class applies.
     *
     * <p>The body's own view references ({@code int_}/{@code u_}/{@code src_}) are unqualified and
     * live in {@code aran.main}; this view lives in {@code aran.products} so a client's
     * {@code products.<name>} resolves, and the {@code aran.products} search path includes
     * {@code main}, so the unqualified references still bind (see {@link #ensureGraphContext}).
     *
     * <p><b>Failure isolation.</b> The body provider returns {@code null} when this product's OQL no
     * longer plans against the active schema (a state the design permits — "re-pin = edit the
     * product"). That must never fail a statement that merely word-matched this product's name
     * (e.g. in a string literal): so a null body <em>skips</em> the view with a WARN rather than
     * throwing. A statement that genuinely selects from it then fails at execution with DuckDB's own
     * "table not found", scoped to that one statement, which {@code QueryEngineOperator} turns into a
     * message naming the product and its expansion reason.
     *
     * @return whether the view was created ({@code false} = skipped because its OQL did not expand)
     */
    private boolean createVirtualProductView(ProductTable table) throws SQLException {
        if (graphSetupDdl == null || virtualBodyProvider == null) {
            throw new IllegalStateException("virtual product '" + table.sqlName()
                    + "' referenced without a virtual execution context — withVirtualContext must be"
                    + " called before registering it");
        }
        String body = virtualBodyProvider.apply(table.product());
        if (body == null) {
            log.warn("Skipping virtual data product '{}': its OQL did not expand against the active"
                    + " schema. A statement that selects from it will fail with 'table not found'.",
                    table.sqlName());
            return false;
        }
        ensureGraphContext();
        try (Statement st = duckdb.createStatement()) {
            st.execute("CREATE VIEW " + qualified(table.sqlName()) + " AS (" + body + ")");
        }
        return true;
    }

    /**
     * Install the graph-tier view context on this connection, once, so the virtual products'
     * OQL→SQL bodies have their {@code src_}/{@code w_}/{@code u_}/{@code int_} views to read.
     *
     * <p><b>Where the views land, and why it is {@code aran.main} not {@code aran.products}.</b>
     * {@code buildDdl}'s statements are written for the DuckDB default context: unqualified view
     * names, a {@code CREATE SCHEMA IF NOT EXISTS sources}, and {@code sources.<alias> AS SELECT *
     * FROM main.<alias>} references. Run under this executor's {@code USE aran.products}, the
     * {@code main.<alias>} references resolve to {@code aran.main.<alias>} — which does not exist,
     * because the alias landed in {@code aran.products} — and the setup fails. Switching to
     * {@code aran.main} first makes the whole {@code buildDdl} text resolve exactly as it does on
     * the graph tier's own fresh connection (whose default is {@code memory.main}), byte-for-byte,
     * with no edit to {@code buildDdl}. Products stay in {@code aran.products}; the search path
     * there includes {@code main}, so a virtual body's unqualified {@code int_}/{@code u_}
     * references bind to {@code aran.main}. Verified against DuckDB 1.5.
     */
    private void ensureGraphContext() throws SQLException {
        if (graphContextInstalled) {
            return;
        }
        List<String> ddl = graphSetupDdl;
        if (ddl == null) {
            throw new IllegalStateException("no graph setup DDL for virtual product execution");
        }
        try (Statement st = duckdb.createStatement()) {
            // httpfs + per-repository S3 secrets, before any src_ view over read_parquet('s3://…')
            // binds (DuckDB may read the object footer at view-creation time). Never logged.
            if (virtualConnectionSetup != null) {
                virtualConnectionSetup.applyTo(st);
            }
            // postgres/mysql scanner extensions, only if a federated source is actually in the DDL.
            try {
                DuckDbExecutor.ensureFederationExtensions(st, ddl);
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                throw new SQLException("failed to load federation extensions for a virtual product", e);
            }
            st.execute("USE " + CATALOG + ".main");
            for (String statement : ddl) {
                st.execute(statement);
            }
            st.execute("USE " + CATALOG + "." + SCHEMA);
        }
        graphContextInstalled = true;
    }

    /**
     * A local parquet file holding this product's snapshot.
     *
     * <p>Cache first, exactly as the mediated path resolves a source snapshot: a product snapshot
     * is immutable — re-materializing mints a new one — so a hit is always valid, and a repeated
     * BI query against the same product costs no lake round trip at all.
     */
    private Path snapshotFile(ProductTable table) {
        String snapshotId = table.snapshotId();
        Optional<Path> cached = cache.get(tenantId, snapshotId, "PARQUET");
        if (cached.isPresent()) {
            return cached.get();   // cache-owned: not tracked for deletion
        }
        try {
            Path tmp = Files.createTempFile(workDir, "aran-product-" + table.sqlName() + "-", ".parquet");
            tempFiles.add(tmp);    // tracked before the write, so close() cleans up a failed one
            lake.downloadSnapshotTo(tenantId, snapshotId, tmp);
            return tmp;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the snapshot of data product '"
                    + table.sqlName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * The registry as a table: one row per product, whether or not it has ever been materialized.
     *
     * <p>Built from literals rather than a file because it is registry state, not data — and
     * because a client must be able to ask "when was this last refreshed?" without that question
     * itself depending on a lake round trip.
     */
    private void createRegistryView() throws SQLException {
        List<ProductTable> all = catalog.all();
        StringBuilder body = new StringBuilder();
        if (all.isEmpty()) {
            // A typed empty table. Casting each column keeps `SELECT * FROM products._products`
            // describable when a tenant has no products at all, instead of failing to bind.
            body.append("SELECT CAST(NULL AS VARCHAR) AS product, CAST(NULL AS VARCHAR) AS status, ")
                    .append("CAST(NULL AS VARCHAR) AS access, ")
                    .append("CAST(NULL AS TIMESTAMP) AS last_refreshed, ")
                    .append("CAST(NULL AS BIGINT) AS row_count, ")
                    .append("CAST(NULL AS VARCHAR) AS project_id, CAST(NULL AS VARCHAR) AS oql ")
                    .append("WHERE FALSE");
        } else {
            body.append("SELECT * FROM (VALUES ");
            for (int i = 0; i < all.size(); i++) {
                DataProduct product = all.get(i).product();
                if (i > 0) {
                    body.append(", ");
                }
                // last_refreshed/row_count are naturally NULL here for a VIRTUAL product
                // (materializedAt/rowCount are permanently null on one — see DataProduct's
                // compact constructor and virtualReady) — no VIRTUAL-specific branch needed.
                body.append('(')
                        .append(text(all.get(i).sqlName())).append(", ")
                        .append(text(product.status().name())).append(", ")
                        .append(text(product.accessMode().name())).append(", ")
                        .append(timestamp(product.materializedAt())).append(", ")
                        .append(product.rowCount() == null
                                ? "CAST(NULL AS BIGINT)" : product.rowCount() + "::BIGINT").append(", ")
                        .append(text(product.projectId().toString())).append(", ")
                        .append(text(product.oql()))
                        .append(')');
            }
            body.append(") AS t(product, status, access, last_refreshed, row_count, project_id, oql)");
        }
        try (Statement st = duckdb.createStatement()) {
            st.execute("CREATE VIEW " + qualified(REGISTRY_TABLE) + " AS " + body);
        }
    }

    private static String qualified(String table) {
        return CATALOG + "." + SCHEMA + ".\"" + table.replace("\"", "\"\"") + "\"";
    }

    private static String text(String value) {
        return "'" + Names.sqlLiteral(value) + "'";
    }

    private static String timestamp(java.time.@org.jspecify.annotations.Nullable Instant instant) {
        return instant == null
                ? "CAST(NULL AS TIMESTAMP)"
                : "TIMESTAMP '" + TIMESTAMP.format(instant) + "'";
    }

    @Override
    public void close() {
        closeQuietly();
        for (Path tmp : tempFiles) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception e) {
                log.warn("could not delete product snapshot temp file {}", tmp, e);
            }
        }
        tempFiles.clear();
    }

    private void closeQuietly() {
        try {
            duckdb.close();
        } catch (Exception ignore) {
            // Best effort; the temp files still have to go.
        }
    }
}

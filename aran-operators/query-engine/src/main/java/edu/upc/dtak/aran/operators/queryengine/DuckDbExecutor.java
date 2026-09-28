package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.core.util.ConstraintRewriter;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.plan.XsdTypes;
import org.apache.calcite.sql.type.SqlTypeName;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@NullMarked
public final class DuckDbExecutor implements AutoCloseable {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DuckDbExecutor.class);

    public interface SnapshotResolver {
        /**
         * Resolves a dataset ID to a DuckDB read function call, e.g. read_json_auto('path')
         */
        String resolveReadExpression(String datasetId);
    }

    public record QueryResult(List<String> headers, List<List<Object>> rows) {}

    /** Beyond this, a JSON number cannot survive a JavaScript {@code Number} round-trip. */
    private static final long JS_MAX_SAFE_INTEGER = 9007199254740991L; // 2^53 - 1

    /**
     * Converts a JDBC value into something that survives JSON with its type intact.
     *
     * <p>Every value used to go through {@code toString()}, so an INTEGER column arrived at the
     * browser as {@code "42"}. Nothing downstream could tell a number from text: the workbench
     * sorted numeric columns lexicographically ({@code "100" < "20" < "9"}), could not
     * right-align them, and any charting would have had to re-parse strings that were numbers
     * three layers earlier.
     *
     * <p>Types are preserved only where JSON can represent them <em>without loss</em>:
     * <ul>
     *   <li>{@code Integer}/{@code Short}/{@code Byte}, {@code Boolean}, {@code String} pass through.</li>
     *   <li>{@code Long} passes through only within JavaScript's safe-integer range; a larger
     *       BIGINT becomes a string, because {@code JSON.parse} would silently round it.</li>
     *   <li>{@code Double}/{@code Float} pass through unless NaN or infinite, which are not
     *       legal JSON numbers.</li>
     *   <li>{@code BigInteger} (HUGEINT) takes the same range test as {@code Long}: a value JSON
     *       can hold exactly passes through as a number, a larger one is stringified. HUGEINT
     *       used to be stringified unconditionally on the grounds that it "routinely exceeds
     *       double precision" — true of the type's range, false of the values actually seen.
     *       Two things made that costly: DuckDB types {@code SUM} over integers as HUGEINT, so
     *       every aggregate left here as text, and 1.5 began inferring HUGEINT for the small
     *       integers 1.3 called BIGINT ({@code read_json_auto} on {@code {"h_index": 5}}), which
     *       extended it to whole columns. The workbench's own comparator coerces numeric strings,
     *       so its result table still sorted correctly — but every other consumer of
     *       {@code /api/query} (the Python SDK, charting, anything reading the JSON directly) was
     *       handed text where this method's contract promises a number.</li>
     *   <li>{@code BigDecimal} stays stringified: unlike HUGEINT it carries a scale, and a
     *       value that survives the magnitude test can still lose its last digit to
     *       IEEE-754.</li>
     *   <li>Dates, timestamps, times, arrays, structs and intervals are stringified — JSON has
     *       no native form, and their {@code toString()} is already the readable one.</li>
     * </ul>
     */
    static Object jsonSafe(Object val) {
        return switch (val) {
            case null -> null;
            case Integer i -> i;
            case Short s -> s;
            case Byte b -> b;
            case Boolean b -> b;
            case String s -> s;
            // Not Math.abs: abs(Long.MIN_VALUE) is itself negative, so the old form let exactly
            // that one value through as a number JSON.parse would round.
            case Long l -> withinJsSafeRange(l) ? l : l.toString();
            case java.math.BigInteger b ->
                    b.bitLength() < Long.SIZE && withinJsSafeRange(b.longValue())
                            ? (Object) b.longValue()
                            : b.toString();
            case Double d -> d.isNaN() || d.isInfinite() ? d.toString() : d;
            case Float f -> f.isNaN() || f.isInfinite() ? f.toString() : f;
            default -> val.toString();
        };
    }

    /** Can JSON hold {@code value} exactly? Beyond 2^53-1 a JS {@code Number} cannot. */
    private static boolean withinJsSafeRange(long value) {
        return value >= -JS_MAX_SAFE_INTEGER && value <= JS_MAX_SAFE_INTEGER;
    }

    private final MappingCatalog catalog;
    private final SnapshotResolver snapshots;
    private final Map<String, List<String>> enforcedByClass;
    private @Nullable Set<String> requiredClasses;
    private @Nullable ConnectionSetup connectionSetup;
    private Connection conn;

    public DuckDbExecutor(MappingCatalog catalog, SnapshotResolver snapshots) {
        this(catalog, snapshots, Map.of());
    }

    /**
     * Restrict {@link #prepare()} to the classes {@code sql} actually references, so the snapshots
     * behind every other class are never fetched. Call before {@code prepare()}; without it the
     * whole catalog is materialised.
     */
    public DuckDbExecutor onlyClassesReferencedBy(String sql) {
        this.requiredClasses = referencedClasses(catalog, sql);
        return this;
    }

    /**
     * The credential-bearing setup to run before the query — {@code httpfs} plus one
     * {@code CREATE SECRET} per object-storage repository this query touches. Populated by the
     * resolver as it resolves each dataset (the same object it appends its temp files to), so it is
     * complete by the time {@link #prepare()} builds the DDL and applies it. Call before
     * {@code prepare()}; a query touching no object storage needs it not at all.
     *
     * <p>The statements it runs are never logged — see {@link ConnectionSetup}.
     */
    public DuckDbExecutor withConnectionSetup(@Nullable ConnectionSetup connectionSetup) {
        this.connectionSetup = connectionSetup;
        return this;
    }

    /**
     * The integrated classes whose DuckDB views appear in {@code sql}, by name.
     *
     * <p>Matching on the generated view names rather than parsing is deliberate: both entry points
     * — planner-generated SQL naming {@code u_<ds>_<class>}, and hand-written SQL naming
     * {@code int_<class>} — end up as a statement string, so one matcher covers both, and it does
     * not care which SQL dialect or quoting style produced it.
     *
     * <p>The match is word-bounded so {@code int_Paper} does not match {@code int_PaperDraft}, and
     * it deliberately errs towards <em>over</em>-inclusion: a view name appearing inside a string
     * literal builds a view nobody reads, which costs a snapshot fetch. Under-inclusion would
     * produce "table does not exist", so the asymmetry matters and the safe direction is the one
     * chosen.
     */
    static Set<String> referencedClasses(MappingCatalog catalog, String sql) {
        // Sources-tier alias names count as references too, credited to the SAME class that
        // buildDdl picks to emit the alias (aliasBackingClasses) — required, or a statement
        // naming only the alias would materialise nothing and fail with "table not found".
        Map<String, String> backingByPair = aliasBackingClasses(catalog);
        Map<String, List<String>> aliasesByClass = new LinkedHashMap<>();
        for (edu.upc.dtak.aran.core.domain.SourceCatalog.SourceTable t
                : catalog.sourceCatalog().tables()) {
            String backing = backingByPair.get(pairKey(t.datasetId(), t.sourceClassIri()));
            if (backing != null) {
                aliasesByClass.computeIfAbsent(backing, k -> new ArrayList<>()).add(t.sqlName());
            }
        }

        Set<String> required = new java.util.LinkedHashSet<>();
        for (MappingCatalog.IntegratedClass cls : catalog.classes().values()) {
            String classHash = Names.sqlName(cls.iri());
            List<String> names = new ArrayList<>(List.of(
                    "int_" + classHash, "q_" + classHash));
            names.addAll(aliasesByClass.getOrDefault(cls.iri(), List.of()));
            for (MappingCatalog.SourceView source : cls.sources()) {
                String dsHash = Names.sqlName(source.datasetId());
                names.add("u_" + dsHash + "_" + classHash);
                names.add("w_" + dsHash + "_" + classHash);
                names.add("src_" + dsHash);
            }
            for (String name : names) {
                if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b")
                        .matcher(sql).find()) {
                    required.add(cls.iri());
                    break;
                }
            }
        }
        return required;
    }

    /**
     * @param enforcedByClass integrated-class IRI → the enforced constraint expressions to
     *                        apply, each a SQL boolean predicate written against the class's
     *                        global-property <em>local names</em> (rewritten to the int_
     *                        view's IRI-named columns here). Classes absent from the map are
     *                        materialised unfiltered, exactly as before M6.
     */
    public DuckDbExecutor(MappingCatalog catalog, SnapshotResolver snapshots,
                          Map<String, List<String>> enforcedByClass) {
        this.catalog = catalog;
        this.snapshots = snapshots;
        this.enforcedByClass = enforcedByClass;
        try {
            this.conn = DuckDbSession.open();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize DuckDB", e);
        }
    }

    /**
     * Does this DDL reach the network — a remote database via a scanner, or an object store via
     * httpfs? Read by two callers that must agree: the one that installs the scanner extensions,
     * and {@link #lockDown}, which cannot confine a query that needs the network. Package-private so
     * the agreement is testable without a live extension download.
     *
     * <p>Why it is decided from the DDL rather than up front: those scanners live in DuckDB
     * extensions that {@code INSTALL} fetches over the network on first use. Installing them
     * unconditionally made every query — including the overwhelming majority that only read local
     * snapshot files — depend on DuckDB's extension CDN being reachable, and made a cold container
     * fail its first query outright.
     *
     * <p>Four signals, all "external access is in play, so {@code enable_external_access=false}
     * would cut the query off, not just confine it":
     * <ul>
     *   <li>{@code postgres_scan} — the table-function form of a Postgres read. No engine emits
     *       it any more (Postgres reads an attached catalog, below, so no view body carries its
     *       DSN); it stays a signal because reading it as local would lock a live read out.</li>
     *   <li>{@link FederatedScan#ATTACH_ALIAS_PREFIX} — a table of an attached Postgres, MySQL or
     *       MariaDB catalog. No function name marks that read, so the reserved alias does;
     *       {@code WrapperSqlValidator} refuses it in every screened statement, so only the
     *       resolver can emit it.</li>
     *   <li>{@code s3://} — an object-storage read over httpfs ({@code read_parquet('s3://…')} and
     *       friends), emitted only by the query-engine resolver's object-storage branch. The
     *       secret that authenticates it is created separately on the connection (never in this
     *       DDL); this predicate only decides that the lockdown must be skipped.</li>
     *   <li>{@code iceberg_scan} — a live Iceberg table.</li>
     * </ul>
     *
     * <p>Like the {@code *_scan} signals this is a substring test, so a wrapper that put the literal
     * text {@code s3://} in a projection would also trip it — but a wrapper cannot <em>call</em> a
     * reader ({@code WrapperSqlValidator} forbids {@code read_*} and a string literal in table
     * position), so the worst a crafted literal buys is losing the defence-in-depth lockdown on a
     * query whose reads are still confined to local snapshots by the resolver. The SQL screen, not
     * this predicate, is the fail-closed gate. This is the same accepted property the {@code *_scan}
     * substrings already carried.
     */
    static boolean isFederated(List<String> ddl) {
        for (String statement : ddl) {
            String lower = statement.toLowerCase(Locale.ROOT);
            if (lower.contains("postgres_scan") || lower.contains(FederatedScan.ATTACH_ALIAS_PREFIX)
                    || lower.contains("s3://") || lower.contains("iceberg_scan")) {
                return true;
            }
        }
        return false;
    }

    // Package-private so ProductSqlExecutor can install the same scanner extensions before a
    // virtual product's graph-tier src_ view over a federated source binds — one copy of the
    // INSTALL/LOAD decision, not two that can drift. An attached MySQL catalog is not here: its
    // extension load is part of its own setup (ConnectionSetup.addAttachedCatalog), which must
    // run before its src_ view can bind at all.
    static void ensureFederationExtensions(Statement stmt, List<String> ddl) throws Exception {
        boolean needsPostgres = false;
        boolean needsIceberg = false;
        for (String statement : ddl) {
            String lower = statement.toLowerCase(Locale.ROOT);
            if (lower.contains("postgres_scan")) needsPostgres = true;
            if (lower.contains("iceberg_scan")) needsIceberg = true;
        }
        if (needsPostgres) {
            stmt.execute("INSTALL postgres;");
            stmt.execute("LOAD postgres;");
        }
        if (needsIceberg) {
            // iceberg_scan reads the metadata/manifests/files itself, over httpfs whatever the
            // scheme (s3:// or http(s)://) — so httpfs is loaded here even when no S3 secret was
            // registered on the connection (ConnectionSetup loads it only when it carries one).
            stmt.execute("INSTALL httpfs;");
            stmt.execute("LOAD httpfs;");
            stmt.execute("INSTALL iceberg;");
            stmt.execute("LOAD iceberg;");
        }
    }

    /**
     * The directories this connection may touch once {@link #prepare()} has locked it down.
     * Empty means no lockdown — see {@link #restrictFilesystemTo}.
     */
    private List<Path> allowedDirectories = List.of();

    /**
     * Confine the connection's filesystem access to {@code directories} — everything Aran
     * legitimately reads or writes, and nothing else.
     *
     * <p>This is the second layer under the SQL screen, and it exists because the first layer is
     * a screen: {@code WrapperSqlValidator} decides from the statement text what a query is
     * allowed to do, and a screen that has to keep up with an engine's syntax will eventually be
     * wrong about something. DuckDB's replacement scan already proved that once — a bare string
     * literal in {@code FROM} position reads a file while naming no function at all. The screen
     * now catches it; this makes the next miss cost a permission error instead of {@code /etc}.
     *
     * <p>Call before {@link #prepare()}. Without it the connection is unrestricted, which is what
     * the tests and the {@code explain} path — neither of which executes against real files —
     * want.
     */
    public DuckDbExecutor restrictFilesystemTo(List<Path> directories) {
        this.allowedDirectories = List.copyOf(directories);
        return this;
    }

    private @Nullable WrapperOutputCache wrapperCache;
    private Map<String, WrapperOutputCache.Spec> wrapperCacheSpecs = Map.of();

    /**
     * Serve eligible {@code w_} views from the {@link WrapperOutputCache} — the per-class,
     * join-key-sorted Parquet materialisation of the wrapper's output. {@code specs} is keyed by
     * wrapper view name and computed by the operator, which holds the catalog and the graph's
     * snapshot ids; classes absent from it (EXPANDED, identity wrappers, no snapshot) keep their
     * logical wrapper view untouched. Call before {@link #prepare()}; without it — the default,
     * and what {@code explain} and every pre-cache test gets — nothing changes.
     */
    public DuckDbExecutor withWrapperOutputCache(@Nullable WrapperOutputCache cache,
                                                 Map<String, WrapperOutputCache.Spec> specs) {
        this.wrapperCache = cache;
        this.wrapperCacheSpecs = Map.copyOf(specs);
        return this;
    }

    public void prepare() {
        try (Statement stmt = conn.createStatement()) {
            // Build the DDL first: resolving each dataset is what fills connectionSetup (an s3://
            // read expression for an object-storage source, and its CREATE SECRET registered as a
            // side effect), so it is complete by the time we apply it below.
            List<String> ddl = buildDdl(catalog, snapshots, enforcedByClass, requiredClasses);
            ensureFederationExtensions(stmt, ddl);
            // httpfs + the per-repo S3 secrets, before any CREATE VIEW over read_parquet('s3://…')
            // binds — DuckDB may read the object's footer at view-creation time, which needs both.
            // ConnectionSetup never logs the secret-bearing statements it runs.
            if (connectionSetup != null) {
                connectionSetup.applyTo(stmt);
            }
            for (String statement : ddl) {
                stmt.execute(statement);
            }
            // After the DDL (a fill reads through the just-bound src_/w_ views) and before the
            // lockdown (a fill WRITES into the cache directory; afterwards the query only reads
            // it, which the allowlist covers because the operator puts the cache root in
            // queryFileRoots). Rebinding w_ after int_/u_/alias exist is safe: DuckDB views
            // resolve names at query time, so every downstream view sees the rebound body.
            applyWrapperOutputCache(stmt, ddl);
            lockDown(stmt, ddl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to prepare DuckDB executor: " + rootMessage(e), e);
        }
    }

    /**
     * Rebind each spec'd {@code w_} view to its cached, sorted Parquet — filling on a miss by
     * executing the wrapper once ({@code COPY (SELECT * FROM w_… ORDER BY …)}) on this same
     * connection, which is the only place the {@code src_} binding it needs exists.
     *
     * <p>Two guards, both fail-open to the logical wrapper view (a cache must not be able to
     * break or wrongly freeze a query):
     * <ul>
     *   <li>Only views this DDL actually defined participate — touching a pruned class's spec
     *       would make {@code resolveReadExpression} download a snapshot nothing reads.</li>
     *   <li>The dataset's resolver expression is re-checked with {@link #isFederated}: a live
     *       scan is never cached, whatever the spec claims, because a frozen copy of a federated
     *       source is a wrong answer, not a slow one.</li>
     * </ul>
     *
     * <p>The rows are identical by construction (the cache key binds snapshot + wrapper text), so
     * the logical plan {@code explain} renders stays truthful; what changes is only the physical
     * source the {@code w_} name resolves to — documented in {@code WrapperOutputCache}.
     */
    private void applyWrapperOutputCache(Statement stmt, List<String> ddl) {
        WrapperOutputCache cache = wrapperCache;
        if (cache == null || wrapperCacheSpecs.isEmpty()) {
            return;
        }
        for (Map.Entry<String, WrapperOutputCache.Spec> e : wrapperCacheSpecs.entrySet()) {
            String wView = e.getKey();
            WrapperOutputCache.Spec spec = e.getValue();
            try {
                String prefix = "CREATE OR REPLACE VIEW " + wView + " AS ";
                if (ddl.stream().noneMatch(s -> s.startsWith(prefix))) {
                    continue; // pruned out of this statement's classes
                }
                if (isFederated(List.of(snapshots.resolveReadExpression(spec.datasetId())))) {
                    continue;
                }
                Optional<Path> cached = cache.get(spec.key());
                if (cached.isEmpty()) {
                    cached = cache.fillFrom(spec.key(), stmt, fillSelect(wView, spec.sortColumns()));
                }
                if (cached.isPresent()) {
                    stmt.execute(prefix + "SELECT * FROM read_parquet('"
                            + Names.sqlLiteral(cached.get().toAbsolutePath().toString()) + "')");
                }
            } catch (Exception ex) {
                log.debug("Wrapper-output cache pass failed for {} — the logical wrapper view"
                        + " stands", wView, ex);
            }
        }
    }

    /** {@code SELECT * FROM w_… [ORDER BY "k1", "k2"]} — the fill's engine-built body. */
    static String fillSelect(String wrapperViewName, List<String> sortColumns) {
        if (sortColumns.isEmpty()) {
            return "SELECT * FROM " + wrapperViewName;
        }
        String order = sortColumns.stream()
                .map(c -> "\"" + c.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(", "));
        return "SELECT * FROM " + wrapperViewName + " ORDER BY " + order;
    }

    /**
     * Closes the connection's filesystem and network access to everything but
     * {@link #allowedDirectories}, then locks the configuration so the statement about to run
     * cannot reopen it.
     *
     * <p><b>The order is the whole trick, and it is not the order you would guess.</b>
     * {@code allowed_directories} on its own does nothing at all — verified against DuckDB
     * 1.3.1, which happily read {@code /etc} with an allowlist set. It is not a restriction but
     * an <em>exemption list</em>, and it only means anything once
     * {@code enable_external_access=false} has taken the filesystem away. Setting the allowlist
     * second would be worse than useless: it would look like a control and be none.
     *
     * <p>The ordering was derived on 1.3.1 and still holds on the pinned 1.5.5:
     * {@code DuckDbLockdownTest} exercises the end state — a read outside the allowlist fails and
     * the configuration cannot be reopened — and passes on both. Re-run it on any engine bump;
     * this is a security control whose behaviour is engine-defined, not ours.
     *
     * <p>Runs after the DDL for two reasons. The {@code INSTALL}/{@code LOAD} in
     * {@link #ensureFederationExtensions} fetches over the network, which
     * {@code enable_external_access=false} forbids; and the DDL is also what says whether this
     * query federates at all.
     *
     * <p><b>A federated query cannot be locked down</b>, because {@code postgres_scan} and an
     * attached MySQL catalog <em>are</em> external access — the setting that would confine the
     * filesystem is the same one that reaches the remote database. Those queries keep the SQL
     * screen as their only layer, which is what they had before. Deciding this from the DDL
     * rather than up front is what keeps the ordinary local-snapshot query — the overwhelming
     * majority — fully confined.
     */
    private void lockDown(Statement stmt, List<String> ddl) throws Exception {
        if (allowedDirectories.isEmpty()) {
            return;
        }
        // The federation signal must come from the RESOLVER's own source reads, never from the
        // whole DDL: a per-class wrapper (w_ view) carries USER SQL spliced verbatim, so a wrapper
        // that merely PROJECTS the literal 's3://x' — or the text 'postgres_scan' — would otherwise
        // trip the federation substring test and drop the lockdown on a query that performs no
        // external access at all (sec-audit F-QUERY-2). A wrapper cannot itself emit a real read
        // (WrapperSqlValidator forbids read_*/*_scan/query()/replacement scans), so a federation
        // substring in a w_ body is necessarily a literal, not a scan.
        if (isFederated(resolverSourceReads(ddl))) {
            log.debug("Federated scan in play — filesystem lockdown skipped for this query");
            return;
        }
        lockDownFilesystem(stmt, allowedDirectories);
    }

    /**
     * The resolver-emitted source reads only — the {@code CREATE OR REPLACE VIEW src_… AS SELECT *
     * FROM <readExpr>} statements whose body is a raw read expression {@link SnapshotResolver}
     * produced (a local snapshot read, a {@code postgres_scan}, an attached MySQL table, or an
     * {@code s3://} httpfs read). These are the only statements that can perform real external
     * access; every other statement {@link #buildDdl} emits is either user wrapper SQL ({@code w_})
     * or an engine-generated projection ({@code int_}/{@code u_}/{@code q_}/alias), which
     * {@link #isFederated} must not read for the lockdown decision.
     */
    static List<String> resolverSourceReads(List<String> ddl) {
        List<String> out = new ArrayList<>();
        for (String statement : ddl) {
            if (statement.stripLeading().toUpperCase(Locale.ROOT)
                    .startsWith("CREATE OR REPLACE VIEW SRC_")) {
                out.add(statement);
            }
        }
        return out;
    }

    /**
     * The three-statement sequence, in the one order that actually restricts anything — see this
     * method's former home in {@link #lockDown} for why the order is not the one you would guess
     * ({@code allowed_directories} alone is an exemption list, not a restriction, until
     * {@code enable_external_access=false} has taken the filesystem away first).
     *
     * <p>Package-private and reused by {@code QueryEngineOperator.verifyWrapper}'s own scratch
     * connection, which needs the identical guarantee over a single ad-hoc {@code src_} view
     * rather than a whole prepared catalog: one copy of the sequence, not two that can drift
     * apart on the next DuckDB version bump. The caller decides whether to call this at all —
     * see {@code verifyWrapper}'s own federated check, mirroring {@link #isFederated} here.
     */
    static void lockDownFilesystem(Statement stmt, List<Path> directories) throws Exception {
        String dirs = directories.stream()
                .map(p -> "'" + Names.sqlLiteral(p.toAbsolutePath().toString()) + "'")
                .collect(Collectors.joining(", "));
        stmt.execute("SET allowed_directories=[" + dirs + "]");
        stmt.execute("SET enable_external_access=false");
        stmt.execute("SET lock_configuration=true");
    }

    /**
     * Builds the ordered list of {@code CREATE OR REPLACE VIEW} statements that materialise
     * the GAV unfolding for the given catalog — the {@code src_*} source views, the
     * {@code w_*_*} per-class wrappers, and the {@code int_*} UNION ALL views the engine
     * queries against. This is the textual GAV unfolding: each integrated class becomes the
     * union of its source wrappers, with every source projected onto the same global-column
     * vector (missing properties padded with NULL) so the UNION ALL is well-typed.
     *
     * <p>Kept as a pure function (no JDBC) so it can serve two callers identically:
     * {@link #prepare()} executes the statements, while the query engine's <em>explain</em>
     * surface renders them for inspection. Because both go through here, what you see in
     * explain is exactly what runs.
     */
    public static List<String> buildDdl(MappingCatalog catalog, SnapshotResolver snapshots) {
        return buildDdl(catalog, snapshots, Map.of());
    }

    /**
     * As {@link #buildDdl(MappingCatalog, SnapshotResolver)}, but each integrated class named
     * in {@code enforcedByClass} has its {@code int_} view filtered by the class's enforced
     * denial constraints ({@code WHERE (c1) AND (c2) …}) and gains a companion quarantine
     * view {@code q_<Class>} holding exactly the rows the filter rejects
     * ({@code WHERE NOT ((c1) AND …)}).
     *
     * <p>Constraint expressions are authored against the class's global-property <em>local
     * names</em> (what the workbench and catalog show); the {@code int_} view exposes columns
     * named by their full global-property IRI, so each bare identifier that matches a known
     * local name is rewritten to the quoted IRI before it is spliced in. A NULL constraint
     * column lands in neither view (SQL three-valued logic), matching normal {@code WHERE}
     * semantics — the seeded reject in the M6 test is a concrete FALSE, so it moves cleanly to
     * {@code q_}.
     */
    public static List<String> buildDdl(MappingCatalog catalog, SnapshotResolver snapshots,
                                        Map<String, List<String>> enforcedByClass) {
        return buildDdl(catalog, snapshots, enforcedByClass, null);
    }

    /**
     * As above, but restricted to {@code requiredClasses} — the integrated-class IRIs whose views
     * the statement about to run actually names. {@code null} means "the whole catalog", which is
     * what {@code explain} wants.
     *
     * <p>Building the whole catalog is what the query path used to do, and the cost is not the
     * {@code CREATE VIEW} statements — it is that {@link SnapshotResolver} is invoked for every
     * source of every class, and the resolver <em>downloads the snapshot</em>. A single-class
     * query over a twenty-dataset catalog therefore pulled all twenty snapshots out of the lake,
     * wrote them all to disk, built eighty-odd views, read one of them, and deleted everything.
     * The download count per query was the size of the catalog, not the size of the query.
     */
    public static List<String> buildDdl(MappingCatalog catalog, SnapshotResolver snapshots,
                                        Map<String, List<String>> enforcedByClass,
                                        @Nullable Set<String> requiredClasses) {
        List<String> ddl = new ArrayList<>();
        // Sources-tier alias names, computed over the WHOLE catalog before any pruning —
        // SourceCatalog's collision suffixes depend on every claimant, so a pruned build must
        // not hand the same table a different name than an unpruned one.
        Map<String, String> aliasByPair = new LinkedHashMap<>();
        for (edu.upc.dtak.aran.core.domain.SourceCatalog.SourceTable t
                : catalog.sourceCatalog().tables()) {
            aliasByPair.put(pairKey(t.datasetId(), t.sourceClassIri()), t.sqlName());
        }
        Map<String, String> backingClassByPair = aliasBackingClasses(catalog);

        // The JDBC tree lists the alias tables under a schema named `sources`, so clients write
        // `sources.acm` — which only resolves if that schema really exists. Found live by
        // JdbcRealClientIT (V28); same move ProductSqlExecutor makes for `products`.
        boolean anyAlias = !aliasByPair.isEmpty()
                && (requiredClasses == null
                        || backingClassByPair.values().stream().anyMatch(requiredClasses::contains));
        if (anyAlias) {
            ddl.add("CREATE SCHEMA IF NOT EXISTS sources");
        }

        for (MappingCatalog.IntegratedClass intClass : catalog.classes().values()) {
            if (requiredClasses != null && !requiredClasses.contains(intClass.iri())) {
                continue;
            }
            String classHash = Names.sqlName(intClass.iri());
            List<String> wrapperViews = new ArrayList<>();
            List<String> globalColumns = collectGlobalColumns(intClass);

            for (MappingCatalog.SourceView srcView : intClass.sources()) {
                String datasetId = srcView.datasetId();
                String dsHash = Names.sqlName(datasetId);

                // 1. src_<datasetId> view — the raw snapshot, read via the resolver.
                String readExpr = snapshots.resolveReadExpression(datasetId);
                ddl.add("CREATE OR REPLACE VIEW src_" + dsHash + " AS SELECT * FROM " + readExpr);

                // 2. w_<datasetId>_<classHash> view — the per-class wrapper SQL with the
                //    ${SNAPSHOT} placeholder bound to the src_ view.
                String wrapperViewName = "w_" + dsHash + "_" + classHash;
                String sql = srcView.wrapperSql().replace("${SNAPSHOT}", "src_" + dsHash);
                ddl.add("CREATE OR REPLACE VIEW " + wrapperViewName + " AS " + sql);

                // 2b. Sources-tier alias: the SourceCatalog-named view a SQL author types.
                // Backed on w_ (not u_) so it keeps source-native shape — including rows that
                // denial constraints quarantine, a documented property of the tier. A source
                // class feeding several integrated classes has one wrapper view per class with
                // identical contents; the class chosen by aliasBackingClasses emits the alias,
                // so it appears exactly once and the choice does not depend on map order.
                if (!srcView.srcClass().isEmpty()
                        && intClass.iri().equals(
                                backingClassByPair.get(pairKey(datasetId, srcView.srcClass())))) {
                    String aliasName = aliasByPair.get(pairKey(datasetId, srcView.srcClass()));
                    String aliasProjection = aliasProjection(srcView);
                    if (aliasName != null && !aliasProjection.isEmpty()) {
                        // Quoted: a source table called `order` or `group` keeps that name
                        // through SourceCatalog, and an unquoted reserved word here failed
                        // prepare() for every graph-tier query touching the class (S7-8).
                        String alias = Names.sqlIdentifier(aliasName);
                        ddl.add("CREATE OR REPLACE VIEW " + alias + " AS SELECT "
                                + aliasProjection + " FROM " + wrapperViewName);
                        // The same table under its schema-qualified name, so `sources.acm`
                        // (what the JDBC tree advertises) and bare `acm` (what the workbench
                        // panel shows) are one view, not two definitions. The body must say
                        // `main.` — inside schema `sources` an unqualified name binds to the
                        // view's own schema first, which is the view itself, recursively.
                        ddl.add("CREATE OR REPLACE VIEW sources." + alias
                                + " AS SELECT * FROM main." + alias);
                    }
                }

                // 3. Project to match global columns order exactly for UNION ALL. TRY_CAST, not
                // CAST: a dirty cell must degrade to NULL — what the padding path (the NULL
                // branch below) always produced — never fail a query that used to run.
                String projection = globalColumns.isEmpty() ? "*" : globalColumns.stream()
                        .map(c -> {
                            SqlTypeName type = XsdTypes.widenedType(intClass, c);
                            String quotedGlobal = "\"" + c.replace("\"", "\"\"") + "\"";
                            String srcCol = srcView.columnMap().get(c);
                            if (srcCol != null) {
                                String quotedSrc = "\"" + srcCol.replace("\"", "\"\"") + "\"";
                                if (type == SqlTypeName.VARCHAR) {
                                    return quotedSrc + " AS " + quotedGlobal;
                                }
                                return "TRY_CAST(" + quotedSrc + " AS " + XsdTypes.duckType(type)
                                        + ") AS " + quotedGlobal;
                            } else {
                                // Defensive: a production catalog can't reach type != VARCHAR here
                                // (QUERY 1 always writes columnMap and columnTypes together), so
                                // only a hand-built catalog exercises the CAST(NULL AS ...) below.
                                if (type == SqlTypeName.VARCHAR) {
                                    return "NULL AS " + quotedGlobal;
                                }
                                return "CAST(NULL AS " + XsdTypes.duckType(type) + ") AS " + quotedGlobal;
                            }
                        })
                        .collect(Collectors.joining(", "));

                wrapperViews.add("SELECT " + projection + " FROM " + wrapperViewName);

                // 3b. u_<datasetId>_<classHash> — the same projected union member, named.
                //
                // int_ below inlines these projections and filters the union as a whole, which
                // makes the union invisible to Calcite: it can only scan int_ as one opaque
                // table, so per-source row counts and keys are aggregated away before the cost
                // model sees them. Naming each member lets the planner put the UNION ALL in the
                // RelNode tree and attach that source's own statistics to its branch.
                //
                // The enforced constraints are applied HERE, per member, rather than once above
                // the union. Selection distributes over union — σ(A ∪ B) = σ(A) ∪ σ(B), and that
                // holds for bag semantics too — so a union of filtered members is exactly int_.
                // Without this, a planner that unions the members directly would silently stop
                // enforcing denial constraints and surface quarantined rows.
                List<String> memberConstraints = enforcedByClass.getOrDefault(intClass.iri(), List.of());
                String memberBody = "SELECT " + projection + " FROM " + wrapperViewName;
                if (!memberConstraints.isEmpty()) {
                    Map<String, String> localToGlobal = localToGlobal(intClass);
                    String conjunction = memberConstraints.stream()
                            .map(expr -> "(" + rewriteConstraint(expr, localToGlobal) + ")")
                            .collect(Collectors.joining(" AND "));
                    memberBody = "SELECT * FROM (" + memberBody + ") _u WHERE " + conjunction;
                }
                ddl.add("CREATE OR REPLACE VIEW u_" + dsHash + "_" + classHash + " AS " + memberBody);
            }

            // 4. int_<classHash> view as UNION ALL (never UNION — set semantics would
            //    silently drop genuinely duplicate tuples coming from overlapping sources).
            if (!wrapperViews.isEmpty()) {
                String intViewName = "int_" + classHash;
                String unionAll = String.join(" UNION ALL ", wrapperViews);

                List<String> constraints = enforcedByClass.getOrDefault(intClass.iri(), List.of());
                if (constraints.isEmpty()) {
                    ddl.add("CREATE OR REPLACE VIEW " + intViewName + " AS " + unionAll);
                } else {
                    // Rewrite each predicate's local-name identifiers to the int_ view's
                    // IRI-named columns, then combine as a conjunction.
                    Map<String, String> localToGlobal = localToGlobal(intClass);
                    String conjunction = constraints.stream()
                            .map(expr -> "(" + rewriteConstraint(expr, localToGlobal) + ")")
                            .collect(Collectors.joining(" AND "));
                    ddl.add("CREATE OR REPLACE VIEW " + intViewName
                            + " AS SELECT * FROM (" + unionAll + ") _int WHERE " + conjunction);
                    // q_<classHash>: the rejects — same body, negated predicate.
                    ddl.add("CREATE OR REPLACE VIEW q_" + classHash
                            + " AS SELECT * FROM (" + unionAll + ") _q WHERE NOT (" + conjunction + ")");
                }
            }
        }
        return ddl;
    }

    /**
     * Execute a statement that returns no result set — the M8 materializer's
     * {@code COPY (…) TO '<tmp>.parquet'} (the JDBC driver rejects COPY through
     * {@code executeQuery}). Runs on the same connection as {@link #prepare()}'s views.
     */
    public void execute(String sql) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("Statement execution failed: " + rootMessage(e), e);
        }
    }

    /**
     * Wall-clock ceiling on a single interactive query.
     *
     * <p>Without one, a query that never finishes — a cross join over two federated
     * {@code postgres_scan}s is enough — pins the request thread and its DuckDB connection until
     * the client gives up, and the server never learns it should stop. DuckDB's JDBC driver
     * honours {@code setQueryTimeout} and raises {@code INTERRUPT Error: Interrupted!} on expiry.
     *
     * <p>Applied to {@link #run} only. {@link #execute} carries the data-product materialisation
     * {@code COPY}, which is a tracked background job and legitimately long-running.
     */
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 120;

    private int queryTimeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;

    /** The ceiling {@link #run} applies, in seconds. */
    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    /** Override the ceiling — a positive number of seconds. */
    public void setQueryTimeoutSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("query timeout must be positive: " + seconds);
        }
        this.queryTimeoutSeconds = seconds;
    }

    public QueryResult run(String sql) {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            return collect(stmt, sql);
        } catch (Exception e) {
            throw new RuntimeException("Query execution failed: " + rootMessage(e), e);
        }
    }

    private static QueryResult collect(Statement stmt, String sql) throws Exception {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> headers = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                headers.add(meta.getColumnLabel(i));
            }

            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    row.add(jsonSafe(rs.getObject(i)));
                }
                rows.add(row);
            }

            return new QueryResult(headers, rows);
        }
    }

    /**
     * The deepest cause's message.
     *
     * <p>Every failure here used to be wrapped in a {@code RuntimeException} whose message was a
     * fixed string, and {@code QueryEngineOperator}'s catch-all reports {@code e.getMessage()}.
     * So a user who mistyped a column name was told "Query execution failed" and nothing else —
     * DuckDB's "Binder Error: Referenced column … not found" never left the server.
     */
    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    /**
     * Narrowed from {@code throws Exception} to {@code throws SQLException}: the body only
     * closes a JDBC connection, and the wider signature made every try-with-resources over
     * this class warn that it could throw InterruptedException.
     */
    @Override
    public void close() throws java.sql.SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }


    private static String pairKey(String datasetId, String srcClass) {
        return datasetId + "\u0000" + srcClass;
    }

    /**
     * Which integrated class emits each source class's alias view. A source class feeding
     * several integrated classes has one wrapper view per class, all with identical contents;
     * the alias must back exactly one of them, chosen by smallest class IRI so the pick is a
     * function of the catalog and not of {@code classes()}'s map iteration order.
     * {@link #referencedClasses} must agree with this choice, or a statement naming only the
     * alias would materialise a different class than the one the alias reads from.
     */
    static Map<String, String> aliasBackingClasses(MappingCatalog catalog) {
        Map<String, String> backing = new LinkedHashMap<>();
        for (MappingCatalog.IntegratedClass cls : catalog.classes().values()) {
            for (MappingCatalog.SourceView sv : cls.sources()) {
                if (!sv.srcClass().isEmpty()) {
                    backing.merge(pairKey(sv.datasetId(), sv.srcClass()), cls.iri(),
                            (a, b) -> a.compareTo(b) <= 0 ? a : b);
                }
            }
        }
        return backing;
    }

    /**
     * The alias view's projection: every wrapper column (a source property IRI) aliased to its
     * local name, sorted by local name so the shape is deterministic. Distinct first — a join
     * key is routinely a sub-property of both a datatype IR and P_l, so {@code columnMap}
     * carries the same source column under two global keys, and projecting it twice is a
     * duplicate-column error for the whole view.
     */
    private static String aliasProjection(MappingCatalog.SourceView srcView) {
        return srcView.columnMap().values().stream()
                .distinct()
                .sorted(java.util.Comparator
                        .comparing((String iri) -> Names.localName(iri))
                        .thenComparing(iri -> iri))
                .map(iri -> "\"" + iri.replace("\"", "\"\"") + "\" AS \""
                        + Names.localName(iri).replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(", "));
    }

    private static List<String> collectGlobalColumns(MappingCatalog.IntegratedClass intClass) {
        return intClass.sources().stream()
                .flatMap(s -> s.columnMap().keySet().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Local property name → full global-property IRI for one integrated class — the inverse
     * of the display mapping the catalog exposes.
     *
     * <p>Delegates to {@link ConstraintRewriter#localNameToIri}: {@code OqlPlanner} builds the
     * same map to parse the same constraints into planner predicates, and a private second copy
     * here would let the two disagree about which IRI a colliding local name means.
     */
    private static Map<String, String> localToGlobal(MappingCatalog.IntegratedClass intClass) {
        return ConstraintRewriter.localNameToIri(collectGlobalColumns(intClass));
    }

    /**
     * Rewrite a constraint predicate so its bare identifiers reference the {@code int_} view's
     * IRI-named columns — see {@link ConstraintRewriter#rewriteToIriColumns}, which owns the
     * tokeniser now that the planner parses the same rewritten text.
     */
    static String rewriteConstraint(String expr, Map<String, String> localToGlobal) {
        return ConstraintRewriter.rewriteToIriColumns(expr, localToGlobal);
    }
}

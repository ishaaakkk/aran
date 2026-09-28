package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.util.Names;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * An on-disk cache of per-class <b>wrapper outputs</b>, materialised as Parquet and sorted by the
 * class's join keys — the query engine's "index".
 *
 * <h2>Why this level, when {@link SnapshotCache} refused it</h2>
 *
 * <p>{@code SnapshotCache}'s javadoc rejects caching wrapper output on the grounds that a safe key
 * would need "the model version, the wrapper SQL and the enforced-constraint set". For the
 * {@code w_} view level specifically, it does not: denial constraints are applied in the
 * {@code u_}/{@code int_} views <em>above</em> {@code w_} (see {@code DuckDbExecutor.buildDdl}
 * step 3b — never inside the wrapper), and a wrapper may reference only {@code ${SNAPSHOT}}
 * ({@code WrapperSqlValidator}), so a {@code w_} view's rows are a pure function of
 * <b>(snapshot bytes, wrapper text)</b>. Both are content-addressed — a refresh mints a new
 * {@code snapshotId}, a wrapper edit changes the text this key hashes — so, exactly like the
 * snapshot cache, this cache has no invalidation problem, only eviction.
 *
 * <h2>What it buys</h2>
 *
 * <p>Two things, and the second is the reason this exists at all. (1) A query over a cached class
 * scans a Parquet file instead of re-executing the wrapper's transformation over the raw snapshot
 * on every statement. (2) The file is written {@code ORDER BY} the class's join-key columns
 * (from {@code MappingCatalog.JoinProperty}, resolved through the class's own {@code columnMap}),
 * which is what makes DuckDB's row-group zone maps and its runtime hash-join min/max filter
 * pushdown actually prune on the join-heavy queries this engine produces. Classic index
 * structures were considered and rejected: DuckDB's ART indexes exist only on native base tables,
 * serve point lookups, and are ignored by the hash joins that dominate here.
 *
 * <h2>What is deliberately NOT cached</h2>
 *
 * <ul>
 *   <li><b>Federated classes</b> — their {@code w_} view reads a live scan; a cached copy would
 *       silently freeze a source whose whole point is freshness. {@code DuckDbExecutor} re-checks
 *       this against the resolver's own read expression, so a mis-built spec cannot cache one.</li>
 *   <li><b>EXPANDED classes</b> — the planner substitutes their plan over {@code src_} directly
 *       ({@code WrapperExpansion}); a cached {@code w_} would never be read on the fast path.</li>
 *   <li><b>The identity wrapper with no join keys</b> ({@code SELECT * FROM ${SNAPSHOT}}) — a
 *       byte-for-byte duplicate of the snapshot the {@code SnapshotCache} already holds, with no
 *       sort to add value.</li>
 * </ul>
 *
 * <h2>Why the PLANNER does not choose between a cached and an uncached leaf</h2>
 *
 * <p>Considered and rejected (2026-08-29, on the owner's question). Two reasons, either
 * sufficient. The cost model prices plans by cardinality evidence, and a cached leaf produces
 * <em>identical rows</em> to its uncached twin by construction — under cardinality costing the
 * two alternatives cost the same, so Volcano could only pick arbitrarily; giving the choice
 * meaning requires a physical-IO cost dimension, which is the deferred λ/transfer-aware-cost
 * work (do not start it here). And even with IO costing, the cached leaf strictly dominates on
 * a hit — same rows, transformation pre-applied, sorted, unconditional pushdown — so the
 * "choice" has one legal winner while doubling the per-leaf search space that P1 already
 * records as the planning bottleneck. The one place a real cost decision exists is
 * <b>fill admission</b> — whether an entry is worth creating at all — and that is decided here,
 * by measurement: pay the {@code COPY} once, remember the verdict ({@code refusedKeys}).
 *
 * <h2>Policy</h2>
 *
 * <p>Same discipline as {@link SnapshotCache}, deliberately: sha256 keys (nothing user-controlled
 * reaches a filesystem path), LRU by bytes with a {@code max-bytes / 4} per-entry ceiling,
 * fills written to a {@code .part} file and published with an atomic move, per-key locks so N
 * concurrent queries cause one fill, adoption of existing files on restart, and <b>every failure
 * returns {@link Optional#empty()}</b> — the caller keeps the logical wrapper view and the query
 * runs exactly as before this cache existed. A cache must not be able to break a query.
 * {@code max-bytes: 0} disables it.
 *
 * <p>The one structural difference from the snapshot cache: the fill runs on the <em>caller's</em>
 * DuckDB statement ({@link #fillFrom}), because only the query's own connection has the
 * {@code src_}/{@code w_} views (and any federated secrets) bound. The cache owns the file
 * lifecycle; the caller owns the SQL.
 */
@NullMarked
@Component
public final class WrapperOutputCache {

    private static final Logger log = LoggerFactory.getLogger(WrapperOutputCache.class);

    /**
     * One class's cache participation, computed by the operator (which holds the catalog and the
     * graph's snapshot map) and consumed by {@code DuckDbExecutor.prepare}:
     *
     * @param datasetId   the dataset whose resolver read expression decides the federated
     *                    re-check — a live scan is never cached, whatever the spec says
     * @param key         {@link #key}, binding tenant + snapshot + wrapper text + sort spec
     * @param sortColumns wrapper-output column names to {@code ORDER BY} on fill; may be empty
     */
    public record Spec(String datasetId, String key, List<String> sortColumns) {}

    private final @Nullable Path root;
    private final long maxBytes;
    private final long maxEntryBytes;

    /** Access-ordered LRU; guarded by {@code indexLock}, same shape as {@link SnapshotCache}. */
    private final LinkedHashMap<String, Long> sizesByKey = new LinkedHashMap<>(16, 0.75f, true);
    private final Object indexLock = new Object();
    private long totalBytes;
    private long hits;
    private long misses;

    private final ConcurrentHashMap<String, ReentrantLock> fillLocks = new ConcurrentHashMap<>();

    /**
     * Keys whose fill was tried and refused — over the per-entry ceiling, or failed outright.
     * This is the cache's <b>fill-admission decision, measured rather than estimated</b>: whether
     * an entry is worth creating is the one place a real cost choice exists (a cached leaf
     * strictly dominates its uncached twin once it exists, so there is nothing for the planner to
     * choose between — see the class javadoc), and the honest way to decide it is to pay the
     * {@code COPY} once and remember the verdict. Without this set, an over-ceiling class paid
     * that full {@code COPY} on <em>every</em> query, discovered the same answer, and deleted the
     * result — forever. Process-lifetime memory is sound because the verdict is a pure function
     * of the content-addressed key: any change that could alter it (new snapshot, edited wrapper,
     * different sort spec) mints a new key. Bounded so a pathological catalog cannot grow it
     * without limit; on overflow it clears, which only costs re-measuring.
     */
    private final java.util.Set<String> refusedKeys = ConcurrentHashMap.newKeySet();
    private static final int MAX_REFUSED_KEYS = 10_000;

    public WrapperOutputCache(
            @Value("${aran.query.wrapper-cache.dir:}") String dir,
            @Value("${aran.query.wrapper-cache.max-bytes:1073741824}") long maxBytes) {
        this.maxBytes = maxBytes;
        this.maxEntryBytes = maxBytes / 4;
        this.root = maxBytes <= 0 ? null : initRoot(dir);
        if (root != null) {
            adoptExistingFiles();
        }
    }

    /** A permanently disabled cache — what the convenience constructors wire when Spring is absent. */
    public static WrapperOutputCache disabled() {
        return new WrapperOutputCache("", 0);
    }

    private static @Nullable Path initRoot(String configured) {
        try {
            Path path = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("java.io.tmpdir"), "aran-wrapper-cache")
                    : Path.of(configured);
            Files.createDirectories(path);
            return path;
        } catch (Exception e) {
            log.warn("Wrapper-output cache directory unavailable — cache disabled", e);
            return null;
        }
    }

    private void adoptExistingFiles() {
        Path dir = root;
        if (dir == null) {
            return;
        }
        SnapshotCache.adoptTenantDirectories(dir, "wrapper-output", this::record);
        evictIfNeeded();
    }

    /**
     * An engine-built key: {@code <tenant directory>/<entry>}, both sha256 hex. Anything else is
     * refused before it can become a path.
     */
    private static final java.util.regex.Pattern KEY =
            java.util.regex.Pattern.compile("[0-9a-f]{64}/[0-9a-f]{64}");

    /**
     * This tenant's cache directory, created if needed — what a query of this tenant's
     * allowlists, and all of the cache it may read. Same layout, and same reason, as
     * {@link SnapshotCache#tenantRoot}.
     */
    public Optional<Path> tenantRoot(UUID tenantId) {
        Path dir = root;
        if (dir == null) {
            return Optional.empty();
        }
        Path tenantDir = dir.resolve(SnapshotCache.tenantDirectory(tenantId));
        try {
            Files.createDirectories(tenantDir);
            return Optional.of(tenantDir);
        } catch (IOException e) {
            log.warn("Wrapper-output cache tenant directory {} could not be created", tenantDir, e);
            return Optional.empty();
        }
    }

    /**
     * The cache key for one (tenant, snapshot, wrapper, sort) combination. The sort spec is in
     * the key on purpose: when a newly accepted join changes a class's sort columns, the next fill
     * writes a fresh, correctly ordered file and the old one ages out by LRU — self-healing beats
     * serving a stale physical order until eviction. The tenant is in the key for the same reason
     * it is in {@code SnapshotCache}'s: a guessed snapshot id must not reach another tenant's file.
     * It is also the key's first segment, the tenant's own directory ({@link #tenantRoot}), so the
     * filesystem lockdown can confine a query to its tenant's entries.
     */
    public static String key(UUID tenantId, String snapshotId, String wrapperSql,
                             List<String> sortColumns) {
        return SnapshotCache.tenantDirectory(tenantId) + "/"
                + entryHash(tenantId, snapshotId, wrapperSql, sortColumns);
    }

    private static String entryHash(UUID tenantId, String snapshotId, String wrapperSql,
                                    List<String> sortColumns) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(tenantId.toString().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(snapshotId.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(wrapperSql.getBytes(StandardCharsets.UTF_8));
            for (String col : sortColumns) {
                sha.update((byte) 0);
                sha.update(col.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The cached Parquet for {@code key} if present — a pure lookup, no fill. The returned path is
     * owned by the cache: read it, never delete it.
     */
    public Optional<Path> get(String key) {
        Path dir = root;
        if (dir == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        Path target = dir.resolve(key + ".parquet");
        if (Files.isReadable(target)) {
            synchronized (indexLock) {
                sizesByKey.get(key);   // access-order touch: this is the LRU update
                hits++;
            }
            return Optional.of(target);
        }
        synchronized (indexLock) {
            misses++;
        }
        return Optional.empty();
    }

    /**
     * Fill {@code key} by running {@code COPY (<selectSql>) TO <part>} on the caller's statement,
     * then publish atomically. Returns the cached path, or {@link Optional#empty()} on any
     * failure — including an over-ceiling result — after which the caller proceeds uncached.
     *
     * <p>{@code selectSql} is engine-built ({@code SELECT * FROM w_… [ORDER BY …]}), never user
     * text, and runs on a connection whose views the caller has already screened and bound the
     * usual way; this method adds no execution surface, only a {@code COPY} destination inside
     * the cache directory.
     */
    public Optional<Path> fillFrom(String key, Statement stmt, String selectSql) {
        Path dir = root;
        if (dir == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        if (refusedKeys.contains(key)) {
            // Measured once, remembered: this key's output is over the ceiling (or its fill
            // failed). Re-running the COPY would re-derive the same verdict at full price.
            return Optional.empty();
        }
        Path target = dir.resolve(key + ".parquet");
        ReentrantLock lock = fillLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // Another query may have filled it while we waited on the lock.
            if (Files.isReadable(target)) {
                synchronized (indexLock) {
                    sizesByKey.get(key);
                    hits++;
                }
                return Optional.of(target);
            }
            Path part = dir.resolve(key + ".part-" + UUID.randomUUID());   // beside its target
            try {
                Files.createDirectories(part.getParent());
                stmt.execute("COPY (" + selectSql + ") TO '"
                        + Names.sqlLiteral(part.toAbsolutePath().toString())
                        + "' (FORMAT PARQUET)");
                long size = Files.size(part);
                if (size > maxEntryBytes) {
                    log.info("Wrapper output for key {} is {} bytes, over the {} per-entry cache"
                            + " ceiling — not cached (remembered; this measurement is not"
                            + " repeated)", key, size, maxEntryBytes);
                    refuse(key);
                    return Optional.empty();
                }
                // Publish atomically: a reader either sees no file or a complete one.
                Files.move(part, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                part = null;
                record(key, size);
                evictIfNeeded();
                return Optional.of(target);
            } catch (Exception e) {
                // The message can carry engine internals but never credentials: selectSql is the
                // engine's own view reference, not a scan expression. A failed fill is remembered
                // like an over-ceiling one: whatever made this COPY fail (a stale sort column, an
                // unwritable directory) is not going to succeed on the next query's retry either,
                // and the retry is a full wrapper execution.
                log.warn("Wrapper output for key {} could not be cached — query proceeds through"
                        + " the logical wrapper view: {}", key, e.getMessage());
                refuse(key);
                return Optional.empty();
            } finally {
                if (part != null) {
                    try {
                        Files.deleteIfExists(part);
                    } catch (Exception ignore) {
                        // best effort
                    }
                }
            }
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                fillLocks.remove(key, lock);
            }
        }
    }

    /**
     * The cache directory, every tenant's; empty when disabled. Never an allowlist entry — a
     * query's lockdown names only its own {@link #tenantRoot}.
     */
    public Optional<Path> root() {
        return Optional.ofNullable(root);
    }

    private void refuse(String key) {
        if (refusedKeys.size() >= MAX_REFUSED_KEYS) {
            refusedKeys.clear(); // re-measuring is the only cost of forgetting
        }
        refusedKeys.add(key);
    }

    /** Hit/miss/refusal counters, for tests and diagnostics. */
    public Map<String, Long> stats() {
        synchronized (indexLock) {
            return Map.of("hits", hits, "misses", misses,
                    "entries", (long) sizesByKey.size(), "bytes", totalBytes,
                    "refusedKeys", (long) refusedKeys.size());
        }
    }

    private void record(String key, long size) {
        synchronized (indexLock) {
            Long previous = sizesByKey.put(key, size);
            totalBytes += size - (previous == null ? 0 : previous);
        }
    }

    private void evictIfNeeded() {
        Path dir = root;
        if (dir == null) {
            return;
        }
        synchronized (indexLock) {
            var it = sizesByKey.entrySet().iterator();
            while (totalBytes > maxBytes && it.hasNext()) {
                var eldest = it.next();
                try {
                    Files.deleteIfExists(dir.resolve(eldest.getKey() + ".parquet"));
                } catch (IOException e) {
                    log.debug("Could not evict cached wrapper output {}", eldest.getKey(), e);
                }
                totalBytes -= eldest.getValue();
                it.remove();
            }
        }
    }
}

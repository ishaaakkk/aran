package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An on-disk cache of lake snapshots, transcoded to Parquet.
 *
 * <h2>Why</h2>
 *
 * <p>Before this, every query paid a full round trip per source: download the snapshot from the
 * lake, write it to a temp file, build views over it, run, delete. Nothing was reused between
 * queries, so running the same OQL twice cost exactly twice as much, and an interactive session
 * re-fetched the same immutable bytes on every keystroke-to-result.
 *
 * <h2>What is cached, and why that level</h2>
 *
 * <p>The <em>snapshot</em> — not wrapper output, not intermediate views, not query results. That
 * choice follows from the key: a snapshot is immutable and content-addressed, because re-ingesting
 * a dataset mints a <em>new</em> {@code snapshotId}. So a cache keyed on
 * {@code (tenantId, snapshotId)} has no invalidation problem to solve — only eviction. Caching a
 * wrapper's output or a query's result would require the model version, the wrapper SQL and the
 * enforced-constraint set in the key, and getting such a key wrong means serving stale data, which
 * is the worst failure a data product has.
 *
 * <h2>Why Parquet</h2>
 *
 * <p>Transcoding on fill buys the intermediate-result win without a second cache: DuckDB scans
 * Parquet far faster than it re-parses CSV or JSON, and it supports projection pushdown, so a
 * query touching two of forty columns reads two. The conversion happens once per snapshot per
 * lifetime, on the miss path. A snapshot that is already Parquet is stored as-is.
 *
 * <h2>Policy</h2>
 *
 * <ul>
 *   <li><b>Key</b> — {@code sha256(tenantId + ':' + snapshotId)}, hex, used as the filename. The
 *       tenant is in the key so a guessed snapshot id cannot reach another tenant's file, and it
 *       is hashed so nothing user-controlled reaches a filesystem path.</li>
 *   <li><b>Layout</b> — one subdirectory per tenant, {@code <root>/<tenantDirectory(tenant)>/},
 *       and {@link #tenantRoot} is what a query's filesystem lockdown allowlists. With one flat
 *       directory the allowlist had to name the whole cache, so a statement that slipped the SQL
 *       screen could read every other tenant's cached snapshot from inside the lockdown; the key
 *       alone protects only against a <em>guessed</em> id, not against a directory listing or a
 *       glob.</li>
 *   <li><b>Eviction</b> — LRU by total bytes against {@code max-bytes}, plus a per-entry ceiling
 *       ({@code max-bytes / 4}) so one enormous snapshot cannot evict the entire working set. An
 *       entry over that ceiling is not cached at all; the caller falls back. <b>Note the
 *       interaction:</b> a budget smaller than four times your largest snapshot admits nothing and
 *       silently degrades to the uncached path, so size {@code max-bytes} against the data, not
 *       against spare disk.</li>
 *   <li><b>Concurrency</b> — a per-key lock, so N queries wanting the same snapshot cause one
 *       download rather than N.</li>
 *   <li><b>Crash safety</b> — fills are written to {@code <hash>.part} and published with an
 *       atomic move. A partially written file is never visible under its final name, so a crash
 *       mid-download cannot leave a truncated snapshot that reads as complete.</li>
 *   <li><b>Failure</b> — every miss path that goes wrong returns {@link Optional#empty()} rather
 *       than throwing. The caller then does what it did before. A cache must not be able to break
 *       a query.</li>
 *   <li><b>Disabled</b> — {@code max-bytes: 0} makes {@link #get} always return empty, which is
 *       exactly the pre-cache behaviour.</li>
 * </ul>
 *
 * <p>Federated sources are unaffected: they resolve to tables of an attached Postgres or MySQL
 * catalog ({@code aran_attach_…}) or live object-storage/Iceberg scans and never download anything.
 */
@Component
public final class SnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCache.class);

    private final LakeClient lake;
    private final @org.jspecify.annotations.Nullable Path root;
    private final long maxBytes;
    private final long maxEntryBytes;

    /**
     * Access-ordered, so the eldest entry is the least recently <em>used</em> rather than the
     * least recently added. Guarded by {@code indexLock}; the per-key fill locks below serialise
     * downloads, this one only protects the bookkeeping.
     */
    private final LinkedHashMap<String, Long> sizesByKey = new LinkedHashMap<>(16, 0.75f, true);
    private final Object indexLock = new Object();
    private long totalBytes;

    private final ConcurrentHashMap<String, ReentrantLock> fillLocks = new ConcurrentHashMap<>();

    private long hits;
    private long misses;

    public SnapshotCache(LakeClient lake,
                         @Value("${aran.query.snapshot-cache.dir:}") String dir,
                         @Value("${aran.query.snapshot-cache.max-bytes:2147483648}") long maxBytes) {
        this.lake = lake;
        this.maxBytes = maxBytes;
        this.maxEntryBytes = maxBytes / 4;
        this.root = maxBytes <= 0 ? null : initRoot(dir);
        if (root != null) {
            adoptExistingFiles();
        }
    }

    private static @org.jspecify.annotations.Nullable Path initRoot(String configured) {
        try {
            Path path = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("java.io.tmpdir"), "aran-snapshot-cache")
                    : Path.of(configured);
            Files.createDirectories(path);
            if (!Files.isWritable(path)) {
                log.warn("Snapshot cache directory {} is not writable — caching disabled", path);
                return null;
            }
            return path;
        } catch (Exception e) {
            log.warn("Snapshot cache could not be initialised — caching disabled", e);
            return null;
        }
    }

    /** Re-adopt whatever a previous process left behind, so a restart is not a cold cache. */
    private void adoptExistingFiles() {
        Path dir = root;
        if (dir == null) {
            return;
        }
        adoptTenantDirectories(dir, "snapshot", this::record);
        evictIfNeeded();
    }

    /**
     * Walks a per-tenant cache root — shared with {@link WrapperOutputCache}, whose layout is the
     * same — feeding each {@code <tenant>/<entry>.parquet} to {@code record} under its relative
     * key, and deleting a cache file left flat in the root by the layout before tenant
     * directories: its key never names a tenant directory, so nothing could ever be served it.
     */
    static void adoptTenantDirectories(Path dir, String what,
                                       java.util.function.ObjLongConsumer<String> record) {
        try (var children = Files.list(dir)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child) && HEX_64.matcher(name).matches()) {
                    try (var entries = Files.list(child)) {
                        for (Path entry : entries.toList()) {
                            String file = entry.getFileName().toString();
                            if (!file.endsWith(".parquet")) {
                                continue;
                            }
                            try {
                                record.accept(name + "/" + stem(file), Files.size(entry));
                            } catch (IOException ignore) {
                                // Unreadable leftover; it will simply never be hit.
                            }
                        }
                    }
                } else if (name.endsWith(".parquet") && HEX_64.matcher(stem(name)).matches()) {
                    deleteQuietly(child);
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan the {} cache directory", what, e);
        }
    }

    private static String stem(String parquetFileName) {
        return parquetFileName.substring(0, parquetFileName.length() - ".parquet".length());
    }

    static final java.util.regex.Pattern HEX_64 = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    /**
     * The name of a tenant's subdirectory: hashed, like every entry name, so nothing a request
     * carries reaches a path. Package-private because {@link WrapperOutputCache} lays its files out
     * the same way and a query's allowlist names both.
     */
    static String tenantDirectory(UUID tenantId) {
        return sha256Hex("tenant:" + tenantId);
    }

    /**
     * This tenant's cache directory, created if needed — the only part of the cache a query of
     * this tenant's may read, and so the only part its filesystem lockdown allowlists. Empty when
     * caching is off or the directory cannot be created.
     */
    public Optional<Path> tenantRoot(UUID tenantId) {
        Path dir = root;
        if (dir == null) {
            return Optional.empty();
        }
        Path tenantDir = dir.resolve(tenantDirectory(tenantId));
        try {
            Files.createDirectories(tenantDir);
            return Optional.of(tenantDir);
        } catch (IOException e) {
            log.warn("Snapshot cache tenant directory {} could not be created", tenantDir, e);
            return Optional.empty();
        }
    }

    /**
     * A local Parquet file holding this snapshot, fetching and transcoding it on a miss.
     *
     * <p>The returned path is owned by the cache: callers must read it and must not delete it.
     * {@link Optional#empty()} means "not available" — the caller should fall back to its own
     * download — and is returned for a disabled cache, an oversized snapshot, or any failure.
     *
     * @param format the source format recorded on the snapshot ({@code CSV}, {@code PARQUET},
     *               anything else is read as JSON)
     */
    public Optional<Path> get(UUID tenantId, String snapshotId, String format) {
        Path dir = root;
        if (dir == null) {
            return Optional.empty();
        }
        // The bookkeeping key is the entry's path relative to the root, tenant directory first,
        // so eviction and adoption find the file where it lives.
        String key = tenantDirectory(tenantId) + "/" + key(tenantId, snapshotId);
        Path target = dir.resolve(key + ".parquet");

        if (Files.isReadable(target)) {
            synchronized (indexLock) {
                sizesByKey.get(key);   // access-order touch: this is the LRU update
                hits++;
            }
            return Optional.of(target);
        }

        ReentrantLock lock = fillLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // Another thread may have filled it while we waited on the lock.
            if (Files.isReadable(target)) {
                synchronized (indexLock) {
                    sizesByKey.get(key);
                    hits++;
                }
                return Optional.of(target);
            }
            synchronized (indexLock) {
                misses++;
            }
            return fill(tenantId, snapshotId, format, key, target);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                fillLocks.remove(key, lock);
            }
        }
    }

    private Optional<Path> fill(UUID tenantId, String snapshotId, String format,
                                String key, Path target) {
        Path dir = root;
        if (dir == null) {
            return Optional.empty();
        }
        Path source = null;
        Path part = null;
        try {
            String suffix = suffixFor(format);
            Path tenantDir = target.getParent();
            Files.createDirectories(tenantDir);
            String entry = stem(target.getFileName().toString());
            source = Files.createTempFile(tenantDir, entry + "-src-", suffix);
            lake.downloadSnapshotTo(tenantId, snapshotId, source);

            long sourceSize = Files.size(source);
            if (sourceSize > maxEntryBytes) {
                // One 8 GB snapshot must not be able to evict everything else. Let the caller
                // stream it the old way instead of admitting it and thrashing.
                log.info("Snapshot {} is {} bytes, over the {} per-entry cache ceiling — not cached",
                        snapshotId, sourceSize, maxEntryBytes);
                return Optional.empty();
            }

            part = tenantDir.resolve(entry + ".part-" + UUID.randomUUID());
            if ("PARQUET".equalsIgnoreCase(format)) {
                Files.move(source, part, StandardCopyOption.REPLACE_EXISTING);
                source = null;
            } else {
                transcodeToParquet(source, format, part);
            }

            long size = Files.size(part);
            if (size > maxEntryBytes) {
                log.info("Transcoded snapshot {} is {} bytes, over the cache ceiling — not cached",
                        snapshotId, size);
                return Optional.empty();
            }

            // Publish atomically: a reader either sees no file or sees a complete one.
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            part = null;

            record(key, size);
            evictIfNeeded();
            return Optional.of(target);
        } catch (Exception e) {
            log.warn("Snapshot {} could not be cached — falling back to a direct read", snapshotId, e);
            return Optional.empty();
        } finally {
            deleteQuietly(source);
            deleteQuietly(part);
        }
    }

    /**
     * Read {@code source} with the format-appropriate DuckDB reader and write Parquet to
     * {@code target}. Uses its own in-memory connection: this runs on the miss path, and binding
     * it to the query's connection would mean a fill could not be shared between queries.
     */
    private static void transcodeToParquet(Path source, String format, Path target) throws Exception {
        String reader = SnapshotFormats.readerFor(format);
        try (Connection c = DuckDbSession.open();
             Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM " + reader + "('"
                    + Names.sqlLiteral(source.toAbsolutePath().toString()) + "')) TO '"
                    + Names.sqlLiteral(target.toAbsolutePath().toString()) + "' (FORMAT PARQUET)");
        }
    }

    private static String suffixFor(String format) {
        return switch (format == null ? "" : format.toUpperCase(Locale.ROOT)) {
            case "CSV", "TSV" -> ".csv";
            case "PARQUET" -> ".parquet";
            default -> ".json";
        };
    }

    private void record(String key, long size) {
        synchronized (indexLock) {
            Long previous = sizesByKey.put(key, size);
            totalBytes += size - (previous == null ? 0L : previous);
        }
    }

    /** Drop least-recently-used entries until the total is back inside the budget. */
    private void evictIfNeeded() {
        Path dir = root;
        if (dir == null) {
            return;
        }
        while (true) {
            String victim;
            synchronized (indexLock) {
                if (totalBytes <= maxBytes || sizesByKey.isEmpty()) {
                    return;
                }
                victim = sizesByKey.keySet().iterator().next();   // eldest by access order
                Long size = sizesByKey.remove(victim);
                totalBytes -= size == null ? 0L : size;
            }
            deleteQuietly(dir.resolve(victim + ".parquet"));
        }
    }

    private static void deleteQuietly(@org.jspecify.annotations.Nullable Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignore) {
            // Best effort: a leftover costs disk, never correctness.
        }
    }

    private static String key(UUID tenantId, String snapshotId) {
        return sha256Hex(tenantId + ":" + snapshotId);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── introspection, for the operator's stats payload and for tests ────────

    public boolean enabled() {
        return root != null;
    }

    /**
     * Where cached snapshots live, every tenant's, or empty when caching is off. Never an
     * allowlist entry — a query's lockdown names only {@link #tenantRoot} of its own tenant.
     */
    public Optional<Path> root() {
        return Optional.ofNullable(root);
    }

    public Map<String, Long> stats() {
        synchronized (indexLock) {
            return Map.of("hits", hits, "misses", misses,
                    "entries", (long) sizesByKey.size(), "bytes", totalBytes);
        }
    }
}

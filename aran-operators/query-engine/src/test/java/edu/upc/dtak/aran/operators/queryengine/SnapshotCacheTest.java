package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.LakeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache policy. Every property here is one the design depends on, and each is asserted as
 * behaviour rather than as a field value.
 */
class SnapshotCacheTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** A lake that writes fixed CSV content and counts how often it was asked. */
    private static class FakeLake implements LakeClient {
        @Override
        public java.util.List<SnapshotInventoryItem> snapshotInventory(
                UUID tenantId, int limit, int offset) {
            return java.util.List.of();
        }

        @Override
        public StorageUsage storageUsage(UUID tenantId, int datasetLimit) {
            // The cache never asks; a stub that returned made-up bytes would be worse than one
            // that says plainly it holds nothing.
            return StorageUsage.empty();
        }

        final AtomicInteger downloads = new AtomicInteger();
        final List<String> requested = new CopyOnWriteArrayList<>();
        private final String content;
        private volatile CountDownLatch gate;

        FakeLake(String content) { this.content = content; }

        @Override public String downloadSnapshotTo(UUID tenantId, String snapshotId, Path target) {
            downloads.incrementAndGet();
            requested.add(snapshotId);
            if (gate != null) {
                try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try { Files.writeString(target, content); } catch (Exception e) { throw new RuntimeException(e); }
            return "text/csv";
        }

        @Override public SnapshotBytes downloadSnapshot(UUID tenantId, String snapshotId) {
            downloads.incrementAndGet();
            return new SnapshotBytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/csv");
        }

        @Override public InitUploadResponse initUpload(UUID t, UUID d, long s, String c) { throw new UnsupportedOperationException(); }
        @Override public void uploadToS3(String u, byte[] b, String c) { throw new UnsupportedOperationException(); }
        @Override public String completeUpload(UUID t, String u, String h, long s) { throw new UnsupportedOperationException(); }
        @Override public String startImport(UUID t, UUID d, String c, String q) { throw new UnsupportedOperationException(); }
        @Override public LakeJob getLakeJob(UUID t, String j) { throw new UnsupportedOperationException(); }
        @Override public String computeSha256(byte[] b) { throw new UnsupportedOperationException(); }
    }

    private static final String CSV = "id,name\n1,ada\n2,grace\n3,katherine\n";

    private static long rowsIn(Path parquet) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM read_parquet('"
                     + parquet.toAbsolutePath() + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ── the basic contract ───────────────────────────────────────────────────

    @Test
    @DisplayName("a second request for the same snapshot does not hit the lake")
    void secondRequestIsAHit(@TempDir Path dir) {
        FakeLake lake = new FakeLake(CSV);
        SnapshotCache cache = new SnapshotCache(lake, dir.toString(), 100_000_000L);

        Optional<Path> first = cache.get(TENANT_A, "snap-1", "CSV");
        Optional<Path> second = cache.get(TENANT_A, "snap-1", "CSV");

        assertThat(first).isPresent();
        assertThat(second).isPresent().contains(first.get());
        assertThat(lake.downloads).hasValue(1);
        assertThat(cache.stats().get("hits")).isEqualTo(1L);
        assertThat(cache.stats().get("misses")).isEqualTo(1L);
    }

    @Test
    @DisplayName("CSV is transcoded to Parquet, so the cached file is columnar and readable")
    void csvIsTranscoded(@TempDir Path dir) throws Exception {
        SnapshotCache cache = new SnapshotCache(new FakeLake(CSV), dir.toString(), 100_000_000L);

        Path cached = cache.get(TENANT_A, "snap-1", "CSV").orElseThrow();

        assertThat(cached.getFileName().toString()).endsWith(".parquet");
        assertThat(rowsIn(cached))
                .as("the transcode must preserve the rows, not just the bytes")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a snapshot that is already Parquet is stored without a pointless rewrite")
    void parquetIsStoredAsIs(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("src.parquet");
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM range(7) t(n)) TO '" + source.toAbsolutePath() + "' (FORMAT PARQUET)");
        }
        byte[] bytes = Files.readAllBytes(source);

        LakeClient lake = new FakeLake("") {
            @Override public String downloadSnapshotTo(UUID t, String s, Path target) {
                try { Files.write(target, bytes); } catch (Exception e) { throw new RuntimeException(e); }
                return "application/octet-stream";
            }
        };
        SnapshotCache cache = new SnapshotCache(lake, dir.toString(), 100_000_000L);

        Path cached = cache.get(TENANT_A, "snap-p", "PARQUET").orElseThrow();
        assertThat(rowsIn(cached)).isEqualTo(7);
    }

    // ── the key ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the tenant is part of the key — a guessed snapshot id cannot cross tenants")
    void tenantIsInTheKey(@TempDir Path dir) {
        FakeLake lake = new FakeLake(CSV);
        SnapshotCache cache = new SnapshotCache(lake, dir.toString(), 100_000_000L);

        Path a = cache.get(TENANT_A, "same-id", "CSV").orElseThrow();
        Path b = cache.get(TENANT_B, "same-id", "CSV").orElseThrow();

        assertThat(a).isNotEqualTo(b);
        assertThat(lake.downloads)
                .as("tenant B must not be served tenant A's file")
                .hasValue(2);
    }

    @Test
    @DisplayName("the filename is a hash — nothing user-controlled reaches the path")
    void filenameIsHashed(@TempDir Path dir) {
        SnapshotCache cache = new SnapshotCache(new FakeLake(CSV), dir.toString(), 100_000_000L);

        Path cached = cache.get(TENANT_A, "../../etc/passwd", "CSV").orElseThrow();

        assertThat(cached.getParent()).isEqualTo(cache.tenantRoot(TENANT_A).orElseThrow());
        assertThat(cached.getParent().getParent()).isEqualTo(dir);
        assertThat(cached.getFileName().toString()).matches("[0-9a-f]{64}\\.parquet");
    }

    // ── eviction ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the least recently used entry is evicted, not the least recently added")
    void evictionIsLru(@TempDir Path dir) {
        long entry = entrySize(dir);
        // Room for four entries and a half. Must be at least 4x an entry, or the per-entry
        // ceiling (maxBytes / 4) rejects every admission — see cacheTooSmallForItsSnapshots.
        long budget = entry * 4 + entry / 2;

        FakeLake lake = new FakeLake(CSV);
        SnapshotCache cache = new SnapshotCache(lake, dir.toString(), budget);
        for (String id : List.of("a", "b", "c", "d")) {
            cache.get(TENANT_A, id, "CSV");
        }
        cache.get(TENANT_A, "a", "CSV");   // touch a — b is now the eldest by *access* order
        cache.get(TENANT_A, "e", "CSV");   // admitting e must evict exactly one

        assertThat(cache.stats().get("entries")).isEqualTo(4L);
        assertThat(cache.stats().get("bytes")).isLessThanOrEqualTo(budget);

        // The distinguishing assertion: a was added first but used most recently, so it must
        // have survived and b must not have. Counting downloads is how we can tell.
        int before = lake.downloads.get();
        cache.get(TENANT_A, "a", "CSV");
        assertThat(lake.downloads.get())
                .as("a was touched, so it must still be cached")
                .isEqualTo(before);
        cache.get(TENANT_A, "b", "CSV");
        assertThat(lake.downloads.get())
                .as("b was the least recently used, so it must have been evicted")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("a budget under 4x the snapshot size caches nothing — a real misconfiguration")
    void cacheTooSmallForItsSnapshots(@TempDir Path dir) {
        // The per-entry ceiling is maxBytes/4, so a cache sized below four snapshots admits
        // none of them and every query falls back. Silent, and worth a test so the interaction
        // between the two limits is documented rather than discovered in production.
        long entry = entrySize(dir);
        SnapshotCache cache = new SnapshotCache(new FakeLake(CSV), dir.toString(), entry * 2);

        assertThat(cache.get(TENANT_A, "a", "CSV")).isEmpty();
        assertThat(cache.stats().get("entries")).isZero();
    }

    /** The on-disk size of one cached entry for {@link #CSV}, measured rather than guessed. */
    private static long entrySize(Path dir) {
        long size = new SnapshotCache(new FakeLake(CSV), dir.toString(), 100_000_000L)
                .get(TENANT_A, "probe", "CSV")
                .map(p -> {
                    try { return Files.size(p); } catch (Exception e) { throw new RuntimeException(e); }
                })
                .orElseThrow();
        clean(dir);
        return size;
    }

    @Test
    @DisplayName("a snapshot over the per-entry ceiling is not cached at all")
    void oversizedEntryIsRejected(@TempDir Path dir) {
        // One enormous snapshot must not be admitted and evict the whole working set. The caller
        // falls back to a direct read, which is why this returns empty rather than throwing.
        StringBuilder big = new StringBuilder("id\n");
        for (int i = 0; i < 20_000; i++) {
            big.append(i).append('\n');
        }
        SnapshotCache cache = new SnapshotCache(new FakeLake(big.toString()), dir.toString(), 4_000L);

        assertThat(cache.get(TENANT_A, "huge", "CSV")).isEmpty();
        assertThat(cache.stats().get("entries")).isZero();
    }

    // ── failure must never break a query ─────────────────────────────────────

    @Test
    @DisplayName("max-bytes 0 disables the cache entirely")
    void zeroBudgetDisables(@TempDir Path dir) {
        FakeLake lake = new FakeLake(CSV);
        SnapshotCache cache = new SnapshotCache(lake, dir.toString(), 0L);

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.get(TENANT_A, "snap-1", "CSV")).isEmpty();
        assertThat(lake.downloads).as("a disabled cache must not even fetch").hasValue(0);
    }

    @Test
    @DisplayName("a lake failure yields empty, so the caller can fall back")
    void lakeFailureIsSwallowed(@TempDir Path dir) {
        LakeClient exploding = new FakeLake(CSV) {
            @Override public String downloadSnapshotTo(UUID t, String s, Path target) {
                throw new RuntimeException("lake is down");
            }
        };
        SnapshotCache cache = new SnapshotCache(exploding, dir.toString(), 100_000_000L);

        assertThat(cache.get(TENANT_A, "snap-1", "CSV")).isEmpty();
    }

    @Test
    @DisplayName("unparseable content fails the transcode and yields empty rather than a bad file")
    void transcodeFailureLeavesNothingBehind(@TempDir Path dir) throws Exception {
        SnapshotCache cache = new SnapshotCache(
                new FakeLake("{ this is not json at all ["), dir.toString(), 100_000_000L);

        assertThat(cache.get(TENANT_A, "bad", "JSON")).isEmpty();
        try (var stream = Files.walk(dir)) {
            assertThat(stream.filter(Files::isRegularFile).toList())
                    .as("no .part or half-written .parquet may survive a failed fill")
                    .isEmpty();
        }
    }

    // ── concurrency and restart ──────────────────────────────────────────────

    @Test
    @DisplayName("concurrent requests for the same snapshot cause one download, not N")
    void concurrentMissesShareOneFill(@TempDir Path dir) throws Exception {
        FakeLake lake = new FakeLake(CSV);
        lake.gate = new CountDownLatch(1);
        SnapshotCache cache = new SnapshotCache(lake, dir.toString(), 100_000_000L);

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Optional<Path>> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                ready.countDown();
                results.add(cache.get(TENANT_A, "shared", "CSV"));
                done.countDown();
            });
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        lake.gate.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

        assertThat(lake.downloads)
                .as("the per-key lock must collapse N concurrent misses into one fill")
                .hasValue(1);
        assertThat(results).hasSize(threads).allMatch(Optional::isPresent);
    }

    @Test
    @DisplayName("a restart adopts what the previous process left on disk")
    void restartIsNotACold(@TempDir Path dir) {
        FakeLake first = new FakeLake(CSV);
        new SnapshotCache(first, dir.toString(), 100_000_000L).get(TENANT_A, "snap-1", "CSV");
        assertThat(first.downloads).hasValue(1);

        FakeLake second = new FakeLake(CSV);
        SnapshotCache restarted = new SnapshotCache(second, dir.toString(), 100_000_000L);

        assertThat(restarted.get(TENANT_A, "snap-1", "CSV")).isPresent();
        assertThat(second.downloads).as("the file survived, so no refetch").hasValue(0);
        assertThat(restarted.stats().get("entries")).isEqualTo(1L);
    }

    // ── tenant separation on disk ────────────────────────────────────────────

    @Test
    @DisplayName("each tenant's snapshots live under a directory of its own — the lockdown allowlists only that one")
    void tenantsGetSeparateDirectories(@TempDir Path dir) {
        SnapshotCache cache = new SnapshotCache(new FakeLake(CSV), dir.toString(), 100_000_000L);

        Path a = cache.get(TENANT_A, "snap-1", "CSV").orElseThrow();
        Path b = cache.get(TENANT_B, "snap-1", "CSV").orElseThrow();
        Path rootA = cache.tenantRoot(TENANT_A).orElseThrow();
        Path rootB = cache.tenantRoot(TENANT_B).orElseThrow();

        assertThat(rootA).isNotEqualTo(rootB);
        assertThat(rootA.getParent()).isEqualTo(dir);
        assertThat(a).startsWith(rootA);
        assertThat(a.startsWith(rootB)).isFalse();
        assertThat(b).startsWith(rootB);
        assertThat(b.startsWith(rootA)).isFalse();
        // Hashed, like the file names: nothing user-controlled reaches a path.
        assertThat(rootA.getFileName().toString()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("a flat file from the old layout is cleared at startup — no tenant could ever be served it")
    void legacyFlatFilesAreCleared(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("a".repeat(64) + ".parquet");
        Files.writeString(legacy, "stale");

        SnapshotCache cache = new SnapshotCache(new FakeLake(CSV), dir.toString(), 100_000_000L);

        assertThat(legacy).doesNotExist();
        assertThat(cache.stats().get("entries")).isEqualTo(0L);
    }

    /** Empties {@code dir}, the per-tenant subdirectories included; {@code dir} itself stays. */
    private static void clean(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) { } });
        } catch (Exception ignore) { }
    }
}

package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wrapper-output cache — the query engine's "index": a {@code w_} view's output materialised
 * once as join-key-sorted Parquet and rebound for every later query.
 *
 * <p>Everything here runs against a REAL DuckDB: real fills, real rebinds, real queries through
 * the full {@code src_ → w_ → u_ → int_} view stack, under the real filesystem lockdown. What is
 * NOT proven here is that the sort makes anything <em>faster</em> — machine-independent tests
 * cannot assert wall-clock (the perf-suite rule), so the speedup claim is the perf report's to
 * make, not this file's.
 */
class WrapperOutputCacheTest {

    private static final String NAME = "http://ex/prop/name";
    private static final String AUTHOR = "http://ex/prop/author_id";
    private static final String CLASS_IRI = "urn:IResearcher";
    private static final String W_VIEW = "w_researchers_IResearcher";
    private static final String WRAPPER =
            "SELECT UPPER(name) AS uname, author_id FROM ${SNAPSHOT} WHERE h_index >= 0";

    private static final UUID TENANT = UUID.randomUUID();

    @TempDir Path dataDir;
    @TempDir Path cacheDir;

    private File source;

    @BeforeEach
    void writeSource() throws Exception {
        source = dataDir.resolve("researchers.json").toFile();
        try (FileWriter fw = new FileWriter(source)) {
            // Deliberately NOT ordered by author_id, and one row the wrapper filters out.
            fw.write("[{\"name\":\"carol\",\"author_id\":3,\"h_index\":4},"
                    + "{\"name\":\"ada\",\"author_id\":1,\"h_index\":5},"
                    + "{\"name\":\"neg\",\"author_id\":9,\"h_index\":-1},"
                    + "{\"name\":\"bob\",\"author_id\":2,\"h_index\":2}]");
        }
    }

    private DuckDbExecutor.SnapshotResolver localResolver() {
        return ds -> "read_json_auto('" + source.getAbsolutePath() + "')";
    }

    private MappingCatalog catalog() {
        MappingCatalog.SourceView src = new MappingCatalog.SourceView(
                "researchers", WRAPPER, Map.of(NAME, "uname", AUTHOR, "author_id"));
        MappingCatalog.IntegratedClass cls =
                new MappingCatalog.IntegratedClass(CLASS_IRI, List.of(src));
        // The self-join gives the class a join key, which is what earns author_id its sort slot.
        return new MappingCatalog(Map.of(CLASS_IRI, cls),
                List.of(new MappingCatalog.JoinProperty(
                        "urn:collab", CLASS_IRI, CLASS_IRI, AUTHOR, AUTHOR)),
                List.of(), "v1");
    }

    private Map<String, WrapperOutputCache.Spec> specs(List<String> sortColumns) {
        return Map.of(W_VIEW, new WrapperOutputCache.Spec("researchers",
                WrapperOutputCache.key(TENANT, "snap-1", WRAPPER, sortColumns), sortColumns));
    }

    private static List<List<Object>> rows(DuckDbExecutor exec) throws Exception {
        return exec.run("SELECT \"" + NAME + "\", \"" + AUTHOR + "\""
                + " FROM int_IResearcher ORDER BY \"" + AUTHOR + "\"").rows();
    }

    @Test
    @DisplayName("first prepare fills, later queries are served from the cache — same rows, under lockdown")
    void fillsOnceThenServesFromCache() throws Exception {
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        List<Path> roots = List.of(dataDir, cacheDir);

        List<List<Object>> first;
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.restrictFilesystemTo(roots)
                    .withWrapperOutputCache(cache, specs(List.of("author_id")))
                    .prepare();
            first = rows(exec);
            // The w_ view was rebound to the cached Parquet — visible in the connection's own
            // view catalog, which is the executable truth, not a string we composed.
            List<List<Object>> viewSql = exec.run(
                    "SELECT sql FROM duckdb_views() WHERE view_name = '" + W_VIEW + "'").rows();
            assertThat(viewSql).hasSize(1);
            assertThat((String) viewSql.get(0).get(0)).contains("read_parquet");
        }
        assertThat(first).containsExactly(
                List.of("ADA", 1L), List.of("BOB", 2L), List.of("CAROL", 3L));
        assertThat(cache.stats().get("entries")).isEqualTo(1L);

        // Rewrite the underlying source: a second executor with the SAME spec must answer from
        // the cache, not the file — which is exactly the immutability contract the key leans on
        // (a real refresh mints a new snapshotId and therefore a new key; this test holds the id
        // fixed to make a hit observable as a fact rather than a counter).
        try (FileWriter fw = new FileWriter(source)) {
            fw.write("[{\"name\":\"zed\",\"author_id\":7,\"h_index\":1}]");
        }
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.restrictFilesystemTo(roots)
                    .withWrapperOutputCache(cache, specs(List.of("author_id")))
                    .prepare();
            assertThat(rows(exec)).isEqualTo(first);
        }
        assertThat(cache.stats().get("hits")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("the cached Parquet is physically sorted by the class's join key")
    void cachedParquetIsSortedByJoinKey() throws Exception {
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.withWrapperOutputCache(cache, specs(List.of("author_id"))).prepare();
            rows(exec);
        }
        Path cached = cache.get(WrapperOutputCache.key(
                TENANT, "snap-1", WRAPPER, List.of("author_id"))).orElseThrow();

        // Physical row order, read back without any ORDER BY — this is what zone maps see.
        List<Long> physicalOrder = new ArrayList<>();
        try (Connection scratch = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = scratch.createStatement();
             ResultSet rs = st.executeQuery("SELECT author_id FROM read_parquet('"
                     + cached.toAbsolutePath() + "')")) {
            while (rs.next()) {
                physicalOrder.add(rs.getLong(1));
            }
        }
        assertThat(physicalOrder).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("a federated read expression is never cached, whatever the spec claims")
    void federatedReadExpressionIsNeverCached() throws Exception {
        // A local read whose expression carries the federation marker — the same substring
        // signal isFederated keys on for real s3:// reads — so the DDL still executes offline
        // while the executor's re-check sees a live scan. A frozen copy of a federated source
        // would be a wrong answer, so the guard must win over the spec.
        DuckDbExecutor.SnapshotResolver federatedLooking = ds ->
                "read_json_auto('" + source.getAbsolutePath() + "') /* s3:// */";
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), federatedLooking)) {
            exec.withWrapperOutputCache(cache, specs(List.of("author_id"))).prepare();
            assertThat(rows(exec)).hasSize(3);
            List<List<Object>> viewSql = exec.run(
                    "SELECT sql FROM duckdb_views() WHERE view_name = '" + W_VIEW + "'").rows();
            assertThat((String) viewSql.get(0).get(0)).doesNotContain("read_parquet");
        }
        assertThat(cache.stats().get("entries")).isEqualTo(0L);
    }

    @Test
    @DisplayName("a failing fill leaves the logical wrapper view standing — the query still answers")
    void failingFillFallsBackToLogicalWrapper() throws Exception {
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        // A sort column the wrapper output does not have: the fill's COPY fails, the cache must
        // swallow it, and the query must run exactly as it would have without a cache.
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.withWrapperOutputCache(cache, specs(List.of("no_such_column"))).prepare();
            assertThat(rows(exec)).hasSize(3);
        }
        assertThat(cache.stats().get("entries")).isEqualTo(0L);
    }

    @Test
    @DisplayName("an over-ceiling output is not cached; the query is unaffected")
    void overCeilingOutputIsNotCached() throws Exception {
        // max-bytes 4 → per-entry ceiling 1 byte: every fill lands over it.
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 4);
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.withWrapperOutputCache(cache, specs(List.of("author_id"))).prepare();
            assertThat(rows(exec)).hasSize(3);
        }
        assertThat(cache.stats().get("entries")).isEqualTo(0L);
        assertThat(cache.stats().get("refusedKeys")).isEqualTo(1L);
    }

    @Test
    @DisplayName("fill admission is measured ONCE: a refused key never pays the COPY again")
    void refusedKeyIsNeverRetried() throws Exception {
        // The fill-admission decision is the one real cost choice this cache makes (a cached
        // leaf strictly dominates its uncached twin once it exists, so nothing upstream has a
        // choice to make). Before refusedKeys, an over-ceiling class re-ran the full COPY on
        // every query, rediscovered the same verdict, and deleted the result — forever.
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 4);
        String key = WrapperOutputCache.key(TENANT, "snap-1", WRAPPER, List.of("author_id"));
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.withWrapperOutputCache(cache, specs(List.of("author_id"))).prepare();
            rows(exec);
        }
        assertThat(cache.stats().get("refusedKeys")).isEqualTo(1L);

        // The proof no COPY is re-attempted: a statement from a CLOSED connection would throw
        // the moment anything executed on it. fillFrom must short-circuit before touching it.
        Connection closed = DriverManager.getConnection("jdbc:duckdb:");
        Statement deadStmt = closed.createStatement();
        closed.close();
        assertThat(cache.fillFrom(key, deadStmt,
                DuckDbExecutor.fillSelect(W_VIEW, List.of("author_id")))).isEmpty();

        // And a second full prepare over the same spec still answers correctly, uncached.
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.withWrapperOutputCache(cache, specs(List.of("author_id"))).prepare();
            assertThat(rows(exec)).hasSize(3);
        }
        assertThat(cache.stats().get("refusedKeys")).isEqualTo(1L);
        assertThat(cache.stats().get("entries")).isEqualTo(0L);
    }

    @Test
    @DisplayName("a failed fill is remembered too — no per-query retry of a COPY that cannot succeed")
    void failedFillIsRemembered() throws Exception {
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        // A sort column the wrapper output does not have fails every COPY identically.
        for (int i = 0; i < 2; i++) {
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
                exec.withWrapperOutputCache(cache, specs(List.of("no_such_column"))).prepare();
                assertThat(rows(exec)).hasSize(3);
            }
        }
        assertThat(cache.stats().get("refusedKeys")).isEqualTo(1L);
        assertThat(cache.stats().get("entries")).isEqualTo(0L);
    }

    @Test
    @DisplayName("a disabled cache is inert")
    void disabledCacheIsInert() throws Exception {
        WrapperOutputCache cache = WrapperOutputCache.disabled();
        assertThat(cache.root()).isEmpty();
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.withWrapperOutputCache(cache, specs(List.of("author_id"))).prepare();
            assertThat(rows(exec)).hasSize(3);
        }
    }

    // ── the operator's participation rules (what may be cached at all) ──

    private static MappingCatalog.SourceView sourceView(String wrapper, String expansionStatus) {
        return new MappingCatalog.SourceView("researchers", wrapper,
                Map.of(NAME, "uname", AUTHOR, "author_id"), 0L, List.of(),
                "urn:src:Researcher", Map.of(), Map.of(), expansionStatus);
    }

    @Test
    @DisplayName("spec builder: EXPANDED, snapshot-less and identity-no-key classes are skipped; keys bind snapshot + wrapper + sort")
    void specBuilderSkipRules() {
        Map<String, QueryEngineOperator.Snapshot> snaps = Map.of(
                "researchers", new QueryEngineOperator.Snapshot("snap-1", "JSON", "researchers.json"));

        MappingCatalog withJoin = catalog();
        Map<String, WrapperOutputCache.Spec> normal =
                QueryEngineOperator.wrapperCacheSpecs(TENANT, withJoin, snaps);
        assertThat(normal).containsKey(W_VIEW);
        assertThat(normal.get(W_VIEW).sortColumns()).containsExactly("author_id");

        // A placement override excludes the dataset: a forced-MATERIALIZED leaf serves the row
        // snapshot while this spec's key names the graph pin — filing those rows under the graph
        // key would be cache poisoning, not a miss.
        assertThat(QueryEngineOperator.wrapperCacheSpecs(TENANT, withJoin, snaps,
                Map.of("researchers", "MATERIALIZED"))).isEmpty();

        // EXPANDED: the planner reads src_ directly, a cached w_ would sit unread on the fast path.
        MappingCatalog expanded = new MappingCatalog(Map.of(CLASS_IRI,
                new MappingCatalog.IntegratedClass(CLASS_IRI,
                        List.of(sourceView(WRAPPER, "EXPANDED")))),
                withJoin.joinProperties(), List.of(), "v1");
        assertThat(QueryEngineOperator.wrapperCacheSpecs(TENANT, expanded, snaps)).isEmpty();

        // No snapshot in the graph → nothing content-addressed to key on.
        assertThat(QueryEngineOperator.wrapperCacheSpecs(TENANT, withJoin, Map.of())).isEmpty();

        // Identity wrapper with no join keys: a byte-for-byte duplicate of the snapshot cache.
        MappingCatalog identityNoJoin = new MappingCatalog(Map.of(CLASS_IRI,
                new MappingCatalog.IntegratedClass(CLASS_IRI,
                        List.of(new MappingCatalog.SourceView("researchers",
                                "SELECT * FROM ${SNAPSHOT}",
                                Map.of(NAME, "uname", AUTHOR, "author_id"))))),
                List.of(), List.of(), "v1");
        assertThat(QueryEngineOperator.wrapperCacheSpecs(TENANT, identityNoJoin, snaps)).isEmpty();

        // The identity wrapper WITH a join key is cached — the sort is the value.
        MappingCatalog identityWithJoin = new MappingCatalog(Map.of(CLASS_IRI,
                new MappingCatalog.IntegratedClass(CLASS_IRI,
                        List.of(new MappingCatalog.SourceView("researchers",
                                "SELECT * FROM ${SNAPSHOT}",
                                Map.of(NAME, "uname", AUTHOR, "author_id"))))),
                withJoin.joinProperties(), List.of(), "v1");
        assertThat(QueryEngineOperator.wrapperCacheSpecs(TENANT, identityWithJoin, snaps))
                .containsKey(W_VIEW);

        // A wrapper edit changes the key — the cache's whole invalidation story.
        Map<String, QueryEngineOperator.Snapshot> sameSnaps = snaps;
        MappingCatalog edited = new MappingCatalog(Map.of(CLASS_IRI,
                new MappingCatalog.IntegratedClass(CLASS_IRI,
                        List.of(new MappingCatalog.SourceView("researchers",
                                WRAPPER + " AND h_index < 100",
                                Map.of(NAME, "uname", AUTHOR, "author_id"))))),
                withJoin.joinProperties(), List.of(), "v1");
        assertThat(QueryEngineOperator.wrapperCacheSpecs(TENANT, edited, sameSnaps)
                .get(W_VIEW).key())
                .isNotEqualTo(normal.get(W_VIEW).key());
    }

    @Test
    @DisplayName("a fill lands under its tenant's own directory — the lockdown allowlists only that one")
    void fillLandsUnderItsTenantsDirectory() throws Exception {
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.withWrapperOutputCache(cache, specs(List.of("author_id"))).prepare();
            rows(exec);
        }
        Path cached = cache.get(WrapperOutputCache.key(
                TENANT, "snap-1", WRAPPER, List.of("author_id"))).orElseThrow();
        Path mine = cache.tenantRoot(TENANT).orElseThrow();
        Path other = cache.tenantRoot(UUID.randomUUID()).orElseThrow();

        assertThat(mine.getParent()).isEqualTo(cacheDir);
        assertThat(cached).startsWith(mine);
        assertThat(cached.startsWith(other)).isFalse();
        assertThat(mine).isNotEqualTo(other);
    }

    @Test
    @DisplayName("a key the engine did not build never reaches the filesystem")
    void foreignKeyIsRefused() {
        WrapperOutputCache cache = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        assertThat(cache.get("../../etc/passwd")).isEmpty();
        assertThat(cache.get("a".repeat(64))).isEmpty();
    }

    @Test
    @DisplayName("restarted cache adopts existing files: a fresh instance over the same dir serves the old fill")
    void restartAdoptsExistingFiles() throws Exception {
        WrapperOutputCache first = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), localResolver())) {
            exec.withWrapperOutputCache(first, specs(List.of("author_id"))).prepare();
            rows(exec);
        }
        WrapperOutputCache restarted = new WrapperOutputCache(cacheDir.toString(), 64L * 1024 * 1024);
        assertThat(restarted.stats().get("entries")).isEqualTo(1L);
        assertThat(restarted.get(WrapperOutputCache.key(
                TENANT, "snap-1", WRAPPER, List.of("author_id")))).isPresent();
    }
}

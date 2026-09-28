package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.operators.queryengine.WrapperOutputCache;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Numbers, not a gate. <b>Nothing here asserts a duration.</b>
 *
 * <pre>
 * make perf
 * ./gradlew :aran-operators:query-engine:test --tests '*PerfBenchmarkTest' -Daran.perf=true
 * </pre>
 *
 * <p>Opt-in through {@code @EnabledIfSystemProperty} rather than a JUnit tag. The repo's one
 * tag defaults to <em>include</em> and is dropped by a {@code -P} flag in a single module's
 * build; mirroring that would put a multi-minute benchmark into every {@code ./gradlew test},
 * and inverting it would need a second, contradictory tag semantic. The root build already
 * forwards every {@code -Daran.*} into the test JVM, which is what makes this work from the
 * command line — the same mechanism {@code PlanCorpusTest}'s golden flag uses, and the same
 * shape as {@code RealSingerTapTest}'s opt-in.
 *
 * <p>The cost of an opt-in tier is that it can rot unnoticed. The mitigation is structural:
 * {@link PerfData}, {@link PerfCatalogs} and {@link PerfRun} are exercised by
 * {@link PerfGateTest} on every build, so only the scale and the iteration count are gated.
 *
 * <h2>Knobs</h2>
 * <ul>
 *   <li>{@code -Daran.perf.scale=large} — adds the top setting of each dimension (ten million
 *       rows, five hundred classes, six-hop chains). Long, and the depth cells may not finish.</li>
 *   <li>{@code -Daran.perf.iterations} (default 7), {@code -Daran.perf.warmup} (default 3)</li>
 *   <li>{@code -Daran.perf.cellBudgetMs} (default 20000) — a cell stops taking samples once it
 *       has spent this long, and a dimension stops escalating once a setting exceeds it. The
 *       report records how many samples a cell actually got and why it stopped.</li>
 *   <li>{@code -Daran.perf.only=<substring>} — run only dimensions whose name contains it.</li>
 * </ul>
 */
class PerfBenchmarkTest {

    private static final int WARMUP = Integer.getInteger("aran.perf.warmup", 3);
    private static final int ITERATIONS = Integer.getInteger("aran.perf.iterations", 7);
    private static final long CELL_BUDGET_MS = Long.getLong("aran.perf.cellBudgetMs", 20_000L);
    private static final boolean LARGE = "large".equals(System.getProperty("aran.perf.scale"));
    private static final String ONLY = System.getProperty("aran.perf.only", "");

    private static final List<PerfReport.Entry> ENTRIES = new ArrayList<>();

    private enum Mode {
        PLAN, PRUNE, EXECUTE, EXECUTE_UNPRUNED, CONCURRENT,
        /** EXECUTE with a per-cell {@link WrapperOutputCache}: warm-up fills, samples hit. */
        EXECUTE_CACHED,
        /** EXECUTE with a FRESH cache per sample: every sample pays the fill in prepare. */
        EXECUTE_CACHED_COLD,
        /** PLAN plus the {@link edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider}
         *  post-pass, timed as its own phase. */
        PLACEMENT
    }

    private record Cell(String dimension, String setting, String workload, String oql,
                        Map<String, Object> knobs, Mode mode, int threads,
                        Callable<PerfCatalogs.Built> supplier) {}

    /** A cell's cache and its participation map — only EXECUTE_CACHED cells carry one. */
    private record CacheCtx(WrapperOutputCache cache,
                            Map<String, WrapperOutputCache.Spec> specs) {}

    private static CacheCtx newCacheCtx(PerfCatalogs.Built built) {
        // A fresh directory per context: cache keys use a rows-aware snapshot surrogate, but a
        // cold cell's whole point is that nothing pre-exists, and warm cells must not adopt a
        // previous cell's files either.
        String dir = PerfRun.reportRoot().resolve("wrapper-cache")
                .resolve(java.util.UUID.randomUUID().toString()).toString();
        return new CacheCtx(new WrapperOutputCache(dir, 1L << 30),
                PerfCatalogs.wrapperCacheSpecs(built));
    }

    // ── Queries ──────────────────────────────────────────────────────────────────────

    private static final String Q_UNION   = "SELECT C0.key FROM C0";
    private static final String Q_GROUPBY = "SELECT C0.grp, COUNT(*) AS n FROM C0 GROUP BY C0.grp";
    private static final String Q_JOIN    = "SELECT C0.key, C1.grp FROM C0 -[j1]-> C1";
    private static final String Q_TRYCAST = "SELECT C0.key FROM C0 WHERE C0.year >= 0";
    /** Over {@link PerfCatalogs#transposeProbe} — the join-below-union CBO decision. */
    private static final String Q_TRANSPOSE =
            "SELECT Fact.factText, Dim.dimName AS name FROM Fact -[toDim]-> Dim";
    /** C1 unprojected, so a DECLARED edge + exact key lets {@code AranJoinRemoveRule} fire. */
    private static final String Q_ELIM = "SELECT C0.key FROM C0 -[j1]-> C1";

    private static String chainQuery(int depth) {
        StringBuilder q = new StringBuilder("SELECT C0.key, C").append(depth).append(".grp FROM C0");
        for (int i = 1; i <= depth; i++) {
            q.append(" -[j").append(i).append("]-> C").append(i);
        }
        return q.toString();
    }

    /** {@code n} OR-ed AND-groups — the widest predicate OQL's grammar can express. */
    private static String predicateQuery(int groups) {
        StringBuilder q = new StringBuilder("SELECT C0.key FROM C0 WHERE ");
        for (int i = 0; i < groups; i++) {
            if (i > 0) {
                q.append(" OR ");
            }
            q.append("C0.grp = 'g").append(i).append("' AND C0.amount > ").append(i);
        }
        return q.toString();
    }

    // ── The grid ─────────────────────────────────────────────────────────────────────

    private static List<List<Cell>> dimensions() {
        var base = PerfCatalogs.Knobs.benchmark();
        List<List<Cell>> all = new ArrayList<>();

        // Rows. The one dimension where DuckDB itself is doing the work.
        List<long[]> rowScales = LARGE
                ? List.of(new long[]{10_000}, new long[]{100_000}, new long[]{1_000_000}, new long[]{10_000_000})
                : List.of(new long[]{10_000}, new long[]{100_000}, new long[]{1_000_000});
        for (String workload : List.of("union", "groupby", "join", "trycast")) {
            List<Cell> cells = new ArrayList<>();
            for (long[] rows : rowScales) {
                long r = rows[0];
                String oql = switch (workload) {
                    case "groupby" -> Q_GROUPBY;
                    case "join" -> Q_JOIN;
                    case "trycast" -> Q_TRYCAST;
                    default -> Q_UNION;
                };
                cells.add(new Cell("rows/" + workload, r + " rows/source", workload, oql,
                        knobs("rowsPerSource", r), Mode.EXECUTE, 1,
                        () -> PerfCatalogs.star(base.withRows(r))));
            }
            all.add(cells);
        }

        // Catalog width, both wrapper shapes. WRAPPED is the one that reaches SourceCatalog.of
        // and aliasProjection; PLAIN never does, so the gap between them prices those paths.
        List<Integer> widths = LARGE ? List.of(50, 200, 500) : List.of(50, 200);
        for (PerfCatalogs.WrapperShape shape : PerfCatalogs.WrapperShape.values()) {
            List<Cell> cells = new ArrayList<>();
            for (int classes : widths) {
                cells.add(new Cell("catalog-width/" + shape, classes + " classes", "plan+prune",
                        Q_UNION, knobs("classes", classes, "wrapperShape", shape.name()),
                        Mode.PRUNE, 1,
                        () -> PerfCatalogs.starPlanOnly(base.withClasses(classes).withWrapperShape(shape))));
            }
            all.add(cells);
        }

        // Union fan-in.
        List<Integer> fanIns = LARGE ? List.of(2, 5, 10) : List.of(2, 5);
        List<Cell> fanCells = new ArrayList<>();
        for (int sources : fanIns) {
            fanCells.add(new Cell("union-fan-in", sources + " sources/class", "plan", Q_JOIN,
                    knobs("sourcesPerClass", sources), Mode.PLAN, 1,
                    () -> PerfCatalogs.chainPlanOnly(base.withSources(sources), 1)));
        }
        all.add(fanCells);

        // Join depth — the headline. Escalation stops itself when a setting blows the budget,
        // which for this dimension is the finding rather than an inconvenience.
        List<Integer> depths = LARGE ? List.of(1, 2, 3, 4, 5, 6) : List.of(1, 2, 3, 4);
        for (int sources : List.of(1, 2)) {
            List<Cell> cells = new ArrayList<>();
            for (int depth : depths) {
                cells.add(new Cell("join-depth/" + sources + "-source", depth + " hops", "plan",
                        chainQuery(depth), knobs("depth", depth, "sourcesPerClass", sources),
                        Mode.PLAN, 1,
                        () -> PerfCatalogs.chainPlanOnly(base.withSources(sources), depth)));
            }
            all.add(cells);
        }

        // Columns per class — quadratic through XsdTypes.widenedType inside getRowType.
        List<Integer> columns = LARGE ? List.of(6, 20, 60) : List.of(6, 20);
        List<Cell> colCells = new ArrayList<>();
        for (int cols : columns) {
            colCells.add(new Cell("columns-per-class", cols + " columns", "plan+prune", Q_UNION,
                    knobs("columnsPerClass", cols), Mode.PRUNE, 1,
                    () -> PerfCatalogs.starPlanOnly(base.withClasses(50).withColumns(cols))));
        }
        all.add(colCells);

        // Predicate complexity.
        List<Integer> groupCounts = LARGE ? List.of(2, 8, 24) : List.of(2, 8);
        List<Cell> predCells = new ArrayList<>();
        for (int groups : groupCounts) {
            predCells.add(new Cell("predicate-complexity", groups + " OR-groups", "plan",
                    predicateQuery(groups), knobs("orGroups", groups), Mode.PLAN, 1,
                    () -> PerfCatalogs.starPlanOnly(base)));
        }
        all.add(predCells);

        // GROUP BY cardinality.
        List<Cell> groupCells = new ArrayList<>();
        for (int card : List.of(10, 1_000, 100_000)) {
            groupCells.add(new Cell("group-cardinality", card + " groups", "groupby", Q_GROUPBY,
                    knobs("groupCardinality", card), Mode.EXECUTE, 1,
                    () -> PerfCatalogs.star(base.withGroupCardinality(card))));
        }
        all.add(groupCells);

        // Enforced constraints — each one is parsed by a throwaway probe plan at planner
        // construction, so this prices the constraint channel's planning cost.
        List<Integer> constraintCounts = LARGE ? List.of(0, 1, 4, 12) : List.of(0, 1, 4);
        List<Cell> dcCells = new ArrayList<>();
        for (int dcs : constraintCounts) {
            dcCells.add(new Cell("enforced-constraints", dcs + " per class", "plan", Q_UNION,
                    knobs("constraintsPerClass", dcs), Mode.PLAN, 1,
                    () -> PerfCatalogs.starPlanOnly(base.withConstraints(dcs))));
        }
        all.add(dcCells);

        // Dirty-cell fraction — what TRY_CAST costs when it is actually doing work.
        List<Cell> dirtyCells = new ArrayList<>();
        for (int nth : List.of(0, 10, 2)) {
            dirtyCells.add(new Cell("dirty-fraction", nth == 0 ? "clean" : "1 in " + nth,
                    "trycast", Q_TRYCAST, knobs("dirtyEveryNth", nth), Mode.EXECUTE, 1,
                    () -> PerfCatalogs.star(base.withDirtyEveryNth(nth))));
        }
        all.add(dirtyCells);

        // Pruned against unpruned, at a width where the difference is the whole point.
        List<Cell> pruneCells = new ArrayList<>();
        pruneCells.add(new Cell("pruning", "pruned", "union", Q_UNION, knobs("classes", 20),
                Mode.EXECUTE, 1, () -> PerfCatalogs.star(base.withClasses(20).withRows(10_000L))));
        pruneCells.add(new Cell("pruning", "unpruned", "union", Q_UNION, knobs("classes", 20),
                Mode.EXECUTE_UNPRUNED, 1,
                () -> PerfCatalogs.star(base.withClasses(20).withRows(10_000L))));
        all.add(pruneCells);

        // Wrapper-output cache A/B (V55). One join workload, three
        // states: cache off (the pre-cache baseline), cold (a fresh cache per sample, so every
        // sample pays the COPY-with-sort fill inside prepare), warm (a per-cell cache the warm-up
        // passes fill, so every sample hits and the w_ views scan sorted Parquet). The claims the
        // cache makes — wrapper runs once, zone maps prune the join — are priced by warm/off;
        // what a first query pays for them is priced by cold/off.
        List<Long> cacheScales = LARGE ? List.of(100_000L, 1_000_000L) : List.of(100_000L);
        List<Cell> cacheCells = new ArrayList<>();
        for (long r : cacheScales) {
            String at = " @" + r + " rows";
            cacheCells.add(new Cell("wrapper-cache", "off" + at, "join", Q_JOIN,
                    knobs("rowsPerSource", r, "cache", "off"), Mode.EXECUTE, 1,
                    () -> PerfCatalogs.star(base.withRows(r))));
            cacheCells.add(new Cell("wrapper-cache", "cold-fill" + at, "join", Q_JOIN,
                    knobs("rowsPerSource", r, "cache", "cold"), Mode.EXECUTE_CACHED_COLD, 1,
                    () -> PerfCatalogs.star(base.withRows(r))));
            cacheCells.add(new Cell("wrapper-cache", "warm" + at, "join", Q_JOIN,
                    knobs("rowsPerSource", r, "cache", "warm"), Mode.EXECUTE_CACHED, 1,
                    () -> PerfCatalogs.star(base.withRows(r))));
        }
        all.add(cacheCells);

        // The placement post-pass, priced against the optimize it follows — the number behind
        // the "post-pass, not RelSet alternatives" ruling (every leaf treated as a legal
        // candidate, the pass's worst case). Read `placement p50` against `optimize p50`.
        List<Cell> placementCells = new ArrayList<>();
        for (int depth : depths) {
            placementCells.add(new Cell("placement-decision", depth + " hops", "plan+placement",
                    chainQuery(depth), knobs("depth", depth), Mode.PLACEMENT, 1,
                    () -> PerfCatalogs.chainPlanOnly(base, depth)));
        }
        all.add(placementCells);

        // CBO decision: join-below-union transpose. Same fixture premise as
        // JoinUnionTransposeTest, but over computed statistics at scale — the report's nodeCounts
        // show which side the optimizer took, and the optimize p50 shows what deciding costs.
        List<Cell> transposeCells = new ArrayList<>();
        for (boolean unique : List.of(true, false)) {
            transposeCells.add(new Cell("cbo-transpose",
                    unique ? "dim-key-unique" : "dim-key-nonunique", "plan", Q_TRANSPOSE,
                    knobs("dimKeyUnique", unique), Mode.PLAN, 1,
                    () -> PerfCatalogs.transposeProbe(100_000, 10_000, 5, unique)));
        }
        all.add(transposeCells);

        // CBO decision: trust-tier-gated join elimination (Phase 4B). Identical catalogs but for
        // the DECLARED edge; the SCAN nodeCount (2 vs 1) is the decision, the optimize p50 its
        // price. Single-source classes because keyColumnOrder declares no key over a union.
        List<Cell> elimCells = new ArrayList<>();
        elimCells.add(new Cell("cbo-join-elimination", "hypothesis-edge", "plan", Q_ELIM,
                knobs("edgeTier", "HYPOTHESIS", "sourcesPerClass", 1), Mode.PLAN, 1,
                () -> PerfCatalogs.chainPlanOnly(base.withSources(1), 1)));
        elimCells.add(new Cell("cbo-join-elimination", "declared-edge", "plan", Q_ELIM,
                knobs("edgeTier", "DECLARED", "sourcesPerClass", 1), Mode.PLAN, 1,
                () -> PerfCatalogs.withDeclaredJoinEdges(
                        PerfCatalogs.chainPlanOnly(base.withSources(1), 1))));
        all.add(elimCells);

        // In-process concurrency: N threads, N independent in-memory DuckDBs.
        List<Integer> threadCounts = LARGE ? List.of(1, 4, 8) : List.of(1, 4);
        List<Cell> concCells = new ArrayList<>();
        for (int threads : threadCounts) {
            concCells.add(new Cell("concurrency", threads + " threads", "groupby", Q_GROUPBY,
                    knobs("threads", threads), Mode.CONCURRENT, threads,
                    () -> PerfCatalogs.star(base)));
        }
        all.add(concCells);

        return all.stream()
                .filter(cells -> ONLY.isEmpty() || cells.get(0).dimension().contains(ONLY))
                .toList();
    }

    private static Map<String, Object> knobs(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    // ── The runner ───────────────────────────────────────────────────────────────────

    /**
     * Runs on every build, flag or no flag.
     *
     * <p>Two jobs. It keeps the class discoverable — a disabled {@code @TestFactory} produces no
     * test instances at all, so {@code --tests '*PerfBenchmarkTest'} without the flag would fail
     * with "No tests found" rather than reporting a skip. And it is the standing answer to the
     * real cost of an opt-in tier: the grid below is only ever executed on demand, so without
     * something checking it every build, a query in it could stop parsing and nobody would learn
     * that until the next benchmark run — possibly months later.
     */
    @Test
    @DisplayName("every benchmark query parses and every dimension is named once")
    void gridIsWellFormed() {
        List<List<Cell>> grid = dimensions();
        assertThat(grid).isNotEmpty();

        Set<String> seen = new LinkedHashSet<>();
        for (List<Cell> cells : grid) {
            assertThat(cells).isNotEmpty();
            String dimension = cells.get(0).dimension();
            assertThat(seen.add(dimension)).as("dimension named twice: %s", dimension).isTrue();
            for (Cell cell : cells) {
                assertThat(cell.dimension()).isEqualTo(dimension);
                assertThatCode(() -> OqlParser.parse(cell.oql()))
                        .as("%s / %s: %s", dimension, cell.setting(), cell.oql())
                        .doesNotThrowAnyException();
            }
        }
    }

    // On the factory rather than the class: a class-level condition makes JUnit report nothing
    // at all, and `--tests '*PerfBenchmarkTest'` without the flag then fails the build with
    // "No tests found" instead of saying it was skipped.
    @EnabledIfSystemProperty(named = "aran.perf", matches = "true")
    @TestFactory
    Stream<DynamicTest> workloadsAcrossScales() {
        return dimensions().stream().map(cells -> DynamicTest.dynamicTest(
                cells.get(0).dimension(), () -> runDimension(cells)));
    }

    /**
     * Runs a dimension's settings in escalating order, and stops escalating once one of them
     * costs more than the cell budget.
     *
     * <p>Stopping rather than pushing on is the honest choice for the dimensions that blow up:
     * the next setting up from one that already took twenty seconds is not a slower measurement,
     * it is an unbounded one. The abandonment is recorded per cell, so the report says what was
     * not run instead of quietly ending the table.
     */
    private static void runDimension(List<Cell> cells) {
        boolean stop = false;
        String stopReason = "";
        for (Cell cell : cells) {
            if (stop) {
                ENTRIES.add(new PerfReport.Entry(cell.dimension(), cell.setting(), cell.workload(),
                        cell.oql(), cell.knobs(), "skipped", stopReason, List.of()));
                continue;
            }
            PerfReport.Entry entry = runCell(cell);
            ENTRIES.add(entry);

            if (!"ok".equals(entry.outcome())) {
                stop = true;
                stopReason = "not attempted: " + cell.setting() + " ended in " + entry.outcome();
            } else {
                long p50 = PerfRun.percentile(
                        entry.samples().stream().map(PerfRun.Sample::totalNanos).toList(), 0.50);
                if (p50 / 1_000_000 > CELL_BUDGET_MS) {
                    stop = true;
                    stopReason = "not attempted: " + cell.setting() + " already exceeded the "
                            + CELL_BUDGET_MS + " ms cell budget, so the next setting is unbounded";
                }
            }
        }
    }

    private static PerfReport.Entry runCell(Cell cell) {
        List<PerfRun.Sample> samples = new ArrayList<>();
        String note = "";
        try {
            PerfCatalogs.Built built = cell.supplier().call();
            // Per-cell cache: the warm-up passes below fill it, so EXECUTE_CACHED's measured
            // samples are hits (with -Daran.perf.warmup=0 the first sample pays the fill —
            // that is what EXECUTE_CACHED_COLD exists to measure deliberately).
            CacheCtx ctx = cell.mode() == Mode.EXECUTE_CACHED ? newCacheCtx(built) : null;

            // The deadline covers warm-up as well as measurement. On the dimensions that blow
            // up a single warm-up pass can cost more than the whole budget, and a budget that
            // only starts counting afterwards is not a budget.
            long deadline = System.nanoTime() + CELL_BUDGET_MS * 1_000_000L;

            // Warm-up drives both the JIT and, for executing cells, the page cache. It is not
            // JMH's steady-state detection and does not pretend to be.
            int warmed = 0;
            for (int i = 0; i < WARMUP && System.nanoTime() < deadline; i++) {
                once(cell, built, ctx);
                warmed++;
            }
            if (warmed < WARMUP) {
                note = "warm-up cut short at " + warmed + " of " + WARMUP
                        + " — a single pass costs more than the cell budget";
            }

            for (int i = 0; i < ITERATIONS; i++) {
                samples.addAll(once(cell, built, ctx));
                if (System.nanoTime() > deadline) {
                    note = (note.isBlank() ? "" : note + "; ")
                            + "stopped after " + samples.size() + " of " + ITERATIONS
                            + " iterations — cell budget reached";
                    break;
                }
            }
            System.out.printf("[perf] %-28s %-18s n=%d p50=%.1f ms%n",
                    cell.dimension(), cell.setting(), samples.size(),
                    PerfRun.percentile(samples.stream().map(PerfRun.Sample::totalNanos).toList(), 0.50)
                            / 1_000_000.0);
            return new PerfReport.Entry(cell.dimension(), cell.setting(), cell.workload(),
                    cell.oql(), cell.knobs(), "ok", note, samples);
        } catch (Throwable t) {
            // OutOfMemoryError included, deliberately. "It does not finish" is a result this
            // suite exists to record, not a reason to take the run down with it.
            System.out.printf("[perf] %-28s %-18s FAILED: %s%n",
                    cell.dimension(), cell.setting(), t.getClass().getSimpleName());
            return new PerfReport.Entry(cell.dimension(), cell.setting(), cell.workload(),
                    cell.oql(), cell.knobs(), t.getClass().getSimpleName(),
                    String.valueOf(t.getMessage()), samples);
        }
    }

    private static List<PerfRun.Sample> once(Cell cell, PerfCatalogs.Built built,
                                             CacheCtx ctx) throws Exception {
        return switch (cell.mode()) {
            case PLAN -> List.of(PerfRun.plan(built, cell.workload(), cell.oql()));
            case PRUNE -> List.of(PerfRun.prune(built, cell.workload(), cell.oql()));
            case EXECUTE -> List.of(PerfRun.execute(built, cell.workload(), cell.oql(), true, false));
            case EXECUTE_UNPRUNED ->
                    List.of(PerfRun.execute(built, cell.workload(), cell.oql(), false, false));
            case EXECUTE_CACHED -> List.of(PerfRun.execute(built, cell.workload(), cell.oql(),
                    true, false, ctx.cache(), ctx.specs()));
            case EXECUTE_CACHED_COLD -> {
                CacheCtx cold = newCacheCtx(built);
                yield List.of(PerfRun.execute(built, cell.workload(), cell.oql(),
                        true, false, cold.cache(), cold.specs()));
            }
            case PLACEMENT -> List.of(PerfRun.placement(built, cell.workload(), cell.oql()));
            case CONCURRENT -> PerfRun.concurrently(
                    java.util.Collections.nCopies(cell.threads(), built), cell.workload(), cell.oql());
        };
    }

    @AfterAll
    static void writeReport() throws Exception {
        if (ENTRIES.isEmpty()) {
            return;   // skipped run — do not overwrite a real report with an empty one
        }
        PerfReport.write(ENTRIES);
        System.out.println("[perf] report: " + PerfRun.reportRoot().toAbsolutePath() + "/report.md");
    }
}

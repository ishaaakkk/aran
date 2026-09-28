package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.PlacementConfig;
import edu.upc.dtak.aran.operators.queryengine.WrapperOutputCache;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlQuery;
import edu.upc.dtak.aran.operators.queryengine.plan.OqlPlanner;
import edu.upc.dtak.aran.operators.queryengine.plan.PlacementDecider;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanExecutor;
import edu.upc.dtak.aran.operators.queryengine.plan.PlannerBudget;
import edu.upc.dtak.aran.operators.queryengine.plan.Planning;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanShape;
import org.apache.calcite.rel.RelNode;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The production query path, timed phase by phase.
 *
 * <h2>Why not {@link QueryHarness#run}</h2>
 *
 * <p>{@code QueryHarness.run} constructs the executor and calls {@code prepare()} directly.
 * {@code QueryEngineOperator.executeSql} calls
 * {@code onlyClassesReferencedBy(sql).restrictFilesystemTo(...).prepare()}. The difference is not
 * cosmetic: without pruning, {@code prepare()} resolves every source of every class, which is the
 * behaviour {@code DdlPruningTest} exists to prevent. Measured through the harness, a wide-catalog
 * query would describe a path production does not take — so this class re-does the wiring rather
 * than borrowing it, and {@code QueryHarness} stays the plan-side driver the golden tests use.
 *
 * <h2>The JMH seam</h2>
 *
 * <p>{@link #execute} is a pure function of its arguments returning a value, so a future
 * {@code @Benchmark} is a wrapper with {@code @State} holding a {@link PerfCatalogs.Built} —
 * nothing here needs re-plumbing. What is genuinely absent versus JMH, and must not be pretended
 * away in any report built on this: fork isolation, blackholes, and steady-state detection.
 * Dead-code elimination is not among the gaps — every timed phase produces a value that is
 * consumed into a {@link Sample} and written out, so the compiler cannot elide it.
 */
final class PerfRun {

    private PerfRun() {}

    /** View names the emitted SQL may legitimately mention. */
    private static final Pattern VIEW_NAME = Pattern.compile("\\b(?:int_|u_|w_|src_|q_)\\w+");

    /**
     * One measured run. Durations are nanoseconds; every other component is deterministic — a
     * function of the catalog and the query, not of the machine — and is what the gate asserts.
     *
     * @param prepareNanos includes building the DDL and calling the resolver once per source, so
     *                     it is not comparable with {@code ddlStatements}, which is counted
     *                     separately and cheaply
     * @param placementNanos the {@link PlacementDecider} post-pass (leaf discovery + decision)
     *                       over the optimized plan — nonzero only for {@link #placement} runs,
     *                       whose {@code rowHash} then carries the decisions' consumed digest
     * @param allocatedBytes -1 when the VM does not expose per-thread allocation
     */
    record Sample(String workload, String oql,
                  long parseNanos, long plannerCtorNanos, long lowerNanos, long optimizeNanos,
                  long toSqlNanos, long pruneNanos, long prepareNanos, long runNanos,
                  long placementNanos,
                  long totalNanos, long allocatedBytes,
                  String sql, String compactShape, Map<String, Integer> nodeCounts,
                  int ddlStatements, int datasetsResolved, int viewsNamedBySql, int sqlLength,
                  int rows, int columns, long rowHash,
                  Planning planning) {}

    /** Plan only — no DuckDB, no data, no resolver. What the wide-catalog cases use. */
    static Sample plan(PerfCatalogs.Built b, String workload, String oql) {
        return planTimed(b, workload, oql, false);
    }

    /**
     * Plan, then run the {@link PlacementDecider} post-pass over the optimized tree and time it
     * as its own phase — the cost the placement design accepted instead of doubling the per-leaf
     * memo search space (the do-not-re-litigate argument in {@code PlacementDecider}'s javadoc).
     * Every leaf dataset is treated as a legal dual-access candidate, which is the pass's
     * worst case: production probes candidacy first and most catalogs have none.
     */
    static Sample placement(PerfCatalogs.Built b, String workload, String oql) {
        return planTimed(b, workload, oql, true);
    }

    private static Sample planTimed(PerfCatalogs.Built b, String workload, String oql,
                                    boolean withPlacement) {
        long t0 = System.nanoTime();
        long a0 = allocatedBytes();

        long s = System.nanoTime();
        OqlQuery parsed = OqlParser.parse(oql);
        long parseNanos = System.nanoTime() - s;

        s = System.nanoTime();
        OqlPlanner planner = new OqlPlanner(b.catalog(), b.enforced());
        long ctorNanos = System.nanoTime() - s;

        s = System.nanoTime();
        RelNode logical = planner.lower(parsed);
        long lowerNanos = System.nanoTime() - s;

        s = System.nanoTime();
        PlanExecutor.Optimized o = PlanExecutor.optimize(logical, PlannerBudget.DEFAULT);
        RelNode optimized = o.plan();
        long optimizeNanos = System.nanoTime() - s;

        s = System.nanoTime();
        String sql = PlanExecutor.toSqlFromOptimized(optimized);
        long toSqlNanos = System.nanoTime() - s;

        long placementNanos = 0L;
        long placementDigest = 0L;
        if (withPlacement) {
            PlacementConfig lambdas = PlacementConfig.defaults();
            s = System.nanoTime();
            Set<String> leaves = PlacementDecider.leafDatasets(optimized);
            Map<String, PlacementDecider.Candidate> candidates = new LinkedHashMap<>();
            for (String ds : leaves) {
                candidates.put(ds, new PlacementDecider.Candidate(ds, true, "benchmark: legal"));
            }
            Map<String, PlacementDecider.Decision> decisions = PlacementDecider.decide(
                    optimized, candidates, lambdas.liveLambda(), lambdas.localLambda());
            placementNanos = System.nanoTime() - s;
            // Consumed into the sample (rowHash slot — plan samples never use it otherwise) so
            // the timed pass cannot be dead-code-eliminated; see the class javadoc's JMH note.
            for (PlacementDecider.Decision d : decisions.values()) {
                placementDigest += (long) d.effectiveRows() + (long) d.fullRows()
                        + d.chosen().hashCode();
            }
        }

        PlanShape shape = PlanShape.of(optimized);
        long total = System.nanoTime() - t0;

        return new Sample(workload, oql, parseNanos, ctorNanos, lowerNanos, optimizeNanos,
                toSqlNanos, 0L, 0L, 0L, placementNanos, total, allocatedBytes() - a0,
                sql, shape.toCompactString(), nodeCounts(shape),
                0, 0, viewsNamedBySql(sql), sql.length(), 0, 0, placementDigest,
                o.planning());
    }

    /**
     * Plan and execute, wired as {@code QueryEngineOperator} wires it.
     *
     * @param prune    call {@code onlyClassesReferencedBy} — production always does; false is
     *                 kept so the benchmark can price what pruning buys
     * @param lockdown call {@code restrictFilesystemTo} over the generated-data root, the other
     *                 half of production's wiring
     */
    static Sample execute(PerfCatalogs.Built b, String workload, String oql,
                          boolean prune, boolean lockdown) throws Exception {
        return execute(b, workload, oql, prune, lockdown, null, Map.of());
    }

    /**
     * As {@link #execute(PerfCatalogs.Built, String, String, boolean, boolean)}, threading a
     * {@link WrapperOutputCache} through the executor exactly as {@code QueryEngineOperator}
     * does: the A/B seam the {@code wrapper-cache} dimension measures (V55). A cold
     * cache pays its fill (the {@code COPY … ORDER BY}) inside {@code prepareNanos}; a warm one
     * rebinds the {@code w_} views to the sorted Parquet and the win, if any, lands in
     * {@code prepareNanos} (the wrapper is not re-executed) and {@code runNanos} (zone-map
     * pruning on the join).
     */
    static Sample execute(PerfCatalogs.Built b, String workload, String oql,
                          boolean prune, boolean lockdown,
                          @Nullable WrapperOutputCache cache,
                          Map<String, WrapperOutputCache.Spec> cacheSpecs) throws Exception {
        long t0 = System.nanoTime();
        long a0 = allocatedBytes();
        int askedBefore = b.askedDatasets().size();

        long s = System.nanoTime();
        OqlQuery parsed = OqlParser.parse(oql);
        long parseNanos = System.nanoTime() - s;

        s = System.nanoTime();
        OqlPlanner planner = new OqlPlanner(b.catalog(), b.enforced());
        long ctorNanos = System.nanoTime() - s;

        s = System.nanoTime();
        RelNode logical = planner.lower(parsed);
        long lowerNanos = System.nanoTime() - s;

        s = System.nanoTime();
        PlanExecutor.Optimized o = PlanExecutor.optimize(logical, PlannerBudget.DEFAULT);
        RelNode optimized = o.plan();
        long optimizeNanos = System.nanoTime() - s;

        s = System.nanoTime();
        String sql = PlanExecutor.toSqlFromOptimized(optimized);
        long toSqlNanos = System.nanoTime() - s;

        PlanShape shape = PlanShape.of(optimized);

        long pruneNanos = 0L;
        long prepareNanos;
        long runNanos;
        DuckDbExecutor.QueryResult result;

        try (DuckDbExecutor exec = new DuckDbExecutor(b.catalog(), b.resolver(), b.enforced())) {
            if (cache != null) {
                exec.withWrapperOutputCache(cache, cacheSpecs);
            }
            if (prune) {
                // The setter computes eagerly, so this times referencedClasses at production's
                // own entry point rather than at a private one.
                s = System.nanoTime();
                exec.onlyClassesReferencedBy(sql);
                pruneNanos = System.nanoTime() - s;
            }
            if (lockdown) {
                exec.restrictFilesystemTo(List.of(PerfData.root().toAbsolutePath()));
            }
            s = System.nanoTime();
            // Inside prepare's timing on purpose: production's ConnectionSetup runs the same
            // statements inside prepare(), so the live side's per-query ATTACH is priced as it is.
            for (String statement : b.connectionSetupSql()) {
                exec.execute(statement);
            }
            exec.prepare();
            prepareNanos = System.nanoTime() - s;

            s = System.nanoTime();
            result = exec.run(sql);
            runNanos = System.nanoTime() - s;
        }

        long total = System.nanoTime() - t0;
        int resolved = b.askedDatasets().size() - askedBefore;

        return new Sample(workload, oql, parseNanos, ctorNanos, lowerNanos, optimizeNanos,
                toSqlNanos, pruneNanos, prepareNanos, runNanos, 0L, total, allocatedBytes() - a0,
                sql, shape.toCompactString(), nodeCounts(shape),
                0, resolved, viewsNamedBySql(sql), sql.length(),
                result.rows().size(), result.headers().size(), rowHash(result),
                o.planning());
    }

    /**
     * Plan, then prune, then count the DDL — the wide-catalog probe, with no data touched.
     *
     * <p>{@code onlyClassesReferencedBy} computes eagerly and never calls the resolver (only
     * {@code prepare()} does), so the whole per-query catalog cost can be priced without
     * generating a row. That matters because the sites this is aimed at —
     * {@code referencedClasses}'s per-name {@code Pattern.compile}, {@code Names.replaceAll},
     * and {@code SourceCatalog.of}'s quadratic scan — all scale with the catalog rather than
     * with the data, and mixing rows in would bury them.
     */
    static Sample prune(PerfCatalogs.Built b, String workload, String oql) throws Exception {
        Sample planned = plan(b, workload, oql);
        long pruneNanos;
        try (DuckDbExecutor exec = new DuckDbExecutor(b.catalog(), b.resolver(), b.enforced())) {
            long s = System.nanoTime();
            exec.onlyClassesReferencedBy(planned.sql());
            pruneNanos = System.nanoTime() - s;
        }
        int ddl = ddlStatements(b, null);
        return new Sample(workload, oql, planned.parseNanos(), planned.plannerCtorNanos(),
                planned.lowerNanos(), planned.optimizeNanos(), planned.toSqlNanos(),
                pruneNanos, 0L, 0L, 0L, planned.totalNanos() + pruneNanos, planned.allocatedBytes(),
                planned.sql(), planned.compactShape(), planned.nodeCounts(),
                ddl, 0, planned.viewsNamedBySql(), planned.sqlLength(), 0, 0, 0L,
                planned.planning());
    }

    /**
     * DDL statements for {@code required} classes, counted without touching the recording
     * resolver — the read expression cannot change the count, so a stub keeps the count cheap
     * and keeps {@code askedDatasets} honest about what execution really fetched.
     */
    static int ddlStatements(PerfCatalogs.Built b, Set<String> required) {
        return DuckDbExecutor.buildDdl(b.catalog(), ds -> "STUB", b.enforced(), required).size();
    }

    /**
     * An order-insensitive digest of a result.
     *
     * <p><b>Never commit one.</b> {@code DuckDbExecutor.jsonSafe}'s own javadoc records that
     * DuckDB 1.5 infers {@code HUGEINT} where 1.3 inferred {@code BIGINT} for the same input, so
     * the Java type of a cell — and therefore any hash over it — legitimately moves on an engine
     * bump. This is for differential comparisons <em>within one run</em>: pruned versus unpruned,
     * thread versus thread, scale versus scale.
     */
    static long rowHash(DuckDbExecutor.QueryResult result) {
        long h = 0L;
        for (List<Object> row : result.rows()) {
            h += row.hashCode();
        }
        return h;
    }

    /** Node kinds to occurrences, so a test can assert "exactly three scans" without a string match. */
    static Map<String, Integer> nodeCounts(PlanShape shape) {
        Map<String, Integer> counts = new TreeMap<>();
        countInto(shape.root(), counts);
        return counts;
    }

    private static void countInto(PlanShape.Node node, Map<String, Integer> counts) {
        counts.merge(node.kind(), 1, Integer::sum);
        for (PlanShape.Node input : node.inputs()) {
            countInto(input, counts);
        }
    }

    /**
     * Distinct generated view names the emitted SQL mentions. Catches a RelToSql regression the
     * plan shape cannot see — a plan can be the right shape and still unparse to SQL naming a
     * view that was never created.
     */
    static int viewsNamedBySql(String sql) {
        Set<String> names = new java.util.LinkedHashSet<>();
        Matcher m = VIEW_NAME.matcher(sql);
        while (m.find()) {
            names.add(m.group());
        }
        return names.size();
    }

    /**
     * {@code threads} concurrent executions of the same query, each on its own
     * {@link PerfCatalogs.Built} and therefore its own in-memory DuckDB — which is production's
     * shape (one executor per query), though not production's contention.
     */
    static List<Sample> concurrently(List<PerfCatalogs.Built> builts, String workload, String oql)
            throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(builts.size())) {
            List<Callable<Sample>> tasks = new ArrayList<>();
            for (PerfCatalogs.Built b : builts) {
                tasks.add(() -> execute(b, workload, oql, true, false));
            }
            List<Sample> samples = new ArrayList<>();
            for (Future<Sample> f : pool.invokeAll(tasks)) {
                samples.add(f.get());
            }
            return samples;
        }
    }

    /** Per-thread allocation, or -1 where the VM does not expose it. A steadier proxy than time. */
    static long allocatedBytes() {
        if (ManagementFactory.getThreadMXBean()
                instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemoryEnabled()) {
            return bean.getCurrentThreadAllocatedBytes();
        }
        return -1L;
    }

    /** Nearest-rank percentile. No interpolation: at seven samples it would invent precision. */
    static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    /** min / p50 / p90 / max over a phase, keyed for the report. Never a mean. */
    static Map<String, Long> summarise(List<Long> values) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("min", percentile(values, 0.0));
        out.put("p50", percentile(values, 0.50));
        out.put("p90", percentile(values, 0.90));
        out.put("max", values.isEmpty() ? 0L : values.stream().mapToLong(Long::longValue).max().orElse(0L));
        return out;
    }

    /** Where the benchmark tier writes. */
    static Path reportRoot() {
        return Path.of("build", "perf");
    }
}

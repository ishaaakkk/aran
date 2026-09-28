package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import edu.upc.dtak.aran.operators.queryengine.plan.OqlPlanner;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanExecutor;
import edu.upc.dtak.aran.operators.queryengine.plan.PlannerBudget;
import edu.upc.dtak.aran.operators.queryengine.plan.Planning;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanShape;
import org.apache.calcite.rel.RelNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test-only runner: drives an OQL string through the real engine pipeline
 * (OqlParser -> OqlPlanner -> PlanExecutor) and, for {@link #run}, executes the
 * emitted SQL against an in-memory DuckDB over JSON fixtures. Mirrors
 * QueryEngineOperator's own optimize/toSqlFromOptimized path.
 */
final class QueryHarness implements AutoCloseable {

    record Outcome(String sql, String optimizedPlan, PlanShape shape,
                   DuckDbExecutor.QueryResult result, Planning planning) {}

    private final List<Path> tempFiles = new ArrayList<>();

    /** Write a JSON array fixture to a temp file, tracked for cleanup. */
    Path writeJson(String prefix, String json) throws IOException {
        Path p = Files.createTempFile("aran-harness-" + prefix + "-", ".json");
        tempFiles.add(p);
        Files.writeString(p, json);
        return p;
    }

    /** Plan + emit SQL, no execution. */
    Outcome plan(MappingCatalog catalog, String oql) {
        return plan(catalog, Map.of(), oql);
    }

    /**
     * As above, with the class's enforced denial constraints handed to the planner — the same
     * map {@code QueryEngineOperator} passes, so the pulled-up predicates are real.
     */
    Outcome plan(MappingCatalog catalog, Map<String, List<String>> enforced, String oql) {
        RelNode logical = new OqlPlanner(catalog, enforced).lower(OqlParser.parse(oql));
        PlanExecutor.Optimized o = PlanExecutor.optimize(logical, PlannerBudget.DEFAULT);
        RelNode optimized = o.plan();
        return new Outcome(
                PlanExecutor.toSqlFromOptimized(optimized),
                PlanExecutor.planString(optimized),
                PlanShape.of(optimized),
                null, o.planning());
    }

    /** Plan, emit SQL, then execute against DuckDB over the resolver's views. */
    Outcome run(MappingCatalog catalog, DuckDbExecutor.SnapshotResolver resolver, String oql)
            throws Exception {
        return run(catalog, resolver, Map.of(), oql);
    }

    /**
     * As above, with {@code enforced} feeding <em>both</em> the planner and the view bodies —
     * the production invariant: the planner may only reason with constraints the DDL applies.
     */
    Outcome run(MappingCatalog catalog, DuckDbExecutor.SnapshotResolver resolver,
                Map<String, List<String>> enforced, String oql) throws Exception {
        RelNode logical = new OqlPlanner(catalog, enforced).lower(OqlParser.parse(oql));
        PlanExecutor.Optimized o = PlanExecutor.optimize(logical, PlannerBudget.DEFAULT);
        RelNode optimized = o.plan();
        String sql = PlanExecutor.toSqlFromOptimized(optimized);
        String planStr = PlanExecutor.planString(optimized);
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, enforced)) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);
            return new Outcome(sql, planStr, PlanShape.of(optimized), result, o.planning());
        }
    }

    @Override
    public void close() {
        for (Path p : tempFiles) {
            try { Files.deleteIfExists(p); } catch (IOException ignore) { /* best effort */ }
        }
    }
}

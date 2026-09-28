package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.sql.SqlKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Every aggregate function the OQL grammar accepts ({@code COUNT | SUM | AVG | MIN | MAX}) plans
 * through the Volcano pass and returns DuckDB's answer.
 *
 * <p>{@code AVG} did not: Calcite ships no Enumerable implementor for it (it expects
 * {@code AggregateReduceFunctionsRule} to have rewritten it into {@code SUM/COUNT} first), so
 * {@code EnumerableAggregateRule} declined the node and planning died with
 * {@code CannotPlanException} — the seminar demo's A3 query. The execution half pins the
 * answer, not just the plan: AVG over integers must come back fractional (DuckDB's AVG is
 * DOUBLE), which a SUM/COUNT reduction cast back to the argument's integer type would not.
 */
class OqlAggregateFunctionsTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String RESEARCHER = NS + "integration/Researcher";
    private static final String COUNTRY = NS + "integration/country";
    private static final String H_INDEX = NS + "integration/h_index";
    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";

    private static File snapshot;

    private static MappingCatalog catalog() {
        var researcher = new MappingCatalog.IntegratedClass(RESEARCHER, List.of(
                new MappingCatalog.SourceView("res", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(COUNTRY, "country", H_INDEX, "h_index"),
                        4L, List.of(), "", Map.of(H_INDEX, XSD_INTEGER))));
        return new MappingCatalog(Map.of(RESEARCHER, researcher), List.of(), List.of(), "v1");
    }

    /**
     * ES averages to a non-integer (3.5), so a truncating AVG is visible; FR carries a NULL
     * h-index, so COUNT(*) and COUNT(prop) disagree and AVG must ignore the NULL (10, not 5).
     */
    @BeforeAll
    static void writeSnapshot() throws Exception {
        Path dir = Files.createTempDirectory("aran-oql-aggregates");
        snapshot = dir.resolve("researchers.json").toFile();
        try (FileWriter fw = new FileWriter(snapshot)) {
            fw.write("[{\"country\":\"ES\",\"h_index\":3},"
                    + "{\"country\":\"ES\",\"h_index\":4},"
                    + "{\"country\":\"FR\",\"h_index\":10},"
                    + "{\"country\":\"FR\",\"h_index\":null}]");
        }
    }

    private static RelNode lower(String oql) {
        return new OqlPlanner(catalog(), Map.of()).lower(OqlParser.parse(oql));
    }

    private static List<List<Object>> rows(String oql) throws Exception {
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(lower(oql)));
        DuckDbExecutor.SnapshotResolver resolver =
                ds -> "read_json_auto('" + snapshot.getAbsolutePath() + "')";
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver, Map.of())) {
            exec.prepare();
            return exec.run(sql).rows();
        }
    }

    /** country → the second column, rendered as DuckDB's JDBC driver returns it. */
    private static Map<String, Object> run(String oql) throws Exception {
        Map<String, Object> byCountry = new LinkedHashMap<>();
        for (List<Object> row : rows(oql)) {
            byCountry.put(String.valueOf(row.get(0)), row.get(1));
        }
        return byCountry;
    }

    private static List<AggregateCall> aggregateCalls(RelNode rel) {
        List<AggregateCall> calls = new ArrayList<>();
        if (rel instanceof Aggregate a) {
            calls.addAll(a.getAggCallList());
        }
        rel.getInputs().forEach(in -> calls.addAll(aggregateCalls(in)));
        return calls;
    }

    @ParameterizedTest(name = "{0} plans and keeps its own function")
    @CsvSource({
            "COUNT(*),                   COUNT",
            "COUNT(Researcher.h_index),  COUNT",
            "SUM(Researcher.h_index),    SUM",
            "AVG(Researcher.h_index),    AVG",
            "MIN(Researcher.h_index),    MIN",
            "MAX(Researcher.h_index),    MAX"})
    void every_aggregate_plans(String aggregate, SqlKind kind) {
        String oql = "SELECT Researcher.country, " + aggregate + " AS v FROM Researcher "
                + "GROUP BY Researcher.country";

        RelNode optimized = PlanExecutor.optimize(lower(oql));

        assertThat(aggregateCalls(optimized))
                .as("the optimized plan computes %s itself, not a rewrite of it", aggregate)
                .extracting(c -> c.getAggregation().getKind())
                .containsExactly(kind);
        assertThat(PlanExecutor.toSqlFromOptimized(optimized)).contains(kind.name() + "(");
    }

    @Test
    @DisplayName("AVG answers DuckDB's fractional average, ignoring NULLs")
    void avg_executes() throws Exception {
        Map<String, Object> avg = run("SELECT Researcher.country, AVG(Researcher.h_index) AS v "
                + "FROM Researcher GROUP BY Researcher.country");

        assertThat(((Number) avg.get("ES")).doubleValue()).isEqualTo(3.5);
        assertThat(((Number) avg.get("FR")).doubleValue()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("COUNT(*), COUNT, SUM, MIN and MAX answer over the same groups")
    void the_others_execute() throws Exception {
        String from = " AS v FROM Researcher GROUP BY Researcher.country";
        assertThat(run("SELECT Researcher.country, COUNT(*)" + from))
                .containsEntry("ES", 2L).containsEntry("FR", 2L);
        assertThat(run("SELECT Researcher.country, COUNT(Researcher.h_index)" + from))
                .containsEntry("ES", 2L).containsEntry("FR", 1L);
        assertThat(run("SELECT Researcher.country, SUM(Researcher.h_index)" + from))
                .extractingByKeys("ES", "FR").map(v -> ((Number) v).longValue())
                .containsExactly(7L, 10L);
        assertThat(run("SELECT Researcher.country, MIN(Researcher.h_index)" + from))
                .extractingByKeys("ES", "FR").map(v -> ((Number) v).longValue())
                .containsExactly(3L, 10L);
        assertThat(run("SELECT Researcher.country, MAX(Researcher.h_index)" + from))
                .extractingByKeys("ES", "FR").map(v -> ((Number) v).longValue())
                .containsExactly(4L, 10L);
    }

    @Test
    @DisplayName("The seminar demo's A3 shape — AVG beside COUNT(*) under a LIMIT — plans and runs")
    void demo_a3_shape() throws Exception {
        String oql = "SELECT Researcher.country, AVG(Researcher.h_index) AS avg_h_index, "
                + "COUNT(*) AS researcher_count FROM Researcher GROUP BY Researcher.country LIMIT 20";

        Map<String, Object> avg = run(oql);

        assertThat(avg).containsOnlyKeys("ES", "FR");
        assertThat(((Number) avg.get("ES")).doubleValue()).isEqualTo(3.5);
    }

    /**
     * Without GROUP BY an aggregate is one grand-total row. The planner used to route on GROUP BY
     * alone, so {@code SELECT AVG(C.p) FROM C} became a plain projection — every row's raw value
     * under the average's name — and {@code COUNT(*)} failed on a null alias.
     */
    @Test
    @DisplayName("An aggregate without GROUP BY answers one grand-total row")
    void grand_total() throws Exception {
        List<List<Object>> avg = rows("SELECT AVG(Researcher.h_index) AS v FROM Researcher");
        assertThat(avg).hasSize(1);
        assertThat(((Number) avg.get(0).get(0)).doubleValue()).isCloseTo(17.0 / 3, within(1e-9));

        assertThat(rows("SELECT COUNT(*) AS n, MAX(Researcher.h_index) AS m FROM Researcher"))
                .singleElement().satisfies(r -> {
                    assertThat(((Number) r.get(0)).longValue()).isEqualTo(4L);
                    assertThat(((Number) r.get(1)).longValue()).isEqualTo(10L);
                });
    }

    @Test
    @DisplayName("A bare column beside an aggregate must be a GROUP BY key, not silently dropped")
    void ungrouped_column_is_refused() {
        assertThatThrownBy(() -> lower("SELECT Researcher.country, AVG(Researcher.h_index) "
                + "FROM Researcher"))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("Researcher.country is neither aggregated nor in the GROUP BY");
        assertThatThrownBy(() -> lower("SELECT Researcher.country, Researcher.h_index, COUNT(*) "
                + "FROM Researcher GROUP BY Researcher.country"))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("Researcher.h_index is neither aggregated");
    }
}

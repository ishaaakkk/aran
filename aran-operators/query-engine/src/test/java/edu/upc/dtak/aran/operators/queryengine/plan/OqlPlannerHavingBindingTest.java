package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S6-3: which aggregate a HAVING predicate binds to.
 *
 * <p>The binding used to be keyed by the aggregate's <em>display name</em>, so every un-aliased
 * {@code SUM(...)} in one SELECT list shared the key {@code "SUM"} and the last one won:
 * {@code HAVING SUM(A.x) > n} silently filtered on {@code SUM(A.y)}. The same lookup made the
 * "not in the SELECT list" guard unreachable — an unmatched predicate fell back to that display
 * name and found whichever aggregate happened to own it.
 *
 * <p>Both shapes below are wrong-answer, not crash, which is why they are asserted at execution
 * level and not only on plan shape: the fixture is chosen so the two aggregates disagree about
 * which row survives.
 */
class OqlPlannerHavingBindingTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String ARTWORKS = NS + "integration/Artworks";
    private static final String TITLE = NS + "integration/title";
    private static final String YEAR = NS + "integration/year";
    private static final String PRICE = NS + "integration/price";
    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";

    /** Two numeric columns, so one SELECT list can carry two un-aliased {@code SUM(...)}s. */
    private static MappingCatalog catalogFor() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", YEAR, "year", PRICE, "price"),
                        2L, List.of(), "", Map.of(YEAR, XSD_INTEGER, PRICE, XSD_INTEGER))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /**
     * One row per group, so each SUM is just that row's value — and the two columns disagree:
     * {@code SUM(year) > 2000} keeps only A, {@code SUM(price) > 2000} keeps only B. A predicate
     * bound to the wrong aggregate therefore returns the wrong row, not merely a wrong plan.
     */
    private static File snapshotFile() throws Exception {
        Path tempDir = Files.createTempDirectory("aran-s6-3-test");
        File snapshot = tempDir.resolve("artworks.json").toFile();
        try (FileWriter fw = new FileWriter(snapshot)) {
            fw.write("[{\"title\":\"A\",\"year\":2020,\"price\":10},"
                    + "{\"title\":\"B\",\"year\":1990,\"price\":5000}]");
        }
        return snapshot;
    }

    private static RelNode lower(MappingCatalog catalog, String oql) {
        return new OqlPlanner(catalog, Map.of()).lower(OqlParser.parse(oql));
    }

    /** First {@link Filter} in {@code rel}, depth-first — the HAVING filter over the aggregate. */
    private static Filter firstFilter(RelNode rel) {
        if (rel instanceof Filter f) {
            return f;
        }
        for (RelNode input : rel.getInputs()) {
            Filter found = firstFilter(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<String> runFirstColumn(MappingCatalog catalog, File snapshot, String oql)
            throws Exception {
        DuckDbExecutor.SnapshotResolver resolver =
                ds -> "read_json_auto('" + snapshot.getAbsolutePath() + "')";
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(lower(catalog, oql)));
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, Map.of())) {
            exec.prepare();
            return exec.run(sql).rows().stream().map(r -> String.valueOf(r.get(0))).toList();
        }
    }

    @Test
    @DisplayName("HAVING binds to its own aggregate when two un-aliased SUMs share a display name")
    void having_binds_to_the_named_aggregate_not_the_last_one() throws Exception {
        MappingCatalog catalog = catalogFor();
        File snapshot = snapshotFile();
        // Aggregate outputs are [title, SUM(year), SUM(price)] — the HAVING must read ordinal 1.
        String oql = "SELECT Artworks.title, SUM(Artworks.year), SUM(Artworks.price) FROM Artworks "
                + "GROUP BY Artworks.title HAVING SUM(Artworks.year) > 2000";

        Filter having = firstFilter(lower(catalog, oql));
        assertThat(having).as("the plan must contain the HAVING filter").isNotNull();
        RexCall comparison = (RexCall) having.getCondition();
        assertThat(((RexInputRef) comparison.getOperands().get(0)).getIndex())
                .as("HAVING SUM(year) must read the year aggregate, not the price one")
                .isEqualTo(1);

        assertThat(runFirstColumn(catalog, snapshot, oql))
                .as("SUM(year) > 2000 keeps A; binding to SUM(price) would keep B instead")
                .containsExactly("A");
    }

    @Test
    @DisplayName("HAVING over an aggregate the SELECT list does not compute is refused, not rebound")
    void having_over_an_absent_aggregate_is_an_error() {
        MappingCatalog catalog = catalogFor();
        String oql = "SELECT Artworks.title, SUM(Artworks.price) FROM Artworks "
                + "GROUP BY Artworks.title HAVING SUM(Artworks.year) > 2000";

        assertThatThrownBy(() -> lower(catalog, oql))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("not in the SELECT list");
    }

    @Test
    @DisplayName("W-B5: HAVING with neither a GROUP BY nor an aggregate is refused — it used to be dropped, returning every row")
    void having_without_groups_is_refused() {
        MappingCatalog catalog = catalogFor();
        // The builder offered exactly this (J5-F2): the chip read "having COUNT(*) > 5" while the
        // planner took the plain-projection path, which never looks at HAVING.
        String oql = "SELECT Artworks.title FROM Artworks HAVING COUNT(*) > 5";

        assertThatThrownBy(() -> lower(catalog, oql))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("HAVING needs an aggregate or GROUP BY");
    }

    @Test
    @DisplayName("W-B5: HAVING beside an aggregate with no GROUP BY still plans — a grand total can be filtered")
    void having_over_a_grand_total_still_plans() {
        MappingCatalog catalog = catalogFor();
        String oql = "SELECT COUNT(*) AS n FROM Artworks HAVING COUNT(*) > 1";

        assertThat(lower(catalog, oql)).isNotNull();
    }
}

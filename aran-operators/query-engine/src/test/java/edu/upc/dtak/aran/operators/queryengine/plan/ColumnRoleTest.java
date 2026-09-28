package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.rel.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each output column <em>is</em>, as the query declares it.
 *
 * <p>The chart layer cannot tell {@code SELECT Paper.title, Paper.year} (a list of papers) from
 * {@code SELECT Paper.venue, COUNT(*) … GROUP BY} (a comparison across venues) by looking at
 * types — both are text-then-number. Drawing the first as a bar chart asserts a comparison that
 * does not exist. These roles are what make that distinguishable.
 *
 * <p>Every case here asserts the roles line up with the plan's <em>actual</em> output columns,
 * not with the SELECT list: {@code RelBuilder.aggregate} emits {@code [keys…, aggregates…]}
 * regardless of the order they were written in.
 */
class ColumnRoleTest {

    private static final String NS = "http://example.com/";
    private static final String PAPER = NS + "Paper";
    private static final String TITLE = NS + "title";
    private static final String YEAR = NS + "year";
    private static final String VENUE = NS + "venue";

    private static MappingCatalog catalog() {
        var paper = new MappingCatalog.IntegratedClass(PAPER, List.of(
                new MappingCatalog.SourceView("ds", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(TITLE, "title", YEAR, "year", VENUE, "venue"), 100)));
        return new MappingCatalog(Map.of(PAPER, paper), List.of(), List.of(), "v1");
    }

    /** Roles, alongside the plan's real output column count, so a mismatch cannot pass. */
    private static List<OqlPlanner.ColumnRole> rolesFor(String oql) {
        OqlPlanner planner = new OqlPlanner(catalog());
        RelNode rel = planner.lower(OqlParser.parse(oql));
        assertThat(planner.columnRoles())
                .as("one role per output column of the plan, for: %s", oql)
                .hasSize(rel.getRowType().getFieldCount());
        return planner.columnRoles();
    }

    @Test
    @DisplayName("a plain projection is all VALUE — a list, not a comparison")
    void plainProjection() {
        assertThat(rolesFor("SELECT Paper.title, Paper.year FROM Paper"))
                .containsExactly(OqlPlanner.ColumnRole.VALUE, OqlPlanner.ColumnRole.VALUE);
    }

    @Test
    @DisplayName("a grouped query separates the key from the measure")
    void groupedQuery() {
        assertThat(rolesFor("SELECT Paper.venue, COUNT(*) AS n FROM Paper GROUP BY Paper.venue"))
                .containsExactly(OqlPlanner.ColumnRole.KEY, OqlPlanner.ColumnRole.MEASURE);
    }

    @Test
    @DisplayName("roles follow the PLAN's column order, not the SELECT list's")
    void aggregateBeforeKeyInSelect() {
        // Written measure-first, but RelBuilder.aggregate still emits [key, aggregate]. Deriving
        // the roles from the AST's SELECT order would label these backwards — which is exactly
        // why they are recorded during lowering instead.
        assertThat(rolesFor("SELECT COUNT(*) AS n, Paper.venue FROM Paper GROUP BY Paper.venue"))
                .containsExactly(OqlPlanner.ColumnRole.KEY, OqlPlanner.ColumnRole.MEASURE);
    }

    @Test
    @DisplayName("several keys and several measures are each counted")
    void multipleKeysAndMeasures() {
        assertThat(rolesFor(
                "SELECT Paper.venue, Paper.year, COUNT(*) AS n, MAX(Paper.year) AS newest "
                        + "FROM Paper GROUP BY Paper.venue, Paper.year"))
                .containsExactly(
                        OqlPlanner.ColumnRole.KEY, OqlPlanner.ColumnRole.KEY,
                        OqlPlanner.ColumnRole.MEASURE, OqlPlanner.ColumnRole.MEASURE);
    }

    @Test
    @DisplayName("a star expansion gets one role per expanded column, not one per SELECT item")
    void starExpansion() {
        // `SELECT Paper.*` is a single projection item but three output columns; sizing the roles
        // from the SELECT list would produce one role for three columns.
        assertThat(rolesFor("SELECT Paper.* FROM Paper"))
                .containsOnly(OqlPlanner.ColumnRole.VALUE)
                .hasSize(3);
    }

    @Test
    @DisplayName("DISTINCT does not change the columns, so it does not change the roles")
    void distinctIsTransparent() {
        assertThat(rolesFor("SELECT DISTINCT Paper.venue FROM Paper"))
                .containsExactly(OqlPlanner.ColumnRole.VALUE);
    }

    @Test
    @DisplayName("ORDER BY and LIMIT do not change the roles")
    void orderAndLimitAreTransparent() {
        assertThat(rolesFor("SELECT Paper.venue, COUNT(*) AS n FROM Paper "
                + "GROUP BY Paper.venue ORDER BY Paper.venue LIMIT 5"))
                .containsExactly(OqlPlanner.ColumnRole.KEY, OqlPlanner.ColumnRole.MEASURE);
    }
}

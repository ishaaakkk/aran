package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Sort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ORDER BY} an output name, with no class to qualify it.
 *
 * <p>An aggregate's output belongs to no class: {@code COUNT(*) AS n} projects a column called
 * {@code n} and there is nothing to write in front of the dot. The grammar required
 * {@code ClassName '.' ident}, so "top venues by paper count" — the query a user writes
 * immediately after their first GROUP BY — did not parse at all.
 *
 * <p>These assert the resolved <em>sort key</em>, not just that the query plans. A query that
 * plans while sorting on the wrong column looks identical from the outside.
 */
class OrderByOutputAliasTest {

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

    private static RelNode plan(String oql) {
        return new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
    }

    /** The single sort key, as (output column name, direction). */
    private static RelFieldCollation sortKey(RelNode rel) {
        assertThat(rel).as("top of the plan is a Sort").isInstanceOf(Sort.class);
        Sort sort = (Sort) rel;
        assertThat(sort.getCollation().getFieldCollations()).hasSize(1);
        return sort.getCollation().getFieldCollations().get(0);
    }

    private static String sortColumn(RelNode rel) {
        return rel.getRowType().getFieldNames().get(sortKey(rel).getFieldIndex());
    }

    @Test
    @DisplayName("ORDER BY a bare aggregate alias sorts on the aggregate column")
    void bareAggregateAlias() {
        RelNode rel = plan("SELECT Paper.venue, COUNT(*) AS n FROM Paper "
                + "GROUP BY Paper.venue ORDER BY n");
        assertThat(sortColumn(rel)).isEqualTo("n");
        assertThat(sortKey(rel).getDirection()).isEqualTo(RelFieldCollation.Direction.ASCENDING);
    }

    @Test
    @DisplayName("DESC on a bare alias reaches the collation, not just the parser")
    void bareAggregateAliasDescending() {
        RelNode rel = plan("SELECT Paper.venue, COUNT(*) AS n FROM Paper "
                + "GROUP BY Paper.venue ORDER BY n DESC");
        assertThat(sortColumn(rel)).isEqualTo("n");
        assertThat(sortKey(rel).getDirection()).isEqualTo(RelFieldCollation.Direction.DESCENDING);
    }

    @Test
    @DisplayName("a bare group-key name resolves to the global IRI the aggregate actually emits")
    void bareGroupKeyName() {
        RelNode rel = plan("SELECT Paper.venue, COUNT(*) AS n FROM Paper "
                + "GROUP BY Paper.venue ORDER BY venue");
        // RelBuilder.groupKey keeps the input field's name, which in an int_ view is the full
        // global property IRI — not "venue". Resolving to the local name would not be found.
        assertThat(sortColumn(rel)).isEqualTo(VENUE);
    }

    @Test
    @DisplayName("a bare alias resolves through AS, which the qualified form cannot express")
    void bareAliasOnRenamedGroupKey() {
        RelNode rel = plan("SELECT Paper.venue AS v, COUNT(*) AS n FROM Paper "
                + "GROUP BY Paper.venue ORDER BY v");
        assertThat(sortColumn(rel)).isEqualTo(VENUE);
    }

    @Test
    @DisplayName("a bare alias in an ungrouped query sorts on the projected column")
    void bareAliasUngrouped() {
        RelNode rel = plan("SELECT Paper.title AS t, Paper.year FROM Paper ORDER BY t");
        assertThat(sortColumn(rel)).isEqualTo("t");
    }

    @Test
    @DisplayName("a bare alias that names no SELECT output fails, and the message lists what does")
    void unknownBareAlias() {
        assertThatThrownBy(() -> plan("SELECT Paper.venue, COUNT(*) AS n FROM Paper "
                + "GROUP BY Paper.venue ORDER BY nope"))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("venue")
                .hasMessageContaining("n");
    }

    @Test
    @DisplayName("the qualified form still resolves a group key to its global IRI")
    void qualifiedGroupKeyStillWorks() {
        RelNode rel = plan("SELECT Paper.venue, COUNT(*) AS n FROM Paper "
                + "GROUP BY Paper.venue ORDER BY Paper.venue");
        assertThat(sortColumn(rel)).isEqualTo(VENUE);
    }

    @Test
    @DisplayName("a qualified ORDER BY on a class not in FROM still fails at lowering")
    void qualifiedUnknownClassStillFails() {
        assertThatThrownBy(() -> plan("SELECT Paper.title FROM Paper ORDER BY Author.name"))
                .hasMessageContaining("Author");
    }
}

package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 10 acceptance: OQL AST lowers to a correct Calcite logical plan.
 */
class OqlPlannerTest {

    private static final String NS  = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";

    private static final String ARTWORKS  = NS + "integration/Artworks";
    private static final String IARTIST   = NS + "integration/IArtist";
    private static final String P_TITLE   = NS + "integration/title";
    private static final String P_NAME    = NS + "integration/name";
    private static final String P_AID     = SRC + "ds1/CMOA/artist_id";
    private static final String P_ID      = SRC + "ds2/Artist/id";
    private static final String HAS_ARTIST = NS + "integration/hasArtist";

    private MappingCatalog catalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("ds1", "SELECT 1",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"))));
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("ds2", "SELECT 1",
                        Map.of(P_ID, "id", P_NAME, "name"))));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        return new MappingCatalog(
                Map.of(ARTWORKS, artworks, IARTIST, iartist),
                List.of(join), List.of(), "v1");
    }

    @Test
    void runningExampleProducesCorrectPlanShape() {
        String oql = "SELECT Artworks.title, IArtist.name AS artist " +
                     "FROM Artworks -[hasArtist]-> IArtist " +
                     "WHERE Artworks.title <> 'none' LIMIT 100";

        RelNode plan = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String planStr = RelOptUtil.toString(plan);

        assertThat(planStr).contains("LogicalProject");
        assertThat(planStr).contains("LogicalJoin");
        assertThat(planStr).contains("LogicalTableScan");
        assertThat(planStr).contains("LogicalFilter");
        assertThat(planStr).contains("condition=[=(");
        assertThat(planStr).contains("u_ds1_Artworks");
        assertThat(planStr).contains("u_ds2_IArtist");
        assertThat(planStr).contains("fetch=[100]");
        assertThat(planStr).contains("title=");
        assertThat(planStr).contains("artist=");
    }

    @Test
    void filterOnlyQueryProducesFilterOverScan() {
        String oql = "SELECT Artworks.title WHERE Artworks.title = 'Guernica'";

        RelNode plan = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String planStr = RelOptUtil.toString(plan);

        assertThat(planStr).contains("LogicalProject");
        assertThat(planStr).contains("LogicalFilter");
        assertThat(planStr).contains("LogicalTableScan");
        assertThat(planStr).doesNotContain("LogicalJoin");
        assertThat(planStr).contains("'Guernica'");
        assertThat(planStr).contains("title=");
    }

    @Test
    void limitQueryProducesSort() {
        String oql = "SELECT Artworks.title LIMIT 5";

        RelNode plan = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String planStr = RelOptUtil.toString(plan);

        assertThat(planStr).contains("fetch=[5]");
    }

    @Test
    void starExpansionProducesAllColumns() {
        String oql = "SELECT Artworks.*";

        RelNode plan = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));

        assertThat(plan.getRowType().getFieldNames()).containsExactlyInAnyOrder(
                "http://www.essi.upc.edu/DTIM/NextiaDI/integration/title",
                "http://www.essi.upc.edu/DTIM/NextiaDI/source/ds1/CMOA/artist_id"
        );
    }

    @Test
    void unknownClassIsReported() {
        assertThatThrownBy(() -> new OqlPlanner(catalog())
                .lower(OqlParser.parse("SELECT Nope.y")))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("Unknown class 'Nope'");
    }

    @Test
    void unknownPropertyIsReported() {
        assertThatThrownBy(() -> new OqlPlanner(catalog())
                .lower(OqlParser.parse("SELECT Artworks.bogus")))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("has no property 'bogus'");
    }

    @Test
    void unknownJoinPropertyIsReported() {
        assertThatThrownBy(() -> new OqlPlanner(catalog())
                .lower(OqlParser.parse("SELECT Artworks.title, IArtist.name FROM Artworks -[fakeJoin]-> IArtist")))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("Unknown join property 'fakeJoin'");
    }

    // ── The workbench builder's aggregate shape ────────────────────────────────────
    //
    // A join or a filter puts a class on the builder's canvas with no picked field. Beside an
    // aggregate that class's star is neither aggregated nor grouped, so buildOql
    // (aran-web/src/lib/workbenchModel.ts) leaves it out. This catalog mirrors
    // workbenchModel.test.ts's, and BUILDER_AGGREGATE is the exact text that vitest pins.

    private static final String ARTIST      = NS + "integration/Artist";
    private static final String ARTWORK     = NS + "integration/Artwork";
    private static final String P_ARTIST_ID = SRC + "ds1/Artist/id";
    private static final String P_ARTIST_NM = SRC + "ds1/Artist/name";
    private static final String P_WORK_ID   = SRC + "ds2/Artwork/id";
    private static final String P_WORK_TTL  = SRC + "ds2/Artwork/title";
    private static final String P_WORK_AID  = SRC + "ds2/Artwork/artist_id";
    private static final String CREATED     = NS + "integration/created";

    private static final String BUILDER_AGGREGATE = "SELECT Artwork.title, COUNT(Artwork.id) AS count_id\n"
            + "FROM Artist -[created]-> Artwork\n"
            + "WHERE Artist.name = 'Miró'\n"
            + "GROUP BY Artwork.title\n"
            + "LIMIT 100";

    private MappingCatalog builderCatalog() {
        var artist = new MappingCatalog.IntegratedClass(ARTIST, List.of(
                new MappingCatalog.SourceView("ds1", "SELECT 1",
                        Map.of(P_ARTIST_ID, "id", P_ARTIST_NM, "name"))));
        var artwork = new MappingCatalog.IntegratedClass(ARTWORK, List.of(
                new MappingCatalog.SourceView("ds2", "SELECT 1",
                        Map.of(P_WORK_ID, "id", P_WORK_TTL, "title", P_WORK_AID, "artist_id"))));
        var created = new MappingCatalog.JoinProperty(CREATED, ARTIST, ARTWORK, P_ARTIST_ID, P_WORK_AID);
        return new MappingCatalog(Map.of(ARTIST, artist, ARTWORK, artwork),
                List.of(created), List.of(), "v1");
    }

    @Test
    @DisplayName("the builder's aggregate over a joined, filter-only class plans to the grouped columns")
    void builderAggregateOverAFilterOnlyJoinedClassPlans() {
        RelNode plan = new OqlPlanner(builderCatalog()).lower(OqlParser.parse(BUILDER_AGGREGATE));

        assertThat(plan.getRowType().getFieldNames()).containsExactly(P_WORK_TTL, "count_id");
        assertThat(RelOptUtil.toString(plan)).contains("'Miró'");
    }

    /** J5-F9: the builder's "rows per group, most first" — the exact text workbenchModel.test.ts pins. */
    private static final String BUILDER_COUNT_ALL = "SELECT Artwork.title, COUNT(*) AS n\n"
            + "FROM Artwork\n"
            + "GROUP BY Artwork.title\n"
            + "ORDER BY n DESC\n"
            + "LIMIT 100";

    @Test
    @DisplayName("the builder's COUNT(*) AS n, ordered by its alias, plans — walkthrough Q4 was unbuildable")
    void builderCountAllOrderedByAliasPlans() {
        RelNode plan = new OqlPlanner(builderCatalog()).lower(OqlParser.parse(BUILDER_COUNT_ALL));

        assertThat(plan.getRowType().getFieldNames()).containsExactly(P_WORK_TTL, "n");
    }

    @Test
    @DisplayName("a star beside an aggregate is refused by name, which is why the builder omits it")
    void aStarBesideAnAggregateIsRefused() {
        String withStar = BUILDER_AGGREGATE.replace(
                "SELECT Artwork.title, COUNT(Artwork.id) AS count_id",
                "SELECT Artist.*, Artwork.title, COUNT(Artwork.id) AS count_id");

        assertThatThrownBy(() -> new OqlPlanner(builderCatalog()).lower(OqlParser.parse(withStar)))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessage("SELECT Artist.* is neither aggregated nor in the GROUP BY list.");
    }

    @Test
    @DisplayName("a bare column beside an aggregate across a join, with no GROUP BY, is refused by name")
    void anUngroupedColumnAcrossAJoinIsRefused() {
        assertThatThrownBy(() -> new OqlPlanner(catalog()).lower(OqlParser.parse(
                "SELECT Artworks.title, COUNT(IArtist.name) FROM Artworks -[hasArtist]-> IArtist")))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessage("SELECT Artworks.title is neither aggregated nor in the GROUP BY list.");
    }

    // ── F12: an unaligned same-named collision elsewhere in the class must not block
    //         queries against unrelated, unambiguous properties ─────────────────────

    private static final String PAPER      = NS + "integration/Paper";
    private static final String P_PAPER_TITLE = NS + "integration/paperTitle";
    private static final String P_AUTHOR_A  = SRC + "ds1/Paper/author_ref";
    private static final String P_AUTHOR_B  = SRC + "ds2/Paper/author_ref";

    private MappingCatalog catalogWithUnalignedCollision() {
        var paper = new MappingCatalog.IntegratedClass(PAPER, List.of(
                new MappingCatalog.SourceView("ds1", "SELECT 1",
                        Map.of(P_PAPER_TITLE, "title", P_AUTHOR_A, "author_ref")),
                new MappingCatalog.SourceView("ds2", "SELECT 1",
                        Map.of(P_PAPER_TITLE, "title", P_AUTHOR_B, "author_ref"))));
        return new MappingCatalog(Map.of(PAPER, paper), List.of(), List.of(), "v1");
    }

    @Test
    void queryingAnUnambiguousPropertySucceedsDespiteAnUnrelatedCollisionElsewhereInTheClass() {
        RelNode plan = new OqlPlanner(catalogWithUnalignedCollision())
                .lower(OqlParser.parse("SELECT Paper.paperTitle"));

        assertThat(RelOptUtil.toString(plan)).contains("paperTitle=");
    }

    @Test
    void queryingTheActuallyAmbiguousPropertyIsStillReported() {
        assertThatThrownBy(() -> new OqlPlanner(catalogWithUnalignedCollision())
                .lower(OqlParser.parse("SELECT Paper.author_ref")))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("has two properties sharing local name 'author_ref'");
    }

    @Test
    void starExpansionStillReportsAnAmbiguousCollisionAcrossTheWholeClass() {
        assertThatThrownBy(() -> new OqlPlanner(catalogWithUnalignedCollision())
                .lower(OqlParser.parse("SELECT Paper.*")))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("has two properties sharing local name 'author_ref'");
    }
}

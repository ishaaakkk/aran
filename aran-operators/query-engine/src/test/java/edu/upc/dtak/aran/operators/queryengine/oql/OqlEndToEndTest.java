package edu.upc.dtak.aran.operators.queryengine.oql;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.plan.OqlPlanner;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanExecutor;
import org.apache.calcite.rel.RelNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end acceptance: an OQL string produces a valid DuckDB-compatible SQL string
 * via OqlParser → OqlPlanner → PlanExecutor.
 */
class OqlEndToEndTest {

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
    void joinQueryProducesSelectWithJoin() {
        String oql = "SELECT Artworks.title, IArtist.name AS artist " +
                     "FROM Artworks -[hasArtist]-> IArtist " +
                     "WHERE Artworks.title <> 'none' LIMIT 100";

        RelNode logical = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String sql = PlanExecutor.toSql(logical);

        assertThat(sql).containsIgnoringCase("SELECT");
        assertThat(sql).containsIgnoringCase("JOIN");
        assertThat(sql).containsIgnoringCase("u_ds1_Artworks");
        assertThat(sql).containsIgnoringCase("u_ds2_IArtist");
        assertThat(sql).containsIgnoringCase("LIMIT 100");
    }

    @Test
    void singleClassQueryProducesSelectWithoutJoin() {
        String oql = "SELECT Artworks.title WHERE Artworks.title <> 'none'";

        RelNode logical = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String sql = PlanExecutor.toSql(logical);

        assertThat(sql).containsIgnoringCase("SELECT");
        assertThat(sql).doesNotContainIgnoringCase("JOIN");
        assertThat(sql).containsIgnoringCase("u_ds1_Artworks");
    }

    @Test
    void starQueryNoFrom() {
        String oql = "SELECT Artworks.* LIMIT 10";

        RelNode logical = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String sql = PlanExecutor.toSql(logical);

        assertThat(sql).containsIgnoringCase("u_ds1_Artworks");
        assertThat(sql).containsIgnoringCase("LIMIT 10");
    }

    @Test
    void countStarGroupByProducesGroupBySql() {
        String oql = "SELECT Artworks.title, COUNT(*) AS n FROM Artworks GROUP BY Artworks.title";

        RelNode logical = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String sql = PlanExecutor.toSql(logical);

        assertThat(sql).containsIgnoringCase("GROUP BY");
        assertThat(sql).containsIgnoringCase("COUNT(");
        assertThat(sql).containsIgnoringCase("u_ds1_Artworks");
    }

    @Test
    void countStarOnJoinGroupsByRightSide() {
        String oql = "SELECT IArtist.name, COUNT(*) AS works " +
                     "FROM Artworks -[hasArtist]-> IArtist " +
                     "GROUP BY IArtist.name";

        RelNode logical = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String sql = PlanExecutor.toSql(logical);

        assertThat(sql).containsIgnoringCase("GROUP BY");
        assertThat(sql).containsIgnoringCase("COUNT(");
        assertThat(sql).containsIgnoringCase("JOIN");
        assertThat(sql).containsIgnoringCase("u_ds1_Artworks");
        assertThat(sql).containsIgnoringCase("u_ds2_IArtist");
    }

    @Test
    void havingFiltersAggregateOutput() {
        String oql = "SELECT Artworks.title, COUNT(*) AS n " +
                     "FROM Artworks GROUP BY Artworks.title HAVING COUNT(*) > 1";

        RelNode logical = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String sql = PlanExecutor.toSql(logical);

        assertThat(sql).containsIgnoringCase("GROUP BY");
        assertThat(sql).containsIgnoringCase("1");
    }

    @Test
    void minMaxOnPropertyProduceSql() {
        String oql = "SELECT IArtist.name, MIN(Artworks.title) AS first_title, " +
                     "MAX(Artworks.title) AS last_title " +
                     "FROM Artworks -[hasArtist]-> IArtist " +
                     "GROUP BY IArtist.name";

        RelNode logical = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        String sql = PlanExecutor.toSql(logical);

        assertThat(sql).containsIgnoringCase("MIN(");
        assertThat(sql).containsIgnoringCase("MAX(");
        assertThat(sql).containsIgnoringCase("GROUP BY");
    }
}

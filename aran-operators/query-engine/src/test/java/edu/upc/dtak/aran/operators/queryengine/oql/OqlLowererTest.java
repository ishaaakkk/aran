package edu.upc.dtak.aran.operators.queryengine.oql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OqlLowererTest {

    @Test
    void lowersSingleClassQuery() {
        OqlQuery oql = OqlParser.parse("SELECT Artworks.title, Artworks.year");
        AliasedQuery aliased = OqlLowerer.lower(oql);

        assertThat(aliased.from().className()).isEqualTo("Artworks");
        assertThat(aliased.from().alias()).isEqualTo("t0");
        assertThat(aliased.joins()).isEmpty();
        assertThat(aliased.select()).hasSize(2);
        assertThat(aliased.select().get(0).alias()).isEqualTo("t0");
        assertThat(aliased.select().get(0).property()).isEqualTo("title");
        assertThat(aliased.where()).isEmpty();
        assertThat(aliased.limit()).isNull();
    }

    @Test
    void lowersJoinQuery() {
        OqlQuery oql = OqlParser.parse(
            "SELECT Artworks.title, IArtist.name AS artist " +
            "FROM Artworks -[hasArtist]-> IArtist " +
            "WHERE Artworks.title <> 'none' LIMIT 100");
        AliasedQuery aliased = OqlLowerer.lower(oql);

        assertThat(aliased.from().className()).isEqualTo("Artworks");
        assertThat(aliased.from().alias()).isEqualTo("t0");
        assertThat(aliased.joins()).hasSize(1);
        assertThat(aliased.joins().get(0).className()).isEqualTo("IArtist");
        assertThat(aliased.joins().get(0).alias()).isEqualTo("t1");
        assertThat(aliased.joins().get(0).joinProperty()).isEqualTo("hasArtist");
        assertThat(aliased.select().get(1).alias()).isEqualTo("t1");
        assertThat(aliased.select().get(1).as()).isEqualTo("artist");
        assertThat(aliased.where().get(0).alias()).isEqualTo("t0");
        assertThat(aliased.limit()).isEqualTo(100);
    }

    @Test
    void lowersMultiHopPath() {
        OqlQuery oql = OqlParser.parse("SELECT A.x, B.y, C.z FROM A -[p1]-> B -[p2]-> C");
        AliasedQuery q = OqlLowerer.lower(oql);
        assertThat(q.joins()).hasSize(2);
        assertThat(q.joins().get(0).alias()).isEqualTo("t1");
        assertThat(q.joins().get(1).alias()).isEqualTo("t2");
    }

    @Test
    void rejectsUnknownClassInProjection() {
        OqlQuery oql = OqlParser.parse("SELECT Unknown.x FROM Artworks -[p]-> IArtist");
        assertThatThrownBy(() -> OqlLowerer.lower(oql))
            .isInstanceOf(OqlLowerer.OqlLowerException.class)
            .hasMessageContaining("Unknown");
    }

    @Test
    void lowersStar() {
        OqlQuery oql = OqlParser.parse("SELECT Artworks.*");
        AliasedQuery aliased = OqlLowerer.lower(oql);
        assertThat(aliased.select()).hasSize(1);
        assertThat(aliased.select().get(0).star()).isTrue();
        assertThat(aliased.select().get(0).alias()).isEqualTo("t0");
    }

    @Test
    void lowersGroupBy() {
        OqlQuery oql = OqlParser.parse(
                "SELECT Artworks.year, COUNT(*) AS total FROM Artworks GROUP BY Artworks.year");
        AliasedQuery aliased = OqlLowerer.lower(oql);

        assertThat(aliased.groupBy()).hasSize(1);
        assertThat(aliased.groupBy().get(0).alias()).isEqualTo("t0");
        assertThat(aliased.groupBy().get(0).property()).isEqualTo("year");
        assertThat(aliased.select().get(1).isAggregate()).isTrue();
        assertThat(aliased.select().get(1).aggFn()).isEqualTo(OqlQuery.AggFn.COUNT_STAR);
        assertThat(aliased.select().get(1).as()).isEqualTo("total");
    }

    @Test
    void lowersHaving() {
        OqlQuery oql = OqlParser.parse(
                "SELECT Artworks.year, COUNT(*) AS n FROM Artworks GROUP BY Artworks.year HAVING COUNT(*) > 2");
        AliasedQuery aliased = OqlLowerer.lower(oql);

        assertThat(aliased.having()).hasSize(1);
        assertThat(aliased.having().get(0).aggFn()).isEqualTo(OqlQuery.AggFn.COUNT_STAR);
        assertThat(aliased.having().get(0).op()).isEqualTo(AliasedQuery.Op.GT);
        assertThat(aliased.having().get(0).literal()).isEqualTo("2");
    }

    @Test
    void lowersGroupByWithJoin() {
        OqlQuery oql = OqlParser.parse(
                "SELECT IArtist.name, COUNT(*) AS works " +
                "FROM Artworks -[hasArtist]-> IArtist " +
                "GROUP BY IArtist.name");
        AliasedQuery aliased = OqlLowerer.lower(oql);

        assertThat(aliased.groupBy()).hasSize(1);
        assertThat(aliased.groupBy().get(0).alias()).isEqualTo("t1");
        assertThat(aliased.groupBy().get(0).property()).isEqualTo("name");
        assertThat(aliased.joins()).hasSize(1);
    }
}

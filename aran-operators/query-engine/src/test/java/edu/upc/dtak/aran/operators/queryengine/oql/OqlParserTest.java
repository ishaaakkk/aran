package edu.upc.dtak.aran.operators.queryengine.oql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OqlParserTest {

    @Test
    void parsesStarOnSingleClass() {
        OqlQuery q = OqlParser.parse("SELECT Artworks.*");
        assertThat(q.select()).hasSize(1);
        assertThat(q.select().get(0).star()).isTrue();
        assertThat(q.select().get(0).className()).isEqualTo("Artworks");
        assertThat(q.path()).isEmpty();
        assertThat(q.where()).isEmpty();
        assertThat(q.limit()).isNull();
    }

    @Test
    void parsesProjectionWithAs() {
        OqlQuery q = OqlParser.parse("SELECT Artworks.title AS t, Artworks.year");
        assertThat(q.select()).hasSize(2);
        assertThat(q.select().get(0).property()).isEqualTo("title");
        assertThat(q.select().get(0).as()).isEqualTo("t");
        assertThat(q.select().get(1).property()).isEqualTo("year");
        assertThat(q.select().get(1).as()).isNull();
    }

    @Test
    void parsesWhereAndLimit() {
        OqlQuery q = OqlParser.parse(
            "SELECT Artworks.title WHERE Artworks.year > '2000' LIMIT 50");
        assertThat(q.where()).hasSize(1);
        assertThat(q.where().get(0).className()).isEqualTo("Artworks");
        assertThat(q.where().get(0).property()).isEqualTo("year");
        assertThat(q.where().get(0).op()).isEqualTo(OqlQuery.Op.GT);
        assertThat(q.where().get(0).literal()).isEqualTo("'2000'");
        assertThat(q.limit()).isEqualTo(50);
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> OqlParser.parse(""))
            .isInstanceOf(OqlParser.OqlParseException.class);
    }

    @Test
    void rejectsSelectWithoutClass() {
        assertThatThrownBy(() -> OqlParser.parse("SELECT title"))
            .isInstanceOf(OqlParser.OqlParseException.class);
    }

    @Test
    void parsesBareStar() {
        OqlQuery q = OqlParser.parse("SELECT * FROM Artworks LIMIT 50");
        assertThat(q.select()).hasSize(1);
        OqlQuery.Projection p = q.select().get(0);
        assertThat(p.star()).isTrue();
        assertThat(p.className()).isNull();
        assertThat(q.explicitFrom()).isEqualTo("Artworks");
        assertThat(q.path()).isEmpty();
        assertThat(q.limit()).isEqualTo(50);
    }

    @Test
    void parsesBareStar_withJoin() {
        OqlQuery q = OqlParser.parse("SELECT * FROM Artworks -[hasArtist]-> IArtist");
        assertThat(q.select()).hasSize(1);
        assertThat(q.select().get(0).star()).isTrue();
        assertThat(q.select().get(0).className()).isNull();
        assertThat(q.explicitFrom()).isEqualTo("Artworks");
        assertThat(q.path()).hasSize(1);
    }

    @Test
    void parsesDistinct() {
        OqlQuery q = OqlParser.parse("SELECT DISTINCT Artworks.title FROM Artworks LIMIT 50");
        assertThat(q.distinct()).isTrue();
        assertThat(q.select()).hasSize(1);
        assertThat(q.limit()).isEqualTo(50);
    }

    @Test
    void nonDistinctByDefault() {
        OqlQuery q = OqlParser.parse("SELECT Artworks.title");
        assertThat(q.distinct()).isFalse();
    }

    @Test
    void parsesMultiHopWithoutThrow() {
        OqlQuery q = OqlParser.parse(
                "SELECT A.x, B.y, C.z FROM A -[p1]-> B -[p2]-> C LIMIT 50");
        assertThat(q.path()).hasSize(2);
        assertThat(q.path().get(0).joinProperty()).isEqualTo("p1");
        assertThat(q.path().get(1).joinProperty()).isEqualTo("p2");
        assertThat(q.path().get(1).sourceClass()).isEqualTo("B");
        assertThat(q.path().get(1).targetClass()).isEqualTo("C");
        // Lowerer must not throw for multi-hop
        AliasedQuery lowered = edu.upc.dtak.aran.operators.queryengine.oql.OqlLowerer.lower(q);
        assertThat(lowered.joins()).hasSize(2);
        assertThat(lowered.joins().get(0).alias()).isEqualTo("t1");
        assertThat(lowered.joins().get(1).alias()).isEqualTo("t2");
    }

    @Test
    void parsesIsNull() {
        OqlQuery q = OqlParser.parse("SELECT Artworks.title WHERE Artworks.year IS NULL");
        assertThat(q.where()).hasSize(1);
        assertThat(q.where().get(0).op()).isEqualTo(OqlQuery.Op.IS_NULL);
        assertThat(q.where().get(0).literal()).isEmpty();
    }

    @Test
    void parsesIsNotNull() {
        OqlQuery q = OqlParser.parse("SELECT Artworks.title WHERE Artworks.year IS NOT NULL");
        assertThat(q.where()).hasSize(1);
        assertThat(q.where().get(0).op()).isEqualTo(OqlQuery.Op.IS_NOT_NULL);
    }

    @Test
    void parsesOrPredicate() {
        OqlQuery q = OqlParser.parse(
                "SELECT Artworks.title WHERE Artworks.year > '2000' OR Artworks.year IS NULL");
        assertThat(q.where()).hasSize(2);
        assertThat(q.where().get(0).or()).isFalse();
        assertThat(q.where().get(1).or()).isTrue();
        assertThat(q.where().get(1).op()).isEqualTo(OqlQuery.Op.IS_NULL);
    }

    @Test
    void parsesAndOrPrecedence() {
        // A AND B OR C AND D  →  (A AND B) OR (C AND D)
        // In our list: [A(or=false), B(or=false), C(or=true), D(or=false)]
        OqlQuery q = OqlParser.parse(
                "SELECT X.a WHERE X.a = '1' AND X.b = '2' OR X.c = '3' AND X.d = '4'");
        assertThat(q.where()).hasSize(4);
        assertThat(q.where().get(0).or()).isFalse(); // A
        assertThat(q.where().get(1).or()).isFalse(); // B — AND'd with A
        assertThat(q.where().get(2).or()).isTrue();  // C — starts OR group
        assertThat(q.where().get(3).or()).isFalse(); // D — AND'd with C
    }

    @Test
    void parsesOrderBy() {
        OqlQuery q = OqlParser.parse(
                "SELECT Artworks.title, Artworks.price FROM Artworks ORDER BY Artworks.price DESC, Artworks.title ASC LIMIT 10");
        assertThat(q.orderBy()).hasSize(2);
        assertThat(q.orderBy().get(0).className()).isEqualTo("Artworks");
        assertThat(q.orderBy().get(0).property()).isEqualTo("price");
        assertThat(q.orderBy().get(0).desc()).isTrue();
        assertThat(q.orderBy().get(1).property()).isEqualTo("title");
        assertThat(q.orderBy().get(1).desc()).isFalse();
        assertThat(q.limit()).isEqualTo(10);
    }

    @Test
    void rejectsBareStarWithoutFrom() {
        // Cannot infer primary class from SELECT * without FROM
        assertThatThrownBy(() -> {
            OqlQuery q = OqlParser.parse("SELECT *");
            edu.upc.dtak.aran.operators.queryengine.oql.OqlLowerer.lower(q);
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void parsesNegativeNumberLiteral() {
        OqlQuery q = OqlParser.parse("SELECT X.a WHERE X.b > -5");
        assertThat(q.where()).hasSize(1);
        assertThat(q.where().get(0).literal()).isEqualTo("-5");
        assertThat(q.where().get(0).op()).isEqualTo(OqlQuery.Op.GT);
    }

    @Test
    void parsesPathJoin() {
        OqlQuery q = OqlParser.parse(
            "SELECT Artworks.title, IArtist.name FROM Artworks -[hasArtist]-> IArtist");
        assertThat(q.path()).hasSize(1);
        assertThat(q.path().get(0).sourceClass()).isEqualTo("Artworks");
        assertThat(q.path().get(0).joinProperty()).isEqualTo("hasArtist");
        assertThat(q.path().get(0).targetClass()).isEqualTo("IArtist");
        assertThat(q.select()).hasSize(2);
    }

    @Test
    void parsesMultiHopPath() {
        OqlQuery q = OqlParser.parse(
            "SELECT A.x, B.y, C.z FROM A -[p1]-> B -[p2]-> C");
        assertThat(q.path()).hasSize(2);
        assertThat(q.path().get(0).joinProperty()).isEqualTo("p1");
        assertThat(q.path().get(1).joinProperty()).isEqualTo("p2");
        assertThat(q.path().get(1).sourceClass()).isEqualTo("B");
        assertThat(q.path().get(1).targetClass()).isEqualTo("C");
    }

    @Test
    void parsesFullQueryWithAllClauses() {
        String oql = """
            SELECT Artworks.title AS t, IArtist.name
            FROM Artworks -[hasArtist]-> IArtist
            WHERE Artworks.title <> 'none'
            AND IArtist.name = 'Monet'
            LIMIT 10
            """;
        OqlQuery q = OqlParser.parse(oql);
        assertThat(q.select()).hasSize(2);
        assertThat(q.path()).hasSize(1);
        assertThat(q.where()).hasSize(2);
        assertThat(q.limit()).isEqualTo(10);
    }

    @Test
    void astRecordsExist() {
        var proj = OqlQuery.Projection.of("Artworks", "title", null);
        var step = new OqlQuery.PathStep("Artworks", "hasArtist", "IArtist");
        var filter = new OqlQuery.Filter("Artworks", "year", OqlQuery.Op.GT, "'2000'");
        assertThat(proj.className()).isEqualTo("Artworks");
        assertThat(step.joinProperty()).isEqualTo("hasArtist");
        assertThat(filter.op()).isEqualTo(OqlQuery.Op.GT);
    }

    @Test
    void parsesCountStar() {
        OqlQuery q = OqlParser.parse("SELECT COUNT(*) AS n FROM Artworks");
        assertThat(q.select()).hasSize(1);
        OqlQuery.Projection p = q.select().get(0);
        assertThat(p.isAggregate()).isTrue();
        assertThat(p.aggFn()).isEqualTo(OqlQuery.AggFn.COUNT_STAR);
        assertThat(p.as()).isEqualTo("n");
    }

    @Test
    void parsesGroupBy() {
        OqlQuery q = OqlParser.parse(
                "SELECT Artworks.year, COUNT(*) AS total FROM Artworks GROUP BY Artworks.year");
        assertThat(q.groupBy()).hasSize(1);
        assertThat(q.groupBy().get(0).className()).isEqualTo("Artworks");
        assertThat(q.groupBy().get(0).property()).isEqualTo("year");
        assertThat(q.select()).hasSize(2);
        assertThat(q.select().get(0).isAggregate()).isFalse();
        assertThat(q.select().get(1).isAggregate()).isTrue();
        assertThat(q.select().get(1).aggFn()).isEqualTo(OqlQuery.AggFn.COUNT_STAR);
    }

    @Test
    void parsesAggFunctions() {
        OqlQuery q = OqlParser.parse(
                "SELECT SUM(Artworks.price) AS total, AVG(Artworks.price) AS avg, " +
                "MIN(Artworks.price) AS lo, MAX(Artworks.price) AS hi FROM Artworks");
        assertThat(q.select()).hasSize(4);
        assertThat(q.select().get(0).aggFn()).isEqualTo(OqlQuery.AggFn.SUM);
        assertThat(q.select().get(1).aggFn()).isEqualTo(OqlQuery.AggFn.AVG);
        assertThat(q.select().get(2).aggFn()).isEqualTo(OqlQuery.AggFn.MIN);
        assertThat(q.select().get(3).aggFn()).isEqualTo(OqlQuery.AggFn.MAX);
        assertThat(q.select().get(0).property()).isEqualTo("price");
    }

    @Test
    void parsesHavingOnCountStar() {
        OqlQuery q = OqlParser.parse(
                "SELECT Artworks.year, COUNT(*) AS n FROM Artworks GROUP BY Artworks.year HAVING COUNT(*) > 2");
        assertThat(q.having()).hasSize(1);
        OqlQuery.HavingPredicate hp = q.having().get(0);
        assertThat(hp.isAggregate()).isTrue();
        assertThat(hp.aggFn()).isEqualTo(OqlQuery.AggFn.COUNT_STAR);
        assertThat(hp.op()).isEqualTo(OqlQuery.Op.GT);
        assertThat(hp.literal()).isEqualTo("2");
    }

    @Test
    void parsesHavingOnAggProperty() {
        OqlQuery q = OqlParser.parse(
                "SELECT Artworks.year, AVG(Artworks.price) AS avg_price " +
                "FROM Artworks GROUP BY Artworks.year HAVING AVG(Artworks.price) >= 1000");
        assertThat(q.having()).hasSize(1);
        OqlQuery.HavingPredicate hp = q.having().get(0);
        assertThat(hp.aggFn()).isEqualTo(OqlQuery.AggFn.AVG);
        assertThat(hp.className()).isEqualTo("Artworks");
        assertThat(hp.property()).isEqualTo("price");
        assertThat(hp.op()).isEqualTo(OqlQuery.Op.GE);
    }

    @Test
    void parsesGroupByWithJoin() {
        OqlQuery q = OqlParser.parse(
                "SELECT IResearcher.affiliation, COUNT(*) AS papers " +
                "FROM IPaper -[hasAuthor]-> IResearcher " +
                "GROUP BY IResearcher.affiliation " +
                "HAVING COUNT(*) > 1 " +
                "LIMIT 10");
        assertThat(q.path()).hasSize(1);
        assertThat(q.groupBy()).hasSize(1);
        assertThat(q.having()).hasSize(1);
        assertThat(q.limit()).isEqualTo(10);
    }

    // ── Contextual keywords (S6-1) ───────────────────────────────────────────

    @Test
    @DisplayName("a class and properties spelled like keywords are names wherever the grammar wants a name")
    void keywordSpelledNamesParse() {
        OqlQuery q = OqlParser.parse(
                "SELECT Order.count, Order.group AS limit FROM Order -[order]-> Group "
                + "WHERE Order.limit > 5 AND Group.by = 'x' OR Order.is IS NOT NULL "
                + "GROUP BY Order.count, Order.group HAVING Order.count > 1 "
                + "ORDER BY Order.min DESC LIMIT 3");
        assertThat(q.select()).extracting(OqlQuery.Projection::className).containsExactly("Order", "Order");
        assertThat(q.select()).extracting(OqlQuery.Projection::property).containsExactly("count", "group");
        assertThat(q.select().get(1).as()).isEqualTo("limit");
        assertThat(q.path()).containsExactly(new OqlQuery.PathStep("Order", "order", "Group"));
        assertThat(q.where()).extracting(OqlQuery.Filter::property).containsExactly("limit", "by", "is");
        assertThat(q.where().get(2).op()).isEqualTo(OqlQuery.Op.IS_NOT_NULL);
        assertThat(q.groupBy()).extracting(OqlQuery.GroupKey::property).containsExactly("count", "group");
        assertThat(q.having().get(0).property()).isEqualTo("count");
        assertThat(q.orderBy().get(0).property()).isEqualTo("min");
        assertThat(q.orderBy().get(0).desc()).isTrue();
        assertThat(q.limit()).isEqualTo(3);
    }

    @Test
    @DisplayName("an aggregate is its function name followed by '(' — count.x is a class called count")
    void aggregateNeedsAParenthesis() {
        OqlQuery q = OqlParser.parse(
                "SELECT count.x, COUNT(count.max) AS count FROM count "
                + "GROUP BY count.x HAVING MAX(count.max) > 2 ORDER BY count DESC");
        assertThat(q.select().get(0).isAggregate()).isFalse();
        assertThat(q.select().get(0).className()).isEqualTo("count");
        assertThat(q.select().get(1).aggFn()).isEqualTo(OqlQuery.AggFn.COUNT);
        assertThat(q.select().get(1).property()).isEqualTo("max");
        assertThat(q.select().get(1).as()).isEqualTo("count");
        assertThat(q.having().get(0).aggFn()).isEqualTo(OqlQuery.AggFn.MAX);
        assertThat(q.orderBy().get(0).className()).isNull();
        assertThat(q.orderBy().get(0).property()).isEqualTo("count");
    }

    @Test
    @DisplayName("DISTINCT is the keyword unless a '.' follows it")
    void distinctIsContextual() {
        OqlQuery asClass = OqlParser.parse("SELECT Distinct.x FROM Distinct");
        assertThat(asClass.distinct()).isFalse();
        assertThat(asClass.select().get(0).className()).isEqualTo("Distinct");

        OqlQuery both = OqlParser.parse("SELECT DISTINCT Distinct.x FROM Distinct");
        assertThat(both.distinct()).isTrue();
        assertThat(both.select().get(0).className()).isEqualTo("Distinct");
    }

    @Test
    @DisplayName("a clause keyword right after a keyword-named FROM class still opens its clause")
    void clauseKeywordAfterKeywordNamedClass() {
        OqlQuery q = OqlParser.parse("SELECT Where.a FROM Where WHERE Where.a = 1 LIMIT 2");
        assertThat(q.explicitFrom()).isEqualTo("Where");
        assertThat(q.where()).hasSize(1);
        assertThat(q.limit()).isEqualTo(2);
    }

    // ── Quoted identifiers (S6-2) ────────────────────────────────────────────

    @Test
    @DisplayName("a double-quoted identifier writes a name outside the bare alphabet, quotes removed")
    void quotedIdentifiersParse() {
        OqlQuery q = OqlParser.parse(
                "SELECT \"Order\".\"first-name\", \"Order\".\"price.usd\" AS \"unit price\" "
                + "FROM \"Order\" -[\"placed-by\"]-> Customer "
                + "WHERE \"Order\".\"2024_total\" > 5 ORDER BY \"unit price\"");
        assertThat(q.select().get(0).className()).isEqualTo("Order");
        assertThat(q.select().get(0).property()).isEqualTo("first-name");
        assertThat(q.select().get(1).property()).isEqualTo("price.usd");
        assertThat(q.select().get(1).as()).isEqualTo("unit price");
        assertThat(q.path().get(0).joinProperty()).isEqualTo("placed-by");
        assertThat(q.where().get(0).property()).isEqualTo("2024_total");
        assertThat(q.orderBy().get(0).property()).isEqualTo("unit price");
    }

    @Test
    @DisplayName("a doubled double quote inside a quoted identifier is one quote")
    void quotedIdentifierEscape() {
        OqlQuery q = OqlParser.parse("SELECT A.\"say \"\"hi\"\"\" FROM A");
        assertThat(q.select().get(0).property()).isEqualTo("say \"hi\"");
    }

    @Test
    @DisplayName("a quoted word is a name even where a keyword could go")
    void quotedWordIsNeverAKeyword() {
        OqlQuery q = OqlParser.parse(
                "SELECT \"select\".\"from\" FROM \"select\" ORDER BY \"select\".\"from\" DESC");
        assertThat(q.select().get(0).className()).isEqualTo("select");
        assertThat(q.explicitFrom()).isEqualTo("select");
        // A quoted "count" before '(' is a class name, so the '.' a class needs is missing.
        assertThatThrownBy(() -> OqlParser.parse("SELECT \"count\"(*) FROM A"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Expected '.'");
        assertThatThrownBy(() -> OqlParser.parse("\"SELECT\" A.x"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Expected 'SELECT' at position 0");
    }

    @Test
    @DisplayName("an unterminated or empty quoted identifier is a parse error naming its position")
    void badQuotedIdentifiers() {
        assertThatThrownBy(() -> OqlParser.parse("SELECT A.\"x FROM A"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Unterminated quoted identifier at position 9");
        assertThatThrownBy(() -> OqlParser.parse("SELECT A.\"\" FROM A"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Empty quoted identifier at position 9");
    }

    @Test
    @DisplayName("a double-quoted value where a literal belongs says string literals take single quotes")
    void doubleQuotedLiteralGetsAHint() {
        assertThatThrownBy(() -> OqlParser.parse("SELECT A.x FROM A WHERE A.x = \"Monet\""))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Expected literal")
                .hasMessageContaining("'Monet'");
    }

    @Test
    @DisplayName("a column-to-column comparison is still refused with 'Expected literal' (e2e v34 pins the text)")
    void columnToColumnStillRefused() {
        assertThatThrownBy(() -> OqlParser.parse(
                "SELECT Int_curated.journal_code FROM Int_curated "
                + "WHERE Int_curated.journal_code <> Int_curated.impact_band"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Expected literal");
    }

    @Test
    @DisplayName("a token that cannot be a name says what the grammar wanted there")
    void nonNameSaysWhatWasExpected() {
        assertThatThrownBy(() -> OqlParser.parse("SELECT A.x FROM 5"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Expected a class name at position 16, got '5'");
        assertThatThrownBy(() -> OqlParser.parse("SELECT A.x FROM A -[p]-> "))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("got the end of the query");
    }

    @Test
    @DisplayName("an empty or comma-trailed SELECT list points at FROM, while a class called from still projects")
    void emptySelectListPointsAtFrom() {
        assertThatThrownBy(() -> OqlParser.parse("SELECT FROM A"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Expected a projection at position 7, got 'FROM'")
                .hasMessageContaining("SELECT list is empty");
        assertThatThrownBy(() -> OqlParser.parse("SELECT DISTINCT A.x, from A"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("Expected a projection at position 21, got 'from'");
        OqlQuery q = OqlParser.parse("SELECT from.x FROM from");
        assertThat(q.select().get(0).className()).isEqualTo("from");
        assertThat(q.explicitFrom()).isEqualTo("from");
    }

    // ── = NULL is an error, not a silently empty result ───────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"= NULL", "= null"})
    @DisplayName("= NULL is a parse error pointing at IS NULL")
    void equalsNullPointsAtIsNull(String predicate) {
        assertThatThrownBy(() -> OqlParser.parse("SELECT A.x FROM A WHERE A.x " + predicate))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("never true")
                .hasMessageContaining("write IS NULL instead");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<> NULL", "!= NULL"})
    @DisplayName("<> NULL and != NULL are parse errors pointing at IS NOT NULL")
    void notEqualsNullPointsAtIsNotNull(String predicate) {
        assertThatThrownBy(() -> OqlParser.parse("SELECT A.x FROM A WHERE A.x " + predicate))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("write IS NOT NULL instead");
    }

    @Test
    @DisplayName("an ordering comparison with NULL, and any HAVING comparison with NULL, are refused too")
    void otherNullComparisonsRefused() {
        assertThatThrownBy(() -> OqlParser.parse("SELECT A.x FROM A WHERE A.x > NULL"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("IS NULL or IS NOT NULL");
        assertThatThrownBy(() -> OqlParser.parse(
                "SELECT A.x, COUNT(*) FROM A GROUP BY A.x HAVING COUNT(*) = NULL"))
                .isInstanceOf(OqlParser.OqlParseException.class)
                .hasMessageContaining("HAVING has no null test");
    }

    @Test
    @DisplayName("the string 'NULL' and the IS NULL test still parse")
    void nullAsTextAndIsNullStillParse() {
        OqlQuery q = OqlParser.parse("SELECT A.x FROM A WHERE A.x = 'NULL' OR A.x IS NULL");
        assertThat(q.where().get(0).literal()).isEqualTo("'NULL'");
        assertThat(q.where().get(1).op()).isEqualTo(OqlQuery.Op.IS_NULL);
    }
}

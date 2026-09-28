package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the planner resolves the names a query writes: keyword-spelled and quoted names reach the
 * catalog intact (S6-1/S6-2), and an ambiguous name fails only the query that names it (S6-16).
 */
class OqlPlannerNamesTest {

    private static final String INT = "http://ex.org/integration/";
    private static final String SRC = "http://ex.org/source/ord/";

    private static String sql(MappingCatalog catalog, String oql) {
        return PlanExecutor.toSqlFromOptimized(
                PlanExecutor.optimize(new OqlPlanner(catalog).lower(OqlParser.parse(oql))));
    }

    /**
     * A class bootstrapped from an e-commerce table: the class is {@code Order}, two columns are
     * spelled like OQL keywords, and one carries the hyphen {@code Names.iriFragment} keeps.
     */
    @Test
    @DisplayName("a class called Order with properties count, group and first-name plans and executes")
    void keywordAndQuotedNamesExecute() throws Exception {
        Path dir = Files.createTempDirectory("oql-names");
        File snapshot = dir.resolve("orders.json").toFile();
        Files.writeString(snapshot.toPath(), """
                [{"count": 3, "group": "a", "first-name": "Ada"},
                 {"count": 5, "group": "a", "first-name": "Grace"},
                 {"count": 7, "group": "b", "first-name": "Edsger"}]
                """);
        String order = INT + "Order";
        MappingCatalog catalog = new MappingCatalog(Map.of(order,
                new MappingCatalog.IntegratedClass(order, List.of(new MappingCatalog.SourceView(
                        "ord", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(INT + "count", "count", INT + "group", "group",
                                SRC + "first-name", "first-name"))))),
                List.of(), List.of(), "v1");

        String sql = sql(catalog, "SELECT Order.\"first-name\", Order.count FROM Order "
                + "WHERE Order.group = 'a' ORDER BY Order.count DESC");

        DuckDbExecutor.SnapshotResolver resolver =
                ds -> "read_json_auto('" + snapshot.getAbsolutePath() + "')";
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, Map.of())) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);
            assertThat(result.headers()).containsExactly("first-name", "count");
            assertThat(result.rows()).extracting(row -> row.get(0)).containsExactly("Grace", "Ada");
        }
    }

    /** Two unrelated classes minted under different namespaces, one local name — plus a bystander. */
    private static MappingCatalog ambiguousCatalog() {
        String paperA = "http://a.org/Paper";
        String paperB = "http://b.org/Paper";
        String venue = INT + "Venue";
        String author = INT + "Author";
        MappingCatalog.SourceView paperView = new MappingCatalog.SourceView(
                "p", "SELECT 1", Map.of(INT + "title", "title"));
        return new MappingCatalog(Map.of(
                paperA, new MappingCatalog.IntegratedClass(paperA, List.of(paperView)),
                paperB, new MappingCatalog.IntegratedClass(paperB, List.of(paperView)),
                venue, new MappingCatalog.IntegratedClass(venue, List.of(new MappingCatalog.SourceView(
                        "v", "SELECT 1", Map.of(INT + "name", "name", INT + "venueId", "id")))),
                author, new MappingCatalog.IntegratedClass(author, List.of(new MappingCatalog.SourceView(
                        "a", "SELECT 1", Map.of(INT + "fullName", "full_name", INT + "venueRef", "venue"))))),
                List.of(
                        new MappingCatalog.JoinProperty("http://a.org/at", venue, author,
                                INT + "venueId", INT + "venueRef"),
                        new MappingCatalog.JoinProperty("http://b.org/at", venue, author,
                                INT + "venueId", INT + "venueRef")),
                List.of(), "v1");
    }

    @Test
    @DisplayName("an ambiguous class or join-property name does not fail a query that never names it")
    void ambiguityIsLazy() {
        MappingCatalog catalog = ambiguousCatalog();

        assertThatCode(() -> new OqlPlanner(catalog)).doesNotThrowAnyException();
        assertThat(sql(catalog, "SELECT Venue.name FROM Venue")).contains("u_v_Venue");
    }

    @Test
    @DisplayName("naming the ambiguous class fails with the IRIs that share its name")
    void namingTheAmbiguousClassFails() {
        assertThatThrownBy(() -> sql(ambiguousCatalog(), "SELECT Paper.title FROM Paper"))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("Ambiguous class name 'Paper'")
                .hasMessageContaining("http://a.org/Paper")
                .hasMessageContaining("http://b.org/Paper");
    }

    @Test
    @DisplayName("naming the ambiguous join property fails; the same path without it is not the planner's to guess")
    void namingTheAmbiguousJoinFails() {
        assertThatThrownBy(() -> sql(ambiguousCatalog(),
                "SELECT Venue.name, Author.fullName FROM Venue -[at]-> Author"))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("Ambiguous join-property name 'at'");
    }

    @Test
    @DisplayName("an unknown class still lists what is available, ambiguous names included")
    void unknownClassListsAvailable() {
        assertThatThrownBy(() -> sql(ambiguousCatalog(), "SELECT Nope.x FROM Nope"))
                .isInstanceOf(OqlPlanner.OqlPlanException.class)
                .hasMessageContaining("Unknown class 'Nope'")
                .hasMessageContaining("Paper")
                .hasMessageContaining("Venue");
    }
}

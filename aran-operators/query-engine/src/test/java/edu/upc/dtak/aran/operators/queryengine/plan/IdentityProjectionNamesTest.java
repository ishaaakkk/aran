package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V70 (found live by S6b): a projection that is an <em>identity</em> over what it reads — every
 * column, in the input's order — lost its renames and collapsed to its input, so it unparsed as
 * {@code SELECT *}.
 *
 * <p>Two consequences, one per wrapper verdict. Over an EXPANDED class the input is the raw
 * {@code src_} relation, so rows came back under RAW source column names and in the view's
 * PHYSICAL column order — not the order the planner's {@code SrcTable} declares (sorted), so the
 * values could land under the wrong positions. Over an OPAQUE class the input is the {@code u_}
 * view, whose columns are full property IRIs, so the same query answered with full-IRI headers:
 * the cube star table S6b watched.
 *
 * <p>The fixture's JSON writes {@code b_raw} before {@code a_raw}, so the file's physical order
 * disagrees with the sorted order the planner assumes.
 */
class IdentityProjectionNamesTest {

    private static final String NS = "http://x/";
    private static final String CLS = NS + "integration/C";
    private static final String PA = NS + "integration/a";
    private static final String PB = NS + "integration/b";
    private static final String SA = NS + "source/ds/c/a_raw";
    private static final String SB = NS + "source/ds/c/b_raw";

    private static Path snapshot;

    @BeforeAll
    static void writeSnapshot() throws Exception {
        snapshot = Files.createTempFile("aran-identity-projection", ".json");
        Files.writeString(snapshot, "[{\"b_raw\":\"B1\",\"a_raw\":\"A1\"},{\"b_raw\":\"B2\",\"a_raw\":\"A2\"}]");
    }

    private static MappingCatalog catalog(String expansionStatus) {
        String wrapper = "SELECT \"a_raw\" AS \"" + SA + "\", \"b_raw\" AS \"" + SB + "\" FROM ${SNAPSHOT}";
        var source = new MappingCatalog.SourceView("ds", wrapper, Map.of(PA, SA, PB, SB), 2L,
                List.of(), NS + "source/ds/c", Map.of(), Map.of("a_raw", SA, "b_raw", SB),
                expansionStatus);
        return new MappingCatalog(Map.of(CLS, new MappingCatalog.IntegratedClass(CLS, List.of(source))),
                List.of(), List.of(), "v1");
    }

    private static DuckDbExecutor.QueryResult run(String expansionStatus, String oql) throws Exception {
        MappingCatalog catalog = catalog(expansionStatus);
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(
                new OqlPlanner(catalog, Map.of()).lower(OqlParser.parse(oql))));
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog,
                ds -> "read_json_auto('" + snapshot + "')", Map.of())) {
            exec.prepare();
            return exec.run(sql);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"EXPANDED", "OPAQUE"})
    @DisplayName("SELECT C.a, C.b answers under the names asked for, each column holding its own values")
    void identityProjectionKeepsItsNames(String status) throws Exception {
        DuckDbExecutor.QueryResult result = run(status, "SELECT C.a, C.b FROM C");

        assertThat(result.headers()).containsExactly("a", "b");
        assertThat(result.rows()).containsExactlyInAnyOrder(List.of("A1", "B1"), List.of("A2", "B2"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"EXPANDED", "OPAQUE"})
    @DisplayName("a filtered identity projection keeps its names once the optimizer merges it down")
    void filteredIdentityProjectionKeepsItsNames(String status) throws Exception {
        DuckDbExecutor.QueryResult result = run(status, "SELECT C.a, C.b FROM C WHERE C.a <> 'A2'");

        assertThat(result.headers()).containsExactly("a", "b");
        assertThat(result.rows()).containsExactly(List.of("A1", "B1"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"EXPANDED", "OPAQUE"})
    @DisplayName("SELECT C.* answers under the class's property IRIs in the planner's column order")
    void starKeepsTheClassNamesAndOrder(String status) throws Exception {
        DuckDbExecutor.QueryResult result = run(status, "SELECT C.* FROM C");

        assertThat(result.headers()).containsExactly(PA, PB);
        assertThat(result.rows()).containsExactlyInAnyOrder(List.of("A1", "B1"), List.of("A2", "B2"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"EXPANDED", "OPAQUE"})
    @DisplayName("ORDER BY a property a star projected still finds its column")
    void starOrderedByAProperty(String status) throws Exception {
        DuckDbExecutor.QueryResult result = run(status, "SELECT C.* FROM C ORDER BY C.a DESC");

        assertThat(result.headers()).containsExactly(PA, PB);
        assertThat(result.rows()).containsExactly(List.of("A2", "B2"), List.of("A1", "B1"));
    }

    /**
     * Integrated IRIs that sort OPPOSITE to their raw columns ({@code zeta → a_raw},
     * {@code alpha → b_raw}) make the query's projection and the int-level projection two
     * permutations whose product is the identity. Under a WHERE they are not merged at lowering,
     * and {@code ProjectMergeRule}'s permutation branch rebuilds the pair without forcing the
     * names — handing Volcano the wrapper projection under its source IRIs as an equivalent the
     * root may pick. The fixture above sorts both sides alike, so it never reaches that branch.
     */
    private static MappingCatalog oppositeOrderCatalog(String expansionStatus) {
        String wrapper = "SELECT \"a_raw\" AS \"" + SA + "\", \"b_raw\" AS \"" + SB + "\" FROM ${SNAPSHOT}";
        var source = new MappingCatalog.SourceView("ds", wrapper,
                Map.of(NS + "integration/zeta", SA, NS + "integration/alpha", SB), 2L,
                List.of(), NS + "source/ds/c", Map.of(), Map.of("a_raw", SA, "b_raw", SB),
                expansionStatus);
        return new MappingCatalog(Map.of(CLS, new MappingCatalog.IntegratedClass(CLS, List.of(source))),
                List.of(), List.of(), "v1");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "EXPANDED|SELECT C.zeta, C.alpha FROM C WHERE C.zeta <> 'A2'",
            "OPAQUE|SELECT C.zeta, C.alpha FROM C WHERE C.zeta <> 'A2'",
            "EXPANDED|SELECT C.zeta, C.alpha FROM C WHERE C.zeta <> 'A3' ORDER BY C.zeta DESC",
            "OPAQUE|SELECT C.zeta, C.alpha FROM C WHERE C.zeta <> 'A3' ORDER BY C.zeta DESC",
            "EXPANDED|SELECT C.zeta, C.alpha FROM C",
            "OPAQUE|SELECT C.zeta, C.alpha FROM C"})
    @DisplayName("names sorting opposite to their raw columns keep the names asked for, under a WHERE too")
    void oppositeSortOrderKeepsItsNames(String status, String oql) throws Exception {
        MappingCatalog catalog = oppositeOrderCatalog(status);
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(
                new OqlPlanner(catalog, Map.of()).lower(OqlParser.parse(oql))));
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog,
                ds -> "read_json_auto('" + snapshot + "')", Map.of())) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);

            assertThat(result.headers()).containsExactly("zeta", "alpha");
            assertThat(result.rows()).contains(List.of("A1", "B1")).doesNotContain(List.of("B1", "A1"));
        }
    }

    /**
     * Local names equal to the raw columns make the projection an identity with no rename at all,
     * which {@code RelToSqlConverter} writes as {@code SELECT *} whatever the planner does — so
     * the {@code src_} relation itself must never be read positionally.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("an EXPANDED class whose names equal its raw columns still answers in the declared order")
    void sameNamesStillAnswerInDeclaredOrder() throws Exception {
        String a = NS + "integration/a_raw";
        String b = NS + "integration/b_raw";
        String wrapper = "SELECT \"a_raw\" AS \"" + SA + "\", \"b_raw\" AS \"" + SB + "\" FROM ${SNAPSHOT}";
        var source = new MappingCatalog.SourceView("ds", wrapper, Map.of(a, SA, b, SB), 2L,
                List.of(), NS + "source/ds/c", Map.of(), Map.of("a_raw", SA, "b_raw", SB), "EXPANDED");
        MappingCatalog catalog = new MappingCatalog(
                Map.of(CLS, new MappingCatalog.IntegratedClass(CLS, List.of(source))),
                List.of(), List.of(), "v1");
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(
                new OqlPlanner(catalog, Map.of()).lower(OqlParser.parse("SELECT C.a_raw, C.b_raw FROM C"))));
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog,
                ds -> "read_json_auto('" + snapshot + "')", Map.of())) {
            exec.prepare();
            DuckDbExecutor.QueryResult result = exec.run(sql);

            assertThat(result.headers()).containsExactly("a_raw", "b_raw");
            assertThat(result.rows()).containsExactlyInAnyOrder(List.of("A1", "B1"), List.of("A2", "B2"));
        }
    }
}

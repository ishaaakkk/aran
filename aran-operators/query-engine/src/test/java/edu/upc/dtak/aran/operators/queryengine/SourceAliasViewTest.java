package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.SourceCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sources-tier alias layer: one human-named view per wrapper, so plain SQL can say
 * {@code SELECT title FROM acm} instead of naming a UUID-bearing {@code w_} view with IRI
 * columns.
 *
 * <p>Backed on {@code w_}, not {@code u_}, deliberately: the tier shows source-native rows,
 * including ones denial constraints quarantine — stated in the tier's docs rather than
 * discovered.
 */
class SourceAliasViewTest {

    private static final String SRC = "http://ex.com/source/";

    private static MappingCatalog.SourceView view(String datasetId, String classLocal,
                                                  Map<String, String> columnMap) {
        return new MappingCatalog.SourceView(datasetId, "SELECT * FROM ${SNAPSHOT}",
                columnMap, 0L, List.of(), SRC + datasetId + "/" + classLocal);
    }

    /** One integrated class {@code IPaper} fed by {@code acm}: two mapped columns. */
    private static MappingCatalog oneSourceCatalog() {
        Map<String, String> columnMap = Map.of(
                "http://ex.com/global/title", SRC + "ds1/acm/title",
                "http://ex.com/global/year", SRC + "ds1/acm/year");
        return new MappingCatalog(Map.of(
                "urn:IPaper", new MappingCatalog.IntegratedClass("urn:IPaper",
                        List.of(view("ds1", "acm", columnMap)))),
                List.of(), List.of(), "v1");
    }

    @Test
    @DisplayName("the catalog derives one sources-tier table per (dataset, source class)")
    void catalogDerivesSourceTables() {
        SourceCatalog sources = oneSourceCatalog().sourceCatalog();
        assertThat(sources.tables()).hasSize(1);
        assertThat(sources.tables().get(0).sqlName()).isEqualTo("acm");
        assertThat(sources.tables().get(0).datasetId()).isEqualTo("ds1");
    }

    @Test
    @DisplayName("buildDdl emits an alias view: SourceCatalog name, backed on w_, local-name columns")
    void aliasViewEmitted() {
        List<String> ddl = DuckDbExecutor.buildDdl(oneSourceCatalog(), ds -> "read_json_auto('x')");
        String alias = ddl.stream().filter(s -> s.startsWith("CREATE OR REPLACE VIEW \"acm\" AS "))
                .findFirst().orElseThrow(() -> new AssertionError("no alias view in: " + ddl));
        assertThat(alias).contains("FROM w_ds1_IPaper");
        // Sorted by local name, each source IRI aliased to it.
        assertThat(alias).contains(
                "\"" + SRC + "ds1/acm/title\" AS \"title\", \"" + SRC + "ds1/acm/year\" AS \"year\"");
    }

    @Test
    @DisplayName("a SourceView without a source class IRI gets no alias view")
    void noSrcClassNoAlias() {
        MappingCatalog cat = new MappingCatalog(Map.of(
                "urn:IPaper", new MappingCatalog.IntegratedClass("urn:IPaper", List.of(
                        new MappingCatalog.SourceView("ds1", "SELECT * FROM ${SNAPSHOT}",
                                Map.of("g", "s"))))),
                List.of(), List.of(), "v1");
        assertThat(cat.sourceCatalog().tables()).isEmpty();
        assertThat(DuckDbExecutor.buildDdl(cat, ds -> "read_json_auto('x')"))
                .noneMatch(s -> s.contains("VIEW  AS"));
    }

    @Test
    @DisplayName("a source class feeding two integrated classes is one alias view, not two")
    void sharedSourceClassAliasedOnce() {
        MappingCatalog.SourceView shared = view("ds1", "acm",
                Map.of("http://ex.com/global/title", SRC + "ds1/acm/title"));
        MappingCatalog cat = new MappingCatalog(Map.of(
                "urn:IPaper", new MappingCatalog.IntegratedClass("urn:IPaper", List.of(shared)),
                "urn:IArticle", new MappingCatalog.IntegratedClass("urn:IArticle", List.of(shared))),
                List.of(), List.of(), "v1");
        List<String> ddl = DuckDbExecutor.buildDdl(cat, ds -> "read_json_auto('x')");
        assertThat(ddl.stream().filter(s -> s.startsWith("CREATE OR REPLACE VIEW \"acm\" AS "))).hasSize(1);
    }

    @Test
    @DisplayName("two datasets sharing a class name: the later one takes a dataset-id suffix")
    void collisionTakesSuffix() {
        Map<String, String> colsA = Map.of("g1", SRC + "aaaa1111/people/name");
        Map<String, String> colsB = Map.of("g2", SRC + "bbbb2222/people/name");
        MappingCatalog cat = new MappingCatalog(Map.of(
                "urn:IA", new MappingCatalog.IntegratedClass("urn:IA",
                        List.of(view("aaaa1111", "people", colsA))),
                "urn:IB", new MappingCatalog.IntegratedClass("urn:IB",
                        List.of(view("bbbb2222", "people", colsB)))),
                List.of(), List.of(), "v1");
        SourceCatalog sources = cat.sourceCatalog();
        assertThat(sources.tables()).extracting(SourceCatalog.SourceTable::sqlName)
                .containsExactlyInAnyOrder("people", "people_bbbb2222");
        List<String> ddl = DuckDbExecutor.buildDdl(cat, ds -> "read_json_auto('x')");
        assertThat(ddl).anyMatch(s -> s.startsWith("CREATE OR REPLACE VIEW \"people\" AS "));
        assertThat(ddl).anyMatch(s -> s.startsWith("CREATE OR REPLACE VIEW \"people_bbbb2222\" AS "));
    }

    @Test
    @DisplayName("pruning to one class does not move the other's collision suffix")
    void pruningDoesNotShiftNames() {
        // Names must be a function of the whole catalog, not of what a statement materialises —
        // otherwise the same table answers to different names depending on the query before it.
        Map<String, String> colsA = Map.of("g1", SRC + "aaaa1111/people/name");
        Map<String, String> colsB = Map.of("g2", SRC + "bbbb2222/people/name");
        MappingCatalog cat = new MappingCatalog(Map.of(
                "urn:IA", new MappingCatalog.IntegratedClass("urn:IA",
                        List.of(view("aaaa1111", "people", colsA))),
                "urn:IB", new MappingCatalog.IntegratedClass("urn:IB",
                        List.of(view("bbbb2222", "people", colsB)))),
                List.of(), List.of(), "v1");
        List<String> ddl = DuckDbExecutor.buildDdl(cat, ds -> "read_json_auto('x')",
                Map.of(), java.util.Set.of("urn:IB"));
        assertThat(ddl).anyMatch(s -> s.startsWith("CREATE OR REPLACE VIEW \"people_bbbb2222\" AS "));
        assertThat(ddl).noneMatch(s -> s.startsWith("CREATE OR REPLACE VIEW \"people\" AS "));
    }

    @Test
    @DisplayName("two global properties mapped to one source column project it once")
    void duplicateSourceColumnsProjectOnce() {
        // A join key is routinely a sub-property of both a datatype IR and P_l, so columnMap
        // carries two keys with the same value; projecting it twice is a DuckDB duplicate-name
        // error for the whole view.
        Map<String, String> columnMap = new LinkedHashMap<>();
        columnMap.put("http://ex.com/global/id", SRC + "ds1/acm/id");
        columnMap.put("urn:hasAuthorJoin", SRC + "ds1/acm/id");
        MappingCatalog cat = new MappingCatalog(Map.of(
                "urn:IPaper", new MappingCatalog.IntegratedClass("urn:IPaper",
                        List.of(view("ds1", "acm", columnMap)))),
                List.of(), List.of(), "v1");
        String alias = DuckDbExecutor.buildDdl(cat, ds -> "read_json_auto('x')").stream()
                .filter(s -> s.startsWith("CREATE OR REPLACE VIEW \"acm\" AS "))
                .findFirst().orElseThrow();
        assertThat(alias).containsOnlyOnce("AS \"id\"");
    }

    @Test
    @DisplayName("the sources-qualified name resolves too — it is what the JDBC tree tells a client to type")
    void sourcesQualifiedNameResolves() throws Exception {
        // Found by JdbcRealClientIT against a live stack (V28): the JDBC tree lists the table
        // under schema `sources`, so clients write `sources.acm` — and DuckDB had no schema of
        // that name. Same trick as ProductSqlExecutor: make the schema real.
        Path data = Files.createTempFile("aran-alias-", ".json");
        try {
            Files.writeString(data, "{\"title\":\"a\"}\n");
            String wrapperSql = "SELECT title AS \"" + SRC + "ds1/acm/title\" FROM ${SNAPSHOT}";
            MappingCatalog cat = new MappingCatalog(Map.of(
                    "urn:IPaper", new MappingCatalog.IntegratedClass("urn:IPaper", List.of(
                            new MappingCatalog.SourceView("ds1", wrapperSql,
                                    Map.of("http://ex.com/g/title", SRC + "ds1/acm/title"), 0L,
                                    List.of(), SRC + "ds1/acm")))),
                    List.of(), List.of(), "v1");
            try (DuckDbExecutor exec = new DuckDbExecutor(cat,
                    ds -> "read_json_auto('" + data.toAbsolutePath() + "')")) {
                exec.prepare();
                assertThat(exec.run("SELECT title FROM sources.acm").rows()).hasSize(1);
                assertThat(exec.run("SELECT title FROM acm").rows()).hasSize(1);
            }
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("a source table called order: prepare succeeds and both tiers answer (S7-8)")
    void reservedWordSourceTableIsQuoted() throws Exception {
        // SourceCatalog keeps a relational table's own name, so `order` reached the DDL as a bare
        // reserved word: `CREATE OR REPLACE VIEW order` is a DuckDB parse error, and prepare()
        // failed every graph-tier statement on the class — not only sources-tier ones.
        Path data = Files.createTempFile("aran-alias-", ".json");
        try {
            Files.writeString(data, "{\"total\":7}\n");
            String wrapperSql = "SELECT total AS \"" + SRC + "ds1/order/total\" FROM ${SNAPSHOT}";
            MappingCatalog cat = new MappingCatalog(Map.of(
                    "urn:IOrder", new MappingCatalog.IntegratedClass("urn:IOrder", List.of(
                            new MappingCatalog.SourceView("ds1", wrapperSql,
                                    Map.of("http://ex.com/g/total", SRC + "ds1/order/total"), 0L,
                                    List.of(), SRC + "ds1/order")))),
                    List.of(), List.of(), "v1");
            assertThat(cat.sourceCatalog().tables().get(0).sqlName()).isEqualTo("order");
            try (DuckDbExecutor exec = new DuckDbExecutor(cat,
                    ds -> "read_json_auto('" + data.toAbsolutePath() + "')")) {
                exec.prepare();
                assertThat(exec.run("SELECT * FROM int_IOrder").rows()).hasSize(1);
                assertThat(exec.run("SELECT total FROM sources.\"order\"").rows()).hasSize(1);
                assertThat(exec.run("SELECT total FROM \"order\"").rows()).hasSize(1);
            }
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("the alias view is queryable end-to-end with plain SQL")
    void aliasViewRuns() throws Exception {
        Path data = Files.createTempFile("aran-alias-", ".json");
        try {
            Files.writeString(data, "{\"title\":\"a\",\"year\":2020}\n");
            // The wrapper aliases raw columns to source property IRIs, as WrapperSqlBuilder does.
            String wrapperSql = "SELECT title AS \"" + SRC + "ds1/acm/title\", year AS \""
                    + SRC + "ds1/acm/year\" FROM ${SNAPSHOT}";
            Map<String, String> columnMap = Map.of(
                    "http://ex.com/global/title", SRC + "ds1/acm/title",
                    "http://ex.com/global/year", SRC + "ds1/acm/year");
            MappingCatalog cat = new MappingCatalog(Map.of(
                    "urn:IPaper", new MappingCatalog.IntegratedClass("urn:IPaper", List.of(
                            new MappingCatalog.SourceView("ds1", wrapperSql, columnMap, 0L,
                                    List.of(), SRC + "ds1/acm")))),
                    List.of(), List.of(), "v1");
            try (DuckDbExecutor exec = new DuckDbExecutor(cat,
                    ds -> "read_json_auto('" + data.toAbsolutePath() + "')")) {
                exec.prepare();
                DuckDbExecutor.QueryResult result = exec.run("SELECT title, year FROM acm");
                assertThat(result.headers()).containsExactly("title", "year");
                assertThat(result.rows()).hasSize(1);
            }
        } finally {
            Files.deleteIfExists(data);
        }
    }
}

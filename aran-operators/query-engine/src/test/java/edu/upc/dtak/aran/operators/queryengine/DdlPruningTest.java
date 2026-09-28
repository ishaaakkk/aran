package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Materialising only the classes a statement names.
 *
 * <p>The cost being avoided is not the {@code CREATE VIEW} text — it is that
 * {@link DuckDbExecutor.SnapshotResolver} is what downloads a snapshot from the lake, and
 * {@code prepare()} used to call it for every source of every class. The assertions here are
 * therefore about <em>which datasets the resolver is asked for</em>, which is the thing that costs
 * a network round trip.
 */
class DdlPruningTest {

    private static final String NS = "http://example.com/";

    /** A catalog of {@code n} single-source classes named C0…Cn-1, backed by datasets ds0…dsn-1. */
    private static MappingCatalog catalog(int n) {
        Map<String, MappingCatalog.IntegratedClass> classes = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String iri = NS + "C" + i;
            classes.put(iri, new MappingCatalog.IntegratedClass(iri, List.of(
                    new MappingCatalog.SourceView("ds" + i, "SELECT * FROM ${SNAPSHOT}",
                            Map.of(NS + "name", "n"), 10))));
        }
        return new MappingCatalog(classes, List.of(), List.of(), "v1");
    }

    /** Records every dataset the resolver is asked to resolve. */
    private record Recording(DuckDbExecutor.SnapshotResolver resolver, List<String> asked) {}

    private static Recording recording(Path data) {
        List<String> asked = new CopyOnWriteArrayList<>();
        return new Recording(ds -> {
            asked.add(ds);
            return "read_json_auto('" + data.toAbsolutePath() + "')";
        }, asked);
    }

    private static Path fixture() throws Exception {
        Path p = Files.createTempFile("aran-prune-", ".json");
        Files.writeString(p, "{\"n\":\"a\"}\n");
        return p;
    }

    // ── the resolver call count ──────────────────────────────────────────────

    @Test
    @DisplayName("a single-class query fetches one snapshot, not the whole catalog")
    void oneClassQueryFetchesOneSnapshot() throws Exception {
        Path data = fixture();
        try {
            Recording r = recording(data);
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(20), r.resolver())) {
                exec.onlyClassesReferencedBy("SELECT * FROM int_C7").prepare();
                assertThat(exec.run("SELECT * FROM int_C7").rows()).hasSize(1);
            }
            assertThat(r.asked())
                    .as("only C7's dataset should have been resolved")
                    .containsExactly("ds7");
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("without pruning the same query resolves all twenty — the behaviour being fixed")
    void unprunedFetchesEverything() throws Exception {
        Path data = fixture();
        try {
            Recording r = recording(data);
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(20), r.resolver())) {
                exec.prepare();   // no onlyClassesReferencedBy
            }
            assertThat(r.asked())
                    .as("this is what every query used to cost")
                    .hasSize(20);
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("a join across two classes fetches exactly those two")
    void joinFetchesBothSides() throws Exception {
        Path data = fixture();
        try {
            Recording r = recording(data);
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(20), r.resolver())) {
                exec.onlyClassesReferencedBy(
                        "SELECT * FROM u_ds3_C3 JOIN u_ds11_C11 ON 1=1").prepare();
            }
            assertThat(r.asked()).containsExactlyInAnyOrder("ds3", "ds11");
        } finally {
            Files.deleteIfExists(data);
        }
    }

    // ── which names count as a reference ─────────────────────────────────────

    @Test
    @DisplayName("int_, u_, w_, src_ and q_ names all pull their class in")
    void everyGeneratedViewNameCounts() {
        MappingCatalog cat = catalog(3);
        assertThat(DuckDbExecutor.referencedClasses(cat, "SELECT * FROM int_C1")).containsExactly(NS + "C1");
        assertThat(DuckDbExecutor.referencedClasses(cat, "SELECT * FROM u_ds1_C1")).containsExactly(NS + "C1");
        assertThat(DuckDbExecutor.referencedClasses(cat, "SELECT * FROM w_ds1_C1")).containsExactly(NS + "C1");
        assertThat(DuckDbExecutor.referencedClasses(cat, "SELECT * FROM q_C1")).containsExactly(NS + "C1");
        assertThat(DuckDbExecutor.referencedClasses(cat, "SELECT * FROM src_ds1")).containsExactly(NS + "C1");
    }

    @Test
    @DisplayName("quoted identifiers are matched, since RelToSql emits them")
    void quotedIdentifiersMatch() {
        assertThat(DuckDbExecutor.referencedClasses(catalog(3), "SELECT * FROM \"u_ds2_C2\""))
                .containsExactly(NS + "C2");
    }

    @Test
    @DisplayName("a longer name is not matched by a shorter one's prefix")
    void prefixDoesNotMatch() {
        // int_C1 must not pull in a class whose view is int_C11 — hence the word boundary.
        Map<String, MappingCatalog.IntegratedClass> classes = new LinkedHashMap<>();
        for (String suffix : List.of("C1", "C11")) {
            String iri = NS + suffix;
            classes.put(iri, new MappingCatalog.IntegratedClass(iri, List.of(
                    new MappingCatalog.SourceView("ds_" + suffix, "SELECT * FROM ${SNAPSHOT}",
                            Map.of(NS + "name", "n"), 1))));
        }
        MappingCatalog cat = new MappingCatalog(classes, List.of(), List.of(), "v1");

        assertThat(DuckDbExecutor.referencedClasses(cat, "SELECT * FROM int_C11"))
                .containsExactly(NS + "C11");
        assertThat(DuckDbExecutor.referencedClasses(cat, "SELECT * FROM int_C1"))
                .containsExactly(NS + "C1");
    }

    @Test
    @DisplayName("a statement naming nothing prepares nothing")
    void noReferencesMeansNoDdl() {
        assertThat(DuckDbExecutor.referencedClasses(catalog(5), "SELECT 1")).isEmpty();
        assertThat(DuckDbExecutor.buildDdl(catalog(5), ds -> "x", Map.of(), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("null requiredClasses still means the whole catalog — explain depends on it")
    void nullMeansEverything() {
        List<String> all = DuckDbExecutor.buildDdl(catalog(4), ds -> "read_json_auto('x')", Map.of(), null);
        List<String> intViews = new ArrayList<>(all.stream().filter(s -> s.contains("VIEW int_")).toList());
        assertThat(intViews).hasSize(4);
    }

    // ── SourceCatalog alias names ─────────────────────────────────────────────

    /** As {@link #catalog}, but each source records its class IRI so it gets an alias view. */
    private static MappingCatalog aliasedCatalog(int n) {
        Map<String, MappingCatalog.IntegratedClass> classes = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String iri = NS + "C" + i;
            // Real wrappers alias each raw column to its source property IRI (WrapperSqlBuilder);
            // the alias view depends on that, so the fixture must do it too.
            classes.put(iri, new MappingCatalog.IntegratedClass(iri, List.of(
                    new MappingCatalog.SourceView("ds" + i,
                            "SELECT n AS \"" + NS + "src" + i + "/n\" FROM ${SNAPSHOT}",
                            Map.of(NS + "name", NS + "src" + i + "/n"), 10, List.of(),
                            NS + "source/ds" + i + "/tbl" + i))));
        }
        return new MappingCatalog(classes, List.of(), List.of(), "v1");
    }

    @Test
    @DisplayName("a SourceCatalog alias name pulls its backing class in — the pruning trap")
    void aliasNameCounts() {
        // Without this, `SELECT * FROM tbl1` matches no int_/u_/w_/src_ candidate, no class is
        // required, no views are created, and the query fails with "table not found" — which
        // looks like a naming bug and is not.
        assertThat(DuckDbExecutor.referencedClasses(aliasedCatalog(3), "SELECT * FROM tbl1"))
                .containsExactly(NS + "C1");
    }

    @Test
    @DisplayName("a query naming only the alias prepares, runs, and fetches one snapshot")
    void aliasOnlyQueryRunsPruned() throws Exception {
        Path data = fixture();
        try {
            Recording r = recording(data);
            try (DuckDbExecutor exec = new DuckDbExecutor(aliasedCatalog(20), r.resolver())) {
                exec.onlyClassesReferencedBy("SELECT n FROM tbl7").prepare();
                assertThat(exec.run("SELECT n FROM tbl7").rows()).hasSize(1);
            }
            assertThat(r.asked()).containsExactly("ds7");
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    @DisplayName("for a shared source class, pruning agrees with the alias's backing class")
    void aliasPruningAgreesWithBackingClass() {
        // The alias view reads FROM one specific w_ view; if referencedClasses required a
        // different class than the one that emits the alias, the statement would materialise
        // views the alias does not reference and miss the one it does.
        MappingCatalog.SourceView shared = new MappingCatalog.SourceView(
                "ds1", "SELECT * FROM ${SNAPSHOT}", Map.of(NS + "name", NS + "src/n"),
                10, List.of(), NS + "source/ds1/tbl");
        Map<String, MappingCatalog.IntegratedClass> classes = new LinkedHashMap<>();
        classes.put(NS + "CB", new MappingCatalog.IntegratedClass(NS + "CB", List.of(shared)));
        classes.put(NS + "CA", new MappingCatalog.IntegratedClass(NS + "CA", List.of(shared)));
        MappingCatalog cat = new MappingCatalog(classes, List.of(), List.of(), "v1");

        Set<String> required = DuckDbExecutor.referencedClasses(cat, "SELECT * FROM tbl");
        List<String> ddl = DuckDbExecutor.buildDdl(cat, ds -> "read_json_auto('x')",
                Map.of(), required);
        assertThat(ddl).anyMatch(s -> s.startsWith("CREATE OR REPLACE VIEW \"tbl\" AS "));
    }

    // ── the direction the matcher is allowed to be wrong in ──────────────────

    @Test
    @DisplayName("a view name inside a string literal over-includes, which is the safe direction")
    void overInclusionIsAcceptable() {
        // Under-inclusion would produce "table does not exist" at query time; over-inclusion costs
        // one unnecessary snapshot fetch. Pinned so the asymmetry stays deliberate rather than
        // becoming a surprise if someone tightens the matcher.
        assertThat(DuckDbExecutor.referencedClasses(catalog(3),
                "SELECT * FROM int_C0 WHERE name = 'see int_C2 for details'"))
                .containsExactlyInAnyOrder(NS + "C0", NS + "C2");
    }
}

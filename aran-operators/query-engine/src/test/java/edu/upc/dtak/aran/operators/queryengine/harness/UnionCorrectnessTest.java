package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end execution of a <em>multi-source</em> integrated class — the shape the planner
 * now lowers to an explicit UNION ALL over per-source {@code u_} views instead of an opaque
 * {@code int_} scan.
 *
 * <p>Nothing executed this before. Every existing harness fixture puts its sources in
 * different classes, so the union path had plan-level coverage only, and the risky parts of
 * it — NULL padding for a property one source does not supply, column ordering across
 * branches, and the union member's view name matching what {@code DuckDbExecutor} actually
 * creates — were all unverified against a real database.
 */
class UnionCorrectnessTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";

    private static final String PAPER = NS + "integration/Paper";
    private static final String P_TITLE = NS + "integration/title";
    private static final String P_YEAR = NS + "integration/year";

    /**
     * One integrated class fed by two sources with different physical column names —
     * exactly what a CLASS alignment across two datasets produces.
     */
    private static MappingCatalog twoSourceCatalog() {
        var acm = new MappingCatalog.SourceView("acm", "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_TITLE, "title", P_YEAR, "pub_year"));
        var semantic = new MappingCatalog.SourceView("semantic", "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_TITLE, "name", P_YEAR, "year"));
        return new MappingCatalog(
                Map.of(PAPER, new MappingCatalog.IntegratedClass(PAPER, List.of(acm, semantic))),
                List.of(), List.of(), "v1");
    }

    /** As above, but the second source does not supply {@code year} — it must NULL-pad. */
    private static MappingCatalog raggedCatalog() {
        var acm = new MappingCatalog.SourceView("acm", "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_TITLE, "title", P_YEAR, "pub_year"));
        var titlesOnly = new MappingCatalog.SourceView("titles", "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_TITLE, "name"));
        return new MappingCatalog(
                Map.of(PAPER, new MappingCatalog.IntegratedClass(PAPER, List.of(acm, titlesOnly))),
                List.of(), List.of(), "v1");
    }

    private static PlanShape.Node findFirst(PlanShape.Node n, String kind) {
        if (n.kind().equals(kind)) return n;
        for (PlanShape.Node in : n.inputs()) {
            PlanShape.Node hit = findFirst(in, kind);
            if (hit != null) return hit;
        }
        return null;
    }

    @Test
    @DisplayName("a two-source class returns every row from both sources, once each")
    void unionReturnsAllRowsFromBothSources() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path acm = h.writeJson("acm", """
                    {"title":"Alpha","pub_year":2020}
                    {"title":"Beta","pub_year":2021}
                    """);
            Path semantic = h.writeJson("semantic", """
                    {"name":"Gamma","year":2022}
                    """);
            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "acm" -> "read_json_auto('" + acm + "')";
                case "semantic" -> "read_json_auto('" + semantic + "')";
                default -> throw new IllegalArgumentException(ds);
            };

            var outcome = h.run(twoSourceCatalog(), resolver, "SELECT Paper.title");

            assertThat(findFirst(outcome.shape().root(), "UNION"))
                    .as("the union must be in the plan")
                    .isNotNull();
            assertThat(outcome.result().rows())
                    .as("2 rows from acm + 1 from semantic, no dedup")
                    .hasSize(3);
            assertThat(outcome.result().rows().stream().map(r -> String.valueOf(r.get(0))))
                    .containsExactlyInAnyOrder("Alpha", "Beta", "Gamma");
        }
    }

    @Test
    @DisplayName("UNION ALL keeps a genuinely duplicated tuple from two overlapping sources")
    void unionAllDoesNotDeduplicate() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            // The same paper present in both sources must appear twice — set semantics here
            // would silently drop a real row, which is why DuckDbExecutor uses UNION ALL.
            Path acm = h.writeJson("acm", """
                    {"title":"Shared","pub_year":2020}
                    """);
            Path semantic = h.writeJson("semantic", """
                    {"name":"Shared","year":2020}
                    """);
            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "acm" -> "read_json_auto('" + acm + "')";
                case "semantic" -> "read_json_auto('" + semantic + "')";
                default -> throw new IllegalArgumentException(ds);
            };

            var outcome = h.run(twoSourceCatalog(), resolver, "SELECT Paper.title");

            assertThat(outcome.result().rows()).hasSize(2);
        }
    }

    @Test
    @DisplayName("a source missing a property NULL-pads it rather than failing the union")
    void raggedSourceIsNullPadded() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path acm = h.writeJson("acm", """
                    {"title":"Alpha","pub_year":2020}
                    """);
            Path titles = h.writeJson("titles", """
                    {"name":"Beta"}
                    """);
            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "acm" -> "read_json_auto('" + acm + "')";
                case "titles" -> "read_json_auto('" + titles + "')";
                default -> throw new IllegalArgumentException(ds);
            };

            var outcome = h.run(raggedCatalog(), resolver, "SELECT Paper.title, Paper.year");

            assertThat(outcome.result().rows()).hasSize(2);
            // Beta's year comes from the source that does not supply it — must be NULL,
            // not a failure and not a shifted column.
            var beta = outcome.result().rows().stream()
                    .filter(r -> "Beta".equals(String.valueOf(r.get(0))))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Beta row missing from the union"));
            assertThat(beta.get(1)).as("unsupplied property must be NULL-padded").isNull();

            var alpha = outcome.result().rows().stream()
                    .filter(r -> "Alpha".equals(String.valueOf(r.get(0))))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Alpha row missing from the union"));
            assertThat(String.valueOf(alpha.get(1)))
                    .as("the supplying source must keep its value in the same column")
                    .isEqualTo("2020");
        }
    }

    @Test
    @DisplayName("a filter over a two-source class applies to both branches")
    void filterAppliesAcrossTheUnion() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path acm = h.writeJson("acm", """
                    {"title":"Keep","pub_year":2020}
                    {"title":"Drop","pub_year":2020}
                    """);
            Path semantic = h.writeJson("semantic", """
                    {"name":"Keep","year":2022}
                    {"name":"Drop","year":2022}
                    """);
            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "acm" -> "read_json_auto('" + acm + "')";
                case "semantic" -> "read_json_auto('" + semantic + "')";
                default -> throw new IllegalArgumentException(ds);
            };

            var outcome = h.run(twoSourceCatalog(), resolver,
                    "SELECT Paper.title WHERE Paper.title = 'Keep'");

            assertThat(outcome.result().rows())
                    .as("one Keep from each source, both Drops filtered")
                    .hasSize(2);
        }
    }
}

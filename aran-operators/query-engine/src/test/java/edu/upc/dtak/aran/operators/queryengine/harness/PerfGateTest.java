package edu.upc.dtak.aran.operators.queryengine.harness;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What must hold at scale, on any machine.
 *
 * <p>This runs on every build, at ten thousand rows a source, and it asserts <b>nothing about
 * duration</b> except one blow-up ceiling with a hundredfold of headroom. Everything else here is
 * a function of the catalog and the query rather than of the hardware: statement counts, view
 * counts, resolver call counts, and row counts the generator knows exactly because it computed
 * the data from a closed form.
 *
 * <p>That division is the point. A wall-clock threshold on a shared four-core runner fails for
 * reasons that have nothing to do with the code — the failure mode
 * {@code s1-query-stress.spec.ts} names as "the classic source of flaky CI". Numbers belong in
 * {@link PerfBenchmarkTest}'s report, where they are read rather than enforced.
 *
 * <p>Its own wall clock is printed at the end because it has a budget: two seconds on a module
 * suite that runs in seven. If it drifts past that, cut scenarios.
 */
class PerfGateTest {

    private static final String C0 = PerfCatalogs.INT + "C0";

    /** A hundredfold of headroom over the observed cost, in the spirit of {@code AranRulesTest}'s guard. */
    private static final long BLOW_UP_CEILING_MS = 10_000L;

    private static PerfCatalogs.Built plain;
    private static PerfCatalogs.Built wrapped;
    private static long startedAt;

    @BeforeAll
    static void generate() throws Exception {
        startedAt = System.nanoTime();
        plain = PerfCatalogs.star(PerfCatalogs.Knobs.smoke());
        wrapped = PerfCatalogs.star(
                PerfCatalogs.Knobs.smoke().withWrapperShape(PerfCatalogs.WrapperShape.WRAPPED));
    }

    @AfterAll
    static void reportOwnCost() {
        System.out.printf("[perf-gate] class wall clock: %d ms (budget 2000)%n",
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    // ── The standing invariant, at scale ──────────────────────────────────────────────

    @Test
    @DisplayName("one DuckDB statement, whatever the scale and whatever the wrapper shape")
    void oneStatementAtEveryScale() throws Exception {
        for (PerfCatalogs.Built b : List.of(plain, wrapped)) {
            for (String oql : List.of(
                    "SELECT C0.key FROM C0",
                    "SELECT C0.grp, COUNT(*) AS n FROM C0 GROUP BY C0.grp",
                    "SELECT C0.key, C1.grp FROM C0 -[j1]-> C1")) {
                assertThat(PerfRun.execute(b, "invariant", oql, true, false).sql())
                        .as("all-snapshot query must be one statement: %s", oql)
                        .doesNotContain(";");
            }
        }
    }

    // ── Counters that are functions of the catalog, not the machine ───────────────────

    @Test
    @DisplayName("the DDL statement count follows the query, not the catalog")
    void ddlCountIsTheQuerysSizeNotTheCatalogs() throws Exception {
        int sources = PerfCatalogs.Knobs.smoke().sourcesPerClass();

        // PLAIN: per source src_ + w_ + u_, then one int_ for the class.
        assertThat(PerfRun.ddlStatements(plain, Set.of(C0)))
                .as("PLAIN, one class: 3 views per source plus int_")
                .isEqualTo(3 * sources + 1);

        // WRAPPED: the same three, plus the sources-tier alias under both its bare and its
        // schema-qualified name, plus the one CREATE SCHEMA the tier needs.
        assertThat(PerfRun.ddlStatements(wrapped, Set.of(C0)))
                .as("WRAPPED, one class: 5 views per source, int_, and CREATE SCHEMA sources")
                .isEqualTo(5 * sources + 2);

        // An enforced constraint adds the quarantine view, and only one however many there are.
        var constrained = PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke().withConstraints(3));
        assertThat(PerfRun.ddlStatements(constrained, Set.of(C0)))
                .as("three enforced constraints add one q_ view between them, not three")
                .isEqualTo(3 * sources + 2);

        // And the whole catalog costs per class what one class costs — the linearity that makes
        // pruning worth doing rather than an optimisation nobody can feel.
        var wide = PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke().withClasses(50));
        assertThat(PerfRun.ddlStatements(wide, null))
                .as("fifty classes, unpruned")
                .isEqualTo(50 * (3 * sources + 1));
    }

    @Test
    @DisplayName("a single-class query over a fifty-class catalog resolves that class's sources, not fifty")
    void singleClassQueryResolvesOneClassWorthOfSnapshots() throws Exception {
        // The resolver is what downloads a snapshot in production, so its call count is the
        // honest in-process proxy for work that would cross a boundary. Same device, and the
        // same reason, as DdlPruningTest.
        var b = PerfCatalogs.star(PerfCatalogs.Knobs.smoke().withClasses(20).withRows(64L));
        var sample = PerfRun.execute(b, "prune", "SELECT C0.key FROM C0", true, false);

        assertThat(sample.datasetsResolved())
                .as("only C0's two sources, out of forty in the catalog")
                .isEqualTo(PerfCatalogs.Knobs.smoke().sourcesPerClass());
        assertThat(b.askedDatasets()).allSatisfy(ds -> assertThat(ds).startsWith("c0s"));
    }

    @Test
    @DisplayName("the emitted SQL names exactly the views the plan scans")
    void emittedSqlNamesOnlyTheScannedViews() throws Exception {
        var sample = PerfRun.execute(plain, "views", "SELECT C0.key, C1.grp FROM C0 -[j1]-> C1",
                true, false);
        // Two classes, two sources each: the planner scans the four u_ members directly rather
        // than the opaque int_ views, which is what exposes per-source statistics to the cost
        // model in the first place.
        assertThat(sample.viewsNamedBySql()).isEqualTo(4);
        assertThat(sample.nodeCounts()).containsEntry("SCAN", 4);
    }

    // ── Row counts the generator knows exactly ────────────────────────────────────────

    @Test
    @DisplayName("ten thousand rows from each of two sources arrive as twenty thousand")
    void unionReturnsBothSourcesInFull() throws Exception {
        var sample = PerfRun.execute(plain, "union", "SELECT C0.key FROM C0", true, false);
        assertThat(sample.rows()).isEqualTo(plain.expectedUnionRows());
        assertThat(sample.rows()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("a join on a generated foreign key returns one row per fact row, not one per source")
    void manyToOneJoinDoesNotFanOut() throws Exception {
        // Each source has its own key space and every foreign key targets the next class's
        // source 0, so the integrated class's key really is unique across the union. Were it
        // not, this would return the left side times the right side's source count — the
        // failure mode a fixture gets by accident and never notices at three rows.
        var sample = PerfRun.execute(plain, "join", "SELECT C0.key, C1.grp FROM C0 -[j1]-> C1",
                true, false);
        assertThat(sample.rows()).isEqualTo(plain.expectedUnionRows());
    }

    @Test
    @DisplayName("a thousand generated group keys produce exactly a thousand groups")
    void groupByReturnsExactlyTheGeneratedCardinality() throws Exception {
        var sample = PerfRun.execute(plain, "groupby",
                "SELECT C0.grp, COUNT(*) AS n FROM C0 GROUP BY C0.grp", true, false);
        assertThat(sample.rows()).isEqualTo(plain.expectedGroups());
        assertThat(sample.rows()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("TRY_CAST nulls exactly the dirty cells and no others, at ten thousand rows")
    void tryCastDropsExactlyTheDirtyCells() throws Exception {
        // The engine widens `year` to BIGINT from its xsd:integer range and TRY_CASTs into it,
        // so an uncastable cell becomes NULL and fails the predicate instead of failing the
        // query. Every other row's value is its own index, so all of them satisfy `>= 0`:
        // the survivors are exactly the castable rows, per source.
        var sample = PerfRun.execute(plain, "trycast", "SELECT C0.key FROM C0 WHERE C0.year >= 0",
                true, false);
        long expected = plain.expectedCastableRows() * plain.knobs().sourcesPerClass();
        assertThat(sample.rows()).isEqualTo(expected);
        assertThat(sample.rows()).isEqualTo(18_000);
    }

    // ── Differential invariants: same answer down every path ──────────────────────────

    @Test
    @DisplayName("pruning the DDL does not change the answer")
    void prunedAndUnprunedAgreeOnRows() throws Exception {
        String oql = "SELECT C0.grp, COUNT(*) AS n FROM C0 GROUP BY C0.grp";
        var pruned = PerfRun.execute(plain, "pruned", oql, true, false);
        var unpruned = PerfRun.execute(plain, "unpruned", oql, false, false);
        assertThat(pruned.rows()).isEqualTo(unpruned.rows());
        assertThat(pruned.rowHash()).isEqualTo(unpruned.rowHash());
        // …and that pruning is doing something: unpruned resolves both classes' sources.
        assertThat(unpruned.datasetsResolved()).isGreaterThan(pruned.datasetsResolved());
    }

    @Test
    @DisplayName("the filesystem lockdown production applies does not change the answer either")
    void lockedDownAndOpenAgreeOnRows() throws Exception {
        String oql = "SELECT C0.key FROM C0";
        var open = PerfRun.execute(plain, "open", oql, true, false);
        var locked = PerfRun.execute(plain, "locked", oql, true, true);
        assertThat(locked.rows()).isEqualTo(open.rows());
        assertThat(locked.rowHash()).isEqualTo(open.rowHash());
    }

    @Test
    @DisplayName("two threads over the same snapshots return the same rows")
    void concurrentExecutorsAgreeOnRows() throws Exception {
        var samples = PerfRun.concurrently(List.of(plain, plain), "concurrent",
                "SELECT C0.grp, COUNT(*) AS n FROM C0 GROUP BY C0.grp");
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).rowHash()).isEqualTo(samples.get(1).rowHash());
        assertThat(samples).allSatisfy(s -> assertThat(s.rows()).isEqualTo(1_000));
    }

    // ── The decision the cost model exists to make ────────────────────────────────────

    @Test
    @DisplayName("the join moves below the union when a per-source key makes it pay, and not otherwise")
    void transpositionFlipsOnEvidenceNotOnSize() throws Exception {
        String oql = "SELECT Fact.factText, Dim.dimName FROM Fact -[toDim]-> Dim";

        // A unique key per dimension source: below the union each branch is many-to-one, so the
        // join distributes and there is one join per branch.
        var unique = PerfRun.plan(PerfCatalogs.transposeProbe(1_000L, 250L, 2, true), "flip", oql);
        assertThat(unique.nodeCounts()).containsEntry("JOIN", 2).containsEntry("SCAN", 4);

        // The same shape and the same sizes with a non-unique key: nothing to gain, so the join
        // stays above the union and scans it once.
        var repeated = PerfRun.plan(PerfCatalogs.transposeProbe(1_000L, 250L, 2, false), "noflip", oql);
        assertThat(repeated.nodeCounts()).containsEntry("JOIN", 1).containsEntry("SCAN", 3);
    }

    // ── Blow-up tripwires ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("planning a fifty-class catalog stays far inside the blow-up ceiling")
    void wideCatalogPlansWellInsideTheCeiling() throws Exception {
        var wide = PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke().withClasses(50));
        long started = System.nanoTime();
        var sample = PerfRun.plan(wide, "wide", "SELECT C0.key FROM C0");
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis)
                .as("planning a single-class query must not scale with catalog width")
                .isLessThan(BLOW_UP_CEILING_MS);
        // Catalog width must not leak into the plan at all.
        assertThat(sample.nodeCounts()).containsEntry("SCAN", 2);
    }

    @Test
    @DisplayName("planning a two-hop chain stays far inside the blow-up ceiling")
    void boundedChainPlansWellInsideTheCeiling() throws Exception {
        // Bounded at two hops deliberately. Planning cost rises steeply with the product of
        // chain depth and union fan-in (P1), and the
        // gate's job is to catch a regression, not to sit next to the cliff. The depth axis
        // itself is measured, without a threshold, in PerfBenchmarkTest.
        var chain = PerfCatalogs.chainPlanOnly(PerfCatalogs.Knobs.smoke(), 2);
        long started = System.nanoTime();
        var sample = PerfRun.plan(chain, "chain2",
                "SELECT C0.key, C2.grp FROM C0 -[j1]-> C1 -[j2]-> C2");
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).isLessThan(BLOW_UP_CEILING_MS);

        // Pinned because of what these numbers mean, not merely to pin something. The query
        // names three classes of two sources each — six wrapper views — and the plan contains
        // TEN scans and FOUR joins. Distributing each join over each union duplicates the
        // subtree beneath it, so the plan grows multiplicatively in (branches ^ joins) rather
        // than additively. That is the mechanism behind the planning cliff
        // (P1), and this is the cheapest place it is visible.
        // If these numbers move, read the diff before changing them: fewer is the fix landing,
        // more is the cliff getting steeper.
        assertThat(sample.nodeCounts())
                .as("join-over-union distribution duplicates subtrees:%n%s", sample.compactShape())
                .containsEntry("JOIN", 4)
                .containsEntry("SCAN", 10)
                .containsEntry("UNION", 5);
        assertThat(sample.nodeCounts().get("SCAN"))
                .as("ten scans over six distinct source views is duplication, by definition")
                .isGreaterThan(3 * PerfCatalogs.Knobs.smoke().sourcesPerClass());
    }

    @Test
    @DisplayName("the emitted SQL does not grow superlinearly in the number of scans")
    void emittedSqlDoesNotGrowSuperlinearly() throws Exception {
        // A ceiling, not an equality: the exact byte count churns on any Calcite formatting
        // change, which is precisely what PlanShape exists to stop a test depending on. What is
        // worth catching is a plan that duplicates subtrees until the statement explodes.
        List<PerfRun.Sample> samples = new ArrayList<>();
        samples.add(PerfRun.plan(plain, "sql1", "SELECT C0.key FROM C0"));
        samples.add(PerfRun.plan(plain, "sql2", "SELECT C0.key, C1.grp FROM C0 -[j1]-> C1"));
        for (PerfRun.Sample s : samples) {
            int scans = s.nodeCounts().getOrDefault("SCAN", 1);
            assertThat(s.sqlLength())
                    .as("SQL length per scan (%d scans, %d chars):%n%s", scans, s.sqlLength(), s.sql())
                    .isLessThan(512 * scans);
        }
    }
}

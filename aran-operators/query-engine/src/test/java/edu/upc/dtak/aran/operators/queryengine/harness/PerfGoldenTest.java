package edu.upc.dtak.aran.operators.queryengine.harness;

import org.junit.jupiter.api.DisplayName;
import edu.upc.dtak.aran.operators.queryengine.plan.Planning;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan shapes at scale, pinned to committed files.
 *
 * <p>Where {@code PlanCorpusTest} pins the shapes the rule set exists for over the
 * Artworks/IArtist models, this pins what happens when the <em>numbers</em> change: the same
 * query at two scale factors, a catalog fifty classes wide, a chain deep enough to show join
 * order, and — the entry worth the whole file — a pair that must <em>differ</em>, because it is
 * where evidence flips a decision.
 *
 * <p>Regenerate after an intended change with:
 * <pre>./gradlew :aran-operators:query-engine:test --tests '*PerfGoldenTest' -Daran.perf.goldens.update=true</pre>
 * and read the diff — that diff is the review artefact, same as for {@code plan-goldens/}.
 *
 * <p><b>A separate flag from {@code plan-goldens}, deliberately.</b> One flag rewrites whatever
 * corpus it is pointed at, so sharing it would mean that accepting an intended plan change also
 * silently rewrote these — mixing "I meant this" with "and this moved too" in one diff.
 *
 * <p>Every entry is plan-only. Shapes are captured unannotated ({@code PlanShape.of(rel)}, not
 * the access-annotated overload the query API returns) so they stay comparable with
 * {@code plan-goldens/}.
 */
class PerfGoldenTest {

    private static final Path GOLDENS = Path.of("src/test/resources/perf-goldens");
    private static final String UPDATE = "aran.perf.goldens.update";

    private interface Entry {
        PerfRun.Sample sample() throws Exception;
    }

    private static Stream<DynamicTest> corpus() {
        return Stream.of(
                // Same query, same catalog shape, row counts a hundredfold apart. These two
                // files must be IDENTICAL: scale alone must not move a plan.
                test("scale-union-2x10k", () -> unionShape(10_000L)),
                test("scale-union-2x1m",  () -> unionShape(1_000_000L)),

                // The pair that must DIFFER. A unique key per dimension source makes each union
                // branch many-to-one, so distributing the join below the union pays; a repeated
                // key means it does not. Same sizes on both sides — the difference is evidence.
                test("flip-below-union", () -> transposeShape(true)),
                test("flip-above-union", () -> transposeShape(false)),

                // Catalog width must not leak into a single-class plan.
                test("wide-50x3-single-class", () -> PerfRun.plan(
                        PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke().withClasses(50).withSources(3)),
                        "wide", "SELECT C0.key FROM C0")),

                // Join order over a three-hop chain, one source per class: no unions, so this
                // pins ordering alone.
                test("chain-3hop-single-source", () -> PerfRun.plan(
                        PerfCatalogs.chainPlanOnly(PerfCatalogs.Knobs.smoke().withSources(1), 3),
                        "chain3", "SELECT C0.key, C3.grp FROM C0 -[j1]-> C1 -[j2]-> C2 -[j3]-> C3")
                        ),

                // The same chain over two-source classes. Deliberately two hops, not three: the
                // plan grows multiplicatively in (branches ^ joins), so a third hop here costs
                // an order of magnitude more to plan for no extra pinning value (P1).
                test("chain-2hop-union", () -> PerfRun.plan(
                        PerfCatalogs.chainPlanOnly(PerfCatalogs.Knobs.smoke(), 2),
                        "chain2", "SELECT C0.key, C2.grp FROM C0 -[j1]-> C1 -[j2]-> C2")
                        ),

                // An OR of AND-groups — the widest predicate OQL's grammar can express, since it
                // has no parentheses — and where each conjunct landed.
                test("predicate-dnf", () -> PerfRun.plan(
                        PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke()), "dnf",
                        "SELECT C0.key FROM C0 WHERE C0.grp = 'g1' AND C0.amount > 10 "
                                + "OR C0.grp = 'g2' AND C0.amount > 20")),

                test("groupby-having-1k", () -> PerfRun.plan(
                        PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke()), "having",
                        "SELECT C0.grp, COUNT(*) AS n FROM C0 GROUP BY C0.grp HAVING COUNT(*) > 1")
                        ),

                // An enforced constraint the query redundantly repeats: the planner knows the
                // scan already satisfies it, so the predicate should be weakened away rather
                // than evaluated twice.
                test("dc-weakened", () -> PerfRun.plan(
                        PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke().withConstraints(1)),
                        "dcWeak", "SELECT C0.key FROM C0 WHERE C0.amount >= -1")),

                // …and one it contradicts: provably empty, answerable without a scan.
                test("dc-contradiction", () -> PerfRun.plan(
                        PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke().withConstraints(1)),
                        "dcBad", "SELECT C0.key FROM C0 WHERE C0.amount < -1")));
    }

    private static PerfRun.Sample unionShape(long rows) throws Exception {
        return PerfRun.plan(
                PerfCatalogs.starPlanOnly(PerfCatalogs.Knobs.smoke().withRows(rows)),
                "union", "SELECT C0.key FROM C0 WHERE C0.grp = 'g1'");
    }

    private static PerfRun.Sample transposeShape(boolean dimKeyIsUnique) throws Exception {
        return PerfRun.plan(
                PerfCatalogs.transposeProbe(1_000L, 250L, 2, dimKeyIsUnique), "flip",
                "SELECT Fact.factText, Dim.dimName FROM Fact -[toDim]-> Dim");
    }

    private static DynamicTest test(String name, Entry entry) {
        return DynamicTest.dynamicTest(name, () -> {
            PerfRun.Sample sample = entry.sample();
            // D3 tripwire: a golden pins a CHOSEN plan. If the budget cut planning short the
            // shape is an accident of where it stopped, and pinning it would freeze that.
            assertThat(sample.planning().degradation())
                    .as("%s planned within the budget (%d productions)", name,
                            sample.planning().productions())
                    .isEqualTo(Planning.Degradation.NONE);
            Goldens.assertOrUpdate(GOLDENS, name, sample.compactShape(), UPDATE);
        });
    }

    @TestFactory
    Stream<DynamicTest> planShapesAtScaleMatchGoldens() {
        return corpus();
    }

    // ── The two relationships the corpus exists to hold ───────────────────────────────
    //
    // Asserted here rather than left to a reader's diff. A golden pair whose whole point is
    // "these must match" or "these must differ" is only doing its job if something checks it:
    // otherwise both files drift together and the corpus keeps passing while saying nothing.

    @Test
    @DisplayName("a hundredfold more rows does not move the plan")
    void scaleAloneDoesNotMoveTheShape() throws Exception {
        assertThat(unionShape(1_000_000L).compactShape()).isEqualTo(unionShape(10_000L).compactShape());
    }

    @Test
    @DisplayName("a per-source key does move it — the decision the cost model exists to make")
    void evidenceDoesMoveTheShape() throws Exception {
        // Identical sizes on both sides, so the only input that changed is whether each
        // dimension source's key is unique. If these ever match, the flip has stopped
        // happening and the transposition rule is dead weight — fix that, not this test.
        assertThat(transposeShape(true).compactShape()).isNotEqualTo(transposeShape(false).compactShape());
    }
}

package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.ReferentialEdge;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four differentials that prove {@link edu.upc.dtak.aran.operators.queryengine.plan.AranRules}'
 * trust-tier-gated join elimination — Phase 4B's payoff and its correctness spine.
 *
 * <p>Every test uses one query shape, {@code SELECT Artworks.title FROM Artworks -[hasArtist]->
 * IArtist} (IArtist projection-unused), over one two-class model, and flips exactly one fact:
 *
 * <ol>
 *   <li>(a) a DECLARED FK + IArtist.id exact-verified key → the join is eliminated (IArtist's scan
 *       is dropped) and the executed rows are identical to the non-eliminated plan;</li>
 *   <li>(b) the SAME shape but the edge is only HYPOTHESIS (an accepted join, no declared FK) → no
 *       elimination — the trust-tier proof;</li>
 *   <li>(c) DECLARED, but an IArtist column IS projected → no elimination;</li>
 *   <li>(d) DECLARED, but IArtist.id is not an exact key (only a HARVESTED/pg_stats-approximate one)
 *       → no elimination — the exact-key gate and the Task-3 HARVESTED exclusion, end to end.</li>
 * </ol>
 */
class AranJoinRemoveRuleTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";
    private static final String ARTWORKS = NS + "integration/Artworks";
    private static final String IARTIST = NS + "integration/IArtist";
    private static final String P_TITLE = NS + "integration/title";
    private static final String P_NAME = NS + "integration/name";
    private static final String P_AID = SRC + "art/artist_id";
    private static final String P_ID = SRC + "artist/id";
    private static final String HAS_ARTIST = NS + "integration/hasArtist";

    private static final String PROJECT_A_ONLY =
            "SELECT Artworks.title FROM Artworks -[hasArtist]-> IArtist";
    private static final String PROJECT_B_TOO =
            "SELECT Artworks.title, IArtist.name AS artist FROM Artworks -[hasArtist]-> IArtist";

    // ── Knobs ──────────────────────────────────────────────────────────────────

    private enum KeyKind {
        /** IArtist.id is an exact freyja unique key (SNAPSHOT, uniqueness 1.0). */
        EXACT,
        /** IArtist.id looks unique but only via pg_stats (HARVESTED) — never a verified key. */
        HARVESTED_APPROX,
        /** IArtist.id is not profiled at all. */
        NONE
    }

    /**
     * Artworks(art) 1000 rows, referencing IArtist(artist) 100 rows via hasArtist
     * (artist_id → id). The HYPOTHESIS edge always exists (the accepted join the OQL needs to
     * plan); {@code declared} adds the same edge again at {@code DECLARED} tier.
     */
    private static MappingCatalog catalog(boolean declared, KeyKind key) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), 1000L)));
        List<ColumnProfile> artistProfiles = switch (key) {
            case EXACT -> List.of(profile("id", 1.0, "SNAPSHOT"));
            case HARVESTED_APPROX -> List.of(profile("id", 1.0, HarvestedColumnProfiles.SUBJECT));
            case NONE -> List.of();
        };
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("artist", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_ID, "id", P_NAME, "name"), 100L, artistProfiles)));
        var hasArtist = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        List<ReferentialEdge> declaredEdges = declared
                ? List.of(new ReferentialEdge(ARTWORKS, List.of(P_AID), IARTIST, List.of(P_ID), Tier.DECLARED))
                : List.of();
        return new MappingCatalog(
                Map.of(ARTWORKS, artworks, IARTIST, iartist),
                List.of(hasArtist), List.of(), declaredEdges, "v1");
    }

    private static ColumnProfile profile(String column, double uniqueness, String subject) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                100L, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                subject, null);
    }

    private static boolean eliminated(PlanShapeText shape) {
        return !shape.text.contains("JOIN[") && !shape.text.contains("u_artist_IArtist");
    }

    /** Small wrapper so the assertion messages carry the whole shape, not a boolean. */
    private record PlanShapeText(String text) {}

    private static PlanShapeText shapeOf(MappingCatalog catalog, String oql) throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            return new PlanShapeText(h.plan(catalog, oql).shape().toCompactString());
        }
    }

    // ── (a) DECLARED FK + exact key + B unprojected → eliminated ─────────────────

    @Test
    @DisplayName("(a) a declared FK with an exact key and an unprojected referenced side eliminates the join")
    void declaredFkExactKeyUnprojectedEliminatesTheJoin() throws Exception {
        PlanShapeText shape = shapeOf(catalog(true, KeyKind.EXACT), PROJECT_A_ONLY);
        assertThat(eliminated(shape))
                .as("the join and IArtist's scan must be gone:%n%s", shape.text)
                .isTrue();
        // The rewrite is σ(fk IS NOT NULL)(Artworks), not a bare scan — the NULL-rejecting filter
        // is what preserves the inner join's row-dropping semantics.
        assertThat(shape.text).contains("FILTER[").contains("u_art_Artworks");
    }

    @Test
    @DisplayName("(a) the eliminated plan returns exactly the rows the non-eliminated plan does")
    void declaredEliminationIsRowIdenticalToTheJoin() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            // "Orphan" has a NULL artist_id: the inner join drops it, so the eliminated plan's
            // IS NOT NULL filter must drop it too — otherwise the rows would diverge.
            Path art = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"},"
                    + " {\"title\":\"Convergence\",\"artist_id\":\"a2\"},"
                    + " {\"title\":\"Orphan\",\"artist_id\":null}]");
            Path artist = h.writeJson("artist",
                    "[{\"id\":\"a1\",\"name\":\"Picasso\"}, {\"id\":\"a2\",\"name\":\"Pollock\"}]");
            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "art" -> "read_json_auto('" + art.toAbsolutePath() + "')";
                case "artist" -> "read_json_auto('" + artist.toAbsolutePath() + "')";
                default -> throw new IllegalArgumentException("Unknown dataset: " + ds);
            };

            QueryHarness.Outcome declared = h.run(catalog(true, KeyKind.EXACT), resolver, PROJECT_A_ONLY);
            QueryHarness.Outcome hypothesis = h.run(catalog(false, KeyKind.EXACT), resolver, PROJECT_A_ONLY);

            // The differential only proves something if the two plans genuinely differ in shape.
            assertThat(eliminated(new PlanShapeText(declared.shape().toCompactString())))
                    .as("DECLARED plan eliminated:%n%s", declared.optimizedPlan()).isTrue();
            assertThat(eliminated(new PlanShapeText(hypothesis.shape().toCompactString())))
                    .as("HYPOTHESIS plan kept the join:%n%s", hypothesis.optimizedPlan()).isFalse();
            assertThat(declared.sql()).doesNotContain("u_artist_IArtist");
            assertThat(hypothesis.sql()).contains("u_artist_IArtist");

            // Identical rows — and the right ones: Orphan (NULL fk) is dropped by both.
            assertThat(declared.result().rows())
                    .containsExactlyInAnyOrderElementsOf(hypothesis.result().rows());
            assertThat(declared.result().rows())
                    .containsExactlyInAnyOrder(List.<Object>of("Guernica"), List.<Object>of("Convergence"));
        }
    }

    // ── (b) same shape, HYPOTHESIS edge → never eliminated (the spine) ────────────

    @Test
    @DisplayName("(b) a HYPOTHESIS edge (accepted join, no declared FK) is never eliminated")
    void hypothesisEdgeIsNeverEliminated() throws Exception {
        PlanShapeText hypothesis = shapeOf(catalog(false, KeyKind.EXACT), PROJECT_A_ONLY);
        assertThat(eliminated(hypothesis))
                .as("an accepted join is a guess and must keep its join:%n%s", hypothesis.text)
                .isFalse();
        // Byte-identical to the plan a catalog with no referential machinery at all produces: the
        // hypothesis edge changed nothing. (Same query, key present either way.)
        assertThat(hypothesis.text).contains("JOIN[inner").contains("u_art_Artworks").contains("u_artist_IArtist");
    }

    @Test
    @DisplayName("(b) the ONLY difference between eliminated and not is the tier — same catalog otherwise")
    void tierIsTheOnlyThingThatDecidesElimination() throws Exception {
        // catalog(true,…) and catalog(false,…) are identical but for the DECLARED edge: same
        // classes, same profiles, same accepted join. So a divergence in the plan can only be the
        // tier gate — this is the structural heart of the "never on a hypothesis" guarantee.
        PlanShapeText declared = shapeOf(catalog(true, KeyKind.EXACT), PROJECT_A_ONLY);
        PlanShapeText hypothesis = shapeOf(catalog(false, KeyKind.EXACT), PROJECT_A_ONLY);
        assertThat(eliminated(declared)).isTrue();
        assertThat(eliminated(hypothesis)).isFalse();
    }

    // ── (c) DECLARED but the referenced side is projected → not eliminated ────────

    @Test
    @DisplayName("(c) a declared FK does not eliminate the join when a referenced-side column is projected")
    void declaredButReferencedColumnProjectedKeepsTheJoin() throws Exception {
        PlanShapeText shape = shapeOf(catalog(true, KeyKind.EXACT), PROJECT_B_TOO);
        assertThat(eliminated(shape))
                .as("IArtist.name is selected, so IArtist cannot be dropped:%n%s", shape.text)
                .isFalse();
        assertThat(shape.text).contains("JOIN[inner").contains("u_artist_IArtist");
    }

    // ── (d) DECLARED but not an exact key → not eliminated ────────────────────────

    @Test
    @DisplayName("(d) a declared FK does not eliminate the join when the referenced side is only a HARVESTED-approximate key")
    void declaredButHarvestedApproxKeyKeepsTheJoin() throws Exception {
        PlanShapeText shape = shapeOf(catalog(true, KeyKind.HARVESTED_APPROX), PROJECT_A_ONLY);
        assertThat(eliminated(shape))
                .as("a pg_stats-approximate key is not exact, so the join must stay:%n%s", shape.text)
                .isFalse();
        assertThat(shape.text).contains("JOIN[inner").contains("u_artist_IArtist");
    }

    @Test
    @DisplayName("(d) a declared FK does not eliminate the join when the referenced side has no key at all")
    void declaredButNoKeyKeepsTheJoin() throws Exception {
        PlanShapeText shape = shapeOf(catalog(true, KeyKind.NONE), PROJECT_A_ONLY);
        assertThat(eliminated(shape))
                .as("no verified key on IArtist.id, so the join must stay:%n%s", shape.text)
                .isFalse();
        assertThat(shape.text).contains("JOIN[inner").contains("u_artist_IArtist");
    }
}

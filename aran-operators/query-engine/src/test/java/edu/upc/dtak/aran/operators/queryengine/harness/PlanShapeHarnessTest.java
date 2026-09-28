package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlanShapeHarnessTest {

    private static final String NS  = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";
    private static final String ARTWORKS   = NS + "integration/Artworks";
    private static final String IARTIST    = NS + "integration/IArtist";
    private static final String P_TITLE    = NS + "integration/title";
    private static final String P_NAME     = NS + "integration/name";
    private static final String P_AID      = SRC + "art/artist_id";
    private static final String P_ID       = SRC + "artist/id";
    private static final String HAS_ARTIST = NS + "integration/hasArtist";

    private static final String OQL =
            "SELECT Artworks.title, IArtist.name AS artist FROM Artworks -[hasArtist]-> IArtist";

    // ── Join-below-union fixture: mirrors JoinUnionTransposeTest's catalog shape ──────────────
    private static final String JBU_ORDERS     = NS + "integration/Orders";
    private static final String JBU_CUSTOMER   = NS + "integration/Customer";
    private static final String JBU_O_REF      = SRC + "orders/customerRef";
    private static final String JBU_O_AMT      = NS + "integration/amount";
    private static final String JBU_C_ID       = SRC + "customers/id";
    private static final String JBU_C_NAME     = NS + "integration/custName";
    private static final String JBU_PLACED_BY  = NS + "integration/placedBy";
    private static final String JBU_OQL =
            "SELECT Orders.amount, Customer.custName FROM Orders -[placedBy]-> Customer";

    private MappingCatalog catalog(long artworksRows, long iartistRows) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT 1",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), artworksRows)));
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("artist", "SELECT 1",
                        Map.of(P_ID, "id", P_NAME, "name"), iartistRows)));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        return new MappingCatalog(Map.of(ARTWORKS, artworks, IARTIST, iartist),
                List.of(join), List.of(), "v1");
    }

    /** 0 if {@code tableA} is in the first (build) input subtree of the first JOIN, else 1. */
    private static int buildSidePos(PlanShape shape, String tableA, String tableB) {
        PlanShape.Node join = findFirst(shape.root(), "JOIN");
        assertThat(join).as("plan contains a JOIN").isNotNull();
        String left = new PlanShape(join.inputs().get(0)).toCompactString();
        assertThat(left.contains(tableA) || left.contains(tableB))
                .as("one of the two tables is on the build side").isTrue();
        return left.contains(tableA) ? 0 : 1;
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
    void smallerClassBecomesBuildSideAndFlipsWithStats() {
        try (QueryHarness h = new QueryHarness()) {
            // Artworks small, IArtist large -> Artworks (smaller) is the left/build input.
            PlanShape shapeAwSmall = h.plan(catalog(100L, 10_000L), OQL).shape();
            assertThat(buildSidePos(shapeAwSmall, "u_art_Artworks", "u_artist_IArtist"))
                    .as("Artworks (smaller) should be the left/build input")
                    .isEqualTo(0);

            // Flip the stats -> IArtist now smaller -> becomes the left/build input.
            PlanShape shapeIaSmall = h.plan(catalog(10_000L, 100L), OQL).shape();
            assertThat(buildSidePos(shapeIaSmall, "u_art_Artworks", "u_artist_IArtist"))
                    .as("IArtist (now smaller) should be the left/build input")
                    .isEqualTo(1);
        }
    }

    /**
     * Same two classes, both fixed at equal row counts (10,000 each — a tie for the build-side
     * row-count cost term), but with each side's join key carrying an explicit NDV, below the
     * unique-key threshold so {@code AranStatistic#isKey} never fires and the plan can only be
     * discriminating on NDV evidence.
     */
    private MappingCatalog catalogWithJoinKeyNdv(long artworksKeyNdv, long iartistKeyNdv) {
        long rows = 10_000L;
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT 1",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), rows,
                        List.of(ndvProfile("artist_id", artworksKeyNdv, rows)))));
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("artist", "SELECT 1",
                        Map.of(P_ID, "id", P_NAME, "name"), rows,
                        List.of(ndvProfile("id", iartistKeyNdv, rows)))));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        return new MappingCatalog(Map.of(ARTWORKS, artworks, IARTIST, iartist),
                List.of(join), List.of(), "v1");
    }

    /** A {@code ColumnProfile} below the 0.9999 key-uniqueness threshold, carrying an explicit NDV. */
    private static edu.upc.dtak.aran.core.domain.ColumnProfile ndvProfile(String column, long ndv, long rows) {
        return new edu.upc.dtak.aran.core.domain.ColumnProfile(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), column, 0, "VARCHAR",
                ndv, null, (double) ndv / rows, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0, 0L, 0, 0, 0.0, 0.0, null, null, false, false,
                java.util.List.of(), java.util.List.of());
    }

    @Test
    @DisplayName("join-key NDV evidence tightens the optimized plan's own join-cardinality estimate")
    void ndvEvidenceLowersTheOptimizedJoinCardinalityEstimate() {
        try (QueryHarness h = new QueryHarness()) {
            // No profiles at all (catalog() attaches none) -> stock 0.15 selectivity default.
            double withoutNdv = joinRowCount(h.plan(catalog(10_000L, 10_000L), OQL).optimizedPlan());
            // Join-key NDVs 100 (Artworks.artist_id) and 1000 (IArtist.id).
            double withNdv = joinRowCount(h.plan(catalogWithJoinKeyNdv(100L, 1000L), OQL).optimizedPlan());

            assertThat(withoutNdv)
                    .as("10000*10000*0.15")
                    .isCloseTo(10_000.0 * 10_000.0 * 0.15, within(1.0));
            assertThat(withNdv)
                    .as("10000*10000/max(100,1000)")
                    .isCloseTo(100_000.0, within(1.0));
            assertThat(withNdv)
                    .as("NDV evidence must tighten the plan's own displayed join-cardinality estimate")
                    .isLessThan(withoutNdv);
        }
    }

    /**
     * Documents a real, non-obvious property discovered writing this suite: unlike the
     * row-count-driven flip in {@link #smallerClassBecomesBuildSideAndFlipsWithStats}, join-key
     * NDV alone cannot flip which side becomes the build input, even though it does change the
     * join's own cardinality estimate (see the test above).
     *
     * <p>Decompiling {@code EnumerableHashJoin.computeSelfCost} (calcite-core 1.41.0) shows why:
     * cost = {@code rowCount(this) [+ a tie-break epsilon keyed on field count, then RelNode
     * creation id] + nLogN(leftRowCount) + rightRowCount}. Only the last two terms — built from
     * each CHILD's own row count, never from the join's row count — are asymmetric between a
     * join and its {@code JoinCommuteRule} swap, and they tie exactly when the two children have
     * equal row counts as they do here. {@code rowCount(this)} — the value {@link
     * AranRelMdRowCount} controls, unique-key branches included — is provably symmetric under
     * left/right commute for any formula that is a legitimate estimate of the join's own output
     * (the estimate cannot depend on which operand a planner happens to call "left"), so it can
     * never be what breaks a build-side tie. This is not a gap introduced by the NDV branch; the
     * pre-existing unique-key branches have the same property, which is why no build-side-flip
     * test existed for keys either — {@link #declaredKeyLowersJoinCardinalityEstimate} instead
     * asserts the cardinality number directly, exactly as {@link
     * #ndvEvidenceLowersTheOptimizedJoinCardinalityEstimate} now does for NDV.
     */
    @Test
    @DisplayName("at tied row counts, build-side selection ignores join-key NDV (documents why)")
    void buildSideIgnoresJoinKeyNdvWhenRowCountsAreEqual() {
        try (QueryHarness h = new QueryHarness()) {
            int posLowNdvOnArtworks = buildSidePos(
                    h.plan(catalogWithJoinKeyNdv(100L, 1000L), OQL).shape(),
                    "u_art_Artworks", "u_artist_IArtist");
            int posLowNdvOnIArtist = buildSidePos(
                    h.plan(catalogWithJoinKeyNdv(1000L, 100L), OQL).shape(),
                    "u_art_Artworks", "u_artist_IArtist");

            assertThat(posLowNdvOnArtworks)
                    .as("build side must be the same regardless of which side has the lower NDV,"
                            + " because the two children's row counts (not their key NDVs) are"
                            + " what EnumerableHashJoin's cost model treats asymmetrically")
                    .isEqualTo(posLowNdvOnIArtist);
        }
    }

    /**
     * Mirrors {@code JoinUnionTransposeTest}'s catalog: Orders is one source with no verified
     * key on its FK; Customer is integrated from two sources that each declare a genuinely
     * unique {@code id} (25 rows, NDV 25 -> uniqueness 25/25 = 1.0). {@code orderRefNdv} is
     * {@code null} for no {@code ColumnProfile} at all on the join key (no evidence of any kind
     * — not even a low-uniqueness one, per this task's own fixture fix elsewhere), or an
     * explicit NDV to attach one and see whether it changes which plan shape wins.
     */
    private MappingCatalog joinBelowUnionCatalog(Long orderRefNdv) {
        long orderRows = 1000L;
        List<edu.upc.dtak.aran.core.domain.ColumnProfile> orderProfiles = orderRefNdv == null
                ? List.of()
                : List.of(ndvProfile("customer_ref", orderRefNdv, orderRows));
        var orders = new MappingCatalog.IntegratedClass(JBU_ORDERS, List.of(
                new MappingCatalog.SourceView("ord", "SELECT 1",
                        Map.of(JBU_O_REF, "customer_ref", JBU_O_AMT, "amount"),
                        orderRows, orderProfiles)));
        var customer = new MappingCatalog.IntegratedClass(JBU_CUSTOMER, List.of(
                new MappingCatalog.SourceView("cust_eu", "SELECT 1",
                        Map.of(JBU_C_ID, "id", JBU_C_NAME, "name"), 25L,
                        List.of(ndvProfile("id", 25L, 25L))),
                new MappingCatalog.SourceView("cust_us", "SELECT 1",
                        Map.of(JBU_C_ID, "id", JBU_C_NAME, "name"), 25L,
                        List.of(ndvProfile("id", 25L, 25L)))));
        var join = new MappingCatalog.JoinProperty(
                JBU_PLACED_BY, JBU_ORDERS, JBU_CUSTOMER, JBU_O_REF, JBU_C_ID);
        return new MappingCatalog(Map.of(JBU_ORDERS, orders, JBU_CUSTOMER, customer),
                List.of(join), List.of(), "v1");
    }

    /**
     * The plan-shape consequence the NDV estimate exists to have — found as a real regression
     * while fixing {@code JoinUnionTransposeTest}'s own fixtures for this task (see the task
     * report): whether {@code JoinUnionTransposeRule}'s transposed shape (join pushed below the
     * union, onto branches where the PK-FK many-to-one estimate can fire) wins on cost, or loses
     * to the untransposed shape, flips on join-key NDV evidence alone — same keys, same row
     * counts, only the presence of one {@code ColumnProfile} differs.
     *
     * <p><b>No evidence</b> on {@code Orders.customer_ref}: the untransposed join has no
     * unique key and no NDV evidence on either side, so it gets the flat-selectivity fallback,
     * {@code 1000 * 50 * 0.15 = 7500}. Transposed, each branch's {@code Customer.id} is a
     * verified key, so each branch join gets the PK-FK estimate ({@code leftRows = 1000}); two
     * branches union to {@code 2000}. {@code 2000 < 7500}, so the planner transposes.
     *
     * <p><b>NDV 100</b> on {@code Orders.customer_ref} (uniqueness {@code 100/1000 = 0.1}, still
     * far below the 0.9999 key threshold, so the unique-key branches still don't fire): the
     * untransposed join now has NDV evidence on both sides (Orders' own profile; Customer's
     * side derived by Calcite's default {@code Union} handler from each branch's capped NDV), so
     * {@link AranRelMdRowCount}'s NDV branch fires instead of the fallback —
     * {@code 1000 * 50 / max(100, ~25..50) = 500}. {@code 500 < 2000}, so the SAME rule choice
     * now goes the other way, even though no key and no row count changed.
     */
    @Test
    @DisplayName("join-key NDV evidence alone flips whether the join transposes below the union")
    void ndvEvidenceFlipsTheJoinBelowUnionDecision() {
        try (QueryHarness h = new QueryHarness()) {
            // (a) No evidence at all -> transposed: the union sits above the per-branch joins.
            PlanShape noEvidence = h.plan(joinBelowUnionCatalog(null), JBU_OQL).shape();
            String compact1 = noEvidence.toCompactString();
            PlanShape.Node union1 = findFirst(noEvidence.root(), "UNION");
            PlanShape.Node join1 = findFirst(noEvidence.root(), "JOIN");
            assertThat(union1).as("a two-source class must still produce a Union:\n%s", compact1)
                    .isNotNull();
            assertThat(join1).as("the query joins two classes:\n%s", compact1).isNotNull();
            assertThat(findFirst(union1, "JOIN"))
                    .as("no NDV evidence -> transposed: the union must sit above the joins:\n%s",
                            compact1)
                    .isNotNull();
            assertThat(findFirst(join1, "UNION"))
                    .as("transposed means the reverse nesting must NOT also hold:\n%s", compact1)
                    .isNull();

            // (b) NDV 100 on the same FK column -> untransposed: the join now sits above the
            // union instead. Same keys, same row counts — only the NDV evidence changed.
            PlanShape withEvidence = h.plan(joinBelowUnionCatalog(100L), JBU_OQL).shape();
            String compact2 = withEvidence.toCompactString();
            PlanShape.Node union2 = findFirst(withEvidence.root(), "UNION");
            PlanShape.Node join2 = findFirst(withEvidence.root(), "JOIN");
            assertThat(union2).as("a two-source class must still produce a Union:\n%s", compact2)
                    .isNotNull();
            assertThat(join2).as("the query still joins two classes:\n%s", compact2).isNotNull();
            assertThat(findFirst(join2, "UNION"))
                    .as("NDV evidence -> untransposed: the join must sit above the union instead:\n%s",
                            compact2)
                    .isNotNull();
            assertThat(findFirst(union2, "JOIN"))
                    .as("untransposed means the reverse nesting must NOT also hold:\n%s", compact2)
                    .isNull();
        }
    }

    /** Extracts the rowcount printed on the first LogicalJoin line of a plan string. */
    private static double joinRowCount(String plan) {
        for (String line : plan.split("\\R")) {
            if (line.contains("LogicalJoin")) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("rowcount = ([0-9.E]+)").matcher(line);
                if (m.find()) return Double.parseDouble(m.group(1));
            }
        }
        throw new AssertionError("no LogicalJoin rowcount in plan:\n" + plan);
    }

    private MappingCatalog catalogWithKey(boolean iartistIdIsKey) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT 1",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), 10_000L)));

        java.util.List<edu.upc.dtak.aran.core.domain.ColumnProfile> profiles =
                iartistIdIsKey
                    ? java.util.List.of(new edu.upc.dtak.aran.core.domain.ColumnProfile(
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "id", 0, "VARCHAR",
                        100L, null, 1.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                        0, 0, 0.0, 0L, 0, 0, 0.0, 0.0, null, null, false, false,
                        java.util.List.of(), java.util.List.of()))
                    : java.util.List.of();

        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("artist", "SELECT 1",
                        Map.of(P_ID, "id", P_NAME, "name"), 100L, profiles)));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        return new MappingCatalog(Map.of(ARTWORKS, artworks, IARTIST, iartist),
                List.of(join), List.of(), "v1");
    }

    @Test
    void declaredKeyLowersJoinCardinalityEstimate() {
        try (QueryHarness h = new QueryHarness()) {
            double withoutKey = joinRowCount(h.plan(catalogWithKey(false), OQL).optimizedPlan());
            double withKey    = joinRowCount(h.plan(catalogWithKey(true),  OQL).optimizedPlan());
            assertThat(withKey)
                    .as("a declared key on the join column should reduce the estimated join cardinality")
                    .isLessThan(withoutKey);
        }
    }

    // ── Phase 3A: the OPAQUE-vs-EXPANDED differential, executed ─────────────────────────────

    private static final String XSD      = "http://www.w3.org/2001/XMLSchema#";
    private static final String EXP_YEAR = NS + "integration/year";
    private static final String EXP_S_TITLE = SRC + "art/title";
    private static final String EXP_S_AID   = SRC + "art/artist_id";
    private static final String EXP_S_YEAR  = SRC + "art/year";

    /**
     * The same Artworks source twice over, differing only in {@code expansionStatus} — so a row
     * that comes out differently can only be the expansion. The wrapper is the shape
     * {@code WrapperSqlBuilder.buildFlat} generates: each raw column aliased to its
     * source-property IRI.
     */
    private static MappingCatalog expansionCatalog(String status) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView(
                        "art",
                        "SELECT \"title\" AS \"" + EXP_S_TITLE + "\","
                                + " \"artist_id\" AS \"" + EXP_S_AID + "\","
                                + " \"year\" AS \"" + EXP_S_YEAR + "\" FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, EXP_S_TITLE, P_AID, EXP_S_AID, EXP_YEAR, EXP_S_YEAR),
                        3L, List.of(), "",
                        Map.of(EXP_YEAR, XSD + "integer"),
                        Map.of("title", EXP_S_TITLE, "artist_id", EXP_S_AID, "year", EXP_S_YEAR),
                        status)));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /**
     * &sect;2 of the phase-3A spec, executed: the same query over the same bytes must return the
     * same rows whether the wrapper is scanned as an opaque {@code u_} view or planned through its
     * raw {@code src_} relation. Two independent SQL texts — the second one Calcite's own unparse
     * of the wrapper — over one DuckDB, so a divergence is a real answer changing, not a plan
     * rendering.
     */
    @Test
    @DisplayName("EXPANDED and OPAQUE return identical rows, and only EXPANDED reads src_")
    void expandedAndOpaqueMembersAgreeOnRowsAndDifferOnlyInTheSqlTheyEmit() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            java.nio.file.Path artFile = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\",\"year\":1937},"
                            + " {\"title\":\"Convergence\",\"artist_id\":\"a2\",\"year\":1952},"
                            + " {\"title\":\"Impression\",\"artist_id\":\"a3\",\"year\":1872}]");
            edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor.SnapshotResolver resolver =
                    ds -> "read_json_auto('" + artFile.toAbsolutePath() + "')";

            // A WHERE over the typed column: it exercises TRY_CAST on both sides and gives the
            // optimizer a predicate to push into the expanded subtree.
            String oql = "SELECT Artworks.title, Artworks.year FROM Artworks "
                    + "WHERE Artworks.year > 1900";

            QueryHarness.Outcome opaque   = h.run(expansionCatalog("OPAQUE"),   resolver, oql);
            QueryHarness.Outcome expanded = h.run(expansionCatalog("EXPANDED"), resolver, oql);

            assertThat(expanded.sql())
                    .as("an expanded member's SQL is Calcite's unparse over the raw snapshot view")
                    .contains("src_art");
            assertThat(expanded.sql()).doesNotContain("u_art_Artworks");
            assertThat(expanded.sql())
                    .as("the class types year as xsd:integer; the wrapper hands it over untyped")
                    .contains("TRY_CAST(");
            assertThat(opaque.sql())
                    .as("the opaque half must still take the path it always did")
                    .contains("u_art_Artworks");
            assertThat(opaque.sql()).doesNotContain("src_art");

            assertThat(expanded.result().rows())
                    .containsExactlyInAnyOrderElementsOf(opaque.result().rows());
            assertThat(expanded.result().rows())
                    .as("and both are the right answer, not the same wrong one")
                    .containsExactlyInAnyOrder(
                            List.<Object>of("Guernica", 1937L),
                            List.<Object>of("Convergence", 1952L));
            assertThat(expanded.result().headers()).isEqualTo(opaque.result().headers());
        }
    }

    /**
     * The denial-constraint safety property, executed.
     *
     * <p>{@code buildDdl} enforces a class's denial constraints <em>inside each {@code u_} member's
     * body</em> — selection distributes over union, which is what lets the members be filtered
     * individually. An expansion replaces that scan with the wrapper's own plan over {@code src_},
     * and nothing in the RelNode tree carries the constraint, so every quarantined row would
     * surface: the query would silently return rows the OPAQUE path removes. A member of a
     * constrained class therefore stays opaque.
     */
    @Test
    @DisplayName("a class with an enforced denial constraint keeps its members opaque — an expanded"
            + " member would read past the filter buildDdl puts in the u_ view body")
    void anEnforcedDenialConstraintKeepsMembersOpaque() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            java.nio.file.Path artFile = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\",\"year\":1937},"
                            + " {\"title\":\"Convergence\",\"artist_id\":\"a2\",\"year\":1952},"
                            + " {\"title\":\"Impression\",\"artist_id\":\"a3\",\"year\":1872}]");
            edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor.SnapshotResolver resolver =
                    ds -> "read_json_auto('" + artFile.toAbsolutePath() + "')";

            Map<String, List<String>> enforced = Map.of(ARTWORKS, List.of("year > 1900"));
            String oql = "SELECT Artworks.title, Artworks.year FROM Artworks";

            QueryHarness.Outcome opaque =
                    h.run(expansionCatalog("OPAQUE"), resolver, enforced, oql);
            QueryHarness.Outcome expanded =
                    h.run(expansionCatalog("EXPANDED"), resolver, enforced, oql);

            assertThat(expanded.result().rows())
                    .as("the constraint must hold whatever the expansion status says")
                    .containsExactlyInAnyOrderElementsOf(opaque.result().rows());
            assertThat(expanded.result().rows())
                    .as("Impression (1872) is quarantined by year > 1900")
                    .containsExactlyInAnyOrder(
                            List.<Object>of("Guernica", 1937L),
                            List.<Object>of("Convergence", 1952L));
            assertThat(expanded.sql())
                    .as("the only way to keep the constraint today is to keep the u_ scan")
                    .contains("u_art_Artworks")
                    .doesNotContain("src_art");
        }
    }

    /**
     * A single unaligned source: {@code columnMap} is the identity, so the class's global column
     * IRI and the source-property IRI are the same string and the recorded {@code xsd:integer}
     * range reaches <em>both</em> the class's widened type and {@link
     * edu.upc.dtak.aran.operators.queryengine.plan.PlanExecutor}'s view of the wrapper's output.
     * Calcite therefore believes the column is already BIGINT on both sides — while the snapshot
     * actually holds text.
     */
    private static MappingCatalog unalignedTypedCatalog(String status) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView(
                        "art",
                        "SELECT \"title\" AS \"" + EXP_S_TITLE + "\","
                                + " \"year\" AS \"" + EXP_S_YEAR + "\" FROM ${SNAPSHOT}",
                        Map.of(EXP_S_TITLE, EXP_S_TITLE, EXP_S_YEAR, EXP_S_YEAR),
                        1L, List.of(), "",
                        Map.of(EXP_S_YEAR, XSD + "integer"),
                        Map.of("title", EXP_S_TITLE, "year", EXP_S_YEAR),
                        status)));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /**
     * The case where Calcite's belief about a column's type is <em>wrong</em>, and the reason
     * {@code OqlPlanner} refuses to expand a member whose cast that belief makes redundant.
     *
     * <p>{@code buildDdl} TRY_CASTs every non-text column unconditionally; the graph's
     * {@code rdfs:range} says integer, the snapshot holds {@code "1937"}, and the cast is what
     * makes the column a number. Expanding here produced a projection Calcite simplified to the
     * identity and then deleted outright, leaving {@code SELECT * FROM "src_art"} — the raw
     * column, uncast, and the query returned {@code "1937"} where it had returned {@code 1937}.
     * The member therefore stays opaque, and this test is the executed proof that it does and
     * that the answer is unchanged.
     */
    @Test
    @DisplayName("a typed column the wrapper's own plan already carries as that type keeps the"
            + " member opaque — the cast buildDdl writes would be optimized away")
    void aRedundantLookingCastKeepsTheMemberOpaqueAndTheAnswerUnchanged() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            java.nio.file.Path artFile = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"year\":\"1937\"}]");
            edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor.SnapshotResolver resolver =
                    ds -> "read_json_auto('" + artFile.toAbsolutePath() + "')";

            String oql = "SELECT Artworks.title, Artworks.year FROM Artworks";
            QueryHarness.Outcome opaque   = h.run(unalignedTypedCatalog("OPAQUE"),   resolver, oql);
            QueryHarness.Outcome expanded = h.run(unalignedTypedCatalog("EXPANDED"), resolver, oql);

            assertThat(expanded.sql())
                    .as("the EXPANDED verdict is overruled at plan time")
                    .doesNotContain("src_art");
            assertThat(expanded.sql()).isEqualTo(opaque.sql());
            assertThat(expanded.result().rows())
                    .containsExactlyInAnyOrderElementsOf(opaque.result().rows());
            assertThat(expanded.result().rows())
                    .as("the cast in the u_ view is what makes this a number")
                    .containsExactly(List.<Object>of("Guernica", 1937L));
        }
    }

    /**
     * Artworks from two sources where the first supplies no {@code year}. Only that first source's
     * {@code expansionStatus} varies, so the padding an expanded member does with a literal is
     * compared against the padding the {@code u_} view body writes for the same source.
     *
     * <p>Both sources record {@code year}'s range even though the first has no column for it — the
     * hand-built shape {@code buildDdl}'s own {@code CAST(NULL AS …)} branch needs, and the only
     * one an expanded member can pad at all. The realistic untyped case stays opaque by design
     * ({@code OqlPlanner.REPRODUCIBLE_CAST_TYPES}, and the planner test that pins it).
     */
    private static MappingCatalog paddingCatalog(String firstSourceStatus) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView(
                        "art",
                        "SELECT \"title\" AS \"" + EXP_S_TITLE + "\","
                                + " \"artist_id\" AS \"" + EXP_S_AID + "\" FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, EXP_S_TITLE, P_AID, EXP_S_AID),
                        2L, List.of(), "", Map.of(EXP_YEAR, XSD + "integer"),
                        Map.of("title", EXP_S_TITLE, "artist_id", EXP_S_AID),
                        firstSourceStatus),
                new MappingCatalog.SourceView(
                        "art2", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_AID, "artist_id", EXP_YEAR, "year"),
                        1L, List.of(), "", Map.of(EXP_YEAR, XSD + "integer"))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /**
     * The padding half of the differential. A global column the expanded source does not supply
     * becomes a typed NULL literal in the RelNode tree instead of the {@code NULL AS "…"} the
     * {@code u_} view body writes — a substitution that keeps the row type union-compatible even
     * when it pads with the wrong column's values, so only executing it proves it right.
     */
    @Test
    @DisplayName("an expanded member NULL-pads a column it does not supply exactly as its u_ view did")
    void expandedMemberPadsAnUnsuppliedColumnTheSameWayTheOpaqueMemberDoes() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            java.nio.file.Path artFile = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"},"
                            + " {\"title\":\"Convergence\",\"artist_id\":\"a2\"}]");
            java.nio.file.Path art2File = h.writeJson("art2",
                    "[{\"title\":\"Impression\",\"artist_id\":\"a3\",\"year\":1872}]");
            edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor.SnapshotResolver resolver =
                    ds -> "read_json_auto('"
                            + ("art".equals(ds) ? artFile : art2File).toAbsolutePath() + "')";

            String oql = "SELECT Artworks.title, Artworks.year FROM Artworks";
            QueryHarness.Outcome opaque   = h.run(paddingCatalog("OPAQUE"),   resolver, oql);
            QueryHarness.Outcome expanded = h.run(paddingCatalog("EXPANDED"), resolver, oql);

            assertThat(expanded.sql()).contains("src_art");
            assertThat(expanded.sql())
                    .as("the second, still-opaque member keeps its u_ scan")
                    .contains("u_art2_Artworks");
            // The pad's own emitted text, measured rather than assumed: RexBuilder.makeNullLiteral
            // renders through RelToSql as CAST(NULL AS BIGINT), which is character-for-character
            // what buildDdl's unmapped non-VARCHAR branch writes into the u_ view body. If Calcite
            // ever renders it differently this fails here rather than as a changed answer.
            assertThat(expanded.sql())
                    .as("the typed pad must be the same text buildDdl writes")
                    .contains("CAST(NULL AS BIGINT)");
            assertThat(expanded.result().rows())
                    .containsExactlyInAnyOrderElementsOf(opaque.result().rows());
            // The padded rows must carry NULL, not some other column's value — which a row-type
            // assertion cannot tell apart, since any expression of the right type satisfies it.
            assertThat(expanded.result().rows())
                    .containsExactlyInAnyOrder(
                            java.util.Arrays.asList("Guernica", null),
                            java.util.Arrays.asList("Convergence", null),
                            List.<Object>of("Impression", 1872L));
            assertThat(expanded.result().headers()).isEqualTo(opaque.result().headers());
        }
    }
}

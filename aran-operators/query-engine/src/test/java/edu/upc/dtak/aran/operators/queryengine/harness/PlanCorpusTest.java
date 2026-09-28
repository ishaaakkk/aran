package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan-shape corpus (spec &sect;6): every entry pins its optimized plan's shape to a
 * committed golden file under {@code src/test/resources/plan-goldens/}, and every entry
 * checks the standing invariants. Run with {@code -Daran.plan.goldens.update=true} to
 * rewrite goldens after an INTENDED plan change — the diff is the review artifact.
 *
 * <p>The corpus grows over time — {@link #corpus()} is the source of truth for how many entries
 * it holds and what each one covers, not a count restated here that would only go stale again.
 * Every entry spans the Artworks/IArtist and Customer/Orders models already exercised by
 * {@link CorrectnessHarnessTest}, and every one parses and lowers cleanly under today's OQL
 * grammar — none were dropped. {@code GROUP BY}, {@code ORDER BY},
 * multi-hop {@code FROM} paths and {@code COUNT(ClassName.property)} (as opposed to
 * {@code COUNT(*)}) are each already covered individually by
 * {@code OqlParserTest.parsesGroupBy}/{@code parsesOrderBy}/{@code parsesMultiHopPath} and
 * {@code OqlEndToEndTest.countStarOnJoinGroupsByRightSide}/{@code minMaxOnPropertyProduceSql}
 * (the latter is the same {@code AGG(ClassName.property) ... GROUP BY} shape as this
 * corpus's {@code aggregate} entry, with {@code MIN}/{@code MAX} standing in for
 * {@code COUNT}) — so this corpus exercises grammar the engine was already known to accept,
 * not new ground. Should a later corpus addition need syntax the grammar does not accept,
 * drop it here rather than bending the grammar or the query: this corpus documents what the
 * engine does today, and extending OQL is not phase 1.
 */
class PlanCorpusTest {

    record Entry(String name, String oql, Supplier<MappingCatalog> catalog,
                 Map<String, List<String>> enforced) {
        Entry(String name, String oql, Supplier<MappingCatalog> catalog) {
            this(name, oql, catalog, Map.of());
        }
    }

    private static final Path GOLDENS =
            Path.of("src/test/resources/plan-goldens");

    // ── IRI constants (copied from CorrectnessHarnessTest — same NS values) ────────────

    private static final String NS  = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";

    // Artworks / IArtist model
    private static final String ARTWORKS   = NS + "integration/Artworks";
    private static final String IARTIST    = NS + "integration/IArtist";
    private static final String P_TITLE    = NS + "integration/title";   // localName "title"
    private static final String P_NAME     = NS + "integration/name";    // localName "name"
    private static final String P_AID      = SRC + "art/artist_id";      // localName "artist_id"
    private static final String P_ID       = SRC + "artist/id";          // localName "id"
    private static final String HAS_ARTIST = NS + "integration/hasArtist";

    // Customer / Order model (fan-out: one customer, many orders)
    private static final String CUSTOMER   = NS + "integration/Customer";
    // Local name "Orders", not "Order": chosen when the OQL lexer reserved "order". Keywords
    // are contextual now and "Order" would parse, but the goldens pin this name.
    private static final String ORDER      = NS + "integration/Orders";
    private static final String C_NAME     = NS + "integration/name";      // localName "name"
    private static final String C_ID       = SRC + "cust/id";              // localName "id"
    private static final String O_ID       = SRC + "order/id";             // localName "id"
    private static final String O_CUSTID   = SRC + "order/customer_id";    // localName "customer_id"
    private static final String HAS_ORDER  = NS + "integration/hasOrder";

    // Three-class-chain extras (Task 5 brief): a second join property, Orders -[hasArtwork]->
    // Artworks, plus the id column on Artworks that hasArtwork joins against. Scoped to
    // chainCatalog() only — the other entries' Artworks never carries this column.
    private static final String O_ARTWORKID = SRC + "order/artwork_id";    // localName "artwork_id"
    private static final String P_ART_ID    = SRC + "art/id";              // localName "id"
    private static final String HAS_ARTWORK = NS + "integration/hasArtwork";

    // Denial-constraint entry (Task 6): a typed column the constraint and the query both range
    // over. Also reused below by the wrapper-expansion entries (Task 6, phase 3A) — a typed
    // column is exactly what exercises those entries' TRY_CAST too.
    private static final String P_YEAR      = NS + "integration/year";     // localName "year"
    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";

    // Wrapper-expansion entries (Task 6, phase 3A): source-property IRIs a generated wrapper
    // aliases its raw columns to (WrapperSqlBuilder.buildFlat's shape). Deliberately distinct
    // strings from every global property IRI above — including the P_AID value that happens to
    // share this file's SRC namespace — so a typed column's TRY_CAST is never silently
    // optimized away as a redundant identity cast (OqlPlanner.pushExpandedMember's "unaligned"
    // case; see OqlPlannerExpansionTest.anUnalignedTypedColumnKeepsTheMemberOpaque). EXP_S_TITLE
    // /EXP_S_AID/EXP_S_YEAR back expandedCatalog(); EXP_S_ID/EXP_S_NAME back
    // joinCatalogExpanded(), the EXPANDED half of the OPAQUE/EXPANDED differential.
    private static final String EXP_S_TITLE = SRC + "art/title_raw";
    private static final String EXP_S_AID   = SRC + "art/artist_id_raw";
    private static final String EXP_S_YEAR  = SRC + "art/year_raw";
    private static final String EXP_S_ID    = SRC + "artist/id_raw";
    private static final String EXP_S_NAME  = SRC + "artist/name_raw";

    // ── Catalog factories ────────────────────────────────────────────────────────────

    /** 1 class, 1 source, 100 rows. Used by single-class-projection and order-limit. */
    private static MappingCatalog singleSourceCatalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), 100L)));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /**
     * 1 class, 2 sources (100 + 200 rows) — the GAV union corpus entry. Both sources supply
     * the same physical column names; only the row counts differ, which is all this entry
     * needs to exercise the filter-below-union rewrite.
     */
    private static MappingCatalog unionCatalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art1", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), 100L),
                new MappingCatalog.SourceView("art2", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), 200L)));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /**
     * Artworks 100, IArtist 10 000, joined by hasArtist. Shared by two-class-join,
     * join-with-filter and aggregate, which all use the same catalog.
     */
    private static MappingCatalog joinCatalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), 100L)));
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("artist", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_ID, "id", P_NAME, "name"), 10_000L)));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        return new MappingCatalog(
                Map.of(ARTWORKS, artworks, IARTIST, iartist), List.of(join), List.of(), "v1");
    }

    /**
     * Customer 50, Orders 5 000, Artworks 100 — a two-hop chain:
     * {@code Customer -[hasOrder]-> Orders -[hasArtwork]-> Artworks}. hasArtwork is a second
     * join property not used elsewhere in the corpus; Artworks carries an extra id column
     * (P_ART_ID) purely as the join target — the chain query itself never selects it.
     */
    private static MappingCatalog chainCatalog() {
        var customer = new MappingCatalog.IntegratedClass(CUSTOMER, List.of(
                new MappingCatalog.SourceView("cust", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(C_ID, "id", C_NAME, "name"), 50L)));
        var order = new MappingCatalog.IntegratedClass(ORDER, List.of(
                new MappingCatalog.SourceView("order", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(O_ID, "id", O_CUSTID, "customer_id", O_ARTWORKID, "artwork_id"),
                        5_000L)));
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_ART_ID, "id"), 100L)));
        var hasOrder   = new MappingCatalog.JoinProperty(HAS_ORDER, CUSTOMER, ORDER, C_ID, O_CUSTID);
        var hasArtwork = new MappingCatalog.JoinProperty(HAS_ARTWORK, ORDER, ARTWORKS, O_ARTWORKID, P_ART_ID);
        return new MappingCatalog(
                Map.of(CUSTOMER, customer, ORDER, order, ARTWORKS, artworks),
                List.of(hasOrder, hasArtwork), List.of(), "v1");
    }

    /**
     * 1 class, 1 source, {@code year} typed {@code xsd:integer} (so its row-type field is BIGINT
     * and an integer literal compares against it). Paired with {@link #YEAR_POSITIVE}, which the
     * DDL applies to the {@code u_} and {@code int_} bodies and the planner reads as a fact.
     */
    private static MappingCatalog constrainedCatalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_YEAR, "year"),
                        100L, List.of(), "", Map.of(P_YEAR, XSD_INTEGER))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /** The enforced denial constraint for {@link #constrainedCatalog()}. */
    private static final Map<String, List<String>> YEAR_POSITIVE =
            Map.of(ARTWORKS, List.of("year > 0"));

    /**
     * 1 class, 1 source, status {@code EXPANDED} — a generated wrapper
     * ({@code WrapperSqlBuilder.buildFlat}'s shape: each raw column aliased to its
     * source-property IRI) whose SQL is a pure projection over {@code ${SNAPSHOT}}. {@code year}
     * is typed {@code xsd:integer} (BIGINT — {@code OqlPlanner.REPRODUCIBLE_CAST_TYPES}), so its
     * renaming projection genuinely {@code TRY_CAST}s; {@code title} has no recorded range, so it
     * is a bare reference. Every mapped column is present in the wrapper's output and there is no
     * enforced constraint on the class, so this member clears every gate
     * {@code OqlPlanner.pushExpandedMember} checks — mirrors the already-proven
     * {@code OqlPlannerExpansionTest.artSource}/{@code PlanShapeHarnessTest.expansionCatalog}
     * fixture shape exactly, not a new one.
     */
    private static MappingCatalog expandedCatalog() {
        String wrapperSql = "SELECT \"title\" AS \"" + EXP_S_TITLE + "\","
                + " \"artist_id\" AS \"" + EXP_S_AID + "\","
                + " \"year\" AS \"" + EXP_S_YEAR + "\" FROM ${SNAPSHOT}";
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView(
                        "art", wrapperSql,
                        Map.of(P_TITLE, EXP_S_TITLE, P_AID, EXP_S_AID, P_YEAR, EXP_S_YEAR),
                        100L, List.of(), "",
                        Map.of(P_YEAR, XSD_INTEGER),
                        Map.of("title", EXP_S_TITLE, "artist_id", EXP_S_AID, "year", EXP_S_YEAR),
                        "EXPANDED")));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /**
     * The EXPANDED counterpart to {@link #joinCatalog()}, used only by the executed
     * OPAQUE/EXPANDED differential below. Both members are recorded EXPANDED with
     * generated-wrapper-shaped SQL and populated {@code sourceRefs}; row counts and the join
     * property are copied from {@link #joinCatalog()} unchanged, so the two catalogs differ in
     * nothing but expansion status — a row divergence between them can only be the expansion.
     */
    private static MappingCatalog joinCatalogExpanded() {
        String artWrapper = "SELECT \"title\" AS \"" + EXP_S_TITLE + "\","
                + " \"artist_id\" AS \"" + EXP_S_AID + "\" FROM ${SNAPSHOT}";
        String artistWrapper = "SELECT \"id\" AS \"" + EXP_S_ID + "\","
                + " \"name\" AS \"" + EXP_S_NAME + "\" FROM ${SNAPSHOT}";
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", artWrapper,
                        Map.of(P_TITLE, EXP_S_TITLE, P_AID, EXP_S_AID),
                        100L, List.of(), "", Map.of(),
                        Map.of("title", EXP_S_TITLE, "artist_id", EXP_S_AID),
                        "EXPANDED")));
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("artist", artistWrapper,
                        Map.of(P_ID, EXP_S_ID, P_NAME, EXP_S_NAME),
                        10_000L, List.of(), "", Map.of(),
                        Map.of("id", EXP_S_ID, "name", EXP_S_NAME),
                        "EXPANDED")));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        return new MappingCatalog(
                Map.of(ARTWORKS, artworks, IARTIST, iartist), List.of(join), List.of(), "v1");
    }

    /**
     * Artworks(1000) referencing IArtist(100) via hasArtist (artist_id → id), with a
     * {@code DECLARED} referential edge on those columns and an exact ({@code SNAPSHOT}) unique key
     * on IArtist.id. Backs the {@code declared-fk-join-eliminated} entry: {@code AranJoinRemoveRule}
     * drops IArtist entirely, leaving {@code σ(artist_id IS NOT NULL)(Artworks)} — the null-reject
     * filter that keeps the inner join's semantics.
     */
    private static MappingCatalog declaredFkCatalog() {
        return referentialCatalog(/*declared=*/true);
    }

    /**
     * Byte-for-byte {@link #declaredFkCatalog()} except the referential edge is absent — so the only
     * edge is the {@code HYPOTHESIS} one every accepted join yields. Backs
     * {@code hypothesis-join-not-eliminated}: same query, same exact key, join <em>kept</em>. The
     * two goldens side by side are the trust-tier differential in plan-shape form.
     */
    private static MappingCatalog hypothesisFkCatalog() {
        return referentialCatalog(/*declared=*/false);
    }

    private static MappingCatalog referentialCatalog(boolean declared) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), 1000L)));
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("artist", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_ID, "id", P_NAME, "name"), 100L, List.of(keyProfile("id")))));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        List<MappingCatalog.ReferentialEdge> edges = declared
                ? List.of(new MappingCatalog.ReferentialEdge(
                        ARTWORKS, List.of(P_AID), IARTIST, List.of(P_ID), MappingCatalog.Tier.DECLARED))
                : List.of();
        return new MappingCatalog(
                Map.of(ARTWORKS, artworks, IARTIST, iartist), List.of(join), List.of(), edges, "v1");
    }

    /** A profile marking {@code column} an exact unique key (SNAPSHOT, uniqueness 1.0). */
    private static ColumnProfile keyProfile(String column) {
        return new ColumnProfile(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), column, 0, "VARCHAR",
                100L, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    // ── The corpus ───────────────────────────────────────────────────────────────────

    private static List<Entry> corpus() {
        return List.of(
                new Entry("single-class-projection",
                        "SELECT Artworks.title FROM Artworks",
                        PlanCorpusTest::singleSourceCatalog),
                new Entry("filter-into-union",
                        "SELECT Artworks.title FROM Artworks WHERE Artworks.title = 'Guernica'",
                        PlanCorpusTest::unionCatalog),
                new Entry("two-class-join",
                        "SELECT Artworks.title, IArtist.name AS artist "
                                + "FROM Artworks -[hasArtist]-> IArtist",
                        PlanCorpusTest::joinCatalog),
                new Entry("join-with-filter",
                        "SELECT Artworks.title, IArtist.name AS artist "
                                + "FROM Artworks -[hasArtist]-> IArtist "
                                + "WHERE IArtist.name = 'Picasso'",
                        PlanCorpusTest::joinCatalog),
                new Entry("aggregate",
                        "SELECT IArtist.name, COUNT(Artworks.title) AS n "
                                + "FROM Artworks -[hasArtist]-> IArtist GROUP BY IArtist.name",
                        PlanCorpusTest::joinCatalog),
                new Entry("order-limit",
                        "SELECT Artworks.title FROM Artworks ORDER BY Artworks.title LIMIT 5",
                        PlanCorpusTest::singleSourceCatalog),
                new Entry("three-class-chain",
                        "SELECT Customer.name, Artworks.title "
                                + "FROM Customer -[hasOrder]-> Orders -[hasArtwork]-> Artworks",
                        PlanCorpusTest::chainCatalog),
                // An enforced constraint the query contradicts: year > 0 is guaranteed by the
                // view body, so year < 0 cannot match and the plan is empty VALUES with no scan
                // in it at all. The golden is what pins that — see AranRelMdPredicates.
                new Entry("dc-contradiction",
                        "SELECT Artworks.title FROM Artworks WHERE Artworks.year < 0",
                        PlanCorpusTest::constrainedCatalog,
                        YEAR_POSITIVE),
                // The pure src_-leaf shape (spec §7.1): the EXPANDED member's own renaming
                // projection (memberRowType, IRI-typed TRY_CAST included) merges with the
                // query's outer projection into ONE PROJECT node directly over SCAN[src_art] —
                // no u_art_Artworks anywhere. Measured, not assumed:
                //   PROJECT[title, year]
                //     SCAN[src_art]
                new Entry("expanded-projection",
                        "SELECT Artworks.title, Artworks.year FROM Artworks",
                        PlanCorpusTest::expandedCatalog),
                // The filter-pushdown half of the same story: FilterProjectTransposeRule pushes
                // WHERE year > 1900 below the renaming projection, so the FILTER lands directly
                // on SCAN[src_art] rather than above the whole member as it would for an opaque
                // u_ scan — the optimizer can only do this because the wrapper's internals are
                // now visible RelNodes, not a black-box view. The condition re-embeds the
                // TRY_CAST the transpose carried through (Filter's own predicate is independent
                // of the Project's output expression, so both recompute it — ordinary Calcite,
                // not a bug). Measured, not assumed:
                //   PROJECT[title, year]
                //     FILTER[>(TRY_CAST(year), 1900)]
                //       SCAN[src_art]
                new Entry("expanded-filter-pushdown",
                        "SELECT Artworks.title, Artworks.year FROM Artworks WHERE Artworks.year > 1900",
                        PlanCorpusTest::expandedCatalog),
                // Phase 4B — trust-tier-gated join elimination. Same query, same exact key on the
                // referenced side; the ONLY difference is whether a DECLARED referential edge backs
                // the join. With it, the join and IArtist's scan are gone, replaced by the
                // null-rejecting filter that preserves the inner join's row-dropping semantics:
                //   PROJECT[title]
                //     FILTER[IS NOT NULL(artist_id)]
                //       SCAN[u_art_Artworks]
                new Entry("declared-fk-join-eliminated",
                        "SELECT Artworks.title FROM Artworks -[hasArtist]-> IArtist",
                        PlanCorpusTest::declaredFkCatalog),
                // Without the DECLARED edge the same query keeps its join — an accepted join is a
                // HYPOTHESIS and may never drive elimination. This golden is the "byte-identical to
                // today" half of the trust-tier proof.
                new Entry("hypothesis-join-not-eliminated",
                        "SELECT Artworks.title FROM Artworks -[hasArtist]-> IArtist",
                        PlanCorpusTest::hypothesisFkCatalog));
    }

    @TestFactory
    Stream<DynamicTest> corpusPlansMatchGoldensAndInvariantsHold() {
        return corpus().stream().map(e -> DynamicTest.dynamicTest(e.name(), () -> {
            try (QueryHarness h = new QueryHarness()) {
                QueryHarness.Outcome out = h.plan(e.catalog().get(), e.enforced(), e.oql());

                // Standing invariant (spec §6): an all-snapshot/absorbed query is ONE statement.
                assertThat(out.sql()).as("single DuckDB statement").doesNotContain(";");
                // D3 tripwire: the production budget is sized so that no corpus entry ever
                // trips it — a golden pinned on a best-so-far plan would pin an accident.
                assertThat(out.planning().degradation())
                        .as("corpus entry %s planned within the budget (%d productions)",
                                e.name(), out.planning().productions())
                        .isEqualTo(edu.upc.dtak.aran.operators.queryengine.plan.Planning.Degradation.NONE);

                // Shared with PerfGoldenTest, which keeps its own directory and its own update
                // flag: one flag rewrites whatever corpus it is pointed at, so sharing it would
                // let accepting an intended plan change here silently rewrite the scale goldens
                // too — and the diff is the review artifact.
                Goldens.assertOrUpdate(GOLDENS, e.name(), out.shape().toCompactString(),
                        "aran.plan.goldens.update");
            }
        }));
    }

    // ── Executed correctness: the two central shapes ──────────────────────────────────

    @Test
    @DisplayName("corpus: two-class-join returns the joined rows")
    void twoClassJoinRowsCorrect() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path artFile = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}," +
                    " {\"title\":\"Convergence\",\"artist_id\":\"a2\"}]");
            Path artistFile = h.writeJson("artist",
                    "[{\"id\":\"a1\",\"name\":\"Picasso\"}," +
                    " {\"id\":\"a2\",\"name\":\"Pollock\"}]");

            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "art"    -> "read_json_auto('" + artFile.toAbsolutePath() + "')";
                case "artist" -> "read_json_auto('" + artistFile.toAbsolutePath() + "')";
                default -> throw new IllegalArgumentException("Unknown dataset: " + ds);
            };

            var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                    new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(P_TITLE, "title", P_AID, "artist_id"))));
            var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                    new MappingCatalog.SourceView("artist", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(P_ID, "id", P_NAME, "name"))));
            var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
            var catalog = new MappingCatalog(
                    Map.of(ARTWORKS, artworks, IARTIST, iartist),
                    List.of(join), List.of(), "v1");

            String oql = "SELECT Artworks.title, IArtist.name AS artist "
                    + "FROM Artworks -[hasArtist]-> IArtist";

            QueryHarness.Outcome out = h.run(catalog, resolver, oql);

            // Element type of rows() is List<Object>; use List.<Object>of so the
            // expected rows are List<Object>, not the invariant List<String>.
            assertThat(out.result().rows())
                    .containsExactlyInAnyOrder(
                            List.<Object>of("Guernica", "Picasso"),
                            List.<Object>of("Convergence", "Pollock"));
        }
    }

    // ── Phase 3A: the OPAQUE/EXPANDED differential, batched over the two-class-join fixture ───

    @Test
    @DisplayName("corpus: two-class-join returns the same rows whether every member is OPAQUE or"
            + " EXPANDED")
    void twoClassJoinRowsAgreeWhetherMembersAreOpaqueOrExpanded() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path artFile = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}," +
                    " {\"title\":\"Convergence\",\"artist_id\":\"a2\"}]");
            Path artistFile = h.writeJson("artist",
                    "[{\"id\":\"a1\",\"name\":\"Picasso\"}," +
                    " {\"id\":\"a2\",\"name\":\"Pollock\"}]");

            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "art"    -> "read_json_auto('" + artFile.toAbsolutePath() + "')";
                case "artist" -> "read_json_auto('" + artistFile.toAbsolutePath() + "')";
                default -> throw new IllegalArgumentException("Unknown dataset: " + ds);
            };

            String oql = "SELECT Artworks.title, IArtist.name AS artist "
                    + "FROM Artworks -[hasArtist]-> IArtist";

            // joinCatalog() is all-OPAQUE; joinCatalogExpanded() is the same shape with both
            // members recorded EXPANDED — the two catalogs differ in nothing else.
            QueryHarness.Outcome opaque   = h.run(joinCatalog(), resolver, oql);
            QueryHarness.Outcome expanded = h.run(joinCatalogExpanded(), resolver, oql);

            // The differential proves nothing if the EXPANDED side silently fell back to its u_
            // scan, so assert both halves actually took the path their name claims. Quoted: an
            // unquoted "src_art" is a prefix of "src_artist", so that check alone would still
            // pass with Artworks silently opaque and only IArtist actually expanded.
            assertThat(expanded.sql())
                    .as("both members must actually expand, or this differential proves nothing")
                    .contains("\"src_art\"").contains("\"src_artist\"");
            assertThat(opaque.sql())
                    .as("the OPAQUE half must still take the u_ path it always did")
                    .doesNotContain("src_art").doesNotContain("src_artist");

            assertThat(expanded.result().rows())
                    .containsExactlyInAnyOrderElementsOf(opaque.result().rows());
            assertThat(expanded.result().rows())
                    .as("and both are the right answer, not the same wrong one")
                    .containsExactlyInAnyOrder(
                            List.<Object>of("Guernica", "Picasso"),
                            List.<Object>of("Convergence", "Pollock"));
        }
    }

    @Test
    @DisplayName("corpus: filter-into-union returns only matching rows from both sources")
    void filterIntoUnionRowsCorrect() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path art1File = h.writeJson("art1",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}," +
                    " {\"title\":\"Convergence\",\"artist_id\":\"a2\"}]");
            Path art2File = h.writeJson("art2",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a3\"}," +
                    " {\"title\":\"No.5\",\"artist_id\":\"a2\"}]");

            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "art1" -> "read_json_auto('" + art1File.toAbsolutePath() + "')";
                case "art2" -> "read_json_auto('" + art2File.toAbsolutePath() + "')";
                default -> throw new IllegalArgumentException("Unknown dataset: " + ds);
            };

            var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                    new MappingCatalog.SourceView("art1", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(P_TITLE, "title", P_AID, "artist_id")),
                    new MappingCatalog.SourceView("art2", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(P_TITLE, "title", P_AID, "artist_id"))));
            var catalog = new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");

            String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.title = 'Guernica'";

            QueryHarness.Outcome out = h.run(catalog, resolver, oql);

            // One "Guernica" from each source (art1's a1, art2's a3); each source's "not
            // Guernica" row (Convergence, No.5) is filtered out — the filter must apply to
            // both union branches, not just the one Calcite happened to plan first.
            assertThat(out.result().rows())
                    .containsExactlyInAnyOrder(
                            List.<Object>of("Guernica"),
                            List.<Object>of("Guernica"));
        }
    }

    @Test
    @DisplayName("corpus: dc-contradiction returns zero rows, and DuckDB accepts the empty plan")
    void dcContradictionReturnsNoRowsWithoutError() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path artFile = h.writeJson("art-dc",
                    "[{\"title\":\"Guernica\",\"year\":1937}," +
                    " {\"title\":\"Convergence\",\"year\":1952}]");

            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "art" -> "read_json_auto('" + artFile.toAbsolutePath() + "')";
                default -> throw new IllegalArgumentException("Unknown dataset: " + ds);
            };

            QueryHarness.Outcome out = h.run(constrainedCatalog(), resolver, YEAR_POSITIVE,
                    "SELECT Artworks.title FROM Artworks WHERE Artworks.year < 0");

            // The point of the executed variant: the collapsed plan is not merely a nice shape,
            // it unparses to SQL DuckDB will run. Calcite emits an empty relation as
            // "SELECT * FROM (SELECT NULL AS <col> …) AS t WHERE 1 = 0" for this dialect
            // (DuckDBSqlDialect declares supportsAliasedValues() = false), and no view is named
            // at all — the query is answered without opening a snapshot.
            assertThat(out.sql()).doesNotContain("u_art_Artworks").doesNotContain("int_Artworks");
            assertThat(out.result().rows()).isEmpty();
            assertThat(out.result().headers()).hasSize(1);
        }
    }

    @Test
    @DisplayName("corpus: TRY_CAST drops a dirty numeric cell instead of erroring the whole query")
    void tryCastFilterDropsDirtyRowInsteadOfErroring() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path artFile = h.writeJson("art-typed",
                    "[{\"title\":\"Keeper\",\"year\":2015}," +
                    " {\"title\":\"TooOld\",\"year\":9}," +
                    " {\"title\":\"Dirty\",\"year\":\"not-a-number\"}]");

            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "art-typed" -> "read_json_auto('" + artFile.toAbsolutePath() + "')";
                default -> throw new IllegalArgumentException("Unknown dataset: " + ds);
            };

            String yearIri = NS + "integration/year";
            Map<String, String> columnTypes =
                    Map.of(yearIri, "http://www.w3.org/2001/XMLSchema#integer");
            var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                    new MappingCatalog.SourceView("art-typed", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(P_TITLE, "title", yearIri, "year"),
                            0L, List.of(), "", columnTypes)));
            var catalog = new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");

            String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.year > 2000";

            QueryHarness.Outcome out = h.run(catalog, resolver, oql);

            // "TooOld" (year=9) proves this is a genuine numeric comparison, not a leftover
            // string one: as text "9" > "2000" (leading '9' > '2'), so a stringwise compare
            // would wrongly keep it. "Dirty" (year="not-a-number") proves TRY_CAST degrades the
            // cell to NULL rather than raising a runtime conversion error that would otherwise
            // fail the whole query, including the rows that do not even reference it.
            assertThat(out.result().rows()).containsExactly(List.<Object>of("Keeper"));
        }
    }
}

package edu.upc.dtak.aran.operators.queryengine.plan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A: the planner consumes EXPANDED members.
 *
 * <p>A wrapper whose SQL plans cleanly over its raw {@code src_} relation is spliced into the
 * query plan in place of its opaque {@code u_} scan, with an IRI-renaming projection on top that
 * reproduces exactly what {@code DuckDbExecutor.buildDdl} puts in the {@code u_} view body —
 * TRY_CAST for a mapped column whose type must change, a plain reference where it need not, and a
 * typed NULL literal for a global column this source does not supply.
 *
 * <p>Every fixture below mirrors the shape a real catalog produces (see {@code MappingCatalogTest}
 * and {@code WrapperSqlBuilder}): {@code columnMap} is global property IRI → source-property IRI,
 * {@code sourceRefs} is raw physical column name → source-property IRI, {@code columnTypes} is
 * global property IRI → XSD range, and a generated wrapper's SELECT aliases each raw column to its
 * source-property IRI.
 */
class OqlPlannerExpansionTest {

    private static final String NS  = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private static final String ARTWORKS = NS + "integration/Artworks";
    /** Global property IRIs. Sorted: artist_id < title < year — the class's column vector. */
    private static final String P_AID   = NS + "integration/artist_id";
    private static final String P_TITLE = NS + "integration/title";
    private static final String P_YEAR  = NS + "integration/year";
    /** Source-property IRIs: the columnMap values, and a generated wrapper's output aliases. */
    private static final String S_AID   = SRC + "art/artist_id";
    private static final String S_TITLE = SRC + "art/title";
    private static final String S_YEAR  = SRC + "art/year";

    private static final String ONE_TITLE = "SELECT Artworks.title FROM Artworks";

    // ── Step 1: the unparse spike ───────────────────────────────────────────────

    @Test
    @DisplayName("spike: a TRY_CAST RexCall unparses as TRY_CAST(\"x\" AS BIGINT) under DuckDbDialect")
    void tryCastUnparsesAsDuckDbTryCast() {
        var sv = new MappingCatalog.SourceView(
                "ds1", "SELECT 1", Map.of(), 0L, List.of(), "",
                Map.of(), Map.of("x", "http://ex/x"), "EXPANDED");
        SchemaPlus schema = Frameworks.createRootSchema(false);
        schema.add("src_ds1", new SrcTable(sv));
        RelBuilder b = RelBuilder.create(
                Frameworks.newConfigBuilder().defaultSchema(schema).build());

        b.scan("src_ds1");
        RexNode field = b.field("x");
        RelDataType bigint = b.getTypeFactory().createTypeWithNullability(
                b.getTypeFactory().createSqlType(SqlTypeName.BIGINT), true);
        RexNode cast = b.getRexBuilder().makeCall(bigint, AranSqlOperators.TRY_CAST, List.of(field));
        RelNode rel = b.project(List.of(cast), List.of("http://ex/x"), true).build();

        String sql = PlanExecutor.toSqlFromOptimized(rel);

        // Pinned, not assumed (ran with the assertion inverted to read the real string):
        //   SELECT TRY_CAST("x" AS BIGINT) AS "http://ex/x"
        //   FROM "src_ds1"
        // So RelToSqlConverter does NOT normalise a SAFE_CAST-kind call away, and the target type
        // survives as the cast's second unparsed operand even though the RexCall carries one
        // operand and a return type. No DuckDbDialect.unparseCall hook is needed — see
        // AranSqlOperators.TRY_CAST for why the operator is Calcite's own.
        assertThat(sql).contains("TRY_CAST(\"x\" AS BIGINT)");
        assertThat(sql).contains("FROM \"src_ds1\"");
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    /**
     * One source of Artworks. {@code status} is the only thing the tests vary between the two
     * halves of the differential, so an assertion difference can only be the expansion.
     */
    private static MappingCatalog.SourceView artSource(String wrapperSql, String status) {
        return new MappingCatalog.SourceView(
                "art", wrapperSql,
                Map.of(P_AID, S_AID, P_TITLE, S_TITLE, P_YEAR, S_YEAR),
                100L, List.of(), "",
                Map.of(P_YEAR, XSD + "integer"),
                Map.of("artist_id", S_AID, "title", S_TITLE, "year", S_YEAR),
                status);
    }

    /** Exactly what {@code WrapperSqlBuilder.buildFlat} emits: raw column AS source-property IRI. */
    private static final String GENERATED_WRAPPER =
            "SELECT \"artist_id\" AS \"" + S_AID + "\", \"title\" AS \"" + S_TITLE + "\","
                    + " \"year\" AS \"" + S_YEAR + "\" FROM ${SNAPSHOT}";

    private static MappingCatalog catalogOf(MappingCatalog.SourceView... sources) {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(sources));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /**
     * The refusal WARNs are deduplicated by a static (dataset, reason) set, so without this a
     * test asserting one would pass or fail depending on what ran before it.
     */
    @BeforeEach
    void forgetPreviousExpansionWarnings() {
        OqlPlanner.resetExpansionWarningDedup();
    }

    private static RelNode lower(MappingCatalog catalog, String oql) {
        return lower(catalog, Map.of(), oql);
    }

    private static RelNode lower(MappingCatalog catalog, Map<String, List<String>> enforced,
                                 String oql) {
        return new OqlPlanner(catalog, enforced).lower(OqlParser.parse(oql));
    }

    private static String shape(MappingCatalog catalog, String oql) {
        return PlanShape.of(lower(catalog, oql)).toCompactString();
    }

    /** Runs {@code body} with {@link OqlPlanner}'s WARNs captured. */
    private static List<ILoggingEvent> capturingPlannerWarnings(Runnable body) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OqlPlanner.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            body.run();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        return captured.list;
    }

    private static void assertWarned(List<ILoggingEvent> events, String... fragments) {
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(fragments);
        });
    }

    /** Asserts {@code oql} still plans over the opaque {@code u_} view, and warns why. */
    private static void assertFallsBackToOpaqueWithWarning(
            Supplier<MappingCatalog> catalog, String... fragments) {
        String[] shapeHolder = new String[1];
        List<ILoggingEvent> events =
                capturingPlannerWarnings(() -> shapeHolder[0] = shape(catalog.get(), ONE_TITLE));

        assertThat(shapeHolder[0])
                .as("a member that cannot be expanded is scanned exactly as it always was")
                .isEqualTo(shape(catalogOf(artSource(GENERATED_WRAPPER, "OPAQUE")), ONE_TITLE));
        assertWarned(events, fragments);
    }

    // ── Step 2 (a): an EXPANDED member plans through its src_ relation ──────────

    @Test
    @DisplayName("an EXPANDED pure-projection member scans src_<dataset>, never its u_ view")
    void expandedMemberPlansThroughItsSrcRelation() {
        String compact = shape(catalogOf(artSource(GENERATED_WRAPPER, "EXPANDED")), ONE_TITLE);

        assertThat(compact).contains("SCAN[src_art]");
        assertThat(compact)
                .as("the opaque member scan is replaced, not supplemented")
                .doesNotContain("SCAN[u_art_Artworks]");
    }

    @Test
    @DisplayName("the emitted SQL for an EXPANDED member reads src_ and TRY_CASTs the typed column")
    void expandedMemberEmitsSqlOverSrcWithTryCast() {
        // year is xsd:integer on the class but VARCHAR as the wrapper hands it over (no raw-name
        // range is recorded), so the rename projection must widen it exactly as buildDdl's u_ body
        // does — TRY_CAST, never CAST: a dirty cell degrades to NULL rather than failing the query.
        String sql = PlanExecutor.toSql(lower(
                catalogOf(artSource(GENERATED_WRAPPER, "EXPANDED")),
                "SELECT Artworks.title, Artworks.year FROM Artworks"));

        assertThat(sql).contains("\"src_art\"");
        assertThat(sql).doesNotContain("u_art_Artworks");
        assertThat(sql).contains("TRY_CAST(");
    }

    @Test
    @DisplayName("a hand-edited wrapper that projects its raw column names unaliased still expands"
            + " — the mapped column is found through the sourceRefs inverse")
    void wrapperProjectingRawNamesResolvesThroughSourceRefs() {
        // The generator always aliases each raw column to its source-property IRI, so every other
        // fixture here matches columnMap's value directly. A wrapper a user rewrote by hand need
        // not: its output names are then the raw ones, and only sourceRefs connects the two.
        String compact = shape(catalogOf(artSource(
                "SELECT \"artist_id\", \"title\", \"year\" FROM ${SNAPSHOT}", "EXPANDED")),
                ONE_TITLE);

        assertThat(compact).contains("SCAN[src_art]");
        assertThat(compact).doesNotContain("SCAN[u_art_Artworks]");
    }

    /**
     * Artworks from two sources: an EXPANDED one that supplies only two of the class's three
     * global columns (so its rename projection has to pad {@code year}), and an OPAQUE one that
     * supplies all three. A {@code LogicalUnion} refuses inputs whose row types differ, so this
     * fixture will not even build if the expansion's projection drifts.
     *
     * <p>Both sources record {@code year}'s range even though the first has no column for it,
     * which no real catalog does — QUERY 1 writes {@code columnMap} and {@code columnTypes}
     * together. It is the same hand-built shape {@code DuckDbExecutor.buildDdl}'s own
     * {@code CAST(NULL AS …)} branch needs, and it is here for the same reason: to reach the
     * typed pad. The realistic, untyped case is
     * {@link #anUnsuppliedUntypedColumnKeepsTheMemberOpaque}.
     */
    private static MappingCatalog twoSourceCatalog() {
        var expanded = new MappingCatalog.SourceView(
                "art", "SELECT \"artist_id\" AS \"" + S_AID + "\", \"title\" AS \"" + S_TITLE
                        + "\" FROM ${SNAPSHOT}",
                Map.of(P_AID, S_AID, P_TITLE, S_TITLE),
                100L, List.of(), "", Map.of(P_YEAR, XSD + "integer"),
                Map.of("artist_id", S_AID, "title", S_TITLE),
                "EXPANDED");
        var opaque = new MappingCatalog.SourceView(
                "art2", "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_AID, S_AID, P_TITLE, S_TITLE, P_YEAR, S_YEAR),
                200L, List.of(), "", Map.of(P_YEAR, XSD + "integer"));
        return catalogOf(expanded, opaque);
    }

    @Test
    @DisplayName("an EXPANDED member's row type is identical to the opaque member's and to"
            + " WrapperTable.getRowType — same order, same types, NULL-padded the same way")
    void expandedMemberRowTypeMatchesTheWrapperTableExactly() {
        MappingCatalog catalog = twoSourceCatalog();
        var cls = catalog.classes().get(ARTWORKS);
        var expanded = cls.sources().get(0);

        RelNode logical = lower(catalog, ONE_TITLE);
        Union union = findUnion(logical);
        assertThat(union).as("a two-source class unions its members").isNotNull();

        RelDataType wrapperTableRowType =
                new WrapperTable(expanded, cls).getRowType(logical.getCluster().getTypeFactory());
        assertThat(union.getInput(0).getRowType()).isEqualTo(wrapperTableRowType);
        assertThat(union.getInput(0).getRowType()).isEqualTo(union.getInput(1).getRowType());
        assertThat(union.getInput(0).getRowType().getFieldNames())
                .containsExactly(P_AID, P_TITLE, P_YEAR);
        assertThat(PlanShape.of(union.getInput(0)).toCompactString()).contains("SCAN[src_art]");
        assertThat(PlanShape.of(union.getInput(1)).toCompactString())
                .isEqualTo("SCAN[u_art2_Artworks]\n");
    }

    // ── The cluster the expanded subtree lives in ──────────────────────────────

    @Test
    @DisplayName("a plan containing an expanded member has exactly one cluster — the probe plans"
            + " in a throwaway cluster of its own, so its tree is rebuilt, never spliced")
    void expandedMemberIsRebuiltInTheQuerysOwnCluster() {
        // Deliberately the two-source fixture. A single-source class hides the bug: RelBuilder
        // merges the query's own projection into the member's, and the merged node inherits its
        // input's cluster, so a spliced plan would be entirely — and wrongly — in the PROBE's
        // cluster and still look self-consistent. With a Union the two members' clusters have to
        // meet, and they must be the same one.
        RelNode logical = lower(twoSourceCatalog(), ONE_TITLE);

        assertThat(clustersIn(logical, new java.util.LinkedHashSet<>()))
                .as("one cluster; PlanExecutor.optimize registers the whole tree into the planner"
                        + " it finds on the ROOT's cluster")
                .hasSize(1);
    }

    @Test
    @DisplayName("the cost model sees an expanded member's per-raw-column statistics — the reason"
            + " the subtree has to be in the planner's own cluster and not merely correct")
    void expandedMemberLeafStatisticsReachTheCostModel() {
        // installKeyAwareMetadata installs Aran's provider chain on the RelBuilder's cluster
        // only, and Calcite reads a node's RelMetadataQuery off rel.getCluster(). A foreign
        // subtree is therefore costed with Calcite's defaults while returning every row
        // correctly — exactly the failure that no row assertion can catch.
        //
        // Measured on this fixture, not argued: the raw "title" column is profiled at NDV 1000
        // over 1000 rows, so AranRelMdSelectivity gives the equality 1/1000 and the plan is
        // estimated at 1.0 rows. Rebuilding the probe's tree replaced by splicing it in unchanged
        // (the one-line experiment) gives 150.0 instead — Calcite's fixed 0.15 guess for `=`,
        // the freyja profile ignored, and every row still correct.
        var profiled = new MappingCatalog.SourceView(
                "art", GENERATED_WRAPPER,
                Map.of(P_AID, S_AID, P_TITLE, S_TITLE, P_YEAR, S_YEAR),
                1000L, List.of(profile(S_TITLE, 1000L)), "",
                Map.of(P_YEAR, XSD + "integer"),
                Map.of("artist_id", S_AID, "title", S_TITLE, "year", S_YEAR),
                "EXPANDED");

        RelNode optimized = PlanExecutor.optimize(lower(catalogOf(profiled),
                "SELECT Artworks.title FROM Artworks WHERE Artworks.title = 'Guernica'"));

        assertThat(optimized.getCluster().getMetadataQuery().getRowCount(optimized))
                .isLessThan(10.0);
    }

    /** A {@code ColumnProfile} carrying only the two fields {@link SrcTable} reads. */
    private static edu.upc.dtak.aran.core.domain.ColumnProfile profile(String columnName, long ndv) {
        return new edu.upc.dtak.aran.core.domain.ColumnProfile(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), columnName, 0, "VARCHAR",
                ndv, null, 0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0, 0L, 0, 0, 0.0, 0.0, null, null, false, false,
                List.of(), List.of());
    }

    private static java.util.Set<Object> clustersIn(RelNode rel, java.util.Set<Object> found) {
        found.add(rel.getCluster());
        for (RelNode input : rel.getInputs()) {
            clustersIn(input, found);
        }
        return found;
    }

    private static Union findUnion(RelNode rel) {
        if (rel instanceof Union union) {
            return union;
        }
        for (RelNode input : rel.getInputs()) {
            Union found = findUnion(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ── Step 2 (b): an OPAQUE member is untouched ──────────────────────────────

    @Test
    @DisplayName("an OPAQUE member's plan is byte-identical to what it was before expansion existed")
    void opaqueMemberPlansExactlyAsBefore() {
        assertThat(shape(catalogOf(artSource(GENERATED_WRAPPER, "OPAQUE")), ONE_TITLE))
                .isEqualTo("""
                        PROJECT[title]
                          SCAN[u_art_Artworks]
                        """);
    }

    // ── Step 2 (c): a plan-time probe failure degrades to the opaque scan ──────

    @Test
    @DisplayName("a member the graph calls EXPANDED whose SQL no longer plans falls back to its"
            + " u_ scan with a WARN, and the query still plans")
    void probeFailureAtPlanTimeFallsBackToTheOpaqueScan() {
        // DuckDB-only syntax Calcite's parser rejects — the graph's EXPANDED verdict and the
        // wrapper text disagree, which only a plan-time probe can notice.
        assertFallsBackToOpaqueWithWarning(
                () -> catalogOf(artSource("SELECT * FROM ${SNAPSHOT} USING SAMPLE 5", "EXPANDED")),
                "art", "could not be expanded");
    }

    // ── Step 2 (d): a mapped column the wrapper does not output ────────────────

    @Test
    @DisplayName("a mapped column absent from the wrapper's output falls back to opaque with a WARN"
            + " — the wrapper text and the mapping disagree")
    void mappedColumnMissingFromWrapperOutputFallsBackToOpaque() {
        // The wrapper plans (title is a real raw column), but it never emits the columns
        // columnMap promises for artist_id and year. Nothing in the round-trip check could see
        // this: the wrapper is internally consistent, it just does not match the mapping.
        assertFallsBackToOpaqueWithWarning(
                () -> catalogOf(artSource(
                        "SELECT \"title\" AS \"" + S_TITLE + "\" FROM ${SNAPSHOT}", "EXPANDED")),
                "art", S_AID);
    }

    // ── Enforced denial constraints are a correctness gate, not a fidelity one ────────

    @Test
    @DisplayName("a class with an enforced denial constraint keeps its members opaque, and says so"
            + " — the filter lives in the u_ view body an expansion would replace")
    void anEnforcedDenialConstraintKeepsTheMemberOpaque() {
        MappingCatalog catalog = catalogOf(artSource(GENERATED_WRAPPER, "EXPANDED"));
        Map<String, List<String>> enforced = Map.of(ARTWORKS, List.of("year > 1900"));

        String[] compact = new String[1];
        List<ILoggingEvent> events = capturingPlannerWarnings(() ->
                compact[0] = PlanShape.of(lower(catalog, enforced, ONE_TITLE)).toCompactString());

        assertThat(compact[0]).contains("SCAN[u_art_Artworks]").doesNotContain("src_art");
        assertWarned(events, "art", "enforces 1 denial constraint(s)");
    }

    @Test
    @DisplayName("a constraint the planner cannot even parse still keeps the member opaque — the"
            + " count that matters is the enforced one, not the parsed one")
    void anUnparseableConstraintStillKeepsTheMemberOpaque() {
        // parseEnforced drops what it cannot lower, on the grounds that a dropped constraint costs
        // only estimate quality — true, because the view body still enforces it. That reasoning
        // inverts here: if expansion consulted the parsed constraints it would see none, expand,
        // and read straight past a filter that is very much still being applied.
        MappingCatalog catalog = catalogOf(artSource(GENERATED_WRAPPER, "EXPANDED"));
        Map<String, List<String>> enforced =
                Map.of(ARTWORKS, List.of("year >>> not_parseable ((("));

        String[] compact = new String[1];
        List<ILoggingEvent> events = capturingPlannerWarnings(() ->
                compact[0] = PlanShape.of(lower(catalog, enforced, ONE_TITLE)).toCompactString());

        assertThat(compact[0]).contains("SCAN[u_art_Artworks]").doesNotContain("src_art");
        assertWarned(events, "art", "enforces 1 denial constraint(s)");
    }

    @Test
    @DisplayName("the same refusal WARNs once, not once per query — a planner is built per query,"
            + " so the throttle has to outlive it")
    void aRepeatedRefusalIsReportedOnce() {
        MappingCatalog catalog = catalogOf(artSource(GENERATED_WRAPPER, "EXPANDED"));
        Map<String, List<String>> enforced = Map.of(ARTWORKS, List.of("year > 1900"));

        List<ILoggingEvent> events = capturingPlannerWarnings(() -> {
            lower(catalog, enforced, ONE_TITLE);
            lower(catalog, enforced, ONE_TITLE);
            lower(catalog, enforced, ONE_TITLE);
        });

        assertThat(events).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
    }

    // ── The projection has to be byte-identical to buildDdl's, not merely equivalent ───

    @Test
    @DisplayName("a column the member does not supply and the class has no range for keeps the"
            + " member opaque — buildDdl pads it with an untyped NULL, which Rex cannot express")
    void anUnsuppliedUntypedColumnKeepsTheMemberOpaque() {
        // The realistic multi-source shape: art has no year at all, so it records no range for it
        // either, so the class's widened type for year is VARCHAR. buildDdl's u_ body pads that
        // with a bare NULL and lets DuckDB's UNION ALL take its type from the sibling member;
        // Calcite can only offer CAST(NULL AS VARCHAR), which makes the union resolve to text.
        // Caught by the executed differential, not by any row type: see REPRODUCIBLE_CAST_TYPES.
        var expanded = new MappingCatalog.SourceView(
                "art", "SELECT \"title\" AS \"" + S_TITLE + "\" FROM ${SNAPSHOT}",
                Map.of(P_TITLE, S_TITLE), 100L, List.of(), "", Map.of(),
                Map.of("title", S_TITLE), "EXPANDED");
        var opaque = new MappingCatalog.SourceView(
                "art2", "SELECT * FROM ${SNAPSHOT}", Map.of(P_TITLE, S_TITLE, P_YEAR, S_YEAR), 200L);

        String[] compact = new String[1];
        List<ILoggingEvent> events = capturingPlannerWarnings(
                () -> compact[0] = shape(catalogOf(expanded, opaque), ONE_TITLE));

        assertThat(compact[0]).contains("SCAN[u_art_Artworks]").doesNotContain("src_art");
        assertWarned(events, "art", "does not supply column", P_YEAR);
    }

    /**
     * Every {@link SqlTypeName} {@link XsdTypes#fromXsd} can return, derived from the XSD ranges a
     * catalog can record rather than hard-coded, so a new mapping shows up here as a new case.
     */
    static java.util.stream.Stream<SqlTypeName> everyTypeXsdTypesCanProduce() {
        return java.util.stream.Stream.of(
                        "string", "integer", "int", "long", "short", "byte", "nonNegativeInteger",
                        "positiveInteger", "decimal", "double", "float", "boolean", "date",
                        "dateTime", "gYear")   // gYear: unrecognised, so it exercises the fallback
                .map(local -> XsdTypes.fromXsd(XSD + local))
                .distinct();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyTypeXsdTypesCanProduce")
    @DisplayName("the reproducible-cast lattice is exactly the set of types whose cast renders as"
            + " XsdTypes.duckType writes it")
    void theCastLatticeIsPinnedToDuckType(SqlTypeName type) {
        if (type == SqlTypeName.VARCHAR) {
            // The one type buildDdl writes NO cast for — a mapped text column is a bare reference
            // and an unmapped one a bare NULL. There is no rendering to compare, so its exclusion
            // is a different fact, pinned by the bare-reference and unsupplied-column tests.
            assertThat(OqlPlanner.REPRODUCIBLE_CAST_TYPES).doesNotContain(type);
            return;
        }

        String sql = renderTryCastTo(type);
        // The trailing ")" matters: without it "AS TIMESTAMP" matches the "AS TIMESTAMP(0)"
        // Calcite actually emits, and the exclusion this test exists to pin would pass by
        // accident.
        String buildDdlWrites = "TRY_CAST(\"x\" AS " + XsdTypes.duckType(type) + ")";

        // The whole screen in one assertion: a type may be expanded exactly when the cast the
        // expansion emits is character-for-character the cast buildDdl writes into the u_ view.
        // Phase 4 harvesting real precision/scale changes duckType(DECIMAL) — this fails then,
        // rather than the two drifting apart silently.
        if (OqlPlanner.REPRODUCIBLE_CAST_TYPES.contains(type)) {
            assertThat(sql)
                    .as("%s is expandable, so its cast must render exactly as buildDdl writes it",
                            type)
                    .contains(buildDdlWrites);
        } else {
            assertThat(sql)
                    .as("%s is excluded precisely because its cast does NOT render as %s", type,
                            XsdTypes.duckType(type))
                    .doesNotContain(buildDdlWrites);
        }
    }

    /** The cast {@code pushExpandedMember} would build for {@code type}, unparsed to DuckDB SQL. */
    private static String renderTryCastTo(SqlTypeName type) {
        var sv = new MappingCatalog.SourceView(
                "ds1", "SELECT 1", Map.of(), 0L, List.of(), "",
                Map.of(), Map.of("x", "http://ex/x"), "EXPANDED");
        SchemaPlus schema = Frameworks.createRootSchema(false);
        schema.add("src_ds1", new SrcTable(sv));
        RelBuilder b = RelBuilder.create(
                Frameworks.newConfigBuilder().defaultSchema(schema).build());
        b.scan("src_ds1");
        // "x" is VARCHAR (no range recorded), so the cast is never a no-op and never simplified —
        // the same condition pushExpandedMember requires before it emits one at all.
        RelDataType target = b.getTypeFactory().createTypeWithNullability(
                b.getTypeFactory().createSqlType(type), true);
        RexNode cast = b.getRexBuilder().makeCall(
                target, AranSqlOperators.TRY_CAST, List.of(b.field("x")));
        return PlanExecutor.toSqlFromOptimized(
                b.project(List.of(cast), List.of("http://ex/x"), true).build());
    }

    @Test
    @DisplayName("an unaligned typed column keeps the member opaque — SrcTable gives the raw column"
            + " the class's own type, so the TRY_CAST buildDdl writes would be optimized away")
    void anUnalignedTypedColumnKeepsTheMemberOpaque() {
        // columnMap identity is what "unaligned" means: the global IRI and the source-property IRI
        // are one string, so the recorded range reaches both the class's widened type and
        // SrcTable's view of the raw column. Calcite then sees a no-op cast, RexSimplify drops it,
        // the projection reduces to the identity and the optimizer deletes it — leaving the raw
        // src_ column uncast. The executed proof that the answer would have changed is in
        // PlanShapeHarnessTest.
        var unaligned = new MappingCatalog.SourceView(
                "art", "SELECT \"title\" AS \"" + S_TITLE + "\", \"year\" AS \"" + S_YEAR
                        + "\" FROM ${SNAPSHOT}",
                Map.of(S_TITLE, S_TITLE, S_YEAR, S_YEAR),
                100L, List.of(), "", Map.of(S_YEAR, XSD + "integer"),
                Map.of("title", S_TITLE, "year", S_YEAR), "EXPANDED");

        String[] compact = new String[1];
        List<ILoggingEvent> events = capturingPlannerWarnings(
                () -> compact[0] = shape(catalogOf(unaligned), ONE_TITLE));

        assertThat(compact[0]).contains("SCAN[u_art_Artworks]").doesNotContain("src_art");
        assertWarned(events, "art", "optimized away as redundant", S_YEAR);
    }

    @Test
    @DisplayName("a class column typed DECIMAL keeps the member opaque — Calcite renders the cast"
            + " as DECIMAL(19, 0) where buildDdl writes DECIMAL(38,9)")
    void aDecimalColumnKeepsTheMemberOpaque() {
        var decimalSource = new MappingCatalog.SourceView(
                "art", GENERATED_WRAPPER,
                Map.of(P_AID, S_AID, P_TITLE, S_TITLE, P_YEAR, S_YEAR),
                100L, List.of(), "",
                Map.of(P_YEAR, XSD + "decimal"),
                Map.of("artist_id", S_AID, "title", S_TITLE, "year", S_YEAR),
                "EXPANDED");

        assertFallsBackToOpaqueWithWarning(() -> catalogOf(decimalSource), "art", "DECIMAL");
    }

    @Test
    @DisplayName("a wrapper that plans to a node kind the planner cannot rebuild in its own cluster"
            + " falls back to opaque with a WARN")
    void unrebuildableWrapperShapeFallsBackToOpaque() {
        // SELECT DISTINCT lowers to an Aggregate, which the transfer deliberately does not
        // rebuild. Without this case the copier's default branch is dead code as far as the suite
        // can tell — every other fixture here plans to Project-over-Scan.
        assertFallsBackToOpaqueWithWarning(
                () -> catalogOf(artSource(
                        "SELECT DISTINCT \"artist_id\" AS \"" + S_AID + "\", \"title\" AS \""
                                + S_TITLE + "\", \"year\" AS \"" + S_YEAR + "\" FROM ${SNAPSHOT}",
                        "EXPANDED")),
                "art", "LogicalAggregate");
    }

    // ── Final-review Important 2: a genuine plan-time exception must degrade, not fail ────────

    /**
     * A wrapper whose SQL carries an {@code ORDER BY} plans to Sort-over-Project-over-Scan, which
     * exercises {@link OqlPlanner#adopt}'s {@code Sort} branch — untested until now, since every
     * other fixture in this class plans to Project-over-Scan, or (for
     * {@link #unrebuildableWrapperShapeFallsBackToOpaque}) an Aggregate that never reaches
     * {@code adopt} at all.
     *
     * <p>This stands in for a test that provokes the new plan-time-exception fallback in
     * {@code pushExpandedMember} (the {@code try/catch} wrapping its post-probe body, added for
     * the same review finding this test documents). A real, un-mocked exception through
     * {@code adopt}, the ordinal-mapping loop, {@code RexBuilder} or {@code LogicalProject.create}
     * was attempted for several shapes engineered to break them — a class with no columns at all
     * (a degenerate zero-expression {@code LogicalProject}), a wrapper with a {@code WHERE} clause
     * (the untested {@code Filter} branch of {@code adopt}), and a wrapper combining
     * {@code WHERE} with {@code ORDER BY} and a {@code LIMIT} — and every one planned cleanly with
     * no exception: {@code adopt} and the projection loop are, empirically, as total as their
     * shape suggests. Absent a genuine failure to provoke without mocking {@link OqlPlanner}
     * internals, this test instead pins the thing the review offered as the acceptable
     * alternative: the previously-untested {@code Sort} branch works end-to-end, still expanding
     * the member rather than merely not-crashing.
     */
    @Test
    @DisplayName("a wrapper whose SQL carries an ORDER BY expands through adopt's untested Sort"
            + " branch — the try/catch's own throwing path could not be provoked without mocking,"
            + " so this pins the Sort-adoption path the review accepted in its place")
    void wrapperWithOrderByExpandsThroughTheSortAdoptionPath() {
        String withOrderBy = "SELECT \"artist_id\" AS \"" + S_AID + "\", \"title\" AS \"" + S_TITLE
                + "\", \"year\" AS \"" + S_YEAR + "\" FROM ${SNAPSHOT} ORDER BY \"year\"";

        String compact = shape(catalogOf(artSource(withOrderBy, "EXPANDED")), ONE_TITLE);

        assertThat(compact).contains("SCAN[src_art]");
        assertThat(compact).as("the wrapper's ORDER BY plans to a Sort node that adopt must rebuild"
                + " in the query's own cluster").contains("SORT[");
        assertThat(compact)
                .as("the opaque member scan is replaced, not supplemented")
                .doesNotContain("SCAN[u_art_Artworks]");
    }
}

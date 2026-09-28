package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link AranRelMdSelectivity} and {@link AranRelMdDistinctRowCount} — profile-backed estimates
 * for a {@link org.apache.calcite.rel.core.TableScan}, evidenced by freyja's exact per-column NDV
 * ({@code ColumnProfile.cardinality}) and null fraction ({@code ColumnProfile.incompleteness}).
 *
 * <p>Two properties matter, mirroring {@link AranRelMdRowCountTest}'s structure: that the
 * profile-backed estimate fires and is numerically correct when evidence exists, and — the
 * safety property — that everything else is byte-identical to (or, for the multi-source case,
 * as conservative as) stock Calcite. A regression that silently dropped into a worse estimate
 * would otherwise be invisible: plans stay valid, they just get worse.
 */
class AranRelMdSelectivityTest {

    private static final String NS = "http://example.com/";
    private static final String ITEM_IRI = NS + "Item";

    /** Fixture profile: cardinality=50, incompleteness=0.2. */
    private static final String PROFILED = NS + "profiledProp";
    /** No {@link ColumnProfile} at all — exercises the "no evidence" fallback path. */
    private static final String UNPROFILED = NS + "unprofiledProp";

    private static ColumnProfile profile(String column, long cardinality, double incompleteness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                cardinality, null, 0.0, incompleteness,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    /**
     * One single-source class: 1000 rows, one profiled column (NDV 50, 20% nulls) and one
     * unprofiled column. Deliberately untyped (no {@code columnTypes} entries, so every column
     * widens to VARCHAR) — NDV/selectivity logic is type-independent, so this sidesteps needing
     * a type-compatible literal for the Task 3 typed-row-type work.
     */
    private static MappingCatalog catalog() {
        var items = new MappingCatalog.IntegratedClass(ITEM_IRI, List.of(
                new MappingCatalog.SourceView("ds_items", "SELECT 1",
                        Map.of(PROFILED, "profiled_col", UNPROFILED, "unprofiled_col"),
                        1000L,
                        List.of(profile("profiled_col", 50L, 0.2)))));
        return new MappingCatalog(Map.of(ITEM_IRI, items), List.of(), List.of(), "v-test");
    }

    /** Installs the planner's real chain — same wiring {@link AranRelMdRowCountTest} uses. */
    private static RelBuilder aranBuilder(MappingCatalog catalog) {
        SchemaPlus schema = AranSchema.create(catalog);
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);
        OqlPlanner.installKeyAwareMetadata(builder.getCluster());
        return builder;
    }

    /**
     * Installs pure stock Calcite — no Aran handler at all, not even {@link AranRelMdRowCount}.
     * The only use is computing the reference value for the byte-identical-fallback assertion,
     * so this must NOT go through {@link OqlPlanner#ARAN_METADATA_PROVIDER}.
     */
    private static RelBuilder stockCalciteBuilder(MappingCatalog catalog) {
        SchemaPlus schema = AranSchema.create(catalog);
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);
        RelOptCluster cluster = builder.getCluster();
        cluster.setMetadataQuerySupplier(() ->
                new RelMetadataQuery(JaninoRelMetadataProvider.of(DefaultRelMetadataProvider.INSTANCE)));
        cluster.invalidateMetadataQuery();
        return builder;
    }

    /** Builds {@code Filter(scan("int_Item"), predicate)} and returns the handler's row-count estimate. */
    private static double filteredRowCount(RelBuilder builder, Function<RelBuilder, RexNode> predicate) {
        builder.scan("int_Item");
        builder.filter(predicate.apply(builder));
        RelNode filter = builder.build();
        RelMetadataQuery mq = filter.getCluster().getMetadataQuery();
        Double rc = mq.getRowCount(filter);
        assertThat(rc).as("Calcite always returns an estimate").isNotNull();
        return rc;
    }

    // ── The profile-backed Selectivity estimate fires ─────────────────────────

    @Test
    @DisplayName("equality on a profiled column uses 1/NDV: 1000 rows * (1/50) = 20")
    void equalityUsesInverseNdv() {
        double rc = filteredRowCount(aranBuilder(catalog()),
                b -> b.equals(b.field(PROFILED), b.literal("x")));
        assertThat(rc).isCloseTo(20.0, within(0.001));
    }

    @Test
    @DisplayName("IS NULL on a profiled column uses the profiled null fraction: 1000 * 0.2 = 200")
    void isNullUsesProfiledNullFraction() {
        double rc = filteredRowCount(aranBuilder(catalog()), b -> b.isNull(b.field(PROFILED)));
        assertThat(rc).isCloseTo(200.0, within(0.001));
    }

    @Test
    @DisplayName("IS NOT NULL on a profiled column is the complement: 1000 * (1 - 0.2) = 800")
    void isNotNullUsesComplementOfNullFraction() {
        double rc = filteredRowCount(aranBuilder(catalog()), b -> b.isNotNull(b.field(PROFILED)));
        assertThat(rc).isCloseTo(800.0, within(0.001));
    }

    // ── The safety property: no evidence → byte-identical to stock Calcite ────

    @Test
    @DisplayName("a filter on an unprofiled column matches Calcite's default estimate exactly")
    void unprofiledColumnMatchesCalciteDefaultExactly() {
        Function<RelBuilder, RexNode> predicate = b -> b.equals(b.field(UNPROFILED), b.literal("x"));

        double withAranChain = filteredRowCount(aranBuilder(catalog()), predicate);
        double stockCalcite = filteredRowCount(stockCalciteBuilder(catalog()), predicate);

        assertThat(withAranChain)
                .as("no profile evidence exists for this column, so our per-term guess must fall"
                        + " back to exactly what RelMdUtil.guessSelectivity(predicate) already gives")
                .isCloseTo(stockCalcite, within(1e-9));
    }

    // ── DistinctRowCount ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getDistinctRowCount on a profiled column returns the profiled NDV")
    void distinctRowCountReturnsProfiledNdv() {
        RelBuilder builder = aranBuilder(catalog());
        builder.scan("int_Item");
        RelNode scan = builder.build();
        int ordinal = scan.getRowType().getFieldNames().indexOf(PROFILED);
        assertThat(ordinal).as("fixture column must be in the row type").isGreaterThanOrEqualTo(0);

        RelMetadataQuery mq = scan.getCluster().getMetadataQuery();
        Double ndv = mq.getDistinctRowCount(scan, ImmutableBitSet.of(ordinal), null);

        assertThat(ndv).isCloseTo(50.0, within(0.001));
    }

    @Test
    @DisplayName("getDistinctRowCount on a cardinality-0 profile matches Calcite's default, not a fabricated NDV")
    void distinctRowCountIgnoresZeroCardinalityProfile() {
        // cardinality 0 is what an all-NULL (or never-profiled) column reports: "no evidence of
        // a distinct value", not "zero distinct rows". An unguarded (double) cast hands Calcite's
        // getDistinctRowCount a bare 0.0 — which RelMdUtil.validateResult (RelMetadataQuery's own
        // public entry point) then floors to a *fabricated* 1.0 ("exactly one distinct value"),
        // not the 0.0 the cast itself produced. Either number is invented precision a zero
        // profile does not support; the correct answer is whatever evidence-free default stock
        // Calcite gives (here: null — the column is not a verified key).
        var items = new MappingCatalog.IntegratedClass(ITEM_IRI, List.of(
                new MappingCatalog.SourceView("ds_items", "SELECT 1",
                        Map.of(PROFILED, "profiled_col"), 1000L,
                        List.of(profile("profiled_col", 0L, 1.0)))));
        var catalog = new MappingCatalog(Map.of(ITEM_IRI, items), List.of(), List.of(), "v-test");

        RelBuilder aran = aranBuilder(catalog);
        aran.scan("int_Item");
        RelNode aranScan = aran.build();
        int ordinal = aranScan.getRowType().getFieldNames().indexOf(PROFILED);
        assertThat(ordinal).as("fixture column must be in the row type").isGreaterThanOrEqualTo(0);
        Double withAranChain = aranScan.getCluster().getMetadataQuery()
                .getDistinctRowCount(aranScan, ImmutableBitSet.of(ordinal), null);

        RelBuilder stock = stockCalciteBuilder(catalog);
        stock.scan("int_Item");
        RelNode stockScan = stock.build();
        Double stockCalcite = stockScan.getCluster().getMetadataQuery()
                .getDistinctRowCount(stockScan, ImmutableBitSet.of(ordinal), null);

        assertThat(withAranChain)
                .as("cardinality 0 must be treated as no evidence, so this must fall back to"
                        + " exactly what stock Calcite's own TableScan handler gives")
                .isEqualTo(stockCalcite);
        // Boxed on purpose: AbstractDoubleAssert's isNotEqualTo(double) demands a non-null
        // actual before it will even unbox, and the correct actual here is null — so the
        // regression guard has to go through the Object-typed overload instead.
        Double fabricatedNdv = 1.0;
        assertThat(withAranChain)
                .as("must not be the bug's fabricated single distinct value — Calcite's own"
                        + " validateResult floors a bare min(0.0, rowCount) up to 1.0")
                .isNotEqualTo(fabricatedNdv);
    }

    @Test
    @DisplayName("getDistinctRowCount caps the profiled NDV at the scan's own row count")
    void distinctRowCountIsCappedAtRowCount() {
        // A source view whose NDV (50) legitimately exceeds its own row count (10) — a stale or
        // inconsistent profile snapshot. The cap keeps the estimate physically sane regardless.
        var items = new MappingCatalog.IntegratedClass(ITEM_IRI, List.of(
                new MappingCatalog.SourceView("ds_items", "SELECT 1",
                        Map.of(PROFILED, "profiled_col"), 10L,
                        List.of(profile("profiled_col", 50L, 0.0)))));
        var catalog = new MappingCatalog(Map.of(ITEM_IRI, items), List.of(), List.of(), "v-test");

        RelBuilder builder = aranBuilder(catalog);
        builder.scan("int_Item");
        RelNode scan = builder.build();
        int ordinal = scan.getRowType().getFieldNames().indexOf(PROFILED);

        RelMetadataQuery mq = scan.getCluster().getMetadataQuery();
        Double ndv = mq.getDistinctRowCount(scan, ImmutableBitSet.of(ordinal), null);

        assertThat(ndv).isCloseTo(10.0, within(0.001));
    }

    // ── Regression guard: the union aggregates NDV away ───────────────────────

    @Test
    @DisplayName("a multi-source class never exposes a per-source NDV — the union aggregates it away")
    void multiSourceClassDoesNotExposeProfiledNdv() {
        // Two sources, each with a clean profile on the same global column. If IntegratedClassTable
        // (wrongly) delegated to either source's profile, this would return 50 instead of null.
        var twoSource = new MappingCatalog.IntegratedClass(ITEM_IRI, List.of(
                new MappingCatalog.SourceView("ds_a", "SELECT 1",
                        Map.of(PROFILED, "profiled_col"), 500L,
                        List.of(profile("profiled_col", 50L, 0.2))),
                new MappingCatalog.SourceView("ds_b", "SELECT 1",
                        Map.of(PROFILED, "profiled_col"), 500L,
                        List.of(profile("profiled_col", 50L, 0.2)))));
        var catalog = new MappingCatalog(Map.of(ITEM_IRI, twoSource), List.of(), List.of(), "v-test");

        RelBuilder builder = aranBuilder(catalog);
        builder.scan("int_Item");
        RelNode scan = builder.build();
        int ordinal = scan.getRowType().getFieldNames().indexOf(PROFILED);

        RelMetadataQuery mq = scan.getCluster().getMetadataQuery();

        RexNode equalsPredicate = builder.getRexBuilder().makeCall(SqlStdOperatorTable.EQUALS,
                RexInputRef.of(ordinal, scan.getRowType()), builder.getRexBuilder().makeLiteral("x"));

        assertThat(mq.getDistinctRowCount(scan, ImmutableBitSet.of(ordinal), null))
                .as("IntegratedClassTable.columnNdv must return null once a class has more than one source")
                .isNull();
        assertThat(mq.getSelectivity(scan, equalsPredicate))
                .as("no 1/NDV shortcut may fire for a multi-source class's column either — Calcite's"
                        + " default 0.15 equality guess must be what comes through instead")
                .isCloseTo(0.15, within(0.001));
    }
}

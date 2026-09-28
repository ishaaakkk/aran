package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Table;
import org.apache.calcite.util.ImmutableBitSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-wrapper statistics reaching Calcite.
 *
 * <p>The motivating defect: {@link AranStatistic} must refuse to declare any key once a
 * class has more than one source, because UNION ALL can introduce duplicates across
 * sources. That switches key-aware join estimation off for exactly the classes integration
 * produces. A wrapper is single-source by construction, so its keys stay valid — these
 * tests pin that difference.
 */
class WrapperStatisticTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String PAPER = NS + "integration/Paper";
    private static final String P_ID = NS + "integration/paperId";
    private static final String P_TITLE = NS + "integration/title";

    /**
     * A profile carrying only the fields this statistic reads. freyja reports uniqueness
     * 1.0 for a key column (exact, from COUNT(DISTINCT)) and lower for a non-key; every
     * other metric is irrelevant here and left at zero.
     */
    private static ColumnProfile profile(String column, double uniqueness, long cardinality) {
        return new ColumnProfile(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), column, 0, "VARCHAR",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    /**
     * A Phase 4B HARVESTED-subject profile: {@code uniqueness} here is derived from PostgreSQL's
     * approximate {@code pg_stats.n_distinct}, not freyja's exact {@code COUNT(DISTINCT)} — see
     * {@code WrapperStatistic.isSingleColumnKey}'s javadoc for why that makes it ineligible
     * as key evidence regardless of how high it reads.
     */
    private static ColumnProfile harvestedProfile(String column, double uniqueness, long cardinality) {
        return new ColumnProfile(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), column, 0, "UNKNOWN",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                "HARVESTED", null);
    }

    private static MappingCatalog.SourceView wrapper(String datasetId, long rowCount,
                                                     List<ColumnProfile> profiles) {
        return new MappingCatalog.SourceView(
                datasetId, "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_ID, "id", P_TITLE, "title"),
                rowCount, profiles);
    }

    private static MappingCatalog.IntegratedClass classOf(MappingCatalog.SourceView... sources) {
        return new MappingCatalog.IntegratedClass(PAPER, List.of(sources));
    }

    // ── Row counts stay per wrapper ──────────────────────────────────────────

    @Test
    @DisplayName("each wrapper reports its own row count, not the class total")
    void rowCountIsPerWrapper() {
        var small = wrapper("ds-small", 100, List.of());
        var large = wrapper("ds-large", 900_000, List.of());
        var cls = classOf(small, large);

        Map<String, Table> tables = AranSchema.buildTableMap(
                new MappingCatalog(Map.of(PAPER, cls), List.of(), List.of(), "v1"));

        Statistic smallStat = tables.get(WrapperTable.viewName(small, cls)).getStatistic();
        Statistic largeStat = tables.get(WrapperTable.viewName(large, cls)).getStatistic();

        assertThat(smallStat.getRowCount()).isEqualTo(100.0);
        assertThat(largeStat.getRowCount()).isEqualTo(900_000.0);
    }

    @Test
    @DisplayName("the integrated class still reports the summed total, so existing plans are unaffected")
    void integratedClassStillSums() {
        var cls = classOf(wrapper("a", 100, List.of()), wrapper("b", 900_000, List.of()));

        Map<String, Table> tables = AranSchema.buildTableMap(
                new MappingCatalog(Map.of(PAPER, cls), List.of(), List.of(), "v1"));

        assertThat(tables.get("int_Paper").getStatistic().getRowCount()).isEqualTo(900_100.0);
    }

    @Test
    @DisplayName("an unprofiled wrapper reports unknown rather than zero")
    void unprofiledWrapperIsUnknown() {
        var sv = wrapper("ds", 0, List.of());
        assertThat(new WrapperTable(sv, classOf(sv)).getStatistic().getRowCount()).isNull();
    }

    // ── The key that AranStatistic has to give up ────────────────────────────

    @Test
    @DisplayName("a wrapper declares its unique column as a key even when the class has two sources")
    void wrapperKeysSurviveMultiSourceClasses() {
        var a = wrapper("ds-a", 100, List.of(profile("id", 1.0, 100), profile("title", 0.4, 40)));
        var b = wrapper("ds-b", 200, List.of(profile("id", 1.0, 200), profile("title", 0.3, 60)));
        var cls = classOf(a, b);

        Map<String, Table> tables = AranSchema.buildTableMap(
                new MappingCatalog(Map.of(PAPER, cls), List.of(), List.of(), "v1"));

        // columnOrder sorts by global IRI: paperId -> "id" (0), title -> "title" (1)
        Statistic wrapperStat = tables.get(WrapperTable.viewName(a, cls)).getStatistic();
        assertThat(wrapperStat.isKey(ImmutableBitSet.of(0))).isTrue();
        assertThat(wrapperStat.isKey(ImmutableBitSet.of(1))).isFalse();
        assertThat(wrapperStat.getKeys()).containsExactly(ImmutableBitSet.of(0));

        // The integrated class above the union cannot, and must not, claim the same key.
        Statistic intStat = tables.get("int_Paper").getStatistic();
        assertThat(intStat.isKey(ImmutableBitSet.of(0)))
                .as("UNION ALL can duplicate across sources, so the class must not declare a key")
                .isFalse();
    }

    @Test
    @DisplayName("a column below the uniqueness threshold is not a key")
    void nonUniqueColumnIsNotAKey() {
        var sv = wrapper("ds", 100, List.of(profile("id", 0.98, 98)));
        assertThat(new WrapperTable(sv, classOf(sv)).getStatistic().getKeys()).isEmpty();
    }

    @Test
    @DisplayName("a wrapper with no profile declares no keys")
    void unprofiledWrapperHasNoKeys() {
        var sv = wrapper("ds", 100, List.of());
        Statistic stat = new WrapperTable(sv, classOf(sv)).getStatistic();
        assertThat(stat.getKeys()).isEmpty();
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isFalse();
    }

    @Test
    @DisplayName("profiles named by source-property IRI (production shape) resolve for keys and NDV")
    void iriNamedProfilesResolve() {
        // On a real bootstrapped catalog columnMap values are source-property IRIs (the wrapper
        // view's own column names), and MappingCatalogOperator renames each freyja profile into
        // that namespace via nx:sourceRef. This pins the invariant the rename exists to satisfy:
        // whatever namespace columnMap's values are in, a profile named identically resolves.
        String idIri = NS + "source/ds/paper/id";
        var sv = new MappingCatalog.SourceView(
                "ds", "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_ID, idIri),
                100, List.of(profile(idIri, 1.0, 100)));
        var table = new WrapperTable(sv, classOf(sv));
        assertThat(table.getStatistic().isKey(ImmutableBitSet.of(0))).isTrue();
        assertThat(table.columnNdv(0)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("an empty column set is never a key")
    void emptyBitSetIsNotAKey() {
        var sv = wrapper("ds", 100, List.of(profile("id", 1.0, 100)));
        assertThat(new WrapperTable(sv, classOf(sv)).getStatistic().isKey(ImmutableBitSet.of())).isFalse();
    }

    // ── Phase 4B fix: HARVESTED uniqueness is approximate (pg_stats), never a verified key ──

    @Test
    @DisplayName("a HARVESTED profile at key-like uniqueness is NOT reported as a key by getKeys()/isKey()")
    void harvestedProfileAtKeyLikeUniquenessIsNotAKey() {
        // uniqueness 1.0 would satisfy the exactness test, but pg_stats.n_distinct is an
        // ANALYZE-based estimate — must never be read as "verified key" regardless of value.
        var sv = wrapper("ds", 100, List.of(harvestedProfile("id", 1.0, 100)));
        Statistic stat = new WrapperTable(sv, classOf(sv)).getStatistic();

        assertThat(stat.getKeys()).as("HARVESTED must never surface as a declared key").isEmpty();
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isFalse();
    }

    @Test
    @DisplayName("a SNAPSHOT profile at the same uniqueness still IS a key — no regression from the HARVESTED guard")
    void snapshotProfileAtSameUniquenessStillIsAKey() {
        var sv = wrapper("ds", 100, List.of(profile("id", 1.0, 100))); // SNAPSHOT (default subject)
        Statistic stat = new WrapperTable(sv, classOf(sv)).getStatistic();

        assertThat(stat.getKeys()).containsExactly(ImmutableBitSet.of(0));
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isTrue();
    }

    @Test
    @DisplayName("a HARVESTED profile still feeds columnNdv (the estimate path) even though it is excluded from key detection")
    void harvestedProfileStillFeedsColumnNdv() {
        // The fix scopes the guard to key detection only — cardinality/NDV evidence (which drives
        // AranRelMdDistinctRowCount/AranRelMdRowCount's join-cardinality estimate, cost-input-only
        // and therefore safe) must keep working exactly as before.
        var sv = wrapper("ds", 100, List.of(harvestedProfile("id", 0.98, 98)));
        var table = new WrapperTable(sv, classOf(sv));

        assertThat(table.getStatistic().isKey(ImmutableBitSet.of(0)))
                .as("0.98 is below the key threshold anyway, but this profile must never be a key")
                .isFalse();
        assertThat(table.columnNdv(0))
                .as("NDV still reaches the planner from a HARVESTED profile")
                .isEqualTo(98.0);
    }

    // ── T1.1 (audit PROFILING-12 + WRAPPER_VERIFY-2): row order and exactness of key evidence ──

    @Test
    @DisplayName("a HARVESTED row listed before the SNAPSHOT row for the same column must not shadow the key")
    void harvestedRowBeforeSnapshotRowDoesNotShadowTheKey() {
        // Once V36 lets SNAPSHOT and HARVESTED generations coexist for one physical column,
        // key detection must not depend on which row the list yields first.
        var sv = wrapper("ds", 100, List.of(harvestedProfile("id", 1.0, 100), profile("id", 1.0, 100)));
        Statistic stat = new WrapperTable(sv, classOf(sv)).getStatistic();

        assertThat(stat.getKeys()).containsExactly(ImmutableBitSet.of(0));
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isTrue();
    }

    @Test
    @DisplayName("near-unique is not a key: uniqueness 0.99995 (a real duplicate in 20k rows) must not pass as exact")
    void nearUniqueProfileIsNotAKey() {
        var sv = wrapper("ds", 100_000, List.of(profile("id", 0.99995, 99_995)));
        Statistic stat = new WrapperTable(sv, classOf(sv)).getStatistic();

        assertThat(stat.getKeys()).isEmpty();
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isFalse();
    }

    // ── Naming must match the DDL, or RelToSql emits views that do not exist ──

    @Test
    @DisplayName("wrapper table names match DuckDbExecutor's w_<ds>_<class> convention")
    void viewNameMatchesDdl() {
        var sv = wrapper("ds-1", 10, List.of());
        assertThat(WrapperTable.viewName(sv, classOf(sv))).isEqualTo("u_ds_1_Paper");
    }

    // ── The union now lives in the plan ──────────────────────────────────────

    @Test
    @DisplayName("a multi-source class lowers to a UNION ALL over its members, not an int_ scan")
    void multiSourceClassLowersToUnion() {
        var cls = classOf(wrapper("ds-a", 100, List.of()), wrapper("ds-b", 200, List.of()));
        var catalog = new MappingCatalog(Map.of(PAPER, cls), List.of(), List.of(), "v1");

        String plan = org.apache.calcite.plan.RelOptUtil.toString(
                new OqlPlanner(catalog).lower(
                        edu.upc.dtak.aran.operators.queryengine.oql.OqlParser.parse(
                                "SELECT Paper.paperId")));

        assertThat(plan).contains("LogicalUnion");
        assertThat(plan).contains("u_ds_a_Paper");
        assertThat(plan).contains("u_ds_b_Paper");
        assertThat(plan)
                .as("the opaque integrated-class scan is what hid the union from the cost model")
                .doesNotContain("int_Paper");
    }

    @Test
    @DisplayName("union is ALL, never distinct — set semantics would drop genuine duplicates")
    void unionIsAll() {
        var cls = classOf(wrapper("ds-a", 100, List.of()), wrapper("ds-b", 200, List.of()));
        var catalog = new MappingCatalog(Map.of(PAPER, cls), List.of(), List.of(), "v1");

        String plan = org.apache.calcite.plan.RelOptUtil.toString(
                new OqlPlanner(catalog).lower(
                        edu.upc.dtak.aran.operators.queryengine.oql.OqlParser.parse(
                                "SELECT Paper.paperId")));

        assertThat(plan).contains("LogicalUnion(all=[true])");
    }

    @Test
    @DisplayName("a single-source class stays a bare scan — no Union node, plan shape unchanged")
    void singleSourceClassHasNoUnion() {
        var cls = classOf(wrapper("ds-only", 100, List.of()));
        var catalog = new MappingCatalog(Map.of(PAPER, cls), List.of(), List.of(), "v1");

        String plan = org.apache.calcite.plan.RelOptUtil.toString(
                new OqlPlanner(catalog).lower(
                        edu.upc.dtak.aran.operators.queryengine.oql.OqlParser.parse(
                                "SELECT Paper.paperId")));

        assertThat(plan).doesNotContain("LogicalUnion");
        assertThat(plan).contains("u_ds_only_Paper");
    }

    // ── Referential constraints below the union (Phase 4B, Task 1) ────────────

    private static final String ORDER = NS + "Order";
    private static final String CUSTOMER = NS + "Customer";
    private static final String O_REF = NS + "custRef";
    private static final String C_ID = NS + "id";

    private static MappingCatalog.IntegratedClass order(String datasetId) {
        return new MappingCatalog.IntegratedClass(ORDER, List.of(
                new MappingCatalog.SourceView(datasetId, "SELECT 1", Map.of(O_REF, "cref"))));
    }

    private static MappingCatalog orderCustomer(MappingCatalog.IntegratedClass customer) {
        var order = order("dso");
        return new MappingCatalog(
                Map.of(ORDER, order, CUSTOMER, customer),
                List.of(new MappingCatalog.JoinProperty(NS + "placedBy", ORDER, CUSTOMER, O_REF, C_ID)),
                List.of(), "v1");
    }

    @Test
    @DisplayName("a wrapper exposes a HYPOTHESIS RI to the target's u_ view when the target is single-source")
    void wrapperExposesHypothesisRiToSingleSourceTarget() {
        var customer = new MappingCatalog.IntegratedClass(CUSTOMER, List.of(
                new MappingCatalog.SourceView("dsc", "SELECT 1", Map.of(C_ID, "cid"))));
        Map<String, Table> tables = AranSchema.buildTableMap(orderCustomer(customer));

        assertThat(tables.get("u_dso_Order").getStatistic().getReferentialConstraints())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getSourceQualifiedName()).containsExactly("u_dso_Order");
                    assertThat(c.getTargetQualifiedName())
                            .as("the sound referent is the target's single u_ member")
                            .containsExactly("u_dsc_Customer");
                    assertThat(TieredReferentialConstraint.tierOf(c))
                            .isEqualTo(MappingCatalog.Tier.HYPOTHESIS);
                });
    }

    @Test
    @DisplayName("a wrapper exposes NO RI when the target class unions multiple sources")
    void wrapperExposesNoRiToMultiSourceTarget() {
        // A union member is not a sound referent for a class-level edge: an Order row may reference
        // a Customer in EITHER source, so no single u_ Customer view can carry the constraint.
        // Mirrors the "multi-source class declares no key" safety property this class exists for.
        var customer = new MappingCatalog.IntegratedClass(CUSTOMER, List.of(
                new MappingCatalog.SourceView("dsc1", "SELECT 1", Map.of(C_ID, "cid")),
                new MappingCatalog.SourceView("dsc2", "SELECT 1", Map.of(C_ID, "cid"))));
        Map<String, Table> tables = AranSchema.buildTableMap(orderCustomer(customer));

        assertThat(tables.get("u_dso_Order").getStatistic().getReferentialConstraints())
                .as("no single union member is a sound referent")
                .isEmpty();
        assertThat(tables.get("int_Order").getStatistic().getReferentialConstraints())
                .as("the integrated class still exposes it — int_Customer is the full class view")
                .hasSize(1);
    }

    @Test
    @DisplayName("every union branch is type-aligned on the class's full global column vector")
    void unionBranchesAreTypeAligned() {
        // ds-b supplies only paperId, so title must be NULL-padded for the union to be legal.
        var a = new MappingCatalog.SourceView("ds-a", "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_ID, "id", P_TITLE, "title"), 100, List.of());
        var b = new MappingCatalog.SourceView("ds-b", "SELECT * FROM ${SNAPSHOT}",
                Map.of(P_ID, "id"), 200, List.of());
        var cls = classOf(a, b);
        var catalog = new MappingCatalog(Map.of(PAPER, cls), List.of(), List.of(), "v1");

        // Lowering at all proves the branches are union-compatible: Calcite rejects a
        // LogicalUnion whose inputs have different row types.
        var plan = new OqlPlanner(catalog).lower(
                edu.upc.dtak.aran.operators.queryengine.oql.OqlParser.parse("SELECT Paper.paperId"));

        assertThat(new WrapperTable(b, cls).getRowType(plan.getCluster().getTypeFactory())
                .getFieldNames())
                .as("a source missing a property still exposes the class's full vector")
                .containsExactly(P_ID, P_TITLE);
    }
}

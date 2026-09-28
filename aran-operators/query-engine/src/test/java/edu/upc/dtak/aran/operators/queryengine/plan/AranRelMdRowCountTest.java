package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link AranRelMdRowCount} — the key-aware join-cardinality handler.
 *
 * <p>Calcite's default join estimate is {@code left × right × 0.15} for a single equi-predicate
 * and never consults {@code isKey()}, so a verified unique key on a join column is inert for
 * join ordering. This handler replaces that with the standard many-to-one (PK–FK) estimate for
 * INNER equi-joins where exactly one side's key is unique.
 *
 * <p>Two properties matter and both are asserted here: that the PK–FK estimate fires when it
 * should, and — the safety property the class documents — that every other shape defers to
 * {@link org.apache.calcite.rel.metadata.RelMdUtil#getJoinRowCount}, byte-identical to stock
 * Calcite. A regression that silently drops into the fallback would otherwise be invisible:
 * plans stay valid, they just get worse.
 */
class AranRelMdRowCountTest {

    private static final String NS = "http://example.com/";
    private static final String SRC = NS + "source/";

    private static final String ORDER_IRI = NS + "Order";
    private static final String CUSTOMER_IRI = NS + "Customer";
    private static final String O_CUST_REF = SRC + "orders/customerRef";
    private static final String O_TOTAL = NS + "orderTotal";
    private static final String C_ID = SRC + "customers/id";
    private static final String C_NAME = NS + "customerName";

    /** Calcite's default selectivity for a single equi-predicate. */
    private static final double DEFAULT_EQUI_SELECTIVITY = 0.15;

    private static ColumnProfile profile(String column, double uniqueness) {
        return profile(column, 100L, uniqueness);
    }

    /** As {@link #profile(String, double)}, but with an explicit NDV ({@code cardinality}). */
    private static ColumnProfile profile(String column, long cardinality, double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
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
     * Two single-source classes joined on {@code customerRef → id}.
     *
     * <p>Single-source deliberately: {@link AranStatistic} refuses to declare a key once a class
     * has more than one source, so this is the shape in which the PK–FK path is reachable today.
     *
     * @param customerIdUnique whether the customer side's join column is a verified key
     * @param orderRefUnique   whether the order side's join column is a verified key
     */
    private static MappingCatalog catalog(long orderRows, long customerRows,
                                          boolean orderRefUnique, boolean customerIdUnique) {
        // The "not unique" branch omits the join-key profile entirely rather than attaching one
        // with a low uniqueness: since Task 5, a column's ColumnProfile also carries NDV
        // evidence (cardinality) that AranRelMdRowCount now consults, so a profile with an
        // arbitrary cardinality would no longer be inert — it would feed the NDV branch these
        // tests don't intend to exercise. "No evidence" must mean no profile, matching how
        // AranRelMdSelectivityTest models its own unprofiled column.
        List<ColumnProfile> orderProfiles = orderRefUnique
                ? List.of(profile("customer_ref", 1.0), profile("total", 0.5))
                : List.of(profile("total", 0.5));
        List<ColumnProfile> customerProfiles = customerIdUnique
                ? List.of(profile("id", 1.0), profile("name", 0.5))
                : List.of(profile("name", 0.5));

        var orders = new MappingCatalog.IntegratedClass(ORDER_IRI, List.of(
                new MappingCatalog.SourceView("ds_orders", "SELECT 1",
                        Map.of(O_CUST_REF, "customer_ref", O_TOTAL, "total"),
                        orderRows, orderProfiles)));
        var customers = new MappingCatalog.IntegratedClass(CUSTOMER_IRI, List.of(
                new MappingCatalog.SourceView("ds_customers", "SELECT 1",
                        Map.of(C_ID, "id", C_NAME, "name"),
                        customerRows, customerProfiles)));
        return new MappingCatalog(
                Map.of(ORDER_IRI, orders, CUSTOMER_IRI, customers),
                List.of(new MappingCatalog.JoinProperty(
                        NS + "placedBy", ORDER_IRI, CUSTOMER_IRI, O_CUST_REF, C_ID)),
                List.of(), "v-test");
    }

    /**
     * Same two classes, but instead of a unique/not-unique flag each join key carries an explicit
     * NDV (below the {@code 0.9999} key-uniqueness threshold, so the unique-key branch never
     * fires and the NDV branch is the only path that can produce a non-fallback estimate).
     */
    private static MappingCatalog catalogWithJoinKeyNdv(long orderRows, long customerRows,
                                                        long orderRefNdv, long customerIdNdv) {
        var orders = new MappingCatalog.IntegratedClass(ORDER_IRI, List.of(
                new MappingCatalog.SourceView("ds_orders", "SELECT 1",
                        Map.of(O_CUST_REF, "customer_ref", O_TOTAL, "total"),
                        orderRows,
                        List.of(profile("customer_ref", orderRefNdv, (double) orderRefNdv / orderRows),
                                profile("total", 0.5)))));
        var customers = new MappingCatalog.IntegratedClass(CUSTOMER_IRI, List.of(
                new MappingCatalog.SourceView("ds_customers", "SELECT 1",
                        Map.of(C_ID, "id", C_NAME, "name"),
                        customerRows,
                        List.of(profile("id", customerIdNdv, (double) customerIdNdv / customerRows),
                                profile("name", 0.5)))));
        return new MappingCatalog(
                Map.of(ORDER_IRI, orders, CUSTOMER_IRI, customers),
                List.of(new MappingCatalog.JoinProperty(
                        NS + "placedBy", ORDER_IRI, CUSTOMER_IRI, O_CUST_REF, C_ID)),
                List.of(), "v-test");
    }

    /** Builds {@code Order ⋈ Customer} and returns the handler's row-count estimate. */
    private static double joinRowCount(MappingCatalog catalog, JoinRelType joinType, boolean equi) {
        SchemaPlus schema = AranSchema.create(catalog);
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);

        // The planner installs this on its own cluster; use the same wiring rather than a
        // reconstruction, so the test exercises the provider chain that actually runs.
        OqlPlanner.installKeyAwareMetadata(builder.getCluster());
        return buildOrderCustomerJoin(builder, joinType, equi);
    }

    /**
     * As {@link #joinRowCount}, but with pure stock Calcite metadata — no Aran handler at all,
     * not even {@link AranRelMdRowCount}. The only use is computing the reference value for a
     * byte-identical-fallback assertion, mirroring {@code AranRelMdSelectivityTest}'s
     * {@code stockCalciteBuilder}; this must NOT go through {@link OqlPlanner}'s metadata chain.
     */
    private static double stockJoinRowCount(MappingCatalog catalog, JoinRelType joinType, boolean equi) {
        SchemaPlus schema = AranSchema.create(catalog);
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);

        RelOptCluster cluster = builder.getCluster();
        cluster.setMetadataQuerySupplier(() ->
                new RelMetadataQuery(JaninoRelMetadataProvider.of(DefaultRelMetadataProvider.INSTANCE)));
        cluster.invalidateMetadataQuery();
        return buildOrderCustomerJoin(builder, joinType, equi);
    }

    /** Builds {@code Order ⋈ Customer} on {@code builder}'s cluster and returns its row-count estimate. */
    private static double buildOrderCustomerJoin(RelBuilder builder, JoinRelType joinType, boolean equi) {
        builder.scan("int_Order");
        builder.scan("int_Customer");
        RexNode cond = equi
                ? builder.call(SqlStdOperatorTable.EQUALS,
                        builder.field(2, 0, O_CUST_REF), builder.field(2, 1, C_ID))
                : builder.call(SqlStdOperatorTable.LESS_THAN,
                        builder.field(2, 0, O_CUST_REF), builder.field(2, 1, C_ID));
        builder.join(joinType, cond);
        RelNode join = builder.build();

        RelMetadataQuery mq = join.getCluster().getMetadataQuery();
        Double rc = mq.getRowCount(join);
        assertThat(rc).as("Calcite always returns an estimate").isNotNull();
        return rc;
    }

    // ── The PK–FK estimate fires ─────────────────────────────────────────────

    @Test
    @DisplayName("right key unique, left not → each left row matches at most one right row")
    void manyToOneYieldsLeftRowCount() {
        // 1000 orders, 50 customers, customer.id unique → 1000 rows, not 1000*50*0.15 = 7500.
        assertThat(joinRowCount(catalog(1000, 50, false, true), JoinRelType.INNER, true))
                .isCloseTo(1000.0, within(0.001));
    }

    @Test
    @DisplayName("left key unique, right not → the right side's row count")
    void oneToManyYieldsRightRowCount() {
        assertThat(joinRowCount(catalog(50, 1000, true, false), JoinRelType.INNER, true))
                .isCloseTo(1000.0, within(0.001));
    }

    @Test
    @DisplayName("both keys unique → one-to-one, bounded by the smaller side")
    void oneToOneYieldsMinimum() {
        assertThat(joinRowCount(catalog(1000, 50, true, true), JoinRelType.INNER, true))
                .isCloseTo(50.0, within(0.001));
    }

    @Test
    @DisplayName("the PK–FK estimate is dramatically tighter than the 0.15 default it replaces")
    void pkFkEstimateBeatsTheDefault() {
        double keyed = joinRowCount(catalog(1000, 50, false, true), JoinRelType.INNER, true);
        double unkeyed = joinRowCount(catalog(1000, 50, false, false), JoinRelType.INNER, true);

        assertThat(unkeyed).isCloseTo(1000 * 50 * DEFAULT_EQUI_SELECTIVITY, within(1.0));
        assertThat(keyed).isLessThan(unkeyed);
    }

    // ── The NDV equi-join estimate fires (neither side a verified key) ───────

    @Test
    @DisplayName("single-column join-key NDV: |L|*|R|/max(V(L,k),V(R,k)) replaces the 0.15 default")
    void ndvBasedEquiJoinEstimate() {
        // Two 10,000-row sides; join-key NDVs 100 (orders.customer_ref) and 1000 (customers.id).
        // Neither key is unique (uniqueness 0.01/0.1, both far below the 0.9999 key threshold),
        // so only the new NDV branch — not the PK-FK branches above — can produce this number.
        double ndvEstimate = joinRowCount(
                catalogWithJoinKeyNdv(10_000, 10_000, 100, 1000), JoinRelType.INNER, true);
        double defaultEstimate = 10_000.0 * 10_000.0 * DEFAULT_EQUI_SELECTIVITY;

        assertThat(ndvEstimate)
                .as("10000 * 10000 / max(100, 1000) = 100000")
                .isCloseTo(100_000.0, within(0.001));
        assertThat(defaultEstimate)
                .as("what the 0.15 flat-selectivity default would have guessed instead")
                .isCloseTo(15_000_000.0, within(0.001));
        assertThat(ndvEstimate)
                .as("NDV evidence must tighten the estimate by two orders of magnitude")
                .isLessThan(defaultEstimate);
    }

    @Test
    @DisplayName("no NDV evidence on either join key → byte-identical to the stock Calcite estimate")
    void noNdvEvidenceMatchesDefaultExactly() {
        // Neither "customer_ref" nor "id" carries a ColumnProfile at all in this catalog (see
        // catalog()'s comment), so the NDV branch cannot fire on either side and must fall back —
        // and the fallback must be the exact same computation stock Calcite would have done.
        MappingCatalog noEvidence = catalog(1000, 50, false, false);
        double aranEstimate = joinRowCount(noEvidence, JoinRelType.INNER, true);
        double stockEstimate = stockJoinRowCount(noEvidence, JoinRelType.INNER, true);

        assertThat(aranEstimate)
                .as("no join-key profile exists on either side, so the fallback must be exact,"
                        + " not merely close")
                .isCloseTo(stockEstimate, within(1e-9));
    }

    // ── Phase 4B: exposing referential constraints is estimate-only ──────────

    @Test
    @DisplayName("exposing a HYPOTHESIS referential constraint does not change the join estimate")
    void referentialConstraintsDoNotChangeTheEstimate() {
        // Identical classes, profiles and keys; the ONLY difference is whether the accepted join is
        // in the catalog — i.e. whether int_Order exposes a referential constraint. The join in the
        // plan is built by hand either way, so any difference in the estimate would be the RI's
        // doing. There is none: the metadata chain (RowCount/DistinctRowCount/Selectivity/
        // Predicates/Default) never reads getReferentialConstraints, so a hypothesis edge is
        // estimate-only by construction. This is what makes Phase 4B's strict tier safe today, even
        // before Task 4's elimination rule (which is the only thing that will read the tier) exists.
        MappingCatalog withJoin = catalog(1000, 50, false, true);
        MappingCatalog withoutJoin = new MappingCatalog(
                withJoin.classes(), List.of(), withJoin.objectProperties(), withJoin.schemaVersionId());

        // Premise: the with-join catalog really does expose the constraint, or this proves nothing.
        assertThat(AranSchema.buildTableMap(withJoin).get("int_Order")
                .getStatistic().getReferentialConstraints())
                .as("the accepted join must surface as a referential constraint")
                .isNotEmpty();
        assertThat(AranSchema.buildTableMap(withoutJoin).get("int_Order")
                .getStatistic().getReferentialConstraints())
                .isEmpty();

        assertThat(joinRowCount(withJoin, JoinRelType.INNER, true))
                .as("the estimate is identical with and without the referential constraint")
                .isEqualTo(joinRowCount(withoutJoin, JoinRelType.INNER, true));
    }

    // ── The safety property: everything else defers to Calcite ───────────────

    @Test
    @DisplayName("neither side unique → stock Calcite estimate")
    void neitherUniqueFallsBack() {
        assertThat(joinRowCount(catalog(1000, 50, false, false), JoinRelType.INNER, true))
                .isCloseTo(1000 * 50 * DEFAULT_EQUI_SELECTIVITY, within(1.0));
    }

    @Test
    @DisplayName("a non-equi condition falls back even with a unique key present")
    void nonEquiFallsBack() {
        double withKey = joinRowCount(catalog(1000, 50, false, true), JoinRelType.INNER, false);
        double withoutKey = joinRowCount(catalog(1000, 50, false, false), JoinRelType.INNER, false);
        assertThat(withKey)
                .as("a unique key must not shortcut a range predicate")
                .isCloseTo(withoutKey, within(0.001));
    }

    @Test
    @DisplayName("outer joins fall back — the many-to-one bound does not hold for them")
    void outerJoinsFallBack() {
        for (JoinRelType type : List.of(JoinRelType.LEFT, JoinRelType.RIGHT, JoinRelType.FULL)) {
            double withKey = joinRowCount(catalog(1000, 50, false, true), type, true);
            double withoutKey = joinRowCount(catalog(1000, 50, false, false), type, true);
            assertThat(withKey)
                    .as("%s join must use the default estimate", type)
                    .isCloseTo(withoutKey, within(0.001));
        }
    }

    @Test
    @DisplayName("an unprofiled side falls back rather than guessing a key")
    void unprofiledSideFallsBack() {
        var orders = new MappingCatalog.IntegratedClass(ORDER_IRI, List.of(
                new MappingCatalog.SourceView("ds_orders", "SELECT 1",
                        Map.of(O_CUST_REF, "customer_ref", O_TOTAL, "total"), 1000)));
        var customers = new MappingCatalog.IntegratedClass(CUSTOMER_IRI, List.of(
                new MappingCatalog.SourceView("ds_customers", "SELECT 1",
                        Map.of(C_ID, "id", C_NAME, "name"), 50)));
        var catalog = new MappingCatalog(
                Map.of(ORDER_IRI, orders, CUSTOMER_IRI, customers),
                List.of(new MappingCatalog.JoinProperty(
                        NS + "placedBy", ORDER_IRI, CUSTOMER_IRI, O_CUST_REF, C_ID)),
                List.of(), "v-test");

        assertThat(joinRowCount(catalog, JoinRelType.INNER, true))
                .isCloseTo(1000 * 50 * DEFAULT_EQUI_SELECTIVITY, within(1.0));
    }

    // ── Regression guard for the multi-source gap this work exists to close ──

    @Test
    @DisplayName("a multi-source class cannot supply the key, so the join falls back today")
    void multiSourceClassStillFallsBack() {
        // Documents the limitation option A removes: AranStatistic must refuse to declare a key
        // once a class unions two sources, so the PK–FK path never fires for an integrated
        // class no matter how clean each underlying wrapper is. When the union moves into the
        // plan and joins are pushed below it, this shape becomes reachable again.
        var orders = new MappingCatalog.IntegratedClass(ORDER_IRI, List.of(
                new MappingCatalog.SourceView("ds_orders", "SELECT 1",
                        Map.of(O_CUST_REF, "customer_ref", O_TOTAL, "total"), 1000,
                        List.of(profile("customer_ref", 0.3), profile("total", 0.5)))));
        var customers = new MappingCatalog.IntegratedClass(CUSTOMER_IRI, List.of(
                new MappingCatalog.SourceView("ds_cust_a", "SELECT 1",
                        Map.of(C_ID, "id", C_NAME, "name"), 25,
                        List.of(profile("id", 1.0), profile("name", 0.5))),
                new MappingCatalog.SourceView("ds_cust_b", "SELECT 1",
                        Map.of(C_ID, "id", C_NAME, "name"), 25,
                        List.of(profile("id", 1.0), profile("name", 0.5)))));
        var catalog = new MappingCatalog(
                Map.of(ORDER_IRI, orders, CUSTOMER_IRI, customers),
                List.of(new MappingCatalog.JoinProperty(
                        NS + "placedBy", ORDER_IRI, CUSTOMER_IRI, O_CUST_REF, C_ID)),
                List.of(), "v-test");

        // Both wrappers have a unique id, but the class of 50 rows cannot claim it.
        assertThat(joinRowCount(catalog, JoinRelType.INNER, true))
                .isCloseTo(1000 * 50 * DEFAULT_EQUI_SELECTIVITY, within(1.0));
    }
}

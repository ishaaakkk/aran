package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rules.CoreRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two prerequisites that let logical rewriting move into the {@link VolcanoPlanner} pass, so
 * the {@code HepPlanner} pre-pass could be deleted:
 *
 * <ol>
 *   <li>the planner is emptied of Calcite's default preparation rules before ours are installed;</li>
 *   <li>every logical rewrite is pinned to the {@code Logical*} node classes.</li>
 * </ol>
 *
 * <p>They are not equally load-bearing, and the tests say which is which: (1) is what actually
 * stops the abort that forced the pre-pass to exist, and (2) is a structural guard that is
 * currently redundant. {@code curatedRuleSetAloneIsEnoughToday} states that in the open rather
 * than letting a reader infer the flattering version.
 */
class AranRulesTest {

    private static final String NS = "http://example.com/";
    private static final String SRC = NS + "source/";
    private static final String ORDERS = NS + "integration/Orders";
    private static final String CUSTOMER = NS + "integration/Customer";
    private static final String O_REF = SRC + "orders/customerRef";
    private static final String O_AMT = NS + "integration/amount";
    private static final String C_ID = SRC + "customers/id";
    private static final String C_NAME = NS + "integration/custName";
    private static final String PLACED_BY = NS + "integration/placedBy";

    private static final String OQL =
            "SELECT Orders.amount, Customer.custName FROM Orders -[placedBy]-> Customer";

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static ColumnProfile profile(String column, double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                100L, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    /**
     * {@code orderSources} sources of Orders joined to {@code customerSources} sources of Customer.
     * {@code uniqueCustomerId} decides whether each Customer branch can declare {@code id} a key,
     * which is the only thing transposing the join below the union buys.
     */
    private static MappingCatalog catalog(int orderSources, int customerSources,
                                          boolean uniqueCustomerId) {
        // Neither "customer_ref" nor a not-unique "id" carries a profile at all: since
        // AranRelMdRowCount's NDV branch (Task 5), a profile's cardinality is live NDV evidence,
        // not cosmetic filler, so a fabricated cardinality on a column these tests mean to leave
        // un-keyed would let the NDV branch fire where the "no evidence" premise requires the
        // fallback — the same fix applied to AranRelMdRowCountTest.catalog().
        List<MappingCatalog.SourceView> orders = new ArrayList<>();
        for (int i = 0; i < orderSources; i++) {
            orders.add(new MappingCatalog.SourceView("ord" + i, "SELECT * FROM ${SNAPSHOT}",
                    Map.of(O_REF, "customer_ref", O_AMT, "amount"),
                    1000 / orderSources,
                    List.of(profile("amount", 0.5))));
        }
        List<MappingCatalog.SourceView> customers = new ArrayList<>();
        for (int i = 0; i < customerSources; i++) {
            List<ColumnProfile> customerProfiles = uniqueCustomerId
                    ? List.of(profile("id", 1.0), profile("name", 0.6))
                    : List.of(profile("name", 0.6));
            customers.add(new MappingCatalog.SourceView("cust" + i, "SELECT * FROM ${SNAPSHOT}",
                    Map.of(C_ID, "id", C_NAME, "name"),
                    1000 / customerSources,
                    customerProfiles));
        }
        Map<String, MappingCatalog.IntegratedClass> classes = new LinkedHashMap<>();
        classes.put(ORDERS, new MappingCatalog.IntegratedClass(ORDERS, orders));
        classes.put(CUSTOMER, new MappingCatalog.IntegratedClass(CUSTOMER, customers));
        return new MappingCatalog(classes,
                List.of(new MappingCatalog.JoinProperty(PLACED_BY, ORDERS, CUSTOMER, O_REF, C_ID)),
                List.of(), "v1");
    }

    private static RelNode lower(MappingCatalog catalog) {
        return new OqlPlanner(catalog).lower(OqlParser.parse(OQL));
    }

    /** {@link PlanExecutor#optimize} with a doctored rule set, to mutate one property at a time. */
    private static RelNode optimizeWith(RelNode rel, List<RelOptRule> rules) {
        VolcanoPlanner planner = (VolcanoPlanner) rel.getCluster().getPlanner();
        planner.clear();
        rules.forEach(planner::addRule);
        AranRules.PHYSICAL.forEach(planner::addRule);
        return findBest(rel, planner);
    }

    /**
     * The configuration as it stood before this change: Calcite's default preparation rules left in
     * place, with the stock {@code CoreRules} added on top. No {@code clear()}.
     */
    private static RelNode optimizeAsShipped(RelNode rel) {
        VolcanoPlanner planner = (VolcanoPlanner) rel.getCluster().getPlanner();
        STOCK_LOGICAL.forEach(planner::addRule);
        return findBest(rel, planner);
    }

    private static RelNode findBest(RelNode rel, VolcanoPlanner planner) {
        RelTraitSet desired = rel.getCluster().traitSet().replace(EnumerableConvention.INSTANCE);
        planner.setRoot(planner.changeTraits(rel, desired));
        return planner.findBestExp();
    }

    /** The unrestricted rules {@code PlanExecutor} used to hand to the HepPlanner. */
    private static final List<RelOptRule> STOCK_LOGICAL = List.of(
            CoreRules.FILTER_SET_OP_TRANSPOSE, CoreRules.FILTER_INTO_JOIN,
            CoreRules.FILTER_PROJECT_TRANSPOSE, CoreRules.FILTER_MERGE, CoreRules.PROJECT_MERGE,
            CoreRules.JOIN_COMMUTE,
            CoreRules.JOIN_LEFT_UNION_TRANSPOSE, CoreRules.JOIN_RIGHT_UNION_TRANSPOSE);

    /** {@link AranRules#LOGICAL} with the pinned join-below-union rules swapped for stock ones. */
    private static List<RelOptRule> logicalWithUnrestrictedUnionTranspose() {
        List<RelOptRule> rules = new ArrayList<>(AranRules.LOGICAL.stream()
                .filter(r -> !r.toString().startsWith("JoinUnionTransposeRule")).toList());
        rules.add(CoreRules.JOIN_LEFT_UNION_TRANSPOSE);
        rules.add(CoreRules.JOIN_RIGHT_UNION_TRANSPOSE);
        return rules;
    }

    // ── Phase 4B: exposing referential constraints cannot eliminate a join ───

    @Test
    @DisplayName("the only join-removal rule is the tier-gated AranJoinRemoveRule; no MV or semi-join rule reads referential constraints")
    void theOnlyJoinRemovalRuleIsTheTierGatedAranRule() {
        // Phase 4B surfaces referential edges as Calcite referential constraints. Task 1 kept that
        // estimate-safe by ensuring nothing consumed them; Task 4 adds the one — and only — rule
        // that does. This guard now pins that reality: exactly one join-removal rule is registered,
        // AranJoinRemoveRule, and it refuses to fire on a HYPOTHESIS edge (the trust-tier spine,
        // proved end to end by AranJoinRemoveRuleTest and the corpus differentials). No
        // materialized-view rewrite — Calcite's other getReferentialConstraints consumer — and no
        // semi-join rule is present, so no OTHER rule can act on an edge.
        List<RelOptRule> all = new ArrayList<>(AranRules.LOGICAL);
        all.addAll(AranRules.PHYSICAL);
        List<String> names = all.stream().map(r -> r.getClass().getSimpleName()).toList();
        assertThat(names)
                .as("no MV rewrite and no semi-join rule may read a referential constraint")
                .noneMatch(n -> n.contains("Materialized") || n.contains("SemiJoin"));
        assertThat(names.stream().filter(n -> n.contains("JoinRemove")).toList())
                .as("the sole join-removal rule is the tier-gated Aran one")
                .containsExactly("AranJoinRemoveRule");
    }

    // ── Prerequisite 1: the planner is emptied ───────────────────────────────

    @Test
    @DisplayName("the cluster's planner arrives preloaded with Calcite's default rule set")
    void plannerArrivesPreloaded() {
        VolcanoPlanner planner = (VolcanoPlanner) lower(catalog(1, 2, true)).getCluster().getPlanner();

        // This is why every addRule() call PlanExecutor used to make was a silent no-op: the rule
        // was already registered, and addRule returns false in that case.
        assertThat(planner.getRules())
                .as("RelBuilder.create routes through CalcitePrepareImpl, which registers ~113 rules")
                .hasSizeGreaterThan(100);
        assertThat(planner.addRule(CoreRules.JOIN_COMMUTE))
                .as("JOIN_COMMUTE is already present, so registering it again does nothing")
                .isFalse();
        assertThat(planner.getRules().stream().map(Object::toString))
                .contains("EnumerableMergeJoinRule(in:NONE,out:ENUMERABLE)", "FilterIntoJoinRule");
    }

    @Test
    @DisplayName("install() leaves exactly the curated rules behind")
    void installCuratesTheRuleSet() {
        VolcanoPlanner planner = (VolcanoPlanner) lower(catalog(1, 2, true)).getCluster().getPlanner();
        AranRules.install(planner);

        assertThat(planner.getRules())
                .hasSize(AranRules.LOGICAL.size() + AranRules.PHYSICAL.size());
        assertThat(planner.getRules().stream().map(Object::toString))
                .as("the default preparation set must be gone, not merely added to")
                .noneMatch(d -> d.startsWith("MaterializedView")
                        || d.startsWith("Delta")
                        || d.startsWith("Bindable")
                        || d.startsWith("SemiJoin")
                        || d.startsWith("EnumerableMergeJoinRule"));
    }

    @Test
    @DisplayName("dropping merge join removes the collation-only sorts from the emitted SQL")
    void noSpuriousOrderBy() {
        // EnumerableMergeJoinRule wins on cost for this shape and demands sorted inputs. Those
        // EnumerableSorts survive toLogical() as LogicalSorts, and RelToSqlConverter unparses them
        // as ORDER BY inside subqueries — sorts DuckDB then performs for a query that asked for
        // none. Registering the rule set ourselves is what excludes it.
        String sql = PlanExecutor.toSql(lower(catalog(1, 2, true)));
        assertThat(sql)
                .as("the OQL has no ORDER BY, so neither should the SQL:\n%s", sql)
                .doesNotContain("ORDER BY");

        String withMergeJoin = PlanExecutor.planString(optimizeWith(lower(catalog(1, 2, true)),
                java.util.stream.Stream.concat(
                        AranRules.LOGICAL.stream(),
                        java.util.stream.Stream.of(
                                org.apache.calcite.adapter.enumerable.EnumerableRules
                                        .ENUMERABLE_MERGE_JOIN_RULE)).toList()));
        assertThat(withMergeJoin)
                .as("mutation check — put merge join back and the sorts return:\n%s", withMergeJoin)
                .contains("Sort");
    }

    // ── Prerequisite 2: logical rewrites see only logical nodes ──────────────

    @Test
    @DisplayName("the shipped configuration really does abort — this is why Hep existed")
    void stockConfigurationAbortsPlanning() {
        // The pre-change setup exactly: default preparation rules plus stock CoreRules, no clear().
        // VolcanoPlanner refuses to *seed* a transformation rule from a physical node, but
        // JOIN_*_UNION_TRANSPOSE has a bare RelNode.class input operand. A match seeded there — on
        // the stock set, at an AbstractConverter carrying the collation EnumerableMergeJoinRule
        // asked for — walks up to its parent, and RelOptRuleOperand.matches applies no convention
        // test on the way. The parent is an EnumerableHashJoin over an EnumerableUnion; union.copy()
        // reproduces the physical trait, and VolcanoRuleCall.transformTo rejects it.
        assertThatThrownBy(() -> optimizeAsShipped(lower(catalog(1, 2, true))))
                .rootCause()
                .hasMessageContaining("is a PhysicalNode, which is not allowed");
    }

    @Test
    @DisplayName("but it is the curated rule set, not the operand pinning, that fixes it today")
    void curatedRuleSetAloneIsEnoughToday() {
        // Worth stating plainly, because the reverse is easy to assume. Drop merge join and nothing
        // requests a collation, so no AbstractConverter is created, so nothing seeds the walk
        // described above — and the unrestricted rules plan without complaint.
        //
        // The Logical* pinning in AranRules.LOGICAL is therefore not what makes the single pass
        // work; it makes it *stay* working. "No converter happens to be created" is a property of
        // this rule set and these query shapes, not an invariant, and the failure mode it guards
        // against aborts a user's query rather than degrading it.
        RelNode planned = optimizeWith(lower(catalog(1, 2, true)),
                logicalWithUnrestrictedUnionTranspose());

        assertThat(PlanExecutor.planString(planned)).contains("Join").contains("Union");
    }

    @Test
    @DisplayName("pinned to LogicalJoin/LogicalUnion, the same rewrite plans fine and fires")
    void restrictedRulePlansInTheSamePass() {
        String plan = PlanExecutor.planString(PlanExecutor.optimize(lower(catalog(1, 2, true))));

        int union = plan.indexOf("Union");
        int join = plan.indexOf("Join");
        assertThat(union).as("a two-source class still unions:\n%s", plan).isGreaterThanOrEqualTo(0);
        assertThat(join).as("the query joins two classes:\n%s", plan).isGreaterThanOrEqualTo(0);
        assertThat(union)
                .as("with unique keys per branch, transposing wins on cost:\n%s", plan)
                .isLessThan(join);
    }

    // ── What deleting the pre-pass bought: transposition is now priced ───────

    @Test
    @DisplayName("without per-branch keys the same rewrite is rejected on cost")
    void transpositionIsRejectedWhenItDoesNotPay() {
        // Identical shape and identical row counts to the test above — only the uniqueness of
        // Customer.id differs. Without it AranRelMdRowCount's many-to-one estimate cannot fire on
        // the branches, so the N joins transposition creates cost more than the one it replaces.
        // Under the HepPlanner pre-pass this rewrite fired whenever it matched, and the only brake
        // was a hardcoded cap on the branch count.
        String plan = PlanExecutor.planString(PlanExecutor.optimize(lower(catalog(1, 2, false))));

        assertThat(plan.indexOf("Join"))
                .as("the join should stay above the union when transposing does not pay:\n%s", plan)
                .isLessThan(plan.indexOf("Union"));
    }

    @ParameterizedTest(name = "{0}x{0} sources")
    @ValueSource(ints = {2, 4, 6})
    @DisplayName("a wide class pair transposes only the side that has a key")
    void wideClassPairsTransposeOnlyTheKeyedSide(int sources) {
        // MAX_TRANSPOSED_JOIN_BRANCHES = 9 existed because the pre-pass applied the rewrite
        // unconditionally: LEFT and RIGHT both run to fixpoint, so N sources joined to N sources
        // became N² joins, and something had to stop it. The cap's effect at 4x4 and 6x6 was to
        // forbid transposition altogether.
        //
        // Priced instead of forced, the answer is neither N² nor 1. Only Customer declares a unique
        // key, so only the Customer union is worth pushing the join below — N joins. Pushing the
        // Orders union down as well buys no better estimate and costs another N× fan-out, so it is
        // not chosen. That is the decision the cap was standing in for.
        long start = System.nanoTime();
        String plan = PlanExecutor.planString(
                PlanExecutor.optimize(lower(catalog(sources, sources, true))));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(plan.split("Join", -1).length - 1)
                .as("expected %d joins (keyed side only), not %d (both sides):%n%s",
                        sources, sources * sources, plan)
                .isEqualTo(sources);
        assertThat(plan.indexOf("Union"))
                .as("the surviving union sits above the joins:%n%s", plan)
                .isLessThan(plan.indexOf("Join"));
        assertThat(millis)
                .as("planning %dx%d took %d ms", sources, sources, millis)
                .isLessThan(10_000);
    }
}

package edu.upc.dtak.aran.operators.queryengine.plan;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 4B Task 3 — the deliverable differential: a federated relational dataset's row count
 * moving from "today's fixed guess" (unprofiled — {@code SourceView.rowCount = 0}, Calcite's own
 * default estimate) to a real harvested value (via {@link HarvestedColumnProfiles}, the {@code
 * ColumnProfile(subject=HARVESTED)} path {@code ProfileStatsSource}/{@code MappingCatalogOperator}
 * thread with zero planner changes) must reach cost-relevant planning metadata, not just sit in
 * the catalog as an inert number.
 *
 * <p><b>Corrected from an earlier version of this test (Phase 4B fix round).</b> A first draft
 * asserted that harvested stats flip which side of a 2-way join becomes {@code inputs().get(0)}
 * ("the build side"). That assertion was passing for the wrong reason: a since-fixed bug let a
 * {@code HARVESTED} profile's approximate (pg_stats-derived) uniqueness cross the exact-key
 * threshold in {@code WrapperStatistic.isSingleColumnKey}, which put {@code AranRelMdRowCount}
 * onto its one-sided "referenced key" branch instead of the symmetric default formula — and it was
 * *that* asymmetry, not a genuine reordering mechanism, that happened to change which physical plan
 * Volcano preferred. Once the key-detection bug was fixed (HARVESTED profiles are never eligible as
 * keys — see {@code AranStatisticTest}/{@code WrapperStatisticTest}), the flip stopped happening.
 * The right signal to assert on is the join's row-count <em>estimate</em>, not which input becomes
 * {@code inputs().get(0)}: {@code JOIN_COMMUTE} and {@code JoinPushThroughJoinRule.LEFT/RIGHT} ARE
 * registered in {@code AranRules}, so which physical input lands on the build side genuinely can be
 * commuted — but a join's {@code getRowCount} is commute-invariant (the cardinality formula is
 * symmetric in its two inputs), so the estimate is a stable function of the harvested row count
 * regardless of orientation, where "which side is get(0)" is not.
 *
 * <p>What this file proves instead, verified directly by dumping {@link PlanExecutor#planString}
 * for both catalogs before writing the assertion below (not assumed): the join's <em>own</em>
 * {@code RelMetadataQuery.getRowCount} estimate — the number anything layered above the join
 * (a further join, a sort, a limit) would actually plan against — scales with the harvested row
 * count. Observed concretely: {@code rowcount = 1.5E7} (guess: Calcite's ~100 default × Fixed's
 * 1,000,000 × 0.15 selectivity) versus {@code rowcount = 1.5E12} (harvested: real 10,000,000 ×
 * 1,000,000 × 0.15) — a ~100,000× increase, exactly the harvested/guess row-count ratio.
 */
class HarvestedStatsPlanShapeTest {

    private static final String NS = "http://example.com/";
    private static final String FIXED = NS + "Fixed";
    private static final String FEDERATED = NS + "Federated";
    private static final String FIXED_KEY = NS + "fixedKey";
    private static final String FEDERATED_KEY = NS + "federatedKey";
    private static final String JOINS_TO = NS + "joinsTo";

    private static final UUID FEDERATED_DATASET_ID = UUID.randomUUID();
    private static final String FEDERATED_TABLE = "remote_table";

    /** An ordinary, always-profiled class: 1,000,000 rows, unrelated to any harvest. */
    private static MappingCatalog.IntegratedClass fixedClass() {
        return new MappingCatalog.IntegratedClass(FIXED, List.of(
                new MappingCatalog.SourceView("ds_fixed", "SELECT 1",
                        Map.of(FIXED_KEY, "id"), 1_000_000L)));
    }

    /** Today's shape for a federated relational dataset with no harvest: unprofiled. */
    private static MappingCatalog guessCatalog() {
        var federated = new MappingCatalog.IntegratedClass(FEDERATED, List.of(
                new MappingCatalog.SourceView("ds_federated", "SELECT 1",
                        Map.of(FEDERATED_KEY, "id")))); // rowCount defaults to 0 — unprofiled
        var join = new MappingCatalog.JoinProperty(JOINS_TO, FEDERATED, FIXED, FEDERATED_KEY, FIXED_KEY);
        return new MappingCatalog(Map.of(FEDERATED, federated, FIXED, fixedClass()),
                List.of(join), List.of(), "v-guess");
    }

    /** Task 3's shape: the same federated dataset, now carrying HARVESTED stats. */
    private static MappingCatalog harvestedCatalog() {
        Harvest harvest = new Harvest(
                Map.of(FEDERATED_TABLE, 10_000_000L),
                Map.of(new Harvest.ColKey(FEDERATED_TABLE, "id"), 10_000_000L),
                List.of());
        List<ColumnProfile> profiles =
                HarvestedColumnProfiles.forTable(harvest, FEDERATED_DATASET_ID, FEDERATED_TABLE);
        assertThat(profiles).as("fixture sanity: the harvest must actually produce a profile").isNotEmpty();
        long rowCount = ProfileStatsSource.estimateRowCount(profiles);

        var federated = new MappingCatalog.IntegratedClass(FEDERATED, List.of(
                new MappingCatalog.SourceView("ds_federated", "SELECT 1",
                        Map.of(FEDERATED_KEY, "id"), rowCount, profiles)));
        var join = new MappingCatalog.JoinProperty(JOINS_TO, FEDERATED, FIXED, FEDERATED_KEY, FIXED_KEY);
        return new MappingCatalog(Map.of(FEDERATED, federated, FIXED, fixedClass()),
                List.of(join), List.of(), "v-harvested");
    }

    private static RelNode optimized(MappingCatalog cat) {
        String oql = "SELECT Federated.federatedKey, Fixed.fixedKey FROM Federated -[joinsTo]-> Fixed";
        return PlanExecutor.optimize(new OqlPlanner(cat).lower(OqlParser.parse(oql)));
    }

    /** Walks the optimized tree to find the (single) {@link Join} node — see the class javadoc. */
    private static Join theJoinNode(RelNode rel) {
        if (rel instanceof Join join) {
            return join;
        }
        for (RelNode input : rel.getInputs()) {
            Join hit = theJoinNode(input);
            if (hit != null) {
                return hit;
            }
        }
        throw new AssertionError("no Join node in plan: " + PlanExecutor.planString(rel));
    }

    @Test
    @DisplayName("harvested stats scale the join's own cost-relevant row-count estimate by the harvested/guess ratio")
    void harvestedStatsScaleTheJoinsCostEstimate() {
        Join guessJoin = theJoinNode(optimized(guessCatalog()));
        Join harvestedJoin = theJoinNode(optimized(harvestedCatalog()));

        Double guessEstimate = guessJoin.getCluster().getMetadataQuery().getRowCount(guessJoin);
        Double harvestedEstimate = harvestedJoin.getCluster().getMetadataQuery().getRowCount(harvestedJoin);

        assertThat(guessEstimate).as("guess join row-count estimate").isNotNull();
        assertThat(harvestedEstimate).as("harvested join row-count estimate").isNotNull();
        // Harvested Federated (10,000,000) replaces Calcite's unprofiled default (~100) as the
        // join formula's only varying factor (Fixed's 1,000,000 and the 0.15 equi-join selectivity
        // are identical in both estimates), so the ratio isolates exactly the harvested-vs-guess
        // row-count difference. Verified concretely while diagnosing this test (see class javadoc):
        // 1.5E7 -> 1.5E12, a ~100,000x increase. The >1000x floor asserted here is a wide,
        // version-safe margin under that observed ratio — not a threshold tuned to the exact number.
        assertThat(harvestedEstimate / guessEstimate)
                .as("harvested=%s guess=%s", harvestedEstimate, guessEstimate)
                .isGreaterThan(1000.0);
    }

    @Test
    @DisplayName("the harvested row count itself reaches RelMetadataQuery, not just the plan shape")
    void harvestedRowCountReachesMetadataQuery() {
        MappingCatalog harvested = harvestedCatalog();
        var schema = AranSchema.create(harvested);
        var config = org.apache.calcite.tools.Frameworks.newConfigBuilder().defaultSchema(schema).build();
        var builder = org.apache.calcite.tools.RelBuilder.create(config);
        builder.scan("int_Federated");
        RelNode scan = builder.build();

        Double rowCount = scan.getCluster().getMetadataQuery().getRowCount(scan);
        assertThat(rowCount)
                .as("the harvested 10,000,000 must reach the planner's own row-count metadata, not"
                        + " just this test's private estimateRowCount computation")
                .isNotNull()
                .isGreaterThan(1_000_000.0); // definitively past Calcite's unprofiled default and Fixed's count
    }
}

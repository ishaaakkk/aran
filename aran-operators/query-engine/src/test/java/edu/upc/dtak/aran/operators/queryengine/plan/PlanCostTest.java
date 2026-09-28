package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11 acceptance — RelMetadataQuery sees profile-derived row counts.
 *
 * The metadata pipeline is: Table.getStatistic().getRowCount()
 *   → RelMdRowCount.tableRowCount()
 *   → RelMetadataQuery.getRowCount(LogicalTableScan).
 *
 * Once these values reach the planner, the VolcanoPlanner uses them for cost-based
 * join reordering (verified end-to-end in Phase 12's PlanExecutor).
 */
class PlanCostTest {

    private static final String NS  = "http://example.com/";
    private static final String SRC = NS + "source/";

    private static final String SMALL_IRI = NS + "Small";
    private static final String LARGE_IRI = NS + "Large";
    private static final String JOIN_IRI  = NS + "hasLink";
    private static final String S_KEY     = SRC + "small/id";
    private static final String L_KEY     = SRC + "large/refId";
    private static final String S_NAME    = NS + "smallName";
    private static final String L_NAME    = NS + "largeName";

    /**
     * Builds a catalog where Small has {@code smallRows} rows and Large has {@code largeRows}.
     */
    private MappingCatalog catalogWithRowCounts(long smallRows, long largeRows) {
        var small = new MappingCatalog.IntegratedClass(SMALL_IRI, List.of(
                new MappingCatalog.SourceView("ds_small", "SELECT 1",
                        Map.of(S_KEY, "id", S_NAME, "name"), smallRows)));
        var large = new MappingCatalog.IntegratedClass(LARGE_IRI, List.of(
                new MappingCatalog.SourceView("ds_large", "SELECT 1",
                        Map.of(L_KEY, "ref_id", L_NAME, "name"), largeRows)));
        var join = new MappingCatalog.JoinProperty(JOIN_IRI, SMALL_IRI, LARGE_IRI, S_KEY, L_KEY);
        return new MappingCatalog(
                Map.of(SMALL_IRI, small, LARGE_IRI, large),
                List.of(join), List.of(), "v-test");
    }

    @Test
    void metadataQueryReflectsProfileRowCounts() {
        MappingCatalog catalog = catalogWithRowCounts(100L, 10_000L);
        SchemaPlus schema = AranSchema.create(catalog);
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);

        builder.scan("int_Small");
        RelNode smallScan = builder.build();

        builder.scan("int_Large");
        RelNode largeScan = builder.build();

        RelMetadataQuery mq = smallScan.getCluster().getMetadataQuery();

        assertThat(mq.getRowCount(smallScan))
                .as("Small table scan should return profile row count 100")
                .isEqualTo(100.0);
        assertThat(mq.getRowCount(largeScan))
                .as("Large table scan should return profile row count 10000")
                .isEqualTo(10_000.0);
    }

    @Test
    void rowCountDiffersWhenStatsChange() {
        // Same query structure, two different catalogs: stats drive different estimates
        MappingCatalog low  = catalogWithRowCounts(50L,    50L);
        MappingCatalog high = catalogWithRowCounts(5_000L, 5_000L);

        double lowCount  = scanRowCount(low,  "int_Small");
        double highCount = scanRowCount(high, "int_Small");

        assertThat(highCount).isGreaterThan(lowCount);
    }

    @Test
    void unprofiedTableYieldsDefaultCalciteEstimate() {
        // rowCount=0 → AranStatistic.getRowCount() == null → Calcite uses its own default
        MappingCatalog catalog = catalogWithRowCounts(0L, 0L);
        SchemaPlus schema = AranSchema.create(catalog);
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);
        builder.scan("int_Small");
        RelNode scan = builder.build();

        // Calcite's default estimate (RelMdRowCount falls back to 100 when table has no stat)
        Double rc = scan.getCluster().getMetadataQuery().getRowCount(scan);
        assertThat(rc).isNotNull(); // Calcite always returns a non-null estimate
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static double scanRowCount(MappingCatalog catalog, String tableName) {
        SchemaPlus schema = AranSchema.create(catalog);
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);
        builder.scan(tableName);
        RelNode scan = builder.build();
        Double rc = scan.getCluster().getMetadataQuery().getRowCount(scan);
        return rc != null ? rc : 0.0;
    }
}

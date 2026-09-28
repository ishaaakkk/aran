package edu.upc.dtak.aran.operators.queryengine.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The physical-placement decision (Option A): per dual-access leaf, snapshot vs live, priced by
 * transfer-aware cost over the plan's own pushed predicates — the A3 property under test here is
 * that <b>the same dataset flips modes purely on the query's selectivity</b>, read through the
 * real Aran metadata chain ({@code OqlPlanner.installKeyAwareMetadata}), not through numbers this
 * test invents.
 */
class PlacementDeciderTest {

    private static final String NS = "http://example.com/";
    private static final String ITEM_IRI = NS + "Item";
    private static final String ID = NS + "id";
    private static final String NAME = NS + "name";
    private static final String DS = "ds_items";
    private static final String U_VIEW = "u_ds_items_Item";

    private static ColumnProfile profile(String column, long cardinality) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                cardinality, null, 0.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    /** 100k rows; id has NDV 10k, so an equality keeps ~10 rows — far under 1/25th. */
    private static MappingCatalog catalog() {
        var items = new MappingCatalog.IntegratedClass(ITEM_IRI, List.of(
                new MappingCatalog.SourceView(DS, "SELECT 1",
                        Map.of(ID, "id", NAME, "name"),
                        100_000L,
                        List.of(profile("id", 10_000L)))));
        return new MappingCatalog(Map.of(ITEM_IRI, items), List.of(), List.of(), "v-test");
    }

    private static RelBuilder aranBuilder() {
        SchemaPlus schema = AranSchema.create(catalog());
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);
        OqlPlanner.installKeyAwareMetadata(builder.getCluster());
        return builder;
    }

    private static Map<String, PlacementDecider.Candidate> legal() {
        return Map.of(DS, new PlacementDecider.Candidate(DS, true, "copy maintained"));
    }

    @Test
    @DisplayName("A3: the same leaf flips FEDERATED↔MATERIALIZED purely on the plan's pushed predicate")
    void selectivityFlipsThePlacement() {
        // Selective: Filter(id = 'k') over the member scan — effRows = 100k/10k = 10 rows,
        // live cost 25×10=250 beats snapshot 1×100k.
        RelBuilder b = aranBuilder();
        b.scan(U_VIEW);
        b.filter(b.equals(b.field(ID), b.literal("k")));
        RelNode selective = b.build();
        Map<String, PlacementDecider.Decision> d1 =
                PlacementDecider.decide(selective, legal(), 25.0, 1.0);
        assertThat(d1).containsKey(DS);
        assertThat(d1.get(DS).chosen()).isEqualTo("FEDERATED");
        assertThat(d1.get(DS).effectiveRows()).isCloseTo(10.0, within(0.001));
        assertThat(d1.get(DS).fullRows()).isCloseTo(100_000.0, within(0.001));
        assertThat(d1.get(DS).reason()).contains("live wins on cost");

        // Full scan: same leaf, no predicate — live cost 25×100k loses to snapshot 1×100k.
        RelBuilder b2 = aranBuilder();
        b2.scan(U_VIEW);
        RelNode full = b2.build();
        Map<String, PlacementDecider.Decision> d2 =
                PlacementDecider.decide(full, legal(), 25.0, 1.0);
        assertThat(d2.get(DS).chosen()).isEqualTo("MATERIALIZED");
        assertThat(d2.get(DS).reason()).contains("snapshot wins on cost");
    }

    @Test
    @DisplayName("a Project in the leaf chain does not break effective-row accounting")
    void projectChainsThroughToTheLeaf() {
        RelBuilder b = aranBuilder();
        b.scan(U_VIEW);
        b.filter(b.equals(b.field(ID), b.literal("k")));
        b.project(b.field(NAME));
        RelNode plan = b.build();
        Map<String, PlacementDecider.Decision> d =
                PlacementDecider.decide(plan, legal(), 25.0, 1.0);
        assertThat(d.get(DS).chosen()).isEqualTo("FEDERATED");
        assertThat(d.get(DS).effectiveRows()).isCloseTo(10.0, within(0.001));
    }

    @Test
    @DisplayName("an illegal snapshot leaf forces FEDERATED, whatever the cost says")
    void illegalSnapshotForcesLive() {
        RelBuilder b = aranBuilder();
        b.scan(U_VIEW); // full scan — cost alone would pick MATERIALIZED
        Map<String, PlacementDecider.Decision> d = PlacementDecider.decide(b.build(),
                Map.of(DS, new PlacementDecider.Candidate(DS, false, "no Every policy")),
                25.0, 1.0);
        assertThat(d.get(DS).chosen()).isEqualTo("FEDERATED");
        assertThat(d.get(DS).reason()).contains("snapshot leaf illegal").contains("no Every policy");
    }

    @Test
    @DisplayName("every occurrence of the leaf transfers: a UNION of two scans sums both sides")
    void repeatedLeavesSum() {
        RelBuilder b = aranBuilder();
        b.scan(U_VIEW);
        b.scan(U_VIEW);
        b.union(true, 2);
        Map<String, PlacementDecider.Decision> d =
                PlacementDecider.decide(b.build(), legal(), 25.0, 1.0);
        assertThat(d.get(DS).fullRows()).isCloseTo(200_000.0, within(0.001));
        assertThat(d.get(DS).effectiveRows()).isCloseTo(200_000.0, within(0.001));
    }

    @Test
    @DisplayName("leafDatasets reports exactly the plan's datasets — the operator probes only these")
    void leafDatasetsAreThePlansOwn() {
        RelBuilder b = aranBuilder();
        b.scan(U_VIEW);
        b.filter(b.equals(b.field(ID), b.literal("k")));
        assertThat(PlacementDecider.leafDatasets(b.build())).containsExactly(DS);
    }

    @Test
    @DisplayName("the OQL planner installs AranCost.FACTORY — a silent revert to RelOptCostImpl fails here")
    void oqlPlannerInstallsAranCost() {
        RelNode rel = new OqlPlanner(catalog(), Map.of())
                .lower(edu.upc.dtak.aran.operators.queryengine.oql.OqlParser.parse("SELECT Item.name"));
        assertThat(rel.getCluster().getPlanner().getCostFactory()).isSameAs(AranCost.FACTORY);
    }

    @Test
    @DisplayName("non-candidate datasets get no decision; empty candidates decide nothing")
    void nonCandidatesUntouched() {
        RelBuilder b = aranBuilder();
        b.scan(U_VIEW);
        RelNode plan = b.build();
        assertThat(PlacementDecider.decide(plan, Map.of(), 25.0, 1.0)).isEmpty();
        assertThat(PlacementDecider.decide(plan,
                Map.of("other_ds", new PlacementDecider.Candidate("other_ds", true, "ok")),
                25.0, 1.0)).isEmpty();
    }
}

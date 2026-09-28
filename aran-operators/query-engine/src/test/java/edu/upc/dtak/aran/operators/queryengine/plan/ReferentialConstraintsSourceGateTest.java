package edu.upc.dtak.aran.operators.queryengine.plan;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.IntegratedClass;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.JoinProperty;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.ReferentialEdge;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.SourceView;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 4B review Important 2 — the source-side single-source gate in {@link ReferentialConstraints}.
 *
 * <p>A {@code DECLARED} referential edge is attributed to an <em>integrated</em> class, but a
 * harvested foreign key was declared on exactly one source dataset. If that integrated class has
 * more than one source, the edge would falsely assert that the co-source's rows also satisfy the FK
 * — and {@code AranJoinRemoveRule} trusts a {@code DECLARED} edge specifically to skip re-verifying
 * it. This pins that such an edge is <b>never exposed</b> for a multi-source referencing class, at
 * both GAV levels, so the unsoundness cannot reach the elimination rule structurally — not merely
 * because today's plan shape (a multi-source class unfolds to a UNION) happens to dodge the rule's
 * match. The gate is tier-aware: a {@code HYPOTHESIS} edge (estimate-only, the rule refuses it) is
 * unaffected, which is what keeps every existing golden plan byte-identical.
 */
class ReferentialConstraintsSourceGateTest {

    private static final String NS = "http://ex/";
    private static final String ORDER = NS + "Order";
    private static final String CUSTOMER = NS + "Customer";
    private static final String O_REF = NS + "custRef";
    private static final String O_AMT = NS + "amount";
    private static final String C_ID = NS + "id";
    private static final String C_NAME = NS + "name";

    private static SourceView orderSource(String datasetId) {
        return new SourceView(datasetId, "SELECT 1", Map.of(O_REF, "cref", O_AMT, "amt"));
    }

    /** {@code sources} referencing-side sources; Customer is always single-source. */
    private static MappingCatalog catalog(List<SourceView> orderSources) {
        var order = new IntegratedClass(ORDER, orderSources);
        var customer = new IntegratedClass(CUSTOMER, List.of(
                new SourceView("dsc", "SELECT 1", Map.of(C_ID, "cid", C_NAME, "cname"))));
        var join = new JoinProperty(NS + "placedBy", ORDER, CUSTOMER, O_REF, C_ID);      // → HYPOTHESIS
        var declared = new ReferentialEdge(ORDER, List.of(O_REF), CUSTOMER, List.of(C_ID), Tier.DECLARED);
        return new MappingCatalog(Map.of(ORDER, order, CUSTOMER, customer),
                List.of(join), List.of(), List.of(declared), "v-test");
    }

    private static List<Tier> tiers(List<RelReferentialConstraint> constraints) {
        return constraints.stream().map(TieredReferentialConstraint::tierOf).toList();
    }

    @Test
    @DisplayName("int_: a multi-source referencing class exposes the HYPOTHESIS edge but NOT the DECLARED one")
    void multiSourceReferencingClassHidesDeclaredEdgeAtIntLevel() {
        MappingCatalog catalog = catalog(List.of(orderSource("dso1"), orderSource("dso2")));
        List<RelReferentialConstraint> constraints =
                ReferentialConstraints.forIntegratedClass(catalog, catalog.classes().get(ORDER));

        assertThat(tiers(constraints))
                .as("only the estimate-only HYPOTHESIS edge survives; the DECLARED edge is gated out")
                .containsExactly(Tier.HYPOTHESIS);
    }

    @Test
    @DisplayName("u_ member: a multi-source referencing class exposes no DECLARED edge for any member either")
    void multiSourceReferencingClassHidesDeclaredEdgeAtWrapperMemberLevel() {
        MappingCatalog catalog = catalog(List.of(orderSource("dso1"), orderSource("dso2")));
        IntegratedClass order = catalog.classes().get(ORDER);
        for (SourceView member : order.sources()) {
            assertThat(tiers(ReferentialConstraints.forWrapperMember(catalog, order, member)))
                    .as("member %s must carry no DECLARED constraint", member.datasetId())
                    .doesNotContain(Tier.DECLARED);
        }
    }

    @Test
    @DisplayName("control: a SINGLE-source referencing class DOES expose the DECLARED edge (the gate only tightens multi-source)")
    void singleSourceReferencingClassStillExposesDeclaredEdge() {
        MappingCatalog catalog = catalog(List.of(orderSource("dso1")));
        List<RelReferentialConstraint> constraints =
                ReferentialConstraints.forIntegratedClass(catalog, catalog.classes().get(ORDER));

        assertThat(tiers(constraints))
                .as("single-source: both the accepted-join HYPOTHESIS and the harvested DECLARED edge are exposed")
                .containsExactlyInAnyOrder(Tier.HYPOTHESIS, Tier.DECLARED);
    }
}

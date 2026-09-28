package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.ReferentialEdge;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4B, Task 1 (domain layer): {@link MappingCatalog#referentialEdges()} derives one
 * {@code HYPOTHESIS}-tier {@link ReferentialEdge} per accepted {@link MappingCatalog.JoinProperty}.
 *
 * <p>The trust tier is the spine of this phase: an accepted join is a <em>hypothesis</em>, so the
 * edge it produces must be tagged {@code HYPOTHESIS} and nothing here may mint a {@code DECLARED}
 * or {@code VERIFIED} one from a join. {@code DECLARED} edges arrive in Task 3 from a different
 * source; {@code referentialEdges()} is left open to concatenate them.
 */
class MappingCatalogReferentialEdgeTest {

    private static final String NS = "http://example.com/";
    private static final String ORDER = NS + "Order";
    private static final String CUSTOMER = NS + "Customer";
    private static final String O_REF = NS + "custRef";
    private static final String C_ID = NS + "id";

    private static MappingCatalog catalogWithJoins(MappingCatalog.JoinProperty... joins) {
        var order = new MappingCatalog.IntegratedClass(ORDER, List.of(
                new MappingCatalog.SourceView("ds_o", "SELECT 1", Map.of(O_REF, "cref"))));
        var customer = new MappingCatalog.IntegratedClass(CUSTOMER, List.of(
                new MappingCatalog.SourceView("ds_c", "SELECT 1", Map.of(C_ID, "cid"))));
        return new MappingCatalog(
                Map.of(ORDER, order, CUSTOMER, customer),
                List.of(joins), List.of(), "v-test");
    }

    @Test
    @DisplayName("one HYPOTHESIS edge per accepted JoinProperty, carrying its classes and columns")
    void derivesOneHypothesisEdgePerJoinProperty() {
        MappingCatalog catalog = catalogWithJoins(
                new MappingCatalog.JoinProperty(NS + "placedBy", ORDER, CUSTOMER, O_REF, C_ID));

        assertThat(catalog.referentialEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.leftClass()).isEqualTo(ORDER);
            assertThat(edge.rightClass()).isEqualTo(CUSTOMER);
            assertThat(edge.leftCols()).containsExactly(O_REF);
            assertThat(edge.rightCols()).containsExactly(C_ID);
            assertThat(edge.tier())
                    .as("an accepted join is a hypothesis, never DECLARED/VERIFIED")
                    .isEqualTo(Tier.HYPOTHESIS);
        });
    }

    @Test
    @DisplayName("no accepted joins → no referential edges (Task 3 will add DECLARED ones)")
    void emptyWhenNoJoins() {
        assertThat(catalogWithJoins().referentialEdges()).isEmpty();
    }

    @Test
    @DisplayName("one edge per join, order preserved, every one HYPOTHESIS")
    void oneEdgePerJoinAllHypothesis() {
        MappingCatalog catalog = catalogWithJoins(
                new MappingCatalog.JoinProperty(NS + "j1", ORDER, CUSTOMER, O_REF, C_ID),
                new MappingCatalog.JoinProperty(NS + "j2", CUSTOMER, ORDER, C_ID, O_REF));

        assertThat(catalog.referentialEdges()).hasSize(2)
                .allSatisfy(e -> assertThat(e.tier()).isEqualTo(Tier.HYPOTHESIS));
        assertThat(catalog.referentialEdges().get(0).leftClass()).isEqualTo(ORDER);
        assertThat(catalog.referentialEdges().get(1).leftClass()).isEqualTo(CUSTOMER);
    }

    @Test
    @DisplayName("a ReferentialEdge rejects mismatched column arity (left/right must pair up)")
    void rejectsMismatchedColumnArity() {
        assertThatThrownBy(() -> new ReferentialEdge(
                ORDER, List.of(O_REF), CUSTOMER, List.of(C_ID, NS + "extra"), Tier.HYPOTHESIS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ReferentialEdge column lists are defensively copied (immutable record)")
    void columnListsAreDefensivelyCopied() {
        var mutable = new java.util.ArrayList<>(List.of(O_REF));
        var edge = new ReferentialEdge(ORDER, mutable, CUSTOMER, List.of(C_ID), Tier.HYPOTHESIS);
        mutable.add("mutated");
        assertThat(edge.leftCols()).containsExactly(O_REF);
    }
}

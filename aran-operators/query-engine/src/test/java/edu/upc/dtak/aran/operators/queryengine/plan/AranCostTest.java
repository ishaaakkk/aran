package edu.upc.dtak.aran.operators.queryengine.plan;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.calcite.plan.RelOptCost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AranCost's contract: three components carried, ordering by rows ONLY — flipping the comparison
 * to a weighted form is a phase-6 decision behind a golden re-derivation, so these assertions are
 * the tripwire that stops it happening by accident.
 */
class AranCostTest {

    @Test
    @DisplayName("comparison is rows-only: a huge io/cpu difference must not reorder costs")
    void orderingIgnoresCpuAndIo() {
        RelOptCost cheapRowsExpensiveIo = AranCost.FACTORY.makeCost(10, 1e9, 1e9);
        RelOptCost manyRowsFreeIo = AranCost.FACTORY.makeCost(1000, 0, 0);
        assertThat(cheapRowsExpensiveIo.isLt(manyRowsFreeIo)).isTrue();
        assertThat(manyRowsFreeIo.isLe(cheapRowsExpensiveIo)).isFalse();
        assertThat(AranCost.FACTORY.makeCost(10, 0, 0).equals(cheapRowsExpensiveIo)).isTrue();
    }

    @Test
    @DisplayName("arithmetic carries all three components — the point of not using RelOptCostImpl")
    void arithmeticCarriesComponents() {
        RelOptCost sum = AranCost.FACTORY.makeCost(10, 20, 30)
                .plus(AranCost.FACTORY.makeCost(1, 2, 3));
        assertThat(sum.getRows()).isEqualTo(11.0);
        assertThat(sum.getCpu()).isEqualTo(22.0);
        assertThat(sum.getIo()).isEqualTo(33.0);
        RelOptCost scaled = sum.multiplyBy(2);
        assertThat(scaled.getIo()).isEqualTo(66.0);
    }

    @Test
    @DisplayName("makeInfiniteCost keeps the load-bearing 1e20 hack: finite, and sums stay finite")
    void infiniteCostIsTheFiniteHack() {
        RelOptCost inf = AranCost.FACTORY.makeInfiniteCost();
        assertThat(inf.isInfinite()).isFalse();
        assertThat(inf.getRows()).isEqualTo(1e20);
        assertThat(inf.plus(inf).isInfinite()).isFalse();
    }
}

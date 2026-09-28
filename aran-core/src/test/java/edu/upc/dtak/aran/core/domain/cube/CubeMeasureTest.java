package edu.upc.dtak.aran.core.domain.cube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two shapes of a measure, and the fact that they are exclusive. Every rejection here is one
 * the YAML and QB4OLAP emitters would otherwise have to guess their way past.
 */
@DisplayName("CubeMeasure")
class CubeMeasureTest {

    private static final String AMOUNT = "http://aran/global#amount";

    @Test
    @DisplayName("a column measure carries a column and an aggregator, and no expr")
    void column_measure() {
        CubeMeasure measure = CubeMeasure.of("revenue", AMOUNT, "SUM", Additivity.ADDITIVE);

        assertThat(measure.columnIri()).isEqualTo(AMOUNT);
        assertThat(measure.aggregator()).isEqualTo("SUM");
        assertThat(measure.expr()).isNull();
    }

    @Test
    @DisplayName("a RATIO measure carries an expr and neither a column nor an aggregator")
    void ratio_measure() {
        CubeMeasure measure = CubeMeasure.ratio("margin_pct", "SUM(margin) / SUM(amount)");

        assertThat(measure.additivity()).isEqualTo(Additivity.RATIO);
        assertThat(measure.expr()).isEqualTo("SUM(margin) / SUM(amount)");
        assertThat(measure.columnIri()).isNull();
        assertThat(measure.aggregator()).isNull();
    }

    @Test
    @DisplayName("a RATIO measure with a column is rejected — it is computed after aggregation")
    void ratio_rejects_a_column() {
        assertThatThrownBy(() -> new CubeMeasure(
                "margin_pct", AMOUNT, "SUM(a)/SUM(b)", null, Additivity.RATIO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither columnIri nor aggregator");
    }

    @Test
    @DisplayName("a RATIO measure without an expr is rejected — there is nothing to compute")
    void ratio_requires_an_expr() {
        assertThatThrownBy(() -> new CubeMeasure(
                "margin_pct", null, null, null, Additivity.RATIO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry expr");
    }

    @Test
    @DisplayName("a column measure without a column is rejected")
    void column_measure_requires_a_column() {
        assertThatThrownBy(() -> new CubeMeasure(
                "revenue", null, null, "SUM", Additivity.ADDITIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columnIri");
    }

    @Test
    @DisplayName("an aggregator the star's engines do not share is rejected")
    void unknown_aggregator_is_rejected() {
        assertThatThrownBy(() -> CubeMeasure.of(
                "revenue", AMOUNT, "MEDIAN", Additivity.ADDITIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEDIAN");
    }

    @Test
    @DisplayName("a column measure carrying an expr is rejected — the two shapes are exclusive")
    void column_measure_rejects_an_expr() {
        assertThatThrownBy(() -> new CubeMeasure(
                "revenue", AMOUNT, "SUM(a)/SUM(b)", "SUM", Additivity.ADDITIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expr belongs to a RATIO measure");
    }

    @Test
    @DisplayName("a blank name is rejected — it becomes a YAML key and an export identifier")
    void blank_name_is_rejected() {
        assertThatThrownBy(() -> CubeMeasure.of(" ", AMOUNT, "SUM", Additivity.ADDITIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}

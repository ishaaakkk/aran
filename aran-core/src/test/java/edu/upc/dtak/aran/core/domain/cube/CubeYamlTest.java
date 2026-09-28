package edu.upc.dtak.aran.core.domain.cube;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code cube.yaml} as a golden: the emitter is hand-rolled, so its output is asserted whole
 * rather than probed field by field — a stray blank line or a moved key is a diff in the artifact
 * users read and paste.
 */
@DisplayName("CubeYaml")
class CubeYamlTest {

    private static final String G = "http://aran/global#";

    private static CubeDefinition cube(String description, List<CubeMeasure> measures,
                                       List<CubeDimension> dimensions, CubeCalendar calendar) {
        return new CubeDefinition(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "sales", description, G + "Sales", measures, dimensions, calendar, List.of(),
                UUID.randomUUID(), null, Instant.now());
    }

    @Test
    @DisplayName("a full cube emits version, fact with its IRI, measures, dimensions and calendar")
    void golden() {
        CubeDefinition cube = cube("Orders by customer",
                List.of(CubeMeasure.of("amount", G + "amount", "SUM", Additivity.ADDITIVE),
                        CubeMeasure.of("stock", G + "stockLevel", "SUM", Additivity.SEMI_ADDITIVE),
                        CubeMeasure.ratio("margin_pct", "SUM(margin) / SUM(amount)")),
                List.of(new CubeDimension("customer", G + "Customer", G + "hasCustomer", true,
                                List.of(G + "country", G + "city"), false),
                        CubeDimension.flat("channel", G + "Channel", G + "hasChannel")),
                new CubeCalendar(G + "orderDate", List.of("year", "month")));

        assertThat(CubeYaml.emit(cube)).isEqualTo("""
                version: 0.1
                name: sales
                description: Orders by customer
                fact: Sales  # http://aran/global#Sales
                measures:
                  - name: amount
                    expr: SUM(amount)
                  - name: stock
                    expr: SUM(stockLevel)
                    additivity: semi_additive
                  - name: margin_pct
                    expr: SUM(margin) / SUM(amount)  # ratio — evaluated after aggregation, \
                never re-aggregated
                    additivity: ratio
                dimensions:
                  - name: customer
                    class: Customer
                    on: hasCustomer
                    unknown_member: true
                    hierarchy: [country, city]
                  - name: channel
                    class: Channel
                    on: hasChannel
                calendar:
                  from: orderDate
                  levels: [year, month]
                """);
    }

    @Test
    @DisplayName("a bare cube omits every optional section rather than emitting empty ones")
    void minimal() {
        CubeDefinition cube = cube(null,
                List.of(CubeMeasure.of("amount", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null);

        assertThat(CubeYaml.emit(cube)).isEqualTo("""
                version: 0.1
                name: sales
                fact: Sales  # http://aran/global#Sales
                measures:
                  - name: amount
                    expr: SUM(amount)
                """);
    }

    @Test
    @DisplayName("free text that would break the document is quoted, not emitted raw")
    void quotes_what_yaml_would_misread() {
        CubeDefinition cube = cube("revenue: net, after # discounts",
                List.of(CubeMeasure.of("amount", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null);

        assertThat(CubeYaml.emit(cube))
                .contains("description: \"revenue: net, after # discounts\"");
    }
}

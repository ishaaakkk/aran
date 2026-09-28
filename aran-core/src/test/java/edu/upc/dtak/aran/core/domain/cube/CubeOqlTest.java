package edu.upc.dtak.aran.core.domain.cube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The star's OQL. The rejections matter as much as the emissions: the generator writes names bare,
 * so a name it cannot write that way must stop cube creation rather than reach a materialization
 * job.
 */
@DisplayName("CubeOql")
class CubeOqlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String G = "http://aran/global#";

    private static final CubeEvidence EVIDENCE = CubeEvidence.parse("""
            {"classes":[],"edges":[
              {"propertyIri":"http://aran/global#hasCustomer","propertyName":"hasCustomer",
               "leftClassIri":"http://aran/global#Sales",
               "rightClassIri":"http://aran/global#Customer",
               "leftColIri":"http://aran/global#customerId",
               "rightColIri":"http://aran/global#customerKey","tier":"DECLARED"}]}
            """, MAPPER);

    private static CubeDefinition cube(List<CubeMeasure> measures, List<CubeDimension> dimensions,
                                       CubeCalendar calendar, String factClassIri) {
        return new CubeDefinition(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "sales", null, factClassIri, measures, dimensions, calendar, List.of(),
                UUID.randomUUID(), null, Instant.now());
    }

    private static CubeDimension customer(List<String> hierarchy) {
        return new CubeDimension("customer", G + "Customer", G + "hasCustomer", false,
                hierarchy, false);
    }

    @Test
    @DisplayName("the fact projects its measured columns, every join key, and the calendar column")
    void fact_projects_measures_keys_and_the_calendar() {
        CubeDefinition cube = cube(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(customer(List.of())),
                new CubeCalendar(G + "orderDate", List.of("year")),
                G + "Sales");

        assertThat(CubeOql.fact(cube, EVIDENCE)).isEqualTo(
                "SELECT Sales.amount, Sales.customerId, Sales.orderDate FROM Sales");
    }

    @Test
    @DisplayName("a RATIO measure contributes no column — it is computed after aggregation")
    void fact_skips_ratio_measures() {
        CubeDefinition cube = cube(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE),
                        CubeMeasure.ratio("margin_pct", "SUM(margin) / SUM(amount)")),
                List.of(), null, G + "Sales");

        assertThat(CubeOql.fact(cube, EVIDENCE)).isEqualTo("SELECT Sales.amount FROM Sales");
    }

    @Test
    @DisplayName("a column measured twice is projected once")
    void fact_dedupes_columns() {
        CubeDefinition cube = cube(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE),
                        CubeMeasure.of("biggest", G + "amount", "MAX", Additivity.NON_ADDITIVE)),
                List.of(), null, G + "Sales");

        assertThat(CubeOql.fact(cube, EVIDENCE)).isEqualTo("SELECT Sales.amount FROM Sales");
    }

    @Test
    @DisplayName("a dimension projects its own side of the join, then its levels in order")
    void dimension_projects_its_key_and_levels() {
        CubeDimension customer = customer(List.of(G + "country", G + "city"));
        CubeDefinition cube = cube(List.of(), List.of(customer), null, G + "Sales");

        assertThat(CubeOql.dimension(cube, customer, EVIDENCE)).isEqualTo(
                "SELECT Customer.customerKey, Customer.country, Customer.city FROM Customer");
    }

    @Test
    @DisplayName("a level that is also the join key is projected once")
    void dimension_dedupes_its_key() {
        CubeDimension customer = customer(List.of(G + "customerKey", G + "country"));
        CubeDefinition cube = cube(List.of(), List.of(customer), null, G + "Sales");

        assertThat(CubeOql.dimension(cube, customer, EVIDENCE)).isEqualTo(
                "SELECT Customer.customerKey, Customer.country FROM Customer");
    }

    @Test
    @DisplayName("two IRIs sharing one local name are refused, with both IRIs and the name said")
    void rejects_two_properties_that_collide_on_their_local_name() {
        CubeDefinition cube = cube(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE),
                        CubeMeasure.of("other", "http://vendor/ns#amount", "SUM",
                                Additivity.ADDITIVE)),
                List.of(), null, G + "Sales");

        // Dropping one silently would ship a star whose definition claims a column it lacks.
        assertThatThrownBy(() -> CubeOql.fact(cube, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(G + "amount")
                .hasMessageContaining("http://vendor/ns#amount")
                .hasMessageContaining("share the local name 'amount'");
    }

    @Test
    @DisplayName("one IRI listed twice still dedupes — the refusal is about two IRIs, not two mentions")
    void projection_dedupes_a_repeated_iri() {
        assertThat(CubeOql.projection(G + "Sales", List.of(G + "amount", G + "amount")))
                .isEqualTo("SELECT Sales.amount FROM Sales");
    }

    @Test
    @DisplayName("a class whose local name is an OQL keyword is refused, not emitted unquoted")
    void rejects_a_reserved_class_name() {
        CubeDefinition cube = cube(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null, G + "Order");

        assertThatThrownBy(() -> CubeOql.fact(cube, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Order")
                .hasMessageContaining("OQL keyword");
    }

    @Test
    @DisplayName("a keyword in any case is still a keyword — the parser does not care either")
    void reserved_words_are_case_insensitive() {
        CubeDefinition cube = cube(
                List.of(CubeMeasure.of("n", G + "Count", "SUM", Additivity.ADDITIVE)),
                List.of(), null, G + "Sales");

        assertThatThrownBy(() -> CubeOql.fact(cube, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OQL keyword");
    }

    @Test
    @DisplayName("a property whose local name OQL has no syntax for is refused")
    void rejects_an_unwritable_property_name() {
        CubeDefinition cube = cube(
                List.of(CubeMeasure.of("revenue", G + "total amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null, G + "Sales");

        assertThatThrownBy(() -> CubeOql.fact(cube, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total amount")
                .hasMessageContaining("cannot write");
    }

    @Test
    @DisplayName("a fact with nothing to project is refused rather than emitted as broken OQL")
    void rejects_an_empty_projection() {
        CubeDefinition cube = cube(List.of(), List.of(), null, G + "Sales");

        assertThatThrownBy(() -> CubeOql.fact(cube, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to project");
    }

    @Test
    @DisplayName("a dimension over a join the engine never reported is refused")
    void rejects_an_unknown_join() {
        CubeDimension unknown =
                new CubeDimension("channel", G + "Channel", G + "hasChannel", false,
                        List.of(), false);
        CubeDefinition cube = cube(List.of(), List.of(unknown), null, G + "Sales");

        assertThatThrownBy(() -> CubeOql.dimension(cube, unknown, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a relationship");
    }
}

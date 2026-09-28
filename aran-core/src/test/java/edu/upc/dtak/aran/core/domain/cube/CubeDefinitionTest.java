package edu.upc.dtak.aran.core.domain.cube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The structural invariants of a cube definition — the ones that hold before any evidence is
 * consulted, because they are about what the star's own artefacts can be named.
 */
@DisplayName("CubeDefinition")
class CubeDefinitionTest {

    private static final String FACT = "http://aran/global#Sales";
    private static final String CUSTOMER = "http://aran/global#Customer";
    private static final String HAS_CUSTOMER = "http://aran/global#hasCustomer";

    private static CubeDefinition cube(String name, List<CubeMeasure> measures,
                                       List<CubeDimension> dimensions) {
        return cube(name, measures, dimensions, null);
    }

    private static CubeDefinition cube(String name, List<CubeMeasure> measures,
                                       List<CubeDimension> dimensions, CubeCalendar calendar) {
        return new CubeDefinition(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                name, null, FACT, measures, dimensions, calendar, List.of(), UUID.randomUUID(),
                null, Instant.now());
    }

    @Test
    @DisplayName("the star's product names are the cube's name plus _fact and _<dimension>")
    void product_names() {
        CubeDimension customer = CubeDimension.flat("customer", CUSTOMER, HAS_CUSTOMER);
        CubeDefinition cube = cube("sales", List.of(), List.of(customer));

        assertThat(cube.factProductName()).isEqualTo("sales_fact");
        assertThat(cube.dimensionProductName(customer)).isEqualTo("sales_customer");
    }

    @Test
    @DisplayName("a cube name that is not a legal table name is rejected at the door")
    void rejects_a_name_no_table_can_carry() {
        assertThatThrownBy(() -> cube("Sales Cube", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cube name 'Sales Cube'");
    }

    @Test
    @DisplayName("a dimension name that is not a legal table name is rejected — it becomes one")
    void rejects_a_dimension_name_no_table_can_carry() {
        assertThatThrownBy(() -> new CubeDimension(
                "Customer", CUSTOMER, HAS_CUSTOMER, false, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension name 'Customer'");
    }

    @Test
    @DisplayName("two dimensions with one name are rejected — they would ask for one product twice")
    void rejects_duplicate_dimension_names() {
        assertThatThrownBy(() -> cube("sales", List.of(), List.of(
                CubeDimension.flat("customer", CUSTOMER, HAS_CUSTOMER),
                CubeDimension.flat("customer", CUSTOMER, "http://aran/global#billedTo"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension name 'customer' is used twice");
    }

    @Test
    @DisplayName("two measures with one name are rejected — one YAML key cannot mean two things")
    void rejects_duplicate_measure_names() {
        assertThatThrownBy(() -> cube("sales", List.of(
                CubeMeasure.of("total", "http://aran/global#amount", "SUM", Additivity.ADDITIVE),
                CubeMeasure.of("total", "http://aran/global#tax", "SUM", Additivity.ADDITIVE)),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("measure name 'total' is used twice");
    }

    @Test
    @DisplayName("a dimension named 'calendar' beside a calendar is rejected — they claim one node")
    void rejects_a_dimension_that_would_merge_with_the_generated_calendar() {
        assertThatThrownBy(() -> cube("sales", List.of(),
                List.of(CubeDimension.flat("calendar", CUSTOMER, HAS_CUSTOMER)),
                new CubeCalendar("http://aran/global#orderDate", List.of("year"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a dimension cannot be named 'calendar'");
    }

    @Test
    @DisplayName("the same dimension name is fine without a calendar — nothing to merge with")
    void allows_a_calendar_named_dimension_when_there_is_no_calendar() {
        CubeDefinition cube = cube("sales", List.of(),
                List.of(CubeDimension.flat("calendar", CUSTOMER, HAS_CUSTOMER)));

        assertThat(cube.dimensions()).singleElement()
                .extracting(CubeDimension::name).isEqualTo("calendar");
    }

    @Test
    @DisplayName("a derived star product name over data_product's 120 chars is rejected by length")
    void rejects_a_product_name_the_column_cannot_hold() {
        // Both halves are legal on their own (the 63-char star-name rule); joined by '_' they
        // are 121, which data_product.name cannot store.
        String cubeName = "c".repeat(63);
        String dimensionName = "d".repeat(57);

        assertThatThrownBy(() -> cube(cubeName, List.of(),
                List.of(CubeDimension.flat(dimensionName, CUSTOMER, HAS_CUSTOMER))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 120 characters")
                .hasMessageContaining("shorten the cube or dimension name");
    }

    @Test
    @DisplayName("a missing hierarchy is a flat dimension, not an error")
    void a_null_hierarchy_is_flat() {
        CubeDimension dimension =
                new CubeDimension("customer", CUSTOMER, HAS_CUSTOMER, false, null, false);

        assertThat(dimension.hierarchy()).isEmpty();
    }

    @Test
    @DisplayName("a calendar level v1 cannot generate is rejected rather than exported onward")
    void rejects_an_unknown_calendar_level() {
        assertThatThrownBy(() -> new CubeCalendar(
                "http://aran/global#orderDate", List.of("year", "fortnight")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown level 'fortnight'");
    }

    @Test
    @DisplayName("calendar levels are normalised to lower case, so the emitters have one spelling")
    void calendar_levels_are_normalised() {
        CubeCalendar calendar =
                new CubeCalendar("http://aran/global#orderDate", List.of("YEAR", "Month"));

        assertThat(calendar.levels()).containsExactly("year", "month");
    }

    @Test
    @DisplayName("a repeated calendar level is rejected — a level is its own place in the rollup")
    void rejects_a_repeated_calendar_level() {
        assertThatThrownBy(() -> new CubeCalendar(
                "http://aran/global#orderDate", List.of("year", "year")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeated");
    }
}

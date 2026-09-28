package edu.upc.data.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Schema} overrode {@code equals} with no {@code hashCode}.
 *
 * <p>The 2026-08-07 static audit filed this against both {@code Column} and {@code Schema},
 * called it "the one lint warning that can produce wrong results, not just noise" because both
 * are lattice-search map keys, and then fixed only {@code Column} while marking the finding
 * done. These tests are what makes the other half stay fixed.
 */
class SchemaEqualsHashCodeTest {

    private static Schema schema(String... columnNames) {
        return new Schema(columnNames);
    }

    @Test
    @DisplayName("equal schemas hash alike, so a HashMap lookup finds the entry")
    void equalSchemasShareAHashCode() {
        Schema a = schema("id", "name");
        Schema b = schema("id", "name");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        // The failure the missing override actually caused: the key is present by equals and
        // absent by hash, so the lookup misses rather than throwing.
        var map = new HashMap<Schema, String>();
        map.put(a, "lattice");
        assertThat(map).containsKey(b);
        assertThat(map.get(b)).isEqualTo("lattice");

        assertThat(new HashSet<>(java.util.List.of(a, b))).hasSize(1);
    }

    @Test
    @DisplayName("schemas differing in column names are unequal")
    void differentColumnsAreUnequal() {
        assertThat(schema("id", "name")).isNotEqualTo(schema("id", "surname"));
        assertThat(schema("id")).isNotEqualTo(schema("id", "name"));
    }

    @Test
    @DisplayName("equals returns false for null and a foreign type rather than throwing")
    void equalsHandlesNullAndForeignTypes() {
        Schema a = schema("id");

        // Before the guard these threw NullPointerException and ClassCastException.
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("not a schema")).isFalse();
        assertThat(a).isEqualTo(a);
    }
}

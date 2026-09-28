package edu.upc.dtak.aran.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The persisted-edge carrier's own invariants — mirrors {@code Harvest.ForeignKey}'s own test. */
class DeclaredForeignKeyTest {

    private static DeclaredForeignKey edge(List<String> fromCols, List<String> toCols) {
        return new DeclaredForeignKey(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), fromCols, UUID.randomUUID(), toCols);
    }

    @Test
    @DisplayName("rejects empty column lists")
    void rejectsEmptyColumns() {
        assertThatThrownBy(() -> edge(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects mismatched column-list lengths — position i must pair with position i")
    void rejectsMismatchedColumns() {
        assertThatThrownBy(() -> edge(List.of("x", "y"), List.of("z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a composite edge carries both column pairs in order")
    void compositeEdgePreservesOrder() {
        DeclaredForeignKey fk = edge(List.of("x1", "x2"), List.of("y1", "y2"));
        assertThat(fk.fromCols()).containsExactly("x1", "x2");
        assertThat(fk.toCols()).containsExactly("y1", "y2");
    }

    @Test
    @DisplayName("a mutable list handed in cannot be mutated out from under the record")
    void defensiveCopies() {
        List<String> fromCols = new ArrayList<>(List.of("a"));
        List<String> toCols = new ArrayList<>(List.of("b"));
        DeclaredForeignKey fk = edge(fromCols, toCols);

        fromCols.clear();
        toCols.clear();

        assertThat(fk.fromCols()).containsExactly("a");
        assertThat(fk.toCols()).containsExactly("b");
    }
}

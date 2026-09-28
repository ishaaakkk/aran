package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.source.Harvest.ColKey;
import edu.upc.dtak.aran.core.source.Harvest.ForeignKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The carrier's own invariants — independent of any engine. */
class HarvestTest {

    @Test
    @DisplayName("EMPTY is genuinely empty in all three pieces")
    void empty_is_empty() {
        assertThat(Harvest.EMPTY.rowCountByTable()).isEmpty();
        assertThat(Harvest.EMPTY.ndvByColumn()).isEmpty();
        assertThat(Harvest.EMPTY.declaredForeignKeys()).isEmpty();
    }

    @Test
    @DisplayName("a mutable map/list handed in cannot be mutated out from under the record")
    void defensive_copies() {
        Map<String, Long> rowCounts = new java.util.HashMap<>(Map.of("t", 1L));
        Map<ColKey, Long> ndv = new java.util.HashMap<>(Map.of(new ColKey("t", "c"), 1L));
        List<ForeignKey> fks = new java.util.ArrayList<>(
                List.of(new ForeignKey("a", List.of("x"), "b", List.of("y"))));

        Harvest harvest = new Harvest(rowCounts, ndv, fks);
        rowCounts.put("other", 2L);
        ndv.clear();
        fks.clear();

        assertThat(harvest.rowCountByTable()).containsOnlyKeys("t");
        assertThat(harvest.ndvByColumn()).hasSize(1);
        assertThat(harvest.declaredForeignKeys()).hasSize(1);
    }

    @Test
    @DisplayName("the 3-arg constructor defaults fkHarvestSucceeded to true — every pre-existing call site meant 'authoritative'")
    void threeArgConstructorDefaultsFkHarvestSucceededTrue() {
        Harvest harvest = new Harvest(Map.of(), Map.of(), List.of());

        assertThat(harvest.fkHarvestSucceeded()).isTrue();
    }

    @Test
    @DisplayName("the 4-arg constructor carries an explicit FK-success signal")
    void fourArgConstructorCarriesExplicitFkSuccessSignal() {
        Harvest failed = new Harvest(Map.of("t", 1L), Map.of(), List.of(), false);

        assertThat(failed.fkHarvestSucceeded()).isFalse();
        // The other two pieces are unaffected by the FK piece failing independently.
        assertThat(failed.rowCountByTable()).containsEntry("t", 1L);
    }

    @Test
    @DisplayName("EMPTY reports its FK harvest as not succeeded — nothing was learned, not \"zero FKs\"")
    void emptyReportsFkHarvestNotSucceeded() {
        assertThat(Harvest.EMPTY.fkHarvestSucceeded()).isFalse();
    }

    @Test
    @DisplayName("a foreign key needs at least one column pair")
    void foreign_key_rejects_empty_columns() {
        assertThatThrownBy(() -> new ForeignKey("a", List.of(), "b", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a foreign key's two column lists must be the same length — position i pairs with position i")
    void foreign_key_rejects_mismatched_columns() {
        assertThatThrownBy(() -> new ForeignKey("a", List.of("x", "y"), "b", List.of("z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a composite foreign key carries both column pairs in order")
    void foreign_key_composite() {
        ForeignKey fk = new ForeignKey("a", List.of("x1", "x2"), "b", List.of("y1", "y2"));
        assertThat(fk.fromCols()).containsExactly("x1", "x2");
        assertThat(fk.toCols()).containsExactly("y1", "y2");
    }
}

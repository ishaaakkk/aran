package edu.upc.dtak.aran.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HarvestedColumnProfilesTest {

    private static final UUID DATASET_ID = UUID.randomUUID();

    @Test
    @DisplayName("one HARVESTED profile per NDV column, cardinality=ndv, uniqueness derived from rowCount")
    void oneProfilePerNdvColumn() {
        Harvest harvest = new Harvest(
                Map.of("employees", 900L),
                Map.of(new Harvest.ColKey("employees", "id"), 900L,
                        new Harvest.ColKey("employees", "status"), 3L,
                        new Harvest.ColKey("departments", "id"), 10L), // a different table — excluded
                List.of());

        List<ColumnProfile> profiles = HarvestedColumnProfiles.forTable(harvest, DATASET_ID, "employees");

        assertThat(profiles).hasSize(2);
        assertThat(profiles).allSatisfy(p -> {
            assertThat(p.subject()).isEqualTo("HARVESTED");
            assertThat(p.classIri()).isNull();
            assertThat(p.datasetId()).isEqualTo(DATASET_ID);
        });

        ColumnProfile idProfile = profiles.stream().filter(p -> p.columnName().equals("id")).findFirst().orElseThrow();
        assertThat(idProfile.cardinality()).isEqualTo(900L);
        assertThat(idProfile.uniqueness()).isCloseTo(1.0, within(1e-9));

        ColumnProfile statusProfile = profiles.stream().filter(p -> p.columnName().equals("status")).findFirst().orElseThrow();
        assertThat(statusProfile.cardinality()).isEqualTo(3L);
        assertThat(statusProfile.uniqueness()).isCloseTo(3.0 / 900.0, within(1e-9));
    }

    @Test
    @DisplayName("cardinality/uniqueness recovers the exact harvested row count for every column, not just the tightest")
    void estimateRowCountRecoversHarvestedRowCountExactly() {
        Harvest harvest = new Harvest(
                Map.of("t", 12_345L),
                Map.of(new Harvest.ColKey("t", "a"), 12_345L,   // PK-like: fully unique
                       new Harvest.ColKey("t", "b"), 7L),        // low-cardinality column
                List.of());

        List<ColumnProfile> profiles = HarvestedColumnProfiles.forTable(harvest, DATASET_ID, "t");

        // ProfileStatsSource.estimateRowCount's own formula, applied here directly: every column
        // must independently recover the true row count, proving the mapping does not rely on
        // happening to pick "the tightest" column.
        for (ColumnProfile p : profiles) {
            double recovered = p.cardinality() / p.uniqueness();
            assertThat(recovered).as("column " + p.columnName()).isCloseTo(12_345.0, within(1e-6));
        }
    }

    @Test
    @DisplayName("a harvested NULL fraction becomes the profile's incompleteness; no evidence stays 0.0")
    void nullFractionIsIncompleteness() {
        Harvest harvest = new Harvest(
                Map.of("employees", 900L),
                Map.of(new Harvest.ColKey("employees", "manager_id"), 40L,
                        new Harvest.ColKey("employees", "id"), 900L),
                List.of(), true,
                Map.of(new Harvest.ColKey("employees", "manager_id"), 0.25));

        List<ColumnProfile> profiles = HarvestedColumnProfiles.forTable(harvest, DATASET_ID, "employees");

        assertThat(profiles).filteredOn(p -> p.columnName().equals("manager_id"))
                .singleElement().extracting(ColumnProfile::incompleteness).isEqualTo(0.25);
        assertThat(profiles).filteredOn(p -> p.columnName().equals("id"))
                .singleElement().extracting(ColumnProfile::incompleteness).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a table absent from the harvest yields no profiles")
    void unharvestedTableYieldsNoProfiles() {
        Harvest harvest = new Harvest(Map.of("other", 5L), Map.of(new Harvest.ColKey("other", "x"), 5L), List.of());

        assertThat(HarvestedColumnProfiles.forTable(harvest, DATASET_ID, "employees")).isEmpty();
    }

    @Test
    @DisplayName("row count absent (never analyzed) still yields NDV profiles, uniqueness falls back to 1.0")
    void missingRowCountFallsBackToFullUniqueness() {
        Harvest harvest = new Harvest(
                Map.of(), // reltuples unknown — Task 2's harvest omits it, doesn't zero it
                Map.of(new Harvest.ColKey("t", "a"), 42L),
                List.of());

        List<ColumnProfile> profiles = HarvestedColumnProfiles.forTable(harvest, DATASET_ID, "t");

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).cardinality()).isEqualTo(42L);
        assertThat(profiles.get(0).uniqueness()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Harvest.EMPTY yields no profiles for any table")
    void emptyHarvestYieldsNothing() {
        assertThat(HarvestedColumnProfiles.forTable(Harvest.EMPTY, DATASET_ID, "anything")).isEmpty();
    }
}

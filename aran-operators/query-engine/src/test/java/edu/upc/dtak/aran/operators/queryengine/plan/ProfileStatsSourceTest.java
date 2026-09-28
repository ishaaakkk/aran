package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link ProfileStatsSource#statsFor} — one load per dataset, and the not-found paths. */
class ProfileStatsSourceTest {

    private static final TenantContext TENANT =
            new TenantContext("11111111-1111-1111-1111-111111111111");

    private static ColumnProfile profile(String column, long cardinality, double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    private static ColumnProfile wrapperOutputProfile(String column, long cardinality, double uniqueness,
                                                       String classIri) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                "WRAPPER_OUTPUT", classIri);
    }

    private static Dataset dataset(UUID id, List<ColumnProfile> columns) {
        return new Dataset(id, UUID.randomUUID(), "ds", "", null, columns,
                null, null);
    }

    @Test
    @DisplayName("row count and profiles come from a single repository load")
    void oneLoadPerCall() {
        // The point of merging rowCountFor + profilesFor. Each did its own findById, the catalog
        // builder called both per source view, and DatasetEntity.columns is @Fetch(SUBSELECT) —
        // so reading one dataset's statistics cost four queries, twice over.
        UUID id = UUID.randomUUID();
        DatasetRepository repo = mock(DatasetRepository.class);
        when(repo.findById(any(), any())).thenReturn(Optional.of(
                dataset(id, List.of(profile("id", 1000, 1.0), profile("kind", 4, 0.004)))));

        ProfileStatsSource.DatasetStats stats =
                new ProfileStatsSource(repo).statsFor(TENANT, id.toString());

        verify(repo, times(1)).findById(any(), any());
        assertThat(stats.rowCount()).isEqualTo(1000L);
        assertThat(stats.profiles()).hasSize(2);
        assertThat(stats.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("a dataset id that is not a UUID yields empty stats rather than throwing")
    void nonUuidDatasetId() {
        DatasetRepository repo = mock(DatasetRepository.class);

        ProfileStatsSource.DatasetStats stats =
                new ProfileStatsSource(repo).statsFor(TENANT, "not-a-uuid");

        assertThat(stats.isEmpty()).isTrue();
        assertThat(stats.rowCount()).isZero();
        assertThat(stats.profiles()).isEmpty();
        verify(repo, times(0)).findById(any(), any());
    }

    @Test
    @DisplayName("an unknown dataset yields empty stats")
    void unknownDataset() {
        DatasetRepository repo = mock(DatasetRepository.class);
        when(repo.findById(any(), any())).thenReturn(Optional.empty());

        assertThat(new ProfileStatsSource(repo).statsFor(TENANT, UUID.randomUUID().toString())
                .isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an unprofiled dataset is empty, so the catalog leaves rowCount at 0")
    void unprofiledDatasetIsEmpty() {
        // MappingCatalogOperator only overwrites a SourceView when the stats are non-empty;
        // rowCount 0 is what makes AranStatistic report "unknown" rather than "zero rows".
        UUID id = UUID.randomUUID();
        DatasetRepository repo = mock(DatasetRepository.class);
        when(repo.findById(any(), any())).thenReturn(Optional.of(dataset(id, List.of())));

        ProfileStatsSource.DatasetStats stats =
                new ProfileStatsSource(repo).statsFor(TENANT, id.toString());

        assertThat(stats.isEmpty()).isTrue();
    }

    // ── Phase 3B (Task 3): filtering an already-loaded dataset's profiles by (subject, classIri) ──

    @Test
    @DisplayName("wrapperOutputFor filters to one class's WRAPPER_OUTPUT profiles and re-derives row count")
    void wrapperOutputForFiltersByClassAndReDerivesRowCount() {
        ColumnProfile forX = wrapperOutputProfile("a", 42, 1.0, "urn:X");
        ColumnProfile forY = wrapperOutputProfile("b", 999, 1.0, "urn:Y");
        ColumnProfile snapshot = profile("c", 1, 1.0);

        ProfileStatsSource.DatasetStats stats =
                ProfileStatsSource.wrapperOutputFor(List.of(forX, forY, snapshot), "urn:X");

        assertThat(stats.profiles()).containsExactly(forX);
        assertThat(stats.rowCount()).isEqualTo(42L);
    }

    @Test
    @DisplayName("wrapperOutputFor is empty when that class has not been wrapper-output-profiled")
    void wrapperOutputForEmptyWhenClassNotProfiled() {
        ColumnProfile forY = wrapperOutputProfile("b", 999, 1.0, "urn:Y");

        assertThat(ProfileStatsSource.wrapperOutputFor(List.of(forY), "urn:X").isEmpty()).isTrue();
    }

    // ── Gap 2 follow-up: the FEDERATED_LIVE selector ──

    @Test
    @DisplayName("federatedLiveFor selects only that subject, and its exact numbers answer the row count")
    void federatedLiveForSelectsAndAnswersRowCount() {
        // freyja executed the profiling suite over the live binding, so the persisted numbers are
        // exact over the full relation at profile time: cardinality/uniqueness — this class's one
        // row-count formula — answers the source's real size with no rescaling anywhere.
        ColumnProfile live = new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), "id", 0, "VARCHAR",
                44_000_000L, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                ColumnProfile.SUBJECT_FEDERATED_LIVE, "urn:X");
        ColumnProfile wrapperOutput = wrapperOutputProfile("b", 7, 1.0, "urn:X");

        ProfileStatsSource.DatasetStats stats =
                ProfileStatsSource.federatedLiveFor(List.of(live, wrapperOutput), "urn:X");

        assertThat(stats.profiles()).containsExactly(live);
        assertThat(stats.rowCount()).isEqualTo(44_000_000L);
        // And the other selector stays blind to it — the two subjects never cross-contaminate.
        assertThat(ProfileStatsSource.wrapperOutputFor(List.of(live), "urn:X").isEmpty()).isTrue();
    }
}

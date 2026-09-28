package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.app.web.dto.DatasetDtos.DatasetProfilesResponse;
import edu.upc.dtak.aran.app.web.dto.DatasetDtos.ProfileGroup;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * J3-F1: the stored profiles of a dataset, as the Profile tab reads them — one group per thing
 * profiled, each saying whether the planner may take it as key evidence.
 */
class DatasetProfilesResponseTest {

    private static final UUID DATASET = UUID.randomUUID();

    private static ColumnProfile profile(String column, int index, String subject, String classIri) {
        return new ColumnProfile(
                UUID.randomUUID(), DATASET, column, index, "BIGINT",
                10L, 2.5, 1.0, 0.1,
                1.0, 1L, 1L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.1,
                3, 1, 2.0,
                10L, 1, 1, 1.0, 0.0,
                "1", "9",
                false, false,
                List.of("1", "2"), List.of(),
                subject, classIri,
                List.of("00411460f7c92d21"));
    }

    @Test
    @DisplayName("groups by (subject, class), snapshot first, and marks only freyja's own measurements as key evidence")
    void groupsBySubjectAndClass() {
        DatasetProfilesResponse response = DatasetProfilesResponse.from(DATASET, List.of(
                profile("id", 0, ColumnProfile.SUBJECT_HARVESTED, null),
                profile("http://ex/p#title", 1, "WRAPPER_OUTPUT", "http://ex/Paper"),
                profile("title", 1, ColumnProfile.SUBJECT_SNAPSHOT, null),
                profile("id", 0, ColumnProfile.SUBJECT_SNAPSHOT, null),
                profile("id", 0, ColumnProfile.SUBJECT_FEDERATED_LIVE, "http://ex/Paper")));

        assertThat(response.datasetId()).isEqualTo(DATASET);
        assertThat(response.groups())
                .extracting(ProfileGroup::subject, ProfileGroup::classIri, ProfileGroup::keyEvidence)
                .containsExactly(
                        tuple("SNAPSHOT", null, true),
                        tuple("WRAPPER_OUTPUT", "http://ex/Paper", true),
                        tuple("FEDERATED_LIVE", "http://ex/Paper", false),
                        tuple("HARVESTED", null, false));
        // Columns in their physical order, whatever order the rows were stored in.
        assertThat(response.groups().get(0).columns())
                .extracting(DatasetDtos.StoredColumnProfile::columnName)
                .containsExactly("id", "title");
    }

    @Test
    @DisplayName("an unprofiled dataset answers an empty list, not an error")
    void emptyWhenNothingStored() {
        assertThat(DatasetProfilesResponse.from(DATASET, List.of()).groups()).isEmpty();
    }

    @Test
    @DisplayName("carries the metrics the tab renders and leaves the internal value-hash sample out")
    void carriesMetricsButNotTheHashSample() {
        DatasetDtos.StoredColumnProfile column = DatasetProfilesResponse.from(DATASET, List.of(
                profile("id", 0, ColumnProfile.SUBJECT_SNAPSHOT, null))).groups().get(0).columns().get(0);

        assertThat(column.cardinality()).isEqualTo(10L);
        assertThat(column.entropy()).isEqualTo(2.5);
        assertThat(column.commonValues()).containsExactly("1", "2");
        assertThat(DatasetDtos.StoredColumnProfile.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("valueHashSample");
    }
}

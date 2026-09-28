package edu.upc.dtak.aran.core.domain;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3B's profile subject discriminator: every {@link ColumnProfile} now says what it
 * profiled — {@code SNAPSHOT} (today's only real shape: Freyja over the dataset's raw snapshot)
 * or {@code WRAPPER_OUTPUT} (a future row profiling one source class's resolved wrapper output,
 * carrying {@code classIri}). The pre-3B constructor shape — every call site across
 * {@code ProfilingOperator}, {@code MappingCatalogOperator} and the query-engine test fixtures —
 * must keep compiling unmodified and must default to {@code SNAPSHOT}/{@code null}: every one of
 * those call sites predates the concept of profiling anything but a snapshot.
 */
class ColumnProfileTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    @Test
    @DisplayName("the pre-3B constructor shape defaults subject to SNAPSHOT and classIri to null")
    void legacyConstructorDefaultsSubjectAndClassIri() {
        ColumnProfile profile = new ColumnProfile(
                ID, DATASET_ID, "col", 0, "VARCHAR",
                100L, null, 0.5, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());

        assertThat(profile.subject()).isEqualTo("SNAPSHOT");
        assertThat(profile.classIri()).isNull();
    }

    @Test
    @DisplayName("the compact constructor normalizes a null subject to SNAPSHOT for a canonical-shape caller too")
    void compactConstructorNormalizesNullSubject() {
        ColumnProfile profile = canonical(null, null);

        assertThat(profile.subject()).isEqualTo("SNAPSHOT");
    }

    @Test
    @DisplayName("a WRAPPER_OUTPUT profile carries its subject and class IRI through unchanged")
    void wrapperOutputSubjectAndClassIriRoundTrip() {
        ColumnProfile profile = canonical("WRAPPER_OUTPUT", "http://ex/Paper");

        assertThat(profile.subject()).isEqualTo("WRAPPER_OUTPUT");
        assertThat(profile.classIri()).isEqualTo("http://ex/Paper");
    }

    @Test
    @DisplayName("only freyja's own measurements are key evidence: SNAPSHOT and WRAPPER_OUTPUT testify, HARVESTED and FEDERATED_LIVE never")
    void keyEvidenceIsDecidedBySubject() {
        assertThat(canonical(ColumnProfile.SUBJECT_SNAPSHOT, null).admissibleAsKeyEvidence()).isTrue();
        assertThat(canonical("WRAPPER_OUTPUT", "http://ex/Paper").admissibleAsKeyEvidence()).isTrue();
        // pg_stats estimates, and a live source whose distinctness decays after profiling.
        assertThat(canonical(ColumnProfile.SUBJECT_HARVESTED, null).admissibleAsKeyEvidence()).isFalse();
        assertThat(canonical(ColumnProfile.SUBJECT_FEDERATED_LIVE, "http://ex/Paper").admissibleAsKeyEvidence())
                .isFalse();
    }

    private static ColumnProfile canonical(String subject, String classIri) {
        return new ColumnProfile(
                ID, DATASET_ID, "col", 0, "VARCHAR",
                100L, null, 0.5, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                subject, classIri);
    }
}

package edu.upc.dtak.aran.core.domain.drift;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The report crosses two process boundaries as JSON: operator → {@code OperatorResult.data()} →
 * controller, and controller → browser.
 *
 * <p>{@link DriftReport} carries derived accessors ({@code verdict()}, {@code breaking()},
 * {@code changes()}, {@code repairable()}) alongside its record components. If Jackson treats
 * those as properties on the way out but cannot bind them on the way in, the round-trip breaks
 * — and it breaks at the controller, at runtime, after the operator has already succeeded.
 */
class DriftReportJsonTest {

    private static final String CLS = "http://ex.org/source/ds/papers";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static DriftReport report() {
        ModelMapping accepted = new ModelMapping(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), MappingKinds.JOIN, CLS + "/venue", "http://global/venue",
                "venue", ModelMapping.ACCEPTED, null, null, null, Instant.now());
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(List.of(
                        SchemaChange.columnRenamed(CLS, CLS + "/venue", CLS + "/conference",
                                "venue", "conference", 0.85, "the only column dropped and the only one added"),
                        SchemaChange.columnAdded(CLS, CLS + "/doi", "doi")),
                List.of(accepted));
        return new DriftReport(UUID.randomUUID(), "baseline", "candidate", impacts);
    }

    @Test
    @DisplayName("a report survives a JSON round-trip with its verdict intact")
    void roundTrips() throws Exception {
        DriftReport original = report();

        String json = objectMapper.writeValueAsString(original);
        DriftReport parsed = objectMapper.readValue(json, DriftReport.class);

        assertThat(parsed.datasetId()).isEqualTo(original.datasetId());
        assertThat(parsed.baselineHash()).isEqualTo("baseline");
        assertThat(parsed.candidateHash()).isEqualTo("candidate");
        assertThat(parsed.verdict()).isEqualTo(original.verdict());
        assertThat(parsed.breaking()).isTrue();
        assertThat(parsed.impacts()).hasSize(2);
    }

    @Test
    @DisplayName("the detail a user reads survives too, not just the verdict")
    void carriesTheHumanDetail() throws Exception {
        DriftReport parsed = objectMapper.readValue(
                objectMapper.writeValueAsString(report()), DriftReport.class);

        DriftImpact rename = parsed.impacts().stream()
                .filter(i -> i.change().kind() == SchemaChangeKind.COLUMN_RENAMED)
                .findFirst().orElseThrow();

        assertThat(rename.change().detail()).contains("venue").contains("conference");
        assertThat(rename.change().confidence()).isEqualTo(0.85);
        assertThat(rename.change().replacementIri()).isEqualTo(CLS + "/conference");
        assertThat(rename.reason()).contains("join");
        assertThat(rename.affected()).singleElement()
                .satisfies(a -> assertThat(a.kind()).isEqualTo(MappingKinds.JOIN));
        assertThat(rename.repairable()).isTrue();
    }

    @Test
    @DisplayName("a baseline report round-trips with its null hash")
    void baselineRoundTrips() throws Exception {
        // baselineHash is null for a first sighting, and null handling is exactly where a
        // record's canonical constructor tends to blow up on the way back in.
        DriftReport parsed = objectMapper.readValue(
                objectMapper.writeValueAsString(DriftReport.baseline(UUID.randomUUID(), "abc")),
                DriftReport.class);

        assertThat(parsed.isBaseline()).isTrue();
        assertThat(parsed.baselineHash()).isNull();
        assertThat(parsed.breaking()).isFalse();
    }
}

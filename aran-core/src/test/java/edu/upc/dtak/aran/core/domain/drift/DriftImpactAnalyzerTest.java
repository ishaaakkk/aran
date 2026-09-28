package edu.upc.dtak.aran.core.domain.drift;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Severity is a property of the change <em>joined against what depends on it</em>.
 *
 * <p>The point of these tests is that the same {@link SchemaChangeKind} comes out at all three
 * severities depending only on the mappings supplied.
 */
class DriftImpactAnalyzerTest {

    private static final String CLS = "http://ex.org/source/ds/papers";
    private static final String VENUE = CLS + "/venue";

    private static ModelMapping mapping(String kind, String sourceIri, String status) {
        return new ModelMapping(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                kind, sourceIri, "http://global/venue", "venue", status, null, null, null, Instant.now());
    }

    private static SchemaChange venueRemoved() {
        return SchemaChange.columnRemoved(CLS, VENUE, "venue");
    }

    @Test
    @DisplayName("a dropped column nobody mapped is INFO")
    void unmappedRemovalIsInfo() {
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(List.of(venueRemoved()), List.of());
        assertThat(impacts).singleElement().satisfies(i -> {
            assertThat(i.severity()).isEqualTo(DriftSeverity.INFO);
            assertThat(i.affected()).isEmpty();
        });
    }

    @Test
    @DisplayName("the same drop is BREAKING once an accepted mapping depends on it")
    void mappedRemovalIsBreaking() {
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(List.of(venueRemoved()),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.ACCEPTED)));

        assertThat(impacts).singleElement().satisfies(i -> {
            assertThat(i.severity()).isEqualTo(DriftSeverity.BREAKING);
            assertThat(i.affected()).hasSize(1);
            assertThat(i.reason()).contains("1 accepted mapping");
        });
    }

    @Test
    @DisplayName("and WARNING when only an undecided mapping points at it")
    void suggestedOnlyIsWarning() {
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(List.of(venueRemoved()),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.SUGGESTED)));

        assertThat(impacts).singleElement().satisfies(i -> {
            assertThat(i.severity()).isEqualTo(DriftSeverity.WARNING);
            assertThat(i.reason()).contains("accepting one would fail");
        });
    }

    @Test
    @DisplayName("a rejected mapping is an audit trail, not a dependency")
    void rejectedMappingsAreIgnored() {
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(List.of(venueRemoved()),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.REJECTED)));

        assertThat(impacts).singleElement().satisfies(i -> {
            assertThat(i.severity()).isEqualTo(DriftSeverity.INFO);
            assertThat(i.affected()).isEmpty();
        });
    }

    @Test
    @DisplayName("a broken join key says that results degrade rather than error")
    void joinKeysAreCalledOut() {
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(List.of(venueRemoved()),
                List.of(mapping(MappingKinds.JOIN, VENUE, ModelMapping.ACCEPTED)));

        assertThat(impacts).singleElement().satisfies(i -> {
            assertThat(i.severity()).isEqualTo(DriftSeverity.BREAKING);
            assertThat(i.reason()).contains("join").contains("drops rows");
        });
    }

    @Test
    @DisplayName("a new column is INFO even when mappings exist elsewhere")
    void additionsAreAlwaysInfo() {
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(
                List.of(SchemaChange.columnAdded(CLS, CLS + "/doi", "doi")),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.ACCEPTED)));

        assertThat(impacts).singleElement()
                .satisfies(i -> assertThat(i.severity()).isEqualTo(DriftSeverity.INFO));
    }

    @Test
    @DisplayName("a vanished class is never merely informational, even unmapped")
    void classRemovalIsNeverInfo() {
        // GAV's UNION ALL means losing a member returns fewer rows with no error. Nothing
        // downstream can notice, so the report is the only place it can surface.
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(
                List.of(SchemaChange.classRemoved(CLS, "$.authors[*]", 4)), List.of());

        assertThat(impacts).singleElement().satisfies(i -> {
            assertThat(i.severity()).isEqualTo(DriftSeverity.WARNING);
            assertThat(i.reason()).contains("fewer rows");
        });
    }

    @Test
    @DisplayName("a type change on a mapped column is a WARNING — it runs, it may not mean the same")
    void typeChangeIsWarning() {
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(
                List.of(SchemaChange.typeChanged(CLS, VENUE, "venue", "xsd:int", "xsd:string")),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.ACCEPTED)));

        assertThat(impacts).singleElement()
                .satisfies(i -> assertThat(i.severity()).isEqualTo(DriftSeverity.WARNING));
    }

    @Test
    @DisplayName("a re-minted class IRI drags every property beneath it, matched by prefix")
    void classIriChangeMatchesPropertiesByPrefix() {
        // The mapping points at a PROPERTY, and only the CLASS IRI is named in the change.
        // Equality matching would report zero affected mappings for the change that breaks the most.
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(
                List.of(SchemaChange.classIriChanged(CLS, "http://ex.org/source/ds/publications", "row")),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.ACCEPTED),
                        mapping(MappingKinds.CLASS, CLS, ModelMapping.ACCEPTED)));

        assertThat(impacts).singleElement().satisfies(i -> {
            assertThat(i.severity()).isEqualTo(DriftSeverity.BREAKING);
            assertThat(i.affected()).hasSize(2);
        });
    }

    @Test
    @DisplayName("a prefix match does not spill onto a sibling class with a shared stem")
    void prefixMatchIsBounded() {
        // `…/papers` must not swallow `…/papers_archive`. The trailing slash is what stops it.
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(
                List.of(SchemaChange.classIriChanged(CLS, CLS + "_v2", "row")),
                List.of(mapping(MappingKinds.DATATYPE, CLS + "_archive/venue", ModelMapping.ACCEPTED)));

        assertThat(impacts).singleElement()
                .satisfies(i -> assertThat(i.affected()).isEmpty());
    }

    @Test
    @DisplayName("a rename with dependents is flagged repairable; a removal is not")
    void repairability() {
        DriftImpact rename = DriftImpactAnalyzer.analyse(
                List.of(SchemaChange.columnRenamed(CLS, VENUE, CLS + "/conference",
                        "venue", "conference", 0.85, "the only column dropped and the only one added")),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.ACCEPTED))).get(0);
        DriftImpact removal = DriftImpactAnalyzer.analyse(List.of(venueRemoved()),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.ACCEPTED))).get(0);

        assertThat(rename.repairable()).isTrue();
        assertThat(rename.reason()).contains("Repairable");
        assertThat(removal.repairable()).isFalse();
    }

    @Test
    @DisplayName("the report's verdict is the worst impact present")
    void reportVerdict() {
        UUID datasetId = UUID.randomUUID();
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(
                List.of(SchemaChange.columnAdded(CLS, CLS + "/doi", "doi"), venueRemoved()),
                List.of(mapping(MappingKinds.DATATYPE, VENUE, ModelMapping.ACCEPTED)));
        DriftReport report = new DriftReport(datasetId, "old", "new", impacts);

        assertThat(report.verdict()).isEqualTo(DriftSeverity.BREAKING);
        assertThat(report.breaking()).isTrue();
        assertThat(report.countOf(DriftSeverity.INFO)).isEqualTo(1);
        assertThat(report.unchanged()).isFalse();
        assertThat(report.isBaseline()).isFalse();
    }

    @Test
    @DisplayName("a first-ever fingerprint is a baseline, not a finding")
    void baselineReport() {
        DriftReport report = DriftReport.baseline(UUID.randomUUID(), "abc");
        assertThat(report.isBaseline()).isTrue();
        assertThat(report.breaking()).isFalse();
        assertThat(report.verdict()).isEqualTo(DriftSeverity.INFO);
    }
}

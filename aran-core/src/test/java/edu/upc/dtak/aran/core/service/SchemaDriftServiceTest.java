package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.drift.DriftReport;
import edu.upc.dtak.aran.core.domain.drift.DriftSeverity;
import edu.upc.dtak.aran.core.domain.drift.SchemaChangeKind;
import edu.upc.dtak.aran.core.domain.drift.SchemaFingerprint;
import edu.upc.dtak.aran.core.domain.drift.SchemaFingerprintRepository;
import edu.upc.dtak.aran.core.domain.drift.StoredSchemaFingerprint;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The orchestration around the pure diff: baselines, short-circuits, and who gets asked what. */
@ExtendWith(MockitoExtension.class)
class SchemaDriftServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();
    private static final String CLS = "http://ex.org/source/ds/papers";
    private static final StoredSchemaFingerprint.CaptureMethod SNAPSHOT =
            StoredSchemaFingerprint.CaptureMethod.SNAPSHOT;

    @Mock SchemaFingerprintRepository fingerprints;
    @Mock IntegrationModelRepository integrationModels;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TenantContext tenant;
    private SchemaDriftService service;

    @BeforeEach
    void setUp() {
        tenant = new TenantContext(TENANT.toString());
        service = new SchemaDriftService(fingerprints, integrationModels, objectMapper);
    }

    private static SchemaFingerprint fingerprint(String... columns) {
        List<SchemaFingerprint.PropertyShape> props = java.util.Arrays.stream(columns)
                .map(c -> new SchemaFingerprint.PropertyShape(CLS + "/" + c, c, c, "xsd:string"))
                .toList();
        return new SchemaFingerprint(DATASET.toString(),
                List.of(new SchemaFingerprint.ClassShape(CLS, "CSV", "row", props)));
    }

    private StoredSchemaFingerprint stored(SchemaFingerprint fingerprint) {
        try {
            return new StoredSchemaFingerprint(UUID.randomUUID(), TENANT, DATASET,
                    fingerprint.hash(), objectMapper.writeValueAsString(fingerprint),
                    "snap-1", SNAPSHOT, Instant.now());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ModelMapping accepted(String sourceIri) {
        return new ModelMapping(UUID.randomUUID(), TENANT, UUID.randomUUID(),
                MappingKinds.DATATYPE, sourceIri, "http://global/venue", "venue",
                ModelMapping.ACCEPTED, null, null, null, Instant.now());
    }

    @Test
    @DisplayName("a dataset with no stored fingerprint is a baseline, not drift")
    void firstSightingIsBaseline() {
        when(fingerprints.findLatest(tenant, DATASET, SNAPSHOT)).thenReturn(Optional.empty());

        DriftReport report = service.check(tenant, DATASET, fingerprint("title", "venue"), SNAPSHOT);

        assertThat(report.isBaseline()).isTrue();
        assertThat(report.breaking()).isFalse();
        verify(integrationModels, never()).findMappingsUnderIri(any(), any());
    }

    @Test
    @DisplayName("an unchanged hash short-circuits before the diff and before touching mappings")
    void unchangedShortCircuits() {
        // The overwhelmingly common case. Loading every dependent mapping to discover nothing
        // changed would put a query on the hot path of every re-bootstrap.
        SchemaFingerprint same = fingerprint("title", "venue");
        when(fingerprints.findLatest(tenant, DATASET, SNAPSHOT)).thenReturn(Optional.of(stored(same)));

        DriftReport report = service.check(tenant, DATASET, same, SNAPSHOT);

        assertThat(report.unchanged()).isTrue();
        assertThat(report.impacts()).isEmpty();
        verify(integrationModels, never()).findMappingsUnderIri(any(), any());
    }

    @Test
    @DisplayName("a real change is diffed and joined against the mappings that depend on it")
    void breakingChangeIsReported() {
        when(fingerprints.findLatest(tenant, DATASET, SNAPSHOT))
                .thenReturn(Optional.of(stored(fingerprint("title", "venue"))));
        when(integrationModels.findMappingsUnderIri(tenant, CLS))
                .thenReturn(List.of(accepted(CLS + "/venue")));

        DriftReport report = service.check(tenant, DATASET, fingerprint("title"), SNAPSHOT);

        assertThat(report.verdict()).isEqualTo(DriftSeverity.BREAKING);
        assertThat(report.changes()).extracting(c -> c.kind())
                .containsExactly(SchemaChangeKind.COLUMN_REMOVED);
    }

    @Test
    @DisplayName("the same change is not breaking when nothing depends on it")
    void unmappedChangeIsNotBreaking() {
        when(fingerprints.findLatest(tenant, DATASET, SNAPSHOT))
                .thenReturn(Optional.of(stored(fingerprint("title", "venue"))));
        when(integrationModels.findMappingsUnderIri(tenant, CLS)).thenReturn(List.of());

        assertThat(service.check(tenant, DATASET, fingerprint("title"), SNAPSHOT).breaking()).isFalse();
    }

    @Test
    @DisplayName("a fingerprint round-trips through JSON")
    void jsonRoundTrip() throws Exception {
        // The whole baseline mechanism rests on this: a fingerprint that cannot be read back is a
        // baseline that silently never matches.
        SchemaFingerprint original = fingerprint("title", "venue", "year");
        SchemaFingerprint parsed = objectMapper.readValue(
                objectMapper.writeValueAsString(original), SchemaFingerprint.class);

        assertThat(parsed.hash()).isEqualTo(original.hash());
        assertThat(parsed.canonical()).isEqualTo(original.canonical());
    }

    @Test
    @DisplayName("an unreadable stored fingerprint re-baselines rather than blocking the bootstrap")
    void corruptBaselineDoesNotBlock() {
        // A storage problem is not evidence that the source changed. Reporting drift here would
        // wedge re-bootstrap on a dataset whose source is perfectly fine.
        StoredSchemaFingerprint corrupt = new StoredSchemaFingerprint(UUID.randomUUID(), TENANT,
                DATASET, "deadbeef", "{not json", "snap-1", SNAPSHOT, Instant.now());
        when(fingerprints.findLatest(tenant, DATASET, SNAPSHOT)).thenReturn(Optional.of(corrupt));

        DriftReport report = service.check(tenant, DATASET, fingerprint("title"), SNAPSHOT);

        assertThat(report.isBaseline()).isTrue();
        assertThat(report.breaking()).isFalse();
    }

    @Test
    @DisplayName("a mapping reachable from two class prefixes is counted once")
    void dependentsAreDeduplicated() {
        // Nested JSON classes have nested IRIs, so prefix queries overlap and the same mapping
        // comes back more than once. Counting it twice would inflate every impact report.
        SchemaFingerprint before = new SchemaFingerprint(DATASET.toString(), List.of(
                new SchemaFingerprint.ClassShape(CLS, "JSON", "$", List.of(
                        new SchemaFingerprint.PropertyShape(CLS + "/venue", "venue", "venue", "xsd:string"))),
                new SchemaFingerprint.ClassShape(CLS + ".a", "JSON", "$.a[*]", List.of())));
        SchemaFingerprint after = new SchemaFingerprint(DATASET.toString(), List.of(
                new SchemaFingerprint.ClassShape(CLS, "JSON", "$", List.of()),
                new SchemaFingerprint.ClassShape(CLS + ".a", "JSON", "$.a[*]", List.of())));

        ModelMapping shared = accepted(CLS + "/venue");
        when(fingerprints.findLatest(tenant, DATASET, SNAPSHOT)).thenReturn(Optional.of(stored(before)));
        when(integrationModels.findMappingsUnderIri(tenant, CLS)).thenReturn(List.of(shared));
        when(integrationModels.findMappingsUnderIri(tenant, CLS + ".a")).thenReturn(List.of(shared));

        DriftReport report = service.check(tenant, DATASET, after, SNAPSHOT);

        assertThat(report.impacts()).singleElement()
                .satisfies(i -> assertThat(i.affected()).hasSize(1));
    }

    @Test
    @DisplayName("recording is a separate call, so a rejected change never advances the baseline")
    void recordIsSeparateFromCheck() {
        // If check() recorded, a breaking change the user refused would still become the new
        // baseline — and the next check would compare against a shape that was never live and
        // cheerfully report no drift.
        when(fingerprints.findLatest(tenant, DATASET, SNAPSHOT))
                .thenReturn(Optional.of(stored(fingerprint("title", "venue"))));
        when(integrationModels.findMappingsUnderIri(tenant, CLS)).thenReturn(List.of());

        service.check(tenant, DATASET, fingerprint("title"), SNAPSHOT);
        verify(fingerprints, never()).save(any(), any());

        when(fingerprints.save(eq(tenant), any())).thenAnswer(i -> i.getArgument(1));
        service.record(tenant, DATASET, fingerprint("title"), "snap-2", SNAPSHOT);
        verify(fingerprints, times(1)).save(eq(tenant), any());
    }
}

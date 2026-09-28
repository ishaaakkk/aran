package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.drift.DriftImpact;
import edu.upc.dtak.aran.core.domain.drift.DriftImpactAnalyzer;
import edu.upc.dtak.aran.core.domain.drift.DriftReport;
import edu.upc.dtak.aran.core.domain.drift.SchemaChange;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.service.SchemaDriftService;
import edu.upc.dtak.aran.core.source.SourceConnectors;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The gate: re-bootstrapping must stop being unconditionally destructive.
 *
 * <p>Before this, {@code bootstrap} called {@code clearGraph} then {@code addTriples} with no
 * question asked, so a renamed column silently took every mapping that referenced it. These tests
 * pin the new contract — a breaking candidate writes <em>nothing</em>, and the escape hatch is
 * explicit rather than the default.
 */
@ExtendWith(MockitoExtension.class)
class BootstrappingDriftGateTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private static final UUID DATASET = UUID.randomUUID();
    private static final String SNAPSHOT = "snap-1";
    private static final String CLS = "http://ex.org/source/" + DATASET + "/papers";

    @Mock LakeClient lakeClient;
    @Mock GraphStore graphStore;
    @Mock DatasetRepository datasetRepository;
    @Mock edu.upc.dtak.aran.core.domain.DataRepositoryRepository dataRepositoryRepository;
    @Mock SchemaDriftService driftService;
    @Mock edu.upc.dtak.aran.core.domain.IntegrationModelRepository integrationModels;

    private BootstrappingOperator operator;

    @BeforeEach
    void setUp() {
        operator = new BootstrappingOperator(lakeClient, graphStore, datasetRepository,
                new SourceConnectors(List.of(new CsvConnector(), new JsonConnector(),
                        new ParquetConnector())),
                dataRepositoryRepository, driftService, integrationModels, new ObjectMapper(),
                new edu.upc.dtak.aran.core.net.OutboundAddressGuard(false));

        // Lenient because one test deliberately never reaches this fixture: the unknown-dataset
        // case bails before any snapshot is fetched, which is precisely what it asserts.
        Dataset dataset = new Dataset(DATASET, UUID.randomUUID(), "papers.csv", "",
                SNAPSHOT, List.of(), null, DatasetStatus.READY);
        org.mockito.Mockito.lenient()
                .when(datasetRepository.findById(any(), eq(DATASET))).thenReturn(Optional.of(dataset));
        org.mockito.Mockito.lenient()
                .when(lakeClient.downloadSnapshot(any(), eq(SNAPSHOT))).thenReturn(
                        new LakeClient.SnapshotBytes(
                                "title,venue\nA,VLDB\n".getBytes(StandardCharsets.UTF_8), "text/csv"));
    }

    private OperatorRequest request(String operation, Map<String, String> params) {
        return new OperatorRequest(TENANT, UUID.randomUUID(), operation, params);
    }

    /** A report whose single change breaks an accepted mapping — the case the gate exists for. */
    private static DriftReport breakingReport() {
        ModelMapping accepted = new ModelMapping(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), MappingKinds.DATATYPE, CLS + "/venue", "http://global/venue",
                "venue", ModelMapping.ACCEPTED, null, null, null, Instant.now());
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(
                List.of(SchemaChange.columnRemoved(CLS, CLS + "/venue", "venue")), List.of(accepted));
        return new DriftReport(DATASET, "baseline-hash", "candidate-hash", impacts);
    }

    @Test
    @DisplayName("a breaking candidate writes NOTHING — not the graph, not the fingerprint")
    void breakingCandidateIsWithheld() {
        when(driftService.check(any(), eq(DATASET), any(), any())).thenReturn(breakingReport());

        OperatorResult result = operator.execute(request("bootstrap",
                Map.of("datasetId", DATASET.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("applied")).isEqualTo("false");
        assertThat(result.data().get("driftVerdict")).isEqualTo("BREAKING");
        verify(graphStore, never()).clearGraph(any(), any());
        verify(graphStore, never()).addTriples(any(), any(), any());
        verify(driftService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("and marks the dataset DRIFTED, not FAILED — nothing went wrong")
    void breakingCandidateMarksDrifted() {
        when(driftService.check(any(), eq(DATASET), any(), any())).thenReturn(breakingReport());

        operator.execute(request("bootstrap", Map.of("datasetId", DATASET.toString())));

        ArgumentCaptor<Dataset> saved = ArgumentCaptor.forClass(Dataset.class);
        verify(datasetRepository).save(any(), saved.capture());
        assertThat(saved.getValue().bootstrapStatus()).isEqualTo(DatasetStatus.DRIFTED);
        // The previous graph JSON is untouched — the dataset still serves its old schema.
        assertThat(saved.getValue().snapshotId()).isEqualTo(SNAPSHOT);
    }

    @Test
    @DisplayName("force=true applies the same breaking candidate")
    void forceApplies() {
        // Renaming a column on purpose and repairing the mappings afterwards is a legitimate
        // workflow. The gate defers to the user; it does not overrule them.
        when(driftService.check(any(), eq(DATASET), any(), any())).thenReturn(breakingReport());

        OperatorResult result = operator.execute(request("bootstrap",
                Map.of("datasetId", DATASET.toString(), "force", "true")));

        assertThat(result.data().get("applied")).isEqualTo("true");
        verify(graphStore).clearGraph(any(), any());
        verify(graphStore).addTriples(any(), any(), any());
        verify(driftService).record(any(), eq(DATASET), any(), eq(SNAPSHOT), any());
    }

    @Test
    @DisplayName("a non-breaking change applies and advances the baseline")
    void nonBreakingApplies() {
        when(driftService.check(any(), eq(DATASET), any(), any()))
                .thenReturn(DriftReport.baseline(DATASET, "candidate-hash"));

        OperatorResult result = operator.execute(request("bootstrap",
                Map.of("datasetId", DATASET.toString())));

        assertThat(result.data().get("applied")).isEqualTo("true");
        assertThat(result.data().get("driftBaseline")).isEqualTo("true");
        verify(graphStore).addTriples(any(), any(), any());
        verify(driftService).record(any(), eq(DATASET), any(), eq(SNAPSHOT), any());
    }

    @Test
    @DisplayName("drift-check writes nothing even when the candidate is perfectly safe")
    void driftCheckIsReadOnly() {
        when(driftService.check(any(), eq(DATASET), any(), any()))
                .thenReturn(DriftReport.baseline(DATASET, "candidate-hash"));

        OperatorResult result = operator.execute(request("drift-check",
                Map.of("datasetId", DATASET.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("applied")).isEqualTo("false");
        verify(graphStore, never()).clearGraph(any(), any());
        verify(graphStore, never()).addTriples(any(), any(), any());
        verify(datasetRepository, never()).save(any(), any());
        verify(driftService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the fingerprint handed to the check comes from the CANDIDATE, not the stored graph")
    void checksTheCandidateNotTheLiveGraph() {
        // If this read the live graph it would compare the baseline against itself and never
        // detect anything — the failure mode that looks exactly like "no drift".
        when(driftService.check(any(), eq(DATASET), any(), any()))
                .thenReturn(DriftReport.baseline(DATASET, "candidate-hash"));

        operator.execute(request("drift-check", Map.of("datasetId", DATASET.toString())));

        ArgumentCaptor<edu.upc.dtak.aran.core.domain.drift.SchemaFingerprint> captured =
                ArgumentCaptor.forClass(edu.upc.dtak.aran.core.domain.drift.SchemaFingerprint.class);
        verify(driftService).check(any(), eq(DATASET), captured.capture(), any());
        assertThat(captured.getValue().classes()).hasSize(1);
        assertThat(captured.getValue().classes().get(0).properties())
                .extracting(p -> p.sourceRef())
                .containsExactlyInAnyOrder("title", "venue");
        verify(graphStore, never()).query(any(), any());
    }

    @Test
    @DisplayName("drift-check on an unknown dataset fails rather than reporting a clean bill")
    void driftCheckOnMissingDataset() {
        UUID missing = UUID.randomUUID();
        when(datasetRepository.findById(any(), eq(missing))).thenReturn(Optional.empty());

        OperatorResult result = operator.execute(request("drift-check",
                Map.of("datasetId", missing.toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DATASET_NOT_FOUND");
    }

    @Test
    @DisplayName("a fingerprint that cannot be recorded does not fail a bootstrap that worked")
    void recordFailureDoesNotFailTheBootstrap() {
        // The graph is already live at that point. Failing here would report a broken bootstrap
        // for a dataset that is serving the new schema perfectly well.
        when(driftService.check(any(), eq(DATASET), any(), any()))
                .thenReturn(DriftReport.baseline(DATASET, "candidate-hash"));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(driftService).record(any(), any(), any(), any(), any());

        OperatorResult result = operator.execute(request("bootstrap",
                Map.of("datasetId", DATASET.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("applied")).isEqualTo("true");
    }
}

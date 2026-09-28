package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.source.SourceConnectors;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BootstrappingOperator. Stubs lake, graphStore, and datasetRepository
 * so we only exercise the operator routing and error-handling logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BootstrappingOperator")
class BootstrappingOperatorTest {

    @Mock LakeClient lakeClient;
    @Mock GraphStore graphStore;
    @Mock DatasetRepository datasetRepository;
    @Mock edu.upc.dtak.aran.core.domain.DataRepositoryRepository dataRepositoryRepository;
    @Mock edu.upc.dtak.aran.core.service.SchemaDriftService driftService;
    @Mock edu.upc.dtak.aran.core.domain.IntegrationModelRepository integrationModels;

    private BootstrappingOperator operator;

    private static final String TENANT_ID = UUID.randomUUID().toString();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final String SNAPSHOT_ID = "snap-abc";

    @BeforeEach
    void setUp() {
        // Real registry with the production connectors — the operator's dispatch is
        // exactly what runs in prod; only lake/graph/repo are mocked.
        SourceConnectors connectors = new SourceConnectors(
                List.of(new CsvConnector(), new JsonConnector(), new ParquetConnector()));
        operator = new BootstrappingOperator(lakeClient, graphStore, datasetRepository, connectors,
                dataRepositoryRepository, driftService, integrationModels,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new edu.upc.dtak.aran.core.net.OutboundAddressGuard(false));

        // These tests are about the operator's dispatch and write path, not about drift. Default
        // the check to "no baseline recorded", which is what a first bootstrap actually sees and
        // is the only verdict that never withholds the write. Drift behaviour has its own tests.
        org.mockito.Mockito.lenient()
                .when(driftService.check(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> edu.upc.dtak.aran.core.domain.drift.DriftReport.baseline(
                        call.getArgument(1), "candidate"));
    }

    @Nested
    @DisplayName("ping")
    class Ping {
        @Test
        @DisplayName("returns success with status=ok")
        void ping_returns_ok() {
            OperatorResult result = operator.execute(req("ping", Map.of()));
            assertThat(result.success()).isTrue();
            assertThat(result.data().get("operator")).isEqualTo("bootstrapping");
        }
    }

    @Nested
    @DisplayName("bootstrap operation")
    class BootstrapOp {

        @Test
        @DisplayName("fails with MISSING_PARAMETER when datasetId is absent")
        void missing_dataset_id() {
            OperatorResult result = operator.execute(req("bootstrap", Map.of()));
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("MISSING_PARAMETER");
        }

        @Test
        @DisplayName("fails with INVALID_PARAMETER when datasetId is not a UUID")
        void invalid_dataset_id() {
            OperatorResult result = operator.execute(req("bootstrap", Map.of("datasetId", "not-a-uuid")));
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("INVALID_PARAMETER");
        }

        @Test
        @DisplayName("fails with DATASET_NOT_FOUND when repository returns empty")
        void dataset_not_found() {
            when(datasetRepository.findById(any(), eq(DATASET_ID))).thenReturn(Optional.empty());
            OperatorResult result = operator.execute(req("bootstrap",
                    Map.of("datasetId", DATASET_ID.toString())));
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("DATASET_NOT_FOUND");
        }

        @Test
        @DisplayName("fails with NO_SNAPSHOT when dataset has no snapshot")
        void no_snapshot() {
            Dataset ds = new Dataset(DATASET_ID, UUID.randomUUID(), "orders", "");
            when(datasetRepository.findById(any(), eq(DATASET_ID))).thenReturn(Optional.of(ds));
            OperatorResult result = operator.execute(req("bootstrap",
                    Map.of("datasetId", DATASET_ID.toString())));
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("NO_SNAPSHOT");
        }

        @Test
        @DisplayName("fails with LAKE_DOWNLOAD_FAILED when lake throws")
        void lake_download_fails() {
            Dataset ds = new Dataset(DATASET_ID, UUID.randomUUID(), "orders", "", SNAPSHOT_ID);
            when(datasetRepository.findById(any(), eq(DATASET_ID))).thenReturn(Optional.of(ds));
            when(lakeClient.downloadSnapshot(any(), eq(SNAPSHOT_ID)))
                    .thenThrow(new RuntimeException("lake unreachable"));
            OperatorResult result = operator.execute(req("bootstrap",
                    Map.of("datasetId", DATASET_ID.toString())));
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("LAKE_DOWNLOAD_FAILED");
        }

        @Test
        @DisplayName("fails with UNKNOWN_FORMAT when MIME is opaque and no extension hint")
        void unknown_format() {
            Dataset ds = new Dataset(DATASET_ID, UUID.randomUUID(), "archive", "", SNAPSHOT_ID);
            when(datasetRepository.findById(any(), eq(DATASET_ID))).thenReturn(Optional.of(ds));
            when(lakeClient.downloadSnapshot(any(), eq(SNAPSHOT_ID)))
                    .thenReturn(new LakeClient.SnapshotBytes(
                            "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8),
                            "application/octet-stream"));
            OperatorResult result = operator.execute(req("bootstrap",
                    Map.of("datasetId", DATASET_ID.toString())));
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("UNKNOWN_FORMAT");
        }

        @Test
        @DisplayName("succeeds for a CSV dataset: clears old graph and writes new triples")
        void bootstrap_csv_success() {
            Dataset ds = new Dataset(DATASET_ID, UUID.randomUUID(), "orders.csv", "", SNAPSHOT_ID);
            when(datasetRepository.findById(any(), eq(DATASET_ID))).thenReturn(Optional.of(ds));
            when(lakeClient.downloadSnapshot(any(), eq(SNAPSHOT_ID)))
                    .thenReturn(new LakeClient.SnapshotBytes(
                            "id,amount\n1,99.50\n".getBytes(StandardCharsets.UTF_8),
                            "text/csv"));

            OperatorResult result = operator.execute(req("bootstrap",
                    Map.of("datasetId", DATASET_ID.toString())));

            assertThat(result.success()).isTrue();
            assertThat(result.data()).containsKey("graphUri");
            assertThat(result.data().get("format")).isEqualTo("CSV");
            verify(graphStore).clearGraph(any(), eq(BootstrapEngine.sourceGraphUri(DATASET_ID.toString())));
            verify(graphStore).addTriples(any(), eq(BootstrapEngine.sourceGraphUri(DATASET_ID.toString())), any());
            verify(datasetRepository).save(any(), any(Dataset.class));
        }

        @Test
        @DisplayName("explicit format=JSON parameter overrides content-type inference")
        void explicit_format_overrides_content_type() {
            byte[] json = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
            Dataset ds = new Dataset(DATASET_ID, UUID.randomUUID(), "file", "", SNAPSHOT_ID);
            when(datasetRepository.findById(any(), eq(DATASET_ID))).thenReturn(Optional.of(ds));
            when(lakeClient.downloadSnapshot(any(), eq(SNAPSHOT_ID)))
                    .thenReturn(new LakeClient.SnapshotBytes(json, "application/octet-stream"));

            OperatorResult result = operator.execute(req("bootstrap",
                    Map.of("datasetId", DATASET_ID.toString(), "format", "JSON")));

            assertThat(result.success()).isTrue();
            assertThat(result.data().get("format")).isEqualTo("JSON");
            verify(datasetRepository).save(any(), any(Dataset.class));
        }
    }

    @Nested
    @DisplayName("unsupported operation")
    class UnsupportedOp {
        @Test
        @DisplayName("returns UNSUPPORTED_OPERATION for any unknown operation key")
        void unknown_operation() {
            OperatorResult result = operator.execute(req("unknown-op", Map.of()));
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("UNSUPPORTED_OPERATION");
        }
    }

    private OperatorRequest req(String operation, Map<String, String> params) {
        return new OperatorRequest(TENANT_ID, USER_ID, operation, params);
    }
}

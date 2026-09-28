package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.SingerTapExtractor;
import edu.upc.dtak.aran.core.domain.SingerTapRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.source.TapConfig;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Registering a dataset from a Singer stream, at the service tier (T6).
 *
 * <p>The whole point of the tranche is that a tap ends up in the lake through the <em>existing</em>
 * tail rather than a parallel one, so that is what this asserts: extract, then the same
 * {@code initUpload}/{@code uploadToS3}/{@code completeUpload}/{@code attachSnapshot} sequence an
 * uploaded file and a JDBC extract take, then the ordinary bootstrap.
 *
 * <p>The extractor is a recording fake here and the job body runs inline — the same shape as
 * {@code DatasetObjectStorageImportTest} and {@code DatasetJdbcExtractImportTest}. The real tap
 * subprocess is proven against scripted taps in {@code SingerTapRunnerExtractorTest}; between the
 * two, the path is covered end to end with no Docker.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetSingerTapImportTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final String SECRET = "k-live-9f2-do-not-leak";

    @Mock
    private DataRepositoryService repositoryService;
    @Mock
    private DatasetRepository datasetRepository;
    @Mock
    private JobService jobService;
    @Mock
    private DatasetRefreshRepository refreshRepository;

    private final CapturingLakeClient lake = new CapturingLakeClient();
    private final RecordingTapExtractor extractor = new RecordingTapExtractor();
    private final Map<UUID, Dataset> stored = new java.util.HashMap<>();
    private final List<DatasetRefresh> savedRefreshes = new java.util.ArrayList<>();

    /** Records what stream it was asked for, and hands back parquet-shaped bytes. */
    private static final class RecordingTapExtractor implements SingerTapExtractor {
        String stream;
        long maxBytes;
        RuntimeException failWith;

        @Override
        public List<String> discoverStreams(SingerTapRepository repository) {
            return List.of("users", "orders");
        }

        @Override
        public Extracted extract(SingerTapRepository repository, String stream, long maxBytes) {
            this.stream = stream;
            this.maxBytes = maxBytes;
            if (failWith != null) {
                throw failWith;
            }
            return new Extracted("PAR1-tap-records".getBytes(StandardCharsets.UTF_8), 21);
        }
    }

    private static final edu.upc.dtak.aran.core.operator.Operator NOOP_BOOTSTRAP =
            new edu.upc.dtak.aran.core.operator.Operator() {
                @Override public String getOperatorId() {
                    return "bootstrapping";
                }

                @Override public String getDisplayName() {
                    return "no-op bootstrap";
                }

                @Override public edu.upc.dtak.aran.core.operator.OperatorResult execute(
                        edu.upc.dtak.aran.core.operator.OperatorRequest request) {
                    return edu.upc.dtak.aran.core.operator.OperatorResult.success(Map.of());
                }
            };

    private DatasetService service(Optional<SingerTapExtractor> tapExtractor) {
        return new DatasetService(
                datasetRepository, repositoryService, lake,
                new OperatorOrchestrator(List.of(NOOP_BOOTSTRAP)), jobService,
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), Optional.empty(), Optional.empty(),
                Optional.of(refreshRepository), Optional.empty(),
                org.mockito.Mockito.mock(IntegrationModelService.class), tapExtractor);
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private static SingerTapRepository tapRepository() {
        return new SingerTapRepository(REPOSITORY, PROJECT, TENANT, "rates", "",
                "exchangeratesapi", TapConfig.toJson(Map.of("api_key", SECRET)));
    }

    @BeforeEach
    void setUp() {
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> {
            Dataset d = inv.getArgument(1);
            stored.put(d.id(), d);
            return d;
        });
        when(datasetRepository.findById(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<UUID>getArgument(1))));
        when(refreshRepository.save(any(), any())).thenAnswer(inv -> {
            DatasetRefresh refresh = inv.getArgument(1);
            savedRefreshes.add(refresh);
            return refresh;
        });
        when(refreshRepository.findByDataset(any(), any()))
                .thenAnswer(inv -> savedRefreshes.stream().findFirst());

        // Inline, on the calling thread: the extract, the upload and the bootstrap all live inside
        // the job body, so a mock that only recorded the submission would assert nothing.
        when(jobService.submit(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            JobService.JobTask task = inv.getArgument(5);
            task.run(edu.upc.dtak.aran.core.operator.ProgressReporter.NOOP);
            return new Job(
                    UUID.randomUUID(), TENANT, PROJECT, inv.getArgument(2),
                    edu.upc.dtak.aran.core.domain.JobStatus.PENDING, Map.of(),
                    null, null, null, null, null, null, Instant.now(), null, null);
        });
    }

    private void repositoryIs(DataRepository repository) {
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(repository);
    }

    private void streamsAre(String... streams) {
        when(repositoryService.listAvailableDatasets(any(), any(), any()))
                .thenReturn(List.of(streams));
    }

    @Test
    @DisplayName("a stream is extracted and uploaded through the ordinary lake tail, then bootstrapped")
    void streamIsExtractedAndUploaded() {
        repositoryIs(tapRepository());
        streamsAre("users", "orders");

        var registration = service(Optional.of(extractor))
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "users");

        assertThat(extractor.stream).isEqualTo("users");
        assertThat(lake.uploadedBytes).asString().startsWith("PAR1");
        assertThat(lake.uploadContentType).isEqualTo("application/vnd.apache.parquet");
        assertThat(lake.initContentType).isEqualTo("application/vnd.apache.parquet");
        assertThat(registration.dataset().name()).isEqualTo("users");
        assertThat(registration.dataset().snapshotId()).isNotNull();
        // READY because the job ran inline; PENDING→READY in production, the same states.
        assertThat(registration.dataset().bootstrapStatus()).isEqualTo(DatasetStatus.READY);
        assertThat(submittedJobTypes()).containsExactly("dataset:import");
    }

    @Test
    @DisplayName("the dataset gains a refresh upstream naming the stream, manual by default")
    void refreshUpstreamIsRecorded() {
        // Without this row the dataset has no upstream and can never be re-pulled — the design's
        // "refresh is free" claim is exactly this row plus the dispatch in refreshDataset.
        repositoryIs(tapRepository());
        streamsAre("users");

        service(Optional.of(extractor))
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "users");

        assertThat(savedRefreshes).hasSize(1);
        assertThat(savedRefreshes.get(0).sourceTable()).isEqualTo("users");
        assertThat(savedRefreshes.get(0).policy())
                .isInstanceOf(edu.upc.dtak.aran.core.source.RefreshPolicy.Manual.class);
    }

    @Test
    @DisplayName("a refresh re-runs the same tap extract and records its outcome")
    void refreshReRunsTheTap() {
        repositoryIs(tapRepository());
        streamsAre("users");
        DatasetService service = service(Optional.of(extractor));
        var registration = service
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "users");
        extractor.stream = null;

        service.refreshDataset(caller(), PROJECT, REPOSITORY, registration.dataset().id(),
                savedRefreshes.get(0));

        assertThat(extractor.stream).isEqualTo("users");
        assertThat(submittedJobTypes()).containsExactly("dataset:import", "dataset:refresh");
        assertThat(savedRefreshes.get(savedRefreshes.size() - 1).lastRefreshAt()).isNotNull();
    }

    @Test
    @DisplayName("the extract is capped, and the cap is a real number rather than Long.MAX_VALUE")
    void extractIsCapped() {
        repositoryIs(tapRepository());
        streamsAre("users");

        service(Optional.of(extractor))
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "users");

        assertThat(extractor.maxBytes).isPositive().isLessThan(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("a stream the tap does not offer is refused before anything runs")
    void streamMustBeInTheLiveListing() {
        // The same membership rule the relational import applies to a table name: what a caller
        // sends is checked against what the source actually offers, not trusted.
        repositoryIs(tapRepository());
        streamsAre("users", "orders");

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "invoices"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(extractor.stream).isNull();
    }

    @Test
    @DisplayName("a non-tap repository is refused rather than run")
    void nonTapRepositoryIsRefused() {
        repositoryIs(new RelationalDatabaseRepository(
                REPOSITORY, PROJECT, TENANT, "pg", "", "POSTGRESQL", "db", 5432, "d", "u", "p",
                null, false));

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "users"))
                .isInstanceOf(UnsupportedImportSourceException.class);
    }

    @Test
    @DisplayName("a build with no tap extractor refuses instead of NPEing")
    void absentExtractorIsARefusal() {
        // The operator modules are runtimeOnly, so this is a state production can genuinely be in.
        repositoryIs(tapRepository());
        streamsAre("users");

        assertThatThrownBy(() -> service(Optional.empty())
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "users"))
                .isInstanceOf(UnsupportedImportSourceException.class);
    }

    @Test
    @DisplayName("a failed extract leaves the dataset FAILED, not stuck PENDING")
    void failedExtractMarksTheDataset() {
        repositoryIs(tapRepository());
        streamsAre("users");
        extractor.failWith = new IllegalStateException("Singer tap 'exchangeratesapi' failed");

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "users"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(stored.values()).allMatch(d -> d.bootstrapStatus() == DatasetStatus.FAILED);
    }

    @Test
    @DisplayName("no tap credential reaches the dataset row or the registration response")
    void noCredentialLeaks() {
        repositoryIs(tapRepository());
        streamsAre("users");

        var registration = service(Optional.of(extractor))
                .registerDatasetFromSingerStream(caller(), PROJECT, REPOSITORY, "users");

        assertThat(registration.dataset().name()).doesNotContain(SECRET);
        assertThat(registration.dataset().description()).doesNotContain(SECRET);
        assertThat(String.valueOf(registration.dataset().snapshotId())).doesNotContain(SECRET);
    }

    private List<String> submittedJobTypes() {
        ArgumentCaptor<String> jobType = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jobService, org.mockito.Mockito.atLeastOnce())
                .submit(any(), any(), jobType.capture(), any(), any(), any());
        return jobType.getAllValues();
    }
}

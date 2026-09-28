package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.ObjectStorageExtractor;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
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
 * The MATERIALIZED object-storage import at the service level: one object is copied out of the store
 * (by the {@link ObjectStorageExtractor} port) and uploaded into the lake through the very same
 * {@code initUpload}/{@code uploadToS3}/{@code completeUpload} tail an uploaded file or a JDBC extract
 * takes — there is no parallel uploader. The extractor is a recording fake here; the real DuckDB copy
 * over httpfs is proven against Garage in {@code DuckDbObjectStorageExtractorGarageTest}.
 *
 * <p>The job body runs inline (see {@code setUp}), because — as in the JDBC-import test this mirrors —
 * everything worth asserting on (the copy, the upload, the bootstrap) lives inside it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetObjectStorageImportTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final String SECRET = "S3kr3t-No-Leak-9xQ";

    @Mock
    private DataRepositoryService repositoryService;
    @Mock
    private DatasetRepository datasetRepository;
    @Mock
    private JobService jobService;

    private final CapturingLakeClient lake = new CapturingLakeClient();
    private final RecordingObjectExtractor extractor = new RecordingObjectExtractor();
    private final java.util.Map<UUID, Dataset> stored = new java.util.HashMap<>();

    /** Records what it was asked to copy and hands back parquet-shaped bytes without an object store. */
    private static final class RecordingObjectExtractor implements ObjectStorageExtractor {
        String objectKey;
        String format;
        long maxBytes;
        RuntimeException failWith;

        @Override
        public Extracted extract(ObjectStorageRepository repository, String objectKey, String format,
                                 long maxBytes) {
            this.objectKey = objectKey;
            this.format = format;
            this.maxBytes = maxBytes;
            if (failWith != null) {
                throw failWith;
            }
            return new Extracted("PAR1-object-copy".getBytes(StandardCharsets.UTF_8), 3);
        }
    }

    /**
     * Does no bootstrapping, but records the {@code filenameHint} the pipeline handed it — the
     * one value that decides which connector claims the copy (S4-9).
     */
    private static final class RecordingBootstrap
            implements edu.upc.dtak.aran.core.operator.Operator {
        String filenameHint;

        @Override public String getOperatorId() {
            return "bootstrapping";
        }

        @Override public String getDisplayName() {
            return "recording no-op bootstrap";
        }

        @Override public edu.upc.dtak.aran.core.operator.OperatorResult execute(
                edu.upc.dtak.aran.core.operator.OperatorRequest request) {
            this.filenameHint = request.parameters().get("filenameHint");
            return edu.upc.dtak.aran.core.operator.OperatorResult.success(Map.of());
        }
    }

    private final RecordingBootstrap bootstrap = new RecordingBootstrap();

    private DatasetService service(Optional<ObjectStorageExtractor> objectExtractor) {
        return new DatasetService(
                datasetRepository, repositoryService, lake,
                new OperatorOrchestrator(List.of(bootstrap)), jobService,
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), Optional.empty(), objectExtractor, Optional.empty(),
                Optional.empty(), org.mockito.Mockito.mock(IntegrationModelService.class),
                Optional.empty());
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private static ObjectStorageRepository objectStore() {
        return new ObjectStorageRepository(
                REPOSITORY, PROJECT, TENANT, "lake", "", "MINIO", "http://localhost:9000",
                "bucket", "AKID", SECRET, "us-east-1");
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

        // The job body runs INLINE, on the calling thread — the copy, the upload and the bootstrap all
        // live inside it, so a mock that only recorded the submission would test nothing.
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

    @Test
    @DisplayName("an object is copied over the extractor and uploaded through the ordinary lake tail")
    void object_is_copied_and_uploaded() {
        repositoryIs(objectStore());

        var registration = service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "people.parquet", "PARQUET");

        assertThat(extractor.objectKey).isEqualTo("people.parquet");
        assertThat(extractor.format).isEqualTo("PARQUET");
        // The exact same upload tail every materialize path takes — no parallel uploader.
        assertThat(lake.uploadedBytes).asString().startsWith("PAR1");
        assertThat(lake.uploadContentType).isEqualTo("application/vnd.apache.parquet");
        assertThat(lake.initContentType).isEqualTo("application/vnd.apache.parquet");
        assertThat(registration.dataset().name()).isEqualTo("people.parquet");
        // READY because the job ran inline; PENDING→READY in production, the same states.
        assertThat(registration.dataset().bootstrapStatus()).isEqualTo(DatasetStatus.READY);
        assertThat(registration.dataset().snapshotId()).isNotNull();
        assertThat(submittedJobTypes()).containsExactly("dataset:import");
    }

    @Test
    @DisplayName("the copy is capped, and the cap is a real number rather than Long.MAX_VALUE")
    void copy_is_capped() {
        // The extractor holds the whole parquet in the backend heap, so an uncapped copy is an OOM
        // with extra steps — the same guard the JDBC extract has.
        repositoryIs(objectStore());

        service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "people.parquet", "PARQUET");

        assertThat(extractor.maxBytes).isPositive().isLessThan(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("a .csv object is bootstrapped under a .parquet hint — the copy is parquet (S4-9)")
    void csv_object_is_hinted_as_the_parquet_the_copy_produced() {
        // The old hint passed a key with a dot straight through, so bootstrap was told
        // "raw/people.csv" about a file whose bytes and content type are parquet. CsvConnector
        // claims the extension, ParquetConnector claims the content type, and SourceConnectors
        // refuses two claims — the dataset went FAILED with the copy already in the lake.
        repositoryIs(objectStore());

        service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "raw/people.csv", "CSV");

        assertThat(bootstrap.filenameHint)
                .as("the hint must describe the file the copy produced, not the object it read")
                .isEqualTo("raw/people.parquet");
        // The extractor still reads the CSV object — only the hint describes the output.
        assertThat(extractor.objectKey).isEqualTo("raw/people.csv");
        assertThat(extractor.format).isEqualTo("CSV");
    }

    @Test
    @DisplayName("a key with no extension, and one that is already parquet, get the same hint")
    void hint_is_parquet_whatever_the_key_looked_like() {
        repositoryIs(objectStore());

        service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "raw/people", "PARQUET");
        assertThat(bootstrap.filenameHint).isEqualTo("raw/people.parquet");

        service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "raw/people.parquet", "PARQUET");
        assertThat(bootstrap.filenameHint).isEqualTo("raw/people.parquet");

        // A dot in a directory name is not an extension. The old test was the key as a whole,
        // which left this one with no extension at all for the detector to read.
        service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "v1.2/people", "PARQUET");
        assertThat(bootstrap.filenameHint).isEqualTo("v1.2/people.parquet");
    }

    @Test
    @DisplayName("the declared format is passed through to the extractor's reader choice")
    void format_is_passed_through() {
        repositoryIs(objectStore());

        service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "people.csv", "CSV");

        assertThat(extractor.format).isEqualTo("CSV");
    }

    @Test
    @DisplayName("no secret ever appears in what the registration returns or the dataset it persists")
    void the_registration_surface_leaks_no_secret() {
        // DatasetService hands the whole repository (secret included) to the extractor and touches
        // the credential nowhere else; this pins that the credential does not escape into the
        // dataset row, its description, or the registration the caller gets back.
        repositoryIs(objectStore());

        var registration = service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "people.parquet", "PARQUET");

        assertThat(registration.dataset().name()).doesNotContain(SECRET);
        assertThat(registration.dataset().description()).doesNotContain(SECRET);
        assertThat(String.valueOf(registration.dataset().snapshotId())).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a blank object key is refused before any copy runs")
    void blank_object_key_is_refused() {
        repositoryIs(objectStore());

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "  ", "PARQUET"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(extractor.objectKey).isNull();
    }

    @Test
    @DisplayName("a non-object-storage repository is refused rather than copied")
    void non_object_storage_repo_is_refused() {
        repositoryIs(new RelationalDatabaseRepository(
                REPOSITORY, PROJECT, TENANT, "pg", "", "POSTGRESQL", "db", 5432, "d", "u", "p", null, false));

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "people.parquet", "PARQUET"))
                .isInstanceOf(UnsupportedImportSourceException.class);

        assertThat(extractor.objectKey).isNull();
    }

    @Test
    @DisplayName("a build with no object-storage extractor bean refuses instead of NPEing")
    void absent_extractor_is_a_refusal() {
        // The operator modules are runtimeOnly, so this is a state production can genuinely be in.
        repositoryIs(objectStore());

        assertThatThrownBy(() -> service(Optional.empty())
                .registerDatasetFromObjectStorage(caller(), PROJECT, REPOSITORY, "people.parquet", "PARQUET"))
                .isInstanceOf(UnsupportedImportSourceException.class);
    }

    private List<String> submittedJobTypes() {
        ArgumentCaptor<String> jobType = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jobService, org.mockito.Mockito.atLeastOnce())
                .submit(any(), any(), jobType.capture(), any(), any(), any());
        return jobType.getAllValues();
    }
}

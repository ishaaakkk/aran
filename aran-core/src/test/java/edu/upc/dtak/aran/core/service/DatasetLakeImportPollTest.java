package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.fake.InMemoryJobRepository;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * S16-3: what the Java→lake poll loop does when the lake is slow or briefly unreachable.
 *
 * <p>Two failure modes, opposite in kind. An import that never becomes terminal used to hold the
 * loop forever, leaving a job row RUNNING with nothing able to end it. And because
 * {@code HttpLakeClient} throws on <em>any</em> non-200 or IO error, one blip on the loopback call
 * failed a Java import whose lake job went on to succeed and register a snapshot nothing attached.
 *
 * <p>Time is real here rather than mocked, but there is very little of it: the deadline and the
 * poll interval are configuration ({@code aran.lake.import-timeout},
 * {@code aran.lake.import-poll-interval}) and these tests set them in milliseconds. A real
 * {@link JobService} over the in-memory repository runs the body inline, so the assertions are on
 * the persisted job row — the thing an operator would actually see.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Lake import — poll deadline and transient-failure tolerance (S16-3)")
class DatasetLakeImportPollTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final String LAKE_JOB_ID = "lake-job-1";

    /**
     * A lake whose polls are scripted: {@code failures} polls throw before each one that answers,
     * and {@code terminal} decides whether answering ever means "done".
     */
    private static final class ScriptedLakeClient extends CapturingLakeClient {
        private final AtomicInteger failuresLeft;
        private final boolean terminal;
        final AtomicInteger polls = new AtomicInteger();

        ScriptedLakeClient(int consecutiveFailures, boolean terminal) {
            this.failuresLeft = new AtomicInteger(consecutiveFailures);
            this.terminal = terminal;
        }

        @Override
        public String startImport(UUID tenantId, UUID datasetId, String connectionString, String query) {
            return LAKE_JOB_ID;
        }

        @Override
        public LakeJob getLakeJob(UUID tenantId, String jobId) {
            polls.incrementAndGet();
            if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                // Exactly what HttpLakeClient throws on a non-200 or an IO error.
                throw new RuntimeException("Lake job poll failed");
            }
            return terminal
                    ? new LakeJob("SUCCEEDED", Map.of("snapshot_id", "snap-1"), null)
                    : new LakeJob("RUNNING", Map.of(), null);
        }
    }

    @Mock private DataRepositoryService repositoryService;
    @Mock private edu.upc.dtak.aran.core.domain.DatasetRepository datasetRepository;

    private final Map<UUID, Dataset> stored = new java.util.HashMap<>();
    private final InMemoryJobRepository jobs = new InMemoryJobRepository();

    private static final edu.upc.dtak.aran.core.operator.Operator NOOP_BOOTSTRAP =
            new edu.upc.dtak.aran.core.operator.Operator() {
                @Override public String getOperatorId() { return "bootstrapping"; }
                @Override public String getDisplayName() { return "no-op bootstrap"; }
                @Override public OperatorResult execute(OperatorRequest request) {
                    return OperatorResult.success(Map.of());
                }
            };

    private DatasetService service(ScriptedLakeClient lake, Duration timeout, Duration interval) {
        // A real JobService on a direct executor: the import body runs inline and its outcome is
        // written to the job row, which is what these tests assert on.
        JobService jobService = new JobService(jobs, Runnable::run, (jobId, snapshot) -> { });
        return new DatasetService(
                datasetRepository, repositoryService, lake,
                new OperatorOrchestrator(List.of(NOOP_BOOTSTRAP)), jobService,
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), org.mockito.Mockito.mock(IntegrationModelService.class),
                Optional.empty(), Optional.empty(), timeout, interval);
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private static RelationalDatabaseRepository postgres() {
        return new RelationalDatabaseRepository(
                REPOSITORY, PROJECT, TENANT, "warehouse", "",
                "POSTGRESQL", "db.internal", 5432, "sales", "u", "p", "", false);
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

        DataRepository repository = postgres();
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(repository);
        when(repositoryService.listAvailableDatasets(any(), any(), any())).thenReturn(List.of("venue"));
        when(repositoryService.buildPostgresConnectionUri(any()))
                .thenReturn("postgresql://u:p@db.internal:5432/sales");
    }

    private Job importVenue(ScriptedLakeClient lake, Duration timeout, Duration interval) {
        UUID jobId = java.util.Objects.requireNonNull(service(lake, timeout, interval)
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "venue")
                .bootstrapJobId());
        return jobs.findById(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT.toString()), jobId)
                .orElseThrow();
    }

    @Test
    @DisplayName("a lake job that never finishes fails at the deadline, naming the lake job id")
    void deadline_fails_a_never_terminal_import() {
        ScriptedLakeClient lake = new ScriptedLakeClient(0, false);

        Job job = importVenue(lake, Duration.ofMillis(150), Duration.ofMillis(5));

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo("LAKE_IMPORT_TIMEOUT");
        assertThat(job.errorMessage())
                .as("an operator must be able to correlate the Java row with the lake's own job")
                .contains(LAKE_JOB_ID)
                .contains("waited");
        assertThat(lake.polls.get())
                .as("it really polled rather than giving up on the first look")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("two consecutive poll failures then a success does not fail the import")
    void transient_poll_failures_are_tolerated() {
        ScriptedLakeClient lake = new ScriptedLakeClient(2, true);

        Job job = importVenue(lake, Duration.ofSeconds(30), Duration.ofMillis(1));

        assertThat(job.status())
                .as("a blip on the loopback poll must not fail an import the lake completed")
                .isEqualTo(JobStatus.SUCCEEDED);
        assertThat(lake.polls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("more consecutive poll failures than are tolerated fails the import")
    void persistent_poll_failures_fail_the_import() {
        ScriptedLakeClient lake = new ScriptedLakeClient(6, true);

        Job job = importVenue(lake, Duration.ofSeconds(30), Duration.ofMillis(1));

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo("LAKE_POLL_FAILED");
        assertThat(job.errorMessage()).contains(LAKE_JOB_ID);
    }
}

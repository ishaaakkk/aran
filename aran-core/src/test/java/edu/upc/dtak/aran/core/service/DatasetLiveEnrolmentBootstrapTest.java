package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRegistration;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ProgressReporter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4-19: registering a table a federated repository reads live — {@code POST …/datasets} with no
 * file — returned the row with no status and started no bootstrap, so the dataset stayed
 * unbootstrapped until someone found a way to start one. It now bootstraps as a job, the way an
 * upload does.
 */
@DisplayName("A live-read dataset registration bootstraps as a job, like an upload")
class DatasetLiveEnrolmentBootstrapTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();

    private final DataRepositoryService repositoryService = mock(DataRepositoryService.class);
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final Map<UUID, Dataset> stored = new HashMap<>();
    private final List<String> bootstrapped = new ArrayList<>();

    private final Operator bootstrapping = new Operator() {
        @Override public String getOperatorId() { return "bootstrapping"; }
        @Override public String getDisplayName() { return "recording bootstrap"; }
        @Override public OperatorResult execute(OperatorRequest request) {
            bootstrapped.add(request.parameters().get("datasetId"));
            return OperatorResult.success(Map.of());
        }
    };

    @BeforeEach
    void setUp() {
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> {
            Dataset d = inv.getArgument(1);
            stored.put(d.id(), d);
            return d;
        });
        when(datasetRepository.findById(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<UUID>getArgument(1))));
        // The job body runs inline, so the bootstrap it submits is observable here.
        when(jobService.submit(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            JobService.JobTask task = inv.getArgument(5);
            task.run(ProgressReporter.NOOP);
            return new Job(UUID.randomUUID(), TENANT, PROJECT, inv.getArgument(2), JobStatus.PENDING,
                    Map.of(), null, null, null, null, null, null, Instant.now(), null, null);
        });
    }

    private DatasetService service() {
        return new DatasetService(
                datasetRepository, repositoryService, new CapturingLakeClient(),
                new OperatorOrchestrator(List.of(bootstrapping)), jobService,
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                mock(IntegrationModelService.class), Optional.empty());
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private void repositoryIs(DataRepository repository) {
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(repository);
    }

    private static RelationalDatabaseRepository postgres(boolean federated) {
        return new RelationalDatabaseRepository(REPOSITORY, PROJECT, TENANT, "sales", "",
                "POSTGRESQL", "db.internal", 5432, "sales", "u", "p", "public", federated);
    }

    @Test
    @DisplayName("a federated relational table is saved PENDING and its bootstrap job id comes back")
    void federatedTableBootstraps() {
        repositoryIs(postgres(true));
        Dataset dataset = new Dataset(UUID.randomUUID(), REPOSITORY, "orders", "live orders");

        DatasetRegistration registration =
                service().registerDatasetForLiveRead(caller(), PROJECT, REPOSITORY, dataset);

        assertThat(registration.bootstrapJobId()).isNotNull();
        assertThat(bootstrapped).containsExactly(dataset.id().toString());
        assertThat(registration.dataset().bootstrapStatus())
                .as("the job's own safety net wrote READY once the operator succeeded")
                .isEqualTo(DatasetStatus.READY);
    }

    @Test
    @DisplayName("a non-federated relational repository keeps the plain registration: nothing to read live")
    void importOnlyRepositoryDoesNotBootstrap() {
        repositoryIs(postgres(false));

        DatasetRegistration registration = service().registerDatasetForLiveRead(caller(), PROJECT,
                REPOSITORY, new Dataset(UUID.randomUUID(), REPOSITORY, "orders", "awaiting import"));

        assertThat(registration.bootstrapJobId()).isNull();
        assertThat(bootstrapped).isEmpty();
        verify(jobService, never()).submit(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a file-system repository keeps the plain registration too")
    void fileRepositoryDoesNotBootstrap() {
        repositoryIs(new FileSystemRepository(REPOSITORY, PROJECT, TENANT, "Uploads", ""));

        DatasetRegistration registration = service().registerDatasetForLiveRead(caller(), PROJECT,
                REPOSITORY, new Dataset(UUID.randomUUID(), REPOSITORY, "venues", ""));

        assertThat(registration.bootstrapJobId()).isNull();
        assertThat(bootstrapped).isEmpty();
    }
}

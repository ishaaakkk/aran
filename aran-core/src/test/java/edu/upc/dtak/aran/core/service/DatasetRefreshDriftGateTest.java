package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TableExtractor;
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
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * REFRESH_DRIFT-1: what a refresh leaves behind when the drift gate withholds the new schema.
 *
 * <p>A refresh attaches the new snapshot before bootstrap runs, because bootstrap reads the
 * dataset row to find the bytes it is about to describe. That ordering is fine right up to the
 * moment the gate says no: the graph then keeps describing the OLD schema while the dataset
 * points at the NEW bytes, so every wrapper compiled from that graph reads columns that moved or
 * vanished — a hard error on a dropped column, a silently different answer on a changed one.
 * These tests pin the compensation: the pointer goes back, the refresh row says why, and the job
 * does not report a success it did not have.
 *
 * <p>Fixture shape follows {@code DatasetJdbcExtractImportTest} — the job body runs inline, so a
 * failure inside it surfaces here as the exception a job worker would record — with the bootstrap
 * operator scripted per test to the payload {@code BootstrappingDriftGateTest} pins
 * ({@code applied=false} plus a {@code driftSummary}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetRefreshDriftGateTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();
    private static final String PRIOR_SNAPSHOT = "snap-previous";

    @Mock
    private DataRepositoryService repositoryService;
    @Mock
    private DatasetRepository datasetRepository;
    @Mock
    private JobService jobService;

    private final CapturingLakeClient lake = new CapturingLakeClient();
    private final InMemoryRefreshes refreshStore = new InMemoryRefreshes();
    private final ScriptedBootstrap bootstrap = new ScriptedBootstrap();
    private final Map<UUID, Dataset> stored = new LinkedHashMap<>();

    /** Enough of the port to see what a refresh records. Not a mock: the assertions are on state. */
    private static final class InMemoryRefreshes implements DatasetRefreshRepository {
        final Map<UUID, DatasetRefresh> byDataset = new LinkedHashMap<>();

        @Override public Optional<DatasetRefresh> findByDataset(TenantContext t, UUID datasetId) {
            return Optional.ofNullable(byDataset.get(datasetId));
        }

        @Override public List<DatasetRefresh> findAllByRepository(TenantContext t, UUID repositoryId) {
            return List.copyOf(byDataset.values());
        }

        @Override public DatasetRefresh save(TenantContext t, DatasetRefresh refresh) {
            byDataset.put(refresh.datasetId(), refresh);
            return refresh;
        }

        @Override public void delete(TenantContext t, UUID datasetId) {
            byDataset.remove(datasetId);
        }

        @Override public List<DatasetRefresh> claimDue(Instant now, int limit) {
            return List.of();
        }
    }

    /** A bootstrapping operator that returns whatever verdict the test under way needs. */
    private static final class ScriptedBootstrap implements Operator {
        OperatorResult next = OperatorResult.success(Map.of("applied", "true"));

        @Override public String getOperatorId() {
            return "bootstrapping";
        }

        @Override public String getDisplayName() {
            return "scripted bootstrap";
        }

        @Override public OperatorResult execute(OperatorRequest request) {
            // Only the bootstrap op is scripted; auto-profiling runs through a different operator
            // id, which is absent here and therefore a no-op the pipeline already swallows.
            return next;
        }
    }

    private DatasetService service() {
        return new DatasetService(
                datasetRepository, repositoryService, lake,
                new OperatorOrchestrator(List.of(bootstrap)), jobService,
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), Optional.of(new StubExtractor()), Optional.empty(),
                Optional.of(refreshStore), Optional.empty(),
                org.mockito.Mockito.mock(IntegrationModelService.class), Optional.empty());
    }

    /** Hands back parquet-shaped bytes without a database, so the refresh produces a NEW snapshot. */
    private static final class StubExtractor implements TableExtractor {
        @Override public Extracted extract(RelationalDatabaseRepository repository, String table, long maxBytes) {
            return new Extracted("PAR1-refreshed".getBytes(java.nio.charset.StandardCharsets.UTF_8), 7);
        }
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private static RelationalDatabaseRepository mysqlRepo() {
        return new RelationalDatabaseRepository(
                REPOSITORY, PROJECT, TENANT, "MySQL box", "",
                "MYSQL", "db.internal", 3306, "sales", "u", "p", null, false);
    }

    /** The withheld payload, exactly as {@code BootstrappingOperator.driftPayload(.., false)} builds it. */
    private static OperatorResult withheld() {
        return OperatorResult.success(Map.of(
                "datasetId", DATASET.toString(),
                "applied", "false",
                "driftVerdict", "BREAKING",
                "driftBaseline", "false",
                "driftUnchanged", "false",
                "driftChangeCount", "1",
                "driftSummary", "1 change(s): [BREAKING] column 'total' was removed "));
    }

    private DatasetRefresh refreshRow() {
        return DatasetRefresh.manual(DATASET, TENANT, REPOSITORY, PROJECT, UUID.randomUUID(), "orders");
    }

    private void datasetHasSnapshot(@org.jspecify.annotations.Nullable String snapshotId) {
        stored.put(DATASET, new Dataset(DATASET, REPOSITORY, "orders", "", snapshotId,
                List.of(), null, DatasetStatus.READY));
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
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(mysqlRepo());
        when(repositoryService.listAvailableDatasets(any(), any(), any())).thenReturn(List.of("orders"));

        // Inline, like DatasetJdbcExtractImportTest: everything worth asserting on is inside the
        // job body, and a body that throws must surface here rather than be swallowed by a mock.
        when(jobService.submit(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            JobService.JobTask task = inv.getArgument(5);
            task.run(ProgressReporter.NOOP);
            return new Job(
                    UUID.randomUUID(), TENANT, PROJECT, inv.getArgument(2),
                    edu.upc.dtak.aran.core.domain.JobStatus.PENDING, Map.of(),
                    null, null, null, null, null, null, Instant.now(), null, null);
        });

        refreshStore.save(new TenantContext(TENANT.toString()), refreshRow());
    }

    @Test
    @DisplayName("a withheld verdict puts the previous snapshot back — new bytes under an old graph is the wrong-results channel")
    void withheld_verdict_restores_the_previous_snapshot() {
        datasetHasSnapshot(PRIOR_SNAPSHOT);
        bootstrap.next = withheld();

        assertThatThrownBy(() -> service()
                .refreshDataset(caller(), PROJECT, REPOSITORY, DATASET, refreshRow()))
                .isInstanceOf(JobFailureException.class);

        assertThat(stored.get(DATASET).snapshotId())
                .describedAs("the graph still describes the old schema, so it must still be served the old bytes")
                .isEqualTo(PRIOR_SNAPSHOT);
        assertThat(stored.get(DATASET).bootstrapStatus()).isEqualTo(DatasetStatus.DRIFTED);
    }

    @Test
    @DisplayName("a withheld verdict records the drift on the refresh row, not a success")
    void withheld_verdict_records_the_drift_error() {
        datasetHasSnapshot(PRIOR_SNAPSHOT);
        bootstrap.next = withheld();

        assertThatThrownBy(() -> service()
                .refreshDataset(caller(), PROJECT, REPOSITORY, DATASET, refreshRow()))
                .isInstanceOf(JobFailureException.class);

        DatasetRefresh after = refreshStore.byDataset.get(DATASET);
        assertThat(after.lastError())
                .describedAs("the refresh row is where a user looks for 'why is this stale'")
                .isNotNull()
                .containsIgnoringCase("drift");
        assertThat(after.lastRefreshAt())
                .describedAs("nothing was refreshed — stamping a success here is the defect")
                .isNull();
    }

    @Test
    @DisplayName("the blocked refresh job carries its own error code rather than reading as a clean success")
    void withheld_verdict_fails_the_job_with_a_typed_code() {
        datasetHasSnapshot(PRIOR_SNAPSHOT);
        bootstrap.next = withheld();

        assertThatThrownBy(() -> service()
                .refreshDataset(caller(), PROJECT, REPOSITORY, DATASET, refreshRow()))
                .isInstanceOf(JobFailureException.class)
                .extracting(e -> ((JobFailureException) e).errorCode())
                .isEqualTo("REFRESH_BLOCKED_BY_DRIFT");
    }

    @Test
    @DisplayName("a clean bootstrap keeps the new snapshot and records the refresh as succeeded")
    void clean_bootstrap_is_unchanged() {
        datasetHasSnapshot(PRIOR_SNAPSHOT);
        bootstrap.next = OperatorResult.success(Map.of("applied", "true"));

        service().refreshDataset(caller(), PROJECT, REPOSITORY, DATASET, refreshRow());

        assertThat(stored.get(DATASET).snapshotId())
                .describedAs("the refresh did what it was for")
                .isEqualTo(lake.completedSnapshotId)
                .isNotEqualTo(PRIOR_SNAPSHOT);
        assertThat(stored.get(DATASET).bootstrapStatus()).isEqualTo(DatasetStatus.READY);
        DatasetRefresh after = refreshStore.byDataset.get(DATASET);
        assertThat(after.lastRefreshAt()).isNotNull();
        assertThat(after.lastError()).isNull();
    }

    @Test
    @DisplayName("a bootstrap failure is recorded on the refresh row, not only in the job log")
    void bootstrap_failure_is_recorded_on_the_refresh_row() {
        datasetHasSnapshot(PRIOR_SNAPSHOT);
        bootstrap.next = OperatorResult.failure("BOOTSTRAP_BROKE", "the snapshot could not be read");

        assertThatThrownBy(() -> service()
                .refreshDataset(caller(), PROJECT, REPOSITORY, DATASET, refreshRow()))
                .isInstanceOf(JobFailureException.class);

        DatasetRefresh after = refreshStore.byDataset.get(DATASET);
        assertThat(after.lastError())
                .describedAs("job rows are pruned; the refresh row is the durable channel")
                .isEqualTo("the snapshot could not be read");
        assertThat(after.lastRefreshAt()).isNull();
    }

    @Test
    @DisplayName("a first-ever refresh blocked by drift keeps the new snapshot — there is no prior one to restore")
    void first_refresh_withheld_has_nothing_to_restore() {
        // Reachable: a dataset registered against a repository table whose very first bootstrap
        // was itself withheld has no snapshot to go back to. Re-attaching null would blank the
        // dataset; the honest state is the new snapshot plus DRIFTED.
        datasetHasSnapshot(null);
        bootstrap.next = withheld();

        assertThatThrownBy(() -> service()
                .refreshDataset(caller(), PROJECT, REPOSITORY, DATASET, refreshRow()))
                .isInstanceOf(JobFailureException.class);

        assertThat(stored.get(DATASET).snapshotId()).isEqualTo(lake.completedSnapshotId);
        assertThat(stored.get(DATASET).bootstrapStatus()).isEqualTo(DatasetStatus.DRIFTED);
        assertThat(refreshStore.byDataset.get(DATASET).lastError()).containsIgnoringCase("drift");
    }
}

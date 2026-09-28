package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.RelationalCatalogHarvestTrigger;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TableExtractor;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.source.RefreshPolicy;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4B Task 5 — the harvest trigger's two hooks: relational-dataset enrolment ({@link
 * DatasetService#registerDataset}, the federated no-file path, and {@link
 * DatasetService#registerDatasetFromRepositoryTable}, the materialized-import path) and the
 * existing refresh cadence ({@link DatasetService#refreshDataset}, the one method both {@code
 * DatasetRefreshService.runDue} and a manual refresh-now call, so wiring the hook there covers
 * both without touching the scheduler at all).
 *
 * <p>{@link RelationalCatalogHarvestTrigger} is mocked throughout — this is a unit test of the
 * wiring (is the port called, with what, and does its failure get swallowed), not of harvesting
 * itself, which {@code RelationalCatalogHarvestServiceTest} (live Postgres) and {@code
 * HarvestPersistenceServiceTest} (mocked ports) already cover.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetHarvestTriggerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();

    @Mock private DataRepositoryService repositoryService;
    @Mock private DatasetRepository datasetRepository;
    @Mock private JobService jobService;
    @Mock private RelationalCatalogHarvestTrigger harvestTrigger;

    private final CapturingLakeClient lake = new CapturingLakeClient();
    private final RecordingExtractor extractor = new RecordingExtractor();
    private final InMemoryRefreshes refreshStore = new InMemoryRefreshes();
    private final Map<UUID, Dataset> stored = new java.util.HashMap<>();

    /** Enough of the port to see what an import records — mirrors DatasetJdbcExtractImportTest. */
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

    /** Records what it was asked for and hands back parquet-shaped bytes without a database. */
    private static final class RecordingExtractor implements TableExtractor {
        @Override
        public Extracted extract(RelationalDatabaseRepository repository, String table, long maxBytes) {
            return new Extracted("PAR1-not-really".getBytes(java.nio.charset.StandardCharsets.UTF_8), 7);
        }
    }

    /** A bootstrapping operator that succeeds and does nothing — every path here ends in a bootstrap. */
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

    private DatasetService service() {
        return new DatasetService(
                datasetRepository, repositoryService, lake,
                new OperatorOrchestrator(List.of(NOOP_BOOTSTRAP)), jobService,
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), Optional.of(extractor), Optional.empty(),
                Optional.of(refreshStore), Optional.of(harvestTrigger),
                org.mockito.Mockito.mock(IntegrationModelService.class), Optional.empty());
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private static RelationalDatabaseRepository relationalRepo(boolean federated) {
        return new RelationalDatabaseRepository(
                REPOSITORY, PROJECT, TENANT, "sales box", "",
                "MYSQL", "db.internal", 3306, "sales", "u", "p", null, federated);
    }

    private void repositoryIs(DataRepository repository, String... tables) {
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(repository);
        when(repositoryService.listAvailableDatasets(any(), any(), any())).thenReturn(List.of(tables));
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

        // The job body runs INLINE, on the calling thread — see DatasetJdbcExtractImportTest's own
        // comment on why: everything expensive moved inside the job body, so a mock that only
        // recorded the submission would leave the harvest assertions (which live inside that body
        // for the import/refresh paths) testing nothing.
        when(jobService.submit(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            JobService.JobTask task = inv.getArgument(5);
            task.run(edu.upc.dtak.aran.core.operator.ProgressReporter.NOOP);
            return new Job(
                    UUID.randomUUID(), TENANT, PROJECT, inv.getArgument(2),
                    edu.upc.dtak.aran.core.domain.JobStatus.PENDING, Map.of(),
                    null, null, null, null, null, null, Instant.now(), null, null);
        });
    }

    // ── enrolment: registerDataset (the federated, no-file path) ────────────────────────────

    @Test
    @DisplayName("enrolling a federated relational dataset (registerDataset) triggers a catalog harvest")
    void enrollingAFederatedRelationalDatasetTriggersHarvest() {
        RelationalDatabaseRepository repo = relationalRepo(true);
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(repo);

        Dataset dataset = new Dataset(UUID.randomUUID(), REPOSITORY, "orders", "");
        service().registerDataset(caller(), PROJECT, REPOSITORY, dataset);

        ArgumentCaptor<TenantContext> tenantCaptor = ArgumentCaptor.forClass(TenantContext.class);
        verify(harvestTrigger).harvest(tenantCaptor.capture(), org.mockito.ArgumentMatchers.eq(repo));
        assertThat(tenantCaptor.getValue().value()).isEqualTo(TENANT.toString());
    }

    @Test
    @DisplayName("registerDataset under a non-relational repository never calls the harvest trigger")
    void registerDatasetUnderNonRelationalRepositoryDoesNotHarvest() {
        DataRepository fsRepo = new FileSystemRepository(REPOSITORY, PROJECT, TENANT, "Uploads", "");
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(fsRepo);

        Dataset dataset = new Dataset(UUID.randomUUID(), REPOSITORY, "venues", "");
        service().registerDataset(caller(), PROJECT, REPOSITORY, dataset);

        verify(harvestTrigger, never()).harvest(any(), any());
    }

    @Test
    @DisplayName("registerDataset succeeds even when the harvest trigger throws — enrolment is never blocked")
    void registerDatasetSurvivesAHarvestFailure() {
        RelationalDatabaseRepository repo = relationalRepo(true);
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(repo);
        org.mockito.Mockito.doThrow(new RuntimeException("permission denied"))
                .when(harvestTrigger).harvest(any(), any());

        Dataset dataset = new Dataset(UUID.randomUUID(), REPOSITORY, "orders", "");

        assertThatCode(() -> {
            Dataset created = service().registerDataset(caller(), PROJECT, REPOSITORY, dataset);
            assertThat(created.name()).isEqualTo("orders");
        }).doesNotThrowAnyException();
    }

    // ── enrolment: registerDatasetFromRepositoryTable (the materialized-import path) ────────

    @Test
    @DisplayName("enrolling via table import (registerDatasetFromRepositoryTable) also triggers a harvest")
    void enrollingByTableImportTriggersHarvest() {
        RelationalDatabaseRepository repo = relationalRepo(false);
        repositoryIs(repo, "orders");

        service().registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders");

        verify(harvestTrigger).harvest(any(), org.mockito.ArgumentMatchers.eq(repo));
    }

    @Test
    @DisplayName("a table import still succeeds (dataset READY) when the harvest trigger throws")
    void tableImportSurvivesAHarvestFailure() {
        RelationalDatabaseRepository repo = relationalRepo(false);
        repositoryIs(repo, "orders");
        org.mockito.Mockito.doThrow(new RuntimeException("host unreachable"))
                .when(harvestTrigger).harvest(any(), any());

        var registration = service()
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders");

        assertThat(registration.dataset().bootstrapStatus())
                .isEqualTo(edu.upc.dtak.aran.core.domain.DatasetStatus.READY);
    }

    // ── refresh cadence: refreshDataset (shared by the scheduler and refresh-now) ───────────

    @Test
    @DisplayName("refreshDataset re-harvests the repository's catalog")
    void refreshDatasetReharvests() {
        RelationalDatabaseRepository repo = relationalRepo(false);
        repositoryIs(repo, "orders");

        var registration = service()
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders");
        // The import itself already harvests once (previous test group) — reset so this test
        // isolates the refresh's OWN call.
        org.mockito.Mockito.clearInvocations(harvestTrigger);

        DatasetRefresh refresh = refreshStore.byDataset.get(registration.dataset().id());
        service().refreshDataset(caller(), PROJECT, REPOSITORY, registration.dataset().id(), refresh);

        verify(harvestTrigger).harvest(any(), org.mockito.ArgumentMatchers.eq(repo));
    }

    @Test
    @DisplayName("a scheduled refresh tick (DatasetRefreshService.runDue) re-harvests through the same hook")
    void scheduledRefreshTickReharvests() {
        // DatasetRefreshService.runDue calls DatasetService.refreshDataset for every due row —
        // this proves the scheduler's own cadence reaches the harvest hook, not just a direct
        // refreshDataset call, without needing to touch DatasetRefreshScheduler's @Scheduled tick.
        RelationalDatabaseRepository repo = relationalRepo(false);
        repositoryIs(repo, "orders");
        DatasetService datasetService = service();

        var registration = datasetService
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders");
        org.mockito.Mockito.clearInvocations(harvestTrigger);

        DatasetRefresh scheduled = refreshStore.byDataset.get(registration.dataset().id())
                .withPolicy(new RefreshPolicy.Every(java.time.Duration.ofHours(1)), Instant.now());
        refreshStore.byDataset.put(registration.dataset().id(), scheduled);

        edu.upc.dtak.aran.core.domain.UserRepository users =
                mock(edu.upc.dtak.aran.core.domain.UserRepository.class);
        when(users.findById(any())).thenReturn(Optional.of(new edu.upc.dtak.aran.core.domain.User(
                scheduled.createdBy(), "sub", "a@b.c", "Creator", Instant.now())));
        IdentityService identity = mock(IdentityService.class);
        when(identity.soleMembership(any())).thenReturn(Optional.of(
                new edu.upc.dtak.aran.core.domain.Membership(
                        UUID.randomUUID(), scheduled.createdBy(), TENANT, TenantRole.OWNER, Instant.now())));

        DatasetRefreshService refreshService = new DatasetRefreshService(
                new DatasetRefreshRepository() {
                    @Override public Optional<DatasetRefresh> findByDataset(TenantContext t, UUID id) {
                        return refreshStore.findByDataset(t, id);
                    }

                    @Override public List<DatasetRefresh> findAllByRepository(TenantContext t, UUID id) {
                        return refreshStore.findAllByRepository(t, id);
                    }

                    @Override public DatasetRefresh save(TenantContext t, DatasetRefresh r) {
                        return refreshStore.save(t, r);
                    }

                    @Override public void delete(TenantContext t, UUID id) {
                        refreshStore.delete(t, id);
                    }

                    @Override public List<DatasetRefresh> claimDue(Instant now, int limit) {
                        return List.of(scheduled);
                    }
                },
                datasetService, repositoryService, identity, users);

        int started = refreshService.runDue(Instant.now());

        assertThat(started).isEqualTo(1);
        verify(harvestTrigger).harvest(any(), org.mockito.ArgumentMatchers.eq(repo));
    }
}

package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
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
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
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
 * Which import path an engine takes, and what the extract path does with the bytes.
 *
 * <p>The routing is the interesting part. Both branches submit the same job and end in the same
 * bootstrap, so a test that only checked "a dataset appeared" would pass with the branch inverted
 * — and inverting it is a real failure mode, since the lake's native importer builds a
 * {@code postgresql://} connection string and would be handed a MySQL host. They are told apart by
 * who does the copying: the JDBC path calls the extractor and uploads bytes, the lake path calls
 * {@code startImport} and uploads nothing.
 *
 * <p>The job body runs inline here (see {@code setUp}), because everything worth asserting on moved
 * into it when register and refresh were unified behind one materialize step.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetJdbcExtractImportTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();

    @Mock
    private DataRepositoryService repositoryService;
    @Mock
    private DatasetRepository datasetRepository;
    @Mock
    private JobService jobService;

    private final CapturingLakeClient lake = new CapturingLakeClient();
    private final RecordingExtractor extractor = new RecordingExtractor();
    private final InMemoryRefreshes refreshStore = new InMemoryRefreshes();

    /** Enough of the port to see what an import records. Not a mock: the assertions are on state. */
    private static final class InMemoryRefreshes implements DatasetRefreshRepository {
        final java.util.Map<UUID, DatasetRefresh> byDataset = new java.util.LinkedHashMap<>();

        @Override public Optional<DatasetRefresh> findByDataset(
                edu.upc.dtak.aran.core.tenant.TenantContext t, UUID datasetId) {
            return Optional.ofNullable(byDataset.get(datasetId));
        }

        @Override public List<DatasetRefresh> findAllByRepository(
                edu.upc.dtak.aran.core.tenant.TenantContext t, UUID repositoryId) {
            return List.copyOf(byDataset.values());
        }

        @Override public DatasetRefresh save(
                edu.upc.dtak.aran.core.tenant.TenantContext t, DatasetRefresh refresh) {
            byDataset.put(refresh.datasetId(), refresh);
            return refresh;
        }

        @Override public void delete(edu.upc.dtak.aran.core.tenant.TenantContext t, UUID datasetId) {
            byDataset.remove(datasetId);
        }

        @Override public List<DatasetRefresh> claimDue(java.time.Instant now, int limit) {
            return List.of();
        }
    }

    /** Records what it was asked for and hands back parquet-shaped bytes without a database. */
    private static final class RecordingExtractor implements TableExtractor {
        String table;
        long maxBytes;
        RuntimeException failWith;

        @Override
        public Extracted extract(RelationalDatabaseRepository repository, String table, long maxBytes) {
            this.table = table;
            this.maxBytes = maxBytes;
            if (failWith != null) {
                throw failWith;
            }
            return new Extracted("PAR1-not-really".getBytes(java.nio.charset.StandardCharsets.UTF_8), 7);
        }
    }

    /**
     * A bootstrapping operator that succeeds and does nothing.
     *
     * <p>Every path here ends in a bootstrap, and the job body now runs inline, so an orchestrator
     * with no operators would fail every test with OPERATOR_NOT_FOUND — a failure about the
     * fixture, not about materialization, which is what these tests are for.
     */
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

    private DatasetService service(Optional<TableExtractor> tableExtractor) {
        return serviceWithModel(tableExtractor, org.mockito.Mockito.mock(IntegrationModelService.class));
    }

    /**
     * The model collaborator is MANDATORY, not {@code Optional} like the two extractors: a dataset
     * whose deletion cannot clean up its enrolment is exactly the defect T1.8 exists to stop, so a
     * build assembled without the model stack must fail at wiring rather than silently skip the
     * cleanup. An earlier version of this suite passed {@code Optional.empty()} here and pinned the
     * opposite ("the re-seed hook is optional"); the reconciliation settled on mandatory.
     */
    private DatasetService serviceWithModel(Optional<TableExtractor> tableExtractor,
                                            IntegrationModelService model) {
        return new DatasetService(
                datasetRepository, repositoryService, lake,
                new OperatorOrchestrator(List.of(NOOP_BOOTSTRAP)), jobService,
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), tableExtractor, Optional.empty(), Optional.of(refreshStore),
                Optional.empty(), model, Optional.empty());
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private static RelationalDatabaseRepository repo(String engine, boolean federated) {
        return new RelationalDatabaseRepository(
                REPOSITORY, PROJECT, TENANT, engine + " box", "",
                engine, "db.internal", 3306, "sales", "u", "p", null, federated);
    }

    /** The datasets this test's repository fake holds, so a job body can read one back. */
    private final java.util.Map<UUID, Dataset> stored = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> {
            Dataset d = inv.getArgument(1);
            stored.put(d.id(), d);
            return d;
        });
        when(datasetRepository.findById(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<UUID>getArgument(1))));

        // The job body runs INLINE, on the calling thread. Everything expensive — the extract, the
        // upload, the bootstrap — moved inside it when register and refresh were unified, so a mock
        // that only recorded the submission would leave these assertions testing nothing. Running
        // it here also means a failure inside the body surfaces as the exception the caller would
        // see, which is what the refusal tests assert on.
        when(jobService.submit(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            JobService.JobTask task = inv.getArgument(5);
            task.run(edu.upc.dtak.aran.core.operator.ProgressReporter.NOOP);
            return new Job(
                    UUID.randomUUID(), TENANT, PROJECT, inv.getArgument(2),
                    edu.upc.dtak.aran.core.domain.JobStatus.PENDING, Map.of(),
                    null, null, null, null, null, null, Instant.now(), null, null);
        });
    }

    private void repositoryIs(DataRepository repository, String... tables) {
        when(repositoryService.getRepository(any(), any(), any())).thenReturn(repository);
        when(repositoryService.listAvailableDatasets(any(), any(), any())).thenReturn(List.of(tables));
    }

    @Test
    @DisplayName("a MySQL table is extracted over JDBC and uploaded, never sent to the lake importer")
    void mysql_goes_through_the_extractor() {
        repositoryIs(repo("MYSQL", false), "orders", "customers");

        var registration = service(Optional.of(extractor))
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders");

        assertThat(extractor.table).isEqualTo("orders");
        assertThat(lake.uploadedBytes).asString().startsWith("PAR1");
        assertThat(lake.uploadContentType).isEqualTo("application/vnd.apache.parquet");
        assertThat(registration.dataset().name()).isEqualTo("orders");
        // READY because the job body ran inline here. In production it is PENDING when the
        // registration returns and READY once the job finishes — the same states, observed at a
        // different moment.
        assertThat(registration.dataset().bootstrapStatus()).isEqualTo(DatasetStatus.READY);
        assertThat(registration.dataset().snapshotId()).isNotNull();
        assertThat(submittedJobTypes()).containsExactly("dataset:import");
    }

    @Test
    @DisplayName("an imported table records its upstream, manual by default")
    void import_records_the_upstream() {
        // The dataset now HAS something to refresh from, which is what makes it refreshable at all.
        // Manual rather than a schedule: nobody asked for recurring load on their production
        // database by importing one table.
        repositoryIs(repo("MYSQL", false), "orders");

        var registration = service(Optional.of(extractor))
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders");

        DatasetRefresh recorded = refreshStore.byDataset.get(registration.dataset().id());
        assertThat(recorded).isNotNull();
        assertThat(recorded.sourceTable()).isEqualTo("orders");
        assertThat(recorded.policy())
                .isInstanceOf(edu.upc.dtak.aran.core.source.RefreshPolicy.Manual.class);
        assertThat(recorded.nextRunAt()).isNull();
        assertThat(recorded.lastRefreshAt())
                .describedAs("the import itself is not a refresh — nothing has been remade yet")
                .isNull();
    }

    @Test
    @DisplayName("an upload records no upstream, because an uploaded file is its own source")
    void upload_records_no_upstream() {
        repositoryIs(new edu.upc.dtak.aran.core.domain.FileSystemRepository(
                REPOSITORY, PROJECT, TENANT, "Uploads", ""));

        var registration = service(Optional.of(extractor)).registerDatasetWithFile(
                caller(), PROJECT, REPOSITORY,
                new Dataset(UUID.randomUUID(), REPOSITORY, "venues", ""),
                "a,b\n1,2".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/csv", "venues.csv");

        assertThat(refreshStore.byDataset).doesNotContainKey(registration.dataset().id());
    }

    @Test
    @DisplayName("the extract is capped, and the cap is a real number rather than Long.MAX_VALUE")
    void extract_is_capped() {
        // A ceiling passed as "no ceiling" is the bug this asserts against: the extractor holds the
        // whole parquet in memory in the backend JVM, so an uncapped table import is an OOM with
        // extra steps.
        repositoryIs(repo("ORACLE", false), "ledger");

        service(Optional.of(extractor))
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "ledger");

        assertThat(extractor.maxBytes).isPositive().isLessThan(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("the filename hint carries the parquet extension, not the bare table name (F14)")
    void filename_hint_has_an_extension() {
        // F14: bootstrap-time format detection reads the hint. "orders" detects nothing.
        repositoryIs(repo("MYSQL", false), "orders");

        service(Optional.of(extractor))
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders");

        // The hint is not on the DTO — it is closed over by the submitted job — so this asserts on
        // the content type, which travels the same decision, plus the extract itself being parquet.
        assertThat(lake.initContentType).isEqualTo("application/vnd.apache.parquet");
    }

    @Test
    @DisplayName("a table the repository does not expose is refused before any extract runs")
    void unknown_table_is_refused() {
        repositoryIs(repo("MYSQL", false), "orders");

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(extractor.table).isNull();
    }

    @Test
    @DisplayName("HBase declares no extract, so the import is refused rather than attempted")
    void engine_without_an_extract_is_refused() {
        repositoryIs(repo("HBASE", false), "events");

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "events"))
                .isInstanceOf(UnsupportedImportSourceException.class);

        assertThat(extractor.table).isNull();
    }

    @Test
    @DisplayName("a build with no extractor bean refuses instead of NPEing")
    void absent_extractor_is_a_refusal() {
        // The operator modules are runtimeOnly, so this is a state production can genuinely be in.
        repositoryIs(repo("MYSQL", false), "orders");

        assertThatThrownBy(() -> service(Optional.empty())
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders"))
                .isInstanceOf(UnsupportedImportSourceException.class);
    }

    @Test
    @DisplayName("an engine declaring a mechanism this build cannot run is a wiring fault, not a user error")
    void mechanism_mismatch_is_not_blamed_on_the_user() {
        // UnsupportedOperationException out of the extractor means the engine's declaration and the
        // extractor's capabilities disagree. Surfacing that as "your database is unsupported" would
        // send the user looking for a fix they cannot make.
        repositoryIs(repo("MYSQL", false), "orders");
        extractor.failWith = new UnsupportedOperationException("no engine registered for 'MYSQL'");

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "orders"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(UnsupportedImportSourceException.class)
                .hasMessageContaining("MYSQL");
    }

    @Test
    @DisplayName("PostgreSQL still takes the lake's native importer, extractor or no extractor")
    void postgres_keeps_the_lake_path() {
        // LAKE_NATIVE is preferred where it exists; the generic path is the fallback, not a
        // replacement. Losing that preference would be a silent downgrade — the lake's hand-written
        // type map replaced by ResultSetMetaData inference — which no other assertion would catch.
        // CapturingLakeClient.startImport throws, so reaching the lake branch is itself the signal.
        repositoryIs(repo("POSTGRESQL", false), "venues");

        assertThatThrownBy(() -> service(Optional.of(extractor))
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "venues"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(extractor.table).describedAs("the JDBC extractor must not be involved").isNull();
        assertThat(lake.uploadedBytes).describedAs("the lake pulls the table itself").isNull();
    }

    @Test
    @DisplayName("V47: a refresh re-seeds the dataset's unedited wrappers from the regenerated graph")
    void refresh_reseeds_unedited_wrappers() {
        // The drift trigger itself. A refresh re-imports AND re-bootstraps, so the graph's
        // per-class nx:wrapperSql is regenerated for the source's new columns — and until this
        // hook existed, the next compile pushed the enrolment-time copy straight back over it
        // (SchemaIntegrationOperator.applyWrapperOverrides), which is what left the class
        // answering nothing but a DuckDB binder error.
        repositoryIs(repo("MYSQL", false), "venues");
        IntegrationModelService model = org.mockito.Mockito.mock(IntegrationModelService.class);
        DatasetService service = serviceWithModel(Optional.of(extractor), model);

        var registration = service.registerDatasetFromRepositoryTable(
                caller(), PROJECT, REPOSITORY, "venues");
        UUID datasetId = registration.dataset().id();
        org.mockito.Mockito.clearInvocations(model);

        service.refreshDataset(caller(), PROJECT, REPOSITORY, datasetId,
                refreshStore.byDataset.get(datasetId));

        org.mockito.Mockito.verify(model).reseedUneditedWrappers(
                org.mockito.ArgumentMatchers.argThat(t -> t.value().equals(TENANT.toString())),
                org.mockito.ArgumentMatchers.eq(datasetId));
    }

    @Test
    @DisplayName("V47: a first import re-seeds too — nothing is enrolled yet, so it is a no-op, not a skip")
    void first_import_still_calls_the_reseed_hook() {
        // Adapted from "a build with no model stack still imports": the reconciliation made the
        // model collaborator mandatory (see serviceWithModel), so "no model stack" is no longer a
        // reachable configuration. What is still worth pinning is that the hook is called
        // unconditionally rather than guarded by "has this dataset been enrolled?" — the guard
        // lives inside reseedUneditedWrappers, where it can see the wrapper_edited flag.
        repositoryIs(repo("MYSQL", false), "venues");
        IntegrationModelService model = org.mockito.Mockito.mock(IntegrationModelService.class);

        var registration = serviceWithModel(Optional.of(extractor), model)
                .registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "venues");

        assertThat(registration.dataset()).isNotNull();
        org.mockito.Mockito.verify(model).reseedUneditedWrappers(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(registration.dataset().id()));
    }

    /** The job types submitted so far, in order — the observable difference between the branches. */
    private List<String> submittedJobTypes() {
        ArgumentCaptor<String> jobType = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jobService, org.mockito.Mockito.atLeastOnce())
                .submit(any(), any(), jobType.capture(), any(), any(), any());
        return jobType.getAllValues();
    }
}

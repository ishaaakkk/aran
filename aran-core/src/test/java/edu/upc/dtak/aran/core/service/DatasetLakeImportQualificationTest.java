package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * S2-7, the lake half: the SELECT the lake is asked to run must name the repository's schema.
 *
 * <p>The lake runs {@code source.query} verbatim over asyncpg (it screens the leading keyword and
 * nothing else) and the connection URI carries no {@code search_path}, so an unqualified
 * {@code SELECT * FROM "venue"} resolves against whatever the server's default search path finds.
 * A repository whose schema is set therefore imported the wrong relation of the same name — or, if
 * no such relation existed, could not import at all. Qualifying the text the Java side builds is
 * the whole fix; nothing new crosses the wire.
 *
 * <p>The assertion is on the query string rather than on rows because this path's row-producing
 * half lives in the Python service. {@code SchemaQualifiedJdbcAccessIT} covers rows for the JDBC
 * paths, where Aran itself issues the SQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Lake import — the SELECT names the repository's schema (S2-7)")
class DatasetLakeImportQualificationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();

    /** Records the SELECT the lake is handed, and reports the import as done. */
    private static final class ImportRecordingLakeClient extends CapturingLakeClient {
        String importQuery;
        String connectionString;

        @Override
        public String startImport(UUID tenantId, UUID datasetId, String connectionString, String query) {
            this.connectionString = connectionString;
            this.importQuery = query;
            return "lake-job-1";
        }

        @Override
        public LakeJob getLakeJob(UUID tenantId, String jobId) {
            return new LakeJob("SUCCEEDED", Map.of("snapshot_id", "snap-1"), null);
        }
    }

    @Mock private DataRepositoryService repositoryService;
    @Mock private edu.upc.dtak.aran.core.domain.DatasetRepository datasetRepository;
    @Mock private JobService jobService;

    private final ImportRecordingLakeClient lake = new ImportRecordingLakeClient();
    private final Map<UUID, Dataset> stored = new java.util.HashMap<>();

    private static final edu.upc.dtak.aran.core.operator.Operator NOOP_BOOTSTRAP =
            new edu.upc.dtak.aran.core.operator.Operator() {
                @Override public String getOperatorId() { return "bootstrapping"; }
                @Override public String getDisplayName() { return "no-op bootstrap"; }
                @Override public OperatorResult execute(OperatorRequest request) {
                    return OperatorResult.success(Map.of());
                }
            };

    private DatasetService service() {
        return new DatasetService(
                datasetRepository, repositoryService, lake,
                new OperatorOrchestrator(List.of(NOOP_BOOTSTRAP)), jobService,
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), org.mockito.Mockito.mock(IntegrationModelService.class),
                Optional.empty());
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "sub", TENANT, TenantRole.OWNER);
    }

    private static RelationalDatabaseRepository postgres(String schema) {
        return new RelationalDatabaseRepository(
                REPOSITORY, PROJECT, TENANT, "warehouse", "",
                "POSTGRESQL", "db.internal", 5432, "sales", "u", "p", schema, false);
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
        // Inline, like DatasetJdbcExtractImportTest: the import runs inside the job body.
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
        when(repositoryService.buildPostgresConnectionUri(any()))
                .thenReturn("postgresql://u:p@db.internal:5432/sales");
    }

    @Test
    @DisplayName("a repository with a schema imports from that schema, not from the search path")
    void import_query_is_schema_qualified() {
        repositoryIs(postgres("reporting"), "venue");

        service().registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "venue");

        assertThat(lake.importQuery).isEqualTo("SELECT * FROM \"reporting\".\"venue\"");
    }

    @Test
    @DisplayName("no schema still imports the bare table, so existing repositories are untouched")
    void import_query_without_a_schema_is_unchanged() {
        repositoryIs(postgres(""), "venue");

        service().registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "venue");

        assertThat(lake.importQuery).isEqualTo("SELECT * FROM \"venue\"");
    }

    @Test
    @DisplayName("a quote in either name is escaped rather than merely wrapped")
    void import_query_escapes_identifiers() {
        repositoryIs(postgres("re\"port"), "ven\"ue");

        service().registerDatasetFromRepositoryTable(caller(), PROJECT, REPOSITORY, "ven\"ue");

        assertThat(lake.importQuery).isEqualTo("SELECT * FROM \"re\"\"port\".\"ven\"\"ue\"");
    }
}

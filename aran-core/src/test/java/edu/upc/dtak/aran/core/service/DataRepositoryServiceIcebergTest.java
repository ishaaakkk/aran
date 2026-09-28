package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.InMemoryDataRepositoryRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import edu.upc.dtak.aran.core.tenant.TenantContext;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ICEBERG_REST kind at the service tier (Gap 2): two independent credentials with their own
 * sentinels and redirect guards, a real control-plane probe, and a scan that comes from the
 * catalog itself.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataRepositoryServiceIcebergTest {

    private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-00000000000a");
    private static final UUID CALLER = UUID.fromString("22222222-0000-0000-0000-00000000000a");
    private static final UUID PROJECT = UUID.fromString("33333333-0000-0000-0000-00000000000a");
    private static final UUID REPO = UUID.fromString("44444444-0000-0000-0000-00000000000a");
    private static final String TOKEN = "catalog-token-do-not-leak";
    private static final String S3_SECRET = "s3-secret-do-not-leak";

    @Mock
    private ProjectService projectService;
    @Mock
    private DriverDownloader driverDownloader;
    @Mock
    private edu.upc.dtak.aran.core.domain.DatasetRepository datasetRepository;
    @Mock
    private IntegrationModelService integrationModels;

    private InMemoryDataRepositoryRepository repositoryStore;
    private final RecordingCatalogClient catalog = new RecordingCatalogClient();

    /** Answers protocol calls (or the failure it was told to) without any HTTP. */
    private static final class RecordingCatalogClient implements IcebergCatalogClient {
        List<String> tables = List.of("db.users", "db.orders");
        RuntimeException failWith;
        IcebergRestRepository sawRepository;

        @Override
        public Map<String, String> config(IcebergRestRepository repository) {
            this.sawRepository = repository;
            if (failWith != null) {
                throw failWith;
            }
            return Map.of();
        }

        @Override
        public List<String> listTables(IcebergRestRepository repository) {
            this.sawRepository = repository;
            if (failWith != null) {
                throw failWith;
            }
            return tables;
        }

        @Override
        public IcebergTable loadTable(IcebergRestRepository repository, String tableIdentifier) {
            throw new UnsupportedOperationException("not exercised at the service tier");
        }
    }

    private DataRepositoryService service() {
        return service(Optional.of(catalog));
    }

    private DataRepositoryService service(Optional<IcebergCatalogClient> client) {
        return new DataRepositoryService(repositoryStore, projectService, driverDownloader,
                datasetRepository, integrationModels, false,
                SingerTapCatalog.empty(), Optional.empty(), client);
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(CALLER, "caller-subject", TENANT, TenantRole.OWNER);
    }

    private static IcebergRestRepository repo(String token, String secretKey) {
        return new IcebergRestRepository(REPO, PROJECT, TENANT, "lakehouse", "",
                "http://localhost:8181/api/catalog", "prod", token,
                "MINIO", "http://localhost:9000", "AKIA", secretKey, "us-east-1");
    }

    @BeforeEach
    void setUp() {
        repositoryStore = new InMemoryDataRepositoryRepository();
        when(projectService.getVisibleProject(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(mock(Project.class));
    }

    @Test
    @DisplayName("the connection test is the protocol's own hello — GET /v1/config")
    void testConnectionProbesConfig() {
        var result = service().testConnection(caller(), PROJECT, repo(TOKEN, S3_SECRET));

        assertThat(result.success()).isTrue();
        assertThat(catalog.sawRepository.token()).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("a catalog refusal is reported with the catalog's own diagnostics")
    void testConnectionReportsFailure() {
        catalog.failWith = new IllegalStateException("Iceberg catalog answered HTTP 401 for /v1/config");

        var result = service().testConnection(caller(), PROJECT, repo(TOKEN, S3_SECRET));

        assertThat(result.success()).isFalse();
        assertThat(result.details()).contains("HTTP 401");
    }

    @Test
    @DisplayName("probing a masked token says so instead of dialing eight asterisks")
    void maskedTokenIsDiagnosedNotDialed() {
        var result = service().testConnection(caller(), PROJECT, repo("********", S3_SECRET));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("token");
        assertThat(catalog.sawRepository).isNull();
    }

    @Test
    @DisplayName("a build without the iceberg adapter refuses the probe honestly")
    void missingClientIsAnHonestNo() {
        var result = service(Optional.empty()).testConnection(caller(), PROJECT, repo(TOKEN, S3_SECRET));

        assertThat(result.success()).isFalse();
        assertThat(result.details()).contains("classpath");
    }

    @Test
    @DisplayName("scanning lists namespace.table identifiers from the catalog itself")
    void scanListsTables() {
        repositoryStore.save(new TenantContext(TENANT.toString()), repo(TOKEN, S3_SECRET));

        List<String> tables = service().listAvailableDatasets(caller(), PROJECT, REPO);

        assertThat(tables).containsExactly("db.users", "db.orders");
    }

    @Test
    @DisplayName("each masked sentinel keeps its own stored credential on update")
    void sentinelsAreIndependent() {
        repositoryStore.save(new TenantContext(TENANT.toString()), repo(TOKEN, S3_SECRET));

        var updated = (IcebergRestRepository) service().updateRepository(
                caller(), PROJECT, repo("********", "newly-typed-secret"));

        assertThat(updated.token()).isEqualTo(TOKEN);
        assertThat(updated.secretKey()).isEqualTo("newly-typed-secret");
    }

    @Test
    @DisplayName("a kept (masked) token may not be redirected to a new catalog URI (F-CRED-5)")
    void maskedTokenCannotBeRedirected() {
        repositoryStore.save(new TenantContext(TENANT.toString()), repo(TOKEN, S3_SECRET));
        var redirected = new IcebergRestRepository(REPO, PROJECT, TENANT, "lakehouse", "",
                "http://localhost:9999/api/catalog", "prod", "********",
                "MINIO", "http://localhost:9000", "AKIA", "********", "us-east-1");

        assertThatThrownBy(() -> service().updateRepository(caller(), PROJECT, redirected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Re-enter the credential");
    }

    @Test
    @DisplayName("a kept (masked) storage secret may not be pointed at a new endpoint (F-CRED-5)")
    void maskedStorageSecretCannotBeRedirected() {
        repositoryStore.save(new TenantContext(TENANT.toString()), repo(TOKEN, S3_SECRET));
        var redirected = new IcebergRestRepository(REPO, PROJECT, TENANT, "lakehouse", "",
                "http://localhost:8181/api/catalog", "prod", TOKEN,
                "MINIO", "http://localhost:9999", "AKIA", "********", "us-east-1");

        assertThatThrownBy(() -> service().updateRepository(caller(), PROJECT, redirected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Re-enter the credential");
    }
}

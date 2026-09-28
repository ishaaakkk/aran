package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.*;
import edu.upc.dtak.aran.core.fake.InMemoryDataRepositoryRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataRepositoryService Tests")
class DataRepositoryServiceTest {

    private InMemoryDataRepositoryRepository repositoryStore;
    @Mock
    private ProjectService projectService;
    @Mock
    private DriverDownloader driverDownloader;
    @Mock
    private edu.upc.dtak.aran.core.domain.DatasetRepository datasetRepository;
    @Mock
    private IntegrationModelService integrationModels;

    private DataRepositoryService service;

    private static final UUID TENANT_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID CALLER_ID = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("33333333-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        repositoryStore = new InMemoryDataRepositoryRepository();
        // blockPrivateNetworks = false: the probes below deliberately target loopback, which is
        // the on-prem default and what a local dev stack looks like. No taps configured and no
        // tap extractor: the T6 kind is off, which is the default and has nothing to do with any
        // behaviour in this class.
        service = new DataRepositoryService(repositoryStore, projectService, driverDownloader,
                datasetRepository, integrationModels, false,
                edu.upc.dtak.aran.core.source.SingerTapCatalog.empty(), java.util.Optional.empty());
    }

    private AuthenticatedUser caller() {
        return new AuthenticatedUser(CALLER_ID, "caller-subject", TENANT_ID, TenantRole.OWNER);
    }

    @Test
    @DisplayName("listRepositories verifies project visibility and returns matching repositories")
    void list_repositories_success() {
        AuthenticatedUser caller = caller();
        // Mock projectService to verify project access is checked
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                repoId, PROJECT_ID, TENANT_ID, "My DB", "Description",
                "POSTGRESQL", "localhost", 5432, "aran", "postgres", "secret", "public", false
        );
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), repo);

        List<DataRepository> result = service.listRepositories(caller, PROJECT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(repoId);
        verify(projectService).getVisibleProject(caller, PROJECT_ID);
    }

    @Test
    @DisplayName("getRepository throws AccessDeniedException if repository project does not match context")
    void get_repository_project_mismatch() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                repoId, otherProjectId, TENANT_ID, "My DB", "Description",
                "POSTGRESQL", "localhost", 5432, "aran", "postgres", "secret", "public", false
        );
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), repo);

        assertThatThrownBy(() -> service.getRepository(caller, PROJECT_ID, repoId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not belong to project");
    }

    @Test
    @DisplayName("createRepository saves repository successfully after validations")
    void create_repository_success() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                repoId, PROJECT_ID, TENANT_ID, "My DB", "Description",
                "POSTGRESQL", "localhost", 5432, "aran", "postgres", "secret", "public", false
        );

        DataRepository created = service.createRepository(caller, PROJECT_ID, repo);

        assertThat(created).isNotNull();
        assertThat(created.id()).isEqualTo(repoId);
        
        List<DataRepository> repos = repositoryStore.findAllByProject(
                new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), PROJECT_ID
        );
        assertThat(repos).hasSize(1);
    }

    @Test
    @DisplayName("an object store with no endpoint is stored unscreened — the provider default is public (F-S1-1)")
    void create_object_storage_without_endpoint() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        // A blank endpoint means the provider's own public URL (s3.amazonaws.com for S3): there is
        // no caller-chosen host to screen, and screening "" would refuse every AWS repository.
        var repo = new edu.upc.dtak.aran.core.domain.ObjectStorageRepository(
                UUID.randomUUID(), PROJECT_ID, TENANT_ID, "Bucket", "Description",
                "S3", "", "my-bucket", "AKIA", "secret", "eu-west-1");

        assertThat(service.createRepository(caller, PROJECT_ID, repo)).isNotNull();
    }

    @Test
    @DisplayName("createRepository throws IllegalArgumentException if project ID mismatches")
    void create_repository_project_mismatch() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                repoId, otherProjectId, TENANT_ID, "My DB", "Description",
                "POSTGRESQL", "localhost", 5432, "aran", "postgres", "secret", "public", false
        );

        assertThatThrownBy(() -> service.createRepository(caller, PROJECT_ID, repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Repository project ID must match target project");
    }

    @Test
    @DisplayName("deleteRepository removes the repository successfully")
    void delete_repository_success() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                repoId, PROJECT_ID, TENANT_ID, "My DB", "Description",
                "POSTGRESQL", "localhost", 5432, "aran", "postgres", "secret", "public", false
        );
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), repo);

        service.deleteRepository(caller, PROJECT_ID, repoId);

        List<DataRepository> repos = repositoryStore.findAllByProject(
                new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), PROJECT_ID
        );
        assertThat(repos).isEmpty();
    }

    @Test
    @DisplayName("a file-system connection test probes nothing and says the kind holds uploads (D1)")
    void test_connection_file_system_is_upload_only() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        FileSystemRepository fsRepo = new FileSystemRepository(
                UUID.randomUUID(), PROJECT_ID, TENANT_ID, "FS Repo", "Desc");

        DataRepositoryService.ConnectionTestResult result = service.testConnection(caller, PROJECT_ID, fsRepo);
        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("upload").contains("reads no server directory");
    }

    @Test
    @DisplayName("previewDataset throws IllegalArgumentException on invalid dataset name (SQL injection attempt)")
    void preview_dataset_invalid_name_throws_IllegalArgumentException() throws Exception {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));
        // The gate is membership of what the repository actually exposes, not a name pattern
        // (S2-7), so the refusal now needs a catalog to check against. DuckDB stands in for the
        // source: it has real schemas and real metadata, which a stubbed ResultSet would not.
        when(driverDownloader.loadDriver("POSTGRESQL")).thenReturn(duckDbStandingInForTheSource());

        UUID repoId = UUID.randomUUID();
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                repoId, PROJECT_ID, TENANT_ID, "My DB", "Description",
                "POSTGRESQL", "localhost", 5432, "aran", "postgres", "secret", "public", false
        );
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), repo);

        assertThatThrownBy(() -> service.previewDataset(caller, PROJECT_ID, repoId, "users; DROP TABLE users;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid dataset name");
    }

    /**
     * A {@link java.sql.Driver} that ignores the PostgreSQL URL and hands back a fresh in-memory
     * DuckDB holding one relation, {@code public.users} — enough of a catalog for the preview's
     * membership check to have something to refuse against.
     */
    private static java.sql.Driver duckDbStandingInForTheSource() {
        return new java.sql.Driver() {
            @Override public java.sql.Connection connect(String url, java.util.Properties info)
                    throws java.sql.SQLException {
                java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:duckdb:");
                try (java.sql.Statement s = conn.createStatement()) {
                    s.execute("CREATE SCHEMA public");
                    s.execute("CREATE TABLE public.users (id INTEGER)");
                }
                return conn;
            }
            @Override public boolean acceptsURL(String url) { return true; }
            @Override public java.sql.DriverPropertyInfo[] getPropertyInfo(
                    String url, java.util.Properties info) {
                return new java.sql.DriverPropertyInfo[0];
            }
            @Override public int getMajorVersion() { return 1; }
            @Override public int getMinorVersion() { return 0; }
            @Override public boolean jdbcCompliant() { return false; }
            @Override public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getLogger("duckdb-stand-in");
            }
        };
    }

    // -------------------------------------------------------------------------
    //  testConnection — non-JDBC probes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("testConnection returns false for ObjectStorage with no endpoint and unknown provider")
    void test_connection_object_storage_unknown_provider_returns_false() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        // Provider "CUSTOM" has no default endpoint → cannot probe → false
        ObjectStorageRepository osRepo = new ObjectStorageRepository(
                UUID.randomUUID(), PROJECT_ID, TENANT_ID, "Store", "Desc",
                "CUSTOM", /* endpoint */ "", "my-bucket", "access-key", "secret-key", "us-east-1"
        );

        assertThat(service.testConnection(caller, PROJECT_ID, osRepo).success()).isFalse();
    }

    // -------------------------------------------------------------------------
    //  testConnection — SSRF screen
    //  The probe takes host:port straight from the request body, so without a screen an
    //  authenticated user can aim it at anything Aran's network reaches and read the
    //  outcome off the structured result. These pin the screen at the service boundary;
    //  OutboundAddressGuardTest pins the range logic itself.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("testConnection refuses the cloud metadata endpoint before opening a socket")
    void test_connection_refuses_metadata_endpoint() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        IcebergRestRepository metadataProbe = new IcebergRestRepository(
                UUID.randomUUID(), PROJECT_ID, TENANT_ID, "Catalog", "Desc",
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/", "", "",
                "S3", "", "", "", ""
        );

        var result = service.testConnection(caller, PROJECT_ID, metadataProbe);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Target host is not permitted");
    }

    @Test
    @DisplayName("the screen covers every socket-opening repository type, not just HTTP")
    void test_connection_screen_covers_all_transports() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        RelationalDatabaseRepository jdbc = new RelationalDatabaseRepository(
                UUID.randomUUID(), PROJECT_ID, TENANT_ID, "PG", "Desc",
                "POSTGRESQL", "169.254.169.254", 5432, "db", "u", "p", "public", false
        );
        ObjectStorageRepository os = new ObjectStorageRepository(
                UUID.randomUUID(), PROJECT_ID, TENANT_ID, "Store", "Desc",
                "CUSTOM", "http://169.254.169.254", "b", "ak", "sk", "us-east-1"
        );

        assertThat(service.testConnection(caller, PROJECT_ID, jdbc).message())
                .isEqualTo("Target host is not permitted");
        assertThat(service.testConnection(caller, PROJECT_ID, os).message())
                .isEqualTo("Target host is not permitted");
    }

    @Test
    @DisplayName("the screen does not break the on-prem case: a private host still gets probed")
    void test_connection_still_probes_private_hosts() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        // Loopback port 1 refuses instantly. The point is the *reason* it failed: it reached
        // the probe and got a transport error, rather than being refused by the screen.
        ObjectStorageRepository onPrem = new ObjectStorageRepository(
                UUID.randomUUID(), PROJECT_ID, TENANT_ID, "MinIO", "Desc",
                "MINIO", "http://127.0.0.1:1", "b", "ak", "sk", "us-east-1"
        );

        var result = service.testConnection(caller, PROJECT_ID, onPrem);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isNotEqualTo("Target host is not permitted");
    }

    @Test
    @DisplayName("previewDataset throws PreviewFailedException for repository types that cannot be previewed")
    void preview_unsupported_repository_type_throws() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        ObjectStorageRepository repo = new ObjectStorageRepository(
                repoId, PROJECT_ID, TENANT_ID, "Store", "Desc",
                "S3", "", "bucket", "ak", "sk", "us-east-1");
        repositoryStore.save(
                new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), repo);

        assertThatThrownBy(() -> service.previewDataset(caller, PROJECT_ID, repoId, "orders"))
                .isInstanceOf(PreviewFailedException.class)
                .hasMessageContaining("not available");
    }

    // -------------------------------------------------------------------------
    //  F-CRED-5 (sec-audit): a masked (kept) credential must not be redirected to a
    //  new connection target. Otherwise a project member who cannot see the stored
    //  password submits {host: attacker, password: "********"} and the next
    //  refresh/scan delivers the real credential to the attacker's server.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("registering a source at the cloud metadata endpoint is refused before it is stored (F-S1-1)")
    void create_relational_screens_metadata_host() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));
        var repo = new RelationalDatabaseRepository(UUID.randomUUID(), PROJECT_ID, TENANT_ID,
                "PG", "", "POSTGRESQL", "169.254.169.254", 5432, "db", "u", "p", "public", true);
        assertThatThrownBy(() -> service.createRepository(caller, PROJECT_ID, repo))
                .isInstanceOf(edu.upc.dtak.aran.core.net.OutboundAddressGuard.BlockedHostException.class);
    }

    @Test
    @DisplayName("repointing a stored source at the cloud metadata endpoint is refused (F-S1-1)")
    void update_relational_screens_metadata_host() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));
        UUID repoId = UUID.randomUUID();
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()),
                new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                        "PG", "", "POSTGRESQL", "db.internal", 5432, "db", "u", "s3cr3t", "public", true));
        var moved = new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "PG", "", "POSTGRESQL", "169.254.169.254", 5432, "db", "u", "newpass", "public", true);
        assertThatThrownBy(() -> service.updateRepository(caller, PROJECT_ID, moved))
                .isInstanceOf(edu.upc.dtak.aran.core.net.OutboundAddressGuard.BlockedHostException.class);
    }

    @Test
    @DisplayName("a masked relational credential cannot be redirected to a new host (F-CRED-5)")
    void update_relational_masked_credential_cannot_change_host() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        var stored = new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "PG", "Desc", "POSTGRESQL", "db.internal", 5432, "warehouse",
                "aran", "s3cr3t", "public", true);
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), stored);

        var redirected = new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "PG", "Desc", "POSTGRESQL", "attacker.example.com", 5432, "warehouse",
                "aran", "********", "public", true);

        assertThatThrownBy(() -> service.updateRepository(caller, PROJECT_ID, redirected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential");
    }

    @Test
    @DisplayName("a masked relational credential still allows an in-place edit (rename), same host")
    void update_relational_masked_credential_rename_is_allowed() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        var stored = new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "PG", "Desc", "POSTGRESQL", "db.internal", 5432, "warehouse",
                "aran", "s3cr3t", "public", true);
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), stored);

        var renamed = new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "PG (prod)", "Desc", "POSTGRESQL", "db.internal", 5432, "warehouse",
                "aran", "********", "public", true);
        var updated = (RelationalDatabaseRepository) service.updateRepository(caller, PROJECT_ID, renamed);

        assertThat(updated.name()).isEqualTo("PG (prod)");
        assertThat(updated.password()).isEqualTo("s3cr3t");
    }

    @Test
    @DisplayName("changing the host is allowed when the credential is re-entered")
    void update_relational_new_credential_may_change_host() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        var stored = new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "PG", "Desc", "POSTGRESQL", "db.internal", 5432, "warehouse",
                "aran", "s3cr3t", "public", true);
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), stored);

        var moved = new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "PG", "Desc", "POSTGRESQL", "db2.internal", 5432, "warehouse",
                "aran", "newpass", "public", true);
        var updated = (RelationalDatabaseRepository) service.updateRepository(caller, PROJECT_ID, moved);

        assertThat(updated.host()).isEqualTo("db2.internal");
        assertThat(updated.password()).isEqualTo("newpass");
    }

    @Test
    @DisplayName("a masked object-storage secret cannot be redirected to a new endpoint (F-CRED-5)")
    void update_object_storage_masked_secret_cannot_change_endpoint() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));
        UUID repoId = UUID.randomUUID();
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()),
                new ObjectStorageRepository(repoId, PROJECT_ID, TENANT_ID, "S3", "d",
                        "MINIO", "http://minio.internal:9000", "bucket", "ak", "REAL_SECRET", "us-east-1"));
        var redirected = new ObjectStorageRepository(repoId, PROJECT_ID, TENANT_ID, "S3", "d",
                "MINIO", "http://attacker.example.com:9000", "bucket", "ak", "********", "us-east-1");
        assertThatThrownBy(() -> service.updateRepository(caller, PROJECT_ID, redirected))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credential");
    }

    // -------------------------------------------------------------------------
    //  D1 — FILE_SYSTEM is upload-only: Aran reads no server directory for it.
    //  The kind used to list a caller-chosen root (scan) and read files under it (preview),
    //  fenced by an allowlist (T3.2, SOURCE_REGISTRATION-2). No listed file could be ingested,
    //  so that was a read of the backend's own filesystem with nothing behind it (S3-8).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a file-system repository offers nothing to scan and previews no file, whatever the name")
    void file_system_reads_no_server_file() throws Exception {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        // A real, readable CSV: if a read path ever came back for this kind, this is a file it
        // could reach — by absolute path, or relative to the working directory.
        Path dir = Files.createTempDirectory("aran-fs-upload-only");
        Path csv = Files.writeString(dir.resolve("data.csv"), "id,name\n1,Alice\n");
        UUID repoId = UUID.randomUUID();
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()),
                new FileSystemRepository(repoId, PROJECT_ID, TENANT_ID, "files", ""));

        assertThat(service.listAvailableDatasets(caller, PROJECT_ID, repoId)).isEmpty();
        for (String name : List.of(csv.toString(), "data.csv", "/etc/passwd", "../../etc/passwd")) {
            assertThatThrownBy(() -> service.previewDataset(caller, PROJECT_ID, repoId, name))
                    .as(name)
                    .isInstanceOf(PreviewFailedException.class)
                    .hasMessageContaining("not available");
        }
    }

    // -------------------------------------------------------------------------
    //  T3.3 — the outbound screen also covers scan and preview (SOURCE_REGISTRATION-10)
    //  It used to run in testConnection only, so an SSRF probe simply skipped the test
    //  and asked for a scan instead — with the driver's error text returned verbatim.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("scanning a repository at the metadata endpoint is refused before any socket opens")
    void scan_refuses_metadata_endpoint() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()),
                new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID, "Probe", "Desc",
                        "POSTGRESQL", "169.254.169.254", 5432, "aran", "u", "p", "public", false));

        assertThatThrownBy(() -> service.listAvailableDatasets(caller, PROJECT_ID, repoId))
                .isInstanceOf(edu.upc.dtak.aran.core.net.OutboundAddressGuard.BlockedHostException.class);
        verifyNoInteractions(driverDownloader);
    }

    @Test
    @DisplayName("previewing a repository at the metadata endpoint is refused before any socket opens")
    void preview_refuses_metadata_endpoint() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()),
                new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID, "Probe", "Desc",
                        "POSTGRESQL", "169.254.169.254", 5432, "aran", "u", "p", "public", false));

        assertThatThrownBy(() -> service.previewDataset(caller, PROJECT_ID, repoId, "users"))
                .isInstanceOf(edu.upc.dtak.aran.core.net.OutboundAddressGuard.BlockedHostException.class);
        verifyNoInteractions(driverDownloader);
    }

    @Test
    @DisplayName("deleteRepository detaches every dataset from the integration model before the cascade (T1.8)")
    void deleteRepositoryDetachesEveryDatasetFirst() {
        AuthenticatedUser caller = caller();
        when(projectService.getVisibleProject(caller, PROJECT_ID)).thenReturn(mock(Project.class));

        UUID repoId = UUID.randomUUID();
        repositoryStore.save(new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()),
                new FileSystemRepository(repoId, PROJECT_ID, TENANT_ID, "files", ""));
        UUID ds1 = UUID.randomUUID();
        UUID ds2 = UUID.randomUUID();
        when(datasetRepository.findAllByRepository(
                any(edu.upc.dtak.aran.core.tenant.TenantContext.class), eq(repoId)))
                .thenReturn(List.of(
                        new edu.upc.dtak.aran.core.domain.Dataset(ds1, repoId, "a", ""),
                        new edu.upc.dtak.aran.core.domain.Dataset(ds2, repoId, "b", "")));

        service.deleteRepository(caller, PROJECT_ID, repoId);

        // The DB cascade (V7) deletes the dataset rows; the model-side cleanup must run first,
        // per dataset, or the enrolments/mappings/source graphs orphan (audit MIGRATIONS-3).
        verify(integrationModels).detachDataset(
                any(edu.upc.dtak.aran.core.tenant.TenantContext.class), eq(PROJECT_ID), eq(ds1));
        verify(integrationModels).detachDataset(
                any(edu.upc.dtak.aran.core.tenant.TenantContext.class), eq(PROJECT_ID), eq(ds2));
        assertThat(repositoryStore.findById(
                new edu.upc.dtak.aran.core.tenant.TenantContext(TENANT_ID.toString()), repoId))
                .isEmpty();
    }
}

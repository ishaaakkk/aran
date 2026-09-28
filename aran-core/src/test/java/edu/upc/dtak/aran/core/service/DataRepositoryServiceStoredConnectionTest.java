package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.ConnectionCheck;
import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.SingerTapExtractor;
import edu.upc.dtak.aran.core.domain.SingerTapRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.InMemoryConnectionCheckRepository;
import edu.upc.dtak.aran.core.fake.InMemoryDataRepositoryRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import edu.upc.dtak.aran.core.source.TapConfig;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * UI audit J2-F1: a connection test of a SAVED repository must dial the
 * credential that is stored, never the {@code ********} mask every API response carries.
 *
 * <p>The repositories list used to post each row's GET body back to the body-only test route, so a
 * password-protected Postgres read UNREACHABLE on every visit while the dialog that registered it
 * had said "connected" a minute earlier. The stored-repository test resolves the credential
 * server-side; the body-only route refuses to dial a mask at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Testing a stored repository dials its stored credential (J2-F1)")
class DataRepositoryServiceStoredConnectionTest {

    private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-00000000000a");
    private static final UUID CALLER = UUID.fromString("22222222-0000-0000-0000-00000000000a");
    private static final UUID PROJECT = UUID.fromString("33333333-0000-0000-0000-00000000000a");
    private static final UUID OTHER_PROJECT = UUID.fromString("33333333-0000-0000-0000-00000000000b");
    private static final UUID REPO = UUID.fromString("44444444-0000-0000-0000-00000000000a");
    private static final String PASSWORD = "s3cret-pg-password";
    private static final String MASK = "********";

    @Mock
    private ProjectService projectService;
    @Mock
    private DriverDownloader driverDownloader;
    @Mock
    private edu.upc.dtak.aran.core.domain.DatasetRepository datasetRepository;
    @Mock
    private IntegrationModelService integrationModels;

    private InMemoryDataRepositoryRepository repositoryStore;
    private InMemoryConnectionCheckRepository checks;
    private CapturingDriver driver;
    private final RecordingCatalog catalog = new RecordingCatalog();
    private final RecordingTapExtractor tapExtractor = new RecordingTapExtractor();

    /** A JDBC driver that accepts exactly {@link #PASSWORD} and remembers every password it saw. */
    private static final class CapturingDriver implements Driver {
        final List<String> passwords = new ArrayList<>();
        java.time.Instant dialedAt;

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            passwords.add(info.getProperty("password"));
            dialedAt = java.time.Instant.now();
            try {
                // A probe takes time; a check stamped after it returns is visibly later than this.
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!PASSWORD.equals(info.getProperty("password"))) {
                throw new SQLException("FATAL: password authentication failed for user \"aran\"", "28P01");
            }
            Connection connection = mock(Connection.class);
            DatabaseMetaData meta = mock(DatabaseMetaData.class);
            when(connection.getMetaData()).thenReturn(meta);
            when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(meta.getDatabaseProductVersion()).thenReturn("17.0");
            return connection;
        }

        @Override public boolean acceptsURL(String url) { return true; }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    private static final class RecordingCatalog implements IcebergCatalogClient {
        IcebergRestRepository sawRepository;

        @Override
        public Map<String, String> config(IcebergRestRepository repository) {
            this.sawRepository = repository;
            return Map.of();
        }

        @Override
        public List<String> listTables(IcebergRestRepository repository) {
            return List.of();
        }

        @Override
        public IcebergTable loadTable(IcebergRestRepository repository, String tableIdentifier) {
            throw new UnsupportedOperationException("not exercised here");
        }
    }

    private static final class RecordingTapExtractor implements SingerTapExtractor {
        SingerTapRepository sawRepository;

        @Override
        public List<String> discoverStreams(SingerTapRepository repository) {
            this.sawRepository = repository;
            return List.of("rates");
        }

        @Override
        public Extracted extract(SingerTapRepository repository, String stream, long maxBytes) {
            throw new UnsupportedOperationException("not exercised here");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        repositoryStore = new InMemoryDataRepositoryRepository();
        checks = new InMemoryConnectionCheckRepository();
        driver = new CapturingDriver();
        when(projectService.getVisibleProject(any(), any())).thenReturn(mock(Project.class));
        when(driverDownloader.loadDriver("POSTGRESQL")).thenReturn(driver);
    }

    private DataRepositoryService service() {
        return new DataRepositoryService(repositoryStore, projectService, driverDownloader,
                datasetRepository, integrationModels, false, taps(), Optional.of(tapExtractor),
                Optional.of(catalog), Optional.of(checks));
    }

    private static SingerTapCatalog taps() {
        return new SingerTapCatalog(List.of(new SingerTapCatalog.Tap(
                "exchangeratesapi", "Exchange Rates API", List.of("/opt/taps/bin/tap"),
                Set.of("base", "api_key"), Set.of("api_key"))));
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(CALLER, "caller-subject", TENANT, TenantRole.OWNER);
    }

    private static TenantContext tenant() {
        return new TenantContext(TENANT.toString());
    }

    private static RelationalDatabaseRepository postgres(String host, String schema, String password) {
        return new RelationalDatabaseRepository(REPO, PROJECT, TENANT, "prod-pg", "",
                "POSTGRESQL", host, 5432, "sourcedb", "aran", password, schema, false);
    }

    private void storePostgres() {
        repositoryStore.save(tenant(), postgres("localhost", "public", PASSWORD));
    }

    /** What the list shows: each repository's last check, keyed by repository. */
    private static Map<UUID, ConnectionCheck> lastChecks(DataRepositoryService service) {
        Map<UUID, ConnectionCheck> byRepository = new java.util.HashMap<>();
        for (DataRepositoryService.ListedRepository listed : service.listRepositoriesWithLastCheck(caller(), PROJECT)) {
            if (listed.lastCheck() != null) {
                byRepository.put(listed.repository().id(), listed.lastCheck());
            }
        }
        return byRepository;
    }

    // ── the durable fix: the stored credential, resolved server-side ─────────────────────────

    @Test
    @DisplayName("a stored repository is tested with its stored password — the list's J2-F1 path")
    void storedTestDialsTheStoredPassword() {
        storePostgres();

        ConnectionCheck check = service().testStoredConnection(caller(), PROJECT, REPO, null);

        assertThat(driver.passwords).containsExactly(PASSWORD);
        assertThat(check.success()).isTrue();
        assertThat(check.message()).contains("Connected");
    }

    @Test
    @DisplayName("the edit dialog's test keeps a masked password stored, and applies the other edits")
    void editsWithAMaskedPasswordAreTestedWithTheStoredOne() {
        storePostgres();

        ConnectionCheck check = service().testStoredConnection(
                caller(), PROJECT, REPO, postgres("localhost", "reporting", MASK));

        assertThat(driver.passwords).containsExactly(PASSWORD);
        assertThat(check.success()).isTrue();
    }

    @Test
    @DisplayName("a masked password pointed at a new host is refused before anything is dialed (F-CRED-5)")
    void aMaskedPasswordCannotBeRedirectedByATest() {
        storePostgres();

        assertThatThrownBy(() -> service().testStoredConnection(
                caller(), PROJECT, REPO, postgres("attacker.example", "public", MASK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Re-enter the credential");
        assertThat(driver.passwords).isEmpty();
    }

    @Test
    @DisplayName("a repository of another project is not testable through this one")
    void anotherProjectsRepositoryIsRefused() {
        repositoryStore.save(tenant(), new RelationalDatabaseRepository(REPO, OTHER_PROJECT, TENANT,
                "prod-pg", "", "POSTGRESQL", "localhost", 5432, "sourcedb", "aran", PASSWORD,
                "public", false));

        assertThatThrownBy(() -> service().testStoredConnection(caller(), PROJECT, REPO, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(driver.passwords).isEmpty();
    }

    @Test
    @DisplayName("an Iceberg catalog is tested with its stored token, not the mask")
    void icebergStoredTestUsesTheStoredToken() {
        repositoryStore.save(tenant(), new IcebergRestRepository(REPO, PROJECT, TENANT, "lake", "",
                "http://localhost:8181/api/catalog", "prod", "stored-bearer-token",
                "MINIO", "http://localhost:9000", "AKIA", "stored-s3-secret", "us-east-1"));

        ConnectionCheck check = service().testStoredConnection(caller(), PROJECT, REPO, null);

        assertThat(check.success()).isTrue();
        assertThat(catalog.sawRepository.token()).isEqualTo("stored-bearer-token");
    }

    @Test
    @DisplayName("a tap is tested with each stored secret setting, per key")
    void tapStoredTestUsesTheStoredSecret() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("base", "EUR");
        config.put("api_key", "stored-api-key");
        repositoryStore.save(tenant(), new SingerTapRepository(REPO, PROJECT, TENANT, "rates", "",
                "exchangeratesapi", TapConfig.toJson(config)));

        Map<String, String> masked = new LinkedHashMap<>(config);
        masked.put("api_key", MASK);
        ConnectionCheck check = service().testStoredConnection(caller(), PROJECT, REPO,
                new SingerTapRepository(REPO, PROJECT, TENANT, "rates", "",
                        "exchangeratesapi", TapConfig.toJson(masked)));

        assertThat(check.success()).isTrue();
        assertThat(TapConfig.toMap(tapExtractor.sawRepository.configJson()))
                .containsEntry("api_key", "stored-api-key");
    }

    // ── the body-only route never dials a mask ───────────────────────────────────────────────

    @Test
    @DisplayName("the body-only test refuses a masked password instead of dialing eight asterisks")
    void bodyOnlyTestRefusesAMaskedPassword() {
        var result = service().testConnection(caller(), PROJECT, postgres("localhost", "public", MASK));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("password");
        assertThat(result.details()).contains("saved repository");
        verifyNoInteractions(driverDownloader);
    }

    @Test
    @DisplayName("the body-only test refuses a masked object-storage secret too")
    void bodyOnlyTestRefusesAMaskedSecretKey() {
        var result = service().testConnection(caller(), PROJECT, new ObjectStorageRepository(
                REPO, PROJECT, TENANT, "bucket", "", "MINIO", "http://127.0.0.1:1", "b", "AKIA",
                MASK, "us-east-1"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("secret key");
    }

    // ── what the list reads: the last explicit test, not a probe per visit (J2-F19) ─────────

    @Test
    @DisplayName("a test of the stored configuration is recorded as the repository's last check")
    void aStoredTestIsRecorded() {
        storePostgres();

        service().testStoredConnection(caller(), PROJECT, REPO, null);

        Map<UUID, ConnectionCheck> last = lastChecks(service());
        assertThat(last).containsKey(REPO);
        assertThat(last.get(REPO).success()).isTrue();
        assertThat(last.get(REPO).testedBy()).isEqualTo(CALLER);
    }

    @Test
    @DisplayName("a check is stamped when the configuration was read, not when the probe returned")
    void aCheckIsStampedBeforeItsProbe() throws Exception {
        // A save landing mid-probe must be later than the check, so the list drops the check as
        // describing the configuration the save replaced (J2-F19; ConnectionCheckRepository).
        storePostgres();
        java.time.Instant before = java.time.Instant.now();
        Thread.sleep(2);

        ConnectionCheck check = service().testStoredConnection(caller(), PROJECT, REPO, null);

        assertThat(check.testedAt()).isAfterOrEqualTo(before).isBeforeOrEqualTo(driver.dialedAt);
        assertThat(lastChecks(service()).get(REPO).testedAt()).isEqualTo(check.testedAt());
    }

    @Test
    @DisplayName("a failed stored test is recorded too, with the source's own reason")
    void aFailedStoredTestIsRecorded() {
        repositoryStore.save(tenant(), postgres("localhost", "public", "a-wrong-password"));

        ConnectionCheck check = service().testStoredConnection(caller(), PROJECT, REPO, null);

        assertThat(check.success()).isFalse();
        assertThat(check.details()).contains("password authentication failed");
        assertThat(lastChecks(service()).get(REPO).success()).isFalse();
    }

    @Test
    @DisplayName("a test of unsaved edits is not recorded: it does not describe the stored repository")
    void aTestOfEditsIsNotRecorded() {
        storePostgres();

        service().testStoredConnection(caller(), PROJECT, REPO, postgres("localhost", "reporting", MASK));

        assertThat(checks.size()).isZero();
    }

    @Test
    @DisplayName("an unchanged edit form is a test of the stored configuration, and is recorded")
    void anUnchangedEditFormIsRecorded() {
        storePostgres();

        service().testStoredConnection(caller(), PROJECT, REPO, postgres("localhost", "public", MASK));

        assertThat(checks.size()).isOne();
    }

    @Test
    @DisplayName("saving a change forgets the last check, which described the old configuration")
    void anUpdateForgetsTheLastCheck() {
        storePostgres();
        service().testStoredConnection(caller(), PROJECT, REPO, null);

        service().updateRepository(caller(), PROJECT, postgres("localhost", "reporting", MASK));

        assertThat(lastChecks(service())).isEmpty();
    }

    @Test
    @DisplayName("a build without the check store still tests, and simply remembers nothing")
    void noCheckStoreStillTests() {
        storePostgres();
        DataRepositoryService withoutStore = new DataRepositoryService(repositoryStore, projectService,
                driverDownloader, datasetRepository, integrationModels, false, taps(),
                Optional.of(tapExtractor), Optional.of(catalog));

        ConnectionCheck check = withoutStore.testStoredConnection(caller(), PROJECT, REPO, null);

        assertThat(check.success()).isTrue();
        assertThat(lastChecks(withoutStore)).isEmpty();
    }
}

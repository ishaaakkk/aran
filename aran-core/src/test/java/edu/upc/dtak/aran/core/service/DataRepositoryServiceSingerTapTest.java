package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.SingerTapExtractor;
import edu.upc.dtak.aran.core.domain.SingerTapRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.InMemoryDataRepositoryRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import edu.upc.dtak.aran.core.source.TapConfig;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * The SINGER_TAP kind at the service tier (T6): what the catalogue authorises, what the sentinel
 * preserves, and what a probe actually proves.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataRepositoryServiceSingerTapTest {

    private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-000000000009");
    private static final UUID CALLER = UUID.fromString("22222222-0000-0000-0000-000000000009");
    private static final UUID PROJECT = UUID.fromString("33333333-0000-0000-0000-000000000009");
    private static final UUID REPO = UUID.fromString("44444444-0000-0000-0000-000000000009");
    private static final String SECRET = "k-live-9f2-do-not-leak";

    @Mock
    private ProjectService projectService;
    @Mock
    private DriverDownloader driverDownloader;
    @Mock
    private edu.upc.dtak.aran.core.domain.DatasetRepository datasetRepository;
    @Mock
    private IntegrationModelService integrationModels;

    private InMemoryDataRepositoryRepository repositoryStore;
    private final RecordingTapExtractor extractor = new RecordingTapExtractor();

    /** Answers with streams (or the failure it was told to) without launching anything. */
    private static final class RecordingTapExtractor implements SingerTapExtractor {
        List<String> streams = List.of("users", "orders");
        RuntimeException failWith;
        SingerTapRepository sawRepository;

        @Override
        public List<String> discoverStreams(SingerTapRepository repository) {
            this.sawRepository = repository;
            if (failWith != null) {
                throw failWith;
            }
            return streams;
        }

        @Override
        public Extracted extract(SingerTapRepository repository, String stream, long maxBytes) {
            return new Extracted("PAR1".getBytes(StandardCharsets.UTF_8), 1);
        }
    }

    private static SingerTapCatalog catalog() {
        return new SingerTapCatalog(List.of(new SingerTapCatalog.Tap(
                "exchangeratesapi", "Exchange Rates API", List.of("/opt/taps/bin/tap"),
                Set.of("base", "api_key"), Set.of("api_key"))));
    }

    private DataRepositoryService service() {
        return service(Optional.of(extractor));
    }

    private DataRepositoryService service(Optional<SingerTapExtractor> tapExtractor) {
        return new DataRepositoryService(repositoryStore, projectService, driverDownloader,
                datasetRepository, integrationModels, false, catalog(), tapExtractor);
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(CALLER, "caller-subject", TENANT, TenantRole.OWNER);
    }

    private static Map<String, String> config(String apiKey) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("base", "EUR");
        config.put("api_key", apiKey);
        return config;
    }

    private static SingerTapRepository repo(String tapId, Map<String, String> config) {
        return repo(tapId, config, "rates");
    }

    private static SingerTapRepository repo(String tapId, Map<String, String> config, String name) {
        return new SingerTapRepository(REPO, PROJECT, TENANT, name, "", tapId,
                TapConfig.toJson(config));
    }

    /** The settings the edit form sends back after a GET: values as typed, secrets masked. */
    private static Map<String, String> resubmitted(String base, String apiKey) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("base", base);
        config.put("api_key", apiKey);
        return config;
    }

    @BeforeEach
    void setUp() {
        repositoryStore = new InMemoryDataRepositoryRepository();
        when(projectService.getVisibleProject(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(mock(Project.class));
    }

    @Test
    @DisplayName("a tap this deployment does not declare cannot be registered")
    void unknownTapIsRefusedAtCreate() {
        assertThatThrownBy(() -> service()
                .createRepository(caller(), PROJECT, repo("tap-anything", config(SECRET))))
                .isInstanceOf(SingerTapCatalog.TapNotAllowedException.class);
    }

    @Test
    @DisplayName("a setting the tap does not accept is refused rather than silently dropped")
    void unknownConfigKeyIsRefused() {
        Map<String, String> typo = new LinkedHashMap<>();
        typo.put("apiKey", SECRET);

        assertThatThrownBy(() -> service()
                .createRepository(caller(), PROJECT, repo("exchangeratesapi", typo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    @DisplayName("a declared tap with accepted settings registers")
    void validTapRegisters() {
        DataRepository saved = service()
                .createRepository(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        assertThat(saved).isInstanceOf(SingerTapRepository.class);
        assertThat(TapConfig.toMap(((SingerTapRepository) saved).configJson()))
                .containsEntry("api_key", SECRET);
    }

    @Test
    @DisplayName("the masked sentinel keeps a stored secret per key, and a new value replaces it")
    void updateSentinelIsPerKey() {
        DataRepositoryService service = service();
        service.createRepository(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        // The ordinary edit-form round trip: nothing but the repository's own name changed, and
        // the secret comes back masked because the form never held it.
        SingerTapRepository updated = (SingerTapRepository) service.updateRepository(
                caller(), PROJECT,
                repo("exchangeratesapi", resubmitted("EUR", "********"), "euro rates"));

        assertThat(updated.name()).isEqualTo("euro rates");
        assertThat(TapConfig.toMap(updated.configJson()))
                .containsEntry("base", "EUR")
                .containsEntry("api_key", SECRET);

        SingerTapRepository rotated = (SingerTapRepository) service.updateRepository(
                caller(), PROJECT, repo("exchangeratesapi", config("k-live-rotated")));

        assertThat(TapConfig.toMap(rotated.configJson()))
                .containsEntry("api_key", "k-live-rotated");
    }

    @Test
    @DisplayName("a kept (masked) secret may not travel with an edited non-secret setting")
    void maskedSecretCannotBeRedirected() {
        // F-CRED-5 for taps: a tap's target and its credential live in the SAME settings map, so
        // editing `base` (stand-in for the api_url most real taps expose) while the secret comes
        // back masked is exactly the redirect the other credentialled kinds refuse. A member who
        // cannot see the credential must not be able to point it somewhere new.
        DataRepositoryService service = service();
        service.createRepository(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        assertThatThrownBy(() -> service.updateRepository(caller(), PROJECT,
                repo("exchangeratesapi", resubmitted("USD", "********"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Re-enter the credential");

        // Refused before the store, so neither half of the redirect landed.
        SingerTapRepository stored = (SingerTapRepository) repositoryStore
                .findById(new TenantContext(TENANT.toString()), REPO).orElseThrow();
        assertThat(TapConfig.toMap(stored.configJson()))
                .containsEntry("base", "EUR")
                .containsEntry("api_key", SECRET);
    }

    @Test
    @DisplayName("re-entering the credential is the price: the same edit goes through with it")
    void redirectIsAllowedWhenTheCredentialIsReEntered() {
        DataRepositoryService service = service();
        service.createRepository(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        SingerTapRepository updated = (SingerTapRepository) service.updateRepository(
                caller(), PROJECT, repo("exchangeratesapi", resubmitted("USD", "k-live-rotated")));

        assertThat(TapConfig.toMap(updated.configJson()))
                .containsEntry("base", "USD")
                .containsEntry("api_key", "k-live-rotated");
    }

    @Test
    @DisplayName("rotating only the secret, with the settings unchanged, needs no re-entry ritual")
    void secretRotationAloneIsAccepted() {
        DataRepositoryService service = service();
        service.createRepository(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        SingerTapRepository updated = (SingerTapRepository) service.updateRepository(
                caller(), PROJECT, repo("exchangeratesapi", resubmitted("EUR", "k-live-rotated")));

        assertThat(TapConfig.toMap(updated.configJson()))
                .containsEntry("base", "EUR")
                .containsEntry("api_key", "k-live-rotated");
    }

    @Test
    @DisplayName("a key dropped from the submitted settings is dropped, not resurrected")
    void sentinelDoesNotResurrectRemovedKeys() {
        // The sentinel means "keep the stored VALUE for this key", never "keep every key the row
        // used to have" — otherwise a setting could not be cleared from the form.
        DataRepositoryService service = service();
        service.createRepository(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        SingerTapRepository updated = (SingerTapRepository) service.updateRepository(
                caller(), PROJECT, repo("exchangeratesapi", Map.of("base", "GBP")));

        assertThat(TapConfig.toMap(updated.configJson())).containsOnlyKeys("base");
    }

    @Test
    @DisplayName("a connection test really runs the tap and reports the streams it found")
    void testConnectionRunsDiscovery() {
        extractor.streams = List.of("users", "orders");

        DataRepositoryService.ConnectionTestResult result = service()
                .testConnection(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("2");
        assertThat(extractor.sawRepository).isNotNull();
    }

    @Test
    @DisplayName("a tap that cannot run fails the test with its own reason, secret not echoed")
    void testConnectionReportsTapFailure() {
        extractor.failWith = new IllegalStateException("tap exited with status 1");

        DataRepositoryService.ConnectionTestResult result = service()
                .testConnection(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        assertThat(result.success()).isFalse();
        assertThat(result.details()).contains("status 1");
        assertThat(result.message() + result.details()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a build with no tap extractor says so instead of claiming the tap works")
    void testConnectionWithoutExtractor() {
        DataRepositoryService.ConnectionTestResult result = service(Optional.empty())
                .testConnection(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("tap");
    }

    @Test
    @DisplayName("a scan lists the tap's streams, the way a relational scan lists tables")
    void scanListsStreams() {
        DataRepositoryService service = service();
        service.createRepository(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        assertThat(service.listAvailableDatasets(caller(), PROJECT, REPO))
                .containsExactly("users", "orders");
    }

    @Test
    @DisplayName("a preview says it is unavailable rather than inventing rows")
    void previewIsHonestlyUnavailable() {
        DataRepositoryService service = service();
        service.createRepository(caller(), PROJECT, repo("exchangeratesapi", config(SECRET)));

        assertThatThrownBy(() -> service.previewDataset(caller(), PROJECT, REPO, "users"))
                .isInstanceOf(PreviewFailedException.class);
    }
}

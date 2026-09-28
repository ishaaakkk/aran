package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.*;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import edu.upc.dtak.aran.core.source.RelationalEngine;
import edu.upc.dtak.aran.core.source.RelationalEngines;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import edu.upc.dtak.aran.core.source.TapConfig;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service managing {@link DataRepository} lifecycles and connection tests.
 */
@Service
@Transactional
@NullMarked
public class DataRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(DataRepositoryService.class);

    private final DataRepositoryRepository repositoryStore;
    private final ProjectService projectService;
    private final DriverDownloader driverDownloader;
    /** T1.8: repository deletion cascades its datasets (V7), so their model-side cleanup runs here first. */
    private final DatasetRepository datasetRepository;
    private final IntegrationModelService integrationModels;
    private final OutboundAddressGuard addressGuard;
    /** T6: which Singer taps this deployment allows. Empty = the kind cannot be registered. */
    private final SingerTapCatalog taps;
    /**
     * T6: runs a tap, or null in a build without the bootstrapping operator on the classpath —
     * the operator modules are {@code runtimeOnly}, the same reachable-null this service's
     * {@code DriverDownloader} sibling in {@code DatasetService} handles.
     */
    private final @Nullable SingerTapExtractor tapExtractor;
    /**
     * Gap 2: speaks the Iceberg REST protocol, or null in a build without the iceberg adapter on
     * the classpath — the same reachable-null as {@link #tapExtractor}.
     */
    private final @Nullable IcebergCatalogClient icebergCatalog;
    /**
     * V46: each stored repository's last explicit connection test, or null where no store is wired
     * (the unit-test constructors) — then a test still runs and is simply not remembered.
     */
    private final @Nullable ConnectionCheckRepository connectionChecks;

    /** The value a masked secret is echoed as, and the sentinel an update sends back unchanged. */
    private static final String MASKED = "********";

    /** Test-friendly constructor without the Iceberg catalog client — the pre-Gap-2 shape. */
    public DataRepositoryService(
            DataRepositoryRepository repositoryStore,
            ProjectService projectService,
            DriverDownloader driverDownloader,
            DatasetRepository datasetRepository,
            IntegrationModelService integrationModels,
            boolean blockPrivateNetworks,
            SingerTapCatalog taps,
            Optional<SingerTapExtractor> tapExtractor) {
        this(repositoryStore, projectService, driverDownloader, datasetRepository,
                integrationModels, blockPrivateNetworks, taps, tapExtractor, Optional.empty());
    }

    /** Test-friendly constructor without the connection-check store — the pre-V46 shape. */
    public DataRepositoryService(
            DataRepositoryRepository repositoryStore,
            ProjectService projectService,
            DriverDownloader driverDownloader,
            DatasetRepository datasetRepository,
            IntegrationModelService integrationModels,
            boolean blockPrivateNetworks,
            SingerTapCatalog taps,
            Optional<SingerTapExtractor> tapExtractor,
            Optional<IcebergCatalogClient> icebergCatalog) {
        this(repositoryStore, projectService, driverDownloader, datasetRepository,
                integrationModels, blockPrivateNetworks, taps, tapExtractor, icebergCatalog,
                Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DataRepositoryService(
            DataRepositoryRepository repositoryStore,
            ProjectService projectService,
            DriverDownloader driverDownloader,
            DatasetRepository datasetRepository,
            IntegrationModelService integrationModels,
            @Value("${aran.security.block-private-networks:false}") boolean blockPrivateNetworks,
            SingerTapCatalog taps,
            Optional<SingerTapExtractor> tapExtractor,
            Optional<IcebergCatalogClient> icebergCatalog,
            Optional<ConnectionCheckRepository> connectionChecks) {
        this.addressGuard = new OutboundAddressGuard(blockPrivateNetworks);
        this.repositoryStore = repositoryStore;
        this.projectService = projectService;
        this.driverDownloader = driverDownloader;
        this.datasetRepository = datasetRepository;
        this.integrationModels = integrationModels;
        this.taps = taps;
        this.tapExtractor = tapExtractor.orElse(null);
        this.icebergCatalog = icebergCatalog.orElse(null);
        this.connectionChecks = connectionChecks.orElse(null);
    }

    /**
     * Lists all repositories scoped to a project, after validating caller visibility.
     */
    @Transactional(readOnly = true)
    public List<DataRepository> listRepositories(AuthenticatedUser caller, UUID projectId) {
        projectService.getVisibleProject(caller, projectId);

        return repositoryStore.findAllByProject(new TenantContext(caller.tenantId().toString()), projectId);
    }

    @Transactional(readOnly = true)
    public DataRepository getRepository(AuthenticatedUser caller, UUID projectId, UUID repositoryId) {
        projectService.getVisibleProject(caller, projectId);

        DataRepository repo = repositoryStore.findById(new TenantContext(caller.tenantId().toString()), repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + repositoryId));

        if (!repo.projectId().equals(projectId)) {
            throw new AccessDeniedException("Repository " + repositoryId + " does not belong to project " + projectId);
        }

        return repo;
    }

    public DataRepository createRepository(AuthenticatedUser caller, UUID projectId, DataRepository repository) {
        projectService.getVisibleProject(caller, projectId);

        if (!repository.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Repository project ID must match target project");
        }
        if (!repository.tenantId().equals(caller.tenantId())) {
            throw new IllegalArgumentException("Repository tenant ID must match caller's active tenant");
        }
        if (repository instanceof SingerTapRepository tapRepo) {
            requireAllowedTap(tapRepo);
        }
        // Screen the target before it is STORED (sec-audit F-S1-1), so an internal/metadata host is
        // not persisted and later reached by the unattended import/harvest/federated paths. Lenient
        // on resolution: registration must not require the host to be live (the connect paths screen
        // strictly when they actually dial).
        screenStoredTarget(repository);
        return repositoryStore.save(new TenantContext(caller.tenantId().toString()), repository);
    }

    /**
     * A masked (kept) credential may not be redirected to a new connection target. Otherwise a
     * project member who cannot see the stored secret — every API response masks it — submits
     * {@code {host: attacker, password: "********"}}, and the next refresh/scan/bootstrap reloads
     * the stored repository and delivers the real credential to the attacker's server during the
     * connection handshake (sec-audit F-CRED-5). Re-entering the credential is the price of
     * pointing it somewhere new.
     */
    private static void requireUnchangedCredentialTarget(boolean targetUnchanged) {
        if (!targetUnchanged) {
            throw new IllegalArgumentException(
                    "Re-enter the credential to change the connection target — a kept (masked) "
                            + "credential may not be redirected to a new host or endpoint");
        }
    }

    public DataRepository updateRepository(AuthenticatedUser caller, UUID projectId, DataRepository repository) {
        projectService.getVisibleProject(caller, projectId);

        DataRepository existing = getRepository(caller, projectId, repository.id());
        DataRepository merged = withStoredSecrets(existing, repository, projectId, caller);
        if (merged instanceof SingerTapRepository tapRepo) {
            requireAllowedTap(tapRepo);
        }

        // Screen the (possibly changed) target before it is stored — see createRepository (F-S1-1).
        screenStoredTarget(merged);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        DataRepository saved = repositoryStore.save(tenant, merged);
        // The last check described the configuration this save replaced. Kept, it would read
        // "connected" for a host nobody has dialed yet (J2-F19).
        if (connectionChecks != null) {
            connectionChecks.delete(tenant, saved.id());
        }
        return saved;
    }

    /**
     * The submitted repository with every masked credential replaced by the stored one — the
     * {@code ********} sentinel an edit form sends back for "keep what is stored".
     *
     * <p>One definition for its two readers: an update, which persists the result, and a test of
     * the edit dialog's unsaved settings, which dials it. Both apply F-CRED-5 — a kept credential
     * may not travel to a new target — so a test can never be the way to deliver a stored password
     * to a host the caller chose.
     */
    private DataRepository withStoredSecrets(DataRepository existing, DataRepository repository,
                                             UUID projectId, AuthenticatedUser caller) {
        if (!repository.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Repository project ID must match target project");
        }
        if (!repository.tenantId().equals(caller.tenantId())) {
            throw new IllegalArgumentException("Repository tenant ID must match caller's active tenant");
        }

        if (repository instanceof RelationalDatabaseRepository relational) {
            if (!(existing instanceof RelationalDatabaseRepository existingRelational)) {
                throw new IllegalStateException(
                        "Repository type mismatch: stored record is not a RelationalDatabaseRepository");
            }
            String updatedPassword = relational.password();
            if (MASKED.equals(updatedPassword)) {
                requireUnchangedCredentialTarget(
                        relational.host().equals(existingRelational.host())
                                && relational.port() == existingRelational.port()
                                && relational.database().equals(existingRelational.database()));
                updatedPassword = existingRelational.password();
            }
            return new RelationalDatabaseRepository(
                    relational.id(), relational.projectId(), relational.tenantId(),
                    relational.name(), relational.description(), relational.engine(),
                    relational.host(), relational.port(), relational.database(),
                    relational.username(), updatedPassword, relational.schema(),
                    relational.federated()
            );
        } else if (repository instanceof ObjectStorageRepository objectStorage) {
            if (!(existing instanceof ObjectStorageRepository existingOS)) {
                throw new IllegalStateException(
                        "Repository type mismatch: stored record is not an ObjectStorageRepository");
            }
            String updatedSecret = objectStorage.secretKey();
            if (MASKED.equals(updatedSecret)) {
                requireUnchangedCredentialTarget(
                        objectStorage.endpoint().equals(existingOS.endpoint())
                                && objectStorage.accessKey().equals(existingOS.accessKey()));
                updatedSecret = existingOS.secretKey();
            }
            return new ObjectStorageRepository(
                    objectStorage.id(), objectStorage.projectId(), objectStorage.tenantId(),
                    objectStorage.name(), objectStorage.description(), objectStorage.provider(),
                    objectStorage.endpoint(), objectStorage.bucketName(), objectStorage.accessKey(),
                    updatedSecret, objectStorage.region()
            );
        } else if (repository instanceof IcebergRestRepository iceberg) {
            if (!(existing instanceof IcebergRestRepository existingIce)) {
                throw new IllegalStateException(
                        "Repository type mismatch: stored record is not an IcebergRestRepository");
            }
            // Two credentials, two sentinels, two redirect guards: the catalog token is tied to the
            // catalog URI, the data-plane secret to its endpoint+accessKey — masking one must not
            // let the other be pointed somewhere new (F-CRED-5, applied per plane).
            String updatedToken = iceberg.token();
            if (MASKED.equals(updatedToken)) {
                requireUnchangedCredentialTarget(iceberg.catalogUri().equals(existingIce.catalogUri()));
                updatedToken = existingIce.token();
            }
            String updatedSecret = iceberg.secretKey();
            if (MASKED.equals(updatedSecret)) {
                requireUnchangedCredentialTarget(
                        iceberg.endpoint().equals(existingIce.endpoint())
                                && iceberg.accessKey().equals(existingIce.accessKey()));
                updatedSecret = existingIce.secretKey();
            }
            return new IcebergRestRepository(
                    iceberg.id(), iceberg.projectId(), iceberg.tenantId(),
                    iceberg.name(), iceberg.description(), iceberg.catalogUri(),
                    iceberg.warehouse(), updatedToken, iceberg.provider(),
                    iceberg.endpoint(), iceberg.accessKey(), updatedSecret, iceberg.region()
            );
        } else if (repository instanceof SingerTapRepository tapRepo) {
            if (!(existing instanceof SingerTapRepository existingTap)) {
                throw new IllegalStateException(
                        "Repository type mismatch: stored record is not a SingerTapRepository");
            }
            // T6: the sentinel is applied PER KEY, unlike the kinds above, each of whose secrets is
            // a field of its own. A tap's settings arrive as one map holding both what the user
            // edits and what the response masked, so "is this value the sentinel" has to be asked
            // entry by entry.
            java.util.Map<String, String> submittedConfig = TapConfig.toMap(tapRepo.configJson());
            java.util.Map<String, String> storedConfig = TapConfig.toMap(existingTap.configJson());
            requireUnchangedTapTargetWhenSecretKept(tapRepo.tapId(), submittedConfig, storedConfig);
            return new SingerTapRepository(
                    tapRepo.id(), tapRepo.projectId(), tapRepo.tenantId(),
                    tapRepo.name(), tapRepo.description(), tapRepo.tapId(),
                    TapConfig.toJson(mergeTapConfig(submittedConfig, storedConfig))
            );
        }
        // A file-system repository has no credential to keep.
        return repository;
    }

    public void deleteRepository(AuthenticatedUser caller, UUID projectId, UUID repositoryId) {
        projectService.getVisibleProject(caller, projectId);

        DataRepository repo = getRepository(caller, projectId, repositoryId);

        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        // The V7 FK cascades this repository's dataset rows away with it — so the model-side
        // cleanup each dataset needs (reject mappings, flag rebuild, clear its source graph;
        // T1.8) must run first, while the datasets are still observable.
        for (var dataset : datasetRepository.findAllByRepository(tenant, repo.id())) {
            integrationModels.detachDataset(tenant, projectId, dataset.id());
        }
        repositoryStore.delete(tenant, repo.id());
    }

    /**
     * Structured result of a connectivity probe.
     *
     * @param success {@code true} when the target endpoint responded successfully.
     * @param message Short human-readable summary (shown in the UI header).
     * @param details Optional verbose detail, e.g. the raw driver or protocol error message.
     */
    public record ConnectionTestResult(boolean success, String message, String details) {
        static ConnectionTestResult ok(String message) {
            return new ConnectionTestResult(true, message, null);
        }
        static ConnectionTestResult fail(String message, String details) {
            return new ConnectionTestResult(false, message, details);
        }
    }

    /**
     * Tests connectivity to an external repository dynamically.
     *
     * <p>Every probe returns a {@link ConnectionTestResult} — callers always get a
     * structured pass/fail signal together with human-readable diagnostics.
     */
    public ConnectionTestResult testConnection(AuthenticatedUser caller, UUID projectId, DataRepository repository) {
        projectService.getVisibleProject(caller, projectId);

        // Every response masks a stored credential as the sentinel, so a body carrying it is a GET
        // posted back — the list view's old path. Dialing it fails authentication and reads as
        // "unreachable" for a source that is fine (J2-F1); the stored-repository test
        // is the route that knows the credential.
        Optional<String> masked = maskedCredentialIn(repository);
        if (masked.isPresent()) {
            return ConnectionTestResult.fail(masked.get() + " not provided",
                    "This repository's " + masked.get().toLowerCase(java.util.Locale.ROOT)
                            + " is masked in API responses. Test the saved repository, or re-enter "
                            + "it to test these settings.");
        }
        return probe(repository);
    }

    /**
     * Test a stored repository with its stored credentials (UI audit J2-F1).
     *
     * <p>With {@code edits} null this is the repositories list's test: the stored configuration,
     * exactly. With {@code edits} it is the edit dialog's test of settings not yet saved — each
     * masked credential in them is replaced by the stored one under the same F-CRED-5 rule an
     * update applies, so neither the list nor the dialog ever sends the mask as a password.
     *
     * <p>A test of exactly the stored configuration is recorded as the repository's last check
     * (V46), which is what the list shows instead of probing every source on every visit
     * (J2-F19). A test of edits that differ is not: it describes settings nobody has saved.
     *
     * <p>The check is stamped with the moment the configuration was read, before the probe runs —
     * not when the probe returns. A save that lands while the probe is in flight (a host edited
     * during "Test all") is therefore later than the check it would otherwise be mistaken for, and
     * {@link ConnectionCheckRepository#findAllByRepositoryIds} drops a check older than the
     * repository's last save: the list never reads "Connected" for a host nobody has dialed.
     *
     * @throws IllegalArgumentException when the edits point a kept credential at a new target
     */
    public ConnectionCheck testStoredConnection(AuthenticatedUser caller, UUID projectId,
                                                UUID repositoryId, @Nullable DataRepository edits) {
        java.time.Instant readAt = java.time.Instant.now();
        DataRepository stored = getRepository(caller, projectId, repositoryId);
        DataRepository target = edits == null
                ? stored
                : withStoredSecrets(stored, edits, projectId, caller);

        ConnectionTestResult result = probe(target);
        ConnectionCheck check = new ConnectionCheck(repositoryId, caller.tenantId(), result.success(),
                result.message(), result.details(), readAt, caller.userId());
        if (connectionChecks != null && target.equals(stored)) {
            connectionChecks.save(new TenantContext(caller.tenantId().toString()), check);
        }
        return check;
    }

    /** A stored repository and its last connection check, when one still describes it (V46). */
    public record ListedRepository(DataRepository repository, @Nullable ConnectionCheck lastCheck) {
    }

    /**
     * The project's repositories, each with its last recorded check — one listing, one gate, and
     * nothing dialed (J2-F19).
     */
    @Transactional(readOnly = true)
    public List<ListedRepository> listRepositoriesWithLastCheck(AuthenticatedUser caller, UUID projectId) {
        List<DataRepository> repositories = listRepositories(caller, projectId);
        java.util.Map<UUID, ConnectionCheck> byRepository = new java.util.HashMap<>();
        if (connectionChecks != null && !repositories.isEmpty()) {
            for (ConnectionCheck check : connectionChecks.findAllByRepositoryIds(
                    new TenantContext(caller.tenantId().toString()),
                    repositories.stream().map(DataRepository::id).toList())) {
                byRepository.put(check.repositoryId(), check);
            }
        }
        return repositories.stream()
                .map(r -> new ListedRepository(r, byRepository.get(r.id())))
                .toList();
    }

    /**
     * The name of the first credential in {@code repository} that is the masked sentinel, if any.
     *
     * <p>A tap's secrets are the keys its catalogue entry declares; a tap the catalogue no longer
     * declares has no answer to "which setting is a secret", so any masked value counts.
     */
    private Optional<String> maskedCredentialIn(DataRepository repository) {
        return switch (repository) {
            case RelationalDatabaseRepository jdbc ->
                    MASKED.equals(jdbc.password()) ? Optional.of("Password") : Optional.empty();
            case ObjectStorageRepository os ->
                    MASKED.equals(os.secretKey()) ? Optional.of("Secret key") : Optional.empty();
            case IcebergRestRepository ice -> MASKED.equals(ice.token())
                    ? Optional.of("Catalog token")
                    : MASKED.equals(ice.secretKey()) ? Optional.of("Secret key") : Optional.empty();
            case SingerTapRepository tap -> {
                java.util.Set<String> secretKeys = taps.find(tap.tapId())
                        .map(SingerTapCatalog.Tap::secretKeys)
                        .orElse(null);
                yield TapConfig.toMap(tap.configJson()).entrySet().stream()
                        .filter(e -> MASKED.equals(e.getValue()))
                        .filter(e -> secretKeys == null || secretKeys.contains(e.getKey()))
                        .map(e -> "Tap setting '" + e.getKey() + "'")
                        .findFirst();
            }
            case FileSystemRepository ignored -> Optional.empty();
        };
    }

    /** Screen, then dial: the probe itself, for a repository whose credentials are real. */
    private ConnectionTestResult probe(DataRepository repository) {
        try {
            screenOutboundTarget(repository);
        } catch (OutboundAddressGuard.BlockedHostException e) {
            log.warn("Refused connection test to a blocked host — {}", e.getMessage());
            return ConnectionTestResult.fail("Target host is not permitted", e.getMessage());
        }

        if (repository instanceof RelationalDatabaseRepository jdbcRepo) {
            return testJdbcConnection(jdbcRepo);
        } else if (repository instanceof FileSystemRepository) {
            // Upload-only (D1): no directory, host or credential stands behind this kind, so there
            // is nothing to probe, and the message says so instead of reporting a check that never ran.
            return ConnectionTestResult.ok("Nothing to connect to: a file-system repository holds "
                    + "the files you upload to it as datasets, and Aran reads no server directory "
                    + "for it.");
        } else if (repository instanceof ObjectStorageRepository osRepo) {
            return testObjectStorageConnection(osRepo);
        } else if (repository instanceof SingerTapRepository tapRepo) {
            return testSingerTapConnection(tapRepo);
        } else if (repository instanceof IcebergRestRepository icebergRepo) {
            return testIcebergConnection(icebergRepo);
        }

        return ConnectionTestResult.fail("Unsupported repository type", null);
    }

    /**
     * Screen the outbound target of a probe before any socket is opened.
     *
     * <p>Centralised here rather than repeated in each {@code test*Connection} method so the
     * screen is a single reviewable point, and so the failure is reported as one uniform
     * result instead of one differently-worded result per kind. {@link FileSystemRepository}
     * has no target at all: it holds uploaded files, and Aran reads no server path for it.
     *
     * <p>An {@link ObjectStorageRepository} with no endpoint falls through unscreened because
     * the provider defaults it to a public AWS/GCS/Azure URL — there is no caller-controlled
     * host to screen in that case.
     *
     * <p><b>An exhaustive switch over the sealed interface, deliberately — not an
     * {@code instanceof} chain like the rest of this file.</b> A chain ends in an implicit
     * "anything else is fine", so a new {@code DataRepository} subtype would compile
     * cleanly and silently connect without ever being screened. A security control must fail
     * closed: here, adding a subtype breaks the build until someone states its target.
     */
    private void screenOutboundTarget(DataRepository repository) {
        switch (repository) {
            case RelationalDatabaseRepository jdbcRepo -> addressGuard.checkHost(jdbcRepo.host());
            case ObjectStorageRepository osRepo -> {
                String endpoint = osRepo.endpoint();
                if (endpoint != null && !endpoint.isBlank()) {
                    addressGuard.checkUri(endpoint);
                }
            }
            // Opens no socket — see above.
            case FileSystemRepository ignored -> { }
            // T6: Aran opens no socket for a tap either — the tap dials out itself, to whatever
            // its own code decided, and no field on this repository is a target Aran could screen.
            // What stands in for the screen is one level up and stronger: the executable is named
            // by DEPLOYMENT configuration (SingerTapCatalog), so an operator has already decided
            // which programs may reach the network as this backend. Screening a config VALUE here
            // would be theatre — a tap's base URL is one of its own settings, and nothing makes
            // the tap use it.
            case SingerTapRepository ignored -> { }
            // Gap 2: both planes are caller-chosen targets and both are dialed — the catalog by
            // Aran's own HTTP client, the endpoint by DuckDB's httpfs.
            case IcebergRestRepository icebergRepo -> {
                addressGuard.checkUri(icebergRepo.catalogUri());
                String endpoint = icebergRepo.endpoint();
                if (endpoint != null && !endpoint.isBlank()) {
                    addressGuard.checkUri(endpoint);
                }
            }
        }
    }

    /**
     * The create/update-time twin of {@link #screenOutboundTarget}, but LENIENT on DNS resolution:
     * a host that does not resolve is allowed to be stored (registration must not require the target
     * to be live), while a host that resolves to a link-local/metadata address — a literal
     * {@code 169.254.169.254} or a name pointing at one — is refused (sec-audit F-S1-1). The actual
     * connect paths still apply the strict screen when they dial.
     */
    private void screenStoredTarget(DataRepository repository) {
        switch (repository) {
            case RelationalDatabaseRepository jdbcRepo -> addressGuard.checkHostIfResolvable(jdbcRepo.host());
            case ObjectStorageRepository osRepo -> {
                String endpoint = osRepo.endpoint();
                if (endpoint != null && !endpoint.isBlank()) {
                    addressGuard.checkUriIfResolvable(endpoint);
                }
            }
            case FileSystemRepository ignored -> { }
            case SingerTapRepository ignored -> { }
            case IcebergRestRepository icebergRepo -> {
                addressGuard.checkUriIfResolvable(icebergRepo.catalogUri());
                String endpoint = icebergRepo.endpoint();
                if (endpoint != null && !endpoint.isBlank()) {
                    addressGuard.checkUriIfResolvable(endpoint);
                }
            }
        }
    }

    private ConnectionTestResult testJdbcConnection(RelationalDatabaseRepository repo) {
        log.info("Testing connection to database engine {} at {}:{}", repo.engine(), repo.host(), repo.port());

        Optional<JdbcDriverRegistry.DriverMetadata> metaOpt = JdbcDriverRegistry.get(repo.engine());
        if (metaOpt.isEmpty()) {
            String msg = "Engine '" + repo.engine() + "' is not registered in the driver registry";
            log.warn(msg);
            return ConnectionTestResult.fail("Unsupported database engine", msg);
        }

        JdbcDriverRegistry.DriverMetadata meta = metaOpt.get();
        String url = meta.urlTemplate()
                .replace("{host}", repo.host())
                .replace("{port}", String.valueOf(repo.port()))
                .replace("{database}", repo.database());

        try {
            Driver driver = driverDownloader.loadDriver(repo.engine());

            Properties props = new Properties();
            if (repo.username() != null) props.setProperty("user", repo.username());
            if (repo.password() != null) props.setProperty("password", repo.password());

            log.debug("Establishing connection using JDBC URL: {}", url);
            try (Connection conn = driver.connect(url, props)) {
                if (conn != null) {
                    DatabaseMetaData dbMeta = conn.getMetaData();
                    String versionInfo = dbMeta.getDatabaseProductName() + " " + dbMeta.getDatabaseProductVersion();
                    log.info("Successfully connected to {} database — server: {}", repo.engine(), versionInfo);
                    return ConnectionTestResult.ok("Connected to " + repo.engine() + " at " + repo.host() + ":" + repo.port() + ". Server: " + versionInfo);
                }
            }
        } catch (java.sql.SQLException e) {
            // SQL-level errors: authentication, bad DB name, SSL mismatch, etc.
            String sqlState = e.getSQLState() != null ? " [SQLState=" + e.getSQLState() + "]" : "";
            String detail = e.getMessage() + sqlState;
            log.error("JDBC connection test failed — {}", detail);
            return ConnectionTestResult.fail("Database connection refused", detail);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            // Network-level errors: host unreachable, TCP timeout, etc.
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Failed to test connection to relational database", e);
            return ConnectionTestResult.fail("Transport error — could not reach host", detail);
        }

        return ConnectionTestResult.fail("Driver returned null connection",
                "JDBC driver connected but returned a null Connection object for URL: " + url);
    }

    /**
     * Confirm the deployment declares this tap and accepts these settings (T6).
     *
     * <p>Re-checked on every write and every probe rather than once at registration: the catalogue
     * is configuration, so an operator can narrow it after a repository was registered, and the
     * row must not outlive the permission that created it. The catalogue's own refusal message is
     * used unchanged — it deliberately never says what it <em>would</em> allow.
     */
    private void requireAllowedTap(SingerTapRepository repo) {
        taps.validateConfig(repo.tapId(), TapConfig.toMap(repo.configJson()));
    }

    /**
     * The submitted settings, with each masked value replaced by the one already stored.
     *
     * <p>Only values are carried over, never keys: a setting the user removed from the form stays
     * removed, or a tap's settings could never be cleared.
     */
    private static java.util.Map<String, String> mergeTapConfig(
            java.util.Map<String, String> submitted, java.util.Map<String, String> stored) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        submitted.forEach((key, value) -> merged.put(key,
                MASKED.equals(value) ? stored.getOrDefault(key, value) : value));
        return merged;
    }

    /**
     * F-CRED-5 for taps: a kept (masked) credential may not travel with an edited setting.
     *
     * <p>The other credentialled kinds each name their target explicitly — a host, an endpoint, a
     * catalog URI — and refuse a sentinel when it moves. A tap has no such field: its target and its
     * credential are two entries of the <em>same</em> settings map, and a typical tap
     * ({@code tap-rest-api}, {@code tap-exchangeratesapi}) exposes an {@code api_url} among them.
     * Without this, a member who can edit the repository but has only ever seen {@code ********}
     * could repoint the tap and let the next discovery post the stored credential to an address
     * they chose.
     *
     * <p>Which entry <em>is</em> the target is not knowable here — only the operator's catalogue
     * could say, and it declares no target keys — so the guard is the conservative one: if any
     * secret is being kept, the non-secret settings must be identical to what is stored. Rotating
     * a secret is unaffected, and so is the ordinary edit-form round trip, which resubmits the
     * settings unchanged. The price of an edit alongside a masked secret is re-entering it, which
     * is the rule the sibling kinds already state in {@link #requireUnchangedCredentialTarget}.
     */
    private void requireUnchangedTapTargetWhenSecretKept(String tapId,
                                                         java.util.Map<String, String> submitted,
                                                         java.util.Map<String, String> stored) {
        java.util.Set<String> secretKeys = taps.find(tapId)
                .map(SingerTapCatalog.Tap::secretKeys)
                .orElse(java.util.Set.of());
        boolean keepsAStoredSecret = secretKeys.stream()
                .anyMatch(key -> MASKED.equals(submitted.get(key)) && stored.containsKey(key));
        if (!keepsAStoredSecret) {
            return;
        }
        requireUnchangedCredentialTarget(
                withoutKeys(submitted, secretKeys).equals(withoutKeys(stored, secretKeys)));
    }

    /** The settings that are not credentials, compared as a set of entries rather than in order. */
    private static java.util.Map<String, String> withoutKeys(java.util.Map<String, String> config,
                                                             java.util.Set<String> excluded) {
        java.util.Map<String, String> kept = new java.util.HashMap<>(config);
        kept.keySet().removeAll(excluded);
        return kept;
    }

    /**
     * Probe a tap by running its discovery (T6).
     *
     * <p>A real probe, not a reachability guess: the executable exists, launches, reads the config
     * Aran wrote, and answers with a stream list. That is everything a later import depends on
     * except the volume of data, which is the most any test can prove about a tap.
     *
     * <p>Nothing is screened with {@link #screenOutboundTarget} — see the {@code SINGER_TAP} case
     * there for why the trust decision is the operator's catalogue rather than an address check.
     */
    private ConnectionTestResult testSingerTapConnection(SingerTapRepository repo) {
        try {
            requireAllowedTap(repo);
        } catch (SingerTapCatalog.TapNotAllowedException | IllegalArgumentException e) {
            return ConnectionTestResult.fail("Tap settings are not accepted", e.getMessage());
        }
        if (tapExtractor == null) {
            return ConnectionTestResult.fail("Singer taps cannot run in this build",
                    "The bootstrapping operator, which runs a tap, is not on this server's classpath.");
        }
        log.info("Testing Singer tap {}", repo.tapId());
        try {
            List<String> streams = tapExtractor.discoverStreams(repo);
            return ConnectionTestResult.ok(
                    "Tap ran and offered " + streams.size() + " stream(s): " + streams);
        } catch (SingerTapCatalog.TapNotAllowedException e) {
            return ConnectionTestResult.fail("Tap is not allowed here", e.getMessage());
        } catch (RuntimeException e) {
            // The tap's own diagnostics, not a rewritten summary — "exited with status 1" is what
            // tells an operator which half of the setup is wrong. Values never appear in it: the
            // config travels by file, and the runner never echoes the file back.
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Singer tap {} failed its connection test — {}", repo.tapId(), detail);
            return ConnectionTestResult.fail("The tap did not complete discovery", detail);
        }
    }

    /**
     * Probe an Iceberg REST catalog with the protocol's own hello: {@code GET /v1/config} (Gap 2).
     *
     * <p>A real probe of the control plane — reachability, TLS, and whether the token is accepted —
     * which is everything a later scan or introspection depends on. Deliberately <em>not</em> a
     * data-plane probe: the object store the metadata points at is only dialed when a table is
     * actually read, and a catalog whose config answers is a catalog worth registering even while
     * its store credentials are still being provisioned.
     */
    private ConnectionTestResult testIcebergConnection(IcebergRestRepository repo) {
        if (icebergCatalog == null) {
            return ConnectionTestResult.fail("Iceberg catalogs cannot be reached in this build",
                    "The Iceberg adapter, which speaks the REST catalog protocol, is not on this "
                            + "server's classpath.");
        }
        // A masked token never reaches this probe: testConnection refuses the sentinel for every
        // kind, and the stored-repository test resolves it first.
        log.info("Testing Iceberg REST catalog at {}", repo.catalogUri());
        try {
            icebergCatalog.config(repo);
            return ConnectionTestResult.ok("Catalog answered GET /v1/config at " + repo.catalogUri());
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Iceberg catalog probe failed for {} — {}", repo.catalogUri(), detail);
            return ConnectionTestResult.fail("Could not reach the Iceberg catalog", detail);
        }
    }

    /**
     * Probes an object-storage service with an HTTP GET to the configured endpoint.
     *
     * <p>Any HTTP response (including 403/404 — which indicate the service is up but the
     * credentials or bucket are wrong) is treated as reachable. Only transport-level
     * failures (timeout, DNS error, TLS handshake failure) return {@code false}.
     *
     * <p>Provider-specific endpoint defaults:
     * <ul>
     *   <li>S3 / AWS  → {@code https://s3.amazonaws.com}</li>
     *   <li>GCS       → {@code https://storage.googleapis.com}</li>
     *   <li>Azure     → {@code https://blob.core.windows.net}</li>
     *   <li>MinIO / other → uses the configured {@code endpoint} field</li>
     * </ul>
     */
    private ConnectionTestResult testObjectStorageConnection(ObjectStorageRepository repo) {
        String endpoint = repo.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = switch (repo.provider().toUpperCase()) {
                case "S3", "AWS"   -> "https://s3.amazonaws.com";
                case "GCS"         -> "https://storage.googleapis.com";
                case "AZURE"       -> "https://blob.core.windows.net";
                default            -> null;
            };
        }
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("ObjectStorage provider '{}' has no resolvable endpoint — cannot probe", repo.provider());
            return ConnectionTestResult.fail("No endpoint configured",
                    "Provider '" + repo.provider() + "' requires an explicit endpoint URL (no default is known).");
        }

        final String resolvedEndpoint = endpoint;
        log.info("Testing Object Storage connection to endpoint: {}", resolvedEndpoint);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolvedEndpoint))
                    .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                    .GET()
                    .build();
            HttpResponse<Void> response = buildHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            log.info("Object Storage probe returned HTTP {} for endpoint {}", status, resolvedEndpoint);
            return ConnectionTestResult.ok("Endpoint reachable — HTTP " + status + " from " + resolvedEndpoint);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Failed to reach Object Storage endpoint {} — {}", resolvedEndpoint, e.getMessage());
            return ConnectionTestResult.fail("Could not reach storage endpoint", detail);
        }
    }

    /** Connect timeout (ms) for HTTP probes. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Total timeout (ms) for HTTP probes. */
    private static final int HTTP_TIMEOUT_MS = 10_000;

    /** Shared factory for HTTP probes. A new client per probe keeps state simple. */
    private HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(java.net.ProxySelector.getDefault())
                .build();
    }

    /**
     * Builds the libpq-style {@code postgresql://user:pass@host:port/db} connection URI
     * the lake's asyncpg import path speaks.
     *
     * <p>{@code testConnection} builds a JDBC URL from {@code JdbcDriverRegistry}'s template
     * plus a {@code Properties} object, which asyncpg cannot consume — so this is the one
     * other place that reads the repository's stored credentials. User and password are
     * URI-encoded so special characters survive the round-trip.
     */
    public String buildPostgresConnectionUri(RelationalDatabaseRepository repo) {
        String user = uriEncode(repo.username() == null ? "" : repo.username());
        String pass = uriEncode(repo.password() == null ? "" : repo.password());
        String userInfo = pass.isEmpty() ? user : user + ":" + pass;
        String db = uriEncode(repo.database());
        return "postgresql://" + (userInfo.isEmpty() ? "" : userInfo + "@")
                + repo.host() + ":" + repo.port() + "/" + db;
    }

    /** Percent-encode a URI component ({@code URLEncoder} form-encodes spaces as {@code +}). */
    private static String uriEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Scans and lists available dataset candidates (tables, views, streams) from a repository.
     *
     * <p>A scan that cannot reach the source fails — it does not answer with invented
     * table names. Repository types Aran cannot scan answer with an empty list, which is
     * the truth: nothing is known about them.
     *
     * <p>Screened like {@link #testConnection}: a scan connects to the same caller-chosen host,
     * so screening only the test left the oracle intact for anyone who skipped it (T3.3, audit
     * SOURCE_REGISTRATION-10). The refusal is thrown rather than returned as an empty list —
     * "nothing here" and "not allowed to look" must not read the same.
     *
     * <p>The screen sits in the branch that connects, not at the top of the method: the JDBC
     * branch is the only one that opens a socket here, and screening a kind this method never
     * dials would answer "host does not resolve" where the truth is "Aran cannot scan this kind".
     */
    public List<String> listAvailableDatasets(AuthenticatedUser caller, UUID projectId, UUID repositoryId) {
        DataRepository repo = getRepository(caller, projectId, repositoryId);
        try {
            if (repo instanceof RelationalDatabaseRepository jdbcRepo) {
                screenOutboundTarget(jdbcRepo);
                return listJdbcDatasets(jdbcRepo);
            } else if (repo instanceof SingerTapRepository tapRepo) {
                // T6: a tap has no catalogue to read, so "what can I import from here" is a
                // discovery RUN of the same executable. Same contract as the relational branch —
                // the list is what dataset registration then checks membership against, so it must
                // come from the source rather than from anything a caller sent.
                return listSingerTapStreams(tapRepo);
            } else if (repo instanceof IcebergRestRepository icebergRepo) {
                screenOutboundTarget(icebergRepo);
                return listIcebergTables(icebergRepo);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to scan datasets for repository {}", repositoryId, e);
            throw new PreviewFailedException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        // A file-system repository holds only what was uploaded to it (D1 — Aran lists no server
        // directory), and object storage has no listing yet: nothing to offer, and saying so
        // beats making something up.
        return List.of();
    }

    private List<String> listJdbcDatasets(RelationalDatabaseRepository repo) throws Exception {
        Optional<JdbcDriverRegistry.DriverMetadata> metaOpt = JdbcDriverRegistry.get(repo.engine());
        if (metaOpt.isEmpty()) {
            throw new IllegalArgumentException("Unsupported engine: " + repo.engine());
        }

        JdbcDriverRegistry.DriverMetadata meta = metaOpt.get();
        String url = meta.urlTemplate()
                .replace("{host}", repo.host())
                .replace("{port}", String.valueOf(repo.port()))
                .replace("{database}", repo.database());

        Driver driver = driverDownloader.loadDriver(repo.engine());
        Properties props = new Properties();
        if (repo.username() != null) props.setProperty("user", repo.username());
        if (repo.password() != null) props.setProperty("password", repo.password());

        try (Connection conn = driver.connect(url, props)) {
            return conn == null ? List.of() : listTables(conn, repo);
        }
    }

    /**
     * The relations this repository exposes, over an already-open connection.
     *
     * <p>Shared with {@link #previewJdbcDataset} rather than copied into it: the list is what
     * decides both what a user may pick and what a preview will accept, and two copies of the
     * {@code getTables} call could disagree about that — which is exactly how S2-7's second half
     * arose, a preview refusing names the scan had offered.
     */
    private static List<String> listTables(Connection conn, RelationalDatabaseRepository repo)
            throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData dbMeta = conn.getMetaData();
        try (ResultSet rs = dbMeta.getTables(
                null, schemaPatternOf(repo), "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (tableName != null) {
                    tables.add(tableName);
                }
            }
        }
        return tables;
    }

    /** A blank schema means "wherever the connection looks", which JDBC spells as a null pattern. */
    private static @Nullable String schemaPatternOf(RelationalDatabaseRepository repo) {
        return repo.schema() == null || repo.schema().isBlank() ? null : repo.schema();
    }

    /** The streams a tap offers, after re-checking the deployment still declares it (T6). */
    private List<String> listSingerTapStreams(SingerTapRepository repo) {
        requireAllowedTap(repo);
        if (tapExtractor == null) {
            throw new PreviewFailedException(
                    "Singer taps cannot run in this build: the bootstrapping operator is not on "
                    + "this server's classpath.");
        }
        return tapExtractor.discoverStreams(repo);
    }

    /**
     * Every {@code namespace.table} the catalog offers (Gap 2). Same contract as the relational and
     * tap branches: this list is what dataset registration checks membership against, so it comes
     * from the catalog itself, never from anything a caller sent.
     */
    private List<String> listIcebergTables(IcebergRestRepository repo) {
        if (icebergCatalog == null) {
            throw new PreviewFailedException(
                    "Iceberg catalogs cannot be reached in this build: the Iceberg adapter is not "
                    + "on this server's classpath.");
        }
        return icebergCatalog.listTables(repo);
    }

    /**
     * Executes a safe limited query to generate a dataset preview.
     *
     * <p>Fails with {@link PreviewFailedException} when the source cannot be read, so the
     * caller sees the driver's own message. A preview never invents rows.
     *
     * <p>Screened before the connect, like the scan above and for the same reason (T3.3): the
     * driver's own message is exactly what makes an unscreened preview a usable port oracle.
     * The refusal is rethrown untouched rather than wrapped as a failed preview, so it cannot be
     * mistaken for the source being down.
     */
    public DatasetPreview previewDataset(AuthenticatedUser caller, UUID projectId, UUID repositoryId, String datasetName) {
        DataRepository repo = getRepository(caller, projectId, repositoryId);
        try {
            if (repo instanceof RelationalDatabaseRepository jdbcRepo) {
                screenOutboundTarget(jdbcRepo);
                return previewJdbcDataset(jdbcRepo, datasetName);
            }
        } catch (IllegalArgumentException | AccessDeniedException
                 | OutboundAddressGuard.BlockedHostException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Failed to preview dataset {} in repository {}", datasetName, repositoryId, e);
            throw new PreviewFailedException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to preview dataset {} in repository {}", datasetName, repositoryId, e);
            throw new PreviewFailedException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        throw new PreviewFailedException(
                "Preview is not available for repository type " + repo.getClass().getSimpleName()
                        + ". Only relational databases can be previewed by name; upload a file to "
                        + "ingest data from other sources.");
    }

    /**
     * The first five rows of one relation this repository exposes.
     *
     * <p>Two things about the relation name, both S2-7. It is checked for <b>membership of the
     * scanned list</b>, not against a name pattern: the pattern this replaces
     * ({@code ^[A-Za-z_][A-Za-z0-9_]{0,63}$}) was simultaneously too strict — a table the scan
     * offered under a name with a space or a dot could not be previewed — and no safer, since the
     * membership check is the one that also refuses a legally-named relation the repository does
     * not expose. And it is <b>qualified by the engine</b>
     * ({@link RelationalEngine#qualify}): with the repository's schema on the engines where the
     * scanned list is schema-scoped, so the chosen name is only unique within it; bare on MySQL
     * and MariaDB, whose drivers ignore the {@code schemaPattern} and whose database, the
     * connection's default, is the schema.
     */
    private DatasetPreview previewJdbcDataset(RelationalDatabaseRepository repo, String datasetName) throws Exception {
        Optional<JdbcDriverRegistry.DriverMetadata> metaOpt = JdbcDriverRegistry.get(repo.engine());
        if (metaOpt.isEmpty()) {
            throw new IllegalArgumentException("Unsupported engine: " + repo.engine());
        }
        // Both registries list the same six engines, so the driver check above has already refused
        // anything this lookup could miss.
        RelationalEngine engine = RelationalEngines.defaults()
                .find(repo.engine())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported engine: " + repo.engine()));

        JdbcDriverRegistry.DriverMetadata meta = metaOpt.get();
        String url = meta.urlTemplate()
                .replace("{host}", repo.host())
                .replace("{port}", String.valueOf(repo.port()))
                .replace("{database}", repo.database());

        Driver driver = driverDownloader.loadDriver(repo.engine());
        Properties props = new Properties();
        if (repo.username() != null) props.setProperty("user", repo.username());
        if (repo.password() != null) props.setProperty("password", repo.password());

        try (Connection conn = driver.connect(url, props)) {
            if (conn == null) {
                throw new PreviewFailedException(
                        "the " + repo.engine() + " driver did not accept " + url);
            }
            // On the connection the preview will read from, so the check and the read cannot see
            // different catalogs. Free-form input never reaches the SQL.
            if (datasetName == null || !listTables(conn, repo).contains(datasetName)) {
                throw new IllegalArgumentException("Invalid dataset name: " + datasetName);
            }
            String qualifiedTable = engine.qualify(repo.schema(), datasetName);

            try (Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(5);
                try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + qualifiedTable)) {
                    ResultSetMetaData dbMeta = rs.getMetaData();
                    int cols = dbMeta.getColumnCount();
                    List<String> headers = new ArrayList<>();
                    for (int i = 1; i <= cols; i++) {
                        headers.add(dbMeta.getColumnName(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= cols; i++) {
                            Object val = rs.getObject(i);
                            row.add(val == null ? "NULL" : val.toString());
                        }
                        rows.add(row);
                    }
                    return new DatasetPreview(headers, rows);
                }
            }
        }
    }
}

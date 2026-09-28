package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.domain.DatasetRegistration;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.IcebergTableExtractor;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.ObjectStorageExtractor;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.domain.RelationalCatalogHarvestTrigger;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.SingerTapExtractor;
import edu.upc.dtak.aran.core.domain.SingerTapRepository;
import edu.upc.dtak.aran.core.domain.TableExtractor;
import edu.upc.dtak.aran.core.domain.WrapperSqlValidator;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.source.AccessMode;
import edu.upc.dtak.aran.core.source.RelationalEngine;
import edu.upc.dtak.aran.core.source.RelationalEngines;
import edu.upc.dtak.aran.core.source.SourceAccess;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.Names;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for managing dataset definitions.
 */
@Service
@Transactional
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final DatasetRepository datasetRepository;
    private final DataRepositoryService repositoryService;
    private final LakeClient lakeClient;
    private final OperatorOrchestrator orchestrator;
    private final JobService jobService;
    private final UsageService usage;
    private final LicenseService licenses;

    /**
     * The generic JDBC extract, or null in a build without the bootstrapping operator on the
     * classpath. Null is reachable and handled rather than assumed away: the operator modules are
     * {@code runtimeOnly} in the application, so the core must not require one to construct.
     */
    private final @Nullable TableExtractor tableExtractor;

    /**
     * The object-storage copy-to-parquet extractor, or null in a build without the bootstrapping
     * operator on the classpath — same {@code runtimeOnly} reasoning as {@link #tableExtractor}, and
     * handled the same way: a MATERIALIZED object-storage import refuses cleanly when it is absent
     * rather than NPEing.
     */
    private final @Nullable ObjectStorageExtractor objectStorageExtractor;

    /**
     * Refresh policies, absent in a build assembled without the persistence adapter (the core's own
     * test slice). Import still works there; the dataset simply records no upstream.
     */
    private final Optional<DatasetRefreshRepository> datasetRefreshRepository;

    /**
     * Best-effort relational catalog harvest (Phase 4B Task 5), absent in a build assembled
     * without the bootstrapping operator — same {@code runtimeOnly} reasoning as {@link
     * #tableExtractor}. Triggered at relational-dataset enrolment ({@link #registerDataset},
     * {@link #registerDatasetFromRepositoryTable}) and on every refresh ({@link #refreshDataset},
     * reused by both the scheduled cadence and a manual refresh-now).
     */
    private final Optional<RelationalCatalogHarvestTrigger> harvestTrigger;

    /**
     * T6: the Singer tap extract, or null in a build without the bootstrapping operator on the
     * classpath — same {@code runtimeOnly} reasoning as {@link #tableExtractor} and the same
     * handling: a tap import refuses cleanly when it is absent rather than NPEing.
     */
    private final @Nullable SingerTapExtractor singerTapExtractor;

    /**
     * Gap 2: the Iceberg copy-to-parquet extract, or null in a build without the bootstrapping
     * operator on the classpath — same {@code runtimeOnly} reasoning and handling as
     * {@link #tableExtractor}.
     */
    private final @Nullable IcebergTableExtractor icebergExtractor;

    /**
     * The model side of a dataset's lifecycle. Three callers, one collaborator:
     *
     * <ul>
     *   <li>T1.8 — dataset deletion's model-side cleanup ({@link #deleteDataset});</li>
     *   <li>T2 — the refresh tail downgrades EXPANDED wrapper verdicts the new bytes invalidate;</li>
     *   <li>V47 — after every successful (re)bootstrap, unedited stored wrappers are re-seeded from
     *       the regenerated source graph, so a refreshed schema is not silently overwritten at the
     *       next compile by the enrolment-time copy.</li>
     * </ul>
     *
     * <p>Mandatory, not {@code Optional} like the extractors above: a dataset whose deletion
     * cannot clean up its enrolment is exactly the defect T1.8 exists to stop, so a build that
     * assembles this service without the model stack must fail at wiring rather than silently skip
     * the cleanup.
     */
    private final IntegrationModelService integrationModels;

    /**
     * What every materialize mechanism produces. The lake's native import writes parquet and so
     * does the JDBC extract, which is why the filename hint (F14) can be a constant rather than
     * something each branch decides.
     */
    private static final String PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet";

    /**
     * Ceiling on one generic JDBC extract, guarding backend memory and disk rather than the
     * tenant's quota — {@code registerDatasetWithFile} still applies the licence's storage limit
     * to the bytes this produces. The lake has {@code materialize_max_bytes} for the same job on
     * its side; this is the Java path's equivalent and should become configuration once anyone
     * needs a different number.
     */
    private static final long MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * The bootstrapping operator's withheld-verdict keys, read out of {@code OperatorResult.data()}.
     *
     * <p>Duplicated as literals rather than imported from {@code BootstrappingOperator.APPLIED_KEY}
     * because the operator modules depend on the core, not the other way round. Two readers is one
     * too many to leave as bare strings: the refresh path's compensation (REFRESH_DRIFT-1) hinges on
     * seeing the same "false" the bootstrap path does, and a typo in either would restore nothing
     * while looking correct.
     */
    private static final String APPLIED_KEY = "applied";
    private static final String DRIFT_SUMMARY_KEY = "driftSummary";

    /**
     * Ceiling on one lake-native import, and how often it is asked whether it is done. Generous by
     * default: a legitimate import of a large table is legitimately slow, and the deadline is here
     * to stop an unbounded wait, not to cap useful work.
     */
    private static final Duration DEFAULT_LAKE_IMPORT_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_LAKE_POLL_INTERVAL = Duration.ofSeconds(2);

    /**
     * How many polls in a row may fail before the import does. {@code HttpLakeClient} throws on
     * <em>any</em> non-200 or IO error, so with no tolerance a single blip on the loopback call
     * failed a Java import whose lake job kept running, registered a snapshot, and left it attached
     * to nothing (S16-3). Consecutive, not cumulative: a lake that answers once has proved it is
     * still there, so the count resets.
     */
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 5;

    private final Duration lakeImportTimeout;
    private final Duration lakePollInterval;

    /** Test-friendly constructor without the Iceberg extractor — the pre-Gap-2 shape. */
    public DatasetService(DatasetRepository datasetRepository,
                          DataRepositoryService repositoryService,
                          LakeClient lakeClient,
                          OperatorOrchestrator orchestrator,
                          JobService jobService,
                          UsageService usage,
                          LicenseService licenses,
                          Optional<TableExtractor> tableExtractor,
                          Optional<ObjectStorageExtractor> objectStorageExtractor,
                          Optional<DatasetRefreshRepository> datasetRefreshRepository,
                          Optional<RelationalCatalogHarvestTrigger> harvestTrigger,
                          IntegrationModelService integrationModels,
                          Optional<SingerTapExtractor> singerTapExtractor) {
        this(datasetRepository, repositoryService, lakeClient, orchestrator, jobService, usage,
                licenses, tableExtractor, objectStorageExtractor, datasetRefreshRepository,
                harvestTrigger, integrationModels, singerTapExtractor, Optional.empty());
    }

    /** Test-friendly constructor at the production poll cadence. */
    public DatasetService(DatasetRepository datasetRepository,
                          DataRepositoryService repositoryService,
                          LakeClient lakeClient,
                          OperatorOrchestrator orchestrator,
                          JobService jobService,
                          UsageService usage,
                          LicenseService licenses,
                          Optional<TableExtractor> tableExtractor,
                          Optional<ObjectStorageExtractor> objectStorageExtractor,
                          Optional<DatasetRefreshRepository> datasetRefreshRepository,
                          Optional<RelationalCatalogHarvestTrigger> harvestTrigger,
                          IntegrationModelService integrationModels,
                          Optional<SingerTapExtractor> singerTapExtractor,
                          Optional<IcebergTableExtractor> icebergExtractor) {
        this(datasetRepository, repositoryService, lakeClient, orchestrator, jobService, usage,
                licenses, tableExtractor, objectStorageExtractor, datasetRefreshRepository,
                harvestTrigger, integrationModels, singerTapExtractor, icebergExtractor,
                DEFAULT_LAKE_IMPORT_TIMEOUT, DEFAULT_LAKE_POLL_INTERVAL);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DatasetService(DatasetRepository datasetRepository,
                          DataRepositoryService repositoryService,
                          LakeClient lakeClient,
                          OperatorOrchestrator orchestrator,
                          JobService jobService,
                          UsageService usage,
                          LicenseService licenses,
                          Optional<TableExtractor> tableExtractor,
                          Optional<ObjectStorageExtractor> objectStorageExtractor,
                          Optional<DatasetRefreshRepository> datasetRefreshRepository,
                          Optional<RelationalCatalogHarvestTrigger> harvestTrigger,
                          IntegrationModelService integrationModels,
                          Optional<SingerTapExtractor> singerTapExtractor,
                          Optional<IcebergTableExtractor> icebergExtractor,
                          @Value("${aran.lake.import-timeout:30m}") Duration lakeImportTimeout,
                          @Value("${aran.lake.import-poll-interval:2s}") Duration lakePollInterval) {
        this.lakeImportTimeout = lakeImportTimeout;
        this.lakePollInterval = lakePollInterval;
        this.singerTapExtractor = singerTapExtractor.orElse(null);
        this.icebergExtractor = icebergExtractor.orElse(null);
        this.datasetRepository = datasetRepository;
        this.repositoryService = repositoryService;
        this.lakeClient = lakeClient;
        this.orchestrator = orchestrator;
        this.jobService = jobService;
        this.usage = usage;
        this.licenses = licenses;
        this.tableExtractor = tableExtractor.orElse(null);
        this.objectStorageExtractor = objectStorageExtractor.orElse(null);
        this.datasetRefreshRepository = datasetRefreshRepository;
        this.harvestTrigger = harvestTrigger;
        this.integrationModels = integrationModels;
    }

    /**
     * List all datasets defined within a given repository, after verifying user access.
     */
    @Transactional(readOnly = true)
    public List<Dataset> listDatasets(AuthenticatedUser caller, UUID projectId, UUID repositoryId) {
        repositoryService.getRepository(caller, projectId, repositoryId);
        return datasetRepository.findAllByRepository(new TenantContext(caller.tenantId().toString()), repositoryId);
    }

    /**
     * The column profiles stored for one dataset, every generation (subject) at once (J3-F1).
     *
     * <p>Bootstrap persists a SNAPSHOT generation, a compile persists WRAPPER_OUTPUT (and
     * FEDERATED_LIVE for a live class), and a relational harvest persists HARVESTED rows — none of
     * which the Profile tab could show, so it claimed there was no profile and invited a re-run.
     * Reads the registry only: nothing is profiled, and no lake or source is touched.
     *
     * <p>The dataset must belong to the repository named in the path. {@code findById} is scoped
     * to the tenant, not the repository, and a by-id read that ignores the path would let a
     * member of one project read another project's evidence by guessing a dataset id.
     */
    @Transactional(readOnly = true)
    public List<ColumnProfile> listColumnProfiles(AuthenticatedUser caller, UUID projectId,
                                                  UUID repositoryId, UUID datasetId) {
        repositoryService.getRepository(caller, projectId, repositoryId);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        return datasetRepository.findById(tenant, datasetId)
                .filter(d -> d.repositoryId().equals(repositoryId))
                .map(Dataset::columns)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset not found: " + datasetId));
    }

    /**
     * Register a new dataset within a given repository, after verifying user access.
     *
     * <p>This is the path a federated relational table takes — no file, no snapshot, just a name
     * naming a table the repository already exposes live — so it is also relational-dataset
     * <em>enrolment</em> in the Task 5 sense: if the repository is relational, a best-effort
     * catalog harvest runs inline, right after the row lands (so it is already visible to {@code
     * HarvestPersistenceService}'s {@code findAllByRepository} scan). A harvest failure is logged
     * and swallowed — it must never turn a successful registration into a failed one.
     */
    public Dataset registerDataset(AuthenticatedUser caller, UUID projectId, UUID repositoryId, Dataset dataset) {
        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);

        Dataset saved = datasetRepository.save(new TenantContext(caller.tenantId().toString()), dataset);
        harvestIfRelational(caller.tenantId(), repository);
        return saved;
    }

    /**
     * {@link #registerDataset}, then — for a dataset its federated relational repository reads
     * live — the bootstrap, as a job, exactly as the upload path submits one (S4-19).
     *
     * <p>This is what {@code POST …/datasets} without a file calls. A live table has nothing to
     * upload, so registration used to be the whole request: the row came back with no status, no
     * job ran, and the dataset sat unbootstrapped until someone found a way to start one. It is
     * persisted {@code PENDING} now and the job id comes back with it. Every other repository
     * kind keeps the plain registration: there is nothing a no-file bootstrap could read.
     */
    public DatasetRegistration registerDatasetForLiveRead(AuthenticatedUser caller, UUID projectId,
                                                          UUID repositoryId, Dataset dataset) {
        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);
        boolean live = repository instanceof RelationalDatabaseRepository
                && SourceAccess.resolve(repository, dataset.name(), false) == AccessMode.FEDERATED;
        if (!live) {
            return new DatasetRegistration(registerDataset(caller, projectId, repositoryId, dataset), null);
        }
        Dataset saved = registerDataset(caller, projectId, repositoryId, new Dataset(
                dataset.id(), dataset.repositoryId(), dataset.name(), dataset.description(),
                dataset.snapshotId(), dataset.columns(), dataset.graphJson(), DatasetStatus.PENDING));
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        UUID datasetId = saved.id();
        Job job = jobService.submit(
                tenant,
                projectId,
                "dataset:bootstrap",
                Map.of("datasetId", datasetId.toString()),
                caller.userId(),
                progress -> runBootstrapPipeline(tenant, caller.userId(), datasetId, null, progress));
        return new DatasetRegistration(datasetRepository.findById(tenant, datasetId).orElse(saved), job.id());
    }

    /**
     * Register a new dataset with an uploaded file, sending it directly to the S3 lake.
     *
     * <p>The upload happens in the request; the bootstrap does not. Extracting a
     * schema means downloading the snapshot again, parsing it, and writing a graph
     * — minutes on a large file, and a request that holds a connection open for
     * all of it tells the user nothing while it does. So the dataset is persisted
     * {@code PENDING} and the bootstrap is submitted as a job whose id comes back
     * with it: the UI follows the real stages instead of guessing.
     *
     * <p>{@code BootstrappingOperator} writes {@code READY} itself on success. The
     * status writes here are the safety net for the paths it cannot reach — an
     * operator failure it reports rather than throws, or an exception before its
     * own write — so the row never sits at {@code PENDING} forever.
     */
    public DatasetRegistration registerDatasetWithFile(AuthenticatedUser caller, UUID projectId, UUID repositoryId, Dataset dataset, byte[] fileBytes, String contentType) {
        return registerDatasetWithFile(caller, projectId, repositoryId, dataset, fileBytes, contentType, null);
    }

    /**
     * As the five-argument overload, but also carries the originally uploaded file's
     * name through to bootstrap-time format detection (F14). The dataset's own display name
     * ({@code dataset.name()}, e.g. "venues") routinely has no extension, so it is
     * useless as a format hint; the real filename ("venues.parquet") is only ever
     * known here, at upload time, and is otherwise discarded. May be null/blank —
     * {@code BootstrappingOperator} falls back to the dataset name when absent.
     */
    public DatasetRegistration registerDatasetWithFile(AuthenticatedUser caller, UUID projectId, UUID repositoryId, Dataset dataset, byte[] fileBytes, String contentType, String originalFilename) {
        // Visibility and ownership: refused here, before a single byte reaches the lake.
        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);
        // So is a name the repository already holds (J2-F21). The row is saved only after the
        // upload, so without this the unique constraint refused it after every byte had been
        // sent — minutes of upload ending in a generic 500. A concurrent registration can still
        // win the race; the constraint stays the backstop, and it too answers 409.
        requireNameFree(new TenantContext(caller.tenantId().toString()), repositoryId, repository,
                dataset.name());

        long started = System.currentTimeMillis();
        long sizeBytes = fileBytes.length;
        // Before the upload, and counting what it is about to add: a ceiling you only discover
        // after crossing it is not a ceiling. Unset on every OSS install by default.
        licenses.requireWithinStorageLimit(caller, sizeBytes);
        LakeClient.InitUploadResponse initResponse = lakeClient.initUpload(caller.tenantId(), dataset.id(), sizeBytes, contentType);
        lakeClient.uploadToS3(initResponse.uploadUrl(), fileBytes, contentType);
        String hash = lakeClient.computeSha256(fileBytes);
        String snapshotId = lakeClient.completeUpload(caller.tenantId(), initResponse.uploadId(), hash, sizeBytes);

        Dataset datasetWithSnapshot = new Dataset(dataset.id(), dataset.repositoryId(), dataset.name(), dataset.description(), snapshotId, dataset.columns(), dataset.graphJson(), DatasetStatus.PENDING);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        Dataset saved = datasetRepository.save(tenant, datasetWithSnapshot);
        UUID datasetId = saved.id();

        // The bytes are the point of this event, so it is recorded once the upload has landed
        // and before the bootstrap job — the pipeline that follows is measured, if at all, by
        // whatever it does itself, not by the arrival of the file.
        usage.record(caller, UsageKind.UPLOAD, System.currentTimeMillis() - started, null, sizeBytes);

        Job job = jobService.submit(
                tenant,
                projectId,
                "dataset:bootstrap",
                Map.of("datasetId", datasetId.toString()),
                caller.userId(),
                progress -> runBootstrapPipeline(tenant, caller.userId(), datasetId, originalFilename, progress));

        return new DatasetRegistration(datasetRepository.findById(tenant, datasetId).orElse(saved), job.id());
    }

    /**
     * The bootstrap stage shared by the upload and import registration paths: run the
     * bootstrapping operator, write the READY/FAILED safety net, then trigger the
     * best-effort auto-profiling. Runs on a job worker thread.
     *
     * @param filenameHint the originally uploaded file's name, when known (F14) — null
     *                     for callers with no file of their own (e.g. table import).
     */
    private Map<String, String> runBootstrapPipeline(TenantContext tenant, UUID actor, UUID datasetId,
                                                     String filenameHint,
                                                     edu.upc.dtak.aran.core.operator.ProgressReporter progress) {
        Map<String, String> bootstrapParams = (filenameHint == null || filenameHint.isBlank())
                ? Map.of("datasetId", datasetId.toString())
                : Map.of("datasetId", datasetId.toString(), "filenameHint", filenameHint);
        OperatorRequest req = new OperatorRequest(
                tenant.value(),
                actor,
                "bootstrap",
                bootstrapParams);
        OperatorResult result;
        try {
            result = orchestrator.execute("bootstrapping", req, progress);
        } catch (RuntimeException e) {
            updateBootstrapStatus(tenant, datasetId, DatasetStatus.FAILED);
            throw e;
        }
        if (!result.success()) {
            log.warn("Auto-bootstrap failed for dataset {}: {}", datasetId, result.errorMessage());
            updateBootstrapStatus(tenant, datasetId, DatasetStatus.FAILED);
            throw new JobFailureException(
                    result.errorCode() != null ? result.errorCode() : "BOOTSTRAP_FAILED",
                    result.errorMessage() != null ? result.errorMessage() : "bootstrapping reported failure");
        }

        // The operator succeeded but withheld the new schema because applying it would break
        // mappings compiled into the active schema. Nothing failed and nothing needs retrying —
        // a human has to decide — so the job completes and the dataset says DRIFTED.
        //
        // Forcing READY here would be actively wrong twice over: it would claim the dataset is
        // serving a schema it is not, and it would send auto-profiling off to profile a snapshot
        // whose columns never made it into the graph, silently poisoning the CBO's statistics.
        if ("false".equals(result.data().get(APPLIED_KEY))) {
            log.info("Bootstrap of dataset {} withheld by schema drift: {}",
                    datasetId, result.data().get(DRIFT_SUMMARY_KEY));
            updateBootstrapStatus(tenant, datasetId, DatasetStatus.DRIFTED);
            progress.stage("blocked by schema drift");
            return result.data();
        }

        updateBootstrapStatus(tenant, datasetId, DatasetStatus.READY);

        // V47: the graph now carries this dataset's freshly generated per-class wrapper SQL. Any
        // enrolled source row whose stored wrapper nobody has edited is a copy of the PREVIOUS
        // generation of exactly that string, and the next compile would push it back over this
        // one (SchemaIntegrationOperator.applyWrapperOverrides) — which, after a refresh that
        // renamed or dropped a column, is what left the class answering nothing but a DuckDB
        // binder error. Re-seeding here, right where the new answer exists, is what keeps an
        // untouched wrapper following its source. Edited wrappers are never touched (the gate is
        // inside IntegrationModelRepository.reseedUneditedWrappers); a compile validates those
        // instead. A no-op on first import: nothing is enrolled yet.
        integrationModels.reseedUneditedWrappers(tenant, datasetId);

        // Phase 1b: Synchronous Stats Freshness — Trigger profiling immediately
        // after bootstrap completes so Calcite CBO is always in sync with the lake snapshot.
        try {
            Dataset ds = datasetRepository.findById(tenant, datasetId).orElse(null);
            if (ds != null && ds.snapshotId() != null) {
                OperatorRequest profileReq = new OperatorRequest(
                        tenant.value(),
                        actor,
                        "profile",
                        Map.of(
                            "snapshotId", ds.snapshotId().toString(),
                            "datasetId", datasetId.toString(),
                            "varcharOnly", "false"
                        ));
                progress.stage("triggering auto-profiling");
                orchestrator.execute("profiling", profileReq, progress);
            }
        } catch (Exception e) {
            log.warn("Auto-profiling failed for dataset {}, but bootstrap succeeded: {}", datasetId, e.getMessage());
        }

        // The job's last visible stage is "done" whatever became of the
        // best-effort profiling (freyja may be absent) — the job did succeed.
        progress.stage("done");
        return result.data();
    }

    /**
     * Register a dataset whose data is imported from a table in a relational repository: the table
     * is copied into the lake as parquet, then the regular bootstrap pipeline runs. Complementary
     * to live federation — federated repositories pass queries through at query time; the import
     * path copies.
     *
     * <p>The dataset row is persisted {@code PENDING} immediately; the copy and the bootstrap run
     * inside one aran job whose id comes back with the registration. <b>Nothing expensive happens
     * on the request thread</b> — an extract of a large table would otherwise look to the user
     * like a hung request rather than a running job.
     */
    public DatasetRegistration registerDatasetFromRepositoryTable(AuthenticatedUser caller, UUID projectId,
                                                                  UUID repositoryId, String tableName) {
        RelationalDatabaseRepository relational =
                requireImportableTable(caller, projectId, repositoryId, tableName);

        Dataset dataset = new Dataset(
                UUID.randomUUID(),
                repositoryId,
                tableName,
                // displayName, not the engine id: this is shown to a user, and "POSTGRESQL" is the
                // registry key rather than the name of the product.
                "Imported from " + RelationalEngines.defaults().find(relational.engine())
                        .map(RelationalEngine::displayName).orElse(relational.engine())
                        + " table " + tableName,
                null, List.of(), null, DatasetStatus.PENDING);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        Dataset saved = datasetRepository.save(tenant, dataset);
        UUID datasetId = saved.id();

        // The dataset now has an upstream, so it becomes refreshable. Manual by default: a
        // schedule nobody chose is a surprising thing to start running against someone else's
        // production database.
        datasetRefreshRepository.ifPresent(refreshes -> refreshes.save(tenant,
                DatasetRefresh.manual(datasetId, caller.tenantId(), repositoryId, projectId,
                        caller.userId(), tableName)));

        Job job = jobService.submit(
                tenant,
                projectId,
                "dataset:import",
                Map.of("datasetId", datasetId.toString(), "tableName", tableName),
                caller.userId(),
                progress -> {
                    String snapshotId;
                    try {
                        snapshotId = materializeTable(caller, datasetId, relational, tableName, progress);
                    } catch (RuntimeException e) {
                        updateBootstrapStatus(tenant, datasetId, DatasetStatus.FAILED);
                        throw e;
                    }
                    attachSnapshot(tenant, datasetId, snapshotId);
                    Map<String, String> result = runBootstrapPipeline(
                            tenant, caller.userId(), datasetId, filenameHintFor(tableName), progress);
                    // Relational-dataset enrolment (Task 5): the table is now a known Aran dataset,
                    // so its repository's catalog is worth harvesting. Inside the job, not on the
                    // request thread — this method's own contract above ("nothing expensive on the
                    // request thread") applies to a live JDBC catalog read exactly as it does to the
                    // extract itself.
                    harvestCatalog(caller.tenantId(), relational);
                    return result;
                });

        return new DatasetRegistration(datasetRepository.findById(tenant, datasetId).orElse(saved), job.id());
    }

    /**
     * Register a dataset from one stream of a Singer tap (T6): the tap runs, its records land in
     * the lake as parquet, then the regular bootstrap pipeline runs over that snapshot.
     *
     * <p>Structurally the same method as {@link #registerDatasetFromRepositoryTable} — persist
     * {@code PENDING}, record the upstream so the dataset is refreshable, do everything expensive
     * inside one job whose id comes back with the registration, and reuse the same
     * {@code initUpload}/{@code uploadToS3}/{@code completeUpload} tail. Only the acquisition
     * differs, which is the whole point of the {@link SingerTapExtractor} port: a tap is the one
     * source kind Aran reaches by running a program rather than by speaking a protocol.
     */
    public DatasetRegistration registerDatasetFromSingerStream(AuthenticatedUser caller,
            UUID projectId, UUID repositoryId, String stream) {
        SingerTapRepository tap = requireImportableStream(caller, projectId, repositoryId, stream);

        Dataset dataset = new Dataset(
                UUID.randomUUID(), repositoryId, stream,
                "Pulled by the " + tap.tapId() + " tap, stream " + stream,
                null, List.of(), null, DatasetStatus.PENDING);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        Dataset saved = datasetRepository.save(tenant, dataset);
        UUID datasetId = saved.id();

        // Manual by default, like a table import: a tap calls someone else's API, often against a
        // rate limit, and a schedule nobody chose is a surprising thing to start doing to it.
        datasetRefreshRepository.ifPresent(refreshes -> refreshes.save(tenant,
                DatasetRefresh.manual(datasetId, caller.tenantId(), repositoryId, projectId,
                        caller.userId(), stream)));

        Job job = jobService.submit(
                tenant,
                projectId,
                "dataset:import",
                Map.of("datasetId", datasetId.toString(), "stream", stream),
                caller.userId(),
                progress -> {
                    String snapshotId;
                    try {
                        snapshotId = materializeStream(caller, datasetId, tap, stream, progress);
                    } catch (RuntimeException e) {
                        updateBootstrapStatus(tenant, datasetId, DatasetStatus.FAILED);
                        throw e;
                    }
                    attachSnapshot(tenant, datasetId, snapshotId);
                    return runBootstrapPipeline(
                            tenant, caller.userId(), datasetId, filenameHintFor(stream), progress);
                });

        return new DatasetRegistration(datasetRepository.findById(tenant, datasetId).orElse(saved), job.id());
    }

    /**
     * Register a dataset copied from one table of an Iceberg REST catalog (Gap 2): the table's
     * current snapshot is read via DuckDB's {@code iceberg} extension, its rows land in the lake as
     * parquet, then the regular bootstrap pipeline runs over that snapshot. The MATERIALIZED
     * counterpart to federated Iceberg — federated re-reads the table's live metadata at query
     * time, this takes a durable copy — and the exact structural mirror of
     * {@link #registerDatasetFromSingerStream}: persist {@code PENDING}, record the upstream so the
     * dataset is refreshable, do everything expensive inside one job, reuse the same upload tail.
     */
    public DatasetRegistration registerDatasetFromIcebergTable(AuthenticatedUser caller,
            UUID projectId, UUID repositoryId, String tableIdentifier) {
        IcebergRestRepository catalog =
                requireImportableIcebergTable(caller, projectId, repositoryId, tableIdentifier);

        Dataset dataset = new Dataset(
                UUID.randomUUID(), repositoryId, tableIdentifier,
                "Copied from Iceberg table " + tableIdentifier,
                null, List.of(), null, DatasetStatus.PENDING);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        Dataset saved = datasetRepository.save(tenant, dataset);
        UUID datasetId = saved.id();

        // Manual by default, like a table import: re-reading someone's lakehouse on a schedule
        // nobody chose is a surprising thing to start doing to it.
        datasetRefreshRepository.ifPresent(refreshes -> refreshes.save(tenant,
                DatasetRefresh.manual(datasetId, caller.tenantId(), repositoryId, projectId,
                        caller.userId(), tableIdentifier)));

        Job job = jobService.submit(
                tenant,
                projectId,
                "dataset:import",
                Map.of("datasetId", datasetId.toString(), "tableIdentifier", tableIdentifier),
                caller.userId(),
                progress -> {
                    String snapshotId;
                    try {
                        snapshotId = materializeIcebergTable(
                                caller, datasetId, catalog, tableIdentifier, progress);
                    } catch (RuntimeException e) {
                        updateBootstrapStatus(tenant, datasetId, DatasetStatus.FAILED);
                        throw e;
                    }
                    attachSnapshot(tenant, datasetId, snapshotId);
                    return runBootstrapPipeline(
                            tenant, caller.userId(), datasetId, filenameHintFor(tableIdentifier), progress);
                });

        return new DatasetRegistration(datasetRepository.findById(tenant, datasetId).orElse(saved), job.id());
    }

    /**
     * Check that this repository is an Iceberg catalog Aran can copy from and that it really offers
     * this table — the same re-read-the-source membership check every import path makes, because
     * the identifier ends up located through the catalog and must be a member of what it offers,
     * never free-form input.
     */
    private IcebergRestRepository requireImportableIcebergTable(
            AuthenticatedUser caller, UUID projectId, UUID repositoryId, String tableIdentifier) {
        DataRepository repo = repositoryService.getRepository(caller, projectId, repositoryId);
        if (!(repo instanceof IcebergRestRepository catalog)) {
            throw new UnsupportedImportSourceException(
                    "Iceberg table import is only available for Iceberg REST catalog repositories. "
                    + "Use table import for relational sources or file upload for other kinds.");
        }
        if (icebergExtractor == null) {
            throw new UnsupportedImportSourceException(
                    "This build has no Iceberg extractor available, so tables cannot be copied "
                    + "into the lake. Use file upload instead.");
        }
        if (tableIdentifier == null || tableIdentifier.isBlank()) {
            throw new IllegalArgumentException("tableIdentifier must not be blank");
        }
        List<String> available = repositoryService.listAvailableDatasets(caller, projectId, repositoryId);
        if (!available.contains(tableIdentifier)) {
            throw new IllegalArgumentException(
                    "Table '" + tableIdentifier + "' is not offered by this catalog (scan returned "
                    + available.size() + " table(s))");
        }
        return catalog;
    }

    /**
     * Copy one Iceberg table's current contents into the lake and return the new snapshot id.
     *
     * <p>Deliberately identical in shape to {@link #materializeObject}: the extractor produces
     * parquet bytes, the licence ceiling is checked against the bytes actually produced, and the
     * same upload tail uploads them. Runs on a job worker thread.
     */
    private String materializeIcebergTable(AuthenticatedUser caller, UUID datasetId,
            IcebergRestRepository catalog, String tableIdentifier,
            edu.upc.dtak.aran.core.operator.ProgressReporter progress) {
        if (icebergExtractor == null) {
            throw new UnsupportedImportSourceException(
                    "This build has no Iceberg extractor available, so tables cannot be copied "
                    + "into the lake. Use file upload instead.");
        }
        progress.stage("copying " + tableIdentifier + " via iceberg_scan");
        long started = System.currentTimeMillis();

        IcebergTableExtractor.Extracted extracted =
                icebergExtractor.extract(catalog, tableIdentifier, MAX_EXTRACT_BYTES);

        progress.stage("uploading copy");
        byte[] parquet = extracted.parquet();
        long sizeBytes = parquet.length;
        licenses.requireWithinStorageLimit(caller, sizeBytes);

        LakeClient.InitUploadResponse init = lakeClient.initUpload(
                caller.tenantId(), datasetId, sizeBytes, PARQUET_CONTENT_TYPE);
        lakeClient.uploadToS3(init.uploadUrl(), parquet, PARQUET_CONTENT_TYPE);
        String snapshotId = lakeClient.completeUpload(
                caller.tenantId(), init.uploadId(), lakeClient.computeSha256(parquet), sizeBytes);

        usage.record(caller, UsageKind.UPLOAD, System.currentTimeMillis() - started, null, sizeBytes);
        return snapshotId;
    }

    /**
     * Check that this repository is a tap Aran can run and that it really offers this stream.
     *
     * <p>The membership check is the relational path's, for the relational path's reason: what a
     * caller names must be a member of what the source actually offers, re-read now rather than
     * trusted from the row. A tap's stream list can change between an import and a refresh exactly
     * as a database's table list can — an API version bumps, a scope is revoked — and the string
     * ends up in the tap's own {@code --config}-driven selection, so it is not free-form input.
     */
    private SingerTapRepository requireImportableStream(
            AuthenticatedUser caller, UUID projectId, UUID repositoryId, String stream) {

        DataRepository repo = repositoryService.getRepository(caller, projectId, repositoryId);
        if (!(repo instanceof SingerTapRepository tap)) {
            throw new UnsupportedImportSourceException(
                    "Stream import is only available for Singer tap repositories. Use table import "
                    + "for relational sources or file upload for other kinds.");
        }
        if (singerTapExtractor == null) {
            throw new UnsupportedImportSourceException(
                    "This build cannot run Singer taps, so a stream cannot be imported. Use file "
                    + "upload instead.");
        }
        if (stream == null || stream.isBlank()) {
            throw new IllegalArgumentException("stream must not be blank");
        }
        List<String> available = repositoryService.listAvailableDatasets(caller, projectId, repositoryId);
        if (!available.contains(stream)) {
            throw new IllegalArgumentException(
                    "Stream '" + stream + "' is not offered by this tap (discovery returned "
                    + available.size() + " stream(s))");
        }
        return tap;
    }

    /**
     * Run one stream and put its records in the lake, returning the new snapshot id.
     *
     * <p>Deliberately identical in shape to {@link #materializeByJdbcExtract} and
     * {@link #materializeObject}: the extractor produces parquet bytes, the licence ceiling is
     * checked against the bytes actually produced, and the same upload tail uploads them. Runs on a
     * job worker thread — a tap paginating a SaaS API is minutes of work, not milliseconds.
     */
    private String materializeStream(AuthenticatedUser caller, UUID datasetId,
            SingerTapRepository tap, String stream,
            edu.upc.dtak.aran.core.operator.ProgressReporter progress) {
        if (singerTapExtractor == null) {
            throw new UnsupportedImportSourceException(
                    "This build cannot run Singer taps, so a stream cannot be imported. Use file "
                    + "upload instead.");
        }
        progress.stage("running the " + tap.tapId() + " tap for stream " + stream);
        long started = System.currentTimeMillis();

        SingerTapExtractor.Extracted extracted =
                singerTapExtractor.extract(tap, stream, MAX_EXTRACT_BYTES);

        progress.stage("uploading records");
        byte[] parquet = extracted.parquet();
        long sizeBytes = parquet.length;
        licenses.requireWithinStorageLimit(caller, sizeBytes);

        LakeClient.InitUploadResponse init = lakeClient.initUpload(
                caller.tenantId(), datasetId, sizeBytes, PARQUET_CONTENT_TYPE);
        lakeClient.uploadToS3(init.uploadUrl(), parquet, PARQUET_CONTENT_TYPE);
        String snapshotId = lakeClient.completeUpload(
                caller.tenantId(), init.uploadId(), lakeClient.computeSha256(parquet), sizeBytes);

        usage.record(caller, UsageKind.UPLOAD, System.currentTimeMillis() - started, null, sizeBytes);
        return snapshotId;
    }

    /**
     * Check that this repository, engine and table can actually be imported, and return the
     * repository typed.
     *
     * <p>Shared by registration and by refresh, and the membership check is the reason: a table can
     * be dropped or renamed between the two. Trusting the {@code source_table} recorded at import
     * would mean a refresh interpolating a name the source no longer has — the same string reaching
     * SQL that the check exists to prevent.
     */
    private RelationalDatabaseRepository requireImportableTable(
            AuthenticatedUser caller, UUID projectId, UUID repositoryId, String tableName) {

        edu.upc.dtak.aran.core.domain.DataRepository repo =
                repositoryService.getRepository(caller, projectId, repositoryId);
        RelationalEngine.BulkExtract mechanism = repo instanceof RelationalDatabaseRepository r0
                ? RelationalEngines.defaults().find(r0.engine())
                        .map(RelationalEngine::bulkExtract)
                        .orElse(RelationalEngine.BulkExtract.NONE)
                : RelationalEngine.BulkExtract.NONE;
        if (!(repo instanceof RelationalDatabaseRepository relational) || !mechanism.isImplemented()) {
            throw new UnsupportedImportSourceException(
                    "Table import is not available for this repository\'s engine. "
                    + "Use file upload for other sources, or a federated repository for live passthrough.");
        }
        if (mechanism == RelationalEngine.BulkExtract.JDBC_GENERIC && tableExtractor == null) {
            throw new UnsupportedImportSourceException(
                    "This build has no JDBC extract available, so " + relational.engine()
                    + " tables cannot be imported. Use file upload instead.");
        }

        // SQL-injection guard: the table name must be an exact member of what the
        // repository actually exposes — we never interpolate free-form input into SQL.
        List<String> available = repositoryService.listAvailableDatasets(caller, projectId, repositoryId);
        if (!available.contains(tableName)) {
            throw new IllegalArgumentException(
                    "Table \'" + tableName + "\' is not visible in this repository (scan returned "
                    + available.size() + " tables)");
        }
        return relational;
    }

    /**
     * Copy a table\'s current contents into the lake and return the new snapshot id.
     *
     * <p>The single place a materialized copy is produced, used by the first import and by every
     * refresh after it. Keeping them one method is the point: a refresh that produced its snapshot
     * differently from the import would drift in type handling, size limits and usage accounting,
     * and the divergence would only show up as a schema change on a dataset nobody edited.
     *
     * <p>Which mechanism runs comes from {@link RelationalEngine#bulkExtract()}. {@code LAKE_NATIVE}
     * is preferred where it exists — the lake pulls the table itself with a hand-written type map,
     * which beats inferring from {@code ResultSetMetaData}. {@code JDBC_GENERIC} is the fallback
     * that makes every other engine work at all.
     *
     * <p>Runs on a job worker thread, never on a request thread.
     */
    private String materializeTable(AuthenticatedUser caller, UUID datasetId,
                                    RelationalDatabaseRepository relational, String tableName,
                                    edu.upc.dtak.aran.core.operator.ProgressReporter progress) {

        RelationalEngine.BulkExtract mechanism = RelationalEngines.defaults().find(relational.engine())
                .map(RelationalEngine::bulkExtract)
                .orElse(RelationalEngine.BulkExtract.NONE);

        return mechanism == RelationalEngine.BulkExtract.JDBC_GENERIC
                ? materializeByJdbcExtract(caller, datasetId, relational, tableName, progress)
                : materializeByLakeImport(caller, datasetId, relational, tableName, progress);
    }

    /** The lake pulls the table itself (PostgreSQL today) and hands back a snapshot id. */
    private String materializeByLakeImport(AuthenticatedUser caller, UUID datasetId,
                                           RelationalDatabaseRepository relational, String tableName,
                                           edu.upc.dtak.aran.core.operator.ProgressReporter progress) {
        progress.stage("starting lake import");
        UUID tenantUuid = caller.tenantId();
        String connectionString = repositoryService.buildPostgresConnectionUri(relational);
        // Qualified with the repository's schema (S2-7). The lake runs this text verbatim — it
        // screens the leading keyword and nothing else — and the connection URI carries no
        // search_path, so an unqualified name resolves against the server's default. That is the
        // wrong relation whenever the schema is set and a same-named table exists elsewhere, and
        // no relation at all when it does not.
        RelationalEngine engine = RelationalEngines.defaults().find(relational.engine())
                .orElseThrow(() -> new UnsupportedImportSourceException(
                        "no engine registered for '" + relational.engine() + "'"));
        String importQuery = "SELECT * FROM " + engine.qualify(relational.schema(), tableName);

        String lakeJobId = lakeClient.startImport(tenantUuid, datasetId, connectionString, importQuery);
        // Surfaced on the job row: the lake job id is otherwise a local variable, and without it
        // nobody can correlate a failed Aran import with the lake's own row (S16-3/JOBS_EVENTS-12).
        progress.stage("lake import " + lakeJobId + " running");
        LakeClient.LakeJob lakeJob = awaitLakeJob(tenantUuid, lakeJobId);
        if (!"SUCCEEDED".equals(lakeJob.status())) {
            throw new JobFailureException("LAKE_IMPORT_FAILED",
                    lakeJob.errorMessage() != null
                            ? lakeJob.errorMessage()
                            : "lake import job " + lakeJobId + " failed");
        }
        Object snapshotId = lakeJob.result().get("snapshot_id");
        if (snapshotId == null) {
            throw new JobFailureException("LAKE_IMPORT_FAILED",
                    "lake import job " + lakeJobId + " succeeded but returned no snapshot_id");
        }
        return snapshotId.toString();
    }

    /**
     * Wait for a lake job to reach a terminal state — but not forever, and not at the mercy of one
     * bad HTTP call.
     *
     * <p>Two hazards, opposite in kind, and neither was handled (S16-3). Without a deadline this
     * loop waited indefinitely on a lake job that never finished, holding a job row RUNNING that
     * nothing could end. And since {@code HttpLakeClient} throws on any non-200 or IO error, a
     * single blip on the loopback call failed a Java import whose lake job went on to succeed and
     * register a snapshot nothing ever attached — a FAILED dataset beside real bytes.
     *
     * <p>Timing out does not cancel the lake's work (there is no cancel endpoint); the message says
     * as much, so the operator knows to look on the lake side rather than assume nothing happened.
     */
    private LakeClient.LakeJob awaitLakeJob(UUID tenantUuid, String lakeJobId) {
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plus(lakeImportTimeout);
        int consecutiveFailures = 0;

        while (true) {
            try {
                LakeClient.LakeJob lakeJob = lakeClient.getLakeJob(tenantUuid, lakeJobId);
                consecutiveFailures = 0;
                if (lakeJob.isTerminal()) {
                    return lakeJob;
                }
            } catch (RuntimeException ex) {
                if (++consecutiveFailures > MAX_CONSECUTIVE_POLL_FAILURES) {
                    throw new JobFailureException("LAKE_POLL_FAILED",
                            "could not reach the lake to poll import job " + lakeJobId + " after "
                                    + consecutiveFailures + " attempts in a row (" + ex.getMessage()
                                    + "); the import itself may have completed");
                }
                log.warn("Polling lake import job {} failed ({} in a row, tolerating {})",
                        lakeJobId, consecutiveFailures, MAX_CONSECUTIVE_POLL_FAILURES, ex);
            }

            Instant now = Instant.now();
            if (!now.isBefore(deadline)) {
                throw new JobFailureException("LAKE_IMPORT_TIMEOUT",
                        "lake import job " + lakeJobId + " did not finish within " + lakeImportTimeout
                                + " (waited " + Duration.between(startedAt, now) + "); it may still be"
                                + " running in the lake — raise aran.lake.import-timeout if this table"
                                + " is legitimately this slow");
            }

            try {
                Thread.sleep(lakePollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobFailureException("LAKE_IMPORT_INTERRUPTED",
                        "waiting on lake import job " + lakeJobId + " was interrupted");
            }
        }
    }

    /** Aran reads the table over JDBC, writes parquet, and uploads it like any other file. */
    private String materializeByJdbcExtract(AuthenticatedUser caller, UUID datasetId,
                                            RelationalDatabaseRepository relational, String tableName,
                                            edu.upc.dtak.aran.core.operator.ProgressReporter progress) {
        if (tableExtractor == null) {
            throw new UnsupportedImportSourceException(
                    "This build has no JDBC extract available, so " + relational.engine()
                    + " tables cannot be imported. Use file upload instead.");
        }
        progress.stage("extracting " + tableName + " over JDBC");
        long started = System.currentTimeMillis();

        TableExtractor.Extracted extracted;
        try {
            extracted = tableExtractor.extract(relational, tableName, MAX_EXTRACT_BYTES);
        } catch (UnsupportedOperationException e) {
            // The engine declared a mechanism this build cannot perform. That is a wiring mistake
            // rather than a user\'s, so it must not read as "your database is unsupported".
            throw new IllegalStateException(
                    "engine " + relational.engine() + " declares a bulk extract this build cannot "
                    + "run: " + e.getMessage(), e);
        }

        progress.stage("uploading extract");
        byte[] parquet = extracted.parquet();
        long sizeBytes = parquet.length;
        // The same ceiling an uploaded file gets. Checked after the extract rather than before
        // because the source table\'s size on disk is not the parquet\'s size, and guessing would
        // either refuse imports that fit or admit ones that do not.
        licenses.requireWithinStorageLimit(caller, sizeBytes);

        LakeClient.InitUploadResponse init = lakeClient.initUpload(
                caller.tenantId(), datasetId, sizeBytes, PARQUET_CONTENT_TYPE);
        lakeClient.uploadToS3(init.uploadUrl(), parquet, PARQUET_CONTENT_TYPE);
        String snapshotId = lakeClient.completeUpload(
                caller.tenantId(), init.uploadId(), lakeClient.computeSha256(parquet), sizeBytes);

        usage.record(caller, UsageKind.UPLOAD, System.currentTimeMillis() - started, null, sizeBytes);
        return snapshotId;
    }

    /**
     * Register a dataset whose data is copied from one object in an object store: the object is read
     * live over {@code httpfs}, copied into the lake as parquet, then the regular bootstrap pipeline
     * runs over that snapshot. The MATERIALIZED counterpart to federated object storage — federated
     * reads the {@code s3://} object at query time, this takes a durable copy — and the exact
     * structural mirror of {@link #registerDatasetFromRepositoryTable}: persist {@code PENDING}
     * immediately, do all the expensive work inside one job whose id comes back with the
     * registration, reuse the same {@code initUpload}/{@code uploadToS3}/{@code completeUpload} tail
     * every other materialize path takes.
     *
     * @param objectKey the object's key within the repository's bucket — the specific object to copy
     * @param format    the object's format ({@code PARQUET}/{@code CSV}/…); blank degrades to PARQUET
     */
    public DatasetRegistration registerDatasetFromObjectStorage(AuthenticatedUser caller,
            UUID projectId, UUID repositoryId, String objectKey, String format) {
        ObjectStorageRepository objectStore =
                requireImportableObject(caller, projectId, repositoryId, objectKey);

        Dataset dataset = new Dataset(
                UUID.randomUUID(), repositoryId, objectKey,
                "Copied from " + objectStore.provider() + " object " + objectKey,
                null, List.of(), null, DatasetStatus.PENDING);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        Dataset saved = datasetRepository.save(tenant, dataset);
        UUID datasetId = saved.id();

        Job job = jobService.submit(
                tenant,
                projectId,
                "dataset:import",
                Map.of("datasetId", datasetId.toString(), "objectKey", objectKey),
                caller.userId(),
                progress -> {
                    String snapshotId;
                    try {
                        snapshotId = materializeObject(
                                caller, datasetId, objectStore, objectKey, format, progress);
                    } catch (RuntimeException e) {
                        updateBootstrapStatus(tenant, datasetId, DatasetStatus.FAILED);
                        throw e;
                    }
                    attachSnapshot(tenant, datasetId, snapshotId);
                    return runBootstrapPipeline(
                            tenant, caller.userId(), datasetId, filenameHintForObject(objectKey), progress);
                });

        return new DatasetRegistration(datasetRepository.findById(tenant, datasetId).orElse(saved), job.id());
    }

    /**
     * Check that this repository is an object store Aran can copy from, and return it typed.
     *
     * <p>The extractor must be present: the operator modules are {@code runtimeOnly}, so a
     * build can genuinely lack it, and NPEing there would read as a bug rather than a wiring state.
     */
    private ObjectStorageRepository requireImportableObject(
            AuthenticatedUser caller, UUID projectId, UUID repositoryId, String objectKey) {
        DataRepository repo = repositoryService.getRepository(caller, projectId, repositoryId);
        if (!(repo instanceof ObjectStorageRepository objectStore)) {
            throw new UnsupportedImportSourceException(
                    "Object copy is only available for object-storage repositories. Use table import "
                    + "for relational sources or file upload for other kinds.");
        }
        if (objectStorageExtractor == null) {
            throw new UnsupportedImportSourceException(
                    "This build has no object-storage extractor available, so objects cannot be "
                    + "copied into the lake. Use file upload instead.");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        return objectStore;
    }

    /**
     * Copy one object's current contents into the lake and return the new snapshot id.
     *
     * <p>Deliberately identical in shape to {@link #materializeByJdbcExtract}: the extractor produces
     * parquet bytes, the licence ceiling is checked against the bytes actually produced, and the same
     * {@code initUpload}/{@code uploadToS3}/{@code completeUpload} tail uploads them. Only the
     * extractor differs — one reads a table over JDBC, the other an object over httpfs — which is the
     * whole point of the {@link ObjectStorageExtractor} port. Runs on a job worker thread.
     */
    private String materializeObject(AuthenticatedUser caller, UUID datasetId,
            ObjectStorageRepository objectStore, String objectKey, String format,
            edu.upc.dtak.aran.core.operator.ProgressReporter progress) {
        if (objectStorageExtractor == null) {
            throw new UnsupportedImportSourceException(
                    "This build has no object-storage extractor available, so objects cannot be "
                    + "copied into the lake. Use file upload instead.");
        }
        progress.stage("copying " + objectKey + " over httpfs");
        long started = System.currentTimeMillis();

        ObjectStorageExtractor.Extracted extracted =
                objectStorageExtractor.extract(objectStore, objectKey, format, MAX_EXTRACT_BYTES);

        progress.stage("uploading copy");
        byte[] parquet = extracted.parquet();
        long sizeBytes = parquet.length;
        // The same ceiling an uploaded file gets, checked against the parquet actually produced.
        licenses.requireWithinStorageLimit(caller, sizeBytes);

        LakeClient.InitUploadResponse init = lakeClient.initUpload(
                caller.tenantId(), datasetId, sizeBytes, PARQUET_CONTENT_TYPE);
        lakeClient.uploadToS3(init.uploadUrl(), parquet, PARQUET_CONTENT_TYPE);
        String snapshotId = lakeClient.completeUpload(
                caller.tenantId(), init.uploadId(), lakeClient.computeSha256(parquet), sizeBytes);

        usage.record(caller, UsageKind.UPLOAD, System.currentTimeMillis() - started, null, sizeBytes);
        return snapshotId;
    }

    /**
     * The filename bootstrap-time format detection should read for a copied object (F14).
     *
     * <p>The rule is F14's: the hint names the file that was <em>uploaded</em>. What
     * {@link #materializeObject} uploads is the extractor's parquet under
     * {@link #PARQUET_CONTENT_TYPE} — never the object's own bytes — so the hint is a
     * {@code .parquet} name whatever the key looked like. This used to pass a key containing a dot
     * through unchanged, which described the object read rather than the file produced, and for
     * {@code raw/people.csv} that is not a cosmetic mismatch: detection is N independent boolean
     * claims and refuses two, so {@code CsvConnector} (extension) and {@code ParquetConnector}
     * (content type) both claimed the copy, {@code SourceConnectors.forSource} threw "Ambiguous
     * source", and the dataset went FAILED with the copy already sitting in the lake (S4-9).
     *
     * <p>The key's stem is kept, so the source class is still recognisably {@code people}, and only
     * the <em>last path segment</em> is examined — a dot in a directory ({@code v1.2/people}) is not
     * an extension, and treating it as one left the hint with no extension at all.
     */
    private static String filenameHintForObject(String objectKey) {
        int lastSlash = objectKey.lastIndexOf('/');
        String segment = objectKey.substring(lastSlash + 1);
        int dot = segment.lastIndexOf('.');
        // dot > 0, not >= 0: a leading dot is a hidden file's name, not its extension.
        String stem = dot > 0 ? segment.substring(0, dot) : segment;
        return objectKey.substring(0, lastSlash + 1) + stem + ".parquet";
    }

    /**
     * Point the dataset at a newly produced snapshot.
     *
     * <p>Bootstrap reads the dataset row, so this must land before it runs — and on a refresh it is
     * also the moment queries start seeing the new copy. That is why the refresh path keeps the
     * previous snapshot id in hand and calls this a second time when the drift gate withholds the
     * new schema (REFRESH_DRIFT-1): the graph and the bytes have to be put back together.
     */
    private void attachSnapshot(TenantContext tenant, UUID datasetId, String snapshotId) {
        Dataset current = datasetRepository.findById(tenant, datasetId)
                .orElseThrow(() -> new JobFailureException("DATASET_GONE",
                        "dataset " + datasetId + " disappeared during import"));
        datasetRepository.save(tenant, new Dataset(
                current.id(), current.repositoryId(), current.name(), current.description(),
                snapshotId, current.columns(), current.graphJson(), current.bootstrapStatus()));
    }

    /**
     * The filename bootstrap-time format detection should read (F14).
     *
     * <p>A bare table name has no extension to detect, and every mechanism here produces parquet,
     * so the hint says so. The dataset\'s own name ("orders") is useless for this and is what the
     * detector would otherwise fall back to.
     */
    private static String filenameHintFor(String tableName) {
        return tableName + ".parquet";
    }

    /**
     * Re-copy a materialized dataset from its upstream, inside a job.
     *
     * <p>Everything after the copy is deliberately identical to the first import: same snapshot
     * attach, same bootstrap, same auto-profiling, and — Phase 4B Task 5 — the same best-effort
     * catalog harvest. This is the one method both the refresh scheduler ({@code
     * DatasetRefreshService.runDue}) and a manual refresh-now ({@code refreshNow}) call, so wiring
     * the harvest here is what makes "the existing refresh cadence re-harvests" true for both
     * without duplicating the hook in the scheduler itself. A refresh is not a lighter operation
     * than an import — the source schema may have changed, and re-running bootstrap is how that is
     * noticed.
     *
     * <p>One thing is <em>not</em> identical, and it is what noticing costs (REFRESH_DRIFT-1): a
     * first import that the drift gate withholds leaves nothing behind, whereas a refresh has
     * already replaced the copy the active graph was compiled against. So this path alone unwinds
     * that — see {@link #blockedByDrift}.
     *
     * <p>T6: which upstream is re-read is decided here and nowhere else. A Singer dataset's upstream
     * is a tap stream rather than a table, and everything after the re-read — the drift
     * compensation, the outcome recording, the stale wrapper verdicts — is identical, so the two
     * kinds differ by exactly one lambda rather than by a second copy of this method.
     *
     * @return the submitted job
     */
    public Job refreshDataset(AuthenticatedUser caller, UUID projectId, UUID repositoryId,
                              UUID datasetId, DatasetRefresh refresh) {
        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);
        if (repository instanceof SingerTapRepository) {
            SingerTapRepository tap = requireImportableStream(
                    caller, projectId, repositoryId, refresh.sourceTable());
            return submitRefresh(caller, projectId, datasetId, refresh,
                    progress -> materializeStream(
                            caller, datasetId, tap, refresh.sourceTable(), progress),
                    // No catalog to harvest: a tap has no information_schema (Phase 4B is
                    // relational-only, by its own honest limits).
                    () -> { });
        }
        if (repository instanceof IcebergRestRepository) {
            IcebergRestRepository catalog = requireImportableIcebergTable(
                    caller, projectId, repositoryId, refresh.sourceTable());
            return submitRefresh(caller, projectId, datasetId, refresh,
                    progress -> materializeIcebergTable(
                            caller, datasetId, catalog, refresh.sourceTable(), progress),
                    // Phase 4B's harvest speaks information_schema; an Iceberg catalog has none.
                    () -> { });
        }
        RelationalDatabaseRepository relational = requireImportableTable(
                caller, projectId, repositoryId, refresh.sourceTable());
        return submitRefresh(caller, projectId, datasetId, refresh,
                progress -> materializeTable(
                        caller, datasetId, relational, refresh.sourceTable(), progress),
                () -> harvestCatalog(caller.tenantId(), relational));
    }

    /** How a refresh re-reads its upstream. The only thing that differs between source kinds. */
    @FunctionalInterface
    private interface Rematerialize {
        /** @return the new snapshot id */
        String run(edu.upc.dtak.aran.core.operator.ProgressReporter progress);
    }

    /**
     * The refresh job body: everything a refresh does once its upstream has been re-read.
     *
     * @param afterSuccess best-effort work that only applies to some source kinds, run last so a
     *                     failure in it cannot undo a refresh that succeeded
     */
    private Job submitRefresh(AuthenticatedUser caller, UUID projectId, UUID datasetId,
                              DatasetRefresh refresh, Rematerialize rematerialize,
                              Runnable afterSuccess) {
        TenantContext tenant = new TenantContext(caller.tenantId().toString());

        return jobService.submit(
                tenant,
                projectId,
                "dataset:refresh",
                Map.of("datasetId", datasetId.toString(), "tableName", refresh.sourceTable()),
                caller.userId(),
                progress -> {
                    // REFRESH_DRIFT-1: read before the copy, because attaching the copy is what
                    // overwrites it. This is the only pointer that can put the dataset back where
                    // the still-active graph expects to find its bytes.
                    String priorSnapshotId = datasetRepository.findById(tenant, datasetId)
                            .map(Dataset::snapshotId)
                            .orElse(null);
                    String snapshotId;
                    try {
                        snapshotId = rematerialize.run(progress);
                    } catch (RuntimeException e) {
                        // The dataset keeps its previous snapshot and stays queryable: a failed
                        // refresh must not take a working dataset down. The failure is recorded on
                        // the refresh row, which is where a user looks for "why is this stale".
                        recordRefreshOutcome(tenant, refresh, e);
                        throw e;
                    }
                    attachSnapshot(tenant, datasetId, snapshotId);
                    Map<String, String> result;
                    try {
                        result = runBootstrapPipeline(
                                tenant, caller.userId(), datasetId,
                                filenameHintFor(refresh.sourceTable()), progress);
                    } catch (RuntimeException e) {
                        // REFRESH_DRIFT-1: same channel as the materialize failure above. Without
                        // this the refresh row stayed silent on a failed bootstrap — the freshness
                        // panel showed the previous success and no error at all.
                        recordRefreshOutcome(tenant, refresh, e);
                        throw e;
                    }
                    if ("false".equals(result.get(APPLIED_KEY))) {
                        throw blockedByDrift(tenant, datasetId, refresh, priorSnapshotId, result);
                    }
                    recordRefreshOutcome(tenant, refresh, null);
                    // T2 (audit WRAPPER_VERIFY-1): the new bytes invalidate any EXPANDED wrapper
                    // verdict computed against the old ones — downgrade now so the badge is
                    // honest, re-earned at the next compile. Best-effort, like the harvest below.
                    try {
                        integrationModels.markWrapperVerdictsStale(tenant, datasetId);
                    } catch (RuntimeException e) {
                        log.warn("Could not mark wrapper verdicts stale for dataset {} after"
                                + " refresh", datasetId, e);
                    }
                    afterSuccess.run();
                    return result;
                });
    }

    /**
     * REFRESH_DRIFT-1: undo a refresh whose new schema the drift gate refused to apply.
     *
     * <p>The attach has to come before the bootstrap — bootstrap reads the dataset row to find the
     * bytes it is about to describe — so by the time the gate speaks, queries are already reading
     * the new copy through wrappers compiled from the <em>old</em> graph. That is the wrong-results
     * channel this closes: a dropped column errors, and a redefined one answers differently with
     * nothing saying so. Putting the pointer back is the only thing that restores the pairing,
     * because the graph is deliberately left alone for a human to decide about.
     *
     * <p>The job is failed rather than completed with a "blocked" marker in its result map. The
     * refresh-now caller does not read this job's result — it re-reads the dataset row and the
     * refresh policy — so a marker would be invisible, while {@code lastError} on the refresh row
     * (this method's other half) and the job's own FAILED/errorCode are both surfaced. And a
     * refresh that rolled its own copy back has not succeeded in any sense a user would recognise:
     * the data is exactly as stale as before. This differs from the first-import path on purpose,
     * where a withheld bootstrap really is a clean outcome — nothing was rolled back there, because
     * nothing was serving yet.
     *
     * @param priorSnapshotId the copy the graph was compiled against, or null on a dataset that has
     *                        never had one — nothing to restore, so it keeps the new copy and the
     *                        {@code DRIFTED} status the pipeline already set
     */
    private JobFailureException blockedByDrift(TenantContext tenant, UUID datasetId,
                                               DatasetRefresh refresh,
                                               @Nullable String priorSnapshotId,
                                               Map<String, String> bootstrapResult) {
        String summary = bootstrapResult.get(DRIFT_SUMMARY_KEY);
        JobFailureException failure = new JobFailureException(
                "REFRESH_BLOCKED_BY_DRIFT",
                "blocked by schema drift — review the drift report and force a re-bootstrap to "
                + "adopt the new schema"
                + (summary == null || summary.isBlank() ? "" : ". " + summary));
        // Recorded before the restore: if the dataset vanished under us, the reason the refresh
        // stopped is still the thing the user needs on the row.
        recordRefreshOutcome(tenant, refresh, failure);
        if (priorSnapshotId == null) {
            log.warn("Refresh of dataset {} was blocked by schema drift and has no previous snapshot "
                    + "to restore; it keeps the new copy and stays DRIFTED", datasetId);
        } else {
            log.warn("Refresh of dataset {} was blocked by schema drift; restoring snapshot {}",
                    datasetId, priorSnapshotId);
            attachSnapshot(tenant, datasetId, priorSnapshotId);
        }
        return failure;
    }

    private void recordRefreshOutcome(TenantContext tenant, DatasetRefresh refresh,
                                      @Nullable RuntimeException failure) {
        datasetRefreshRepository.ifPresent(refreshes -> refreshes.findByDataset(tenant, refresh.datasetId())
                .ifPresent(current -> refreshes.save(tenant, failure == null
                        ? current.succeeded(java.time.Instant.now())
                        : current.failed(failure.getMessage() == null
                                ? failure.getClass().getSimpleName()
                                : failure.getMessage()))));
    }

    /**
     * Force the dataset's bootstrap status, whatever the operator did or did not write.
     *
     * <p>Runs on the job's worker thread, outside the registering request's
     * transaction — by the time it fires, that transaction has committed.
     */
    public void updateBootstrapStatus(TenantContext tenant, UUID datasetId, DatasetStatus status) {
        datasetRepository.findById(tenant, datasetId).ifPresent(current -> {
            if (status == current.bootstrapStatus()) {
                return;
            }
            datasetRepository.save(tenant, new Dataset(
                    current.id(), current.repositoryId(), current.name(), current.description(),
                    current.snapshotId(), current.columns(), current.graphJson(), status));
        });
    }

    /** {@link #updateDataset}'s refusal code: the dataset is read live, and its name is its table. */
    public static final String LIVE_DATASET_RENAME = "LIVE_DATASET_RENAME";

    /**
     * Update an existing dataset definition, after verifying user access.
     *
     * <p><b>UI-OD8 (audit J2-F6): a dataset read live cannot be renamed.</b> Its name is the
     * source table the live scan reads ({@code SourceAccess}'s FEDERATED), so a rename points
     * every query over its class at a table that does not exist. The description stays editable.
     * An upload may still be renamed — its bytes are in the lake under its id — though the next
     * re-bootstrap re-mints its IRIs and is held as BREAKING drift, which the UI warns about.
     * An immutable identifier under an editable display label is the C3 follow-up.
     */
    public Dataset updateDataset(AuthenticatedUser caller, UUID projectId, UUID repositoryId, Dataset dataset) {
        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);

        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        // The dataset must be the path repository's: findById is tenant-scoped only, and the
        // access mode below is read off that repository (review UA5 — naming an upload repository
        // in the URL let a live dataset be renamed). 404 either way, never "it exists elsewhere".
        Dataset existing = datasetRepository.findById(tenant, dataset.id())
                .filter(d -> d.repositoryId().equals(repositoryId))
                .orElseThrow(() -> new ResourceNotFoundException("dataset not found"));
        if (!existing.name().equals(dataset.name())
                && SourceAccess.resolve(repository, existing.name(), existing.snapshotId() != null)
                        == AccessMode.FEDERATED) {
            throw new StateConflictException(LIVE_DATASET_RENAME,
                    "'" + existing.name() + "' is read live from its source, and its name is the "
                            + "table every query reads: renaming it would break every query over "
                            + "its class. Nothing was changed. Register the new table as its own "
                            + "dataset instead.");
        }

        Dataset merged = new Dataset(
                existing.id(),
                existing.repositoryId(),
                dataset.name(),
                dataset.description(),
                existing.snapshotId(),
                existing.columns(),
                existing.graphJson(),
                existing.bootstrapStatus()
        );
        return datasetRepository.save(tenant, merged);
    }

    /**
     * Preview the first rows the given wrapper SQL produces over the dataset's snapshot.
     *
     * <p>{@code wrapperSql} is request-body text — the Wrapper tab sends whatever is in the
     * editor, saved or not — so it gets the same two layers every other snapshot-executing path
     * applies, in the same order (T3.6 / audit WRAPPER_VERIFY-3, which found this method running
     * on a raw unconfined connection with the screen as its only layer): {@link
     * WrapperSqlValidator}'s token screen first, fail-closed, before the repository is read or a
     * byte is downloaded; then the connection's own filesystem lockdown, once the snapshot view
     * exists. Unlike the query path there is no federated branch to skip — a preview always reads
     * a downloaded snapshot FILE, never {@code postgres_scan}/{@code mysql_scan} — so the second
     * layer always applies here.
     */
    public edu.upc.dtak.aran.core.domain.DatasetPreview previewWrapper(AuthenticatedUser caller, UUID projectId, UUID repositoryId, UUID datasetId, String wrapperSql) {
        WrapperSqlValidator.validate(wrapperSql);

        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        Dataset dataset = datasetRepository.findById(tenant, datasetId)
                .orElseThrow(() -> new IllegalArgumentException("dataset not found"));

        if (dataset.snapshotId() == null) {
            throw noStoredSnapshot(repository, dataset);
        }

        // WRAPPER_VERIFY-5: the scratch directory is owned by THIS try/finally, from creation.
        // The temp file used to be created inside the try and deleted by an inner finally around
        // the DuckDB connection alone, so anything that threw between the two — a download that
        // returned nothing usable, the delimiter sniff, a driver that would not load — left the
        // snapshot bytes on disk for good.
        java.nio.file.Path workDir = null;
        try {
            // A per-invocation scratch DIRECTORY, not the shared temp root, for the same reason
            // ConstraintDiscoveryOperator uses one: the lockdown below allowlists exactly this
            // directory, and it holds only this preview's single file. Allowlisting the JVM's
            // temp root would allowlist every other tenant's snapshot sitting next to it.
            workDir = java.nio.file.Files.createTempDirectory("aran-wrapper-preview-");
            LakeClient.SnapshotBytes blob = lakeClient.downloadSnapshot(caller.tenantId(), dataset.snapshotId());

            java.nio.file.Path snapshotFile =
                    java.nio.file.Files.createTempFile(workDir, "snapshot-", ".data");
            java.nio.file.Files.write(snapshotFile, blob.bytes());

            return runWrapperPreview(previewReadExpression(blob, dataset.name(), snapshotFile),
                    wrapperSql, workDir);
        } catch (Exception e) {
            throw new PreviewFailedException("Failed to execute wrapper preview: " + e.getMessage(), e);
        } finally {
            deletePreviewScratchDir(workDir);
        }
    }

    /**
     * The DuckDB read expression for a downloaded snapshot.
     *
     * <p>WRAPPER_VERIFY-4: this used to know only JSON and CSV, so every parquet snapshot — what
     * a relational import, a JDBC extract and an object-storage copy all produce — was read as
     * delimited text and either failed or previewed garbage. {@link #isParquet} decides on the
     * {@code PAR1} magic as well as the content type, because the content type an import records
     * is not always the one that reaches here.
     */
    private String previewReadExpression(LakeClient.SnapshotBytes blob, String datasetName,
                                         java.nio.file.Path snapshotFile) {
        String path = Names.sqlLiteral(snapshotFile.toAbsolutePath().toString());
        if (isParquet(blob)) {
            return "read_parquet('" + path + "')";
        }
        String ct = blob.contentType() == null ? "" : blob.contentType().toLowerCase(java.util.Locale.ROOT);
        if (ct.contains("json") || datasetName.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            return "read_json_auto('" + path + "')";
        }
        return "read_csv_auto('" + path + "', delim='" + detectDelimiter(blob.bytes()) + "', header=true)";
    }

    /** The view a preview's {@code ${SNAPSHOT}} binds to — never the read expression itself. */
    private static final String PREVIEW_SNAPSHOT_VIEW = "src_snapshot";

    /** Rows a wrapper preview returns. The Wrapper tab shows a sample, not a result set. */
    private static final int PREVIEW_ROW_LIMIT = 20;

    /**
     * Run the (already screened) wrapper SQL against the snapshot on a locked-down in-memory
     * DuckDB, and read back at most {@link #PREVIEW_ROW_LIMIT} rows.
     *
     * <p>Binding the snapshot behind a view rather than substituting the read expression into the
     * user's text is what makes the lockdown possible at all: the read expression is created
     * before the filesystem is taken away, and the statement that runs afterwards names only a
     * view. Same idiom as {@code QueryEngineOperator.verifyWrapper}'s scratch connection.
     */
    private edu.upc.dtak.aran.core.domain.DatasetPreview runWrapperPreview(
            String readExpr, String wrapperSql, java.nio.file.Path allowedDir) throws Exception {
        java.util.List<String> headers = new java.util.ArrayList<>();
        java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();

        try (java.sql.Connection conn = edu.upc.dtak.aran.core.util.DuckDbSession.open()) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                // Operator-built expression over the file just downloaded — not user text — so it
                // is deliberately not subject to the wrapper screen; the wrapper only ever sees
                // this view through ${SNAPSHOT}.
                stmt.execute("CREATE OR REPLACE VIEW " + PREVIEW_SNAPSHOT_VIEW
                        + " AS SELECT * FROM " + readExpr);
                lockDownFilesystem(stmt, allowedDir);
            }
            String boundSql = wrapperSql.replace("${SNAPSHOT}", PREVIEW_SNAPSHOT_VIEW);
            try (java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery(capPreviewRows(boundSql))) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    headers.add(meta.getColumnName(i));
                }
                while (rs.next()) {
                    java.util.List<Object> row = new java.util.ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object value = rs.getObject(i);
                        row.add(value != null ? value.toString() : null);
                    }
                    rows.add(row);
                }
            }
        }
        return new edu.upc.dtak.aran.core.domain.DatasetPreview(headers, rows);
    }

    /**
     * Bound the preview by wrapping the user's statement as a subquery, never by appending.
     *
     * <p>WRAPPER_VERIFY-5, and the same bug class {@code QueryEngineOperator.capRows} documents:
     * {@code sql + " LIMIT 20"} is defeated by a trailing {@code --} comment (which swallows the
     * clause outright, so the preview downloads and materialises the whole snapshot) and by a
     * trailing semicolon (which makes it a parse error). A subquery keeps the user's comments,
     * literals and their own LIMIT inside it, and caps whatever it produces. The newline before
     * the closing paren is what stops a trailing comment from commenting the paren out.
     */
    static String capPreviewRows(String sql) {
        String stripped = sql.strip();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        return "SELECT * FROM (\n" + stripped + "\n) AS _aran_preview LIMIT " + PREVIEW_ROW_LIMIT;
    }

    /**
     * The three-statement DuckDB filesystem lockdown, in the one order that actually restricts
     * anything — a local mirror of {@code DuckDbExecutor.lockDownFilesystem} (query-engine),
     * whose javadoc and {@code DuckDbLockdownTest} are the canonical explanation: an
     * {@code allowed_directories} list is an exemption list, not a restriction, and means nothing
     * until {@code enable_external_access=false} has taken the filesystem away first.
     *
     * <p>Mirrored rather than imported because aran-core cannot depend on an operator module (the
     * dependency runs the other way, and the operators are {@code runtimeOnly} in the
     * application), exactly as {@code ConstraintDiscoveryOperator} already mirrors it for the same
     * reason. The security-critical decision that IS shared — the {@link WrapperSqlValidator}
     * token screen — lives here in core and is reused by both. Re-verify all three copies against
     * {@code DuckDbLockdownTest} on any DuckDB engine bump.
     */
    static void lockDownFilesystem(java.sql.Statement stmt, java.nio.file.Path allowedDir)
            throws Exception {
        stmt.execute("SET allowed_directories=['"
                + Names.sqlLiteral(allowedDir.toAbsolutePath().toString()) + "']");
        stmt.execute("SET enable_external_access=false");
        stmt.execute("SET lock_configuration=true");
    }

    /** Best-effort removal of a preview's scratch directory and the one file in it. */
    private static void deletePreviewScratchDir(java.nio.file.@Nullable Path workDir) {
        if (workDir == null) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> entries =
                     java.nio.file.Files.list(workDir)) {
            for (java.nio.file.Path entry : entries.toList()) {
                java.nio.file.Files.deleteIfExists(entry);
            }
        } catch (Exception e) {
            log.warn("Could not clear wrapper-preview scratch directory {}: {}", workDir, e.toString());
        }
        try {
            java.nio.file.Files.deleteIfExists(workDir);
        } catch (Exception e) {
            log.warn("Could not delete wrapper-preview scratch directory {}: {}", workDir, e.toString());
        }
    }

    /**
     * The 409 for a preview of a dataset with no stored snapshot (J2-F12/J4-F13). Two different
     * situations share the missing id, and the sentence tells them apart: a table a federated
     * repository reads live never has a copy to preview, while an upload or import has one only
     * once it lands.
     *
     * <p>Object storage is excluded from "read live" although {@link SourceAccess#resolve} says
     * FEDERATED for an uncopied object: no path enrols a bucket object live yet (V35, UI-OD13 —
     * the picker says "connect only — upload files"), so such a dataset is an upload that has not
     * landed, and "query it through its integrated class" would send the user to a class that
     * does not exist. {@code aran-web/src/lib/liveSource.ts} makes the same call.
     */
    private static ConflictException noStoredSnapshot(DataRepository repository, Dataset dataset) {
        boolean live = !(repository instanceof ObjectStorageRepository)
                && SourceAccess.resolve(repository, dataset.name(), false) == AccessMode.FEDERATED;
        return new ConflictException(ConflictException.NO_SNAPSHOT, live
                ? "'" + dataset.name() + "' is read live from its source, so Aran holds no stored "
                        + "copy of it to preview. Query it through its integrated class instead."
                : "'" + dataset.name() + "' has no stored data yet — its upload or import has not "
                        + "finished. Try again once it is ready.");
    }

    /** Refuses a dataset name the repository already holds (the V7 {@code uq_dataset_repository_name}). */
    private void requireNameFree(TenantContext tenant, UUID repositoryId,
                                 @org.jspecify.annotations.Nullable DataRepository repository, String name) {
        boolean taken = datasetRepository.findAllByRepository(tenant, repositoryId).stream()
                .anyMatch(existing -> existing.name().equals(name));
        if (taken) {
            String where = repository != null ? "the repository '" + repository.name() + "'" : "this repository";
            throw new ConflictException(ConflictException.NAME_TAKEN,
                    "A dataset named '" + name + "' already exists in " + where + ". Choose another "
                            + "name — uploading a new version of an existing dataset is not "
                            + "supported yet.");
        }
    }

    public edu.upc.dtak.aran.core.domain.DatasetPreview previewRawDataset(AuthenticatedUser caller, UUID projectId, UUID repositoryId, UUID datasetId) {
        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        Dataset dataset = datasetRepository.findById(tenant, datasetId)
                .orElseThrow(() -> new IllegalArgumentException("dataset not found"));

        if (dataset.snapshotId() == null) {
            throw noStoredSnapshot(repository, dataset);
        }

        try {
            LakeClient.SnapshotBytes blob = lakeClient.downloadSnapshot(caller.tenantId(), dataset.snapshotId());

            // Parquet is binary — reading it as UTF-8 text yields garbage. Detect it by
            // its "PAR1" magic (present at both ends) or content type, and preview it via
            // DuckDB read_parquet instead of the text/CSV/JSON path below.
            if (isParquet(blob)) {
                return previewParquetRaw(blob.bytes());
            }

            String content = new String(blob.bytes(), java.nio.charset.StandardCharsets.UTF_8);

            java.util.List<String> headers = new java.util.ArrayList<>();
            java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();

            if (content.startsWith("{") || content.startsWith("[")) {
                headers.add("Raw JSON");
                String[] lines = content.split("\\r?\\n");
                for (int i = 0; i < Math.min(20, lines.length); i++) {
                    rows.add(java.util.List.of(lines[i]));
                }
                if (lines.length > 20) rows.add(java.util.List.of("... (truncated)"));
            } else {
                // Treat as text/csv/tsv
                String[] lines = content.split("\\r?\\n");
                if (lines.length > 0) {
                    String headerLine = lines[0];
                    String delim = ",";
                    if (headerLine.contains("\t")) delim = "\t";
                    else if (headerLine.contains(";")) delim = ";";
                    
                    headers = new java.util.ArrayList<>(java.util.List.of(headerLine.split(delim)));
                    for (int i = 1; i < Math.min(6, lines.length); i++) {
                        String[] parts = lines[i].split(delim, -1);
                        java.util.List<Object> row = new java.util.ArrayList<>();
                        for (String part : parts) row.add(part);
                        while(row.size() < headers.size()) row.add("");
                        rows.add(row);
                    }
                }
            }
            return new edu.upc.dtak.aran.core.domain.DatasetPreview(headers, rows);
        } catch (Exception e) {
            throw new PreviewFailedException("Failed to load raw dataset preview: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a dataset from a given repository, after verifying user access.
     */
    public void deleteDataset(AuthenticatedUser caller, UUID projectId, UUID repositoryId, UUID datasetId) {
        repositoryService.getRepository(caller, projectId, repositoryId);
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        // Model-side cleanup BEFORE the row delete (T1.8): reject the dataset's mappings, flag
        // the model for rebuild, clear the bootstrap's source graph. The model_source rows
        // themselves cascade with the row (V37). Without this, the next compile ships a class
        // over a dataset that no longer exists.
        integrationModels.detachDataset(tenant, projectId, datasetId);
        datasetRepository.delete(tenant, datasetId);
    }

    /**
     * {@link #harvestCatalog}, but for a call site that only has the generic {@link DataRepository}
     * — {@link #registerDataset} serves every repository kind, so it checks here rather than
     * requiring every caller to test {@code instanceof} itself.
     */
    private void harvestIfRelational(UUID tenantId, DataRepository repository) {
        if (repository instanceof RelationalDatabaseRepository relational) {
            harvestCatalog(tenantId, relational);
        }
    }

    /**
     * Best-effort relational catalog harvest (Phase 4B Task 5) — row counts, column NDV, and
     * declared foreign keys, feeding the query planner's DECLARED tier. Absent in a build with no
     * bootstrapping operator ({@link #harvestTrigger} empty), and a failure from the trigger itself
     * is caught and logged rather than propagated: harvest is a secondary, catalog-only signal that
     * feeds cost estimation, never a precondition a registration or a refresh should depend on. See
     * {@code RelationalCatalogHarvestTrigger}'s javadoc for the full best-effort contract this
     * try/catch is a second, defensive layer on top of.
     */
    private void harvestCatalog(UUID tenantId, RelationalDatabaseRepository relational) {
        harvestTrigger.ifPresent(trigger -> {
            try {
                trigger.harvest(new TenantContext(tenantId.toString()), relational);
            } catch (RuntimeException e) {
                log.warn("Catalog harvest for repository {} failed: {}", relational.id(), e.toString());
            }
        });
    }

    /** True when the snapshot bytes are Parquet — recognised by the {@code PAR1} magic or content type. */
    private static boolean isParquet(LakeClient.SnapshotBytes blob) {
        String ct = blob.contentType() == null ? "" : blob.contentType().toLowerCase(java.util.Locale.ROOT);
        if (ct.contains("parquet")) return true;
        byte[] b = blob.bytes();
        return b.length >= 4 && b[0] == 'P' && b[1] == 'A' && b[2] == 'R' && b[3] == '1';
    }

    /** Preview a parquet snapshot's first rows via an in-memory DuckDB {@code read_parquet}. */
    private edu.upc.dtak.aran.core.domain.DatasetPreview previewParquetRaw(byte[] bytes) throws Exception {
        java.io.File tempFile = java.io.File.createTempFile("snapshot_", ".parquet");
        java.util.List<String> headers = new java.util.ArrayList<>();
        java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
            fos.write(bytes);
        }
        try {
            String path = Names.sqlLiteral(tempFile.getAbsolutePath());
            try (java.sql.Connection conn = edu.upc.dtak.aran.core.util.DuckDbSession.open();
                 java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery(
                         "SELECT * FROM read_parquet('" + path + "') LIMIT 20")) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    headers.add(meta.getColumnName(i));
                }
                while (rs.next()) {
                    java.util.List<Object> row = new java.util.ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object v = rs.getObject(i);
                        row.add(v != null ? v.toString() : null);
                    }
                    rows.add(row);
                }
            }
        } finally {
            tempFile.delete();
        }
        return new edu.upc.dtak.aran.core.domain.DatasetPreview(headers, rows);
    }

    private char detectDelimiter(byte[] bytes) {
        int end = Math.min(bytes.length, 4096);
        String head = new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        int newline = head.indexOf('\n');
        String firstLine = newline >= 0 ? head.substring(0, newline) : head;
        if (firstLine.indexOf('\t') >= 0) return '\t';
        if (firstLine.indexOf(';') >= 0) return ';';
        return ',';
    }
}

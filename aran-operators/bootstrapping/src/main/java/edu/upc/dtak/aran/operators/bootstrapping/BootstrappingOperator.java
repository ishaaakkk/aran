package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ParameterSpec;
import edu.upc.dtak.aran.core.operator.ProgressReporter;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.service.JdbcDriverRegistry;
import edu.upc.dtak.aran.core.source.AccessMode;
import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.core.source.SourceAccess;
import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceConnectors;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.source.SourceSchema;
import edu.upc.dtak.aran.core.source.UnsupportedSourceException;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.drift.DriftImpact;
import edu.upc.dtak.aran.core.domain.drift.DriftReport;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import edu.upc.dtak.aran.core.domain.drift.FederatedSchemaProbe;
import edu.upc.dtak.aran.core.domain.drift.SchemaFingerprint;
import edu.upc.dtak.aran.core.domain.drift.SourceGraphFingerprinter;
import edu.upc.dtak.aran.core.domain.drift.StoredSchemaFingerprint;
import edu.upc.dtak.aran.core.service.SchemaDriftService;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Schema bootstrapping operator.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@code ping} — health check.</li>
 *   <li>{@code bootstrap} — extract an RDFS-typed schema with wrapper metadata
 *       (see {@link WrapperVocab}) and store it in Fuseki at
 *       {@code urn:aran:source:{datasetId}}, replacing any previous source graph
 *       for that dataset. The schema comes from the dataset's snapshot bytes in
 *       aran-lake, or — for a dataset that has no snapshot because its source is
 *       read where it lives (a federated relational table) — from the remote's
 *       own catalog over JDBC. Which one is not a parameter: it follows from
 *       whether the dataset has a snapshot and what
 *       {@code SourceAccess.resolve} says about its repository.</li>
 * </ul>
 *
 * <p>Parameters for {@code bootstrap}:
 * <ul>
 *   <li>{@code datasetId} (required) — UUID of the dataset.</li>
 *   <li>{@code format} (optional) — a source kind (e.g. {@code JSON}, {@code CSV},
 *       {@code PARQUET}) forcing a specific {@code SourceConnector}. If omitted, the
 *       connector is resolved from the snapshot's MIME type, falling back to the
 *       dataset name's extension.</li>
 * </ul>
 *
 * <p>Source kinds are pluggable: each is a {@code SourceConnector} discovered via Spring
 * (see {@code edu.upc.dtak.aran.core.source}). Adding a new kind does not change this operator.
 */
@Component
@NullMarked
public final class BootstrappingOperator implements Operator {

    private static final Logger log = LoggerFactory.getLogger(BootstrappingOperator.class);

    /**
     * Set on a result when the candidate schema was <em>not</em> written because applying it would
     * break something compiled into the active schema. {@code DatasetService} reads this to mark
     * the dataset {@code DRIFTED} rather than {@code READY} — nothing failed, so a failure result
     * would be a lie, but nothing was applied either.
     */
    public static final String APPLIED_KEY = "applied";

    private final LakeClient lakeClient;
    private final GraphStore graphStore;
    private final DatasetRepository datasetRepository;
    private final SourceConnectors sourceConnectors;
    private final DataRepositoryRepository dataRepositoryRepository;
    private final SchemaDriftService driftService;
    private final IntegrationModelRepository integrationModels;
    private final ObjectMapper objectMapper;

    private final edu.upc.dtak.aran.core.net.OutboundAddressGuard addressGuard;

    public BootstrappingOperator(LakeClient lakeClient,
                                 GraphStore graphStore,
                                 DatasetRepository datasetRepository,
                                 SourceConnectors sourceConnectors,
                                 DataRepositoryRepository dataRepositoryRepository,
                                 SchemaDriftService driftService,
                                 IntegrationModelRepository integrationModels,
                                 ObjectMapper objectMapper,
                                 edu.upc.dtak.aran.core.net.OutboundAddressGuard addressGuard) {
        this.lakeClient = lakeClient;
        this.graphStore = graphStore;
        this.datasetRepository = datasetRepository;
        this.sourceConnectors = sourceConnectors;
        this.dataRepositoryRepository = dataRepositoryRepository;
        this.driftService = driftService;
        this.integrationModels = integrationModels;
        this.objectMapper = objectMapper;
        this.addressGuard = addressGuard;
    }

    @Override
    public String getOperatorId() {
        return "bootstrapping";
    }

    @Override
    public String getDisplayName() {
        return "Bootstrapping";
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        return execute(request, ProgressReporter.NOOP);
    }

    @Override
    public OperatorResult execute(OperatorRequest request, ProgressReporter progress) {
        log.debug("Bootstrapping handling operation '{}' for tenant '{}'",
                request.operation(), request.tenantId());

        return switch (request.operation()) {
            case "ping" -> OperatorResult.success(Map.of(
                    "status", "ok",
                    "operator", "bootstrapping",
                    "message", "Bootstrapping service operational"
            ));
            case "bootstrap" -> bootstrap(request, progress);
            case "drift-check" -> driftCheck(request, progress);
            case "drift-probe" -> driftProbe(request, progress);
            default -> OperatorResult.failure(
                    "UNSUPPORTED_OPERATION",
                    "Bootstrapping does not support operation '" + request.operation() + "'"
            );
        };
    }

    /**
     * The candidate schema a bootstrap would install, plus everything the caller needs to decide
     * whether to install it. Produced without touching Fuseki or the dataset row — which is what
     * makes both the drift check and the non-destructive bootstrap possible.
     */
    private record Candidate(Dataset dataset, UUID datasetId, Model schema, String format,
                             int wrappersAttached, SchemaFingerprint fingerprint) {}

    /** Either a {@link Candidate} or the failure that stopped us getting one. */
    private record CandidateOrFailure(@org.jspecify.annotations.Nullable Candidate candidate,
                                      @org.jspecify.annotations.Nullable OperatorResult failure) {
        static CandidateOrFailure of(Candidate c) {
            return new CandidateOrFailure(c, null);
        }

        static CandidateOrFailure failed(OperatorResult f) {
            return new CandidateOrFailure(null, f);
        }
    }

    /**
     * Introspect the dataset's source into a schema graph, in memory — its lake snapshot, or the
     * live catalog when there is no snapshot to have (see {@link #liveDescriptor}).
     *
     * <p>Everything the old {@code bootstrap} did up to (but not including) the point where it
     * started writing. Splitting here is the whole basis of the drift work: the candidate can be
     * compared against the stored baseline before {@code clearGraph} destroys the evidence.
     */
    private CandidateOrFailure prepareCandidate(OperatorRequest request, ProgressReporter progress) {
        String datasetIdParam = request.parameters().get("datasetId");
        if (datasetIdParam == null || datasetIdParam.isBlank()) {
            return CandidateOrFailure.failed(
                    OperatorResult.failure("MISSING_PARAMETER", "datasetId is required"));
        }

        UUID datasetId;
        try {
            datasetId = UUID.fromString(datasetIdParam);
        } catch (IllegalArgumentException e) {
            return CandidateOrFailure.failed(
                    OperatorResult.failure("INVALID_PARAMETER", "datasetId must be a UUID"));
        }

        TenantContext tenant = new TenantContext(request.tenantId());
        Optional<Dataset> dsOpt = datasetRepository.findById(tenant, datasetId);
        if (dsOpt.isEmpty()) {
            return CandidateOrFailure.failed(OperatorResult.failure(
                    "DATASET_NOT_FOUND", "Dataset " + datasetId + " not found for tenant"));
        }
        Dataset dataset = dsOpt.get();

        SourceDescriptor descriptor;
        if (dataset.snapshotId() == null) {
            // No bytes in the lake. That is a dead end for a file source and the normal state of a
            // live one, so the question is not "where are the bytes" but "can this source be
            // introspected where it stands" — V45.
            SourceDescriptor live = liveDescriptor(tenant, dataset, datasetId);
            if (live == null) {
                return CandidateOrFailure.failed(OperatorResult.failure(
                        "NO_SNAPSHOT",
                        "Dataset " + datasetId + " has no snapshot — upload data before bootstrapping"));
            }
            progress.stage("reading the live schema");
            descriptor = live;
        } else {
            // Pull bytes from the lake (presigned-URL roundtrip handled inside the client).
            progress.stage("downloading snapshot");
            LakeClient.SnapshotBytes blob;
            try {
                blob = lakeClient.downloadSnapshot(UUID.fromString(tenant.value()), dataset.snapshotId());
            } catch (RuntimeException e) {
                return CandidateOrFailure.failed(
                        OperatorResult.failure("LAKE_DOWNLOAD_FAILED", e.getMessage()));
            }

            // Resolve the connector: explicit 'format' parameter > snapshot content-type >
            // filename extension hint. Adding a source kind is a new SourceConnector — this
            // operator never changes.
            //
            // F14: the hint MUST be the originally
            // uploaded file's name (e.g. "venues.parquet"), passed through as the
            // 'filenameHint' request parameter by the caller that still has it — NOT
            // dataset.name(), the user-chosen display label, which routinely carries no
            // extension at all ("venues"). Falls back to dataset.name() when the caller
            // has no file to have named (e.g. the table-import path), preserving the
            // pre-fix behaviour for that case rather than passing an empty hint.
            String requestedHint = request.parameters().get("filenameHint");
            String filenameHint = (requestedHint == null || requestedHint.isBlank())
                    ? dataset.name() : requestedHint;
            descriptor = SourceDescriptor.ofFile(
                    datasetId.toString(), dataset.name(), dataset.snapshotId(),
                    blob.contentType(), filenameHint, request.parameters().get("format"),
                    blob.bytes());
        }

        SourceConnector connector;
        try {
            connector = sourceConnectors.forSource(descriptor);
        } catch (UnsupportedSourceException | IllegalStateException e) {
            return CandidateOrFailure.failed(OperatorResult.failure(
                    "UNKNOWN_FORMAT",
                    "Could not determine source format. Pass 'format' parameter (JSON, CSV or PARQUET)."));
        }
        String format = connector.sourceKind();

        progress.stage("extracting schema");
        Model schema;
        try {
            SourceSchema result = connector.introspect(descriptor);
            schema = result.rdfs();
        } catch (Exception e) {
            log.error("Bootstrapping failed for dataset {}", datasetId, e);
            return CandidateOrFailure.failed(
                    OperatorResult.failure("BOOTSTRAP_FAILED", e.getMessage()));
        }

        // Attach one wrapper-SQL triple per rdfs:Class so the schema graph is
        // self-describing and carries wrappers through the integration union.
        progress.stage("generating wrappers");
        int wrappersAttached = 0;
        try {
            wrappersAttached = WrapperGenerator.attachPerClassWrappers(schema);
        } catch (Exception ex) {
            log.warn("Failed to attach per-class wrappers for dataset {}", datasetId, ex);
        }

        return CandidateOrFailure.of(new Candidate(dataset, datasetId, schema, format, wrappersAttached,
                SourceGraphFingerprinter.of(schema, datasetId.toString())));
    }

    /**
     * A descriptor for a dataset that has no snapshot because it never will — the source is read
     * where it lives — or {@code null} when this dataset is simply missing its bytes.
     *
     * <p>Returning {@code null} rather than a failure is the point: only a source Aran can identify
     * as live earns the live path, and everything else must still get the {@code NO_SNAPSHOT}
     * answer it has always got. The gate is {@link SourceAccess#resolve} — the same function the
     * query engine's resolver and the plan view ask — so a dataset can never be bootstrapped as
     * live and then served MATERIALIZED, or the reverse.
     *
     * <p><b>Relational and Iceberg, deliberately not object storage.</b> Object storage is the
     * remaining twin case (V35: {@code SourceDescriptor.ofObjectStorage} has no production call
     * site, so an {@code s3://} object cannot become a source class either) and belongs as a
     * sibling branch here, reached the same way through {@code SourceAccess.resolve}. It is not
     * wired here because the relational branch is proven against Postgres and nothing else; adding
     * it without its own acceptance pass would be a claim, not a feature. The Iceberg branch has
     * the same caveat: its introspection is control-plane-only and unit-tested, and no live
     * catalog has been run through this path yet.
     */
    private @Nullable SourceDescriptor liveDescriptor(TenantContext tenant, Dataset dataset,
                                                      UUID datasetId) {
        DataRepository repo = dataRepositoryRepository
                .findById(tenant, dataset.repositoryId()).orElse(null);
        if (repo instanceof edu.upc.dtak.aran.core.domain.IcebergRestRepository iceberg
                && SourceAccess.resolve(repo, dataset.name()) == AccessMode.FEDERATED) {
            // Screened like the relational branch below: the catalog URI is the outbound target
            // introspection is about to dial (the data plane is not dialed at all — the connector
            // reads the schema from the catalog's own LoadTableResponse).
            addressGuard.checkUri(iceberg.catalogUri());
            // dataset.name() IS the namespace.table identifier — the same value the resolver hands
            // to loadTable at query time, so introspection and execution cannot read different
            // tables (the invariant the relational branch pins for postgres_scan).
            return SourceDescriptor.ofIceberg(
                    datasetId.toString(), dataset.name(), null,
                    new SourceDescriptor.Iceberg(
                            iceberg.catalogUri(), iceberg.warehouse(), iceberg.token(),
                            dataset.name(), iceberg.provider(), iceberg.endpoint(),
                            iceberg.accessKey(), iceberg.secretKey(), iceberg.region()));
        }
        if (!(repo instanceof RelationalDatabaseRepository relational)
                || SourceAccess.resolve(repo, dataset.name()) != AccessMode.FEDERATED) {
            return null;
        }
        // The URL template, not a hand-rolled string: the connection test, the table extract and
        // the catalog harvest all build theirs from JdbcDriverRegistry, and a fourth spelling here
        // is the kind of copy that drifts into introspecting a different database from the one
        // queries reach.
        Optional<JdbcDriverRegistry.DriverMetadata> meta = JdbcDriverRegistry.get(relational.engine());
        if (meta.isEmpty()) {
            // No driver coordinates for this engine, so nothing could open the connection anyway.
            log.warn("Dataset {} is federated over engine '{}', which has no registered JDBC driver",
                    datasetId, relational.engine());
            return null;
        }
        // Screen the target before a federated descriptor is built for it, so bootstrap refuses
        // an internal/metadata host the same way the registration path does (sec-audit F-S1-1).
        addressGuard.checkHost(relational.host());

        String url = meta.get().urlTemplate()
                .replace("{host}", relational.host())
                .replace("{port}", String.valueOf(relational.port()))
                .replace("{database}", relational.database());

        // dataset.name() IS the remote relation — the same value FederatedScanExpression
        // interpolates into the attached catalog's table reference at query time, so introspection and execution cannot
        // read different tables.
        //
        // No explicit kind, and the request's 'format' is deliberately NOT forwarded: format
        // detection is a question about bytes, and there are none. The connection alone decides
        // the connector, so honouring format=CSV here would only let a caller route a database
        // into a file connector that then fails on the missing bytes.
        return SourceDescriptor.ofJdbc(
                datasetId.toString(), dataset.name(), null,
                new SourceDescriptor.Connection(url, relational.username(), relational.password(),
                        relational.schema(), dataset.name(), relational.engine()));
    }

    /**
     * Install a dataset's source schema, unless doing so would break something.
     *
     * <p>Re-bootstrapping used to be unconditionally destructive: it cleared the dataset's named
     * graph and wrote the new one, so a renamed column took the mappings that referenced it with
     * no warning and no way back. Now the candidate is compared against the stored baseline first,
     * and a breaking result leaves the existing graph exactly where it is.
     *
     * <p>Pass {@code force=true} to apply anyway. That is a legitimate choice — the user may have
     * renamed the column deliberately and intend to repair the mappings — so the gate reports and
     * defers rather than refusing outright.
     */
    private OperatorResult bootstrap(OperatorRequest request, ProgressReporter progress) {
        CandidateOrFailure prepared = prepareCandidate(request, progress);
        if (prepared.failure() != null) {
            return prepared.failure();
        }
        Candidate candidate = prepared.candidate();
        TenantContext tenant = new TenantContext(request.tenantId());
        UUID datasetId = candidate.datasetId();

        progress.stage("checking for schema drift");
        DriftReport report = driftService.check(tenant, datasetId, candidate.fingerprint(),
                StoredSchemaFingerprint.CaptureMethod.SNAPSHOT);
        boolean force = Boolean.parseBoolean(request.parameters().getOrDefault("force", "false"));

        if (report.breaking() && !force) {
            // Nothing is written — not the graph, not the fingerprint. The dataset keeps working
            // against its previous schema while a human decides.
            log.warn("Bootstrap of dataset {} withheld: {} breaking change(s) against the active schema",
                    datasetId, report.countOf(edu.upc.dtak.aran.core.domain.drift.DriftSeverity.BREAKING));
            markDrifted(tenant, candidate.dataset());
            progress.stage("blocked by schema drift");
            return OperatorResult.success(driftPayload(datasetId, report, false));
        }

        // Replace any previous source graph for this dataset — bootstrapping is idempotent.
        progress.stage("writing source graph");
        String graphUri = BootstrapEngine.sourceGraphUri(datasetId.toString());
        graphStore.clearGraph(tenant, graphUri);
        graphStore.addTriples(tenant, graphUri, candidate.schema());

        // Cache the graph JSON on the dataset record
        try {
            String graphJson = edu.upc.dtak.aran.core.service.SourceGraphSerializer.serialize(
                    datasetId.toString(), candidate.schema());
            Dataset dataset = candidate.dataset();
            Dataset datasetWithGraph = new Dataset(
                    dataset.id(),
                    dataset.repositoryId(),
                    dataset.name(),
                    dataset.description(),
                    dataset.snapshotId(),
                    dataset.columns(),
                    graphJson,
                    DatasetStatus.READY
            );
            datasetRepository.save(tenant, datasetWithGraph);
        } catch (Exception e) {
            log.error("Failed to cache source graph JSON on dataset {}", datasetId, e);
        }

        reseedUneditedWrappers(tenant, datasetId, candidate.schema());

        // Only now — the candidate is live, so it is the shape the next check must compare against.
        // Recording earlier would move the baseline for a change that never took effect.
        try {
            driftService.record(tenant, datasetId, candidate.fingerprint(),
                    candidate.dataset().snapshotId(), StoredSchemaFingerprint.CaptureMethod.SNAPSHOT);
        } catch (RuntimeException e) {
            // A missing fingerprint costs the NEXT check its baseline; it does not invalidate the
            // bootstrap that just succeeded, so it must not fail the job.
            log.error("Failed to record schema fingerprint for dataset {}", datasetId, e);
        }

        log.info("Bootstrapped dataset {} ({} format) into graph {} — {} triples",
                datasetId, candidate.format(), graphUri, candidate.schema().size());

        progress.stage("done");
        Map<String, String> payload = new LinkedHashMap<>(driftPayload(datasetId, report, true));
        payload.put("graphUri", graphUri);
        payload.put("format", candidate.format());
        payload.put("tripleCount", String.valueOf(candidate.schema().size()));
        payload.put("wrappersAttached", String.valueOf(candidate.wrappersAttached()));
        payload.put("turtle", serializeTurtle(candidate.schema()));
        return OperatorResult.success(payload);
    }

    /**
     * Report what a bootstrap would change, writing neither the graph nor the fingerprint.
     *
     * <p>Safe to run before deciding, and the thing the UI calls to show a drift badge without
     * committing the user to anything. One write does happen: a BREAKING report marks the dataset
     * {@code DRIFTED}, so the badge is there for everyone, not only for whoever ran the check.
     */
    private OperatorResult driftCheck(OperatorRequest request, ProgressReporter progress) {
        CandidateOrFailure prepared = prepareCandidate(request, progress);
        if (prepared.failure() != null) {
            return prepared.failure();
        }
        Candidate candidate = prepared.candidate();
        progress.stage("comparing against the recorded baseline");
        TenantContext tenant = new TenantContext(request.tenantId());
        DriftReport report = driftService.check(tenant,
                candidate.datasetId(), candidate.fingerprint(),
                StoredSchemaFingerprint.CaptureMethod.SNAPSHOT);
        if (report.breaking()) {
            markDrifted(tenant, candidate.dataset());
        }
        progress.stage("done");
        return OperatorResult.success(driftPayload(candidate.datasetId(), report, false));
    }

    /**
     * Check a <em>live</em> federated table for drift.
     *
     * <p>The case with no ingest to hook: a {@code postgres_scan} or an attached MySQL catalog
     * reads the remote at query time, so nothing re-bootstraps and no snapshot is taken. A column
     * that changes type there gives silently wrong aggregates with no error anywhere; the only way
     * to notice is to look, which is what this does.
     *
     * <p>Unlike the snapshot gate there is nothing to withhold — the change is <em>already</em>
     * live. So a breaking result is recorded as a dataset status and deliberately <b>not</b>
     * baselined: re-baselining would silence the alarm on the next probe while the breakage is
     * still there. A clean result advances the baseline as normal.
     */
    private OperatorResult driftProbe(OperatorRequest request, ProgressReporter progress) {
        String datasetIdParam = request.parameters().get("datasetId");
        if (datasetIdParam == null || datasetIdParam.isBlank()) {
            return OperatorResult.failure("MISSING_PARAMETER", "datasetId is required");
        }
        UUID datasetId;
        try {
            datasetId = UUID.fromString(datasetIdParam);
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", "datasetId must be a UUID");
        }

        TenantContext tenant = new TenantContext(request.tenantId());
        Optional<Dataset> dsOpt = datasetRepository.findById(tenant, datasetId);
        if (dsOpt.isEmpty()) {
            return OperatorResult.failure("DATASET_NOT_FOUND",
                    "Dataset " + datasetId + " not found for tenant");
        }
        Dataset dataset = dsOpt.get();

        Optional<DataRepository> repoOpt =
                dataRepositoryRepository.findById(tenant, dataset.repositoryId());
        if (repoOpt.isEmpty()
                || !(repoOpt.get() instanceof RelationalDatabaseRepository repo)
                || !repo.federated()) {
            return OperatorResult.failure("NOT_FEDERATED",
                    "Dataset " + datasetId + " is not backed by a federated relational repository. "
                            + "Snapshot-backed datasets are checked by 'drift-check' instead.");
        }
        FederatedScan scan = FederatedScanExpression.forRepository(repo, dataset.name());
        if (scan == null) {
            return OperatorResult.failure("NOT_FEDERATED",
                    "No federated scanner for engine '" + repo.engine() + "'.");
        }

        progress.stage("reading the live schema");
        List<FederatedSchemaProbe.ProbedColumn> columns;
        try {
            columns = LiveSchemaProbe.describe(scan);
        } catch (FederatedSchemaProbe.ProbeUnavailableException e) {
            // Unreachable is not drifted. Reporting it as drift would fire a breaking alarm every
            // time the remote blinked, and people would stop reading them.
            return OperatorResult.failure("PROBE_UNAVAILABLE", e.getMessage());
        }

        String classIri = BootstrapEngine.sourceClassIri(datasetId.toString(), dataset.name());
        SchemaFingerprint candidate = FederatedSchemaProbe.fingerprint(
                datasetId.toString(), classIri, repo.engine(), columns);

        progress.stage("comparing against the recorded baseline");
        DriftReport report = driftService.check(tenant, datasetId, candidate,
                StoredSchemaFingerprint.CaptureMethod.LIVE_PROBE);

        if (report.breaking()) {
            log.warn("Live federated source for dataset {} has drifted: {}", datasetId, summarize(report));
            markDrifted(tenant, dataset);
        } else {
            try {
                driftService.record(tenant, datasetId, candidate, null,
                        StoredSchemaFingerprint.CaptureMethod.LIVE_PROBE);
            } catch (RuntimeException e) {
                log.error("Failed to record live probe fingerprint for dataset {}", datasetId, e);
            }
        }

        progress.stage("done");
        Map<String, String> payload = new LinkedHashMap<>(driftPayload(datasetId, report, false));
        payload.put("columnCount", String.valueOf(columns.size()));
        payload.put("engine", repo.engine());
        return OperatorResult.success(payload);
    }

    /**
     * Push the freshly written {@code nx:wrapperSql} into every <em>unedited</em> enrolled wrapper
     * for this dataset (V47), here — where the new answer exists — rather than only on the paths
     * that happen to run through {@code DatasetService.runBootstrapPipeline}.
     *
     * <p><b>Why this is not redundant with that call.</b> V47 re-seeded from the pipeline, which
     * upload, import and refresh all pass through. A <em>federated</em> dataset passes through none
     * of them: it is created by {@code registerDataset} (no job, no pipeline) and its only bootstrap
     * is the raw operator invoke, which is what {@code AlignmentsView}'s "Run Bootstrapping
     * Operator" submits. Without this call, a re-bootstrap over a changed remote schema regenerated
     * the graph correctly and the next compile pushed the enrolment-time copy straight back over it
     * ({@code SchemaIntegrationOperator.applyWrapperOverrides}) — V47's binder error for a renamed
     * column, and a silently missing column for an added one, on a source kind V47 could not have
     * seen because none could exist. The same gap existed for a <em>file</em> dataset
     * re-bootstrapped from that button; this closes both, because the graph write is the one event
     * every bootstrap has.
     *
     * <p>The re-seed goes through {@link IntegrationModelRepository} rather than
     * {@code IntegrationModelService}: that service is constructed with the
     * {@code OperatorOrchestrator}, which is constructed with every {@code Operator} — including
     * this one — so consuming it here would be a constructor cycle. The port has no such edge, and
     * the guarantee worth having in one place (never overwrite a wrapper a person edited) is
     * enforced inside the repository method, not by its callers.
     *
     * <p>Best-effort, like the graph-JSON cache and the fingerprint record around it: the schema is
     * already live by the time this runs, so a failure here must degrade the next compile, not
     * fail a bootstrap that succeeded.
     */
    private void reseedUneditedWrappers(TenantContext tenant, UUID datasetId, Model schema) {
        try {
            Map<String, String> byClass = new LinkedHashMap<>();
            for (org.apache.jena.rdf.model.Statement st
                    : schema.listStatements(null, WrapperVocab.WRAPPER_SQL, (org.apache.jena.rdf.model.RDFNode) null)
                    .toList()) {
                if (st.getSubject().isURIResource() && st.getObject().isLiteral()) {
                    byClass.put(st.getSubject().getURI(), st.getString());
                }
            }
            if (byClass.isEmpty()) {
                return;
            }
            int reseeded = integrationModels.reseedUneditedWrappers(tenant, datasetId, byClass);
            if (reseeded > 0) {
                log.info("Re-seeded {} unedited wrapper(s) for dataset {} from the bootstrap that "
                        + "just wrote them", reseeded, datasetId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not re-seed unedited wrappers for dataset {} — a later compile may push"
                    + " a stale enrolment-time wrapper over the regenerated one", datasetId, e);
        }
    }

    /** Move the dataset to {@code DRIFTED}, preserving everything else about it. */
    private void markDrifted(TenantContext tenant, Dataset dataset) {
        try {
            datasetRepository.save(tenant, new Dataset(
                    dataset.id(), dataset.repositoryId(), dataset.name(), dataset.description(),
                    dataset.snapshotId(), dataset.columns(), dataset.graphJson(),
                    DatasetStatus.DRIFTED));
        } catch (RuntimeException e) {
            log.error("Failed to mark dataset {} DRIFTED", dataset.id(), e);
        }
    }

    /**
     * The drift half of an operator result. Flat strings because {@code OperatorResult.data()} is
     * a {@code Map<String,String>}; the full report rides along as JSON under {@code driftReport}
     * for callers that want the detail.
     */
    private Map<String, String> driftPayload(UUID datasetId, DriftReport report, boolean applied) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("datasetId", datasetId.toString());
        payload.put(APPLIED_KEY, String.valueOf(applied));
        payload.put("driftVerdict", report.verdict().name());
        payload.put("driftBaseline", report.isBaseline() ? "true" : "false");
        payload.put("driftUnchanged", String.valueOf(report.unchanged()));
        payload.put("driftChangeCount", String.valueOf(report.impacts().size()));
        payload.put("driftSummary", summarize(report));
        try {
            payload.put("driftReport", objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            log.warn("Failed to serialize drift report for dataset {}", datasetId, e);
        }
        return payload;
    }

    /** A one-line human summary, so a caller that reads nothing else still learns what happened. */
    private static String summarize(DriftReport report) {
        if (report.isBaseline()) {
            return "First fingerprint recorded for this source — nothing to compare against yet.";
        }
        if (report.unchanged()) {
            return "No change to the source schema.";
        }
        StringBuilder sb = new StringBuilder(report.impacts().size() + " change(s): ");
        for (DriftImpact impact : report.impacts()) {
            sb.append('[').append(impact.severity()).append("] ")
                    .append(impact.change().detail()).append(' ');
        }
        return sb.toString().trim();
    }

    @Override
    public OperatorManifest manifest() {
        return new OperatorManifest(getOperatorId(), getDisplayName(), List.of(
                new OperationSpec("ping", "Health check", false, List.of()),
                new OperationSpec("bootstrap",
                        "Generate source wrappers and initial GAV graph from a dataset's snapshot, "
                                + "or from the live catalog when the source is federated", true, List.of(
                        ParameterSpec.required("datasetId", "UUID of the dataset to bootstrap"),
                        ParameterSpec.optional("format",
                                "Force a source format (JSON, CSV, PARQUET); ignored for a "
                                        + "snapshot-less federated source, which has no bytes to detect",
                                null),
                        ParameterSpec.optional("filenameHint", "Filename hint for format detection", null),
                        ParameterSpec.optional("force",
                                "Apply the new schema even when it breaks mappings compiled into the "
                                        + "active schema", "false")
                )),
                new OperationSpec("drift-check",
                        "Report what re-bootstrapping would change, without writing anything", false, List.of(
                        ParameterSpec.required("datasetId", "UUID of the dataset to check"),
                        ParameterSpec.optional("format", "Force a source format (JSON, CSV, PARQUET)", null),
                        ParameterSpec.optional("filenameHint", "Filename hint for format detection", null)
                )),
                new OperationSpec("drift-probe",
                        "Check a live federated table's current schema against its recorded baseline",
                        false, List.of(
                        ParameterSpec.required("datasetId", "UUID of the federated dataset to probe")
                ))
        ));
    }

    private static String serializeTurtle(Model model) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            RDFDataMgr.write(out, model, Lang.TURTLE);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // The graph is already persisted; serialization is convenience only.
            return "";
        }
    }
}

package edu.upc.dtak.aran.operators.profiling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.WrapperSqlValidator;
import edu.upc.dtak.aran.core.source.AccessMode;
import edu.upc.dtak.aran.core.source.FederatedProfilingBinding;
import edu.upc.dtak.aran.core.source.SourceAccess;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ParameterSpec;
import edu.upc.dtak.aran.core.operator.ProgressReporter;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.Credentials;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Platform operator implementing dataset profiling capabilities using the FREYJA service.
 */
@Component
@NullMarked
public final class ProfilingOperator implements Operator {

    private static final Logger log = LoggerFactory.getLogger(ProfilingOperator.class);

    private final String freyjaUrl;
    private final String freyjaSecret;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final DatasetRepository datasetRepository;
    private final @org.jspecify.annotations.Nullable DataRepositoryRepository dataRepositoryRepository;
    private final @org.jspecify.annotations.Nullable IcebergCatalogClient icebergCatalog;

    /** The pre-federated-profiling shape, kept for tests that never touch the federated path. */
    public ProfilingOperator(
            String freyjaUrl,
            String freyjaSecret,
            ObjectMapper objectMapper,
            DatasetRepository datasetRepository) {
        this(freyjaUrl, freyjaSecret, objectMapper, datasetRepository,
                java.util.Optional.empty(), java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProfilingOperator(
            // Single property for the one FREYJA sidecar (join-discovery uses
            // the same key). Dev default lives in application.yml.
            @Value("${aran.freyja.url}") String freyjaUrl,
            @Value("${aran.freyja.secret:}") String freyjaSecret,
            ObjectMapper objectMapper,
            DatasetRepository datasetRepository,
            // Optional because they only serve federated profiling (federated=true): the
            // repository row supplies the scan credentials, and the Iceberg client — absent
            // in builds without the adapter on the classpath — the current metadata location.
            java.util.Optional<DataRepositoryRepository> dataRepositoryRepository,
            java.util.Optional<IcebergCatalogClient> icebergCatalog) {
        this.freyjaUrl = freyjaUrl.replaceAll("/+$", "");
        this.freyjaSecret = freyjaSecret;
        this.objectMapper = objectMapper;
        this.datasetRepository = datasetRepository;
        this.dataRepositoryRepository = dataRepositoryRepository.orElse(null);
        this.icebergCatalog = icebergCatalog.orElse(null);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getOperatorId() {
        return "profiling";
    }

    @Override
    public String getDisplayName() {
        return "PROFILING";
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        return execute(request, ProgressReporter.NOOP);
    }

    @Override
    public OperatorManifest manifest() {
        return new OperatorManifest(getOperatorId(), getDisplayName(), List.of(
                new OperationSpec("profile", "Profile dataset columns via freyja sidecar", true, List.of(
                        // Optional in the MANIFEST because OperatorInvocationController enforces
                        // required parameters from it, and a federated=true call carries none —
                        // execute() itself requires one of snapshotId / federated+datasetId.
                        ParameterSpec.optional("snapshotId",
                                "Lake snapshot identifier — required unless federated=true", null),
                        ParameterSpec.optional("varcharOnly", "Profile only varchar columns", "true"),
                        ParameterSpec.optional("maxConcurrentColumnQueries", "Max concurrent column queries", "4"),
                        ParameterSpec.optional("datasetId", "UUID to persist profiles to database", null),
                        ParameterSpec.optional("wrapperSql",
                                "When set, profile this wrapper's resolved output (forwarded to freyja as"
                                        + " wrapper_sql) instead of the raw snapshot", null),
                        ParameterSpec.optional("classIri",
                                "Source class IRI the wrapper output belongs to — stamped onto each"
                                        + " persisted profile alongside subject; required whenever"
                                        + " wrapperSql is set", null),
                        // S5-12: `subject` is deliberately NOT a parameter. It is derived from
                        // federated/wrapperSql (see deriveSubject) because it describes what was
                        // measured, which the operator knows and the caller only asserts. A
                        // caller that sends a DISAGREEING value is still refused rather than
                        // silently overridden — hence absent from the manifest, not rejected.
                        ParameterSpec.optional("federated",
                                "true → profile over the dataset's LIVE federated binding (the same"
                                        + " scan queries use): freyja executes the profiling SQL"
                                        + " against the source in place and only aggregates cross"
                                        + " the wire. Requires datasetId; snapshotId is ignored",
                                "false")
                ))
        ));
    }

    /**
     * S5-12 — the one definition of what a profiling run measured.
     *
     * <p>{@code federated} wins over {@code wrapperSql}: the wrapper is bound over the LIVE scan,
     * so the numbers decay with the source and are never key evidence, which is the property
     * {@link ColumnProfile#SUBJECT_FEDERATED_LIVE} carries and {@code WRAPPER_OUTPUT} does not.
     * {@code HARVESTED} is not reachable from here at all — it names a catalog harvest, not a
     * freyja measurement, and is written by {@code HarvestedColumnProfiles}.
     */
    private static String deriveSubject(boolean federated, boolean hasWrapperSql) {
        if (federated) return ColumnProfile.SUBJECT_FEDERATED_LIVE;
        if (hasWrapperSql) return "WRAPPER_OUTPUT";
        return ColumnProfile.SUBJECT_SNAPSHOT;
    }

    @Override
    public OperatorResult execute(OperatorRequest request, ProgressReporter progress) {
        log.info("Profiling Operator executing operation '{}' for tenant '{}'", request.operation(), request.tenantId());

        if (!"profile".equalsIgnoreCase(request.operation())) {
            return OperatorResult.failure(
                    "UNSUPPORTED_OPERATION",
                    "Profiling does not support operation '" + request.operation() + "'"
            );
        }

        boolean federated = "true".equalsIgnoreCase(request.parameters().get("federated"));
        String snapshotId = request.parameters().get("snapshotId");
        if (!federated && (snapshotId == null || snapshotId.isBlank())) {
            return OperatorResult.failure(
                    "MISSING_PARAMETER",
                    "Required parameter 'snapshotId' is missing or blank (or pass federated=true"
                            + " with datasetId)"
            );
        }

        boolean varcharOnly = !"false".equalsIgnoreCase(request.parameters().get("varcharOnly"));
        int maxConcurrent = 4;
        try {
            String maxConcurrentParam = request.parameters().get("maxConcurrentColumnQueries");
            if (maxConcurrentParam != null && !maxConcurrentParam.isBlank()) {
                maxConcurrent = Integer.parseInt(maxConcurrentParam);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid maxConcurrentColumnQueries parameter, defaulting to 4: {}", e.getMessage());
        }

        // Phase 3B (Task 3): profile a wrapper's resolved output instead of the raw snapshot.
        // wrapperSql/classIri never reach freyja except wrapperSql itself (as wrapper_sql, only
        // when non-blank — freyja's ProfileRequest is extra="forbid", so sending an unrecognised
        // field would 422 the whole call); classIri/subject are Aran-side bookkeeping, stamped
        // onto each persisted ColumnProfile below.
        String wrapperSqlParam = request.parameters().get("wrapperSql");
        String wrapperSql = (wrapperSqlParam != null && !wrapperSqlParam.isBlank()) ? wrapperSqlParam : null;
        String classIriParam = request.parameters().get("classIri");
        String classIri = (classIriParam != null && !classIriParam.isBlank()) ? classIriParam : null;

        // S5-12: the subject says what was MEASURED, which this operator knows and a caller can
        // only assert. Deriving it here closes a wrong-rows channel: AranStatistic's key detection
        // skips HARVESTED and FEDERATED_LIVE and then trusts exactlyUnique(), so live-source
        // numbers labelled SNAPSHOT become verified-key evidence that AranJoinRemoveRule may use
        // to eliminate a join. The operator is reachable over HTTP by any tenant member with a
        // FULL-scope credential, and OperatorInvocationController only checks that REQUIRED
        // parameters are present — never a parameter's content.
        String subject = deriveSubject(federated, wrapperSql != null);
        String subjectParam = request.parameters().get("subject");
        if (subjectParam != null && !subjectParam.isBlank() && !subject.equals(subjectParam.trim())) {
            // Refuse rather than override silently: a caller asking for a subject this run cannot
            // produce has a wrong model of what it is doing, and quietly correcting it would hide
            // that from the next reader as much as obeying it would mislabel the evidence.
            return OperatorResult.failure("INVALID_PARAMETER",
                    "'subject' is derived from what is profiled, not chosen: this run measures "
                            + subject + " (federated=" + federated + ", wrapperSql="
                            + (wrapperSql != null) + "), but the request asked for "
                            + subjectParam.trim() + ". Omit the parameter.");
        }

        // Security boundary, not defence-in-depth-for-its-own-sake: this operator is reachable
        // directly over HTTP by any authenticated tenant member (POST
        // /api/operators/profiling/invoke or /submit) — OperatorInvocationController's
        // validateParameters only checks that REQUIRED parameters are present, never a
        // parameter's content, and wrapperSql is optional, so nothing upstream of this line
        // screens it. wrapperSql is about to be forwarded to freyja (as wrapper_sql) and
        // substituted into a real query against a real, unrestricted DuckDB connection
        // (LakeQueryExecutor) — the exact class of SSRF/LFI WrapperSqlValidator exists to close
        // (a forbidden reader/scanner such as read_csv_auto/http_get, or a string literal in
        // table-reference position, which DuckDB's replacement scan turns into a reader with no
        // function call in the text at all). The lake's own /api/query screen is a cruder regex,
        // not this tokenizer, precisely because its docs assume "no caller-supplied SQL reaches
        // this endpoint" — wrapperSql breaks that assumption, so the screen belongs at this
        // boundary, independent of whatever a trusted caller (IntegrationModelService's compile
        // tail) may already have done before invoking this operator.
        if (wrapperSql != null) {
            String forbidden = WrapperSqlValidator.findForbiddenConstruct(wrapperSql);
            if (forbidden != null) {
                log.warn("Rejected profile request with forbidden construct in wrapperSql: {}", forbidden);
                return OperatorResult.failure(
                        "INVALID_WRAPPER_SQL",
                        "Forbidden construct in wrapper SQL: " + forbidden
                );
            }
        }

        // S5-12: a WRAPPER_OUTPUT row's columns are named by property IRI and are only ever read
        // back per class (MappingCatalogOperator's preference order; the merge key below). One with
        // no classIri is a generation nothing can address, and it collides with the dataset's
        // SNAPSHOT baseline, whose classIri is also null. Checked AFTER the security screen above
        // on purpose — a malicious wrapper must be refused as malicious, not as under-specified.
        if (wrapperSql != null && classIri == null) {
            return OperatorResult.failure("MISSING_PARAMETER",
                    "'classIri' is required when 'wrapperSql' is set — a wrapper-output profile is"
                            + " stored and read back per source class.");
        }

        try {
            Map<String, Object> bodyMap = new HashMap<>();
            bodyMap.put("tenant_id", request.tenantId());
            bodyMap.put("varchar_only", varcharOnly);
            bodyMap.put("max_concurrent_column_queries", maxConcurrent);
            if (wrapperSql != null) {
                bodyMap.put("wrapper_sql", wrapperSql);
            }
            if (federated) {
                // Federated profiling (Gap 2 follow-up, per the design ruling): profiling is a
                // suite of SQL over the wrapper-as-view, so it binds ${SNAPSHOT} exactly as a
                // query does — freyja executes the suite over the LIVE scan binding built below
                // and only aggregates cross the wire. No snapshot_id: nothing is staged to the
                // lake, ever, whatever the source's size.
                OperatorResult bindingFailure = putFederatedScan(request, bodyMap);
                if (bindingFailure != null) {
                    return bindingFailure;
                }
            } else {
                bodyMap.put("snapshot_id", snapshotId);
            }

            String requestBody = objectMapper.writeValueAsString(bodyMap);

            HttpRequest httpRequest = withFreyjaAuth(HttpRequest.newBuilder()
                    .uri(URI.create(freyjaUrl + "/operators/profile")))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMinutes(15)) // Profiling can be a bit slow on big datasets
                    .build();

            progress.stage("submitting to freyja");
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {
                // Scrubbed before it is logged or becomes a job's errorMessage: a federated
                // source's 502 carries DuckDB's refusal line, which names the whole DSN. freyja
                // redacts it too; this is the half Aran owns.
                String body = scrubbedFreyjaBody(httpResponse.body());
                log.error("FREYJA profiling failed with status {}: {}", httpResponse.statusCode(), body);
                return OperatorResult.failure(
                        "PROFILING_ERROR",
                        "FREYJA sidecar profiling failed with status " + httpResponse.statusCode() + ": " + body
                );
            }

            // Persist the profiling result in PostgreSQL if datasetId is supplied
            String datasetIdStr = request.parameters().get("datasetId");
            if (datasetIdStr != null && !datasetIdStr.isBlank()) {
                progress.stage("storing profiles");
                try {
                    UUID datasetId = UUID.fromString(datasetIdStr);
                    TenantContext tenant = new TenantContext(request.tenantId());
                    JsonNode root = objectMapper.readTree(httpResponse.body());
                    JsonNode columnsNode = root.get("columns");
                    // freyja names each column whose distribution query failed in the response's
                    // errors map and emits it scalar-only. Persisting such a column would let
                    // path(...).asDouble(0.0) below fabricate every missing distribution metric
                    // as a real-looking 0.0 feeding the CBO — skip it instead (audit PROFILING-4).
                    JsonNode errorsNode = root.get("errors");

                    List<ColumnProfile> columnProfiles = new ArrayList<>();
                    if (columnsNode != null && columnsNode.isArray()) {
                        for (JsonNode colNode : columnsNode) {
                            String columnName = colNode.get("column_name").asText();
                            if (errorsNode != null && errorsNode.has(columnName)) {
                                log.warn("Skipping column '{}' of dataset {}: freyja reported a "
                                                + "per-column failure ({}); its distribution metrics "
                                                + "are absent and must not persist as zeros",
                                        columnName, datasetIdStr,
                                        Credentials.scrub(errorsNode.get(columnName).asText()));
                                continue;
                            }
                            int columnIndex = colNode.get("column_index").asInt();
                            String sourceType = colNode.get("source_type").asText();
                            JsonNode m = colNode.get("metrics");
                            
                            if (m != null) {
                                List<String> commonValues = new ArrayList<>();
                                JsonNode cvNode = m.get("freq_word_containment");
                                if (cvNode != null && cvNode.isArray()) {
                                    for (JsonNode v : cvNode) {
                                        commonValues.add(v.asText());
                                    }
                                }
                                
                                List<String> commonValuesSoundex = new ArrayList<>();
                                JsonNode cvsNode = m.get("freq_word_soundex_containment");
                                if (cvsNode != null && cvsNode.isArray()) {
                                    for (JsonNode v : cvsNode) {
                                        commonValuesSoundex.add(v.asText());
                                    }
                                }

                                List<String> valueHashSample = new ArrayList<>();
                                JsonNode sampleNode = m.get("value_hash_sample");
                                if (sampleNode != null && sampleNode.isArray()) {
                                    for (JsonNode v : sampleNode) {
                                        valueHashSample.add(v.asText());
                                    }
                                }

                                ColumnProfile cp = new ColumnProfile(
                                        UUID.randomUUID(),
                                        datasetId,
                                        columnName,
                                        columnIndex,
                                        sourceType,
                                        m.path("cardinality").asLong(0),
                                        m.hasNonNull("entropy") ? m.get("entropy").asDouble() : null,
                                        m.path("uniqueness").asDouble(0.0),
                                        m.path("incompleteness").asDouble(0.0),
                                        m.path("frequency_avg").asDouble(0.0),
                                        m.path("frequency_min").asLong(0),
                                        m.path("frequency_max").asLong(0),
                                        m.path("frequency_sd").asDouble(0.0),
                                        m.path("frequency_iqr").asDouble(0.0),
                                        m.path("val_pct_min").asDouble(0.0),
                                        m.path("val_pct_max").asDouble(0.0),
                                        m.path("val_pct_std").asDouble(0.0),
                                        m.path("constancy").asDouble(0.0),
                                        m.path("len_max_word").asInt(0),
                                        m.path("len_min_word").asInt(0),
                                        m.path("len_avg_word").asDouble(0.0),
                                        m.path("number_words").asLong(0),
                                        m.path("words_cnt_max").asInt(0),
                                        m.path("words_cnt_min").asInt(0),
                                        m.path("words_cnt_avg").asDouble(0.0),
                                        m.path("words_cnt_sd").asDouble(0.0),
                                        m.hasNonNull("first_word") ? m.get("first_word").asText() : null,
                                        m.hasNonNull("last_word") ? m.get("last_word").asText() : null,
                                        m.path("is_empty").asInt(0) == 1,
                                        m.path("is_binary").asInt(0) == 1,
                                        commonValues,
                                        commonValuesSoundex,
                                        subject,
                                        classIri,
                                        valueHashSample
                                );
                                columnProfiles.add(cp);
                            }
                        }
                    }
                    
                    datasetRepository.findById(tenant, datasetId).ifPresent(dataset -> {
                        // Merge, don't replace: JpaDatasetRepository.save clears and rebuilds the
                        // WHOLE column collection from whatever list Dataset.columns() carries, so
                        // handing it only this batch would wipe every other (subject, classIri)
                        // generation the dataset already had — the SNAPSHOT baseline from bootstrap
                        // when this call is profiling one class's WRAPPER_OUTPUT, or another class's
                        // own WRAPPER_OUTPUT rows on the same multi-class dataset. A full replace was
                        // only ever correct while "profile" was called at most once per dataset;
                        // Phase 3B calls it once per (dataset, OPAQUE class) in addition to bootstrap's
                        // once-per-dataset snapshot profiling. Keep every existing profile whose
                        // (subject, classIri) differs from this batch's; this batch supersedes the
                        // rest (its own prior generation, if any — a re-profile must not accumulate).
                        //
                        // Known limitation (V33), deliberately not fixed
                        // here: this key has no wrapper-text/draft-version dimension. A compile that
                        // edits this class's wrapper and profiles it WITHOUT promoting the resulting
                        // draft overwrites the same (dataset, WRAPPER_OUTPUT, classIri) row the
                        // currently-active (already-promoted) catalog reads its cost evidence from —
                        // MappingCatalogOperator has no notion of which draft/version produced the
                        // profiles it finds here. Cost-input staleness only (a plan can go stale, a
                        // query never returns wrong rows — SourceView.wrapperSql() still comes from
                        // whatever graph is actually active), and out of scope for this task: closing
                        // it needs either a versioned key or moving the trigger to promotion, both a
                        // real design decision.
                        List<ColumnProfile> merged = new ArrayList<>();
                        for (ColumnProfile existing : dataset.columns()) {
                            boolean sameGeneration = subject.equals(existing.subject())
                                    && Objects.equals(classIri, existing.classIri());
                            if (!sameGeneration) {
                                merged.add(existing);
                            }
                        }
                        merged.addAll(columnProfiles);

                        // The full constructor: the 6-arg convenience one used here previously
                        // silently defaulted graphJson/bootstrapStatus to null on every profiling
                        // run, wiping them from the persisted row.
                        Dataset updated = new Dataset(
                                dataset.id(),
                                dataset.repositoryId(),
                                dataset.name(),
                                dataset.description(),
                                dataset.snapshotId(),
                                merged,
                                dataset.graphJson(),
                                dataset.bootstrapStatus()
                        );
                        datasetRepository.save(tenant, updated);
                        log.info("Successfully persisted {} column profiles (subject={}, classIri={}) for"
                                        + " dataset {} ({} total after merge)",
                                columnProfiles.size(), subject, classIri, datasetId, merged.size());
                    });
                } catch (Exception e) {
                    // A failed persist means the evidence did NOT land — callers that check
                    // result.success() (the compile tail above all) must learn that. Returning
                    // success here is what hid V40/V44's constraint collisions behind a
                    // WARN (audit PROFILING-5): a caller that had asked for profiles to
                    // be PERSISTED was told the run succeeded while the dataset kept whatever
                    // numbers the previous pass left. A caller for which profiling is best-effort
                    // (bootstrap's auto-profiling, IntegrationModelService's wrapper-output pass)
                    // already treats a failed result as a WARN and carries on, so nothing that
                    // should survive this stops working.
                    //
                    // Deliberately one catch over both halves, and worded for both: freyja's answer
                    // failing to PARSE loses exactly as much as the write failing to land, and a
                    // caller cannot act differently on the two. Narrowing it to the save alone
                    // would leave the parse branch reporting success on a dataset whose profiles
                    // never changed — the very shape this fix exists to stop.
                    log.error("Failed to parse and persist profiling results on dataset {}", datasetIdStr, e);
                    return OperatorResult.failure(
                            "PROFILE_PERSISTENCE_FAILED",
                            // The dataset id is IN the message, not only in the log: a compile
                            // profiles several datasets in one pass, and "persisting failed" that
                            // does not say which one sends the reader back to the log this fix
                            // exists to stop them needing.
                            "Profiling succeeded but persisting the profiles failed for dataset "
                                    + datasetIdStr + ": " + e.getMessage());
                }
            }

            progress.stage("done");
            return OperatorResult.success(Map.of(
                    "profile", httpResponse.body()
            ));

        } catch (Exception e) {
            log.error("Failed to invoke profiling operator", e);
            return OperatorResult.failure(
                    "PROFILING_INVOCATION_FAILED",
                    "Exception during profiling invocation: " + e.getMessage()
            );
        }
    }

    /**
     * Build the dataset's live scan binding and put it on the freyja request body as
     * {@code federated_scan}, or return the failure result explaining why it cannot be built.
     * Returns {@code null} on success (the body was filled in).
     *
     * <p>The binding is rebuilt per call from the same authorities the query resolver uses
     * ({@link FederatedProfilingBinding}) — for Iceberg that includes fetching the metadata
     * location from the catalog NOW, because a commit moves it, exactly like a query.
     *
     * <p>Credential hygiene: the binding's expression/setup can carry a DSN or keys. They go into
     * {@code bodyMap} (the freyja request — the same trust plane that already receives a DSN via
     * {@code LakeClient.startImport}) and nowhere else: not the logs, not the OperatorResult, not
     * an exception message (failures below name the dataset and the root cause's own message,
     * never a statement).
     */
    private @org.jspecify.annotations.Nullable OperatorResult putFederatedScan(
            OperatorRequest request, Map<String, Object> bodyMap) {
        String datasetIdStr = request.parameters().get("datasetId");
        if (datasetIdStr == null || datasetIdStr.isBlank()) {
            return OperatorResult.failure("MISSING_PARAMETER",
                    "federated=true requires 'datasetId'");
        }
        if (dataRepositoryRepository == null) {
            return OperatorResult.failure("FEDERATED_PROFILING_UNAVAILABLE",
                    "this build has no repository store wired — cannot resolve the dataset's"
                            + " federated binding");
        }
        TenantContext tenant = new TenantContext(request.tenantId());
        Dataset dataset;
        try {
            dataset = datasetRepository.findById(tenant, UUID.fromString(datasetIdStr)).orElse(null);
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", "datasetId must be a UUID");
        }
        if (dataset == null) {
            return OperatorResult.failure("DATASET_NOT_FOUND",
                    "Dataset " + datasetIdStr + " not found for tenant");
        }
        DataRepository repo = dataRepositoryRepository
                .findById(tenant, dataset.repositoryId()).orElse(null);
        // The same per-dataset signal the query resolver keys on — a dataset served from a lake
        // copy has exact snapshot profiling available and must use it, not a live read.
        if (SourceAccess.resolve(repo, dataset.name(), dataset.snapshotId() != null)
                != AccessMode.FEDERATED) {
            return OperatorResult.failure("NOT_FEDERATED",
                    "Dataset " + datasetIdStr + " is not served federated — profile its lake"
                            + " snapshot instead");
        }
        FederatedProfilingBinding binding;
        try {
            binding = FederatedProfilingBinding.forDataset(repo, dataset.name(), icebergCatalog);
        } catch (RuntimeException e) {
            return OperatorResult.failure("FEDERATED_BINDING_FAILED",
                    "Could not build the live binding for dataset " + datasetIdStr + ": "
                            + e.getMessage());
        }
        if (binding == null) {
            return OperatorResult.failure("FEDERATED_PROFILING_UNSUPPORTED",
                    "Dataset " + datasetIdStr + "'s repository kind has no live profiling"
                            + " binding");
        }
        bodyMap.put("federated_scan", Map.of(
                "expression", binding.expression(),
                "setup_sql", binding.setupSql()));
        return null;
    }

    /**
     * Adds the FREYJA bearer token when one is configured.
     *
     * <p>freyja enforces this only when its own {@code FREYJA_SHARED_SECRET} is set, so an
     * empty value here is valid and simply sends no header — that keeps a sidecar started
     * before the secret existed working. Configure both sides together.
     */
    private HttpRequest.Builder withFreyjaAuth(HttpRequest.Builder builder) {
        if (freyjaSecret != null && !freyjaSecret.isBlank()) {
            builder.header("Authorization", "Bearer " + freyjaSecret);
        }
        return builder;
    }

    /**
     * A failed call's body with every credential masked. The scrub runs over the JSON's decoded
     * strings, not its text: JSON writes libpq's {@code \'} escape as {@code \\'}, which reads as
     * an escaped backslash followed by the closing quote, so a scrub of the raw body would stop
     * mid-password. A body that is not JSON is scrubbed as it is.
     */
    private String scrubbedFreyjaBody(String body) {
        try {
            JsonNode tree = objectMapper.readTree(body);
            if (tree != null && !tree.isMissingNode()) {
                return objectMapper.writeValueAsString(scrubStrings(tree));
            }
        } catch (com.fasterxml.jackson.core.JacksonException notJson) {
            // falls through to the plain-text scrub
        }
        return Credentials.scrub(body);
    }

    private static JsonNode scrubStrings(JsonNode node) {
        if (node.isTextual()) {
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(Credentials.scrub(node.asText()));
        }
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                object.set(name, scrubStrings(object.get(name)));
            }
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, scrubStrings(array.get(i)));
            }
        }
        return node;
    }
}

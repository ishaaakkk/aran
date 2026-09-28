package edu.upc.dtak.aran.operators.constraintdiscovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.data.dataset.CSVDataset;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ParameterSpec;
import edu.upc.dtak.aran.core.operator.ProgressReporter;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.WrapperSqlValidator;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import edu.upc.lima.Scheduler;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * Operator executing LIMA's lattice-sampling denial constraint discovery.
 */
@Component
@NullMarked
public final class ConstraintDiscoveryOperator implements Operator {

    private static final Logger log = LoggerFactory.getLogger(ConstraintDiscoveryOperator.class);

    private final LakeClient lakeClient;
    private final DatasetRepository datasetRepository;
    private final ObjectMapper objectMapper;

    public ConstraintDiscoveryOperator(LakeClient lakeClient,
                                       DatasetRepository datasetRepository,
                                       ObjectMapper objectMapper) {
        this.lakeClient = lakeClient;
        this.datasetRepository = datasetRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getOperatorId() {
        return "constraint-discovery";
    }

    @Override
    public String getDisplayName() {
        return "Denial Constraint Discovery";
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        return execute(request, ProgressReporter.NOOP);
    }

    @Override
    public OperatorManifest manifest() {
        return new OperatorManifest(getOperatorId(), getDisplayName(), List.of(
                new OperationSpec("ping", "Health check", false, List.of()),
                new OperationSpec("discover", "Discover denial constraints over a dataset snapshot", true, List.of(
                        ParameterSpec.required("datasetId", "UUID of the dataset"),
                        ParameterSpec.optional("aprox", "Approximation threshold", "0.0001"),
                        ParameterSpec.optional("limit", "Row limit for sampling", null),
                        ParameterSpec.optional("wrapperSql",
                                "When set, discover over this wrapper's RESOLVED OUTPUT (references"
                                        + " ${SNAPSHOT}) instead of the raw snapshot", null),
                        ParameterSpec.optional("classIri",
                                "Source class IRI the wrapper output belongs to — echoed on the"
                                        + " result for traceability", null)
                ))
        ));
    }

    @Override
    public OperatorResult execute(OperatorRequest request, ProgressReporter progress) {
        log.debug("Constraint Discovery handling operation '{}' for tenant '{}'",
                request.operation(), request.tenantId());

        return switch (request.operation()) {
            case "ping" -> OperatorResult.success(Map.of(
                    "status", "ok",
                    "operator", "constraint-discovery",
                    "message", "Constraint Discovery service operational"
            ));
            case "discover" -> discover(request, progress);
            default -> OperatorResult.failure(
                    "UNSUPPORTED_OPERATION",
                    "Constraint Discovery does not support operation '" + request.operation() + "'"
            );
        };
    }

    private OperatorResult discover(OperatorRequest request, ProgressReporter progress) {
        String datasetIdParam = request.parameters().get("datasetId");
        if (datasetIdParam == null || datasetIdParam.isBlank()) {
            return OperatorResult.failure("MISSING_PARAMETER", "datasetId is required");
        }

        UUID datasetId;
        try {
            datasetId = UUID.fromString(datasetIdParam);
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", "datasetId must be a valid UUID");
        }

        double aprox = 0.0001;
        String aproxParam = request.parameters().get("aprox");
        if (aproxParam != null && !aproxParam.isBlank()) {
            try {
                aprox = Double.parseDouble(aproxParam);
            } catch (NumberFormatException e) {
                log.warn("Invalid aprox parameter, defaulting to 0.0001: {}", e.getMessage());
            }
        }

        Integer limit = null;
        String limitParam = request.parameters().get("limit");
        if (limitParam != null && !limitParam.isBlank()) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException e) {
                log.warn("Invalid limit parameter, defaulting to all rows: {}", e.getMessage());
            }
        }

        String classIri = trimToNull(request.parameters().get("classIri"));
        String wrapperSql = trimToNull(request.parameters().get("wrapperSql"));

        // Security screen — the fail-closed gate, run BEFORE any dataset lookup or lake access so
        // that naming an unknown dataset cannot skip it (the discipline QueryEngineOperator
        // .verifyWrapper states as "checked before the catalog is even read"). discover is
        // reachable directly over the generic /api/operators/{id}/invoke endpoint, whose
        // validateParameters only checks that REQUIRED parameters are present, never a parameter's
        // content — and wrapperSql is optional, so nothing upstream screens it. When present it is
        // about to be substituted into a real query against a real DuckDB connection below, which
        // is the exact SSRF/LFI class WrapperSqlValidator exists to close (a read_*/*_scan/http_get
        // call, or a string literal in table-reference position that DuckDB's replacement scan
        // reads as a file). This is the same Critical the reviewer caught in Task 3's
        // ProfilingOperator; it is screened at this boundary too, independent of any screen a
        // trusted caller may already have applied.
        if (wrapperSql != null) {
            String forbidden = WrapperSqlValidator.findForbiddenConstruct(wrapperSql);
            if (forbidden != null) {
                log.warn("Rejected discover request with forbidden construct in wrapperSql: {}", forbidden);
                return OperatorResult.failure("INVALID_WRAPPER_SQL",
                        "Forbidden construct in wrapper SQL: " + forbidden);
            }
        }

        TenantContext tenant = new TenantContext(request.tenantId());
        Optional<Dataset> dsOpt = datasetRepository.findById(tenant, datasetId);
        if (dsOpt.isEmpty()) {
            return OperatorResult.failure("DATASET_NOT_FOUND", "Dataset " + datasetId + " not found");
        }
        Dataset dataset = dsOpt.get();
        if (dataset.snapshotId() == null) {
            return OperatorResult.failure("NO_SNAPSHOT", "Dataset has no uploaded snapshot");
        }

        // When a wrapper is given, the discovery subject is its RESOLVED OUTPUT, not the raw
        // snapshot (Phase 3B §3.1). This path is resilient by contract — see its javadoc.
        if (wrapperSql != null) {
            return discoverOverWrapperOutput(tenant, dataset, datasetId, classIri, wrapperSql, aprox, limit, progress);
        }

        // 1. Download snapshot bytes from data lake
        progress.stage("downloading snapshot");
        LakeClient.SnapshotBytes blob;
        try {
            blob = lakeClient.downloadSnapshot(UUID.fromString(tenant.value()), dataset.snapshotId());
        } catch (Exception e) {
            log.error("Failed to download snapshot from lake for dataset {}", datasetId, e);
            return OperatorResult.failure("LAKE_DOWNLOAD_FAILED", "Failed to download snapshot: " + e.getMessage());
        }

        File tempFile;
        try {
            tempFile = File.createTempFile("aran-lima-" + datasetId + "-", ".csv");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(blob.bytes());
            }
        } catch (IOException e) {
            log.error("Failed to create temporary file for dataset {}", datasetId, e);
            return OperatorResult.failure("TEMP_FILE_FAILED", "Failed to cache snapshot: " + e.getMessage());
        }

        List<String> discoveredConstraints = new ArrayList<>();
        try {
            int totalRows = 0;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(blob.bytes()), StandardCharsets.UTF_8))) {
                br.readLine();
                while (br.readLine() != null) {
                    totalRows++;
                }
            }

            int nrow = totalRows;
            if (limit != null && limit > 0) {
                nrow = Math.min(limit, totalRows);
            }

            // <= 1, not <= 0: LIMA states its constraints over PAIRS of DISTINCT tuples, and
            // CSVDataset.sample draws the second one with `random.nextInt(size - 1)` — nextInt(0),
            // which throws, for a one-row table. A table with fewer than two rows has no pair to
            // state anything about, so an empty result is the answer, not a crash (CONSTRAINT_
            // DISCOVERY-11).
            if (nrow <= 1) {
                return OperatorResult.success(Map.of("constraints", "[]"));
            }

            // Schema inference plus the predicate lattice the sampler will then walk.
            progress.stage("building lattice");
            Scheduler.minGrad = aprox * 0.01;
            CSVDataset csvDataset = new CSVDataset(tempFile.getAbsolutePath(), nrow, 42L);
            
            log.info("LIMA schema inference for dataset {}: discovered {} columns", datasetId, csvDataset.schema.columns.length);
            for (edu.upc.data.schema.Column col : csvDataset.schema.columns) {
                log.info("  Column '{}': inferred type {}", col.name, col.type);
            }

            Scheduler scheduler = new Scheduler(csvDataset);

            progress.stage("sampling");
            discoveredConstraints = scheduler.populatePredicates();
            log.info("LIMA discovered {} denial constraints for dataset {}", discoveredConstraints.size(), datasetId);

        } catch (Exception e) {
            log.error("LIMA constraint discovery failed for dataset {}", datasetId, e);
            return OperatorResult.failure("DISCOVERY_FAILED", "Constraint discovery failed: " + e.getMessage());
        } finally {
            if (tempFile.exists()) {
                if (!tempFile.delete()) {
                    log.warn("Failed to delete temporary file {}", tempFile.getAbsolutePath());
                }
            }
        }

        String jsonResult;
        try {
            jsonResult = objectMapper.writeValueAsString(discoveredConstraints);
        } catch (Exception e) {
            log.error("Failed to serialize constraints", e);
            jsonResult = "[]";
        }

        progress.stage("done");
        return OperatorResult.success(Map.of(
                "constraints", jsonResult
        ));
    }

    /**
     * Discover denial constraints over the RESOLVED WRAPPER OUTPUT rather than the raw snapshot.
     *
     * <p>Mechanism — path (a) in the task brief, the smaller of the two options: export the
     * wrapper's output to a temp CSV via an in-process scratch DuckDB, then feed that CSV to LIMA's
     * <em>existing</em> {@link CSVDataset} path. LIMA's algorithm code ({@code edu.upc.lima.*}) and
     * the vendored dataset classes stay byte-for-byte untouched — the wrapper output simply becomes
     * the CSV the operator already knows how to discover over. (Path (b), a second
     * {@code CSVDataset}/{@code RelationalDataset} constructor fed from a JDBC result, would touch
     * vendored research code and re-implement the CSV parsing and type inference {@code CSVDataset}
     * already does from a file — strictly more surface for no gain.)
     *
     * <p>The scratch connection mirrors {@code QueryEngineOperator.verifyWrapper}'s safety exactly:
     * the {@code wrapperSql} token screen has already run in {@link #discover} (before any tenant
     * data was touched); here the snapshot is bound to {@code ${SNAPSHOT}} through an operator-built
     * read expression, the connection's filesystem is locked down to a single per-invocation
     * scratch directory, and only then is the wrapper's output COPYed out. Discovery stays read-only
     * with respect to tenant data throughout.
     *
     * <p><b>Resilient, but never silent.</b> Any runtime failure — the lake, DuckDB, the wrapper
     * itself, LIMA — returns {@code constraints: []} + a WARN rather than an operator failure, so
     * that a user who asks a whole model's worth of awkward wrappers for candidates gets partial
     * answers instead of a 500. But the result is marked {@code degraded=true} with a {@code reason}
     * ({@link #degradedResult}): the earlier version returned the SAME clean zero a
     * constraint-free class returns, so the UI toasted "discovery complete" on a lake outage
     * (CONSTRAINT_DISCOVERY-12). (Its javadoc also justified the swallow with a compile tail that
     * triggers this — no such caller exists. Discovery is user-triggered: {@code
     * IntegrationModelService.discoverConstraints}, from the Constraints tab's own button, is the
     * only production caller. The swallow is still right; the reason given for it was not.)
     *
     * <p>The security screen and the shared dataset/snapshot preconditions in {@link #discover}
     * remain explicit, loud failures — a forbidden wrapper or a bad datasetId is a deliberate
     * rejection, not a transient hiccup.
     */
    private OperatorResult discoverOverWrapperOutput(TenantContext tenant, Dataset dataset,
            UUID datasetId, @Nullable String classIri, String wrapperSql, double aprox,
            @Nullable Integer limit, ProgressReporter progress) {
        Path workDir = null;
        List<String> discovered;
        try {
            // A per-invocation scratch DIRECTORY, not the shared temp root: the filesystem lockdown
            // below allowlists exactly this directory, which holds ONLY this call's two files — the
            // downloaded snapshot (read) and the exported wrapper output (written by COPY). That is
            // a tighter boundary than allowlisting a shared work dir full of other tenants' files.
            workDir = Files.createTempDirectory("aran-lima-wrap-" + Names.sqlName(datasetId.toString()) + "-");

            progress.stage("downloading snapshot");
            Path snapshotFile = Files.createTempFile(workDir, "snapshot-", ".raw");
            String contentType = lakeClient.downloadSnapshotTo(
                    UUID.fromString(tenant.value()), dataset.snapshotId(), snapshotFile);
            String readExpr = readExpressionFor(contentType, snapshotFile);

            progress.stage("resolving wrapper output");
            Path outputCsv = workDir.resolve("wrapper-output.csv");
            long totalRows = exportWrapperOutput(readExpr, wrapperSql, outputCsv, workDir);

            int nrow = (limit != null && limit > 0) ? (int) Math.min((long) limit, totalRows) : (int) totalRows;
            // <= 1 for the same reason as the raw path above — no tuple pair exists, so this is a
            // clean zero (never the degraded branch below: nothing failed).
            if (nrow <= 1) {
                log.info("Wrapper output for dataset {} (class {}) has fewer than two rows — no"
                        + " tuple pairs to discover over", datasetId, classIri);
                return wrapperOutputResult("[]", classIri);
            }

            // The EXISTING LIMA CSV path — unchanged constructor, untouched algorithm.
            progress.stage("building lattice");
            Scheduler.minGrad = aprox * 0.01;
            CSVDataset csvDataset = new CSVDataset(outputCsv.toAbsolutePath().toString(), nrow, 42L);

            progress.stage("sampling");
            Scheduler scheduler = new Scheduler(csvDataset);
            discovered = scheduler.populatePredicates();
            log.info("LIMA discovered {} denial constraints over the WRAPPER OUTPUT of dataset {} (class {})",
                    discovered.size(), datasetId, classIri);
        } catch (Exception e) {
            // Best-effort: never break a caller. See method javadoc — but SAY SO. Returning the
            // same clean `constraints: []` an honestly-constraint-free class returns made a lake
            // outage, a broken wrapper and a LIMA crash indistinguishable from "we looked and found
            // nothing", which is what the UI then told the user (CONSTRAINT_DISCOVERY-12).
            log.warn("Wrapper-output constraint discovery failed for dataset {} (class {}) — returning"
                    + " no constraints", datasetId, classIri, e);
            return degradedResult(classIri, e);
        } finally {
            deleteScratchDir(workDir);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(discovered);
        } catch (Exception e) {
            log.warn("Failed to serialize wrapper-output constraints for dataset {}", datasetId, e);
            json = "[]";
        }
        progress.stage("done");
        return wrapperOutputResult(json, classIri);
    }

    /**
     * Bind {@code ${SNAPSHOT}} and COPY the wrapper's output to {@code outputCsv} on a locked-down
     * scratch DuckDB connection, returning the number of data rows written. The same idiom as
     * {@code QueryEngineOperator.verifyWrapper}'s scratch connection: create the snapshot view,
     * lock the filesystem down, then run the user's (already-screened) SQL.
     *
     * <p>The driver is reached through {@link DuckDbSession}, which loads it by name over
     * {@code java.sql} only, so this module still needs no compile-time DuckDB dependency — the
     * driver is on aran-application's runtime classpath, exactly as
     * {@code aran-operators:query-engine} relies on it.
     */
    private static long exportWrapperOutput(String readExpr, String wrapperSql, Path outputCsv, Path allowedDir)
            throws Exception {
        try (Connection conn = DuckDbSession.open()) {
            String snapshotView = "src_snapshot";
            try (Statement st = conn.createStatement()) {
                // Operator-built read expression over the downloaded snapshot file. It is NOT user
                // text, so it is deliberately not subject to the wrapperSql screen; the wrapper only
                // ever sees the view name through ${SNAPSHOT}. Mirrors verify-wrapper's own
                // "CREATE OR REPLACE VIEW src_ AS SELECT * FROM <readExpr>".
                st.execute("CREATE OR REPLACE VIEW " + snapshotView + " AS SELECT * FROM " + readExpr);
                // The second layer under the token screen. readExpr is always a read over the
                // downloaded snapshot FILE (readExpressionFor never emits postgres_scan/mysql_scan),
                // so this connection never needs external network access — the lockdown therefore
                // always applies, and unlike verify-wrapper there is no federated branch to skip.
                lockDownFilesystem(st, allowedDir);
            }
            String boundSql = wrapperSql.replace("${SNAPSHOT}", snapshotView);
            String targetLiteral = Names.sqlLiteral(outputCsv.toAbsolutePath().toString());
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * FROM (" + boundSql + ")) TO '" + targetLiteral
                        + "' (FORMAT CSV, HEADER)");
            }
            // Exact row count from the written file (immune to embedded newlines, unlike a
            // line count) — the file is inside the allowlisted directory, so this read is
            // permitted under the lockdown. Same move materialize-oql makes after its COPY.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) FROM read_csv_auto('" + targetLiteral + "')")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * The three-statement DuckDB filesystem lockdown, in the one order that actually restricts
     * anything — a local mirror of {@code DuckDbExecutor.lockDownFilesystem} (query-engine), whose
     * javadoc and {@code DuckDbLockdownTest} are the canonical explanation and guarantee: an
     * {@code allowed_directories} list is an exemption list, not a restriction, and means nothing
     * until {@code enable_external_access=false} has taken the filesystem away first.
     *
     * <p>Mirrored rather than imported because the operators are independent siblings
     * (constraint-discovery depends only on aran-core), that method is package-private to
     * query-engine, and pulling in query-engine's Calcite/Jena transitive weight for three SET
     * statements is a poor trade. The shared, security-critical decision — the wrapperSql token
     * screen — IS reused from aran-core ({@link WrapperSqlValidator}); this is three engine-defined
     * statements with a pointer to their source of truth. Re-verify against {@code DuckDbLockdownTest}
     * on any DuckDB engine bump.
     */
    private static void lockDownFilesystem(Statement st, Path allowedDir) throws Exception {
        st.execute("SET allowed_directories=['"
                + Names.sqlLiteral(allowedDir.toAbsolutePath().toString()) + "']");
        st.execute("SET enable_external_access=false");
        st.execute("SET lock_configuration=true");
    }

    /**
     * The DuckDB read expression for a snapshot of the given content type. constraint-discovery has
     * always consumed CSV (the raw-snapshot path hands bytes straight to a CSV parser), so CSV is
     * the default; the content type the lake recorded disambiguates JSON and parquet snapshots.
     * Mirrors {@code SnapshotFormats} (query-engine) rather than depending on it — see
     * {@link #lockDownFilesystem} for why query-engine is not a dependency of this module.
     */
    private static String readExpressionFor(@Nullable String contentType, Path file) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String reader;
        if (ct.contains("parquet")) {
            reader = "read_parquet";
        } else if (ct.contains("json")) {
            reader = "read_json_auto";
        } else {
            // csv, tsv, text/plain, or unrecorded — CSV is this operator's native input, and
            // read_csv_auto sniffs the delimiter, so TSV is covered too.
            reader = "read_csv_auto";
        }
        return reader + "('" + Names.sqlLiteral(file.toAbsolutePath().toString()) + "')";
    }

    /** {@code {constraints, subject=WRAPPER_OUTPUT[, classIri]}} — the wrapper-path result shape. */
    private static OperatorResult wrapperOutputResult(String constraintsJson, @Nullable String classIri) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("constraints", constraintsJson);
        data.put("subject", "WRAPPER_OUTPUT");
        if (classIri != null) {
            data.put("classIri", classIri);
        }
        return OperatorResult.success(data);
    }

    /**
     * The wrapper-path result shape when discovery <em>could not run</em> — still a success with no
     * constraints (this path never breaks its caller), but carrying {@code degraded=true} and a
     * {@code reason} so the caller can tell an outage from a class that genuinely has no
     * constraints. Both zero results are otherwise byte-identical, which is the whole defect
     * (CONSTRAINT_DISCOVERY-12).
     *
     * <p>{@code reason} is the exception's own type and message — this runs server-side over
     * already-screened input (the wrapper text passed {@link WrapperSqlValidator} in {@link
     * #discover} before anything was touched), and its consumer is the person who clicked
     * "Discover constraints" on their own class, so a DuckDB parse error naming their own column is
     * exactly what they need to see rather than a generic "it failed".
     */
    private static OperatorResult degradedResult(@Nullable String classIri, Exception cause) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("constraints", "[]");
        data.put("subject", "WRAPPER_OUTPUT");
        if (classIri != null) {
            data.put("classIri", classIri);
        }
        data.put("degraded", "true");
        String message = cause.getMessage();
        data.put("reason", message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message);
        return OperatorResult.success(data);
    }

    /** Recursively delete a per-invocation scratch directory; best effort, never throws. */
    private static void deleteScratchDir(@Nullable Path dir) {
        if (dir == null) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    /* best effort */
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean up scratch directory {}", dir, e);
        }
    }

    private static @Nullable String trimToNull(@Nullable String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

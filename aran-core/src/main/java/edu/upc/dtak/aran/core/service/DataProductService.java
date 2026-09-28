package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.domain.cube.CubeRepository;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Lifecycle of data products (M8): create-with-validation, materialize-as-a-job,
 * stale-marking on schema promotion, and preview. A MATERIALIZED product previews its
 * snapshot; a VIRTUAL one (Phase 5) has none, so {@link #create} reaches its servable state by
 * planning the OQL instead of materializing, and {@link #preview} runs that OQL instead of
 * reading a file — see each method's own javadoc for the access-mode split.
 *
 * <p>Everything DuckDB-shaped runs inside the query-engine operator (core cannot
 * depend on DuckDB), reached through {@link OperatorOrchestrator} exactly the way
 * {@link IntegrationModelService} drives {@code compile}: {@code materialize-oql}
 * executes the product's OQL with the row cap disabled and COPYs the result to a
 * temp parquet; {@code preview-parquet} reads the first rows of a lake snapshot;
 * {@code rewrite-oql} plans an OQL query to SQL (and, since Phase 5, to a column shape)
 * without executing anything; {@code run-oql} plans and executes.
 * This service owns the lake upload (the {@link LakeClient} port is core-visible)
 * and the product row's state transitions.
 *
 * <p>A product over a federated dataset materializes from the <em>live</em> source
 * (no snapshot exists for it); this is stated in the product UI copy.
 */
@Service
@NullMarked
public class DataProductService {

    private static final Logger log = LoggerFactory.getLogger(DataProductService.class);

    /** Same content type the lake's materialize handler registers for parquet snapshots. */
    static final String PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet";

    private final DataProductRepository repository;
    private final GraphStore graphStore;
    private final OperatorOrchestrator orchestrator;
    private final JobService jobService;
    private final LakeClient lake;
    private final UsageService usage;
    private final LicenseService licenses;
    private final CubeRepository cubes;

    public DataProductService(DataProductRepository repository,
                              GraphStore graphStore,
                              OperatorOrchestrator orchestrator,
                              JobService jobService,
                              LakeClient lake,
                              UsageService usage,
                              LicenseService licenses,
                              CubeRepository cubes) {
        this.repository = repository;
        this.graphStore = graphStore;
        this.orchestrator = orchestrator;
        this.jobService = jobService;
        this.lake = lake;
        this.usage = usage;
        this.licenses = licenses;
        this.cubes = cubes;
    }

    /** A project's products, newest first. */
    public List<DataProduct> list(TenantContext tenant, UUID projectId) {
        return repository.findByProject(tenant, projectId);
    }

    /**
     * The tenant's products across every project, oldest first.
     *
     * <p>The JDBC {@code products} schema is the caller: a JDBC connection is authenticated as a
     * tenant and has no project in its tree, so the schema it browses is the tenant's whole
     * registry. {@link ProductCatalog} turns this list into table names.
     */
    public List<DataProduct> listAll(TenantContext tenant) {
        return repository.findAll(tenant);
    }

    /** @throws ResourceNotFoundException when the product does not exist (or is not visible) */
    public DataProduct get(TenantContext tenant, UUID productId) {
        return repository.findById(tenant, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Data product not found: " + productId));
    }

    /**
     * The product, when it belongs to {@code projectId}.
     *
     * <p>A product's URL names a project and a product id, and visibility is checked on the
     * project (S2-1). That check is only worth something if the product is then required to be
     * one of that project's — otherwise a project the caller can see is a door to every product
     * in the tenant by id. Not found rather than forbidden, for the same reason the project gate
     * answers that way.
     */
    public DataProduct get(TenantContext tenant, UUID projectId, UUID productId) {
        DataProduct product = get(tenant, productId);
        if (!product.projectId().equals(projectId)) {
            throw new ResourceNotFoundException("Data product not found: " + productId);
        }
        return product;
    }

    /**
     * Create a MATERIALIZED product (the pre-Phase-5 signature): delegates to the
     * {@link ProductAccessMode}-taking overload below, unchanged behavior for every existing
     * caller.
     *
     * @throws IllegalArgumentException when the OQL does not parse/plan, or when the
     *                                  tenant has no ACTIVE schema version to pin
     */
    public DataProduct create(TenantContext tenant, UUID projectId, String name,
                              @Nullable String description, String oql, @Nullable UUID actor) {
        return create(tenant, projectId, name, description, oql, actor,
                ProductAccessMode.MATERIALIZED);
    }

    /**
     * Create a product: validate the OQL through the query engine's {@code rewrite-oql}
     * (parse + plan, no execution) and pin the tenant's current ACTIVE schema version.
     *
     * <p>MATERIALIZED (unchanged): lands DRAFT, never touching the lake until
     * {@link #materialize}. VIRTUAL (Phase 5): {@code rewrite-oql}'s own planning already
     * produced the row shape — {@code rewrite-oql}'s {@code columns} entry, the same derivation
     * {@code ProductColumns} performs off the optimizer's row type — so there is nothing left
     * to run; the product is recorded {@link DataProduct#virtualReady servable} immediately,
     * with {@code snapshotId} staying {@code null}. Either way this method never calls the lake
     * or {@code materialize-oql} — that is the whole point of a VIRTUAL product, and it is also
     * simply not needed for MATERIALIZED, which has always deferred materializing to its own
     * explicit step.
     *
     * @throws IllegalArgumentException when the OQL does not parse/plan, or when the
     *                                  tenant has no ACTIVE schema version to pin
     * @throws ConflictException        when the project already has a product with this name
     */
    public DataProduct create(TenantContext tenant, UUID projectId, String name,
                              @Nullable String description, String oql, @Nullable UUID actor,
                              ProductAccessMode accessMode) {
        // Before planning, which is the slow part: a create retried after the browser gave up
        // waiting finds its own first attempt here and says so as a 409 (J6-F6), instead of
        // re-planning and then failing V18's unique key as a generic 500. The key stays the
        // backstop for a true race, and that too answers 409.
        String trimmedName = name.trim();
        boolean nameTaken = repository.findByProject(tenant, projectId).stream()
                .anyMatch(existing -> existing.name().equals(trimmedName));
        if (nameTaken) {
            throw new ConflictException(ConflictException.NAME_TAKEN,
                    "A data product named '" + trimmedName + "' already exists in this project. "
                            + "If an earlier save timed out, it may be that one — check the list "
                            + "before choosing another name.");
        }

        OperatorRequest validate = new OperatorRequest(
                tenant.value(), actor, "rewrite-oql", Map.of("oql", oql));
        OperatorResult result = orchestrator.execute("query-engine", validate);
        if (!result.success()) {
            throw new IllegalArgumentException("Invalid OQL: "
                    + (result.errorMessage() != null ? result.errorMessage() : result.errorCode()));
        }

        GraphVersion current = graphStore.currentVersion(tenant);
        if (current == null) {
            throw new IllegalArgumentException(
                    "No schema version to pin — promote an integrated schema first.");
        }
        if (!"ACTIVE".equals(current.status())) {
            throw new IllegalArgumentException(
                    "The current schema version is " + current.status()
                            + "; a data product pins an ACTIVE version — promote it first.");
        }

        DataProduct product = DataProduct.draft(
                UUID.randomUUID(), UUID.fromString(tenant.value()), projectId,
                name.trim(), description, oql, current.id(), actor, Instant.now(), accessMode);
        if (accessMode == ProductAccessMode.VIRTUAL) {
            product = product.virtualReady(result.data().get("columns"));
        }
        return repository.save(tenant, product);
    }

    /**
     * Materialize the product's OQL to a parquet snapshot in the lake, as a job
     * ({@code jobType="product:materialize"}). Re-materializing always runs the OQL
     * against the tenant's current ACTIVE schema (the {@code materialize-oql}
     * operation reads {@code catalogOperator.getCatalog(tenant)}, never
     * {@code product.schemaVersionId()}) — the pin is not consulted at execution
     * time. {@code schemaVersionId} only records which version the OQL was written
     * for, so {@link #markStaleForVersionChange} can flag the product STALE once the
     * active schema moves past it; re-pinning is creating a new product, v1.
     *
     * @throws IllegalArgumentException when the product is VIRTUAL — it is already servable
     *                                   (reached READY at create, Phase 5) and has no
     *                                   materialize step to run. Checked first, before any state
     *                                   change or lake I/O: {@link #runMaterialization} ends by
     *                                   calling {@link DataProduct#materialized}, which the
     *                                   compact constructor now rejects for a VIRTUAL product
     *                                   (it would carry a non-null snapshotId) — without this
     *                                   guard that exception would surface as a corrupted
     *                                   FAILED product plus an orphaned lake upload, not a clean
     *                                   rejection of the request that should never have run.
     */
    public UUID materialize(TenantContext tenant, UUID productId, @Nullable UUID actor) {
        DataProduct product = get(tenant, productId);
        if (product.accessMode() == ProductAccessMode.VIRTUAL) {
            throw new IllegalArgumentException(
                    "A VIRTUAL product has no materialize step — it is already servable.");
        }
        // Checked here rather than inside the job, so an over-quota tenant is told at the click
        // instead of watching a job start and fail. The size is unknown until the query runs, so
        // this asks only whether the tenant is already over.
        licenses.requireWithinStorageLimit(UUID.fromString(tenant.value()), 0);
        return jobService.submit(
                tenant, product.projectId(), "product:materialize",
                Map.of("productId", productId.toString()), actor,
                progress -> runMaterialization(tenant, productId, actor, progress)).id();
    }

    private Map<String, String> runMaterialization(TenantContext tenant, UUID productId,
                                                   @Nullable UUID actor,
                                                   edu.upc.dtak.aran.core.operator.ProgressReporter progress)
            throws Exception {
        DataProduct product = get(tenant, productId);
        repository.save(tenant, product.withStatus(DataProductStatus.MATERIALIZING));
        long started = System.currentTimeMillis();
        try {
            progress.stage("executing query");
            OperatorRequest request = new OperatorRequest(
                    tenant.value(), actor, "materialize-oql", Map.of("oql", product.oql()));
            OperatorResult result = orchestrator.execute("query-engine", request);
            if (!result.success()) {
                throw new JobFailureException(
                        result.errorCode() != null ? result.errorCode() : "MATERIALIZE_FAILED",
                        result.errorMessage() != null ? result.errorMessage() : "materialization failed");
            }
            Path parquet = Path.of(result.data().get("parquetPath"));
            long rowCount = Long.parseLong(result.data().get("rowCount"));
            // The result's own columns, as the engine described them. Recorded on the product so
            // the JDBC `products` schema can answer getColumns() from the registry instead of
            // downloading every snapshot to look at its header.
            String outputSchema = result.data().get("columns");
            // The version the engine actually planned against (UI-OD12): a refresh runs under
            // whatever is ACTIVE, never under schemaVersionId, so this — not the definition-time
            // pin — is what the rows mean. Read off the engine's own catalog, not re-read here,
            // so a promote landing mid-build cannot make the record disagree with the rows.
            UUID builtAgainst = parseUuidOrNull(result.data().get("schemaVersionId"));

            String snapshotId;
            long byteCount;
            try {
                progress.stage("uploading snapshot");
                byte[] bytes = Files.readAllBytes(parquet);
                byteCount = bytes.length;
                UUID tenantUuid = UUID.fromString(tenant.value());
                LakeClient.InitUploadResponse init =
                        lake.initUpload(tenantUuid, productId, bytes.length, PARQUET_CONTENT_TYPE);
                lake.uploadToS3(init.uploadUrl(), bytes, PARQUET_CONTENT_TYPE);
                snapshotId = lake.completeUpload(
                        tenantUuid, init.uploadId(), lake.computeSha256(bytes), bytes.length);
            } finally {
                try { Files.deleteIfExists(parquet); } catch (Exception ignore) { /* best effort */ }
            }

            // Recorded on success only, and after the upload, so `bytes` is the size actually
            // moved into the lake rather than the size we hoped to write. `actor` is null for a
            // scheduled refresh — that is the unattributed row on the admin screen, not a gap.
            usage.record(UUID.fromString(tenant.value()), actor, UsageKind.MATERIALIZE,
                    System.currentTimeMillis() - started, rowCount, byteCount);

            DataProduct ready = get(tenant, productId)
                    .materialized(snapshotId, rowCount, outputSchema, Instant.now(), builtAgainst);
            repository.save(tenant, ready);
            progress.stage("done");
            return Map.of("snapshotId", snapshotId, "rowCount", Long.toString(rowCount));
        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            repository.save(tenant, get(tenant, productId).failed(error));
            throw e;
        }
    }

    /**
     * Flip every READY MATERIALIZED product not pinned to the new ACTIVE version to STALE.
     * Called from both places a new ACTIVE version appears: promote
     * ({@code SourceGraphController.promoteDraft}) and rollback
     * ({@link SchemaVersionService#rollback}, which promotes internally). STALE is
     * information, not breakage — the product keeps serving its pinned snapshot.
     *
     * <p>A VIRTUAL product is skipped outright (Phase 5, ruling R1): it has no pinned snapshot
     * to go stale against — it reads whatever schema is ACTIVE at each read, so a version change
     * is exactly the freshness a virtual product promises, not a staleness event.
     */
    public void markStaleForVersionChange(TenantContext tenant) {
        GraphVersion current = graphStore.currentVersion(tenant);
        if (current == null) {
            return;
        }
        for (DataProduct product : repository.findByStatus(tenant, DataProductStatus.READY)) {
            if (product.accessMode() == ProductAccessMode.VIRTUAL) {
                continue;
            }
            if (!product.schemaVersionId().equals(current.id())) {
                repository.save(tenant, product.withStatus(DataProductStatus.STALE));
                log.info("Data product {} marked STALE (pinned {}, active now {})",
                        product.id(), product.schemaVersionId(), current.id());
            }
        }
    }

    /**
     * Preview the product's first rows. Returns the query-engine's result payload JSON
     * (headers/headerNames/rows/rowCount), shaped exactly like a workbench query result.
     *
     * <p>MATERIALIZED: reads the pinned snapshot ({@code preview-parquet}), unchanged.
     * VIRTUAL (Phase 5): there is no snapshot to read, so this runs the product's own OQL
     * through the live GAV pipeline instead ({@code run-oql}) — the same execution path a
     * virtual product's query-time semantics are defined by (always fresh, live denial-
     * constraint enforcement). {@code limit} is forwarded as {@code run-oql}'s page size, bounded
     * inside DuckDB: it used to be dropped here, so an agent asking a VIRTUAL product for 20 rows
     * received the engine's full 1,000-row page (V37 gap 3, audit J6-F11).
     *
     * @throws IllegalArgumentException when a MATERIALIZED product has never been materialized
     */
    public String preview(TenantContext tenant, UUID productId, int limit) {
        DataProduct product = get(tenant, productId);
        if (product.accessMode() == ProductAccessMode.VIRTUAL) {
            OperatorRequest request = new OperatorRequest(
                    tenant.value(), null, "run-oql",
                    Map.of("oql", product.oql(), "limit", Integer.toString(limit)));
            OperatorResult result = orchestrator.execute("query-engine", request);
            if (!result.success()) {
                throw new IllegalStateException("Preview failed: "
                        + (result.errorMessage() != null ? result.errorMessage() : result.errorCode()));
            }
            return result.data().get("result");
        }
        if (product.snapshotId() == null) {
            throw new IllegalArgumentException(
                    "Product has no materialized snapshot yet — materialize it first.");
        }
        OperatorRequest request = new OperatorRequest(
                tenant.value(), null, "preview-parquet",
                Map.of("snapshotId", product.snapshotId(), "limit", Integer.toString(limit)));
        OperatorResult result = orchestrator.execute("query-engine", request);
        if (!result.success()) {
            throw new IllegalStateException("Preview failed: "
                    + (result.errorMessage() != null ? result.errorMessage() : result.errorCode()));
        }
        return result.data().get("result");
    }

    private static @Nullable UUID parseUuidOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("materialize-oql reported a schema version that is not a UUID: {}", raw);
            return null;
        }
    }

    /**
     * Which of a project's products are members of a cube's star, keyed by product id, valued by
     * the cube's name — the refusal {@link #delete} applies, available before the click (J6-F17)
     * so the products screen can disable delete and say why instead of refusing after a confirm.
     */
    public Map<UUID, String> cubeMembership(TenantContext tenant, UUID projectId) {
        Map<UUID, String> membership = new java.util.LinkedHashMap<>();
        cubes.list(tenant, projectId).forEach(cube ->
                cube.memberProductIds().forEach(id -> membership.putIfAbsent(id, cube.name())));
        return membership;
    }

    /**
     * Delete a product — unless it is a member of a cube's star.
     *
     * <p>A star is a unit: the cube's row lists its products by position and still advertises
     * every one of them, so deleting one from the products screen left a cube pointing at a table
     * that no longer exists, with no signal anywhere (S17). Deleting the cube removes its whole
     * star, and {@link CubeService#delete} drops the row first so these checks let it through.
     * Only the product's own project is searched: a cube creates its star in the cube's project.
     *
     * @throws IllegalArgumentException naming the cube, when the product is one of its members
     */
    public void delete(TenantContext tenant, UUID productId) {
        DataProduct product = get(tenant, productId); // 404 when absent
        cubes.list(tenant, product.projectId()).stream()
                .filter(cube -> cube.memberProductIds().contains(productId))
                .findFirst()
                .ifPresent(cube -> {
                    throw new IllegalArgumentException("data product '" + product.name()
                            + "' is part of cube '" + cube.name() + "' — delete the cube to"
                            + " remove its whole star");
                });
        repository.delete(tenant, productId);
    }
}

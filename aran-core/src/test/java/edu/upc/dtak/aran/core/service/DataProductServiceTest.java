package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.fake.FakeGraphStore;
import edu.upc.dtak.aran.core.fake.InMemoryCubeRepository;
import edu.upc.dtak.aran.core.fake.InMemoryDataProductRepository;
import edu.upc.dtak.aran.core.fake.InMemoryJobRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DataProductService} against in-memory fakes: a scripted query-engine
 * operator behind a real {@link OperatorOrchestrator}, a {@link JobService} on a
 * direct executor (the materialize task runs inline), and a capturing
 * {@link LakeClient} so the upload contract — parquet content type included —
 * is asserted rather than assumed.
 */
@DisplayName("DataProductService")
class DataProductServiceTest {

    private static final Executor DIRECT = Runnable::run;
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantContext TENANT = new TenantContext(TENANT_ID.toString());
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID ACTIVE_VERSION = UUID.randomUUID();
    private static final String OQL = "SELECT IPaper.title FROM IPaper";

    private InMemoryDataProductRepository products;
    private InMemoryJobRepository jobs;
    private PinnableGraphStore graphStore;
    private ScriptedQueryEngine queryEngine;
    private CapturingLakeClient lake;
    private RecordingUsageEventRepository usageEvents;
    private InMemoryCubeRepository cubes;
    private DataProductService service;

    @BeforeEach
    void setUp() {
        products = new InMemoryDataProductRepository();
        jobs = new InMemoryJobRepository();
        graphStore = new PinnableGraphStore();
        graphStore.current = version(ACTIVE_VERSION, 1, "ACTIVE");
        queryEngine = new ScriptedQueryEngine();
        lake = new CapturingLakeClient();
        JobService jobService = new JobService(jobs, DIRECT, (jobId, snapshot) -> { });
        usageEvents = new RecordingUsageEventRepository();
        cubes = new InMemoryCubeRepository();
        service = new DataProductService(
                products, graphStore, new OperatorOrchestrator(List.of(queryEngine)),
                jobService, lake, new UsageService(usageEvents), UncappedLicenses.create(), cubes);
    }

    // ─── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create validates the OQL via rewrite-oql and pins the ACTIVE schema version")
    void create_pins_active_version() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", "desc", OQL, null);

        assertThat(created.status()).isEqualTo(DataProductStatus.DRAFT);
        assertThat(created.schemaVersionId()).isEqualTo(ACTIVE_VERSION);
        assertThat(created.oql()).isEqualTo(OQL);
        assertThat(queryEngine.requests).extracting(OperatorRequest::operation)
                .containsExactly("rewrite-oql");
        assertThat(products.findById(TENANT, created.id())).isPresent();
    }

    @Test
    @DisplayName("create rejects OQL the parser rejects, carrying the parser message")
    void create_rejects_invalid_oql() {
        queryEngine.script("rewrite-oql",
                req -> OperatorResult.failure("INVALID_OQL", "no FROM clause"));

        assertThatThrownBy(() -> service.create(TENANT, PROJECT_ID, "bad", null, "nonsense", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no FROM clause");
        assertThat(products.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("create is rejected when the current schema version is a DRAFT")
    void create_rejects_draft_version() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        graphStore.current = version(UUID.randomUUID(), 2, "DRAFT");

        assertThatThrownBy(() -> service.create(TENANT, PROJECT_ID, "papers", null, OQL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    @DisplayName("create refuses a name the project already uses as a 409, before planning the OQL (J6-F6)")
    void create_refuses_a_taken_name_as_a_conflict() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        queryEngine.requests.clear();

        // The retry after a client-side timeout is the case: the first create landed, the
        // browser never heard, and the second used to fail on V18's unique key as a generic 500.
        assertThatThrownBy(() -> service.create(TENANT, PROJECT_ID, " papers ", null, OQL, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("A data product named 'papers' already exists in this project")
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ConflictException.NAME_TAKEN);
        assertThat(queryEngine.requests).as("the name is checked before the OQL is planned").isEmpty();
        assertThat(products.findByProject(TENANT, PROJECT_ID)).hasSize(1);
    }

    // ─── create: VIRTUAL (Phase 5, Task 2) ──────────────────────────────────────

    @Test
    @DisplayName("create of a VIRTUAL product derives output_schema from rewrite-oql's own "
            + "columns and reaches READY with no snapshot, touching neither the lake nor "
            + "materialize-oql")
    void create_virtual_derivesOutputSchemaWithNoLakeInteraction() {
        String columnsJson = "[{\"name\":\"title\",\"typeName\":\"VARCHAR\",\"jdbcType\":12}]";
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of(
                "status", "ok", "sql", "SELECT 1", "columns", columnsJson)));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", "desc", OQL, null,
                ProductAccessMode.VIRTUAL);

        assertThat(created.accessMode()).isEqualTo(ProductAccessMode.VIRTUAL);
        assertThat(created.status()).isEqualTo(DataProductStatus.READY);
        assertThat(created.snapshotId()).isNull();
        assertThat(ProductColumn.fromJson(created.outputSchema()))
                .containsExactly(new ProductColumn("title", "VARCHAR", java.sql.Types.VARCHAR));
        assertThat(products.findById(TENANT, created.id())).isPresent();

        // The whole point of VIRTUAL: definition-time planning only, never execution. Exactly
        // one operator call (rewrite-oql, which plans but runs nothing) and zero lake calls —
        // no materialize-oql in the request log, no upload/init/complete on the lake fake.
        assertThat(queryEngine.requests).extracting(OperatorRequest::operation)
                .containsExactly("rewrite-oql");
        assertThat(lake.initContentType).as("no upload was ever initiated").isNull();
        assertThat(lake.uploadedBytes).as("no bytes were ever uploaded").isNull();
        assertThat(lake.completedSnapshotId).as("no upload was ever completed").isNull();
    }

    @Test
    @DisplayName("create of a VIRTUAL product rejects OQL the parser rejects, same validation "
            + "path as MATERIALIZED")
    void create_virtual_rejectsInvalidOql() {
        queryEngine.script("rewrite-oql",
                req -> OperatorResult.failure("INVALID_OQL", "no FROM clause"));

        assertThatThrownBy(() -> service.create(TENANT, PROJECT_ID, "bad", null, "nonsense", null,
                ProductAccessMode.VIRTUAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no FROM clause");
        assertThat(products.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    // ─── materialize ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("materialize of a VIRTUAL product is rejected up front — no state change, no "
            + "lake interaction, no job")
    void materialize_virtual_rejectedWithNoStateChangeOrLakeInteraction() {
        String columnsJson = "[{\"name\":\"title\",\"typeName\":\"VARCHAR\",\"jdbcType\":12}]";
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of(
                "status", "ok", "sql", "SELECT 1", "columns", columnsJson)));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null,
                ProductAccessMode.VIRTUAL);

        // The defect this guards against: without it, runMaterialization would set MATERIALIZING,
        // actually run materialize-oql, upload a real parquet to the lake, and only THEN fail —
        // inside DataProduct.materialized() — on the compact constructor's VIRTUAL/snapshotId
        // invariant, corrupting a working READY product into FAILED with an orphaned lake upload.
        // The guard must reject before any of that, synchronously, from materialize() itself.
        assertThatThrownBy(() -> service.materialize(TENANT, created.id(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIRTUAL")
                .hasMessageContaining("materialize");

        DataProduct unchanged = products.findById(TENANT, created.id()).orElseThrow();
        assertThat(unchanged.status()).as("still READY, not MATERIALIZING/FAILED").isEqualTo(DataProductStatus.READY);
        assertThat(unchanged.snapshotId()).isNull();
        assertThat(unchanged.outputSchema()).isEqualTo(created.outputSchema());
        assertThat(jobs.findRecent(TENANT, PROJECT_ID, 10))
                .as("no materialize job was ever submitted").isEmpty();
        assertThat(lake.initContentType).as("no upload was ever initiated").isNull();
        assertThat(lake.uploadedBytes).as("no bytes were ever uploaded").isNull();
        assertThat(lake.completedSnapshotId).as("no upload was ever completed").isNull();
        // Only the original create-time rewrite-oql call — the guard fires before the operator
        // is asked to do anything else (no materialize-oql).
        assertThat(queryEngine.requests).extracting(OperatorRequest::operation)
                .containsExactly("rewrite-oql");
    }

    @Test
    @DisplayName("materialize uploads the operator's parquet with the parquet content type and lands READY")
    void materialize_success() throws IOException {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        Path parquet = Files.createTempFile("test-product-", ".parquet");
        Files.write(parquet, new byte[] {1, 2, 3, 4});
        queryEngine.script("materialize-oql", req -> OperatorResult.success(Map.of(
                "status", "ok",
                "parquetPath", parquet.toAbsolutePath().toString(),
                "rowCount", "7")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        UUID jobId = service.materialize(TENANT, created.id(), null);

        assertThat(jobs.findById(TENANT, jobId).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        DataProduct ready = products.findById(TENANT, created.id()).orElseThrow();
        assertThat(ready.status()).isEqualTo(DataProductStatus.READY);
        assertThat(ready.snapshotId()).isEqualTo(lake.completedSnapshotId);
        assertThat(ready.rowCount()).isEqualTo(7L);
        assertThat(ready.materializedAt()).isNotNull();
        // The upload contract: parquet content type on init and on the S3 PUT.
        assertThat(lake.initContentType).isEqualTo("application/vnd.apache.parquet");
        assertThat(lake.uploadContentType).isEqualTo("application/vnd.apache.parquet");
        assertThat(lake.uploadedBytes).containsExactly(1, 2, 3, 4);
        // The service owns the temp file once the operator hands it over.
        assertThat(Files.exists(parquet)).isFalse();
    }

    @Test
    @DisplayName("the engine's column description is recorded on the product, for the JDBC products schema")
    void materialize_recordsTheOutputSchema() throws IOException {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        Path parquet = Files.createTempFile("test-product-", ".parquet");
        Files.write(parquet, new byte[] {1});
        queryEngine.script("materialize-oql", req -> OperatorResult.success(Map.of(
                "status", "ok",
                "parquetPath", parquet.toAbsolutePath().toString(),
                "rowCount", "1",
                "columns", "[{\"name\":\"title\",\"typeName\":\"VARCHAR\",\"jdbcType\":12}]")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        service.materialize(TENANT, created.id(), null);

        DataProduct ready = products.findById(TENANT, created.id()).orElseThrow();
        assertThat(ProductColumn.fromJson(ready.outputSchema()))
                .containsExactly(new ProductColumn("title", "VARCHAR", java.sql.Types.VARCHAR));
    }

    @Test
    @DisplayName("an engine that describes no columns still materializes — the table lists undescribed")
    void materialize_withoutColumnsStillSucceeds() throws IOException {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        Path parquet = Files.createTempFile("test-product-", ".parquet");
        Files.write(parquet, new byte[] {1});
        queryEngine.script("materialize-oql", req -> OperatorResult.success(Map.of(
                "status", "ok",
                "parquetPath", parquet.toAbsolutePath().toString(),
                "rowCount", "1")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        service.materialize(TENANT, created.id(), null);

        DataProduct ready = products.findById(TENANT, created.id()).orElseThrow();
        assertThat(ready.status()).isEqualTo(DataProductStatus.READY);
        assertThat(ready.outputSchema()).isNull();
    }

    @Test
    @DisplayName("materialize records one MATERIALIZE event with the rows and the bytes uploaded")
    void materialize_recordsUsage() throws IOException {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        Path parquet = Files.createTempFile("test-product-", ".parquet");
        Files.write(parquet, new byte[] {1, 2, 3, 4});
        queryEngine.script("materialize-oql", req -> OperatorResult.success(Map.of(
                "status", "ok",
                "parquetPath", parquet.toAbsolutePath().toString(),
                "rowCount", "7")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        UUID actor = UUID.randomUUID();
        service.materialize(TENANT, created.id(), actor);

        assertThat(usageEvents.ofKind(UsageKind.MATERIALIZE)).singleElement().satisfies(e -> {
            assertThat(e.userId()).isEqualTo(actor);
            assertThat(e.rowCount()).isEqualTo(7L);
            // The bytes actually moved into the lake, not the row count and not an estimate.
            assertThat(e.byteCount()).isEqualTo(4L);
            assertThat(e.durationMs()).isNotNull();
        });
    }

    @Test
    @DisplayName("a scheduled materialization is recorded unattributed rather than not at all")
    void materialize_recordsUnattributedWork() throws IOException {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        Path parquet = Files.createTempFile("test-product-", ".parquet");
        Files.write(parquet, new byte[] {9});
        queryEngine.script("materialize-oql", req -> OperatorResult.success(Map.of(
                "status", "ok",
                "parquetPath", parquet.toAbsolutePath().toString(),
                "rowCount", "1")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        service.materialize(TENANT, created.id(), null);

        // Null actor, not a substituted service account: "nobody asked for this" is the fact,
        // and it is what the admin screen's unattributed row is made of.
        assertThat(usageEvents.ofKind(UsageKind.MATERIALIZE)).singleElement()
                .extracting(e -> e.userId()).isNull();
    }

    @Test
    @DisplayName("a failed materialization records no usage — there was no result to charge for")
    void materialize_failureRecordsNothing() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        queryEngine.script("materialize-oql",
                req -> OperatorResult.failure("BOOM", "materialization failed"));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        service.materialize(TENANT, created.id(), null);

        assertThat(usageEvents.ofKind(UsageKind.MATERIALIZE)).isEmpty();
    }

    @Test
    @DisplayName("a failed materialization lands FAILED with the error, and the job FAILED")
    void materialize_failure() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        queryEngine.script("materialize-oql",
                req -> OperatorResult.failure("NO_SCHEMA", "No active global schema"));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        UUID jobId = service.materialize(TENANT, created.id(), null);

        assertThat(jobs.findById(TENANT, jobId).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
        DataProduct failed = products.findById(TENANT, created.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(DataProductStatus.FAILED);
        assertThat(failed.lastError()).contains("No active global schema");
    }

    // ─── stale marking ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a new ACTIVE version flips READY products pinned elsewhere to STALE, nothing else")
    void mark_stale_on_version_change() {
        Instant now = Instant.now();
        UUID newActive = UUID.randomUUID();
        DataProduct pinnedOld = new DataProduct(UUID.randomUUID(), TENANT_ID, PROJECT_ID,
                "old_ready", null, OQL, ACTIVE_VERSION, ProductAccessMode.MATERIALIZED,
                DataProductStatus.READY, "snap-1", 10L, null, now, null, null, now);
        DataProduct pinnedNew = new DataProduct(UUID.randomUUID(), TENANT_ID, PROJECT_ID,
                "new_ready", null, OQL, newActive, ProductAccessMode.MATERIALIZED,
                DataProductStatus.READY, "snap-2", 10L, null, now, null, null, now);
        DataProduct draft = new DataProduct(UUID.randomUUID(), TENANT_ID, PROJECT_ID,
                "draft", null, OQL, ACTIVE_VERSION, ProductAccessMode.MATERIALIZED,
                DataProductStatus.DRAFT, null, null, null, null, null, null, now);
        products.save(TENANT, pinnedOld);
        products.save(TENANT, pinnedNew);
        products.save(TENANT, draft);

        graphStore.current = version(newActive, 2, "ACTIVE");
        service.markStaleForVersionChange(TENANT);

        assertThat(products.findById(TENANT, pinnedOld.id()).orElseThrow().status())
                .isEqualTo(DataProductStatus.STALE);
        assertThat(products.findById(TENANT, pinnedNew.id()).orElseThrow().status())
                .isEqualTo(DataProductStatus.READY);
        assertThat(products.findById(TENANT, draft.id()).orElseThrow().status())
                .isEqualTo(DataProductStatus.DRAFT);
    }

    @Test
    @DisplayName("a READY VIRTUAL product is never marked STALE, no matter which version goes ACTIVE")
    void virtual_products_are_never_marked_stale() {
        Instant now = Instant.now();
        DataProduct virtualReady = new DataProduct(UUID.randomUUID(), TENANT_ID, PROJECT_ID,
                "virtual_ready", null, OQL, ACTIVE_VERSION, ProductAccessMode.VIRTUAL,
                DataProductStatus.READY, null, null, null, null, null, null, now);
        products.save(TENANT, virtualReady);

        // Pinned to ACTIVE_VERSION, which is no longer ACTIVE — a MATERIALIZED product in this
        // exact situation is the pinnedOld case above and DOES flip to STALE. VIRTUAL must not.
        graphStore.current = version(UUID.randomUUID(), 2, "ACTIVE");
        service.markStaleForVersionChange(TENANT);

        assertThat(products.findById(TENANT, virtualReady.id()).orElseThrow().status())
                .isEqualTo(DataProductStatus.READY);
    }

    // ─── preview ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("preview of a VIRTUAL product runs its OQL through run-oql, not preview-parquet")
    void preview_virtual_runsItsOqlThroughRunOql() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        queryEngine.script("run-oql", req -> OperatorResult.success(
                Map.of("status", "ok", "result", "{\"rows\":[]}")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null,
                ProductAccessMode.VIRTUAL);
        String payload = service.preview(TENANT, created.id(), 100);

        assertThat(payload).isEqualTo("{\"rows\":[]}");
        assertThat(queryEngine.requests).extracting(OperatorRequest::operation)
                .containsExactly("rewrite-oql", "run-oql");
        assertThat(queryEngine.requests.get(1).parameters()).containsEntry("oql", OQL);
    }

    @Test
    @DisplayName("preview of a VIRTUAL product forwards the caller's limit — an agent asking for 20 rows gets 20, not 1000 (V37 gap 3)")
    void preview_virtual_forwardsTheLimit() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        queryEngine.script("run-oql", req -> OperatorResult.success(
                Map.of("status", "ok", "result", "{\"rows\":[]}")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null,
                ProductAccessMode.VIRTUAL);
        service.preview(TENANT, created.id(), 20);

        assertThat(queryEngine.requests.get(1).parameters()).containsEntry("limit", "20");
    }

    @Test
    @DisplayName("a build records the schema version the engine planned it against (UI-OD12)")
    void materialize_recordsTheVersionItWasBuiltAgainst() throws IOException {
        UUID promotedLater = UUID.randomUUID();
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        Path parquet = Files.createTempFile("test-product-", ".parquet");
        Files.write(parquet, new byte[] {1});
        queryEngine.script("materialize-oql", req -> OperatorResult.success(Map.of(
                "status", "ok",
                "parquetPath", parquet.toAbsolutePath().toString(),
                "rowCount", "1",
                "schemaVersionId", promotedLater.toString())));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        service.materialize(TENANT, created.id(), null);

        DataProduct ready = products.findById(TENANT, created.id()).orElseThrow();
        assertThat(ready.builtAgainstVersionId()).isEqualTo(promotedLater);
        assertThat(ready.schemaVersionId()).isEqualTo(ACTIVE_VERSION);
    }

    @Test
    @DisplayName("an engine that does not say which version it planned against records none rather than guessing")
    void materialize_withoutAReportedVersionRecordsNone() throws IOException {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        Path parquet = Files.createTempFile("test-product-", ".parquet");
        Files.write(parquet, new byte[] {1});
        queryEngine.script("materialize-oql", req -> OperatorResult.success(Map.of(
                "status", "ok",
                "parquetPath", parquet.toAbsolutePath().toString(),
                "rowCount", "1")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        service.materialize(TENANT, created.id(), null);

        assertThat(products.findById(TENANT, created.id()).orElseThrow().builtAgainstVersionId()).isNull();
    }

    @Test
    @DisplayName("preview of a MATERIALIZED product with no snapshot yet is still rejected, unchanged")
    void preview_materialized_withoutSnapshotStillRejected() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));

        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);

        assertThatThrownBy(() -> service.preview(TENANT, created.id(), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("materialize it first");
    }

    // ─── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a product outside any cube is deleted")
    void delete_removes_an_ordinary_product() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);

        service.delete(TENANT, created.id());

        assertThat(products.findById(TENANT, created.id())).isEmpty();
    }

    @Test
    @DisplayName("a cube's star member is refused, naming the cube, and survives")
    void delete_refuses_a_cube_member() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        DataProduct fact = service.create(TENANT, PROJECT_ID, "sales_fact", null, OQL, null);
        cubes.save(TENANT, cubeHolding("sales", fact.id()));

        assertThatThrownBy(() -> service.delete(TENANT, fact.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cube 'sales'")
                .hasMessageContaining("delete the cube");
        assertThat(products.findById(TENANT, fact.id())).isPresent();
    }

    @Test
    @DisplayName("cube membership is known before a delete is attempted, per product (J6-F17)")
    void cubeMembership_namesTheCubeHoldingEachMember() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        DataProduct fact = service.create(TENANT, PROJECT_ID, "sales_fact", null, OQL, null);
        DataProduct loose = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        cubes.save(TENANT, cubeHolding("sales", fact.id()));

        Map<UUID, String> membership = service.cubeMembership(TENANT, PROJECT_ID);

        assertThat(membership).containsEntry(fact.id(), "sales").doesNotContainKey(loose.id());
    }

    @Test
    @DisplayName("a cube in another project holding the same id does not block — membership is per project")
    void delete_only_consults_the_products_own_project() {
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        DataProduct created = service.create(TENANT, PROJECT_ID, "papers", null, OQL, null);
        CubeRecord elsewhere = cubeHolding("other", created.id());
        cubes.save(TENANT, new CubeRecord(elsewhere.id(), elsewhere.tenantId(), UUID.randomUUID(),
                elsewhere.name(), null, elsewhere.factClassIri(), elsewhere.definitionJson(),
                elsewhere.productIdsJson(), elsewhere.schemaVersionId(), null,
                elsewhere.createdAt()));

        service.delete(TENANT, created.id());

        assertThat(products.findById(TENANT, created.id())).isEmpty();
    }

    private static CubeRecord cubeHolding(String name, UUID... productIds) {
        String ids = java.util.Arrays.stream(productIds)
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return new CubeRecord(UUID.randomUUID(), TENANT_ID, PROJECT_ID, name, null,
                "http://aran/global#Sales", "{}", ids, ACTIVE_VERSION, null, Instant.now());
    }

    // ─── fakes ────────────────────────────────────────────────────────────────

    private static GraphVersion version(UUID id, int number, String status) {
        return new GraphVersion(id, TENANT_ID, number, status, Instant.now(), null, null, null);
    }

    /** FakeGraphStore whose {@code currentVersion} is settable. */
    private static final class PinnableGraphStore extends FakeGraphStore {
        GraphVersion current;

        @Override
        public GraphVersion currentVersion(TenantContext tenant) {
            return current;
        }
    }

    /** A query-engine operator whose operations are scripted per test. */
    private static final class ScriptedQueryEngine implements Operator {
        final Map<String, Function<OperatorRequest, OperatorResult>> script = new LinkedHashMap<>();
        final List<OperatorRequest> requests = new ArrayList<>();

        void script(String operation, Function<OperatorRequest, OperatorResult> fn) {
            script.put(operation, fn);
        }

        @Override
        public String getOperatorId() {
            return "query-engine";
        }

        @Override
        public String getDisplayName() {
            return "scripted query engine";
        }

        @Override
        public OperatorResult execute(OperatorRequest request) {
            requests.add(request);
            Function<OperatorRequest, OperatorResult> fn = script.get(request.operation());
            if (fn == null) {
                return OperatorResult.failure("UNKNOWN_OPERATION",
                        "not scripted: " + request.operation());
            }
            return fn.apply(request);
        }
    }

}

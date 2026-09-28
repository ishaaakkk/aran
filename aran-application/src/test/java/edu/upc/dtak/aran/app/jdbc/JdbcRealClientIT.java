package edu.upc.dtak.aran.app.jdbc;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRegistration;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import edu.upc.dtak.aran.core.service.DataProductService;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.service.JobEventPublisher;
import edu.upc.dtak.aran.core.service.JobService;
import edu.upc.dtak.aran.core.service.ProjectService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.domain.JobRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Closes V1 and V27: a real out-of-process JDBC client —
 * the actual {@code avatica-core} driver, not a stub or an in-process call — connecting to a real
 * running Aran and proving both schemas work: {@code graph} (OQL, the GAV rewrite) and
 * {@code products} (plain SQL, unrewritten, over a materialized product's parquet).
 *
 * <p>Fuseki is stubbed the same way {@link edu.upc.dtak.aran.app.web.ModelCompileIntegrationIT}
 * stubs it — {@code BaseIntegrationTest.TestGraphConfig}'s {@code InMemoryGraphStore} — and the
 * lake is a byte-store fake keyed by upload id, since Garage is not part of this test's infra.
 * Everything else is the production path: real Postgres, a real Spring context on a real HTTP
 * port, the real bootstrap → integrate → promote → materialize pipeline, a real {@code aran_pat_}
 * token minted by the real {@link ApiTokenService}, and a real {@code DriverManager} connection
 * through {@code org.apache.calcite.avatica.remote.Driver}.
 */
@DisplayName("JDBC: a real Avatica client against graph and products")
class JdbcRealClientIT extends BaseIntegrationTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    /**
     * V46 fixture: a DATE and a TIMESTAMP value per row, distinct enough (different months,
     * a midnight, a 23:59:59, a leading-second-of-day) that a truncation or an off-by-one-day
     * TZ bug would show up as a wrong value, not a coincidentally-right one.
     */
    private static final DateTimeFormatter TIMESTAMP_LITERAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalDate ALICE_JOINED = LocalDate.of(2024, 3, 15);
    private static final LocalDateTime ALICE_CREATED = LocalDateTime.of(2024, 3, 15, 10, 30, 0);
    private static final LocalDate BOB_JOINED = LocalDate.of(2024, 6, 1);
    private static final LocalDateTime BOB_CREATED = LocalDateTime.of(2024, 6, 1, 23, 59, 59);
    private static final LocalDate CAROL_JOINED = LocalDate.of(2023, 12, 25);
    private static final LocalDateTime CAROL_CREATED = LocalDateTime.of(2023, 12, 25, 0, 0, 0);
    private static final LocalDate DAVE_JOINED = LocalDate.of(2024, 1, 1);
    private static final LocalDateTime DAVE_CREATED = LocalDateTime.of(2024, 1, 1, 12, 0, 1);
    private static final Map<String, LocalDate> EXPECTED_JOINED_ON = Map.of(
            "Alice", ALICE_JOINED, "Bob", BOB_JOINED, "Carol", CAROL_JOINED, "Dave", DAVE_JOINED);
    private static final Map<String, LocalDateTime> EXPECTED_CREATED_AT = Map.of(
            "Alice", ALICE_CREATED, "Bob", BOB_CREATED, "Carol", CAROL_CREATED, "Dave", DAVE_CREATED);

    /**
     * The one fixed column order every SELECT list this test builds uses — shared so the graph
     * OQL, the product OQL and the ordinal column indices in {@link #assertDateColumn}/
     * {@link #assertTimestampColumn} calls can never drift apart from each other.
     */
    private static final List<String> ORDERED_PROPERTIES =
            List.of("id", "name", "joined_on", "created_at", "tz_created");

    /**
     * Review finding I1: {@code Types.TIME_WITH_TIMEZONE}/{@code TIMESTAMP_WITH_TIMEZONE} are
     * deliberately excluded from the V46 numeric-Rep fix and fall through to {@code Rep.STRING}
     * instead (see {@code AranCursor.avaticaTypeFor}'s comment) — a real, reachable path (Postgres
     * {@code timestamptz} → {@code JdbcBulkExtractor.duckDbType} → DuckDB {@code TIMESTAMPTZ} →
     * snapshot parquet → a sources-tier read), exercised here by giving BOTH people datasets a
     * uniform, DATATYPE-mapped {@code tz_created} column (the {@code sources} tier's table listing
     * ({@code MappingCatalog.sourceCatalog()}) only ever includes datasets that belong to some
     * {@code IntegratedClass} — an unmapped, never-integrated dataset is invisible there entirely,
     * confirmed by reading the code and by {@code a1-single-csv.spec.ts}'s own documented
     * behaviour, so a standalone dataset cannot reach this tier at all). Mapped, not left
     * unaligned, to avoid the same "two properties sharing a local name" ambiguity the pre-fix
     * "id"/"name" mapping comment below already documents. Reaching the graph/products tiers too
     * is an unavoidable side effect of being part of the merged class at all — harmless, since
     * {@code ParquetBootstrap.xsdRangeFor} folds TIMESTAMPTZ to plain {@code xsd:dateTime} there,
     * the same already-covered path {@code created_at} exercises; the graph/products tier only
     * needs to prove it does not throw, not a value (see the two "does not throw" checks below).
     * Two distinct, non-UTC offsets (+02:00, -05:00) so a session that silently normalised to UTC
     * or dropped the offset would be caught.
     */
    private static final DateTimeFormatter TZ_LITERAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");
    private static final java.time.OffsetDateTime ALICE_TZ_CREATED =
            java.time.OffsetDateTime.of(2024, 3, 15, 10, 30, 0, 0, java.time.ZoneOffset.ofHours(2));
    private static final java.time.OffsetDateTime BOB_TZ_CREATED =
            java.time.OffsetDateTime.of(2024, 6, 1, 23, 59, 59, 0, java.time.ZoneOffset.ofHours(-5));
    private static final java.time.OffsetDateTime CAROL_TZ_CREATED =
            java.time.OffsetDateTime.of(2023, 12, 25, 0, 0, 0, 0, java.time.ZoneOffset.ofHours(2));
    private static final java.time.OffsetDateTime DAVE_TZ_CREATED =
            java.time.OffsetDateTime.of(2024, 1, 1, 12, 0, 1, 0, java.time.ZoneOffset.ofHours(-5));
    private static final Map<String, java.time.OffsetDateTime> EXPECTED_TZ_CREATED = Map.of(
            "Alice", ALICE_TZ_CREATED, "Bob", BOB_TZ_CREATED, "Carol", CAROL_TZ_CREATED, "Dave", DAVE_TZ_CREATED);

    /**
     * Runs a job on its own thread and joins it, exactly like
     * {@link edu.upc.dtak.aran.app.web.DatasetBootstrapIntegrationIT}'s executor — a job
     * dispatched from {@code afterCommit} must not run on the calling thread, or its writes
     * would join a transaction that already finished.
     */
    @TestConfiguration
    static class DirectExecutorJobConfig {
        @Bean
        @Primary
        JobService directJobService(JobRepository jobRepository, JobEventPublisher publisher) {
            Executor awaitedOffThread = task -> {
                Thread worker = new Thread(task, "test-job-worker");
                worker.start();
                try {
                    worker.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting the job", ex);
                }
            };
            return new JobService(jobRepository, awaitedOffThread, publisher);
        }
    }

    @LocalServerPort
    private int port;

    /**
     * {@code BaseIntegrationTest} runs {@code database} licensing mode, which needs a seeded
     * {@code license} row this test has no reason to build — nothing here is testing licensing.
     * Replacing the provider (not the mode) is the same move
     * {@code AranMetaTest.uncappedLicenses()} makes for the same reason.
     */
    @MockitoBean
    private edu.upc.dtak.aran.core.licensing.LicenseProvider licenseProvider;

    @Autowired private edu.upc.dtak.aran.core.domain.TenantRepository tenantRepository;
    @Autowired private edu.upc.dtak.aran.core.domain.UserRepository userRepository;
    @Autowired private DatasetService datasetService;
    @Autowired private ProjectService projectService;
    @Autowired private DataRepositoryService dataRepositoryService;
    @Autowired private IntegrationModelService modelService;
    @Autowired private DataProductService productService;
    @Autowired private ApiTokenService apiTokenService;

    @MockitoBean
    private LakeClient lakeClient;

    /** Every "upload" the test drives, keyed by snapshot id — a byte-store fake for Garage. */
    private final Map<String, byte[]> lakeStore = new ConcurrentHashMap<>();
    /**
     * The content type each snapshot was actually uploaded with, keyed the same way — needed so
     * {@code BootstrappingOperator}'s {@code blob.contentType()} format detection sees the real
     * value (V46: a hardcoded "text/csv" here would silently force every fixture, Parquet
     * included, through the CSV bootstrap path).
     */
    private final Map<String, String> lakeContentTypes = new ConcurrentHashMap<>();
    private volatile byte[] pendingUpload;
    private volatile String pendingContentType;

    @Test
    @DisplayName("a real avatica-core client reads OQL from graph and SQL from products, over one connection")
    void real_client_reads_both_schemas() throws Exception {
        wireLakeFake();
        when(licenseProvider.findFor(any(UUID.class))).thenAnswer(inv ->
                java.util.Optional.of(new edu.upc.dtak.aran.core.domain.LicenseEntitlements(
                        inv.getArgument(0), edu.upc.dtak.aran.core.domain.LicensePlan.ENTERPRISE,
                        edu.upc.dtak.aran.core.domain.LicenseStatus.ACTIVE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE, null, null, Set.of(), null)));
        AuthenticatedUser caller = new AuthenticatedUser(
                userId, "sub", tenantId, edu.upc.dtak.aran.core.domain.TenantRole.OWNER);
        TenantContext tenant = new TenantContext(tenantId.toString());
        // A fresh tenant per run rather than the shared dev-seed demo tenant: this test's
        // "exactly one product" assertions would be pollutable by whatever else the full
        // suite has created under a shared tenant id.
        tenantRepository.save(new edu.upc.dtak.aran.core.domain.Tenant(
                tenantId, "jdbc-it-" + tenantId, "JDBC IT", java.time.Instant.now()));
        userRepository.save(new edu.upc.dtak.aran.core.domain.User(
                userId, "jdbc-it-" + userId, "jdbc-it@example.test", "JDBC IT", java.time.Instant.now()));
        // The API token filter re-resolves the caller from the token's user id and requires a
        // live Membership — a token whose owner has no membership left is treated as revoked.
        tenantRepository.saveMembership(new edu.upc.dtak.aran.core.domain.Membership(
                UUID.randomUUID(), userId, tenantId,
                edu.upc.dtak.aran.core.domain.TenantRole.OWNER, java.time.Instant.now()));

        // ── 1. Two real datasets, bootstrapped for real from real CSV bytes ────────────────
        // SchemaIntegrator only ever mints an IntegratedResource from an alignment — see
        // a1-single-csv.spec.ts's own comment ("a lone source has nothing to be integrated
        // with... Integration and OQL start at A2, where there are two sources to integrate").
        // One dataset would bootstrap fine but never become a `graph`-schema table, so this
        // mirrors A2 rather than A1: two sources, one class alignment.
        Project project = projectService.createProject(caller, "JDBC IT", "desc", "PUBLIC", "#fff");
        DataRepository repo = dataRepositoryService.createRepository(caller, project.id(),
                new FileSystemRepository(UUID.randomUUID(), project.id(), tenantId, "Repo", "desc"));

        // V46: a real DATE, TIMESTAMP and TIMESTAMPTZ column, not just VARCHAR/BIGINT. CsvBootstrap
        // types every column xsd:string ("type refinement is profiling's job, not bootstrapping's"
        // — CsvBootstrap's own javadoc), so a CSV fixture can never reach the Rep bug: nothing
        // downstream would ever declare a graph/product column DATE or TIMESTAMP. ParquetBootstrap
        // reads DuckDB's own DESCRIBE schema and maps DATE/TIMESTAMP(TZ) straight to
        // xsd:date/xsd:dateTime (ParquetBootstrap.xsdRangeFor) — a real column schema, the same
        // shape a bootstrapped relational or object-storage source carries, and what live runs
        // actually hit (V37's products._products.last_refreshed, Int_concert.held_on).
        DatasetRegistration reg1 = datasetService.registerDatasetWithFile(caller, project.id(), repo.id(),
                new Dataset(UUID.randomUUID(), repo.id(), "people1", "desc"),
                peopleParquet(1L, "Alice", ALICE_JOINED, ALICE_CREATED, ALICE_TZ_CREATED,
                        2L, "Bob", BOB_JOINED, BOB_CREATED, BOB_TZ_CREATED),
                "application/octet-stream", "people1.parquet");
        DatasetRegistration reg2 = datasetService.registerDatasetWithFile(caller, project.id(), repo.id(),
                new Dataset(UUID.randomUUID(), repo.id(), "people2", "desc"),
                peopleParquet(3L, "Carol", CAROL_JOINED, CAROL_CREATED, CAROL_TZ_CREATED,
                        4L, "Dave", DAVE_JOINED, DAVE_CREATED, DAVE_TZ_CREATED),
                "application/octet-stream", "people2.parquet");

        // ── 2. Integrate the two sources under one class alignment, then promote ───────────
        List<edu.upc.dtak.aran.core.domain.ModelSource> sources1 =
                modelService.addSource(tenant, project.id(), reg1.dataset().id());
        List<edu.upc.dtak.aran.core.domain.ModelSource> sources2 =
                modelService.addSource(tenant, project.id(), reg2.dataset().id());
        String class1 = sources1.get(0).classIri();
        String class2 = sources2.get(0).classIri();
        modelService.addMapping(tenant, project.id(), edu.upc.dtak.aran.core.domain.MappingKinds.CLASS,
                class1, class2, "People", userId, null);
        // Without these, "id" and "name" stay two distinct global properties that happen to
        // share a local name — AranMeta.getColumns() lists both, and OQL then refuses a bare
        // People.id as ambiguous ("two properties sharing local name 'id'"), found by actually
        // running the query rather than assuming a class-level alignment was enough. Same
        // reasoning covers tz_created below (I1) — mapped, not left to collide.
        modelService.addMapping(tenant, project.id(), edu.upc.dtak.aran.core.domain.MappingKinds.DATATYPE,
                class1 + "/id", class2 + "/id", "id", userId, null);
        modelService.addMapping(tenant, project.id(), edu.upc.dtak.aran.core.domain.MappingKinds.DATATYPE,
                class1 + "/name", class2 + "/name", "name", userId, null);
        modelService.addMapping(tenant, project.id(), edu.upc.dtak.aran.core.domain.MappingKinds.DATATYPE,
                class1 + "/joined_on", class2 + "/joined_on", "joined_on", userId, null);
        modelService.addMapping(tenant, project.id(), edu.upc.dtak.aran.core.domain.MappingKinds.DATATYPE,
                class1 + "/created_at", class2 + "/created_at", "created_at", userId, null);
        modelService.addMapping(tenant, project.id(), edu.upc.dtak.aran.core.domain.MappingKinds.DATATYPE,
                class1 + "/tz_created", class2 + "/tz_created", "tz_created", userId, null);

        Map<String, String> compiled = modelService.rebuildToDraft(tenant, project.id(), userId);
        UUID runId = UUID.fromString(compiled.get("integrationRunId"));
        GraphVersion active = modelService.promoteRun(tenant, project.id(), runId, userId);
        assertThat(active.status()).isEqualTo("ACTIVE");

        // ── 3. A real READ-scoped API token ─────────────────────────────────────────────────
        ApiTokenService.IssuedToken issued =
                apiTokenService.create(tenant, userId, "jdbc-it", ApiTokenScope.READ, null);
        String token = issued.secret();
        assertThat(token).startsWith("aran_pat_");

        String baseUrl = "jdbc:avatica:remote:url=http://localhost:" + port + "/api/jdbc"
                + ";serialization=json;authentication=BASIC;avatica_user=aran;avatica_password=" + token;

        Class.forName("org.apache.calcite.avatica.remote.Driver");

        // ── 4. graph schema: discover the class via real DatabaseMetaData, then run real OQL ──
        String className;
        List<String> propertyLocalNames = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(baseUrl)) {
            DatabaseMetaData meta = conn.getMetaData();

            try (ResultSet schemas = meta.getSchemas()) {
                Set<String> schemaNames = new HashSet<>();
                while (schemas.next()) {
                    schemaNames.add(schemas.getString("TABLE_SCHEM"));
                }
                assertThat(schemaNames).contains("graph", "products", "sources");
            }

            try (ResultSet tables = meta.getTables("aran", "graph", null, null)) {
                assertThat(tables.next())
                        .as("the bootstrapped class must show up as exactly one table in graph")
                        .isTrue();
                className = tables.getString("TABLE_NAME");
                assertThat(tables.next()).as("only one class was ever integrated").isFalse();
            }

            try (ResultSet columns = meta.getColumns("aran", "graph", className, null)) {
                while (columns.next()) {
                    propertyLocalNames.add(columns.getString("COLUMN_NAME"));
                }
            }
            assertThat(propertyLocalNames).isNotEmpty();
            // V46 fixture: id/name/joined_on/created_at/tz_created, always in ORDERED_PROPERTIES'
            // fixed order — avoids depending on how OQL's unquoted `Class.property` projections
            // label themselves (case-folding is the parser's call, not this test's); column
            // POSITION is unambiguous regardless, and 1-based ordinal getters read off it
            // directly. tz_created reaching this tier is unavoidable (ORDERED_PROPERTIES' own
            // javadoc explains why) — every property of every source in the merged class shows
            // up here, mapped or not.
            assertThat(propertyLocalNames)
                    .as("the five bootstrapped properties, id/name plus the V46 temporal trio")
                    .containsExactlyInAnyOrderElementsOf(ORDERED_PROPERTIES);

            // OQL identifiers are bare, not SQL-quoted — the JDBC endpoint's documented
            // examples never quote a class or property name (`SELECT Paper.title FROM Paper`).
            String selectList = String.join(", ", ORDERED_PROPERTIES.stream()
                    .map(p -> className + "." + p).toList());
            String oql = "SELECT " + selectList + " FROM " + className;

            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(oql)) {
                int rows = 0;
                Set<String> namesSeen = new HashSet<>();
                while (rs.next()) {
                    rows++;
                    String name = rs.getString(2);
                    namesSeen.add(name);
                    // V46: getDate/getObject/getString/getTimestamp must return the CORRECT
                    // value, not merely avoid throwing — this is the assertion that was red
                    // before the Rep fix (ClassCastException on every one of these).
                    assertDateColumn(rs, 3, EXPECTED_JOINED_ON.get(name));
                    assertTimestampColumn(rs, 4, EXPECTED_CREATED_AT.get(name));
                    // Column 5 (tz_created) at the graph tier: I1's ORDERED_PROPERTIES javadoc
                    // explains why this column is even here (unavoidable — every column of every
                    // source in the merged class shows up) and why only "does not throw" is
                    // checked, not a value — ParquetBootstrap folds TIMESTAMPTZ to plain
                    // xsd:dateTime, so this is already-covered plain-TIMESTAMP handling, and the
                    // exact naive value DuckDB's TIMESTAMPTZ->TIMESTAMP cast produces is not this
                    // task's concern.
                    assertThat(rs.getTimestamp(5)).as("column 5 (tz_created) via getTimestamp()").isNotNull();
                    assertThat(rs.getObject(5)).as("column 5 (tz_created) via getObject()").isNotNull();
                    assertThat(rs.getString(5)).as("column 5 (tz_created) via getString()").isNotBlank();
                }
                assertThat(rows).as("all four rows, from both merged sources, via a real OQL round trip")
                        .isEqualTo(4);
                assertThat(namesSeen).as("every row's temporal columns were checked, not just one")
                        .containsExactlyInAnyOrder("Alice", "Bob", "Carol", "Dave");
            }

            // ── 4b. sources schema: each dataset is its own table, with source-native columns ──
            List<String> sourceTables = new ArrayList<>();
            try (ResultSet tables = meta.getTables("aran", "sources", null, null)) {
                while (tables.next()) {
                    sourceTables.add(tables.getString("TABLE_NAME"));
                }
            }
            assertThat(sourceTables)
                    .as("two bootstrapped datasets, two sources-tier tables")
                    .hasSize(2);

            List<String> sourceColumns = new ArrayList<>();
            try (ResultSet columns = meta.getColumns("aran", "sources", sourceTables.get(0), null)) {
                while (columns.next()) {
                    sourceColumns.add(columns.getString("COLUMN_NAME"));
                }
            }
            assertThat(sourceColumns)
                    .as("source-native column names, not property IRIs")
                    .containsExactlyInAnyOrder("id", "name", "joined_on", "created_at", "tz_created");

            // Plain SQL over one source table returns that source's own rows — per-source
            // granularity, not the graph's merged four.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) AS n FROM sources." + sourceTables.get(0))) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("n"))
                        .as("one source's own two rows, not the merged four")
                        .isEqualTo(2);
            }

            // V46: every tier goes through the same AranCursor, and this drives a real client
            // read of the sources tier: plain SQL, real DATE/TIMESTAMP/TIMESTAMPTZ columns, real
            // values. tz_created is I1 (review): TIME_WITH_TIMEZONE/TIMESTAMP_WITH_TIMEZONE are
            // excluded from the numeric-Rep fix and fall through to Rep.STRING
            // (AranCursor.avaticaTypeFor's comment) — this is that path, real and reachable (see
            // TZ_LITERAL's javadoc). getObject() on a Rep.STRING/declared-VARCHAR column returns
            // the same String getString() does.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name, joined_on, created_at, tz_created FROM sources." + sourceTables.get(0))) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    String name = rs.getString(1);
                    assertDateColumn(rs, 2, EXPECTED_JOINED_ON.get(name));
                    assertTimestampColumn(rs, 3, EXPECTED_CREATED_AT.get(name));

                    String tzWire = rs.getString(4);
                    assertThat(tzWire).as("TIMESTAMPTZ read as a string, not thrown").isNotBlank();
                    assertThat(rs.getObject(4)).as("getObject() agrees with getString()").isEqualTo(tzWire);
                    // The OFFSET DuckDB prints depends on the reading session's own default
                    // TimeZone (verified empirically — not the offset either fixture value was
                    // written with), so the value that must round-trip correctly is the INSTANT,
                    // not the literal text. Parsing via OffsetDateTime and comparing toInstant()
                    // is what actually proves the value is unchanged, not merely well-formed.
                    java.time.OffsetDateTime tzParsed = java.time.OffsetDateTime.parse(tzWire);
                    assertThat(tzParsed.toInstant())
                            .as("tz_created's instant round-tripped correctly, offset display aside")
                            .isEqualTo(EXPECTED_TZ_CREATED.get(name).toInstant());
                }
                assertThat(rows).as("one source's own two rows, temporal columns included")
                        .isEqualTo(2);
            }
        }

        // ── 5. Materialize a data product from that same class, over the same connection type ──
        // Every property, in ORDERED_PROPERTIES' fixed order — V46 needs the product to carry
        // joined_on/created_at too, so the products tier exercises the Rep fix independently of
        // graph's, and the shared fixed order keeps column positions known and un-driftable.
        String productOql = "SELECT " + String.join(", ", ORDERED_PROPERTIES.stream()
                .map(p -> className + "." + p).toList())
                + " FROM " + className;
        // Captured before materialize() sets last_refreshed for M1's read of the real V46-named
        // example cell (products._products.last_refreshed) below — a half-open window ending
        // strictly after the call returns is what proves the timestamp is "now", not stale/zero.
        java.time.Instant beforeMaterialize = java.time.Instant.now();
        // Deliberately not "People" too — a product sharing the graph class's name would make
        // the negative test below (People.id on products, expected to fail) accidentally valid.
        DataProduct product = productService.create(
                tenant, project.id(), "PeopleExport", null, productOql, userId);
        productService.materialize(tenant, product.id(), userId);
        DataProduct materialized = productService.get(tenant, product.id());
        assertThat(materialized.status().name()).isEqualTo("READY");

        // ── 6. products schema: plain SQL, a real materialized parquet snapshot, real types ──
        //
        // PRODUCT FINDING (closed V27): `;schema=products` in the connection string does NOT
        // reach AranMeta with the real avatica-core driver, despite the docs documenting it as
        // equivalent to setSchema. AranMeta.openConnection correctly reads `info.get("schema")`
        // — confirmed by reading it — so the gap is client-side: the real driver treats "schema"
        // as a structured JDBC property negotiated only through connectionSync (what
        // Connection.setSchema() triggers), not as a free-form property forwarded through
        // openConnection's info map the way `user`/`password` are. Proven here by opening with
        // `;schema=products` in the URL and observing conn.getSchema() still report "graph"
        // until setSchema() is called explicitly — which is what every real client (DBeaver's
        // schema browser included) does anyway when a person picks a schema, so this doesn't
        // block real usage, but the URL-string half of the documented recipe does not work.
        try (Connection conn = DriverManager.getConnection(baseUrl + ";schema=products")) {
            assertThat(conn.getSchema())
                    .as("the real driver does not forward a schema= URL property — see the finding above")
                    .isEqualTo("graph");
            conn.setSchema("products");
            DatabaseMetaData meta = conn.getMetaData();
            String productTable;
            try (ResultSet tables = meta.getTables("aran", "products", null, null)) {
                List<String> names = new ArrayList<>();
                while (tables.next()) {
                    names.add(tables.getString("TABLE_NAME"));
                }
                assertThat(names).as("the product plus the registry table").contains("_products");
                productTable = names.stream().filter(n -> !n.equals("_products")).findFirst()
                        .orElseThrow(() -> new AssertionError("no product table among " + names));
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM " + productTable)) {
                int rows = 0;
                Set<String> namesSeen = new HashSet<>();
                while (rs.next()) {
                    rows++;
                    // Ordinal, matching productOql's fixed ORDERED_PROPERTIES order — see
                    // assertDateColumn's javadoc for why position, not label.
                    String name = rs.getString(2);
                    namesSeen.add(name);
                    // V46 on the products tier: a MATERIALIZED product's own parquet snapshot,
                    // read back through the same AranCursor as graph — the register's
                    // products._products.last_refreshed ClassCastException, reproduced here on
                    // a product's own DATE/TIMESTAMP columns instead.
                    assertDateColumn(rs, 3, EXPECTED_JOINED_ON.get(name));
                    assertTimestampColumn(rs, 4, EXPECTED_CREATED_AT.get(name));
                    // Column 5 (tz_created): "does not throw" only — see the graph-tier read's
                    // identical check above for why.
                    assertThat(rs.getTimestamp(5)).as("column 5 (tz_created) via getTimestamp()").isNotNull();
                    assertThat(rs.getObject(5)).as("column 5 (tz_created) via getObject()").isNotNull();
                    assertThat(rs.getString(5)).as("column 5 (tz_created) via getString()").isNotBlank();
                }
                assertThat(rows).as("plain SQL over the product's real parquet snapshot")
                        .isEqualTo(4);
                assertThat(namesSeen).containsExactlyInAnyOrder("Alice", "Bob", "Carol", "Dave");
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM products._products")) {
                assertThat(rs.next()).as("the registry lists the product this token can see").isTrue();
                // M1 (review): V46's own named example is exactly this cell
                // (products._products.last_refreshed, plain TIMESTAMP) — read the VALUE, not
                // just the row's presence. Column found by metadata rather than assumed ordinal
                // or label case, matching every other lookup in this test.
                java.sql.ResultSetMetaData productsMeta = rs.getMetaData();
                int lastRefreshedIdx = -1;
                for (int i = 1; i <= productsMeta.getColumnCount(); i++) {
                    if (productsMeta.getColumnLabel(i).equalsIgnoreCase("last_refreshed")) {
                        lastRefreshedIdx = i;
                        break;
                    }
                }
                assertThat(lastRefreshedIdx).as("_products carries a last_refreshed column").isPositive();

                java.time.Instant afterMaterialize = java.time.Instant.now();
                Timestamp lastRefreshed = rs.getTimestamp(lastRefreshedIdx);
                assertThat(lastRefreshed).as("a MATERIALIZED product: last_refreshed is set, not null")
                        .isNotNull();
                // NOT lastRefreshed.toInstant()/.getTime(): those read the client Timestamp's raw
                // millis field, which (like the server-side bug this task fixed) is offset by the
                // JVM's default TimeZone once reconstructed via Timestamp.valueOf-style
                // construction. .toLocalDateTime() is the TZ-neutral accessor (same reasoning as
                // assertTimestampColumn); re-deriving the instant from THAT is what actually
                // proves the value, not an artefact of this machine's zone.
                java.time.Instant lastRefreshedInstant =
                        lastRefreshed.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
                // Floored to the second, not compared exactly: ProductSqlExecutor.TIMESTAMP (the
                // formatter that renders products.materializedAt() into the _products view's
                // DuckDB literal) is "yyyy-MM-dd HH:mm:ss" — no fractional seconds — so
                // last_refreshed genuinely loses sub-second precision before AranCursor/Avatica
                // ever see it. Pre-existing, unrelated to V46, confirmed by reading that
                // formatter; tolerating it here (rather than pinning the fractional part) is what
                // makes this a correct assertion instead of a flaky one.
                assertThat(lastRefreshedInstant)
                        .as("last_refreshed falls inside the window materialize() actually ran in, "
                                + "modulo its own whole-seconds truncation")
                        .isBetween(beforeMaterialize.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                                afterMaterialize);
                assertThat(rs.getObject(lastRefreshedIdx)).as("getObject() agrees with getTimestamp()")
                        .isEqualTo(lastRefreshed);
                assertThat(rs.getString(lastRefreshedIdx)).as("getString() does not throw").isNotBlank();
            }

            // Unqualified OQL still fails cleanly on the products schema — this is SQL here.
            try (Statement st = conn.createStatement()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> st.executeQuery("SELECT " + className + ".id FROM " + className))
                        .as("the graph class name means nothing as a SQL table on products")
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }

        // ── 7. products. -qualified SQL reaches the registry from the graph-default connection ──
        try (Connection conn = DriverManager.getConnection(baseUrl)) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM products._products")) {
                assertThat(rs.next())
                        .as("a products.-qualified statement takes the passthrough path even on graph")
                        .isTrue();
            }
        }
    }

    /**
     * A tiny two-row Parquet snapshot with a real DATE and a real TIMESTAMP column. Unlike the
     * CSV path this test used before V46 (see the comment at the registration call site),
     * Parquet carries a real column schema, which is what lets {@code ParquetBootstrap} record
     * {@code xsd:date}/{@code xsd:dateTime} instead of the CSV path's blanket {@code xsd:string}
     * — without that, no column in this test would ever be declared DATE/TIMESTAMP anywhere
     * downstream, and the Rep bug would never be reachable. Built with DuckDB itself (already on
     * the classpath — {@link AranCursor} uses it the same way) rather than a Parquet-writing
     * library this module has no other reason to depend on. {@code tz_created} (I1, review) is a
     * real TIMESTAMPTZ column, uniform across both people datasets so it can be a proper
     * DATATYPE-mapped property rather than an ambiguous unmapped one (see ORDERED_PROPERTIES'
     * javadoc).
     */
    private static byte[] peopleParquet(long id1, String name1, LocalDate joined1, LocalDateTime created1,
                                         java.time.OffsetDateTime tzCreated1,
                                         long id2, String name2, LocalDate joined2, LocalDateTime created2,
                                         java.time.OffsetDateTime tzCreated2)
            throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Path tmp = Files.createTempFile("jdbc-it-people-", ".parquet");
        // M4 (review): the whole build, not just the read, is inside this try — a DuckDB
        // statement throwing (a bad literal, a driver hiccup) used to skip straight past the
        // temp-file cleanup below it entirely, leaking the file.
        try {
            try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                 Statement st = duck.createStatement()) {
                st.execute("CREATE TABLE t(id BIGINT, name VARCHAR, joined_on DATE, created_at TIMESTAMP, "
                        + "tz_created TIMESTAMPTZ)");
                st.execute(String.format(Locale.ROOT,
                        "INSERT INTO t VALUES (%d, '%s', DATE '%s', TIMESTAMP '%s', TIMESTAMPTZ '%s'), "
                                + "(%d, '%s', DATE '%s', TIMESTAMP '%s', TIMESTAMPTZ '%s')",
                        id1, name1, joined1, created1.format(TIMESTAMP_LITERAL), tzCreated1.format(TZ_LITERAL),
                        id2, name2, joined2, created2.format(TIMESTAMP_LITERAL), tzCreated2.format(TZ_LITERAL)));
                st.execute("COPY t TO '" + tmp.toAbsolutePath() + "' (FORMAT PARQUET)");
            }
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * {@code getDate}/{@code getObject}/{@code getString} on a DATE column, through
     * {@code AranCursor}'s V46 Rep fix — asserting the round-tripped VALUE, not merely that the
     * call doesn't throw (every one of these three threw {@code ClassCastException} before the
     * fix, per the register).
     *
     * <p><b>TZ-safety</b>: {@code java.sql.Date.valueOf(LocalDate)}/{@code toLocalDate()} encode
     * and decode through the JVM's default {@code TimeZone} identically in both directions, so
     * whatever offset that TimeZone applies cancels out — and Avatica's own
     * {@code DateFromNumberAccessor.getDate(Calendar)} is built the same way. Confirmed
     * empirically, not assumed: the real avatica-core 1.27.0 accessor classes were driven via
     * reflection across six zones (UTC, America/Los_Angeles, Pacific/Kiritimati at +14:00,
     * Asia/Kathmandu at +5:45, Australia/Lord_Howe at +10:30, Europe/Madrid) before this test was
     * written, and {@code toLocalDate()} recovered the exact calendar date in every one.
     * {@code getString()} is TZ-invariant outright — {@code AbstractCursor$DateFromNumberAccessor
     * .getString()} passes {@code null} for the Calendar, so {@code DateTimeUtils.
     * unixDateToString} never sees a timezone at all.
     *
     * <p>Takes a 1-based column INDEX, not a label: OQL's unquoted {@code Class.property}
     * projections do not reliably come back labelled by the bare property name (case-folding is
     * the parser's call, not this test's), and the position is already known exactly — every
     * caller builds its SELECT list from a fixed, explicit column order.
     */
    private static void assertDateColumn(ResultSet rs, int columnIndex, LocalDate expected) throws SQLException {
        assertThat(rs.getDate(columnIndex).toLocalDate()).as("column " + columnIndex + " via getDate()").isEqualTo(expected);
        assertThat(((java.sql.Date) rs.getObject(columnIndex)).toLocalDate())
                .as("column " + columnIndex + " via getObject()").isEqualTo(expected);
        assertThat(rs.getString(columnIndex)).as("column " + columnIndex + " via getString()").isEqualTo(expected.toString());
    }

    /**
     * {@code getTimestamp}/{@code getObject}/{@code getString} on a TIMESTAMP column — the same
     * proof as {@link #assertDateColumn}, for the other half of V46.
     *
     * <p><b>TZ-safety</b>: {@code Timestamp.toLocalDateTime()} decomposes off the JVM's default
     * {@code TimeZone}, and on the real client path {@code AvaticaResultSet.getTimestamp(int)}
     * forwards its own {@code localCalendar} field (never {@code null} — confirmed by reading its
     * bytecode) into {@code TimestampFromNumberAccessor.getTimestamp(Calendar)}, which is built
     * the same default-zone way — so the two conversions cancel, verified the same
     * reflection-driven way as {@link #assertDateColumn}, across the same six zones.
     * {@code getString()} again passes no Calendar, so its exact
     * {@code "yyyy-MM-dd HH:mm:ss"} text is TZ-invariant outright.
     *
     * <p>Takes a 1-based column INDEX — see {@link #assertDateColumn} for why.
     */
    private static void assertTimestampColumn(ResultSet rs, int columnIndex, LocalDateTime expected)
            throws SQLException {
        assertThat(rs.getTimestamp(columnIndex).toLocalDateTime())
                .as("column " + columnIndex + " via getTimestamp()").isEqualTo(expected);
        assertThat(((java.sql.Timestamp) rs.getObject(columnIndex)).toLocalDateTime())
                .as("column " + columnIndex + " via getObject()").isEqualTo(expected);
        assertThat(rs.getString(columnIndex)).as("column " + columnIndex + " via getString()")
                .isEqualTo(expected.format(TIMESTAMP_LITERAL));
    }

    private void wireLakeFake() {
        when(lakeClient.initUpload(any(UUID.class), any(UUID.class), anyLong(), any()))
                .thenAnswer(inv -> {
                    pendingContentType = inv.getArgument(3);
                    return new LakeClient.InitUploadResponse(
                            "upload-" + UUID.randomUUID(), "http://mock/upload", inv.getArgument(3));
                });
        doAnswer(inv -> {
            pendingUpload = inv.getArgument(1);
            return null;
        }).when(lakeClient).uploadToS3(any(String.class), any(byte[].class), any(String.class));
        when(lakeClient.computeSha256(any())).thenReturn("hash");
        when(lakeClient.completeUpload(any(UUID.class), any(String.class), any(String.class), anyLong()))
                .thenAnswer(inv -> {
                    String snapshotId = "snap-" + UUID.randomUUID();
                    lakeStore.put(snapshotId, pendingUpload);
                    lakeContentTypes.put(snapshotId,
                            pendingContentType != null ? pendingContentType : "text/csv");
                    return snapshotId;
                });
        when(lakeClient.downloadSnapshot(any(UUID.class), any(String.class)))
                .thenAnswer(inv -> new LakeClient.SnapshotBytes(
                        lakeStore.get(inv.getArgument(1, String.class)),
                        lakeContentTypes.getOrDefault(inv.getArgument(1, String.class), "text/csv")));
        doAnswer(inv -> {
            String snapshotId = inv.getArgument(1);
            Path target = inv.getArgument(2);
            byte[] bytes = lakeStore.get(snapshotId);
            if (bytes == null) {
                throw new IOException("no such snapshot in the fake lake store: " + snapshotId);
            }
            Files.write(target, bytes);
            return lakeContentTypes.getOrDefault(snapshotId, "text/csv");
        }).when(lakeClient).downloadSnapshotTo(any(UUID.class), any(String.class), any(Path.class));
    }
}

package edu.upc.dtak.aran.app.e2e;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRegistration;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.bootstrapping.BootstrappingOperator;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalogOperator;
import edu.upc.dtak.aran.operators.queryengine.QueryEngineOperator;
import edu.upc.dtak.aran.operators.schemaintegration.SchemaIntegrationOperator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V45 on the path a user actually walks: a table of a <em>federated</em> PostgreSQL repository is
 * enrolled with no file and no snapshot, bootstrapped, integrated, promoted, and then queried —
 * and every row it returns is read out of the live database at query time, never out of the lake.
 *
 * <p>Live on 2026-08-27 the bootstrap answered {@code NO_SNAPSHOT} and stopped there:
 * {@code BootstrappingOperator.prepareCandidate} demanded {@code dataset.snapshotId() != null} and
 * built only {@code SourceDescriptor.ofFile}, so {@code JdbcConnector} and
 * {@code SourceDescriptor.ofJdbc} had no production call site at all. Everything downstream was
 * already in place — {@code SourceAccess.resolve} returns FEDERATED off the repository flag,
 * {@code FederatedScanExpression} builds the live read (an attached catalog since the
 * closing review moved Postgres off {@code postgres_scan}, whose DSN rode in every view body), and
 * {@code QueryEngineOperator}'s snapshot resolver has the branch that returns it — which is why the
 * fix is bootstrap wiring and nothing else, and why this test asserts on rows rather than on a
 * mocked seam.
 *
 * <p><b>Two PostgreSQL containers, and they are not the same database.</b>
 * {@link BaseIntegrationTest} starts Aran's own (projects, datasets, profiles); {@link #SOURCE} is
 * the customer database being federated. Nothing ever copies between them: the only thing that
 * crosses is a read DuckDB issues against the attached source while the query runs.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> {@code DatasetService} is {@code
 * @Transactional} at class level and its inline catalog harvest flushes at that commit, so a test
 * holding its own never-committing transaction would not be watching the production sequence.
 */
@DisplayName("Federated relational table → bootstrap → compile → live rows (V45)")
class FederatedRelationalBootstrapIT extends BaseIntegrationTest {

    /**
     * The same file {@link BaseIntegrationTest} guards on. Its presence means the sandbox has no
     * bridge networking, so Testcontainers cannot start a container at all and one pre-started,
     * host-networked PostgreSQL is serving Aran's own schema instead.
     */
    private static final Path CONTAINER_INFO_PATH = Path.of(".container-info.json");

    /**
     * The customer database. Federated, never imported — no row of it reaches the lake.
     *
     * <p>Constructed here but <b>started in {@link #startSourceDatabase}</b>, never in a static
     * initializer. This class needs a SECOND database, which the escape hatch above cannot supply
     * (it provides exactly one shared instance, and that one is Aran's own). A container started at
     * class-load would fail class initialization there and take this class down while every other
     * IT in the module still passed; the assumption turns that into an honest skip naming the
     * reason.
     */
    private static final PostgreSQLContainer<?> SOURCE =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("srcdb")
                    .withUsername("srcuser")
                    .withPassword("srcpw");

    /**
     * The PostgreSQL driver already on this module's test classpath, instead of
     * {@code HttpJdbcDriverDownloader}'s Maven Central fetch — the enrolment path's inline catalog
     * harvest wants a driver, and downloading one is neither what this test is about nor something
     * an offline run should depend on. Also forks this class its own Spring context, which the
     * in-memory {@code GraphStore} (tenant-agnostic, shared per context) needs.
     */
    @TestConfiguration
    static class ClasspathDriverConfig {
        @Bean
        @Primary
        DriverDownloader classpathDriverDownloader() {
            return new DriverDownloader() {
                @Override
                public Driver loadDriver(String engine) throws Exception {
                    return DriverManager.getDriver(SOURCE.getJdbcUrl());
                }

                @Override
                public void unloadDriver(String engine) {
                    // Nothing sandboxed to release.
                }
            };
        }
    }

    @Autowired DatasetService datasetService;
    @Autowired BootstrappingOperator bootstrapping;
    @Autowired SchemaIntegrationOperator integration;
    @Autowired QueryEngineOperator queryEngine;
    @Autowired MappingCatalogOperator catalogOperator;
    @Autowired IntegrationModelService integrationModel;
    @Autowired GraphStore graphStore;
    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired MockMvc mockMvc;

    private final ObjectMapper om = new ObjectMapper();

    private AuthenticatedUser caller;
    private TenantContext ctx;
    private UUID userId;
    private Project project;
    private RelationalDatabaseRepository repository;

    @BeforeAll
    static void startSourceDatabase() throws Exception {
        assumeFalse(Files.exists(CONTAINER_INFO_PATH),
                "constrained Docker: no bridge networking, so the "
                        + "second, independent database this test federates over cannot be started");
        SOURCE.start();
        try (Connection conn = DriverManager.getConnection(
                SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE venue (
                        venue_id INTEGER,
                        name     TEXT,
                        city     TEXT,
                        capacity BIGINT
                    )""");
            stmt.execute("INSERT INTO venue VALUES (1, 'Palau', 'Barcelona', 2200)");
            stmt.execute("INSERT INTO venue VALUES (2, 'Auditori', 'Barcelona', 2400)");
            stmt.execute("""
                    CREATE TABLE concert (
                        concert_id INTEGER,
                        title      TEXT,
                        venue_id   INTEGER
                    )""");
            stmt.execute("INSERT INTO concert VALUES (10, 'Turandot', 1)");
            stmt.execute("INSERT INTO concert VALUES (11, 'Rigoletto', 2)");
        }
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));
        tenants.save(new Tenant(tenantId, "tenant-" + suffix, "Tenant", Instant.now()));

        caller = new AuthenticatedUser(userId, "sub-" + suffix, tenantId, TenantRole.OWNER);
        ctx = new TenantContext(tenantId.toString());
        project = projects.save(ctx, new Project(
                UUID.randomUUID(), tenantId, "Federated-" + suffix, "", userId, Instant.now()));
        projects.addMember(ctx, new ProjectMember(
                UUID.randomUUID(), project.id(), userId, Instant.now()));
        repository = (RelationalDatabaseRepository) repositories.save(ctx,
                new RelationalDatabaseRepository(
                        UUID.randomUUID(), project.id(), tenantId, "customer-" + suffix, "",
                        "POSTGRESQL", SOURCE.getHost(), SOURCE.getFirstMappedPort(),
                        SOURCE.getDatabaseName(), SOURCE.getUsername(), SOURCE.getPassword(),
                        "public", true));
    }

    @Test
    @DisplayName("a snapshot-less federated table bootstraps from the live catalog and answers OQL with live rows")
    void federatedTableBootstrapsAndServesLiveRows() throws Exception {
        Dataset venue = enrol("venue");
        Dataset concert = enrol("concert");
        assertThat(venue.snapshotId())
                .as("a federated relational dataset has no snapshot, by definition")
                .isNull();

        // ── 1. bootstrap: NO_SNAPSHOT live on 2026-08-27 ────────────────────────────────────
        OperatorResult boot = bootstrap(venue);
        assertThat(boot.success())
                .as("bootstrap: %s / %s", boot.errorCode(), boot.errorMessage())
                .isTrue();
        assertThat(boot.data()).containsEntry("format", "JDBC");
        assertThat(datasets.findById(ctx, venue.id()).orElseThrow().bootstrapStatus())
                .isEqualTo(DatasetStatus.READY);
        assertThat(bootstrap(concert).success()).isTrue();

        // ── 2. the source graph the query engine needs: class, wrapper, live scan metadata ───
        Model venueGraph = sourceGraphOf(venue);
        assertThat(venueGraph.isEmpty()).as("bootstrap must have written a source graph").isFalse();
        assertThat(wrapperSqlOf(venueGraph))
                .as("the wrapper is the flat projection a relational scan needs, not a JSON unnest")
                .contains("${SNAPSHOT}")
                .contains("\"venue_id\"")
                .contains("\"capacity\"")
                .doesNotContain("UNNEST");

        // ── 3. compile + promote. The join alignment is what mints integrated classes at all —
        //       source-kind-agnostic, and the same shape SourcesToOqlEndToEndTest uses on files.
        OperatorResult compiled = integration.execute(new OperatorRequest(
                ctx.value(), userId, "compile", Map.of(
                        "sourceDatasetIds",
                        om.writeValueAsString(List.of(venue.id().toString(), concert.id().toString())),
                        "alignments", om.writeValueAsString(List.of(Map.of(
                                "iriA", propertyIriOf(sourceGraphOf(concert), "venue_id"),
                                "iriB", propertyIriOf(venueGraph, "venue_id"),
                                "label", "venueKey|atVenue|forward",
                                "type", "join"))),
                        "modelId", UUID.randomUUID().toString())));
        assertThat(compiled.success())
                .as("compile: %s / %s", compiled.errorCode(), compiled.errorMessage())
                .isTrue();
        graphStore.promote(ctx, UUID.fromString(compiled.data().get("draftVersionId")),
                new AuditedActor(userId));

        MappingCatalog catalog = catalogOperator.getCatalog(ctx);
        MappingCatalog.JoinProperty edge = catalog.joinProperties().stream()
                .filter(j -> Names.localName(j.iri()).equals("atVenue")).findFirst()
                .orElseThrow(() -> new AssertionError("no atVenue edge; catalog has "
                        + catalog.joinProperties().stream().map(j -> Names.localName(j.iri())).toList()));
        String concertCls = Names.localName(edge.leftClass());
        String venueCls = Names.localName(edge.rightClass());
        MappingCatalog.IntegratedClass venueClass = catalog.classes().get(edge.rightClass());
        String nameCol = propertyNamed(venueClass, "name");
        String cityCol = propertyNamed(venueClass, "city");

        // ── 4. OQL over the class returns rows that were never copied anywhere ──────────────
        OperatorResult projection = runOql(
                "SELECT " + venueCls + "." + nameCol + ", " + venueCls + "." + cityCol
                        + " FROM " + venueCls);
        // The scan expression itself lives in the src_ view's DDL, not in the statement Calcite
        // unparses, so the plan's own access annotation is what says which branch of the resolver
        // ran. Nothing is in the lake, so the alternative is not a slower answer — it is
        // "No snapshot recorded for dataset".
        assertThat(planShapeOf(projection))
                .as("the plan must read the remote where it lives, not a lake snapshot")
                .contains("access=FEDERATED");
        assertThat(rowsOf(projection))
                .containsExactlyInAnyOrder(
                        List.of("Palau", "Barcelona"),
                        List.of("Auditori", "Barcelona"));

        // A join across two live scans, in one DuckDB session, with nothing in the lake.
        String joinOql = "SELECT " + concertCls + "."
                + propertyNamed(catalog.classes().get(edge.leftClass()), "title")
                + ", " + venueCls + "." + nameCol
                + " FROM " + concertCls + " -[atVenue]-> " + venueCls;
        assertThat(rowsOf(runOql(joinOql)))
                .containsExactlyInAnyOrder(
                        List.of("Turandot", "Palau"),
                        List.of("Rigoletto", "Auditori"));

        // ── 5. LIVE means live: a row inserted after the promote appears with no refresh ────
        execOnSource("INSERT INTO venue VALUES (3, 'Liceu', 'Barcelona', 2292)");

        assertThat(rowsOf(runOql("SELECT " + venueCls + "." + nameCol
                + ", " + venueCls + "." + cityCol + " FROM " + venueCls)))
                .as("nothing was refreshed, re-bootstrapped or re-promoted — the read is the source")
                .containsExactlyInAnyOrder(
                        List.of("Palau", "Barcelona"),
                        List.of("Auditori", "Barcelona"),
                        List.of("Liceu", "Barcelona"));
    }

    @Test
    @DisplayName("re-bootstrapping re-introspects the live catalog, so a new column reaches the graph")
    void reBootstrapPicksUpASourceSchemaChange() throws Exception {
        String table = "artist_" + UUID.randomUUID().toString().substring(0, 8);
        execOnSource("CREATE TABLE " + table + " (artist_id INTEGER, name TEXT)");

        Dataset enrolled = enrol(table);
        assertThat(bootstrap(enrolled).success()).isTrue();

        execOnSource("ALTER TABLE " + table + " ADD COLUMN country TEXT");

        OperatorResult again = bootstrap(enrolled);
        assertThat(again.success())
                .as("re-bootstrap: %s / %s", again.errorCode(), again.errorMessage())
                .isTrue();
        assertThat(again.data()).containsEntry("applied", "true");
        assertThat(wrapperSqlOf(sourceGraphOf(enrolled)))
                .as("the added column must reach the wrapper the next compile will carry")
                .contains("\"country\"");
    }

    @Test
    @DisplayName("a re-bootstrap re-seeds the enrolled unedited wrapper, so the compile cannot revive it (V47)")
    void reBootstrapReseedsAnUneditedEnrolledWrapper() throws Exception {
        // V47's exact sequence, on the source kind V47 could not have seen — because until this
        // change no federated dataset could be bootstrapped at all. A federated dataset reaches
        // NONE of the paths V47 re-seeds from: it is created by registerDataset (no job, no
        // runBootstrapPipeline), it has no import and no refresh, and its only bootstrap is the raw
        // operator invoke that AlignmentsView's "Run Bootstrapping Operator" submits. Without the
        // re-seed at the graph write, the next compile pushes the enrolment-time copy — still
        // naming "city" — back over the regenerated wrapper, and every query over the class dies on
        // DuckDB's `Binder Error: Referenced column "city" not found in FROM clause!`.
        String table = "hall_" + UUID.randomUUID().toString().substring(0, 8);
        execOnSource("CREATE TABLE " + table + " (hall_id INTEGER, name TEXT, city TEXT)");

        Dataset enrolled = enrol(table);
        assertThat(bootstrap(enrolled).success()).isTrue();

        // Enrol in the integration model: this is what seeds model_source.wrapper_sql from the
        // class's nx:wrapperSql, with wrapper_edited = false.
        List<ModelSource> seeded = integrationModel.addSource(ctx, project.id(), enrolled.id());
        assertThat(seeded).singleElement().satisfies(s -> {
            assertThat(s.wrapperSql()).contains("\"city\"");
            assertThat(s.wrapperEdited()).isFalse();
        });

        execOnSource("ALTER TABLE " + table + " RENAME COLUMN city TO city_name");
        assertThat(bootstrap(enrolled).success()).isTrue();

        assertThat(integrationModel.getBundle(ctx, project.id()).sources().stream()
                .filter(s -> s.datasetId().equals(enrolled.id())).toList())
                .as("the stored wrapper must follow its source, or the compile revives the dropped column")
                .singleElement().satisfies(s -> {
                    assertThat(s.wrapperSql()).contains("\"city_name\"").doesNotContain("\"city\"");
                    assertThat(s.wrapperEdited())
                            .as("a re-seed is not an edit — the flag must still say nobody typed this")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("V65: a live timestamptz column reads as its UTC wall time, whatever zone the host is in")
    void aLiveTimestamptzReadsInUtcWhateverTheHostZone() throws Exception {
        // The pin's live effect on the federated path: the Postgres scanner hands DuckDB a TIMESTAMPTZ,
        // and the int_ view casts xsd:dateTime to a naive TIMESTAMP — a cast DuckDB performs in
        // the SESSION's zone. Unpinned on a Europe/Madrid host, 23:30Z would read as 01:30 the
        // next day and fall out of a same-day predicate. Vacuous on a UTC host (so is
        // DuckDbSessionTest); run with TZ=Europe/Madrid to make it bite — the control below
        // proves the host really was non-UTC when it does.
        String hostZone;
        try (Connection unpinned = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = unpinned.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT current_setting('TimeZone'),"
                     + " (TIMESTAMPTZ '2020-06-01 23:30:00+00')::TIMESTAMP::VARCHAR")) {
            rs.next();
            hostZone = rs.getString(1);
            System.out.println("[V65] host DuckDB zone: " + hostZone
                    + "; unpinned cast of 23:30Z: " + rs.getString(2));
            if (!"UTC".equals(hostZone) && !"Etc/UTC".equals(hostZone)) {
                assertThat(rs.getString(2))
                        .as("control: on this non-UTC host an unpinned session moves the wall time")
                        .isNotEqualTo("2020-06-01 23:30:00");
            }
        }

        String table = "gig_" + UUID.randomUUID().toString().substring(0, 8);
        execOnSource("CREATE TABLE " + table + " (gig_id INTEGER, starts TIMESTAMPTZ, venue_id INTEGER)");
        execOnSource("INSERT INTO " + table + " VALUES"
                + " (1, '2020-06-01 10:00:00+00', 1), (2, '2020-06-01 23:30:00+00', 2)");
        Dataset gig = enrol(table);
        Dataset venue = enrol("venue");
        assertThat(bootstrap(gig).success()).isTrue();
        assertThat(bootstrap(venue).success()).isTrue();

        // A join alignment is what mints integrated classes (see the first test).
        OperatorResult compiled = integration.execute(new OperatorRequest(
                ctx.value(), userId, "compile", Map.of(
                        "sourceDatasetIds",
                        om.writeValueAsString(List.of(gig.id().toString(), venue.id().toString())),
                        "alignments", om.writeValueAsString(List.of(Map.of(
                                "iriA", propertyIriOf(sourceGraphOf(gig), "venue_id"),
                                "iriB", propertyIriOf(sourceGraphOf(venue), "venue_id"),
                                "label", "gigVenueKey|playsAt|forward",
                                "type", "join"))),
                        "modelId", UUID.randomUUID().toString())));
        assertThat(compiled.success())
                .as("compile: %s / %s", compiled.errorCode(), compiled.errorMessage())
                .isTrue();
        graphStore.promote(ctx, UUID.fromString(compiled.data().get("draftVersionId")),
                new AuditedActor(userId));

        MappingCatalog catalog = catalogOperator.getCatalog(ctx);
        MappingCatalog.JoinProperty edge = catalog.joinProperties().stream()
                .filter(j -> Names.localName(j.iri()).equals("playsAt")).findFirst()
                .orElseThrow(() -> new AssertionError("no playsAt edge"));
        MappingCatalog.IntegratedClass gigClass = catalog.classes().get(edge.leftClass());
        String cls = Names.localName(gigClass.iri());
        String idCol = propertyNamed(gigClass, "gig_id");
        String startsCol = propertyNamed(gigClass, "starts");

        assertThat(rowsOf(runOql("SELECT " + cls + "." + idCol + ", " + cls + "." + startsCol
                + " FROM " + cls)))
                .as("host zone %s", hostZone)
                .containsExactlyInAnyOrder(
                        List.of("1", "2020-06-01 10:00:00.0"),
                        List.of("2", "2020-06-01 23:30:00.0"));
        assertThat(rowsOf(runOql("SELECT " + cls + "." + idCol + " FROM " + cls
                + " WHERE " + cls + "." + startsCol + " < '2020-06-02 00:00:00'")))
                .as("row membership, not only rendering: host zone %s", hostZone)
                .containsExactlyInAnyOrder(List.of("1"), List.of("2"));
    }

    @Test
    @DisplayName("registering a federated table with no file starts its bootstrap job, as an upload does (S4-19)")
    void noFileRegistrationStartsTheBootstrap() throws Exception {
        DatasetRegistration registration = datasetService.registerDatasetForLiveRead(caller,
                project.id(), repository.id(),
                new Dataset(UUID.randomUUID(), repository.id(), "venue", "live venue"));

        assertThat(registration.bootstrapJobId()).as("the job id POST …/datasets answers with").isNotNull();
        Job job = awaitTerminal(registration.bootstrapJobId());
        assertThat(job.status()).as("job %s: %s", job.errorCode(), job.errorMessage())
                .isEqualTo(JobStatus.SUCCEEDED);
        assertThat(datasets.findById(ctx, registration.dataset().id()).orElseThrow().bootstrapStatus())
                .isEqualTo(DatasetStatus.READY);
        assertThat(wrapperSqlOf(sourceGraphOf(registration.dataset())))
                .as("bootstrapped from the live catalog, with no snapshot")
                .contains("${SNAPSHOT}").contains("\"capacity\"");
    }

    @Test
    @DisplayName("POST …/datasets with no file on a federated repository answers the bootstrap job to watch (S4-19)")
    void noFileRegistrationOverHttpAnswersTheBootstrapJob() throws Exception {
        // The service-level test above cannot see the controller: reverting it to
        // registerDataset, or dropping bootstrapJobId from the body, would leave the SPA's
        // register dialog with no job to follow while every other test still passed.
        UUID tenantId = UUID.fromString(ctx.value());
        tenants.saveMembership(new Membership(
                UUID.randomUUID(), userId, tenantId, TenantRole.OWNER, Instant.now()));
        String subject = users.findById(userId).orElseThrow().subject();

        String body = mockMvc.perform(post("/api/projects/{p}/repositories/{r}/datasets",
                                project.id(), repository.id())
                        .with(JwtTestHelper.subject(subject, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"venue\",\"description\":\"live venue\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bootstrapStatus").value("PENDING"))
                .andExpect(jsonPath("$.bootstrapJobId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        Job job = awaitTerminal(UUID.fromString(om.readTree(body).get("bootstrapJobId").asText()));
        assertThat(job.status()).as("job %s: %s", job.errorCode(), job.errorMessage())
                .isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("a snapshot-less dataset in a non-federated repository still answers NO_SNAPSHOT")
    void aSnapshotlessDatasetInANonFederatedRepositoryIsStillNoSnapshot() {
        // The live branch is a branch, not a replacement: it is reached only for a source
        // SourceAccess.resolve calls FEDERATED. Everything else keeps the answer it had.
        RelationalDatabaseRepository notFederated = (RelationalDatabaseRepository) repositories.save(ctx,
                new RelationalDatabaseRepository(
                        UUID.randomUUID(), project.id(), UUID.fromString(ctx.value()),
                        "imported", "", "POSTGRESQL", SOURCE.getHost(), SOURCE.getFirstMappedPort(),
                        SOURCE.getDatabaseName(), SOURCE.getUsername(), SOURCE.getPassword(),
                        "public", false));
        Dataset pending = datasetService.registerDataset(caller, project.id(), notFederated.id(),
                new Dataset(UUID.randomUUID(), notFederated.id(), "venue", "awaiting its import"));

        OperatorResult result = bootstrap(pending);
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NO_SNAPSHOT");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private Dataset enrol(String table) {
        return datasetService.registerDataset(caller, project.id(), repository.id(),
                new Dataset(UUID.randomUUID(), repository.id(), table, "live " + table));
    }

    private Job awaitTerminal(UUID jobId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (true) {
            Job job = jobs.findById(ctx, jobId).orElseThrow();
            if (job.status() == JobStatus.SUCCEEDED || job.status() == JobStatus.FAILED
                    || job.status() == JobStatus.CANCELLED) {
                return job;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("job " + jobId + " still " + job.status() + " after 90 s");
            }
            Thread.sleep(200);
        }
    }

    private OperatorResult bootstrap(Dataset dataset) {
        return bootstrapping.execute(new OperatorRequest(
                ctx.value(), userId, "bootstrap", Map.of("datasetId", dataset.id().toString())));
    }

    private Model sourceGraphOf(Dataset dataset) {
        return graphStore.query(ctx, "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:source:"
                + dataset.id() + "> { ?s ?p ?o } }");
    }

    private static void execOnSource(String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private OperatorResult runOql(String oql) {
        OperatorResult result = queryEngine.execute(
                new OperatorRequest(ctx.value(), userId, "run-oql", Map.of("oql", oql)));
        assertThat(result.success())
                .as("run-oql (%s): %s / %s", oql, result.errorCode(), result.errorMessage())
                .isTrue();
        return result;
    }

    private String planShapeOf(OperatorResult result) throws Exception {
        return om.readTree(result.data().get("result")).get("planShapeText").asText();
    }

    private List<List<String>> rowsOf(OperatorResult result) throws Exception {
        JsonNode payload = om.readTree(result.data().get("result"));
        List<List<String>> rows = new java.util.ArrayList<>();
        for (JsonNode row : payload.get("rows")) {
            List<String> cells = new java.util.ArrayList<>();
            for (JsonNode cell : row) {
                cells.add(cell.asText());
            }
            rows.add(cells);
        }
        return rows;
    }

    private static String wrapperSqlOf(Model source) {
        return source.listObjectsOfProperty(source.createProperty(
                        "http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"))
                .toList().stream()
                .map(node -> node.asLiteral().getString())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no nx:wrapperSql on the source graph"));
    }

    /**
     * The source-property IRI for a column, read off the bootstrapped graph rather than rebuilt
     * from {@code BootstrapEngine}'s minting rule — which is package-private, and which a test
     * re-spelling would silently stop tracking.
     */
    private static String propertyIriOf(Model source, String column) {
        return source.listResourcesWithProperty(RDFS.label, column)
                .filterKeep(r -> source.contains(r, RDF.type, RDF.Property))
                .toList().stream()
                .map(Resource::getURI)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no source property for column " + column));
    }

    /**
     * The integrated property backing a source column, as OQL must spell it. {@code columnMap}
     * maps integrated-property IRI → <em>source-property</em> IRI (not the physical name), so the
     * match is on the value's local name.
     */
    private static String propertyNamed(MappingCatalog.IntegratedClass cls, String column) {
        return cls.sources().stream()
                .flatMap(sv -> sv.columnMap().entrySet().stream())
                .filter(e -> Names.localName(e.getValue()).equalsIgnoreCase(column))
                .map(e -> Names.localName(e.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no integrated property for column " + column));
    }
}

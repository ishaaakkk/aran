package edu.upc.dtak.aran.app.e2e;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetPreview;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TableExtractor;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.service.DataProductService;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.service.JdbcDriverRegistry;
import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.DuckDbSession;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A federated MySQL-family repository against a real server: V67's S4-4 half (bootstrap through
 * {@link DriverDownloader}) and V52's MySQL half (the live read).
 *
 * <p><b>The driver is really downloaded.</b> Unlike {@link FederatedRelationalBootstrapIT}, which
 * hands the bootstrap Postgres's classpath driver, this class keeps the production
 * {@code HttpJdbcDriverDownloader}: neither vendor driver is on this module's classpath, so the
 * bootstrap can only succeed through the pinned, hash-checked Maven Central jar in its own
 * classloader — the path S4-4 moved {@code JdbcBootstrap} onto and nothing had exercised against a
 * server. The cache directory is under {@code build/} so a run leaves nothing in the source tree.
 *
 * <p><b>The live read is an attached catalog.</b> Watched here on 2026-09-23, DuckDB 1.5.5's
 * {@code mysql} extension has no {@code mysql_scan} table function, and its DSN grammar is not the
 * single-quoted one {@code MySqlEngine} used to write — so every federated MySQL/MariaDB read
 * failed. Since 2026-09-24 the read is a {@code CREATE SECRET (TYPE mysql)} plus a read-only
 * {@code ATTACH} ({@code MySqlEngine.federatedScan}), and every path that binds it is exercised
 * below against the real server: OQL rows, a join, an aggregate and a pushed filter,
 * verify-wrapper's {@code COUNT(*)} fingerprint, the drift probe, a virtual product's read, and
 * credentials carrying every character a DSN would choke on.
 *
 * <p>The servers are plain {@link GenericContainer}s, not Testcontainers' JDBC modules: those wait
 * by opening a JDBC connection, which needs the vendor driver on the classpath — exactly what
 * would let {@code HttpJdbcDriverDownloader}'s classpath fast-path skip the download under test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "aran.jdbc.driver-cache-dir=build/it-jdbc-drivers")
abstract class AbstractFederatedMySqlFamilyIT extends BaseIntegrationTest {

    /** See {@link FederatedRelationalBootstrapIT}: no bridge networking, no second database. */
    private static final Path CONTAINER_INFO_PATH = Path.of(".container-info.json");

    protected static final String DATABASE = "srcdb";
    protected static final String USER = "srcuser";
    protected static final String PASSWORD = "srcpw";
    protected static final String ROOT_PASSWORD = "rootpw";

    /**
     * Space, both quote characters, a backslash and a would-be keyword: every character a
     * keyword=value DSN would have had to survive (sec-audit F-S1-5) — which is why there is no
     * DSN any more.
     */
    private static final String HOSTILE_USER = "quoted";
    private static final String HOSTILE_PASSWORD = "p w'd\\x\"q host=evil";

    /**
     * The temporary init server logs "ready for connections" with {@code port: 0}; only the real
     * one listens on 3306. Matched on the port rather than the banner because MariaDB prints the
     * port on the line after it; the trailing space keeps MySQL's X plugin ({@code port: 33060})
     * from matching.
     */
    private static final String READY = ".*port: 3306 .*";

    @Autowired DatasetService datasetService;
    @Autowired DataProductService dataProducts;
    @Autowired DataRepositoryService repositoryService;
    @Autowired TableExtractor tableExtractor;
    @Autowired BootstrappingOperator bootstrapping;
    @Autowired SchemaIntegrationOperator integration;
    @Autowired QueryEngineOperator queryEngine;
    @Autowired MappingCatalogOperator catalogOperator;
    @Autowired GraphStore graphStore;
    @Autowired DriverDownloader driverDownloader;
    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    private final ObjectMapper om = new ObjectMapper();

    private GenericContainer<?> source;
    private AuthenticatedUser caller;
    private TenantContext ctx;
    private UUID userId;
    private Project project;
    private RelationalDatabaseRepository repository;

    /** {@code MYSQL} or {@code MARIADB} — the repository engine and the driver registry key. */
    protected abstract String engine();

    /** The image, as a Docker Hub name; the Testcontainers prefix config maps it to a mirror. */
    protected abstract String image();

    /** Environment variables naming the root password, the database and its user. */
    protected abstract Map<String, String> env();

    /** The command-line client inside the image ({@code mysql} or {@code mariadb}). */
    protected abstract String client();

    @BeforeAll
    void startSource() throws Exception {
        assumeFalse(Files.exists(CONTAINER_INFO_PATH),
                "constrained Docker: no bridge networking, so the "
                        + "source database this test federates over cannot be started");
        source = new GenericContainer<>(image())
                .withEnv(env())
                .withExposedPorts(3306)
                .waitingFor(Wait.forLogMessage(READY, 1).withStartupTimeout(Duration.ofMinutes(3)));
        source.start();
        execAsRoot("""
                CREATE TABLE venue (
                    venue_id INT,
                    name     VARCHAR(64),
                    city     VARCHAR(64),
                    capacity BIGINT
                );
                INSERT INTO venue VALUES (1, 'Palau', 'Barcelona', 2200), (2, 'Auditori', 'Barcelona', 2400);
                CREATE TABLE concert (concert_id INT, title VARCHAR(64), venue_id INT);
                INSERT INTO concert VALUES (10, 'Turandot', 1), (11, 'Rigoletto', 2);
                CREATE TABLE events (event_id INT, title VARCHAR(64));
                CREATE USER '%s'@'%%' IDENTIFIED BY '%s';
                GRANT SELECT ON %s.* TO '%s'@'%%';
                """.formatted(HOSTILE_USER, mysqlLiteral(HOSTILE_PASSWORD), DATABASE, HOSTILE_USER));
    }

    @AfterAll
    void stopSource() {
        if (source != null) {
            source.stop();
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
                UUID.randomUUID(), tenantId, engine() + "-" + suffix, "", userId, Instant.now()));
        projects.addMember(ctx, new ProjectMember(
                UUID.randomUUID(), project.id(), userId, Instant.now()));
        // "public" is what RegisterRepositoryDialog pre-fills for every engine, so it is what a
        // MySQL repository registered through the UI carries unless someone clears it.
        repository = (RelationalDatabaseRepository) repositories.save(ctx,
                new RelationalDatabaseRepository(
                        UUID.randomUUID(), project.id(), tenantId, "customer-" + suffix, "",
                        engine(), source.getHost(), source.getMappedPort(3306),
                        DATABASE, USER, PASSWORD, "public", true));
    }

    @Test
    @DisplayName("S4-4: the driver is the downloaded jar in its own classloader, never the application classpath")
    void theDriverComesFromTheDownloader() throws Exception {
        String driverClass = JdbcDriverRegistry.get(engine()).orElseThrow().driverClassName();
        assertThatThrownBy(() -> Class.forName(driverClass))
                .as("with the vendor driver on the classpath the downloader's fast path would skip "
                        + "the download, and this class would not be testing S4-4")
                .isInstanceOf(ClassNotFoundException.class);

        Driver driver = driverDownloader.loadDriver(engine());
        assertThat(driver.getClass().getName()).isEqualTo(driverClass);
        assertThat(driver.getClass().getClassLoader())
                .isNotSameAs(getClass().getClassLoader())
                .isInstanceOf(java.net.URLClassLoader.class);
    }

    @Test
    @DisplayName("a repository whose Schema field holds the pre-filled \"public\" previews its tables: the database is the schema")
    void previewIgnoresTheSchemaField() {
        // Both drivers ignore getTables' schemaPattern, so the scan lists the table whatever the
        // field says. A preview that then qualified with the field read `public`.`venue`, a
        // database that does not exist, and failed.
        assertThat(repository.schema()).isEqualTo("public");
        assertThat(repositoryService.listAvailableDatasets(caller, project.id(), repository.id()))
                .contains("venue");

        DatasetPreview preview =
                repositoryService.previewDataset(caller, project.id(), repository.id(), "venue");

        assertThat(preview.headers()).containsExactly("venue_id", "name", "city", "capacity");
        assertThat(preview.rows())
                .extracting(row -> row.get(1).toString())
                .containsExactlyInAnyOrder("Palau", "Auditori");
    }

    @Test
    @DisplayName("the generic JDBC extract, MySQL's only import, copies the table from the connection's database whatever the Schema field says")
    void jdbcExtractIgnoresTheSchemaField() {
        assertThat(repository.schema()).isEqualTo("public");

        TableExtractor.Extracted extracted = tableExtractor.extract(repository, "venue", 10_000_000);

        assertThat(extracted.rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("S4-4: a snapshot-less federated table bootstraps from the live catalog through the downloaded driver")
    void federatedTableBootstrapsThroughTheDownloadedDriver() {
        Dataset venue = enrol("venue");
        assertThat(venue.snapshotId()).isNull();

        OperatorResult boot = bootstrap(venue);
        assertThat(boot.success())
                .as("bootstrap: %s / %s", boot.errorCode(), boot.errorMessage())
                .isTrue();
        assertThat(boot.data()).containsEntry("format", "JDBC");
        assertThat(datasets.findById(ctx, venue.id()).orElseThrow().bootstrapStatus())
                .isEqualTo(DatasetStatus.READY);

        Model graph = sourceGraphOf(venue);
        assertThat(wrapperSqlOf(graph))
                .as("a relational table is FLAT: a projection, not a JSON unnest")
                .contains("${SNAPSHOT}")
                .contains("\"venue_id\"")
                .contains("\"capacity\"")
                .doesNotContain("UNNEST");
        assertThat(columnRanges(graph))
                .as("introspected from the catalog and typed from JDBC types")
                .containsExactlyInAnyOrder(
                        Map.entry("venue_id", XSD.integer.getURI()),
                        Map.entry("name", XSD.xstring.getURI()),
                        Map.entry("city", XSD.xstring.getURI()),
                        Map.entry("capacity", XSD.xlong.getURI()));
    }

    @Test
    @DisplayName("a table sharing its name with an INFORMATION_SCHEMA view gets only its own columns")
    void aTableNamedLikeACatalogViewIsNotMergedWithIt() {
        // On MySQL and MariaDB the JDBC catalog is the database, so a column lookup that does not
        // name it searches every database — and INFORMATION_SCHEMA.EVENTS is visible to any
        // user. MariaDB 11 merged its 24 columns into this table's two until JdbcBootstrap passed
        // the connection's catalog.
        Dataset events = enrol("events");
        OperatorResult boot = bootstrap(events);
        assertThat(boot.success())
                .as("bootstrap: %s / %s", boot.errorCode(), boot.errorMessage())
                .isTrue();
        assertThat(columnRanges(sourceGraphOf(events)).stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("event_id", "title");
    }

    @Test
    @DisplayName("V52: a snapshot-less federated table answers OQL with live rows — a join, an aggregate and a filter too")
    void federatedTableAnswersOqlWithLiveRows() throws Exception {
        Promoted live = compileAndPromote();

        OperatorResult projection = runOql("SELECT " + live.venueCls() + "." + live.venueName()
                + ", " + live.venueCls() + "." + live.venueCity() + " FROM " + live.venueCls());
        assertThat(planShapeOf(projection))
                .as("the plan must read the remote where it lives, not a lake snapshot")
                .contains("access=FEDERATED");
        assertThat(rowsOf(projection)).containsExactlyInAnyOrder(
                List.of("Palau", "Barcelona"), List.of("Auditori", "Barcelona"));

        // Two attached tables of one repository, joined in one DuckDB session.
        assertThat(rowsOf(runOql("SELECT " + live.concertCls() + "." + live.concertTitle()
                + ", " + live.venueCls() + "." + live.venueName()
                + " FROM " + live.concertCls() + " -[atVenue]-> " + live.venueCls())))
                .containsExactlyInAnyOrder(
                        List.of("Turandot", "Palau"), List.of("Rigoletto", "Auditori"));

        // An aggregate over the int_/u_/w_/src_ view stack: with the extension's aggregate
        // pushdown on, DuckDB 1.5.5 fails this with an internal binder error.
        assertThat(rowsOf(runOql("SELECT " + live.venueCls() + "." + live.venueCity()
                + ", COUNT(*) AS n FROM " + live.venueCls()
                + " GROUP BY " + live.venueCls() + "." + live.venueCity())))
                .containsExactly(List.of("Barcelona", "2"));
        assertThat(rowsOf(runOql("SELECT " + live.venueCls() + "." + live.venueName()
                + " FROM " + live.venueCls() + " WHERE " + live.venueCls() + "."
                + live.venueCapacity() + " > 2300")))
                .containsExactly(List.of("Auditori"));

        // LIVE means live: a row inserted after the promote appears with no refresh.
        execAsRoot("INSERT INTO venue VALUES (3, 'Liceu', 'Barcelona', 2292)");
        try {
            assertThat(rowsOf(runOql("SELECT " + live.venueCls() + "." + live.venueName()
                    + " FROM " + live.venueCls())))
                    .as("nothing was refreshed, re-bootstrapped or re-promoted — the read is the source")
                    .containsExactlyInAnyOrder(List.of("Palau"), List.of("Auditori"), List.of("Liceu"));
        } finally {
            execAsRoot("DELETE FROM venue WHERE venue_id = 3");
        }
    }

    @Test
    @DisplayName("V52: verify-wrapper's COUNT(*) + row-hash fingerprint runs over the live table and earns EXPANDED")
    void verifyWrapperFingerprintsTheLiveTable() throws Exception {
        Promoted live = compileAndPromote();
        Model venueGraph = sourceGraphOf(live.venue());

        OperatorResult verdict = queryEngine.execute(new OperatorRequest(
                ctx.value(), userId, "verify-wrapper", Map.of(
                        "datasetId", live.venue().id().toString(),
                        "classIri", sourceClassIriOf(venueGraph),
                        "sql", wrapperSqlOf(venueGraph))));

        assertThat(verdict.success()).as(String.valueOf(verdict.errorMessage())).isTrue();
        assertThat(verdict.data().get("status"))
                .as("reason: %s", verdict.data().get("reason"))
                .isEqualTo("EXPANDED");
    }

    @Test
    @DisplayName("V52: the drift probe describes the live table through the same secret and ATTACH")
    void driftProbeReadsTheLiveSchema() {
        Dataset venue = enrol("venue");
        assertThat(bootstrap(venue).success()).isTrue();

        OperatorResult probe = bootstrapping.execute(new OperatorRequest(
                ctx.value(), userId, "drift-probe", Map.of("datasetId", venue.id().toString())));

        assertThat(probe.success())
                .as("drift-probe: %s / %s", probe.errorCode(), probe.errorMessage())
                .isTrue();
        assertThat(probe.data())
                .containsEntry("columnCount", "4")
                .containsEntry("engine", engine());
    }

    @Test
    @DisplayName("V52: a VIRTUAL product over the live table reads its rows through the products tier")
    void virtualProductReadsTheLiveTable() throws Exception {
        Promoted live = compileAndPromote();
        String name = "venues_live_" + UUID.randomUUID().toString().substring(0, 8);
        dataProducts.create(ctx, project.id(), name, null,
                "SELECT " + live.venueCls() + "." + live.venueName()
                        + " FROM " + live.venueCls(),
                userId, ProductAccessMode.VIRTUAL);

        OperatorResult read = queryEngine.execute(new OperatorRequest(
                ctx.value(), userId, "materialize-product-sql", Map.of(
                        "sql", "SELECT * FROM " + ProductCatalog.baseTableName(name))));

        assertThat(read.success())
                .as("materialize-product-sql: %s / %s", read.errorCode(), read.errorMessage())
                .isTrue();
        Path parquet = Path.of(read.data().get("path"));
        try (Connection duck = DuckDbSession.open();
             Statement stmt = duck.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM read_parquet('"
                     + Names.sqlLiteral(parquet.toString()) + "') ORDER BY 1")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            assertThat(names).containsExactly("Auditori", "Palau");
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    @Test
    @DisplayName("V52: credentials carrying a space, both quotes, a backslash and a keyword reach the server through the secret, and nowhere else")
    void hostileCredentialsTravelOnlyInTheSecret() throws Exception {
        RelationalDatabaseRepository hostile = new RelationalDatabaseRepository(
                UUID.randomUUID(), project.id(), UUID.fromString(ctx.value()), "hostile", "",
                engine(), source.getHost(), source.getMappedPort(3306),
                DATABASE, HOSTILE_USER, HOSTILE_PASSWORD, "public", true);
        FederatedScan scan = FederatedScanExpression.forRepository(hostile, "venue");

        assertThat(scan.expression()).doesNotContain(HOSTILE_PASSWORD);
        assertThat(scan.setupSql())
                .filteredOn(s -> !s.startsWith("CREATE OR REPLACE TEMPORARY SECRET"))
                .noneSatisfy(s -> assertThat(s).contains("p w"));

        // A statement per call: the DuckDB driver closes a statement whose execution failed.
        try (Connection duck = DuckDbSession.open()) {
            for (String setup : scan.setupSql()) {
                exec(duck, setup);
            }
            List<String> names = new ArrayList<>();
            try (Statement stmt = duck.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT name FROM " + scan.expression() + " ORDER BY venue_id")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            assertThat(names).containsExactly("Palau", "Auditori");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private static void exec(Connection duck, String sql) throws Exception {
        try (Statement stmt = duck.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void execAsRoot(String sql) throws Exception {
        ExecResult result = source.execInContainer(
                client(), "-uroot", "-p" + ROOT_PASSWORD, DATABASE, "-e", sql);
        assertThat(result.getExitCode())
                .as("%s: %s", client(), result.getStderr())
                .isZero();
    }

    private Dataset enrol(String table) {
        return datasetService.registerDataset(caller, project.id(), repository.id(),
                new Dataset(UUID.randomUUID(), repository.id(), table, "live " + table));
    }

    private OperatorResult bootstrap(Dataset dataset) {
        return bootstrapping.execute(new OperatorRequest(
                ctx.value(), userId, "bootstrap", Map.of("datasetId", dataset.id().toString())));
    }

    private Model sourceGraphOf(Dataset dataset) {
        return graphStore.query(ctx, "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:source:"
                + dataset.id() + "> { ?s ?p ?o } }");
    }

    /** Column label → XSD range for every source property the bootstrap wrote. */
    private static List<Map.Entry<String, String>> columnRanges(Model graph) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        graph.listResourcesWithProperty(RDF.type, RDF.Property).forEachRemaining(prop -> {
            String label = prop.getProperty(RDFS.label).getString();
            RDFNode range = prop.getProperty(RDFS.range).getObject();
            out.add(Map.entry(label, range.asResource().getURI()));
        });
        return out;
    }

    private static String wrapperSqlOf(Model source) {
        return source.listObjectsOfProperty(source.createProperty(
                        "http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"))
                .toList().stream()
                .map(node -> node.asLiteral().getString())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no nx:wrapperSql on the source graph"));
    }

    private static String propertyIriOf(Model source, String column) {
        return source.listResourcesWithProperty(RDFS.label, column)
                .filterKeep(r -> source.contains(r, RDF.type, RDF.Property))
                .toList().stream()
                .map(Resource::getURI)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no source property for column " + column));
    }

    private static String propertyNamed(MappingCatalog.IntegratedClass cls, String column) {
        return cls.sources().stream()
                .flatMap(sv -> sv.columnMap().entrySet().stream())
                .filter(e -> Names.localName(e.getValue()).equalsIgnoreCase(column))
                .map(e -> Names.localName(e.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no integrated property for column " + column));
    }

    /** The integrated names OQL has to spell for the venue ⇄ concert model. */
    private record Promoted(Dataset venue, String venueCls, String venueName, String venueCity,
                            String venueCapacity, String concertCls, String concertTitle) {}

    /**
     * Enrol, bootstrap, compile and promote {@code venue} and {@code concert}. The join alignment
     * is what mints integrated classes at all — the same shape {@link FederatedRelationalBootstrapIT}
     * uses.
     */
    private Promoted compileAndPromote() throws Exception {
        Dataset venue = enrol("venue");
        Dataset concert = enrol("concert");
        assertThat(bootstrap(venue).success()).isTrue();
        assertThat(bootstrap(concert).success()).isTrue();

        OperatorResult compiled = integration.execute(new OperatorRequest(
                ctx.value(), userId, "compile", Map.of(
                        "sourceDatasetIds",
                        om.writeValueAsString(List.of(venue.id().toString(), concert.id().toString())),
                        "alignments", om.writeValueAsString(List.of(Map.of(
                                "iriA", propertyIriOf(sourceGraphOf(concert), "venue_id"),
                                "iriB", propertyIriOf(sourceGraphOf(venue), "venue_id"),
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
                .orElseThrow(() -> new AssertionError("no atVenue edge"));
        MappingCatalog.IntegratedClass venueClass = catalog.classes().get(edge.rightClass());
        MappingCatalog.IntegratedClass concertClass = catalog.classes().get(edge.leftClass());
        return new Promoted(venue, Names.localName(edge.rightClass()),
                propertyNamed(venueClass, "name"), propertyNamed(venueClass, "city"),
                propertyNamed(venueClass, "capacity"), Names.localName(edge.leftClass()),
                propertyNamed(concertClass, "title"));
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
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : om.readTree(result.data().get("result")).get("rows")) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row) {
                cells.add(cell.asText());
            }
            rows.add(cells);
        }
        return rows;
    }

    /** The source class — what verify-wrapper's callers pass — is the wrapper's subject. */
    private static String sourceClassIriOf(Model source) {
        return source.listSubjectsWithProperty(source.createProperty(
                        "http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"))
                .toList().stream()
                .map(Resource::getURI)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no nx:wrapperSql on the source graph"));
    }

    /** A MySQL string-literal body: backslash is an escape character there by default. */
    private static String mysqlLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }
}

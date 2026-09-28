package edu.upc.dtak.aran.app.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.IcebergTableExtractor;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.source.SourceSchema;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.operators.bootstrapping.BootstrappingOperator;
import edu.upc.dtak.aran.operators.bootstrapping.IcebergConnector;
import edu.upc.dtak.aran.operators.queryengine.QueryEngineOperator;
import edu.upc.dtak.aran.operators.schemaintegration.SchemaIntegrationOperator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * The consume half of Gap 2 against a <b>real</b> Iceberg REST catalog — V53 (a) and (b): the
 * reference {@code apache/iceberg-rest-fixture} (Iceberg's own RESTCatalogServer over a JDBC
 * catalog), tables written into it by iceberg-core's own {@code RESTCatalog}, then enrolled,
 * bootstrapped, integrated and queried through the production beans, with every row read by a
 * live DuckDB {@code iceberg_scan} at query time.
 *
 * <p><b>The warehouse is a {@code file://} directory bind-mounted at the same absolute path</b>
 * in the container and on the host. The catalog writes metadata into it, this JVM writes data
 * files and manifests into it, and DuckDB reads it — all three agree on every location without
 * an object store. That is the whole of the data-plane simplification: the metadata location is
 * still fetched from the catalog per query, and the scan is still the extension's own reader.
 * The resolver's {@code s3://} branch — a bucket-scoped S3 secret for the data plane — is
 * therefore NOT exercised here (V53);
 * {@code ObjectStorageFederatedQueryGarageTest} drives the same secret shape for object storage.
 *
 * <p>Nothing is mocked in the consume path: the {@link IcebergCatalogClient} is the adapter's
 * {@code HttpIcebergCatalogClient} bean, the connector and extractor are the bootstrapping
 * module's beans, and the query engine is the application's own.
 */
@DisplayName("Iceberg consume against a real REST catalog — control plane, live iceberg_scan, copy (V53 a/b)")
class IcebergRestCatalogIT extends BaseIntegrationTest {

    /** See {@code FederatedRelationalBootstrapIT}: its presence means no bridge networking. */
    private static final Path CONTAINER_INFO_PATH = Path.of(".container-info.json");

    /**
     * Pinned by digest: the fixture tracks Iceberg main, and its REST behaviour is exactly what
     * this test watches (it is the one that advertises {@code namespace-separator} and rejects
     * {@code %1F} in a path).
     */
    private static final DockerImageName FIXTURE = DockerImageName.parse(
            "apache/iceberg-rest-fixture@sha256:"
                    + "db8de90b5b7693d4ac334c336f91d9bbe320d7b19f4f514d26de84cdfbcbfe8d");

    /** Shared by {@code db.venue} and {@code db.hall}, which the federated test unions. */
    private static final Schema VENUE = new Schema(
            Types.NestedField.optional(1, "venue_id", Types.IntegerType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()),
            Types.NestedField.optional(3, "capacity", Types.LongType.get()),
            Types.NestedField.optional(4, "opened", Types.DateType.get()),
            Types.NestedField.optional(5, "fee", Types.DecimalType.of(8, 2)),
            Types.NestedField.optional(6, "last_show", Types.TimestampType.withoutZone()));
    private static final String VENUE_FIELD_IDS =
            "venue_id: 1, name: 2, capacity: 3, opened: 4, fee: 5, last_show: 6";
    private static final String VENUE_COLUMNS = "venue_id, name, capacity, opened, fee, last_show";

    private static final Schema CONCERT = new Schema(
            Types.NestedField.optional(1, "concert_id", Types.IntegerType.get()),
            Types.NestedField.optional(2, "title", Types.StringType.get()),
            Types.NestedField.optional(3, "venue_id", Types.IntegerType.get()),
            Types.NestedField.optional(4, "price", Types.DecimalType.of(8, 2)),
            Types.NestedField.optional(5, "starts_at", Types.TimestampType.withoutZone()));

    private static final Schema SEASON = new Schema(
            Types.NestedField.optional(1, "season_id", Types.IntegerType.get()),
            Types.NestedField.optional(2, "label", Types.StringType.get()));

    private static Path warehouse;
    private static GenericContainer<?> catalogContainer;
    private static RESTCatalog writer;

    @Autowired IcebergCatalogClient catalogClient;
    @Autowired IcebergConnector connector;
    @Autowired IcebergTableExtractor extractor;
    @Autowired DatasetService datasetService;
    @Autowired BootstrappingOperator bootstrapping;
    @Autowired SchemaIntegrationOperator integration;
    @Autowired QueryEngineOperator queryEngine;
    @Autowired GraphStore graphStore;
    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryService repositoryService;
    @Autowired DatasetRepository datasets;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    private final ObjectMapper om = new ObjectMapper();

    private AuthenticatedUser caller;
    private TenantContext ctx;
    private UUID userId;
    private Project project;
    private IcebergRestRepository repository;

    @BeforeAll
    static void startCatalogAndWriteTables() throws Exception {
        assumeFalse(Files.exists(CONTAINER_INFO_PATH),
                "constrained Docker: no bridge networking, so the "
                        + "Iceberg REST catalog this test reads cannot be started");
        warehouse = Files.createTempDirectory("aran-iceberg-wh-");
        String dir = warehouse.toAbsolutePath().toString();
        // The catalog creates each namespace and table directory (0755), and this JVM then
        // writes data/ and the manifests inside them. The image's own user (uid 1000) would own
        // those, so only a JVM running as root or as uid 1000 could write there — CI's runner
        // is uid 1001. Running the catalog as whoever owns the warehouse makes both writers one.
        Object uid = Files.getAttribute(warehouse, "unix:uid");
        Object gid = Files.getAttribute(warehouse, "unix:gid");
        catalogContainer = new GenericContainer<>(FIXTURE)
                .withExposedPorts(8181)
                // A live bind, not a copy: the container and this JVM write into it by turns.
                .withCreateContainerCmdModifier(cmd -> {
                    cmd.withUser(uid + ":" + gid);
                    cmd.getHostConfig().withBinds(new Bind(dir, new Volume(dir)));
                })
                // The catalog's HadoopFileIO logs in as the process's user NAME, and a uid with no
                // passwd entry fails every table create with "Invalid UID, could not determine
                // effective user" — so the image gets a passwd that names the warehouse owner.
                .withCopyToContainer(Transferable.of("root:x:0:0:root:/root:/bin/sh\n"
                        + "aran:x:" + uid + ":" + gid + ":aran:/tmp:/bin/sh\n"), "/etc/passwd")
                .withEnv("CATALOG_WAREHOUSE", "file://" + dir + "/")
                .waitingFor(Wait.forHttp("/v1/config").forStatusCode(200));
        catalogContainer.start();

        writer = new RESTCatalog();
        writer.initialize("fixture", Map.of(
                "uri", catalogUri(),
                "io-impl", IcebergTestFileIOs.LocalFileIO.class.getName()));
        writer.createNamespace(Namespace.of("db"));
        writer.createNamespace(Namespace.of("db", "archive"));

        Table venue = writer.createTable(TableIdentifier.of("db", "venue"), VENUE,
                PartitionSpec.unpartitioned());
        append(venue, VENUE_FIELD_IDS, """
                SELECT * FROM (VALUES
                    (1, 'Palau', 2200::BIGINT, DATE '1908-02-09', 12.50::DECIMAL(8,2),
                     TIMESTAMP '2026-10-01 20:00:00'),
                    (2, 'Auditori', 2400::BIGINT, DATE '1999-03-22', 9.00::DECIMAL(8,2),
                     TIMESTAMP '2026-10-02 19:30:00'))
                  AS t(""" + VENUE_COLUMNS + ")", 2);

        Table hall = writer.createTable(TableIdentifier.of("db", "hall"), VENUE,
                PartitionSpec.unpartitioned());
        append(hall, VENUE_FIELD_IDS, """
                SELECT * FROM (VALUES
                    (7, 'Razzmatazz', 1500::BIGINT, DATE '2000-01-15', 20.00::DECIMAL(8,2),
                     TIMESTAMP '2026-09-30 23:15:00'))
                  AS t(""" + VENUE_COLUMNS + ")", 1);

        Table concert = writer.createTable(TableIdentifier.of("db", "concert"), CONCERT,
                PartitionSpec.unpartitioned());
        append(concert, "concert_id: 1, title: 2, venue_id: 3, price: 4, starts_at: 5", """
                SELECT * FROM (VALUES
                    (10, 'Turandot', 1, 45.50::DECIMAL(8,2), TIMESTAMP '2026-10-01 20:00:00'),
                    (11, 'Rigoletto', 2, 38.00::DECIMAL(8,2), TIMESTAMP '2026-10-02 19:30:00'))
                  AS t(concert_id, title, venue_id, price, starts_at)""", 2);

        // Not through writer.createTable: iceberg-core 1.7.1's own RESTCatalog joins a nested
        // namespace with %1F, and this server answers that path 400 "Suspicious Path Character"
        // — the same defect HttpIcebergCatalogClient had, in the reference client of the version
        // this repository pins. The advertised separator, by hand, is what gets through.
        HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(catalogUri() + "/v1/namespaces/db%2Earchive/tables"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"season\",\"schema\":"
                        + SchemaParser.toJson(SEASON) + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    }

    @AfterAll
    static void stopCatalog() throws Exception {
        if (writer != null) {
            writer.close();
        }
        if (catalogContainer != null) {
            catalogContainer.stop();
        }
        if (warehouse != null) {
            try (var tree = Files.walk(warehouse)) {
                for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
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
                UUID.randomUUID(), tenantId, "Iceberg-" + suffix, "", userId, Instant.now()));
        projects.addMember(ctx, new ProjectMember(
                UUID.randomUUID(), project.id(), userId, Instant.now()));
        // No token and no data-plane credentials: the fixture is unauthenticated and the
        // warehouse is file://, so neither secret branch of the resolver fires. Created through
        // the service, as a user's form is, so its stored-target screen runs on the catalog URI.
        repository = (IcebergRestRepository) repositoryService.createRepository(caller,
                project.id(), new IcebergRestRepository(
                        UUID.randomUUID(), project.id(), tenantId, "fixture-" + suffix, "",
                        catalogUri(), "", "", "S3", "", "", "", ""));
    }

    // ── V53(b): the control plane ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the catalog client lists every table, nested namespaces included, and loads the current schema")
    void controlPlaneAgainstTheReferenceCatalog() {
        assertThat(catalogClient.config(repository))
                .as("the fixture advertises its own namespace separator — the reason a hard-coded"
                        + " %1F could not reach db.archive")
                .containsEntry("namespace-separator", "%2E");

        assertThat(catalogClient.listTables(repository))
                .containsExactlyInAnyOrder("db.venue", "db.hall", "db.concert", "db.archive.season");

        IcebergCatalogClient.IcebergTable concert = catalogClient.loadTable(repository, "db.concert");
        assertThat(concert.metadataLocation())
                .as("the catalog's own answer, pointing into the bind-mounted warehouse")
                .startsWith("file:")
                .contains(warehouse.toAbsolutePath().toString())
                .endsWith(".metadata.json");
        assertThat(concert.fields())
                .extracting(IcebergCatalogClient.IcebergField::name, IcebergCatalogClient.IcebergField::type)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("concert_id", "int"),
                        org.assertj.core.groups.Tuple.tuple("title", "string"),
                        org.assertj.core.groups.Tuple.tuple("venue_id", "int"),
                        org.assertj.core.groups.Tuple.tuple("price", "decimal(8, 2)"),
                        org.assertj.core.groups.Tuple.tuple("starts_at", "timestamp"));

        assertThat(catalogClient.loadTable(repository, "db.archive.season").fields())
                .extracting(IcebergCatalogClient.IcebergField::name)
                .containsExactly("season_id", "label");
    }

    @Test
    @DisplayName("the connector introspects a live table into a FLAT source class with the shared XSD ranges")
    void connectorIntrospectsFromTheLiveCatalog() throws Exception {
        SourceSchema schema = connector.introspect(SourceDescriptor.ofIceberg(
                UUID.randomUUID().toString(), "db.concert", null,
                new SourceDescriptor.Iceberg(catalogUri(), "", "", "db.concert",
                        "S3", "", "", "", "")));

        Model model = schema.rdfs();
        assertThat(rangeOf(model, "concert_id")).isEqualTo(XSD.integer.getURI());
        assertThat(rangeOf(model, "title")).isEqualTo(XSD.xstring.getURI());
        assertThat(rangeOf(model, "price")).isEqualTo(XSD.decimal.getURI());
        assertThat(rangeOf(model, "starts_at")).isEqualTo(XSD.dateTime.getURI());
        Resource cls = model.listResourcesWithProperty(RDF.type, RDFS.Class).toList().getFirst();
        assertThat(model.getProperty(cls, nx("iterator")).getString()).isEqualTo("db.concert");
        assertThat(model.getProperty(cls, nx("shape")).getObject().toString()).contains("FLAT");
    }

    // ── V53(a): the data plane, federated ────────────────────────────────────────────────

    @Test
    @DisplayName("two live tables enrol, bootstrap, integrate and answer OQL; a new commit is visible with no refresh")
    void federatedTablesAnswerOqlWithLiveRows() throws Exception {
        Dataset venue = enrol("db.venue");
        Dataset hall = enrol("db.hall");
        assertThat(venue.snapshotId()).as("a federated Iceberg dataset has no lake snapshot").isNull();

        for (Dataset d : List.of(venue, hall)) {
            OperatorResult boot = bootstrap(d);
            assertThat(boot.success()).as("bootstrap %s: %s / %s", d.name(), boot.errorCode(),
                    boot.errorMessage()).isTrue();
            assertThat(boot.data()).containsEntry("format", "ICEBERG");
            assertThat(datasets.findById(ctx, d.id()).orElseThrow().bootstrapStatus())
                    .isEqualTo(DatasetStatus.READY);
        }
        Model venueGraph = sourceGraphOf(venue);
        Model hallGraph = sourceGraphOf(hall);
        assertThat(wrapperSqlOf(venueGraph))
                .as("a flat projection over the relation, not a JSONPath walk of 'db.venue' (S4-1)")
                .contains("${SNAPSHOT}").contains("\"capacity\"").doesNotContain("\"db\"");

        // A CLASS alignment with a label a person chose, then one DATATYPE alignment per column —
        // the shape the consume docs recommend. (A join-only alignment would auto-promote each
        // side to Int_db_venue: the identifier in OQL's bare alphabet, AutoPromotedClassNameTest.)
        List<Map<String, String>> alignments = new ArrayList<>();
        alignments.add(align(classIriOf(venueGraph), classIriOf(hallGraph), "Venue", "class"));
        for (String[] column : new String[][] {{"venue_id", "venueId"}, {"name", "name"},
                {"capacity", "capacity"}, {"opened", "opened"}, {"fee", "fee"},
                {"last_show", "lastShow"}}) {
            alignments.add(align(propertyIriOf(venueGraph, column[0]),
                    propertyIriOf(hallGraph, column[0]), column[1], "datatype"));
        }
        OperatorResult compiled = integration.execute(new OperatorRequest(
                ctx.value(), userId, "compile", Map.of(
                        "sourceDatasetIds",
                        om.writeValueAsString(List.of(venue.id().toString(), hall.id().toString())),
                        "alignments", om.writeValueAsString(alignments),
                        "modelId", UUID.randomUUID().toString())));
        assertThat(compiled.success())
                .as("compile: %s / %s", compiled.errorCode(), compiled.errorMessage()).isTrue();
        graphStore.promote(ctx, UUID.fromString(compiled.data().get("draftVersionId")),
                new AuditedActor(userId));

        String projection = "SELECT Venue.name, Venue.capacity, Venue.opened, Venue.fee,"
                + " Venue.lastShow FROM Venue";
        OperatorResult first = runOql(projection);
        assertThat(planShapeOf(first))
                .as("the plan reads the tables where they live — there is no lake copy to fall back to")
                .contains("access=FEDERATED");
        // DATE, DECIMAL and TIMESTAMP straight from the Iceberg schema, widened by XsdTypes and
        // TRY_CAST by the executor, across a union of two live scans in one DuckDB session.
        List<List<String>> rows = rowsOf(first);
        assertThat(rows).extracting(r -> r.get(0) + "|" + r.get(1) + "|" + r.get(2))
                .containsExactlyInAnyOrder(
                        "Palau|2200|1908-02-09",
                        "Auditori|2400|1999-03-22",
                        "Razzmatazz|1500|2000-01-15");
        assertThat(rows).extracting(r -> new java.math.BigDecimal(r.get(3)))
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new java.math.BigDecimal("12.50"),
                        new java.math.BigDecimal("9.00"), new java.math.BigDecimal("20.00"));
        assertThat(rows).extracting(r -> r.get(4).replace('T', ' '))
                .allSatisfy(ts -> assertThat(ts).startsWith("2026-"))
                .anySatisfy(ts -> assertThat(ts).startsWith("2026-09-30 23:15"));

        // ── the metadata location is fetched per query: a commit made AFTER the promote, with no
        //    refresh, re-bootstrap or re-promote, is what the next query reads ─────────────────
        String before = catalogClient.loadTable(repository, "db.venue").metadataLocation();
        append(writer.loadTable(TableIdentifier.of("db", "venue")), VENUE_FIELD_IDS, """
                SELECT * FROM (VALUES (3, 'Liceu', 2292::BIGINT, DATE '1847-04-04',
                                       30.00::DECIMAL(8,2), TIMESTAMP '2026-10-03 18:00:00'))
                  AS t(""" + VENUE_COLUMNS + ")", 1);
        assertThat(catalogClient.loadTable(repository, "db.venue").metadataLocation())
                .as("an Iceberg commit moves the metadata location — the premise of per-query fetch")
                .isNotEqualTo(before);
        assertThat(rowsOf(runOql(projection)))
                .extracting(r -> r.get(0))
                .containsExactlyInAnyOrder("Palau", "Auditori", "Razzmatazz", "Liceu");

        // ── verify-wrapper over the live class (S7-16): the fresh connection it opens must bind
        //    the iceberg_scan src_ view itself — the verdict is about the wrapper, never an
        //    environmental "verify unavailable". The bootstrap's own wrapper, because every
        //    column is aligned: a text dropping an aliased column is refused as missing it ────
        OperatorResult verdict = queryEngine.execute(new OperatorRequest(ctx.value(), userId,
                "verify-wrapper", Map.of(
                        "datasetId", venue.id().toString(),
                        "classIri", classIriOf(venueGraph),
                        "sql", wrapperSqlOf(venueGraph))));
        assertThat(verdict.success()).isTrue();
        assertThat(verdict.data().get("reason"))
                .as("verdict %s", verdict.data())
                .doesNotStartWith("verify unavailable");
        assertThat(verdict.data().get("status")).as("verdict %s", verdict.data())
                .isEqualTo("EXPANDED");
    }

    // ── V53(a): the data plane, MATERIALIZED ─────────────────────────────────────────────

    @Test
    @DisplayName("the MATERIALIZED extractor copies the table's current snapshot to parquet via iceberg_scan")
    void extractorCopiesTheCurrentSnapshot() throws Exception {
        long liveRows = count("SELECT count(*) FROM iceberg_scan('"
                + catalogClient.loadTable(repository, "db.concert").metadataLocation() + "')");

        IcebergTableExtractor.Extracted copy = extractor.extract(repository, "db.concert", 64L << 20);

        assertThat(copy.rowCount()).isEqualTo(liveRows).isEqualTo(2L);
        Path parquet = Files.createTempFile("aran-iceberg-copy-", ".parquet");
        try {
            Files.write(parquet, copy.parquet());
            try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                 Statement st = duck.createStatement();
                 ResultSet rs = st.executeQuery("SELECT title, price, starts_at FROM read_parquet('"
                         + parquet.toAbsolutePath() + "') ORDER BY concert_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Turandot");
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("45.50");
                assertThat(rs.getTimestamp(3).toLocalDateTime())
                        .isEqualTo(java.time.LocalDateTime.of(2026, 10, 1, 20, 0));
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Rigoletto");
                assertThat(rs.next()).isFalse();
            }
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    // ── fixture plumbing ─────────────────────────────────────────────────────────────────

    private static String catalogUri() {
        return "http://" + catalogContainer.getHost() + ":" + catalogContainer.getMappedPort(8181);
    }

    /**
     * Append rows to a live table the way any writer does: a parquet data file, then a
     * {@code FastAppend} commit through the REST catalog. DuckDB writes the file with explicit
     * {@code FIELD_IDS} — an Iceberg data file resolves columns by field id, so a file without
     * them is exactly what the produce side's name mapping exists to cover, and is not what a
     * real Iceberg writer produces.
     */
    private static void append(Table table, String fieldIds, String select, long rows)
            throws Exception {
        Path file = IcebergTestFileIOs.LocalFileIO.toFile(
                table.location() + "/data/" + UUID.randomUUID() + ".parquet").toPath();
        Files.createDirectories(file.getParent());
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = duck.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + file.toAbsolutePath()
                    + "' (FORMAT PARQUET, FIELD_IDS {" + fieldIds + "})");
        }
        DataFile dataFile = DataFiles.builder(PartitionSpec.unpartitioned())
                .withPath(file.toUri().toString())
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(Files.size(file))
                .withRecordCount(rows)
                .build();
        table.newAppend().appendFile(dataFile).commit();
    }

    private static long count(String sql) throws Exception {
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = duck.createStatement()) {
            st.execute("LOAD iceberg");
            try (ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }
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
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : payload.get("rows")) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row) {
                cells.add(cell.asText());
            }
            rows.add(cells);
        }
        return rows;
    }

    private static String wrapperSqlOf(Model source) {
        return source.listObjectsOfProperty(nx("wrapperSql")).toList().stream()
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

    private static String classIriOf(Model source) {
        return source.listResourcesWithProperty(RDF.type, RDFS.Class).toList().getFirst().getURI();
    }

    private static Map<String, String> align(String iriA, String iriB, String label, String type) {
        return Map.of("iriA", iriA, "iriB", iriB, "label", label, "type", type);
    }

    private static String rangeOf(Model model, String column) {
        Resource prop = model.listResourcesWithProperty(RDFS.label, column)
                .filterKeep(r -> model.contains(r, RDF.type, RDF.Property))
                .toList().getFirst();
        return model.getProperty(prop, RDFS.range).getObject().asResource().getURI();
    }

    private static org.apache.jena.rdf.model.Property nx(String local) {
        return org.apache.jena.rdf.model.ResourceFactory.createProperty(
                "http://www.essi.upc.edu/DTIM/NextiaDI/" + local);
    }
}

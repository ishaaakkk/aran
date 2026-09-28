package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.cube.Additivity;
import edu.upc.dtak.aran.core.domain.cube.CubeCalendar;
import edu.upc.dtak.aran.core.domain.cube.CubeDefinition;
import edu.upc.dtak.aran.core.domain.cube.CubeDimension;
import edu.upc.dtak.aran.core.domain.cube.CubeMeasure;
import edu.upc.dtak.aran.core.domain.cube.CubeQb4olap;
import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.domain.cube.CubeRepository;
import edu.upc.dtak.aran.core.fake.CapturingLakeClient;
import edu.upc.dtak.aran.core.fake.InMemoryDataProductRepository;
import edu.upc.dtak.aran.core.fake.InMemoryJobRepository;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CubeService} against in-memory fakes: a scripted query-engine behind a real
 * {@link OperatorOrchestrator} answering {@code cube-evidence}, and a <em>real</em>
 * {@link DataProductService} over in-memory product/job repositories, so the star's products are
 * created and materialized through the same path a hand-made product takes rather than through a
 * mock of it.
 *
 * <p>Core's shared {@code FakeGraphStore} is not enough here: it throws from
 * {@code currentVersion} and swallows {@code addTriples}/{@code clearGraph}, and both are things
 * this service is judged on.
 */
@DisplayName("CubeService")
class CubeServiceTest {

    private static final Executor DIRECT = Runnable::run;
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantContext TENANT = new TenantContext(TENANT_ID.toString());
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID ACTIVE_VERSION = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String G = "http://aran/global#";
    private static final String SALES = G + "Sales";
    private static final String CUSTOMER = G + "Customer";
    private static final String HAS_CUSTOMER = G + "hasCustomer";
    private static final String BILLED_TO = G + "billedTo";
    private static final String CHANNEL_OF = G + "channelOf";

    /**
     * Sales (numeric amount, non-numeric status, a date, two FK columns) joined to Customer over
     * a DECLARED edge and over a HYPOTHESIS one, plus an edge that never touches the fact.
     */
    private static final String EVIDENCE_JSON = """
            {"classes":[
              {"iri":"http://aran/global#Sales","name":"Sales","rowCount":2100000,"properties":[
                {"iri":"http://aran/global#amount","name":"amount","xsd":"xsd:decimal",
                 "numeric":true,"ndv":98000,"nullFraction":0.0},
                {"iri":"http://aran/global#status","name":"status","xsd":"xsd:string",
                 "numeric":false,"ndv":4,"nullFraction":0.0},
                {"iri":"http://aran/global#orderDate","name":"orderDate","xsd":"xsd:date",
                 "numeric":false,"ndv":900,"nullFraction":0.0},
                {"iri":"http://aran/global#customerId","name":"customerId","xsd":"xsd:string",
                 "numeric":false,"ndv":48000,"nullFraction":0.002}]},
              {"iri":"http://aran/global#Customer","name":"Customer","rowCount":48000,
               "properties":[
                {"iri":"http://aran/global#customerKey","name":"customerKey","xsd":"xsd:string",
                 "numeric":false,"ndv":48000,"nullFraction":0.0},
                {"iri":"http://aran/global#city","name":"city","xsd":"xsd:string",
                 "numeric":false,"ndv":1200,"nullFraction":0.0},
                {"iri":"http://aran/global#country","name":"country","xsd":"xsd:string",
                 "numeric":false,"ndv":40,"nullFraction":0.0}]},
              {"iri":"http://aran/global#Channel","name":"Channel","rowCount":5,
               "properties":[
                {"iri":"http://aran/global#channelKey","name":"channelKey","xsd":"xsd:string",
                 "numeric":false,"ndv":5,"nullFraction":0.0}]}],
             "edges":[
              {"propertyIri":"http://aran/global#hasCustomer","propertyName":"hasCustomer",
               "leftClassIri":"http://aran/global#Sales",
               "rightClassIri":"http://aran/global#Customer",
               "leftColIri":"http://aran/global#customerId",
               "rightColIri":"http://aran/global#customerKey","tier":"DECLARED"},
              {"propertyIri":"http://aran/global#billedTo","propertyName":"billedTo",
               "leftClassIri":"http://aran/global#Sales",
               "rightClassIri":"http://aran/global#Customer",
               "leftColIri":"http://aran/global#status",
               "rightColIri":"http://aran/global#city","tier":"HYPOTHESIS"},
              {"propertyIri":"http://aran/global#channelOf","propertyName":"channelOf",
               "leftClassIri":"http://aran/global#Customer",
               "rightClassIri":"http://aran/global#Channel",
               "leftColIri":"http://aran/global#city",
               "rightColIri":"http://aran/global#channelKey","tier":"DECLARED"}]}
            """;

    private InMemoryCubeRepository cubes;
    private InMemoryDataProductRepository productRows;
    private RecordingGraphStore graphStore;
    private ScriptedQueryEngine queryEngine;
    private DataProductService products;
    private CubeService service;

    @BeforeEach
    void setUp() {
        cubes = new InMemoryCubeRepository();
        productRows = new InMemoryDataProductRepository();
        graphStore = new RecordingGraphStore();
        graphStore.current = new GraphVersion(
                ACTIVE_VERSION, TENANT_ID, 1, "ACTIVE", Instant.now(), null, null, null);
        queryEngine = new ScriptedQueryEngine();
        queryEngine.script("cube-evidence",
                req -> OperatorResult.success(Map.of("evidence", EVIDENCE_JSON)));
        queryEngine.script("rewrite-oql", req -> OperatorResult.success(Map.of("status", "ok")));
        queryEngine.script("materialize-oql", req -> OperatorResult.success(Map.of(
                "status", "ok", "parquetPath", tempParquet(), "rowCount", "42")));

        OperatorOrchestrator orchestrator = new OperatorOrchestrator(List.of(queryEngine));
        products = new DataProductService(productRows, graphStore, orchestrator,
                new JobService(new InMemoryJobRepository(), DIRECT, (jobId, snapshot) -> { }),
                new CapturingLakeClient(), new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(), cubes);
        service = new CubeService(cubes, graphStore, orchestrator, products, MAPPER);
    }

    // ─── create: the happy path ───────────────────────────────────────────────

    @Test
    @DisplayName("create writes the definition, the star's products and the cube's named graph")
    void create_writes_all_three_artefacts() {
        CubeService.CubeCreation created = create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(new CubeDimension("customer", CUSTOMER, HAS_CUSTOMER, true,
                        List.of(G + "country", G + "city"), false)),
                new CubeCalendar(G + "orderDate", List.of("year", "month")));

        CubeDefinition cube = created.cube();
        assertThat(cube.name()).isEqualTo("sales");
        assertThat(cube.schemaVersionId()).isEqualTo(ACTIVE_VERSION);
        assertThat(cubes.find(TENANT, cube.id())).isPresent();

        // One product per star table, fact first, each with the OQL the generator wrote.
        List<DataProduct> star = cube.productIds().stream()
                .map(id -> productRows.findById(TENANT, id).orElseThrow()).toList();
        assertThat(star).extracting(DataProduct::name)
                .containsExactly("sales_fact", "sales_customer");
        assertThat(star.get(0).oql()).isEqualTo(
                "SELECT Sales.amount, Sales.customerId, Sales.orderDate FROM Sales");
        assertThat(star.get(1).oql()).isEqualTo(
                "SELECT Customer.customerKey, Customer.country, Customer.city FROM Customer");
        // Materialized through the ordinary product path: one job per member, and each one ran.
        assertThat(created.jobIds()).hasSize(2);
        assertThat(star).allSatisfy(product -> assertThat(product.snapshotId()).isNotNull());

        // The graph is rewritten, not merged into: cleared first, always.
        String graphUri = CubeQb4olap.graphUri(cube.id());
        assertThat(graphStore.writes).containsExactly("clear " + graphUri, "add " + graphUri);
        assertThat(graphStore.lastAdded.contains(
                graphStore.lastAdded.createResource(CubeQb4olap.dsdIri(cube.id())),
                org.apache.jena.vocabulary.RDFS.label, "sales")).isTrue();
    }

    @Test
    @DisplayName("a saved cube reads back with its measures, dimensions and calendar intact")
    void definition_round_trips_through_the_repository() {
        CubeDimension customer = new CubeDimension("customer", CUSTOMER, HAS_CUSTOMER, true,
                List.of(G + "country", G + "city"), false);
        CubeMeasure revenue = CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE);
        CubeMeasure ratio = CubeMeasure.ratio("margin_pct", "SUM(margin) / SUM(amount)");
        CubeCalendar calendar = new CubeCalendar(G + "orderDate", List.of("year", "month"));

        CubeDefinition cube = create(List.of(revenue, ratio), List.of(customer), calendar).cube();
        CubeDefinition read = service.get(TENANT, cube.id());

        assertThat(read.measures()).containsExactly(revenue, ratio);
        assertThat(read.dimensions()).containsExactly(customer);
        assertThat(read.calendar()).isEqualTo(calendar);
        assertThat(read.productIds()).isEqualTo(cube.productIds());
        assertThat(service.list(TENANT, PROJECT_ID)).extracting(CubeDefinition::id)
                .containsExactly(cube.id());
    }

    @Test
    @DisplayName("getYaml emits the stored definition, not a re-derived one")
    void get_yaml() {
        CubeDefinition cube = create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("customer", CUSTOMER, HAS_CUSTOMER)), null).cube();

        assertThat(service.getYaml(TENANT, cube.id()))
                .contains("name: sales")
                .contains("fact: Sales  # " + SALES)
                .contains("expr: SUM(amount)")
                .contains("on: hasCustomer");
    }

    // ─── create: the validation gates ─────────────────────────────────────────

    @Test
    @DisplayName("a fact class the ACTIVE schema does not have is refused")
    void rejects_an_unknown_fact_class() {
        assertThatThrownBy(() -> service.create(TENANT, PROJECT_ID, "sales", null, G + "Ghost",
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not an integrated class");
        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a measure over a column the fact class does not have is refused")
    void rejects_a_measure_off_the_fact_class() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "city", "SUM", Additivity.ADDITIVE)),
                List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a property of Sales");
    }

    @Test
    @DisplayName("a non-numeric column cannot be summed")
    void rejects_a_non_numeric_measure() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "status", "SUM", Additivity.ADDITIVE)),
                List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status is not numeric")
                .hasMessageContaining("only COUNT");
    }

    @Test
    @DisplayName("COUNT over a non-numeric column is allowed — counting rows is meaningful")
    void allows_count_over_a_non_numeric_column() {
        CubeDefinition cube = create(
                List.of(CubeMeasure.of("orders", G + "status", "COUNT", Additivity.ADDITIVE)),
                List.of(), null).cube();

        assertThat(cube.measures()).singleElement()
                .extracting(CubeMeasure::aggregator).isEqualTo("COUNT");
    }

    @Test
    @DisplayName("a dimension over a join the engine never reported is refused")
    void rejects_an_unknown_join() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("customer", CUSTOMER, G + "invented")), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a relationship of the integrated schema");
    }

    @Test
    @DisplayName("a join that does not touch the fact class is refused — a dimension is one hop")
    void rejects_a_join_that_misses_the_fact() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("channel", G + "Channel", CHANNEL_OF)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not touch the fact class Sales");
    }

    @Test
    @DisplayName("a join that reaches a different class than the dimension declares is refused")
    void rejects_a_join_to_another_class() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("channel", G + "Channel", HAS_CUSTOMER)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reaches Customer, not Channel");
    }

    @Test
    @DisplayName("a HYPOTHESIS join is refused by default — it would double-count every measure")
    void rejects_an_unverified_join() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("billed", CUSTOMER, BILLED_TO)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not provably to-one (HYPOTHESIS)")
                .hasMessageContaining("set allowUnverified to insist");
        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("allowUnverified takes responsibility for a HYPOTHESIS join, and the cube is built")
    void allow_unverified_admits_a_hypothesis_join() {
        CubeDefinition cube = create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(new CubeDimension("billed", CUSTOMER, BILLED_TO, false, List.of(), true)),
                null).cube();

        assertThat(cube.dimensions()).singleElement()
                .extracting(CubeDimension::allowUnverified).isEqualTo(true);
        assertThat(cube.productIds()).hasSize(2);
    }

    @Test
    @DisplayName("a DECLARED join whose fact class is the REFERENCED side is refused — it is to-many")
    void rejects_a_reversed_declared_join() {
        // channelOf runs Customer → Channel: Customer holds the foreign key, Channel is referenced.
        // Read from Channel it is one-to-MANY, so a Channel fact sliced by Customer would multiply
        // every measure — the fan-out the tier gate exists to refuse, arriving with a trusted tier.
        assertThatThrownBy(() -> service.create(TENANT, PROJECT_ID, "channels", "Reversed",
                G + "Channel",
                List.of(CubeMeasure.of("rows", G + "channelKey", "COUNT", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("customer", CUSTOMER, CHANNEL_OF)), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelOf")
                .hasMessageContaining("is not to-one from Channel");
        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("allowUnverified also takes responsibility for a reversed join")
    void allow_unverified_admits_a_reversed_join() {
        CubeDefinition cube = service.create(TENANT, PROJECT_ID, "channels", "Reversed",
                G + "Channel",
                List.of(CubeMeasure.of("rows", G + "channelKey", "COUNT", Additivity.ADDITIVE)),
                List.of(new CubeDimension("customer", CUSTOMER, CHANNEL_OF, false, List.of(), true)),
                null, null).cube();

        assertThat(cube.dimensions()).singleElement()
                .extracting(CubeDimension::allowUnverified).isEqualTo(true);
    }

    @Test
    @DisplayName("a hierarchy level that is not a property of the dimension class is refused")
    void rejects_a_foreign_hierarchy_level() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(new CubeDimension("customer", CUSTOMER, HAS_CUSTOMER, false,
                        List.of(G + "amount"), false)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hierarchy level")
                .hasMessageContaining("is not a property of Customer");
    }

    @Test
    @DisplayName("a calendar over a column the fact class does not have is refused")
    void rejects_a_foreign_calendar_column() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), new CubeCalendar(G + "city", List.of("year"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("calendar")
                .hasMessageContaining("is not a property of Sales");
    }

    @Test
    @DisplayName("a cube pins an ACTIVE version — a DRAFT one is refused, as for a product")
    void rejects_a_draft_schema_version() {
        graphStore.current = new GraphVersion(
                UUID.randomUUID(), TENANT_ID, 2, "DRAFT", Instant.now(), null, null, null);

        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    @DisplayName("evidence the engine cannot produce fails the create rather than validating "
            + "against nothing")
    void unavailable_evidence_fails_loudly() {
        queryEngine.script("cube-evidence",
                req -> OperatorResult.failure("NO_SCHEMA", "No active global schema"));

        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active global schema");
    }

    @Test
    @DisplayName("a second cube with one project's name is refused by name, before any product")
    void rejects_a_duplicate_cube_name() {
        create(List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null);

        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cube name 'sales' already exists in this project");
        // The V40 unique constraint would have caught it as a 500; the star is untouched.
        assertThat(productRows.findByProject(TENANT, PROJECT_ID))
                .extracting(DataProduct::name).containsExactly("sales_fact");
    }

    @Test
    @DisplayName("a star product name an existing product already holds is refused, naming it")
    void rejects_a_colliding_star_product_name() {
        // Two legal, distinct cube names can still derive one product name: a_b_c is both
        // cube 'a' dimension 'b_c' and cube 'a_b' dimension 'c'.
        create("a", List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("b_c", CUSTOMER, HAS_CUSTOMER)), null);

        assertThatThrownBy(() -> create("a_b",
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("c", CUSTOMER, HAS_CUSTOMER)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a data product named 'a_b_c' already exists in this project");
        assertThat(productRows.findByProject(TENANT, PROJECT_ID))
                .extracting(DataProduct::name).containsExactlyInAnyOrder("a_fact", "a_b_c");
        assertThat(cubes.rows).hasSize(1);
    }

    @Test
    @DisplayName("a star name another project already holds is refused — product tables are one tenant-wide namespace")
    void rejects_a_star_name_held_in_another_project() {
        // data_product is unique per project, so the old project-scoped check let this through,
        // and ProductCatalog then served the fact table as sales_fact_<8 hex> — a name the user
        // never typed. Case-insensitive, as the catalog is.
        UUID otherProject = UUID.randomUUID();
        products.create(TENANT, otherProject, "Sales_Fact", "hand-made", "SELECT x FROM Sales", null);

        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'Sales_Fact' in another project")
                .hasMessageContaining("table name 'sales_fact'");
        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
        assertThat(cubes.rows).isEmpty();
    }

    @Test
    @DisplayName("a star name the catalog would not serve verbatim is refused, before any product")
    void rejects_a_star_name_the_catalog_would_rename() {
        // u_ is a graph-view prefix: ProductCatalog serves such a product as p_u_…, so the name
        // the user typed would not be the table a BI tool sees.
        assertThatThrownBy(() -> create("u_sales",
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would be served as table 'p_u_sales_fact'");
        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a dimension named 'fact' is refused — its product would collide with the fact table")
    void rejects_a_star_that_names_one_product_twice() {
        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("fact", CUSTOMER, HAS_CUSTOMER)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two star products named 'sales_fact'");
        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a failure after the star exists leaves no half-built cube behind")
    void a_failed_create_cleans_up_its_products() {
        cubes.failOnSave = true;

        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("customer", CUSTOMER, HAS_CUSTOMER)), null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(productRows.findByProject(TENANT, PROJECT_ID))
                .as("both member products were rolled back").isEmpty();
        assertThat(cubes.rows).isEmpty();
    }

    @Test
    @DisplayName("a failure writing the QB4OLAP graph rolls the star back and saves no row either")
    void a_failed_graph_write_rolls_everything_back() {
        graphStore.failOnAddTriples = true;

        assertThatThrownBy(() -> create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("customer", CUSTOMER, HAS_CUSTOMER)), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("addTriples failed");

        assertThat(productRows.findByProject(TENANT, PROJECT_ID))
                .as("both member products were rolled back").isEmpty();
        // The graph is written before the row precisely so this ordering holds: the row that
        // makes a cube exist to every read path is never saved over a description that failed.
        assertThat(cubes.rows).isEmpty();
        assertThat(service.list(TENANT, PROJECT_ID)).isEmpty();
    }

    // ─── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete removes the star's products, the named graph and the row")
    void delete_removes_everything_create_made() {
        CubeDefinition cube = create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("customer", CUSTOMER, HAS_CUSTOMER)), null).cube();
        graphStore.writes.clear();

        service.delete(TENANT, cube.id());

        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
        assertThat(graphStore.writes).containsExactly("clear " + CubeQb4olap.graphUri(cube.id()));
        assertThat(cubes.find(TENANT, cube.id())).isEmpty();
    }

    @Test
    @DisplayName("a star member cannot be deleted on its own — the refusal names the cube")
    void a_member_product_is_not_deleted_from_under_its_cube() {
        CubeDefinition cube = create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("customer", CUSTOMER, HAS_CUSTOMER)), null).cube();

        for (UUID member : cube.productIds()) {
            assertThatThrownBy(() -> products.delete(TENANT, member))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cube 'sales'");
        }
        assertThat(productRows.findByProject(TENANT, PROJECT_ID))
                .extracting(DataProduct::name)
                .containsExactlyInAnyOrder("sales_fact", "sales_customer");

        // Deleting the cube is how its star goes.
        service.delete(TENANT, cube.id());
        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a member product already gone does not strand its cube")
    void delete_tolerates_a_product_already_gone() {
        CubeDefinition cube = create(
                List.of(CubeMeasure.of("revenue", G + "amount", "SUM", Additivity.ADDITIVE)),
                List.of(CubeDimension.flat("customer", CUSTOMER, HAS_CUSTOMER)), null).cube();
        // A star built before members were protected may have lost one: removed at the
        // repository, since the service now refuses it.
        productRows.delete(TENANT, cube.productIds().get(0));

        service.delete(TENANT, cube.id());

        assertThat(cubes.find(TENANT, cube.id())).isEmpty();
        assertThat(productRows.findByProject(TENANT, PROJECT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a cube that does not exist is a 404, not a 500")
    void get_of_a_missing_cube() {
        assertThatThrownBy(() -> service.get(TENANT, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── helpers & fakes ──────────────────────────────────────────────────────

    private CubeService.CubeCreation create(List<CubeMeasure> measures,
                                            List<CubeDimension> dimensions,
                                            CubeCalendar calendar) {
        return create("sales", measures, dimensions, calendar);
    }

    private CubeService.CubeCreation create(String name, List<CubeMeasure> measures,
                                            List<CubeDimension> dimensions,
                                            CubeCalendar calendar) {
        return service.create(TENANT, PROJECT_ID, name, "Orders by customer", SALES,
                measures, dimensions, calendar, null);
    }

    private static String tempParquet() {
        try {
            Path parquet = Files.createTempFile("cube-member-", ".parquet");
            Files.write(parquet, new byte[] {1, 2, 3, 4});
            return parquet.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A {@link CubeRepository} in a map, with a switch for the "save blew up" path. */
    private static final class InMemoryCubeRepository implements CubeRepository {
        final Map<UUID, CubeRecord> rows = new LinkedHashMap<>();
        boolean failOnSave;

        @Override
        public List<CubeRecord> list(TenantContext tenant, UUID projectId) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().toString().equals(tenant.value()))
                    .filter(r -> r.projectId().equals(projectId)).toList();
        }

        @Override
        public Optional<CubeRecord> find(TenantContext tenant, UUID id) {
            return Optional.ofNullable(rows.get(id))
                    .filter(r -> r.tenantId().toString().equals(tenant.value()));
        }

        @Override
        public CubeRecord save(TenantContext tenant, CubeRecord cube) {
            if (failOnSave) {
                throw new IllegalStateException("save failed");
            }
            rows.put(cube.id(), cube);
            return cube;
        }

        @Override
        public void delete(TenantContext tenant, UUID id) {
            rows.remove(id);
        }
    }

    /** A graph store with a settable ACTIVE version that records what was written where, plus a
     *  switch for the "the graph write itself blew up" path. */
    private static final class RecordingGraphStore implements GraphStore {
        GraphVersion current;
        final List<String> writes = new ArrayList<>();
        Model lastAdded = ModelFactory.createDefaultModel();
        boolean failOnAddTriples;

        @Override public void provision(TenantContext tenant) { }

        @Override public Model query(TenantContext tenant, String sparql) {
            return ModelFactory.createDefaultModel();
        }

        @Override public GraphVersion replaceDraft(TenantContext tenant, Model fullGraph,
                                                   AuditedActor actor) {
            throw new UnsupportedOperationException();
        }

        @Override public GraphVersion promote(TenantContext tenant, UUID draftVersionId,
                                              AuditedActor actor) {
            throw new UnsupportedOperationException();
        }

        @Override public GraphVersion currentVersion(TenantContext tenant) {
            return current;
        }

        @Override public Model readVersion(TenantContext tenant, UUID versionId) {
            return ModelFactory.createDefaultModel();
        }

        @Override public void addTriples(TenantContext tenant, String graphUri, Model triples) {
            if (failOnAddTriples) {
                throw new IllegalStateException("addTriples failed");
            }
            writes.add("add " + graphUri);
            lastAdded = triples;
        }

        @Override public void clearGraph(TenantContext tenant, String graphUri) {
            writes.add("clear " + graphUri);
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

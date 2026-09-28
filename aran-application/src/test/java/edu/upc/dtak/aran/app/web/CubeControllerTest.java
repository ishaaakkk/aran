package edu.upc.dtak.aran.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CreateCubeRequest;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CubeCalendarDto;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CubeDefinitionDto;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CubeDimensionDto;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CubeDto;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CubeMeasureDto;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CubeProductRef;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.cube.Additivity;
import edu.upc.dtak.aran.core.domain.cube.CubeEvidence;
import edu.upc.dtak.aran.core.domain.cube.CubeQb4olap;
import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.domain.cube.CubeRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.service.CubeService;
import edu.upc.dtak.aran.core.service.DataProductService;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The cube vertical slice from the web boundary down: {@link CubeController}, the
 * {@code CubeDtos} translation, and the real {@link CubeService} over in-memory collaborators.
 *
 * <p>No Spring context, following {@code IntegrationModelControllerTest} and
 * {@code RelationalEngineControllerTest} — and no Docker, so this runs in
 * {@code validate.sh --offline}. The service is <em>real</em> rather than mocked because the
 * things worth pinning here are exactly the ones a mocked service would assert away: that the
 * wizard's DTO reaches the validation gates intact (a HYPOTHESIS join is refused at the boundary
 * that answers 400), that the star's positional product ids come back as named FACT/DIMENSION
 * refs, and that deleting a cube deletes the products it created.
 *
 * <p>The query engine is stubbed at the orchestrator, which is the seam {@code CubeService}
 * already treats as stringly typed: the {@code cube-evidence} payload here is the shape
 * {@code QueryEngineOperator} emits, so the evidence parse is exercised rather than bypassed.
 */
class CubeControllerTest {

    private static final String SALE = "http://ex/Sale";
    private static final String CUSTOMER = "http://ex/Customer";
    private static final String CHANNEL = "http://ex/Channel";
    private static final String AMOUNT = "http://ex/amount";
    private static final String SALE_DATE = "http://ex/sale_date";
    private static final String CUSTOMER_ID = "http://ex/customer_id";
    private static final String CUST_ID = "http://ex/cust_id";
    private static final String COUNTRY = "http://ex/country";
    private static final String PLACED_BY = "http://ex/placed_by";
    private static final String VIA_CHANNEL = "http://ex/via_channel";

    /**
     * The engine's payload, verbatim in shape: full IRIs throughout, {@code numeric} decided by
     * the widened XSD range, and a tier on every edge.
     */
    private static final String EVIDENCE = """
            {
              "classes": [
                {"iri": "http://ex/Sale", "name": "Sale", "rowCount": 1200, "properties": [
                  {"iri": "http://ex/amount", "name": "amount",
                   "xsd": "http://www.w3.org/2001/XMLSchema#decimal", "numeric": true,
                   "ndv": 900, "nullFraction": 0.0},
                  {"iri": "http://ex/sale_date", "name": "sale_date",
                   "xsd": "http://www.w3.org/2001/XMLSchema#date", "numeric": false,
                   "ndv": 365, "nullFraction": 0.0},
                  {"iri": "http://ex/customer_id", "name": "customer_id",
                   "xsd": "http://www.w3.org/2001/XMLSchema#string", "numeric": false,
                   "ndv": 40, "nullFraction": 0.05}
                ]},
                {"iri": "http://ex/Customer", "name": "Customer", "rowCount": 40, "properties": [
                  {"iri": "http://ex/cust_id", "name": "cust_id",
                   "xsd": "http://www.w3.org/2001/XMLSchema#string", "numeric": false,
                   "ndv": 40, "nullFraction": 0.0},
                  {"iri": "http://ex/country", "name": "country",
                   "xsd": "http://www.w3.org/2001/XMLSchema#string", "numeric": false,
                   "ndv": 6, "nullFraction": 0.0}
                ]},
                {"iri": "http://ex/Channel", "name": "Channel", "rowCount": 5, "properties": [
                  {"iri": "http://ex/channel_id", "name": "channel_id",
                   "xsd": "http://www.w3.org/2001/XMLSchema#string", "numeric": false,
                   "ndv": 5, "nullFraction": 0.0}
                ]}
              ],
              "edges": [
                {"propertyIri": "http://ex/placed_by", "propertyName": "placed_by",
                 "leftClassIri": "http://ex/Sale", "rightClassIri": "http://ex/Customer",
                 "leftColIri": "http://ex/customer_id", "rightColIri": "http://ex/cust_id",
                 "tier": "DECLARED"},
                {"propertyIri": "http://ex/via_channel", "propertyName": "via_channel",
                 "leftClassIri": "http://ex/Sale", "rightClassIri": "http://ex/Channel",
                 "leftColIri": "http://ex/channel_id", "rightColIri": "http://ex/channel_id",
                 "tier": "HYPOTHESIS"}
              ]
            }
            """;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID schemaVersionId = UUID.randomUUID();

    private final InMemoryCubes cubes = new InMemoryCubes();
    private final GraphStore graphStore = mock(GraphStore.class);
    private final OperatorOrchestrator orchestrator = mock(OperatorOrchestrator.class);
    private final DataProductService products = mock(DataProductService.class);
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    /** Permissive by default: these tests are about the wizard's contract, not the gate. */
    private final ProjectAccess projectAccess = mock(ProjectAccess.class);

    private final CubeService cubeService =
            new CubeService(cubes, graphStore, orchestrator, products, new ObjectMapper());
    private final CubeController controller =
            new CubeController(cubeService, currentUser, projectAccess);

    /**
     * The port, in memory. A mock would have to be told the answer to every read the service makes
     * of its own writes, which is precisely what the create-then-read tests below are checking.
     */
    private static final class InMemoryCubes implements CubeRepository {
        private final Map<UUID, CubeRecord> rows = new LinkedHashMap<>();

        @Override
        public List<CubeRecord> list(TenantContext tenant, UUID projectId) {
            List<CubeRecord> matching = new ArrayList<>();
            for (CubeRecord row : rows.values()) {
                if (row.tenantId().toString().equals(tenant.value())
                        && row.projectId().equals(projectId)) {
                    matching.add(row);
                }
            }
            return List.copyOf(matching);
        }

        @Override
        public Optional<CubeRecord> find(TenantContext tenant, UUID id) {
            return Optional.ofNullable(rows.get(id))
                    .filter(row -> row.tenantId().toString().equals(tenant.value()));
        }

        @Override
        public CubeRecord save(TenantContext tenant, CubeRecord cube) {
            rows.put(cube.id(), cube);
            return cube;
        }

        @Override
        public void delete(TenantContext tenant, UUID id) {
            rows.remove(id);
        }
    }

    private void authenticated() {
        when(currentUser.require())
                .thenReturn(new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER));
    }

    private void schemaIsActive() {
        when(graphStore.currentVersion(any())).thenReturn(new GraphVersion(
                schemaVersionId, tenantId, 3, "ACTIVE", Instant.now(), userId, Instant.now(), null));
    }

    private void engineAnswersEvidence() {
        when(orchestrator.execute(eq("query-engine"), any()))
                .thenReturn(OperatorResult.success(Map.of("status", "ok", "evidence", EVIDENCE)));
    }

    /** Every {@code products.create} answers with a fresh DRAFT product, and records the job. */
    private void productsAreCreatable() {
        when(products.create(any(), eq(projectId), any(), any(), any(), any()))
                .thenAnswer(inv -> DataProduct.draft(UUID.randomUUID(), tenantId, projectId,
                        inv.getArgument(2), inv.getArgument(3), inv.getArgument(4),
                        schemaVersionId, userId, Instant.now()));
        when(products.materialize(any(), any(), any())).thenAnswer(inv -> UUID.randomUUID());
    }

    private static CubeDefinitionDto definition() {
        return new CubeDefinitionDto(SALE,
                List.of(new CubeMeasureDto("revenue", AMOUNT, null, "SUM", Additivity.ADDITIVE)),
                List.of(new CubeDimensionDto("customer", CUSTOMER, PLACED_BY, false,
                        List.of(COUNTRY), false)),
                new CubeCalendarDto(SALE_DATE, List.of("year", "month")));
    }

    private CubeDto create() {
        return controller.create(projectId, new CreateCubeRequest("sales", "the sales star",
                definition())).getBody();
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("creating a cube answers HttpStatus.CREATED with its star products and the jobs "
            + "materializing them")
    void createsCubeWithStarAndJobs() {
        authenticated();
        schemaIsActive();
        engineAnswersEvidence();
        productsAreCreatable();

        ResponseEntity<CubeDto> response = controller.create(projectId,
                new CreateCubeRequest("sales", "the sales star", definition()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CubeDto cube = response.getBody();
        assertThat(cube.name()).isEqualTo("sales");
        assertThat(cube.description()).isEqualTo("the sales star");
        assertThat(cube.factClassIri()).isEqualTo(SALE);
        // Fact first, then one product per dimension in definition order — the positional
        // convention the DTO exists to spell out.
        assertThat(cube.products()).extracting(CubeProductRef::role, CubeProductRef::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FACT", "sales_fact"),
                        org.assertj.core.groups.Tuple.tuple("DIMENSION", "sales_customer"));
        assertThat(cube.jobIds()).hasSize(2);
        assertThat(cube.definition().measures()).singleElement()
                .extracting(CubeMeasureDto::name, CubeMeasureDto::aggregator)
                .containsExactly("revenue", "SUM");
        assertThat(cube.definition().calendar().levels()).containsExactly("year", "month");
        // The QB4OLAP description is written into the cube's own named graph, cleared first.
        verify(graphStore).clearGraph(any(), eq(CubeQb4olap.graphUri(cube.id())));
        verify(graphStore).addTriples(any(), eq(CubeQb4olap.graphUri(cube.id())), any());
    }

    @Test
    @DisplayName("the fact product's OQL projects the measure, the join key and the calendar column")
    void factOqlCarriesEveryColumnTheStarNeeds() {
        authenticated();
        schemaIsActive();
        engineAnswersEvidence();
        productsAreCreatable();

        create();

        org.mockito.ArgumentCaptor<String> oql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(products, org.mockito.Mockito.times(2))
                .create(any(), eq(projectId), any(), any(), oql.capture(), any());
        assertThat(oql.getAllValues().getFirst())
                .isEqualTo("SELECT Sale.amount, Sale.customer_id, Sale.sale_date FROM Sale");
        assertThat(oql.getAllValues().get(1))
                .isEqualTo("SELECT Customer.cust_id, Customer.country FROM Customer");
    }

    @Test
    @DisplayName("a dimension on a HYPOTHESIS join is refused before any product is created "
            + "(ApiExceptionHandler maps it to 400)")
    void refusesUnverifiedJoin() {
        authenticated();
        schemaIsActive();
        engineAnswersEvidence();

        CubeDefinitionDto hypothesis = new CubeDefinitionDto(SALE,
                List.of(new CubeMeasureDto("revenue", AMOUNT, null, "SUM", Additivity.ADDITIVE)),
                List.of(new CubeDimensionDto("channel", CHANNEL, VIA_CHANNEL, false, List.of(),
                        false)),
                null);

        assertThatThrownBy(() -> controller.create(projectId,
                new CreateCubeRequest("sales", null, hypothesis)))
                // ApiExceptionHandler maps this to 400.
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HYPOTHESIS");
        verify(products, never()).create(any(), any(), any(), any(), any(), any());
        assertThat(cubes.rows).isEmpty();
    }

    @Test
    @DisplayName("the same dimension with allowUnverified set is accepted — the user took the risk")
    void allowUnverifiedInsists() {
        authenticated();
        schemaIsActive();
        engineAnswersEvidence();
        productsAreCreatable();

        CubeDefinitionDto insisted = new CubeDefinitionDto(SALE,
                List.of(new CubeMeasureDto("revenue", AMOUNT, null, "SUM", Additivity.ADDITIVE)),
                List.of(new CubeDimensionDto("channel", CHANNEL, VIA_CHANNEL, false, List.of(),
                        true)),
                null);

        CubeDto cube = controller.create(projectId,
                new CreateCubeRequest("sales", null, insisted)).getBody();

        assertThat(cube.definition().dimensions()).singleElement()
                .extracting(CubeDimensionDto::allowUnverified).isEqualTo(true);
    }

    @Test
    @DisplayName("a body with no name is refused naming the field, before any product is created "
            + "(ApiExceptionHandler maps it to 400)")
    void refusesANamelessBody() {
        authenticated();

        assertThatThrownBy(() -> controller.create(projectId,
                new CreateCubeRequest(null, "no name at all", definition())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        verify(products, never()).create(any(), any(), any(), any(), any(), any());
        assertThat(cubes.rows).isEmpty();
    }

    @Test
    @DisplayName("a body with no definition is refused the same way — a missing piece is a 400, "
            + "never an NPE's 500")
    void refusesABodyWithNoDefinition() {
        authenticated();

        assertThatThrownBy(() -> controller.create(projectId,
                new CreateCubeRequest("sales", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("definition is required");
        verify(products, never()).create(any(), any(), any(), any(), any(), any());
        assertThat(cubes.rows).isEmpty();
    }

    // ── read ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list and detail read back what create wrote, with no jobs attached")
    void listAndDetailRoundTrip() {
        authenticated();
        schemaIsActive();
        engineAnswersEvidence();
        productsAreCreatable();
        CubeDto created = create();

        List<CubeDto> listed = controller.list(projectId);
        CubeDto fetched = controller.get(projectId, created.id());

        assertThat(listed).singleElement().extracting(CubeDto::id).isEqualTo(created.id());
        // A read starts no materialization, so it reports none — unlike the create above.
        assertThat(listed.getFirst().jobIds()).isEmpty();
        assertThat(fetched.jobIds()).isEmpty();
        assertThat(fetched.products()).isEqualTo(created.products());
        assertThat(fetched.definition()).isEqualTo(created.definition());
    }

    @Test
    @DisplayName("the yaml endpoint answers text/plain carrying the cube's own name and measures")
    void yamlIsServedAsText() {
        authenticated();
        schemaIsActive();
        engineAnswersEvidence();
        productsAreCreatable();
        CubeDto created = create();

        ResponseEntity<String> response = controller.yaml(projectId, created.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN))
                .isTrue();
        assertThat(response.getBody()).contains("sales").contains("revenue").contains("customer");
    }

    @Test
    @DisplayName("evidence is served parsed — the classes and tiered edges the wizard prefills from")
    void evidenceIsParsedNotPassedThrough() {
        authenticated();
        engineAnswersEvidence();

        CubeEvidence evidence = controller.evidence(projectId);

        assertThat(evidence.classes()).extracting(CubeEvidence.EvidenceClass::name)
                .containsExactly("Sale", "Customer", "Channel");
        assertThat(evidence.classOf(SALE)).get()
                .extracting(c -> c.property(AMOUNT).orElseThrow().numeric()).isEqualTo(true);
        assertThat(evidence.edgeOf(PLACED_BY)).get()
                .extracting(CubeEvidence.EvidenceEdge::tier).isEqualTo("DECLARED");
        // The full join-key IRIs, not local names — a wizard cannot address a column without them.
        assertThat(evidence.edgeOf(PLACED_BY).orElseThrow().columnOn(SALE))
                .isEqualTo(CUSTOMER_ID);
        assertThat(evidence.edgeOf(PLACED_BY).orElseThrow().columnOppositeTo(SALE))
                .isEqualTo(CUST_ID);
        assertThat(evidence.edgeOf(VIA_CHANNEL)).get()
                .extracting(CubeEvidence.EvidenceEdge::tier).isEqualTo("HYPOTHESIS");
    }

    @Test
    @DisplayName("a cube that does not exist throws instead of answering an empty body "
            + "(ApiExceptionHandler maps it to 404)")
    void unknownCubeIsNotFound() {
        authenticated();

        assertThatThrownBy(() -> controller.get(projectId, UUID.randomUUID()))
                // ApiExceptionHandler maps this to 404.
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleting a cube answers HttpStatus.NO_CONTENT and deletes the star products it "
            + "created")
    void deleteRemovesMemberProducts() {
        authenticated();
        schemaIsActive();
        engineAnswersEvidence();
        productsAreCreatable();
        CubeDto created = create();
        List<UUID> memberIds = created.products().stream().map(CubeProductRef::id).toList();

        ResponseEntity<Void> response = controller.delete(projectId, created.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        for (UUID memberId : memberIds) {
            verify(products).delete(any(), eq(memberId));
        }
        // Twice: once writing the description at create (addTriples is additive, so it clears
        // first), once tearing the graph down here.
        verify(graphStore, org.mockito.Mockito.times(2))
                .clearGraph(any(), eq(CubeQb4olap.graphUri(created.id())));
        assertThat(cubes.rows).isEmpty();
        assertThat(controller.list(projectId)).isEmpty();
    }
    @Test
    @DisplayName("S2-1: a project the caller cannot see answers not-found before any cube work runs")
    void invisible_project_is_refused_first() {
        when(currentUser.require())
                .thenReturn(new AuthenticatedUser(userId, "sub", tenantId, TenantRole.MEMBER));
        when(projectAccess.getVisibleProject(any(), eq(projectId)))
                .thenThrow(new ResourceNotFoundException("project not found: " + projectId));

        assertThatThrownBy(() -> controller.list(projectId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.evidence(projectId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.get(projectId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(orchestrator);
    }
}

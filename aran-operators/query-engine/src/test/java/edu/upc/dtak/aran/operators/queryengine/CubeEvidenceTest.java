package edu.upc.dtak.aran.operators.queryengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code cube-evidence}: the dimensional-modeling evidence the {@code catalog} payload omits —
 * full IRIs, XSD ranges, profile statistics, and referential edges with their trust tiers.
 *
 * <p>Every assertion here is a contract a "shape as cube" wizard reads directly, so the field
 * names and the null-vs-zero distinction are as much the subject as the values are.
 */
class CubeEvidenceTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String G = "http://ex.com/g/";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);

    private JsonNode evidence(MappingCatalog cat) throws Exception {
        when(catalogOperator.getCatalog(any(TenantContext.class))).thenReturn(cat);
        QueryEngineOperator operator = new QueryEngineOperator(
                mock(GraphStore.class), catalogOperator, mock(LakeClient.class), new ObjectMapper(),
                mock(IntegrationModelRepository.class),
                mock(DatasetRepository.class),
                mock(DataRepositoryRepository.class),
                mock(SnapshotCache.class),
                mock(DataProductRepository.class));

        OperatorResult result = operator.execute(
                new OperatorRequest(TENANT, null, "cube-evidence", Map.of()));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        return new ObjectMapper().readTree(result.data().get("evidence"));
    }

    // ── classes and properties ───────────────────────────────────────────────

    @Test
    @DisplayName("a class carries its full IRI, its row count, and every property's IRI and range")
    void classesCarryIrisTypesAndRowCount() throws Exception {
        JsonNode payload = evidence(starCatalog());
        JsonNode sale = classByName(payload, "Sale");

        assertThat(sale.get("iri").asText()).isEqualTo(G + "Sale");
        // Profile-derived, summed over the class's sources exactly as IntegratedClassTable does.
        assertThat(sale.get("rowCount").asLong()).isEqualTo(500L);

        JsonNode amount = propertyByName(sale, "amount");
        assertThat(amount.get("iri").asText()).isEqualTo(G + "amount");
        assertThat(amount.get("xsd").asText()).isEqualTo(XSD + "decimal");
    }

    @Test
    @DisplayName("numeric flags the measure candidates: decimal and int yes, string no")
    void numericFlagsMeasureCandidates() throws Exception {
        JsonNode sale = classByName(evidence(starCatalog()), "Sale");

        assertThat(propertyByName(sale, "amount").get("numeric").asBoolean()).isTrue();
        assertThat(propertyByName(sale, "customerId").get("numeric").asBoolean()).isTrue();
        assertThat(propertyByName(sale, "label").get("numeric").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("an XSD range this payload calls numeric but XsdTypes widens to VARCHAR still reads numeric")
    void unsignedRangesAreMeasureCandidates() throws Exception {
        // unsignedInt has no DuckDB widening target in XsdTypes, so the int_ view declares it
        // VARCHAR — but it is numeric in XSD, and "is this a candidate measure?" is a modeling
        // question. The asymmetry is deliberate; this pins it so it cannot drift silently.
        MappingCatalog cat = singleSourceCatalog(
                Map.of(G + "quantity", "src/quantity"),
                Map.of(G + "quantity", XSD + "unsignedInt"),
                List.of(), 0L);

        JsonNode quantity = propertyByName(classByName(evidence(cat), "Fact"), "quantity");
        assertThat(quantity.get("numeric").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("NDV and null fraction come from the class's own profile evidence")
    void statisticsComeFromProfiles() throws Exception {
        JsonNode sale = classByName(evidence(starCatalog()), "Sale");

        JsonNode customerId = propertyByName(sale, "customerId");
        assertThat(customerId.get("ndv").asLong()).isEqualTo(50L);
        assertThat(customerId.get("nullFraction").asDouble()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("a class with no profiles yields nulls, never zeros")
    void unprofiledClassYieldsNullsNotZeros() throws Exception {
        JsonNode customer = classByName(evidence(starCatalog()), "Customer");

        // The distinction the whole payload turns on: 0 rows / 0 distinct values / 0% NULL are
        // measurements, and this class has none.
        assertThat(customer.get("rowCount").isNull()).isTrue();
        JsonNode name = propertyByName(customer, "name");
        assertThat(name.get("ndv").isNull()).isTrue();
        assertThat(name.get("nullFraction").isNull()).isTrue();
    }

    @Test
    @DisplayName("a HARVESTED profile contributes its NDV but never its structural-zero null fraction")
    void harvestedProfileContributesNdvOnly() throws Exception {
        ColumnProfile harvested = profile("src/region", 7L, 0.0, "HARVESTED");
        MappingCatalog cat = singleSourceCatalog(
                Map.of(G + "region", "src/region"),
                Map.of(G + "region", XSD + "string"),
                List.of(harvested), 120L);

        JsonNode region = propertyByName(classByName(evidence(cat), "Fact"), "region");
        assertThat(region.get("ndv").asLong()).isEqualTo(7L);
        assertThat(region.get("nullFraction").isNull()).isTrue();
    }

    @Test
    @DisplayName("a column profiled as all-NULL reports no NDV rather than zero distinct values")
    void zeroCardinalityIsNoEvidence() throws Exception {
        MappingCatalog cat = singleSourceCatalog(
                Map.of(G + "note", "src/note"),
                Map.of(),
                List.of(profile("src/note", 0L, 1.0, ColumnProfile.SUBJECT_SNAPSHOT)), 120L);

        JsonNode note = propertyByName(classByName(evidence(cat), "Fact"), "note");
        assertThat(note.get("ndv").isNull()).isTrue();
        assertThat(note.get("xsd").isNull()).isTrue();
        assertThat(note.get("numeric").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("sources that disagree on a range report no range, and no statistics")
    void multiSourceDisagreementDegrades() throws Exception {
        MappingCatalog.SourceView a = new MappingCatalog.SourceView(
                "ds1", "SELECT 1", Map.of(G + "code", "src/code"), 100L,
                List.of(profile("src/code", 90L, 0.9, ColumnProfile.SUBJECT_SNAPSHOT)),
                "http://ex.com/s/a", Map.of(G + "code", XSD + "integer"));
        MappingCatalog.SourceView b = new MappingCatalog.SourceView(
                "ds2", "SELECT 1", Map.of(G + "code", "src/code"), 100L, List.of(),
                "http://ex.com/s/b", Map.of(G + "code", XSD + "string"));
        MappingCatalog cat = new MappingCatalog(
                Map.of(G + "Fact", new MappingCatalog.IntegratedClass(G + "Fact", List.of(a, b))),
                List.of(), List.of(), "v1");

        JsonNode code = propertyByName(classByName(evidence(cat), "Fact"), "code");
        assertThat(code.get("xsd").isNull()).isTrue();
        assertThat(code.get("numeric").asBoolean()).isFalse();
        // A UNION ALL's NDV is not knowable from per-source profiles, so neither is offered.
        assertThat(code.get("ndv").isNull()).isTrue();
        assertThat(code.get("nullFraction").isNull()).isTrue();
        // Row count still sums: it is the one statistic a union does compose.
        assertThat(classByName(evidence(cat), "Fact").get("rowCount").asLong()).isEqualTo(200L);
    }

    // ── edges ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an edge carries full class and column IRIs, not the catalog payload's local names")
    void edgesCarryFullIris() throws Exception {
        JsonNode edges = evidence(starCatalog()).get("edges");
        JsonNode edge = edgeByProperty(edges, G + "hasCustomer");

        assertThat(edge.get("propertyName").asText()).isEqualTo("hasCustomer");
        assertThat(edge.get("leftClassIri").asText()).isEqualTo(G + "Sale");
        assertThat(edge.get("rightClassIri").asText()).isEqualTo(G + "Customer");
        assertThat(edge.get("leftColIri").asText()).isEqualTo(G + "customerId");
        assertThat(edge.get("rightColIri").asText()).isEqualTo(G + "id");
    }

    @Test
    @DisplayName("an accepted join alone is HYPOTHESIS")
    void acceptedJoinIsHypothesis() throws Exception {
        MappingCatalog cat = new MappingCatalog(
                starClasses(), List.of(hasCustomer()), List.of(), "v1");

        assertThat(edgeByProperty(evidence(cat).get("edges"), G + "hasCustomer")
                .get("tier").asText()).isEqualTo("HYPOTHESIS");
    }

    @Test
    @DisplayName("a harvested FK over the same columns upgrades that one edge to DECLARED, not a second one")
    void declaredUpgradesRatherThanDuplicates() throws Exception {
        MappingCatalog cat = new MappingCatalog(
                starClasses(), List.of(hasCustomer()),
                List.of(),
                List.of(new MappingCatalog.ReferentialEdge(
                        G + "Sale", List.of(G + "customerId"),
                        G + "Customer", List.of(G + "id"), MappingCatalog.Tier.DECLARED)),
                "v1");

        JsonNode edges = evidence(cat).get("edges");
        assertThat(edges.size()).isEqualTo(1);
        // The join property survives the upgrade: without it there is no OQL step to traverse with.
        assertThat(edges.get(0).get("propertyIri").asText()).isEqualTo(G + "hasCustomer");
        assertThat(edges.get(0).get("tier").asText()).isEqualTo("DECLARED");
    }

    @Test
    @DisplayName("a harvested FK with no accepted join is reported with an empty property, not an invented one")
    void declaredWithoutJoinPropertyHasEmptyProperty() throws Exception {
        MappingCatalog cat = new MappingCatalog(
                starClasses(), List.of(), List.of(),
                List.of(new MappingCatalog.ReferentialEdge(
                        G + "Sale", List.of(G + "customerId"),
                        G + "Customer", List.of(G + "id"), MappingCatalog.Tier.DECLARED)),
                "v1");

        JsonNode edges = evidence(cat).get("edges");
        assertThat(edges.size()).isEqualTo(1);
        assertThat(edges.get(0).get("propertyIri").asText()).isEmpty();
        assertThat(edges.get(0).get("propertyName").asText()).isEmpty();
        assertThat(edges.get(0).get("tier").asText()).isEqualTo("DECLARED");
    }

    @Test
    @DisplayName("a composite foreign key is dropped rather than reported as a single-column join")
    void compositeEdgeIsDropped() throws Exception {
        MappingCatalog cat = new MappingCatalog(
                starClasses(), List.of(), List.of(),
                List.of(new MappingCatalog.ReferentialEdge(
                        G + "Sale", List.of(G + "customerId", G + "label"),
                        G + "Customer", List.of(G + "id", G + "name"),
                        MappingCatalog.Tier.DECLARED)),
                "v1");

        assertThat(evidence(cat).get("edges").size()).isZero();
    }

    @Test
    @DisplayName("an empty schema is empty lists, not a failure")
    void emptySchemaYieldsEmptyLists() throws Exception {
        MappingCatalog cat = new MappingCatalog(Map.of(), List.of(), List.of(), null);

        JsonNode payload = evidence(cat);
        assertThat(payload.get("classes").size()).isZero();
        assertThat(payload.get("edges").size()).isZero();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * A minimal star: a profiled {@code Sale} fact over ds1 joined to an unprofiled
     * {@code Customer} dimension over ds2.
     */
    private static MappingCatalog starCatalog() {
        return new MappingCatalog(starClasses(), List.of(hasCustomer()), List.of(), "v1");
    }

    private static Map<String, MappingCatalog.IntegratedClass> starClasses() {
        Map<String, String> columnMap = new LinkedHashMap<>();
        columnMap.put(G + "amount", "src/amount");
        columnMap.put(G + "customerId", "src/customerId");
        columnMap.put(G + "label", "src/label");
        Map<String, String> columnTypes = new LinkedHashMap<>();
        columnTypes.put(G + "amount", XSD + "decimal");
        columnTypes.put(G + "customerId", XSD + "int");
        columnTypes.put(G + "label", XSD + "string");
        MappingCatalog.SourceView sale = new MappingCatalog.SourceView(
                "ds1", "SELECT 1", columnMap, 500L,
                List.of(profile("src/amount", 480L, 0.96, ColumnProfile.SUBJECT_SNAPSHOT),
                        profile("src/customerId", 50L, 0.1, 0.25, ColumnProfile.SUBJECT_SNAPSHOT)),
                "http://ex.com/s/sale", columnTypes);

        // No profiles and no row count: the "never measured" side of every null assertion above.
        MappingCatalog.SourceView customer = new MappingCatalog.SourceView(
                "ds2", "SELECT 1",
                Map.of(G + "id", "src/id", G + "name", "src/name"), 0L, List.of(),
                "http://ex.com/s/customer",
                Map.of(G + "id", XSD + "int", G + "name", XSD + "string"));

        Map<String, MappingCatalog.IntegratedClass> classes = new LinkedHashMap<>();
        classes.put(G + "Sale", new MappingCatalog.IntegratedClass(G + "Sale", List.of(sale)));
        classes.put(G + "Customer",
                new MappingCatalog.IntegratedClass(G + "Customer", List.of(customer)));
        return classes;
    }

    private static MappingCatalog.JoinProperty hasCustomer() {
        return new MappingCatalog.JoinProperty(
                G + "hasCustomer", G + "Sale", G + "Customer", G + "customerId", G + "id");
    }

    /** One single-source class named {@code Fact}, with whatever columns/profiles a test needs. */
    private static MappingCatalog singleSourceCatalog(Map<String, String> columnMap,
                                                      Map<String, String> columnTypes,
                                                      List<ColumnProfile> profiles, long rowCount) {
        MappingCatalog.SourceView source = new MappingCatalog.SourceView(
                "ds1", "SELECT 1", columnMap, rowCount, profiles, "http://ex.com/s/fact", columnTypes);
        return new MappingCatalog(
                Map.of(G + "Fact", new MappingCatalog.IntegratedClass(G + "Fact", List.of(source))),
                List.of(), List.of(), "v1");
    }

    private static ColumnProfile profile(String columnName, long cardinality, double uniqueness,
                                         String subject) {
        return profile(columnName, cardinality, uniqueness, 0.0, subject);
    }

    private static ColumnProfile profile(String columnName, long cardinality, double uniqueness,
                                         double incompleteness, String subject) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 0, "VARCHAR",
                cardinality, null, uniqueness, incompleteness,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                subject, null);
    }

    // ── payload navigation ───────────────────────────────────────────────────

    private static JsonNode classByName(JsonNode payload, String name) {
        for (JsonNode cls : payload.get("classes")) {
            if (cls.get("name").asText().equals(name)) {
                return cls;
            }
        }
        throw new AssertionError("no class '" + name + "' in " + payload.get("classes"));
    }

    private static JsonNode propertyByName(JsonNode cls, String name) {
        for (JsonNode property : cls.get("properties")) {
            if (property.get("name").asText().equals(name)) {
                return property;
            }
        }
        throw new AssertionError("no property '" + name + "' on " + cls);
    }

    private static JsonNode edgeByProperty(JsonNode edges, String propertyIri) {
        for (JsonNode edge : edges) {
            if (edge.get("propertyIri").asText().equals(propertyIri)) {
                return edge;
            }
        }
        throw new AssertionError("no edge for '" + propertyIri + "' in " + edges);
    }
}

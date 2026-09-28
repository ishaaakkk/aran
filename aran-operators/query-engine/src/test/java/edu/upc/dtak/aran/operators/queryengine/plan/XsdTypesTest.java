package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The XSD range → SQL type mapping and the widen lattice union-typed columns fold through.
 *
 * <p>Per the design (source-access-cbo-design.md §3.2): "Where sources of one class disagree,
 * widen; fall back to VARCHAR per column, never fail compile — union type alignment stays
 * by-construction." Every case here is either the mapping table, or a way that rule can be
 * pushed on (disagreement, missing type info, garbage input) without the fallback slipping.
 */
class XsdTypesTest {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    @Test
    @DisplayName("every mapped XSD datatype resolves to its documented SQL type")
    void mappingTable() {
        Map<String, SqlTypeName> table = Map.ofEntries(
                Map.entry(XSD + "string", SqlTypeName.VARCHAR),
                Map.entry(XSD + "integer", SqlTypeName.BIGINT),
                Map.entry(XSD + "int", SqlTypeName.BIGINT),
                Map.entry(XSD + "long", SqlTypeName.BIGINT),
                Map.entry(XSD + "short", SqlTypeName.BIGINT),
                Map.entry(XSD + "byte", SqlTypeName.BIGINT),
                Map.entry(XSD + "nonNegativeInteger", SqlTypeName.BIGINT),
                Map.entry(XSD + "positiveInteger", SqlTypeName.BIGINT),
                Map.entry(XSD + "decimal", SqlTypeName.DECIMAL),
                Map.entry(XSD + "double", SqlTypeName.DOUBLE),
                Map.entry(XSD + "float", SqlTypeName.DOUBLE),
                Map.entry(XSD + "boolean", SqlTypeName.BOOLEAN),
                Map.entry(XSD + "date", SqlTypeName.DATE),
                Map.entry(XSD + "dateTime", SqlTypeName.TIMESTAMP));

        table.forEach((iri, expected) ->
                assertThat(XsdTypes.fromXsd(iri)).as("fromXsd(%s)", iri).isEqualTo(expected));
    }

    @Test
    @DisplayName("a bare XSD local name with no namespace prefix resolves the same as the full IRI")
    void bareLocalNameAlsoResolves() {
        // Not a requirement — MappingCatalogOperator always stores the full IRI Jena reads off
        // rdfs:range — but fromXsd only strips the namespace when present, so a bare local name
        // falls through to the same switch rather than to the unrecognised-IRI branch. Pinned so
        // a future refactor changes this on purpose, not by accident.
        assertThat(XsdTypes.fromXsd("integer")).isEqualTo(SqlTypeName.BIGINT);
        assertThat(XsdTypes.fromXsd("dateTime")).isEqualTo(SqlTypeName.TIMESTAMP);
    }

    @Test
    @DisplayName("null or an unrecognised IRI falls back to VARCHAR, never throws")
    void unknownFallsBackToVarchar() {
        assertThat(XsdTypes.fromXsd(null)).isEqualTo(SqlTypeName.VARCHAR);
        assertThat(XsdTypes.fromXsd("")).isEqualTo(SqlTypeName.VARCHAR);
        assertThat(XsdTypes.fromXsd("not-an-iri")).isEqualTo(SqlTypeName.VARCHAR);
        assertThat(XsdTypes.fromXsd("http://example.com/CustomType")).isEqualTo(SqlTypeName.VARCHAR);
        // A real XSD datatype this mapping simply has no opinion about (yet) — same fallback,
        // not a crash, because an unmapped XSD type is not evidence of anything narrower.
        assertThat(XsdTypes.fromXsd(XSD + "anyURI")).isEqualTo(SqlTypeName.VARCHAR);
    }

    @Test
    @DisplayName("agreeing types widen to themselves")
    void widenIdentity() {
        for (SqlTypeName t : allMappedTypes()) {
            assertThat(XsdTypes.widen(t, t)).as("widen(%s, %s)", t, t).isEqualTo(t);
        }
    }

    @Test
    @DisplayName("numeric disagreements widen to the wider numeric type")
    void widenNumericPromotion() {
        assertThat(XsdTypes.widen(SqlTypeName.BIGINT, SqlTypeName.DECIMAL)).isEqualTo(SqlTypeName.DECIMAL);
        assertThat(XsdTypes.widen(SqlTypeName.DECIMAL, SqlTypeName.BIGINT)).isEqualTo(SqlTypeName.DECIMAL);
        assertThat(XsdTypes.widen(SqlTypeName.BIGINT, SqlTypeName.DOUBLE)).isEqualTo(SqlTypeName.DOUBLE);
        assertThat(XsdTypes.widen(SqlTypeName.DOUBLE, SqlTypeName.BIGINT)).isEqualTo(SqlTypeName.DOUBLE);
        assertThat(XsdTypes.widen(SqlTypeName.DECIMAL, SqlTypeName.DOUBLE)).isEqualTo(SqlTypeName.DOUBLE);
        assertThat(XsdTypes.widen(SqlTypeName.DOUBLE, SqlTypeName.DECIMAL)).isEqualTo(SqlTypeName.DOUBLE);
    }

    @Test
    @DisplayName("any other disagreement is the honest VARCHAR fallback, never a failure")
    void widenNonNumericDisagreementFallsBack() {
        // DATE/TIMESTAMP are not in the promoted-numeric list even though one could argue a
        // DATE reads fine as a TIMESTAMP: the rule is closed-world on purpose, not "widen
        // whatever seems safe" — anything not explicitly promoted is VARCHAR.
        assertThat(XsdTypes.widen(SqlTypeName.DATE, SqlTypeName.TIMESTAMP)).isEqualTo(SqlTypeName.VARCHAR);
        assertThat(XsdTypes.widen(SqlTypeName.BOOLEAN, SqlTypeName.DATE)).isEqualTo(SqlTypeName.VARCHAR);
        assertThat(XsdTypes.widen(SqlTypeName.VARCHAR, SqlTypeName.BIGINT)).isEqualTo(SqlTypeName.VARCHAR);
        assertThat(XsdTypes.widen(SqlTypeName.BOOLEAN, SqlTypeName.TIMESTAMP)).isEqualTo(SqlTypeName.VARCHAR);
    }

    @Test
    @DisplayName("widen is commutative for every pair drawn from the types the mapping produces")
    void widenIsCommutative() {
        for (SqlTypeName a : allMappedTypes()) {
            for (SqlTypeName b : allMappedTypes()) {
                assertThat(XsdTypes.widen(a, b))
                        .as("widen(%s, %s) vs widen(%s, %s)", a, b, b, a)
                        .isEqualTo(XsdTypes.widen(b, a));
            }
        }
    }

    @Test
    @DisplayName("duckType names DuckDB's column type for each widened SQL type")
    void duckTypeNames() {
        assertThat(XsdTypes.duckType(SqlTypeName.VARCHAR)).isEqualTo("VARCHAR");
        assertThat(XsdTypes.duckType(SqlTypeName.BIGINT)).isEqualTo("BIGINT");
        assertThat(XsdTypes.duckType(SqlTypeName.DECIMAL)).isEqualTo("DECIMAL(38,9)");
        assertThat(XsdTypes.duckType(SqlTypeName.DOUBLE)).isEqualTo("DOUBLE");
        assertThat(XsdTypes.duckType(SqlTypeName.BOOLEAN)).isEqualTo("BOOLEAN");
        assertThat(XsdTypes.duckType(SqlTypeName.DATE)).isEqualTo("DATE");
        assertThat(XsdTypes.duckType(SqlTypeName.TIMESTAMP)).isEqualTo("TIMESTAMP");
    }

    @Test
    @DisplayName("widenedType folds every source's XSD range for one global column")
    void widenedTypeAgreement() {
        String iri = "urn:test:year";
        MappingCatalog.IntegratedClass cls = new MappingCatalog.IntegratedClass("urn:test:Class", List.of(
                sourceWithType("ds1", iri, XSD + "integer"),
                sourceWithType("ds2", iri, XSD + "integer")));
        assertThat(XsdTypes.widenedType(cls, iri)).isEqualTo(SqlTypeName.BIGINT);
    }

    @Test
    @DisplayName("widenedType promotes across sources exactly like widen() does")
    void widenedTypePromotesAcrossSources() {
        String iri = "urn:test:amount";
        MappingCatalog.IntegratedClass cls = new MappingCatalog.IntegratedClass("urn:test:Class", List.of(
                sourceWithType("ds1", iri, XSD + "integer"),
                sourceWithType("ds2", iri, XSD + "decimal")));
        assertThat(XsdTypes.widenedType(cls, iri)).isEqualTo(SqlTypeName.DECIMAL);
    }

    @Test
    @DisplayName("a typed source disagreeing with an untyped one falls back to VARCHAR")
    void widenedTypeDisagreementFallsBackToVarchar() {
        String iri = "urn:test:year";
        MappingCatalog.IntegratedClass cls = new MappingCatalog.IntegratedClass("urn:test:Class", List.of(
                sourceWithType("ds1", iri, XSD + "integer"),
                sourceWithoutType("ds2")));
        assertThat(XsdTypes.widenedType(cls, iri)).isEqualTo(SqlTypeName.VARCHAR);
    }

    @Test
    @DisplayName("a source lacking the column entirely still contributes VARCHAR — absence must not sharpen the type")
    void widenedTypeMissingEntryContributesVarchar() {
        String iri = "urn:test:year";
        MappingCatalog.IntegratedClass cls = new MappingCatalog.IntegratedClass("urn:test:Class", List.of(
                sourceWithoutType("ds1"), sourceWithoutType("ds2")));
        assertThat(XsdTypes.widenedType(cls, iri)).isEqualTo(SqlTypeName.VARCHAR);
    }

    private static SqlTypeName[] allMappedTypes() {
        return new SqlTypeName[] {
                SqlTypeName.VARCHAR, SqlTypeName.BIGINT, SqlTypeName.DECIMAL, SqlTypeName.DOUBLE,
                SqlTypeName.BOOLEAN, SqlTypeName.DATE, SqlTypeName.TIMESTAMP};
    }

    private static MappingCatalog.SourceView sourceWithType(String datasetId, String iri, String xsdIri) {
        return new MappingCatalog.SourceView(datasetId, "SELECT 1", Map.of(iri, "col"),
                0L, List.of(), "", Map.of(iri, xsdIri));
    }

    private static MappingCatalog.SourceView sourceWithoutType(String datasetId) {
        return new MappingCatalog.SourceView(datasetId, "SELECT 1", Map.of());
    }
}

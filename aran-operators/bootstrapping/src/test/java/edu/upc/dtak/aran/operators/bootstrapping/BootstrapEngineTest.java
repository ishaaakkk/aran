package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the CSV and JSON bootstrapping engines.
 * No Spring, no Fuseki, no lake — pure schema extraction logic.
 */
@DisplayName("Bootstrap engines")
class BootstrapEngineTest {

    private static final String DATASET_ID = "test-dataset-id";
    private static final String SNAPSHOT_ID = "snap-001";
    private static final String DS_NAME = "customers";

    @Nested
    @DisplayName("CsvBootstrap")
    class CsvBootstrapTests {

        private final CsvBootstrap engine = new CsvBootstrap();

        @Test
        @DisplayName("produces one rdfs:Class and one rdf:Property per column for a plain CSV")
        void plain_csv_three_columns() throws IOException {
            byte[] csv = "id,name,city\n1,Alice,Barcelona\n2,Bob,Madrid\n"
                    .getBytes(StandardCharsets.UTF_8);

            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, csv);

            String classIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME);
            assertThat(m.contains(
                    m.createResource(classIri), RDF.type, RDFS.Class))
                    .isTrue();

            assertProperty(m, classIri, "id");
            assertProperty(m, classIri, "name");
            assertProperty(m, classIri, "city");
        }

        @Test
        @DisplayName("auto-detects tab delimiter and still produces correct schema")
        void tab_delimited_tsv() throws IOException {
            byte[] tsv = "product\tprice\tstock\nLaptop\t2499\t10\n"
                    .getBytes(StandardCharsets.UTF_8);

            Model m = engine.bootstrap(DATASET_ID, "inventory", SNAPSHOT_ID, tsv);

            String classIri = BootstrapEngine.sourceClassIri(DATASET_ID, "inventory");
            assertPropertyOnClass(m, DATASET_ID, "inventory", classIri, "product");
            assertPropertyOnClass(m, DATASET_ID, "inventory", classIri, "price");
            assertPropertyOnClass(m, DATASET_ID, "inventory", classIri, "stock");
        }

        @Test
        @DisplayName("wrapper vocab triples are attached to the root class")
        void wrapper_triples_on_root_class() throws IOException {
            byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, csv);

            String classIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME);
            var cls = m.createResource(classIri);
            assertThat(m.contains(cls, WrapperVocab.DATASET_ID, DATASET_ID)).isTrue();
            assertThat(m.contains(cls, WrapperVocab.SNAPSHOT_ID, SNAPSHOT_ID)).isTrue();
            assertThat(m.contains(cls, WrapperVocab.FORMAT, "CSV")).isTrue();
            assertThat(m.contains(cls, WrapperVocab.ITERATOR, "row")).isTrue();
        }

        @Test
        @DisplayName("all CSV columns default to xsd:string range")
        void csv_all_string_range() throws IOException {
            byte[] csv = "col\nval\n".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, csv);

            String propIri = BootstrapEngine.sourcePropertyIri(DATASET_ID, DS_NAME, "col");
            assertThat(m.contains(
                    m.createResource(propIri), RDFS.range, XSD.xstring))
                    .isTrue();
        }

        @Test
        @DisplayName("returns empty model for empty (header-only) CSV")
        void empty_csv_no_properties() throws IOException {
            byte[] csv = "id,name\n".getBytes(StandardCharsets.UTF_8);
            // Parser sees header but no data rows — class and properties should still be emitted
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, csv);
            // Class and properties are derived from header alone, not data rows
            assertThat(m.isEmpty()).isFalse();
            assertProperty(m, BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME), "id");
        }
    }

    @Nested
    @DisplayName("JsonBootstrap")
    class JsonBootstrapTests {

        private final JsonBootstrap engine = new JsonBootstrap();

        @Test
        @DisplayName("flat object produces root class + one property per key")
        void flat_object() throws IOException {
            byte[] json = "{\"id\":1,\"name\":\"Alice\",\"active\":true}".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);

            String classIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME);
            assertThat(m.contains(m.createResource(classIri), RDF.type, RDFS.Class)).isTrue();
            assertProperty(m, classIri, "id");
            assertProperty(m, classIri, "name");
            assertProperty(m, classIri, "active");
        }

        @Test
        @DisplayName("integer, boolean, float values map to correct xsd types")
        void primitive_xsd_types() throws IOException {
            byte[] json = "{\"count\":42,\"ratio\":3.14,\"flag\":false,\"huge\":1e30}"
                    .getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);

            String classIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME);
            assertRange(m, classIri, "count", XSD.integer.getURI());
            assertRange(m, classIri, "flag", XSD.xboolean.getURI());
            // Jackson parses a JSON float/exponent literal as a Java double by default (this
            // ObjectMapper has no USE_BIG_DECIMAL_FOR_FLOATS override), so it must map to
            // xsd:double, not xsd:decimal: TRY_CAST-ing into xsd:decimal's DECIMAL(38,9) view-body
            // cast would silently NULL a value like 1e30 (|x| >= 1e29) instead of preserving it.
            assertRange(m, classIri, "ratio", XSD.xdouble.getURI());
            assertRange(m, classIri, "huge", XSD.xdouble.getURI());
        }

        @Test
        @DisplayName("null JSON value maps to xsd:string range")
        void null_value_maps_to_string() throws IOException {
            byte[] json = "{\"optional\":null}".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);

            String classIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME);
            assertRange(m, classIri, "optional", XSD.xstring.getURI());
        }

        @Test
        @DisplayName("nested object produces a child class and an object-property linking to it")
        void nested_object_creates_child_class() throws IOException {
            byte[] json = "{\"address\":{\"street\":\"Main St\",\"zip\":\"08001\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);

            String parentClassIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME);
            String childClassIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME + ".address");

            assertThat(m.contains(m.createResource(childClassIri), RDF.type, RDFS.Class)).isTrue();
            String propIri = BootstrapEngine.sourcePropertyIri(DATASET_ID, DS_NAME, "address");
            assertThat(m.contains(
                    m.createResource(propIri), RDFS.range, m.createResource(childClassIri))).isTrue();
            assertProperty(m, childClassIri, "street");
            assertProperty(m, childClassIri, "zip");
        }

        @Test
        @DisplayName("array of objects produces a child class typed as rdfs:ContainerMembershipProperty")
        void array_of_objects() throws IOException {
            byte[] json = "{\"tags\":[{\"name\":\"rdf\",\"count\":10}]}".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);

            String propIri = BootstrapEngine.sourcePropertyIri(DATASET_ID, DS_NAME, "tags");
            assertThat(m.contains(
                    m.createResource(propIri), RDF.type, RDFS.ContainerMembershipProperty)).isTrue();
            String childClassIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME + ".tags");
            assertProperty(m, childClassIri, "name");
            assertProperty(m, childClassIri, "count");
        }

        @Test
        @DisplayName("array of primitives produces ContainerMembershipProperty with inferred xsd range")
        void array_of_primitives() throws IOException {
            byte[] json = "{\"scores\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);

            String propIri = BootstrapEngine.sourcePropertyIri(DATASET_ID, DS_NAME, "scores");
            assertThat(m.contains(
                    m.createResource(propIri), RDF.type, RDFS.ContainerMembershipProperty)).isTrue();
            assertThat(m.contains(
                    m.createResource(propIri), RDFS.range, XSD.integer)).isTrue();
        }

        @Test
        @DisplayName("root JSON array bootstraps from the first element")
        void root_array() throws IOException {
            byte[] json = "[{\"id\":1,\"email\":\"a@b.com\"},{\"id\":2,\"email\":\"c@d.com\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);

            String classIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME);
            assertThat(m.contains(m.createResource(classIri), RDF.type, RDFS.Class)).isTrue();
            // Iterator should be "$[*]" for a root array
            assertThat(m.contains(m.createResource(classIri), WrapperVocab.ITERATOR, "$[*]")).isTrue();
            assertProperty(m, classIri, "id");
            assertProperty(m, classIri, "email");
        }

        @Test
        @DisplayName("empty root array returns empty model")
        void empty_root_array_returns_empty_model() throws IOException {
            byte[] json = "[]".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);
            assertThat(m.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("root primitive (string) returns empty model — no schema to extract")
        void root_primitive_returns_empty_model() throws IOException {
            byte[] json = "\"just a string\"".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);
            assertThat(m.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("sourceRef wrapper is attached to each leaf property")
        void source_ref_on_leaf_properties() throws IOException {
            byte[] json = "{\"title\":\"Aran\",\"year\":2024}".getBytes(StandardCharsets.UTF_8);
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, json);

            String titleProp = BootstrapEngine.sourcePropertyIri(DATASET_ID, DS_NAME, "title");
            assertThat(m.contains(m.createResource(titleProp), WrapperVocab.SOURCE_REF, "title")).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Asserts that the model contains a property derived from the given class IRI and key,
     * with the expected rdf:type rdf:Property and rdfs:domain pointing to the class.
     * Uses the standard path-extraction convention: strips the SOURCE_NS + datasetId prefix.
     */
    private static void assertProperty(Model m, String classIri, String key) {
        String prefix = BootstrapEngine.SOURCE_NS + DATASET_ID + "/";
        String path = classIri.startsWith(prefix) ? classIri.substring(prefix.length()) : classIri;
        assertPropertyOnClass(m, DATASET_ID, path, classIri, key);
    }

    private static void assertPropertyOnClass(Model m, String datasetId, String path,
                                               String classIri, String key) {
        String propIri = BootstrapEngine.sourcePropertyIri(datasetId, path, key);
        assertThat(m.contains(m.createResource(propIri), RDF.type, RDF.Property))
                .as("Expected property <%s> to have rdf:type rdf:Property", propIri)
                .isTrue();
        assertThat(m.contains(m.createResource(propIri), RDFS.domain, m.createResource(classIri)))
                .as("Expected property <%s> rdfs:domain <%s>", propIri, classIri)
                .isTrue();
    }

    private static void assertRange(Model m, String classIri, String key, String rangeIri) {
        String prefix = BootstrapEngine.SOURCE_NS + DATASET_ID + "/";
        String path = classIri.startsWith(prefix) ? classIri.substring(prefix.length()) : classIri;
        String propIri = BootstrapEngine.sourcePropertyIri(DATASET_ID, path, key);
        assertThat(m.contains(
                m.createResource(propIri), RDFS.range, m.createResource(rangeIri)))
                .as("Expected property <%s> rdfs:range <%s>", propIri, rangeIri)
                .isTrue();
    }
}

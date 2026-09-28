package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class WrapperGeneratorTest {

    @Test
    @DisplayName("an array-of-primitives column is UNNEST'd, not projected as a raw array")
    public void arrayOfPrimitivesProducesUnnestInWrapper() throws Exception {
        String json = "[ { \"name\" : \"Admin\", \"age\" : 36, \"rights\" : [ \"admin\", \"editor\", \"contributor\" ] } ]";
        Model model = new JsonBootstrap().bootstrap("test-id", "test-name", "snap-id", json.getBytes());
        WrapperGenerator.attachPerClassWrappers(model);

        // An array of primitives adds no class of its own — the UNNEST lands on the owning
        // class's wrapper, so this source has exactly one.
        var it = model.listStatements(null, WrapperVocab.WRAPPER_SQL, (org.apache.jena.rdf.model.RDFNode) null);
        assertThat(it.hasNext()).isTrue();
        String wrapper = it.next().getObject().asLiteral().getString();
        assertThat(it.hasNext()).as("array-of-primitives must not mint a second class").isFalse();

        // Array-of-primitives must be UNNEST'd, not projected as a raw DuckDBArray.
        assertThat(wrapper).containsIgnoringCase("UNNEST");
        assertThat(wrapper).contains("rights");
        // Scalar columns are projected normally.
        assertThat(wrapper).contains("\"name\"");
        assertThat(wrapper).contains("\"age\"");
    }

    @Test
    @DisplayName("S4-2: two array-of-primitive fields on one object are refused at bootstrap, not multiplied")
    public void twoPrimitiveArraysAreRefused() {
        // 3 tags × 2 colors used to become 6 rows per record — every scalar and every COUNT/SUM
        // over the class multiplied, silently. Refused with both field names in the message.
        String json = "[ { \"name\" : \"Admin\", \"tags\" : [ \"a\", \"b\", \"c\" ], \"colors\" : [ \"red\", \"blue\" ] } ]";
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new JsonBootstrap().bootstrap("test-id", "test-name", "snap-id", json.getBytes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'tags'")
                .hasMessageContaining("'colors'")
                .hasMessageContaining("cross join");
    }

    @Test
    @DisplayName("S4-2: one array of primitives beside an array of objects is still fine")
    public void oneArrayOfPrimitivesBesideAnArrayOfObjectsIsAccepted() throws Exception {
        // Arrays of OBJECTS get a class of their own and never multiply the parent's rows.
        String json = "[ { \"name\" : \"Admin\", \"tags\" : [ \"a\", \"b\" ],"
                + " \"roles\" : [ { \"title\" : \"editor\" } ] } ]";
        Model model = new JsonBootstrap().bootstrap("test-id", "test-name", "snap-id", json.getBytes());
        assertThat(WrapperGenerator.attachPerClassWrappers(model)).isEqualTo(2);
    }

    @Test
    @DisplayName("S4-2: the builder refuses a class with two array refs even if a graph reached it some other way")
    public void builderRefusesTwoArrayRefs() {
        Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        org.apache.jena.rdf.model.Resource cls = m.createResource("http://ex/ds/Thing");
        m.add(cls, org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.RDFS.Class);
        WrapperVocab.attachClassWrapper(m, cls, "ds", "snap", "JSON", "$[*]", WrapperVocab.Shape.DOCUMENT);
        for (String ref : java.util.List.of("tags[*]", "colors[*]")) {
            org.apache.jena.rdf.model.Resource p = m.createResource("http://ex/ds/Thing/" + ref.replace("[*]", ""));
            m.add(p, org.apache.jena.vocabulary.RDFS.domain, cls);
            m.add(p, org.apache.jena.vocabulary.RDFS.range, org.apache.jena.vocabulary.XSD.xstring);
            m.add(p, WrapperVocab.SOURCE_REF, ref);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> WrapperSqlBuilder.buildForClass(m, cls.getURI(), WrapperVocab.Shape.DOCUMENT, "$[*]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S4-2");
        // …and attachPerClassWrappers swallows it as "no wrapper" rather than crashing bootstrap.
        assertThat(WrapperGenerator.attachPerClassWrappers(m)).isZero();
    }
}

package edu.upc.dtak.aran.core.domain.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading a comparable shape out of a bootstrapped source graph. */
class SourceGraphFingerprinterTest {

    private static final String NS = SourceGraphFingerprinter.NX_NS;
    private static final String DS = "11111111-1111-1111-1111-111111111111";
    private static final String CLS = "http://www.essi.upc.edu/DTIM/NextiaDI/source/" + DS + "/papers";

    /** Builds the triples {@code CsvBootstrap} would write, without depending on the operator module. */
    private static Model csvGraph(String classIri, String... columns) {
        Model m = ModelFactory.createDefaultModel();
        Resource cls = m.createResource(classIri);
        m.add(cls, RDF.type, RDFS.Class);
        m.add(cls, RDFS.label, "papers");
        m.add(cls, m.createProperty(NS + "format"), "CSV");
        m.add(cls, m.createProperty(NS + "iterator"), "row");
        m.add(cls, m.createProperty(NS + "datasetId"), DS);
        for (String column : columns) {
            Resource prop = m.createResource(classIri + "/" + column);
            m.add(prop, RDF.type, RDF.Property);
            m.add(prop, RDFS.label, column);
            m.add(prop, RDFS.domain, cls);
            m.add(prop, RDFS.range, XSD.xstring);
            m.add(prop, m.createProperty(NS + "sourceRef"), column);
        }
        return m;
    }

    @Test
    @DisplayName("extracts one class with its iterator, format and columns")
    void extractsClassAndColumns() {
        SchemaFingerprint fingerprint =
                SourceGraphFingerprinter.of(csvGraph(CLS, "title", "venue", "year"), DS);

        assertThat(fingerprint.classes()).hasSize(1);
        SchemaFingerprint.ClassShape cls = fingerprint.classes().get(0);
        assertThat(cls.iri()).isEqualTo(CLS);
        assertThat(cls.format()).isEqualTo("CSV");
        assertThat(cls.iterator()).isEqualTo("row");
        assertThat(cls.properties()).extracting(SchemaFingerprint.PropertyShape::sourceRef)
                .containsExactlyInAnyOrder("title", "venue", "year");
        assertThat(fingerprint.propertyCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("the hash ignores the order Jena happens to return statements in")
    void hashIsOrderIndependent() {
        // A Model is a set; two fingerprints of the same graph must not differ by iteration order,
        // or every check would report drift against itself.
        String a = SourceGraphFingerprinter.of(csvGraph(CLS, "title", "venue", "year"), DS).hash();
        String b = SourceGraphFingerprinter.of(csvGraph(CLS, "year", "title", "venue"), DS).hash();
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("the hash changes when a column does")
    void hashTracksContent() {
        String before = SourceGraphFingerprinter.of(csvGraph(CLS, "title", "venue"), DS).hash();
        String after = SourceGraphFingerprinter.of(csvGraph(CLS, "title", "conference"), DS).hash();
        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("a property with no sourceRef is not part of the source's shape")
    void ignoresPropertiesWithoutSourceRef() {
        // Integration adds properties that correspond to nothing in the source. They cannot drift,
        // and counting them would make every integration look like a schema change.
        Model m = csvGraph(CLS, "title");
        Resource derived = m.createResource(CLS + "/derived");
        m.add(derived, RDF.type, RDF.Property);
        m.add(derived, RDFS.domain, m.createResource(CLS));

        assertThat(SourceGraphFingerprinter.of(m, DS).propertyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a class with no iterator has no identity, so it is skipped")
    void ignoresClassesWithoutIterator() {
        Model m = ModelFactory.createDefaultModel();
        Resource cls = m.createResource(CLS);
        m.add(cls, RDF.type, RDFS.Class);
        assertThat(SourceGraphFingerprinter.of(m, DS).classes()).isEmpty();
    }

    @Test
    @DisplayName("keeps several JSON classes apart by their iterator")
    void severalClasses() {
        Model m = csvGraph(CLS, "title");
        Resource authors = m.createResource(CLS + ".authors");
        m.add(authors, RDF.type, RDFS.Class);
        m.add(authors, m.createProperty(NS + "format"), "JSON");
        m.add(authors, m.createProperty(NS + "iterator"), "$.authors[*]");
        Resource name = m.createResource(CLS + ".authors/name");
        m.add(name, RDF.type, RDF.Property);
        m.add(name, RDFS.domain, authors);
        m.add(name, m.createProperty(NS + "sourceRef"), "name");

        assertThat(SourceGraphFingerprinter.of(m, DS).classes())
                .extracting(SchemaFingerprint.ClassShape::iterator)
                .containsExactlyInAnyOrder("row", "$.authors[*]");
    }
}

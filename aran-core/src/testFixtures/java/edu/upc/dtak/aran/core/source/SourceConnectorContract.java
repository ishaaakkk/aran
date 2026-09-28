package edu.upc.dtak.aran.core.source;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract every {@link SourceConnector} must satisfy, whoever wrote it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A connector catalog is the thing that decides whether Aran can reach anyone's data, and it
 * only grows if connectors can be accepted without someone reading every line of each one. That
 * needs a definition of "correct" that is executable rather than described. Trino and Airbyte both
 * arrived at the same answer; the difference is that this is being written while there are three
 * connectors, when it costs a day, rather than at thirty, when it costs a quarter and half the
 * catalog fails it.
 *
 * <p><b>Extend this and implement {@link #connector()} and {@link #validSource()}.</b> Everything
 * below then runs against your connector for free. Nothing here is connector-specific: if an
 * assertion does not apply to yours, the likely reading is that the SPI's contract needs changing
 * for everyone rather than that your connector is a special case.
 *
 * <h2>What it deliberately does not check</h2>
 *
 * <p>Type fidelity — whether a source's DECIMAL(38,2) lands as the right XSD type — is a matter
 * for each connector's own tests, because only they know the source's type system. What is checked
 * here is the shape every downstream stage relies on: a class, its properties, and the wrapper
 * metadata the {@code MappingCatalog} reads.
 */
public abstract class SourceConnectorContract {

    /** The connector under test. */
    protected abstract SourceConnector connector();

    /** A descriptor this connector claims and can introspect. */
    protected abstract SourceDescriptor validSource();

    /**
     * A descriptor this connector must not claim.
     *
     * <p>Overridable because "obviously not mine" differs by connector; the default is a file with
     * a content type and extension nothing in Aran handles.
     */
    protected SourceDescriptor foreignSource() {
        return SourceDescriptor.ofFile("d", "d", "s", "application/x-nonsense", "x.nonsense",
                null, new byte[] {0});
    }

    // ── identity ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("declares a stable, non-blank, upper-case kind")
    void sourceKindIsUsable() {
        String kind = connector().sourceKind();
        assertThat(kind).isNotBlank();
        assertThat(kind)
                .describedAs("kind is compared against a caller's explicit format override and "
                        + "labels the operator's output, so case must not be a source of mismatch")
                .isEqualTo(kind.toUpperCase(Locale.ROOT));
    }

    @Test
    @DisplayName("sourceKind is a constant, not derived from the last descriptor seen")
    void sourceKindIsStable() {
        String before = connector().sourceKind();
        connector().supports(validSource());
        assertThat(connector().sourceKind()).isEqualTo(before);
    }

    // ── claiming ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("claims what it can introspect")
    void supportsItsOwnSource() {
        assertThat(connector().supports(validSource())).isTrue();
    }

    @Test
    @DisplayName("does not claim a source it cannot read")
    void doesNotClaimForeignSources() {
        // SourceConnectors enforces mutual exclusivity at startup, so a connector that over-claims
        // does not fail here — it fails the whole application, at boot, for everyone.
        assertThat(connector().supports(foreignSource())).isFalse();
    }

    @Test
    @DisplayName("supports() is a question, not a command — asking twice changes nothing")
    void supportsIsPure() {
        SourceDescriptor source = validSource();
        assertThat(connector().supports(source)).isEqualTo(connector().supports(source));
    }

    // ── introspection ────────────────────────────────────────────────────────

    @Test
    @DisplayName("introspect returns a graph")
    void introspectReturnsAGraph() throws Exception {
        SourceSchema schema = connector().introspect(validSource());
        assertThat(schema).isNotNull();
        assertThat(schema.rdfs()).isNotNull();
    }

    @Test
    @DisplayName("the graph declares exactly one source class, labelled")
    void declaresOneLabelledClass() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();
        List<Resource> classes = model.listResourcesWithProperty(RDF.type, RDFS.Class).toList();

        assertThat(classes)
                .describedAs("one dataset is one source class; the integration stage keys on it")
                .hasSize(1);
        assertThat(model.contains(classes.getFirst(), RDFS.label, (RDFNode) null))
                .describedAs("an unlabelled class shows up in the UI as a bare IRI")
                .isTrue();
    }

    @Test
    @DisplayName("the graph declares at least one property, each with a domain and a source ref")
    void declaresUsableProperties() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();
        List<Resource> properties = model.listResourcesWithProperty(RDF.type, RDF.Property).toList();

        assertThat(properties)
                .describedAs("a class with no properties cannot be queried or mapped")
                .isNotEmpty();

        for (Resource property : properties) {
            assertThat(model.contains(property, RDFS.domain, (RDFNode) null))
                    .describedAs("property %s has no rdfs:domain, so nothing ties it to its class",
                            property.getURI())
                    .isTrue();
            assertThat(model.contains(property, RDFS.range, (RDFNode) null))
                    .describedAs("property %s has no rdfs:range", property.getURI())
                    .isTrue();
            assertThat(model.contains(property, sourceRef(), (RDFNode) null))
                    .describedAs("property %s carries no nx:sourceRef, so the wrapper cannot "
                            + "name the column it comes from", property.getURI())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("every property's domain is the declared class, not some other resource")
    void propertiesBelongToTheDeclaredClass() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();
        Resource cls = model.listResourcesWithProperty(RDF.type, RDFS.Class).toList().getFirst();

        for (Statement domain : model.listStatements(null, RDFS.domain, (RDFNode) null).toList()) {
            assertThat(domain.getObject().asResource().getURI()).isEqualTo(cls.getURI());
        }
    }

    @Test
    @DisplayName("the class carries the wrapper metadata the mapping catalog reads")
    void classCarriesWrapperMetadata() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();
        Resource cls = model.listResourcesWithProperty(RDF.type, RDFS.Class).toList().getFirst();

        // These four are what MappingCatalogOperator's SPARQL selects on. A connector that omits
        // one produces a class that bootstraps cleanly and then cannot be queried — the worst
        // failure shape available, because it looks like success.
        for (String local : List.of("datasetId", "format", "provenance")) {
            assertThat(model.contains(cls, property(local), (RDFNode) null))
                    .describedAs("class carries no nx:%s", local)
                    .isTrue();
        }
    }

    /**
     * The record shape this connector's classes declare — {@code FLAT} unless overridden. Only a
     * JSON document connector is a {@code DOCUMENT}; every other kind is a relation, and the
     * default is the one that cannot invent struct paths (S4-1).
     */
    protected String expectedShape() {
        return "FLAT";
    }

    @Test
    @DisplayName("every class says its record shape explicitly, and it is the kind's shape")
    void shapeIsExplicit() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();
        List<Resource> classes = model.listResourcesWithProperty(RDF.type, RDFS.Class).toList();
        assertThat(classes).isNotEmpty();
        // S4-1: WrapperSqlBuilder used to INFER the shape from nx:format through a closed
        // allowlist, and the eighth kind (Iceberg) fell through to the JSONPath branch with a
        // wrapper that bound to nothing. The shape is now the connector's explicit statement,
        // and a connector that forgets it fails here rather than at query time.
        for (Resource cls : classes) {
            assertThat(model.contains(cls, property("shape"), (RDFNode) null))
                    .describedAs("class %s carries no nx:shape", cls)
                    .isTrue();
            assertThat(model.getProperty(cls, property("shape")).getString())
                    .describedAs("class %s declares the wrong shape for a %s source", cls,
                            connector().sourceKind())
                    .isEqualTo(expectedShape());
        }
    }

    @Test
    @DisplayName("the declared format matches the declared kind")
    void formatAgreesWithKind() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();
        Resource cls = model.listResourcesWithProperty(RDF.type, RDFS.Class).toList().getFirst();
        String format = model.getProperty(cls, property("format")).getString();

        // The SnapshotResolver branches on nx:format to choose a read expression. If it disagrees
        // with the connector's kind, one of the two is lying and the query engine picks the wrong
        // reader — at query time, far from here.
        assertThat(format.toUpperCase(Locale.ROOT)).isEqualTo(connector().sourceKind());
    }

    @Test
    @DisplayName("introspection is repeatable — the same source yields the same schema")
    void introspectionIsDeterministic() throws Exception {
        // Re-bootstrapping a dataset must not churn the graph. Drift detection compares
        // fingerprints, so a connector that emits properties in a different order every time
        // reports schema drift on a source that has not changed.
        Model first = connector().introspect(validSource()).rdfs();
        Model second = connector().introspect(validSource()).rdfs();
        assertThat(first.isIsomorphicWith(second)).isTrue();
    }

    @Test
    @DisplayName("a source it does not claim is refused rather than half-introspected")
    void refusesForeignSources() {
        // Not a hypothetical: the operator picks a connector by supports(), but an explicit format
        // override can route a descriptor to the wrong one. Failing loudly beats emitting a graph
        // that describes nothing real.
        assertThatThrownBy(() -> connector().introspect(foreignSource()))
                .describedAs("introspecting an unclaimed source must throw, not return a graph")
                .isInstanceOf(Exception.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The wrapper vocabulary lives in aran-operators, which aran-core must not depend on. */
    private static final String NX = "http://www.essi.upc.edu/DTIM/NextiaDI/";

    private static org.apache.jena.rdf.model.Property property(String local) {
        return org.apache.jena.rdf.model.ResourceFactory.createProperty(NX + local);
    }

    private static org.apache.jena.rdf.model.Property sourceRef() {
        return property("sourceRef");
    }
}

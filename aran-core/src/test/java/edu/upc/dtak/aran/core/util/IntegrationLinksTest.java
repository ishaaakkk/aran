package edu.upc.dtak.aran.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage for the subClassOf/subPropertyOf walk extracted from {@code
 * IntegrationModelService} (Phase 4B Task 3) so it has its own test surface independent of that
 * service's higher-level {@code discoverConstraints} tests, which exercise it only indirectly.
 * Phase 4B's {@code DeclaredEdgeResolver} (aran-operators/query-engine) is the new caller this
 * extraction exists for.
 */
class IntegrationLinksTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String NX_SUB_CLASS_OF = NS + "subClassOf";
    private static final String NX_SUB_PROPERTY_OF = NS + "subPropertyOf";
    private static final String INTEGRATED_RESOURCE = NS + "IntegratedResource";

    private static Resource markIntegrated(Model m, String iri) {
        Resource r = m.createResource(iri);
        r.addProperty(RDF.type, m.createResource(INTEGRATED_RESOURCE));
        return r;
    }

    @Test
    @DisplayName("resolveIntegratedClass follows rdfs:subClassOf to an IntegratedResource")
    void resolvesViaRdfsSubClassOf() {
        Model m = ModelFactory.createDefaultModel();
        Resource src = m.createResource("http://ex/source/Foo");
        Resource integrated = markIntegrated(m, "http://ex/integration/Foo");
        m.add(src, RDFS.subClassOf, integrated);

        assertThat(IntegrationLinks.resolveIntegratedClass(m, src.getURI()))
                .isEqualTo(integrated.getURI());
    }

    @Test
    @DisplayName("resolveIntegratedClass also follows the nx:subClassOf alternation")
    void resolvesViaNxSubClassOf() {
        Model m = ModelFactory.createDefaultModel();
        Resource src = m.createResource("http://ex/source/Foo");
        Resource integrated = markIntegrated(m, "http://ex/integration/Foo");
        m.add(src, m.createProperty(NX_SUB_CLASS_OF), integrated);

        assertThat(IntegrationLinks.resolveIntegratedClass(m, src.getURI()))
                .isEqualTo(integrated.getURI());
    }

    @Test
    @DisplayName("a plain rdfs:subClassOf to a non-IntegratedResource is not mistaken for the integration link")
    void ignoresSubClassOfToOrdinaryClass() {
        Model m = ModelFactory.createDefaultModel();
        Resource src = m.createResource("http://ex/source/Foo");
        Resource notIntegrated = m.createResource("http://ex/source/SomeParent"); // no IntegratedResource type
        m.add(src, RDFS.subClassOf, notIntegrated);

        assertThat(IntegrationLinks.resolveIntegratedClass(m, src.getURI())).isNull();
    }

    @Test
    @DisplayName("no subClassOf triple at all resolves to null, not a guess")
    void nullWhenNoLinkExists() {
        Model m = ModelFactory.createDefaultModel();
        Resource src = m.createResource("http://ex/source/Lonely");

        assertThat(IntegrationLinks.resolveIntegratedClass(m, src.getURI())).isNull();
    }

    @Test
    @DisplayName("resolveTargetProperty follows subPropertyOf to the aligned IntegratedResource property")
    void resolvesAlignedProperty() {
        Model m = ModelFactory.createDefaultModel();
        Resource src = m.createResource("http://ex/source/name");
        Resource integrated = markIntegrated(m, "http://ex/integration/label");
        m.add(src, RDFS.subPropertyOf, integrated);

        assertThat(IntegrationLinks.resolveTargetProperty(m, src.getURI()))
                .isEqualTo(integrated.getURI());
    }

    @Test
    @DisplayName("resolveTargetProperty also follows the nx:subPropertyOf alternation")
    void resolvesAlignedPropertyViaNx() {
        Model m = ModelFactory.createDefaultModel();
        Resource src = m.createResource("http://ex/source/name");
        Resource integrated = markIntegrated(m, "http://ex/integration/label");
        m.add(src, m.createProperty(NX_SUB_PROPERTY_OF), integrated);

        assertThat(IntegrationLinks.resolveTargetProperty(m, src.getURI()))
                .isEqualTo(integrated.getURI());
    }

    @Test
    @DisplayName("an unaligned property is its own target (COALESCE fallback)")
    void unalignedPropertyIsItsOwnTarget() {
        Model m = ModelFactory.createDefaultModel();
        Resource src = m.createResource("http://ex/source/unaligned");

        assertThat(IntegrationLinks.resolveTargetProperty(m, src.getURI()))
                .isEqualTo(src.getURI());
    }

    @Test
    @DisplayName("subPropertyOf to a non-IntegratedResource property falls back to the source property itself")
    void ignoresSubPropertyOfToOrdinaryProperty() {
        Model m = ModelFactory.createDefaultModel();
        Resource src = m.createResource("http://ex/source/name");
        Resource notIntegrated = m.createResource("http://ex/source/SomeOtherProp");
        m.add(src, RDFS.subPropertyOf, notIntegrated);

        assertThat(IntegrationLinks.resolveTargetProperty(m, src.getURI())).isEqualTo(src.getURI());
    }
}

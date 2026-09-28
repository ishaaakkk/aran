package edu.upc.dtak.aran.operators.schemaintegration;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JoinPropertyColumnsTest {

    @Test
    void testJoinPropertyHasJoinColumns() {
        SchemaIntegrator integrator = new SchemaIntegrator();

        Model mA = ModelFactory.createDefaultModel();
        Resource classA = mA.createResource(IntegrationVocab.INTEGRATION_NS + "A");
        mA.add(classA, RDF.type, RDFS.Class);
        mA.add(classA, RDF.type, mA.createResource(IntegrationVocab.INTEGRATED_RESOURCE));

        Resource propA = mA.createResource("urn:prop:A");
        mA.add(propA, RDF.type, RDF.Property);
        mA.add(propA, RDFS.domain, classA);

        Model mB = ModelFactory.createDefaultModel();
        Resource classB = mB.createResource(IntegrationVocab.INTEGRATION_NS + "B");
        mB.add(classB, RDF.type, RDFS.Class);
        mB.add(classB, RDF.type, mB.createResource(IntegrationVocab.INTEGRATED_RESOURCE));

        Resource propB = mB.createResource("urn:prop:B");
        mB.add(propB, RDF.type, RDF.Property);
        mB.add(propB, RDFS.domain, classB);

        List<Alignment> alignments = List.of(new Alignment("urn:prop:A", "urn:prop:B", "JoinAB", Alignment.Type.JOIN));
        SchemaIntegrator.IntegrationResult res = integrator.integrate(mA, mB, alignments);
        Model result = res.graph();

        // Two-node form (Flores et al. Fig. 14): P_l is the datatype-property parent
        // (generateURI(joinLabel)); P_s is the join object property
        // (generateURI(joinLabel + "_join") when no relationship name is supplied).
        Resource pL = result.createResource(IntegrationVocab.INTEGRATION_NS + "JoinAB");
        Resource pS = result.createResource(IntegrationVocab.INTEGRATION_NS + "JoinAB_join");

        // P_l — integrated datatype property, NOT a JoinProperty, NO domain.
        assertThat(result.contains(pL, RDF.type, result.createResource(IntegrationVocab.INTEGRATED_RESOURCE))).isTrue();
        assertThat(result.contains(pL, RDF.type, RDF.Property)).isTrue();
        assertThat(result.contains(pL, RDF.type, result.createResource(IntegrationVocab.JOIN_PROPERTY))).isFalse();
        assertThat(result.listStatements(pL, RDFS.domain, (Resource) null).hasNext()).isFalse();
        assertThat(result.contains(propA, RDFS.subPropertyOf, pL)).isTrue();
        assertThat(result.contains(propB, RDFS.subPropertyOf, pL)).isTrue();
        assertThat(result.contains(pL, result.createProperty(IntegrationVocab.JOIN_PROPERTY), pS)).isTrue();

        // P_s — the join object property, NOT an IntegratedResource.
        assertThat(result.contains(pS, RDF.type, result.createResource(IntegrationVocab.JOIN_PROPERTY))).isTrue();
        assertThat(result.contains(pS, RDF.type, RDF.Property)).isTrue();
        assertThat(result.contains(pS, RDF.type, result.createResource(IntegrationVocab.INTEGRATED_RESOURCE))).isFalse();

        assertThat(result.contains(pS, RDFS.domain, classA)).isTrue();
        assertThat(result.contains(pS, RDFS.range, classB)).isTrue();

        // Execution metadata (joinOnLeft/joinOnRight) lives on P_s.
        assertThat(result.contains(pS, result.createProperty(IntegrationVocab.JOIN_ON_LEFT), propA)).isTrue();
        assertThat(result.contains(pS, result.createProperty(IntegrationVocab.JOIN_ON_RIGHT), propB)).isTrue();
    }
}

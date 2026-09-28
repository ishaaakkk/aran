package edu.upc.dtak.aran.operators.schemaintegration;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DepthOneInvariantTest {

    @Test
    void testDepthOneMaintainedDuringIncrementalIntegration() {
        SchemaIntegrator integrator = new SchemaIntegrator();

        // Graph A: CMOA (creator1)
        Model cmoa = ModelFactory.createDefaultModel();
        Resource creator1 = cmoa.createResource("urn:cmoa:Creator");
        cmoa.add(creator1, RDF.type, RDFS.Class);
        cmoa.add(creator1, cmoa.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"), "SELECT * FROM CMOA");

        // Graph B: Cooperhewitt (creator2)
        Model cooper = ModelFactory.createDefaultModel();
        Resource creator2 = cooper.createResource("urn:cooper:Creator");
        cooper.add(creator2, RDF.type, RDFS.Class);
        cooper.add(creator2, cooper.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"), "SELECT * FROM COOPER");

        // Graph C: Artists (artist1)
        Model artists = ModelFactory.createDefaultModel();
        Resource artist1 = artists.createResource("urn:artists:Artist");
        artists.add(artist1, RDF.type, RDFS.Class);
        artists.add(artist1, artists.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"), "SELECT * FROM ARTISTS");

        // Step 1: integrate CMOA and Cooperhewitt (I1: creates new IntegratedClass Creator)
        List<Alignment> alignments1 = List.of(new Alignment("urn:cmoa:Creator", "urn:cooper:Creator", "Creator", Alignment.Type.CLASS));
        SchemaIntegrator.IntegrationResult res1 = integrator.integrate(cmoa, cooper, alignments1);
        Model integrated1 = res1.graph();

        // Both should be direct subclasses of the new IntegratedResource Creator
        Resource integratedCreator = integrated1.createResource(IntegrationVocab.INTEGRATION_NS + "Creator");
        assertThat(integrated1.contains(creator1, RDFS.subClassOf, integratedCreator)).isTrue();
        assertThat(integrated1.contains(creator2, RDFS.subClassOf, integratedCreator)).isTrue();
        
        // Assert no Depth-1 violations (the integrator throws IllegalStateException if there are)

        // Step 2: integrate Artists into the result of Step 1, aligning Artists' "Artist"
        // directly against the minted IntegratedResource Creator IRI — a source class vs.
        // an IR, so this exercises I3 rather than I1/I4.
        List<Alignment> alignments2 = List.of(new Alignment("urn:artists:Artist", IntegrationVocab.INTEGRATION_NS + "Creator", "Creator", Alignment.Type.CLASS));
        SchemaIntegrator.IntegrationResult res2 = integrator.integrate(integrated1, artists, alignments2);
        Model integrated2 = res2.graph();

        // In I3, Artists should become a direct subclass of the existing IntegratedResource Creator.
        assertThat(integrated2.contains(artist1, RDFS.subClassOf, integratedCreator)).isTrue();
        
        // Let's test I4. Create another integrated class and merge them.
        Model graphD = ModelFactory.createDefaultModel();
        Resource author1 = graphD.createResource("urn:books:Author");
        graphD.add(author1, RDF.type, RDFS.Class);
        graphD.add(author1, graphD.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"), "SELECT * FROM BOOKS");

        Model graphE = ModelFactory.createDefaultModel();
        Resource writer1 = graphE.createResource("urn:magazines:Writer");
        graphE.add(writer1, RDF.type, RDFS.Class);
        graphE.add(writer1, graphE.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"), "SELECT * FROM MAGS");

        List<Alignment> alignments3 = List.of(new Alignment("urn:books:Author", "urn:magazines:Writer", "Author", Alignment.Type.CLASS));
        SchemaIntegrator.IntegrationResult res3 = integrator.integrate(graphD, graphE, alignments3);
        Model integrated3 = res3.graph();
        Resource integratedAuthor = integrated3.createResource(IntegrationVocab.INTEGRATION_NS + "Author");

        // Now merge the two integrated graphs (I4)
        List<Alignment> alignments4 = List.of(new Alignment(IntegrationVocab.INTEGRATION_NS + "Creator", IntegrationVocab.INTEGRATION_NS + "Author", "Person", Alignment.Type.CLASS));
        SchemaIntegrator.IntegrationResult res4 = integrator.integrate(integrated2, integrated3, alignments4);
        Model finalModel = res4.graph();

        Resource integratedPerson = finalModel.createResource(IntegrationVocab.INTEGRATION_NS + "Person");

        // The old IntegratedClasses (Creator and Author) should be removed completely!
        assertThat(finalModel.containsResource(integratedCreator)).isFalse();
        assertThat(finalModel.containsResource(integratedAuthor)).isFalse();

        // ALL 5 source classes MUST be direct subclasses of Person (Depth-1)
        assertThat(finalModel.contains(creator1, RDFS.subClassOf, integratedPerson)).isTrue();
        assertThat(finalModel.contains(creator2, RDFS.subClassOf, integratedPerson)).isTrue();
        assertThat(finalModel.contains(artist1, RDFS.subClassOf, integratedPerson)).isTrue();
        assertThat(finalModel.contains(author1, RDFS.subClassOf, integratedPerson)).isTrue();
        assertThat(finalModel.contains(writer1, RDFS.subClassOf, integratedPerson)).isTrue();
    }
}

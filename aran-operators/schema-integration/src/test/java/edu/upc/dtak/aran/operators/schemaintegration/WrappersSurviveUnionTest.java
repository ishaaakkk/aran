package edu.upc.dtak.aran.operators.schemaintegration;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WrappersSurviveUnionTest {

    @Test
    void testWrappersSurviveIntegration() {
        SchemaIntegrator integrator = new SchemaIntegrator();

        Model mA = ModelFactory.createDefaultModel();
        Resource classA = mA.createResource("urn:A");
        mA.add(classA, RDF.type, RDFS.Class);
        Property wrapperSqlProp = mA.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql");
        mA.add(classA, wrapperSqlProp, "SELECT * FROM A");

        Model mB = ModelFactory.createDefaultModel();
        Resource classB = mB.createResource("urn:B");
        mB.add(classB, RDF.type, RDFS.Class);
        Property wrapperSqlPropB = mB.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql");
        mB.add(classB, wrapperSqlPropB, "SELECT * FROM B");

        List<Alignment> alignments = List.of(new Alignment("urn:A", "urn:B", "IntegratedAB", Alignment.Type.CLASS));
        SchemaIntegrator.IntegrationResult res = integrator.integrate(mA, mB, alignments);
        Model result = res.graph();

        assertThat(result.contains(classA, wrapperSqlProp, "SELECT * FROM A")).isTrue();
        assertThat(result.contains(classB, wrapperSqlProp, "SELECT * FROM B")).isTrue();
    }
}

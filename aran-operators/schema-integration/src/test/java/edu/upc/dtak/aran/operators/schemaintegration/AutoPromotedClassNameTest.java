package edu.upc.dtak.aran.operators.schemaintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A join auto-promotes each raw side to {@code Int_<source class>} — the one integrated name no
 * person chose, so it has to be one OQL can write bare. An Iceberg dataset's class is named by its
 * {@code namespace.table} identifier, and a nested JSON class by its dotted path, so both used to
 * mint names like {@code Int_db.venue}: reachable only as a quoted identifier, refused by a cube.
 */
@DisplayName("An auto-promoted integrated class is named in OQL's bare alphabet")
class AutoPromotedClassNameTest {

    private static final String A = "http://www.essi.upc.edu/DTIM/NextiaDI/source/ds-a/";
    private static final String B = "http://www.essi.upc.edu/DTIM/NextiaDI/source/ds-b/";
    private static final String IR_NS = IntegrationVocab.INTEGRATION_NS;

    private final SchemaIntegrator integrator = new SchemaIntegrator();

    private static Model source(String classIri, String... columns) {
        Model m = ModelFactory.createDefaultModel();
        Resource cls = m.createResource(classIri);
        m.add(cls, RDF.type, RDFS.Class);
        for (String column : columns) {
            Resource p = m.createResource(classIri + "/" + column);
            m.add(p, RDF.type, RDF.Property);
            m.add(p, RDFS.domain, cls);
        }
        return m;
    }

    private static boolean isIntegrated(Model m, String iri) {
        return m.contains(m.createResource(iri), RDF.type,
                m.createResource(IntegrationVocab.INTEGRATED_RESOURCE));
    }

    @Test
    @DisplayName("a dotted Iceberg identifier or nested path mints Int_ with '_' where the '.' was")
    void dottedSourceClassesMintBareNames() {
        Model venues = source(A + "db.venue", "venue_id");
        Model orders = source(B + "sales.eu.orders", "venue_ref");

        Model m = integrator.integrate(venues, orders, List.of(new Alignment(
                B + "sales.eu.orders/venue_ref", A + "db.venue/venue_id",
                "venueKey|heldAt|forward", Alignment.Type.JOIN))).graph();

        assertThat(isIntegrated(m, IR_NS + "Int_db_venue")).isTrue();
        assertThat(isIntegrated(m, IR_NS + "Int_sales_eu_orders")).isTrue();
        assertThat(isIntegrated(m, IR_NS + "Int_db.venue")).isFalse();
    }

    @Test
    @DisplayName("an identifier-shaped source class keeps its name exactly — Int_researchers stays Int_researchers")
    void bareNamesAreUnchanged() {
        Model papers = source(A + "acm", "author_id");
        Model people = source(B + "researchers", "researcher_id");

        Model m = integrator.integrate(papers, people, List.of(new Alignment(
                A + "acm/author_id", B + "researchers/researcher_id",
                "authorId|hasAuthor|forward", Alignment.Type.JOIN))).graph();

        assertThat(isIntegrated(m, IR_NS + "Int_acm")).isTrue();
        assertThat(isIntegrated(m, IR_NS + "Int_researchers")).isTrue();
    }

    @Test
    @DisplayName("two source classes that differ only by '.' vs '_' collide and are refused, not merged (D2)")
    void underscoreAndDotSpellingsCollideHonestly() {
        Model left = source(A + "db.venue", "id");
        Model right = source(B + "db_venue", "id");
        Model third = source(A + "orders", "venue_ref");
        left.add(third);

        assertThatThrownBy(() -> integrator.integrate(left, right, List.of(
                new Alignment(A + "orders/venue_ref", A + "db.venue/id",
                        "venueKey|heldAt|forward", Alignment.Type.JOIN),
                new Alignment(A + "orders/venue_ref", B + "db_venue/id",
                        "otherKey|alsoAt|forward", Alignment.Type.JOIN))))
                .isInstanceOf(MappingValidationException.class)
                .hasMessageContaining(IR_NS + "Int_db_venue");
    }
}

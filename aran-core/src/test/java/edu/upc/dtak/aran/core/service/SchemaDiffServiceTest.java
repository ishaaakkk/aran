package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.service.SchemaDiffService.ChangedClass;
import edu.upc.dtak.aran.core.service.SchemaDiffService.SchemaDiff;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SchemaDiffService")
class SchemaDiffServiceTest {

    private static final String NS = "http://ex/";
    private static final Property WRAPPER_SQL =
            ModelFactory.createDefaultModel().createProperty(
                    "http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql");

    // No graph store needed for the pure diff(Model, Model) path.
    private final SchemaDiffService service = new SchemaDiffService(new FakeStore());

    @Test
    @DisplayName("reports an added class, a removed property and a changed wrapper")
    void diff_reports_added_class_removed_property_changed_wrapper() {
        Model a = ModelFactory.createDefaultModel();
        // Class Paper with two properties title, year; wrapper "SELECT 1".
        cls(a, "Paper", "SELECT 1");
        prop(a, "title", "Paper");
        prop(a, "year", "Paper");

        Model b = ModelFactory.createDefaultModel();
        // Paper keeps title, loses year, and its wrapper changed to "SELECT 2".
        cls(b, "Paper", "SELECT 2");
        prop(b, "title", "Paper");
        // New class Author.
        cls(b, "Author", "SELECT 3");
        prop(b, "name", "Author");

        SchemaDiff diff = service.diff(a, b);

        assertThat(diff.addedClasses()).containsExactly(NS + "Author");
        assertThat(diff.removedClasses()).isEmpty();
        assertThat(diff.changedClasses()).hasSize(1);

        ChangedClass changed = diff.changedClasses().get(0);
        assertThat(changed.classIri()).isEqualTo(NS + "Paper");
        assertThat(changed.addedProperties()).isEmpty();
        assertThat(changed.removedProperties()).containsExactly(NS + "year");
        assertThat(changed.wrapperChanged()).isTrue();
        assertThat(diff.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("identical schemas diff to empty")
    void diff_of_identical_schemas_is_empty() {
        Model a = ModelFactory.createDefaultModel();
        cls(a, "Paper", "SELECT 1");
        prop(a, "title", "Paper");

        Model b = ModelFactory.createDefaultModel();
        cls(b, "Paper", "SELECT 1");
        prop(b, "title", "Paper");

        assertThat(service.diff(a, b).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("removing a whole class is reported under removedClasses only")
    void diff_reports_removed_class() {
        Model a = ModelFactory.createDefaultModel();
        cls(a, "Paper", "SELECT 1");
        cls(a, "Author", "SELECT 2");

        Model b = ModelFactory.createDefaultModel();
        cls(b, "Paper", "SELECT 1");

        SchemaDiff diff = service.diff(a, b);
        assertThat(diff.removedClasses()).containsExactly(NS + "Author");
        assertThat(diff.addedClasses()).isEmpty();
        assertThat(diff.changedClasses()).isEmpty();
    }

    private static void cls(Model m, String local, String wrapperSql) {
        Resource c = m.createResource(NS + local);
        m.add(c, RDF.type, RDFS.Class);
        m.add(c, WRAPPER_SQL, wrapperSql);
    }

    private static void prop(Model m, String local, String domainLocal) {
        Resource p = m.createResource(NS + local);
        m.add(p, RDF.type, RDF.Property);
        m.add(p, RDFS.domain, m.createResource(NS + domainLocal));
    }

    /** Unused by diff(Model, Model); present only to satisfy the constructor. */
    private static final class FakeStore extends edu.upc.dtak.aran.core.fake.FakeGraphStore {
    }
}

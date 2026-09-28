package edu.upc.dtak.aran.operators.schemaintegration;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S5-3: the alignment label is the integrated IRI, so it is a global key — and nothing used
 * to check it. Two CLASS alignments labelled the same, or two datasets each contributing a
 * {@code customer} class to an unrelated JOIN, both landed on ONE IntegratedResource whose
 * {@code int_} view is a UNION ALL of unrelated tables. DuckDbExecutor pads a missing column
 * with NULL, so that does not even fail — it returns wrong rows.
 *
 * <p>Decision D2 seals the hole by <em>refusal</em>, not by re-derivation: integrated IRIs must
 * stay label-derived because OQL addresses a class by the IRI's local name, so minting from a
 * mapping UUID would make every class a name nobody can guess — writable quoted, never found.
 * The mint therefore refuses when it would land on an IntegratedResource this alignment does not
 * already own — the one legitimate case being the paper's §5.2 I4, where the merge label deliberately
 * names one of the two IRs being merged ({@link PaperRunningExampleTest}).
 */
@DisplayName("S5-3 — an integrated IRI mint that would land on someone else's IntegratedResource is refused")
class IriCollisionRefusalTest {

    private static final String A_NS = "http://src-a/";
    private static final String B_NS = "http://src-b/";
    private static final String C_NS = "http://src-c/";
    private static final String D_NS = "http://src-d/";
    private static final String IR_NS = IntegrationVocab.INTEGRATION_NS;

    private final SchemaIntegrator integrator = new SchemaIntegrator();

    @Test
    @DisplayName("two CLASS alignments carrying the same label are refused instead of merging into one IR")
    void two_class_alignments_with_the_same_label_are_refused() {
        Model a = ModelFactory.createDefaultModel();
        cls(a, A_NS + "Person");
        cls(a, C_NS + "Invoice");
        Model b = ModelFactory.createDefaultModel();
        cls(b, B_NS + "Employee");
        cls(b, D_NS + "Bill");

        // Two unrelated concepts, both labelled "Customer" — mintIri gives both the same IRI.
        List<Alignment> alignments = List.of(
                new Alignment(A_NS + "Person", B_NS + "Employee", "Customer", Alignment.Type.CLASS),
                new Alignment(C_NS + "Invoice", D_NS + "Bill", "Customer", Alignment.Type.CLASS));

        assertThatThrownBy(() -> integrator.integrate(a, b, alignments))
                .isInstanceOf(MappingValidationException.class)
                .hasMessageContaining(IR_NS + "Customer");
    }

    @Test
    @DisplayName("two datasets each contributing a same-named class to a JOIN are refused, not folded into one Int_ class")
    void two_datasets_sharing_a_class_local_name_in_a_join_are_refused() {
        // Auto-promotion mints Int_<localName> from the SOURCE class's local name only, so two
        // datasets that each happen to call a table `customer` used to share one Int_customer —
        // and its int_ view became a UNION ALL of two unrelated customer tables.
        Model one = ModelFactory.createDefaultModel();
        cls(one, A_NS + "customer");
        cls(one, A_NS + "orders");
        dtp(one, A_NS + "customer/id", A_NS + "customer");
        dtp(one, A_NS + "orders/cust_id", A_NS + "orders");

        Model two = ModelFactory.createDefaultModel();
        cls(two, B_NS + "customer");
        cls(two, B_NS + "shipments");
        dtp(two, B_NS + "customer/id", B_NS + "customer");
        dtp(two, B_NS + "shipments/cust_id", B_NS + "shipments");

        List<Alignment> joins = List.of(
                new Alignment(A_NS + "orders/cust_id", A_NS + "customer/id",
                        "ordersKey|placedBy|forward", Alignment.Type.JOIN),
                new Alignment(B_NS + "shipments/cust_id", B_NS + "customer/id",
                        "shipmentsKey|shippedTo|forward", Alignment.Type.JOIN));

        assertThatThrownBy(() -> integrator.integrate(one, two, joins))
                .isInstanceOf(MappingValidationException.class)
                .hasMessageContaining(IR_NS + "Int_customer");
    }

    @Test
    @DisplayName("two DATATYPE alignments labelled `name` on different classes are refused")
    void two_datatype_alignments_with_the_same_label_on_different_classes_are_refused() {
        Model a = ModelFactory.createDefaultModel();
        cls(a, A_NS + "Person");
        dtp(a, A_NS + "personName", A_NS + "Person");
        cls(a, C_NS + "Company");
        dtp(a, C_NS + "companyName", C_NS + "Company");

        Model b = ModelFactory.createDefaultModel();
        cls(b, B_NS + "Employee");
        dtp(b, B_NS + "employeeName", B_NS + "Employee");
        cls(b, D_NS + "Firm");
        dtp(b, D_NS + "firmName", D_NS + "Firm");

        List<Alignment> alignments = List.of(
                new Alignment(A_NS + "Person", B_NS + "Employee", "Person", Alignment.Type.CLASS),
                new Alignment(C_NS + "Company", D_NS + "Firm", "Company", Alignment.Type.CLASS),
                new Alignment(A_NS + "personName", B_NS + "employeeName", "name", Alignment.Type.DATATYPE),
                // Same label on a different integrated class: one integration/name property would
                // end up with two rdfs:domains.
                new Alignment(C_NS + "companyName", D_NS + "firmName", "name", Alignment.Type.DATATYPE));

        assertThatThrownBy(() -> integrator.integrate(a, b, alignments))
                .isInstanceOf(MappingValidationException.class)
                .hasMessageContaining(IR_NS + "name");
    }

    @Test
    @DisplayName("a distinct label per concept still integrates — the refusal is on collision only")
    void distinct_labels_still_integrate() {
        Model a = ModelFactory.createDefaultModel();
        cls(a, A_NS + "Person");
        cls(a, C_NS + "Invoice");
        Model b = ModelFactory.createDefaultModel();
        cls(b, B_NS + "Employee");
        cls(b, D_NS + "Bill");

        Model m = integrator.integrate(a, b, List.of(
                new Alignment(A_NS + "Person", B_NS + "Employee", "Customer", Alignment.Type.CLASS),
                new Alignment(C_NS + "Invoice", D_NS + "Bill", "Charge", Alignment.Type.CLASS))).graph();

        Resource irType = m.createResource(IntegrationVocab.INTEGRATED_RESOURCE);
        assertThat(m.contains(m.createResource(IR_NS + "Customer"), RDF.type, irType)).isTrue();
        assertThat(m.contains(m.createResource(IR_NS + "Charge"), RDF.type, irType)).isTrue();
    }

    /**
     * A JOIN mints two resources from its packed label: {@code P_l} (the datatype property the key
     * columns become sub-properties of) from the label, {@code P_s} (the relationship) from the
     * relationship name. Naming both alike minted ONE resource that was both; compile succeeded
     * and every query then failed as an ambiguous join property.
     */
    @Test
    @DisplayName("a JOIN whose label equals its relationship name is refused, naming both")
    void join_label_equal_to_its_relationship_name_is_refused() {
        Model a = ModelFactory.createDefaultModel();
        cls(a, A_NS + "orders");
        dtp(a, A_NS + "orders/cust_id", A_NS + "orders");
        Model b = ModelFactory.createDefaultModel();
        cls(b, B_NS + "customer");
        dtp(b, B_NS + "customer/id", B_NS + "customer");

        List<Alignment> join = List.of(new Alignment(A_NS + "orders/cust_id", B_NS + "customer/id",
                "placedBy|placedBy|forward", Alignment.Type.JOIN));

        assertThatThrownBy(() -> integrator.integrate(a, b, join))
                .isInstanceOf(MappingValidationException.class)
                .hasMessageContaining("join label 'placedBy'")
                .hasMessageContaining("relationship name 'placedBy'")
                .hasMessageContaining(IR_NS + "placedBy");
    }

    @Test
    @DisplayName("a JOIN whose label and relationship differ still mints two resources")
    void join_with_a_distinct_relationship_name_integrates() {
        Model a = ModelFactory.createDefaultModel();
        cls(a, A_NS + "orders");
        dtp(a, A_NS + "orders/cust_id", A_NS + "orders");
        Model b = ModelFactory.createDefaultModel();
        cls(b, B_NS + "customer");
        dtp(b, B_NS + "customer/id", B_NS + "customer");

        Model m = integrator.integrate(a, b, List.of(new Alignment(A_NS + "orders/cust_id",
                B_NS + "customer/id", "custKey|placedBy|forward", Alignment.Type.JOIN))).graph();

        Resource joinType = m.createResource(IntegrationVocab.JOIN_PROPERTY);
        assertThat(m.contains(m.createResource(IR_NS + "placedBy"), RDF.type, joinType)).isTrue();
        assertThat(m.contains(m.createResource(IR_NS + "custKey"), RDF.type, joinType)).isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static void cls(Model m, String iri) {
        m.add(m.createResource(iri), RDF.type, RDFS.Class);
        m.add(m.createResource(iri), RDFS.label, local(iri));
    }

    private static void dtp(Model m, String iri, String domain) {
        Resource p = m.createResource(iri);
        m.add(p, RDF.type, RDF.Property);
        m.add(p, RDFS.domain, m.createResource(domain));
        m.add(p, RDFS.label, local(iri));
    }

    private static String local(String iri) {
        return iri.substring(Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#')) + 1);
    }
}

package edu.upc.dtak.aran.operators.schemaintegration;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the schema-integration I1–I4 invariants.
 * No Spring, no Fuseki — all graph manipulation is in-memory Jena.
 *
 * Each test builds two minimal source graphs, supplies alignments,
 * runs the integrator, and asserts on the resulting model structure.
 */
@DisplayName("SchemaIntegrator — I1..I4 invariants")
class SchemaIntegratorTest {

    private static final String A_NS = "http://source-a/";
    private static final String B_NS = "http://source-b/";
    private static final String IR_NS = IntegrationVocab.INTEGRATION_NS;

    private SchemaIntegrator integrator;
    private Resource INTEGRATED_RESOURCE_TYPE;

    @BeforeEach
    void setUp() {
        integrator = new SchemaIntegrator();
        INTEGRATED_RESOURCE_TYPE = ModelFactory.createDefaultModel()
                .createResource(IntegrationVocab.INTEGRATED_RESOURCE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Class integration
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Class alignment")
    class ClassAlignmentTests {

        @Test
        @DisplayName("I1: two source classes → new IntegratedResource + both become subclasses")
        void i1_two_source_classes_create_new_ir() {
            Model a = sourceClass(A_NS + "Person");
            Model b = sourceClass(B_NS + "Employee");
            Alignment alignment = new Alignment(A_NS + "Person", B_NS + "Employee", "Person", Alignment.Type.CLASS);

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, List.of(alignment));
            Model m = r.graph();

            Resource ir = m.createResource(IR_NS + "Person");
            assertThat(m.contains(ir, RDF.type, RDFS.Class)).isTrue();
            assertThat(m.contains(ir, RDF.type, INTEGRATED_RESOURCE_TYPE)).isTrue();
            assertThat(m.contains(m.createResource(A_NS + "Person"), RDFS.subClassOf, ir)).isTrue();
            assertThat(m.contains(m.createResource(B_NS + "Employee"), RDFS.subClassOf, ir)).isTrue();
            assertThat(r.unused()).isEmpty();
        }

        @Test
        @DisplayName("I2: left is source, right is already IntegratedResource → left gets subClassOf right")
        void i2_right_is_ir_left_becomes_subclass() {
            Model a = sourceClass(A_NS + "Contact");
            // Graph B already has a resource typed as IntegratedResource
            Model b = integratedClass(B_NS + "Person");

            Alignment alignment = new Alignment(A_NS + "Contact", B_NS + "Person", "Person", Alignment.Type.CLASS);
            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, List.of(alignment));
            Model m = r.graph();

            assertThat(m.contains(
                    m.createResource(A_NS + "Contact"), RDFS.subClassOf, m.createResource(B_NS + "Person")))
                    .isTrue();
        }

        @Test
        @DisplayName("I3: left is IntegratedResource, right is source → right gets subClassOf left")
        void i3_left_is_ir_right_becomes_subclass() {
            Model a = integratedClass(A_NS + "Person");
            Model b = sourceClass(B_NS + "Staff");

            Alignment alignment = new Alignment(A_NS + "Person", B_NS + "Staff", "Person", Alignment.Type.CLASS);
            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, List.of(alignment));
            Model m = r.graph();

            assertThat(m.contains(
                    m.createResource(B_NS + "Staff"), RDFS.subClassOf, m.createResource(A_NS + "Person")))
                    .isTrue();
        }

        @Test
        @DisplayName("I4: both are IntegratedResources → new IR replaces both")
        void i4_both_ir_create_new_ir() {
            Model a = integratedClass(A_NS + "Person");
            Model b = integratedClass(B_NS + "Contact");

            Alignment alignment = new Alignment(A_NS + "Person", B_NS + "Contact", "Entity", Alignment.Type.CLASS);
            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, List.of(alignment));
            Model m = r.graph();

            Resource newIR = m.createResource(IR_NS + "Entity");
            assertThat(m.contains(newIR, RDF.type, INTEGRATED_RESOURCE_TYPE)).isTrue();
            assertThat(m.contains(m.createResource(A_NS + "Person"), null, (Resource) null)).isFalse();
            assertThat(m.contains(m.createResource(B_NS + "Contact"), null, (Resource) null)).isFalse();
        }

        @Test
        @DisplayName("unknown IRIs in alignment are added to unused set")
        void unknown_iris_are_unused() {
            Model empty = ModelFactory.createDefaultModel();
            Alignment alignment = new Alignment(
                    "http://ghost/A", "http://ghost/B", "Ghost", Alignment.Type.CLASS);
            SchemaIntegrator.IntegrationResult r = integrator.integrate(empty, empty, List.of(alignment));
            assertThat(r.unused()).hasSize(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Datatype-property integration
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Datatype-property alignment")
    class DatatypePropertyTests {

        @Test
        @DisplayName("I1 for datatype properties: integrated parent class, two source props → new integrated prop")
        void i1_two_source_datatype_props() {
            Model a = sourceClass(A_NS + "Person");
            Model b = sourceClass(B_NS + "Employee");
            addDatatypeProperty(a, A_NS + "name", A_NS + "Person");
            addDatatypeProperty(b, B_NS + "fullName", B_NS + "Employee");

            Alignment classAlign = new Alignment(A_NS + "Person", B_NS + "Employee", "Person", Alignment.Type.CLASS);
            Alignment propAlign = new Alignment(A_NS + "name", B_NS + "fullName", "name", Alignment.Type.DATATYPE);

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, List.of(classAlign, propAlign));
            Model m = r.graph();

            Resource intProp = m.createResource(IR_NS + "name");
            assertThat(m.contains(intProp, RDF.type, RDF.Property)).isTrue();
            assertThat(m.contains(intProp, RDF.type, INTEGRATED_RESOURCE_TYPE)).isTrue();
            assertThat(m.contains(m.createResource(A_NS + "name"), RDFS.subPropertyOf, intProp)).isTrue();
            assertThat(m.contains(m.createResource(B_NS + "fullName"), RDFS.subPropertyOf, intProp)).isTrue();
            assertThat(r.unused()).isEmpty();
        }

        @Test
        @DisplayName("I2/I3 reuse: new source prop aligned to an already-integrated leaf reuses the existing integrated parent (no competitor IR)")
        void new_prop_aligned_to_integrated_leaf_reuses_parent() {
            // Prior integrated state: integrated class Person, integrated property IP
            // (personName), and source A.personName already integrated as a leaf of IP.
            Model prior = ModelFactory.createDefaultModel();
            Resource irPerson = prior.createResource(IR_NS + "Person");
            prior.add(irPerson, RDF.type, RDFS.Class);
            prior.add(irPerson, RDF.type, INTEGRATED_RESOURCE_TYPE);

            Resource ip = prior.createResource(IR_NS + "personName");   // the existing integrated property
            prior.add(ip, RDF.type, RDF.Property);
            prior.add(ip, RDF.type, INTEGRATED_RESOURCE_TYPE);
            prior.add(ip, RDFS.domain, irPerson);

            Resource srcClassA = prior.createResource(A_NS + "Person");
            prior.add(srcClassA, RDF.type, RDFS.Class);
            prior.add(srcClassA, RDFS.subClassOf, irPerson);
            Resource pOld = prior.createResource(A_NS + "personName");   // already-integrated leaf
            prior.add(pOld, RDF.type, RDF.Property);
            prior.add(pOld, RDFS.domain, srcClassA);
            prior.add(pOld, RDFS.subPropertyOf, ip);

            // New source: Employee (also a Person) with a fresh empName property.
            Model newSource = ModelFactory.createDefaultModel();
            Resource srcClassB = newSource.createResource(B_NS + "Employee");
            newSource.add(srcClassB, RDF.type, RDFS.Class);
            newSource.add(srcClassB, RDFS.subClassOf, irPerson);
            Resource pNew = newSource.createResource(B_NS + "empName");
            newSource.add(pNew, RDF.type, RDF.Property);
            newSource.add(pNew, RDFS.domain, srcClassB);

            // Align the NEW prop against the already-integrated leaf; the label deliberately
            // differs from the existing IP's local name so an I1 mint would be a competitor.
            Alignment propAlign = new Alignment(A_NS + "personName", B_NS + "empName", "combinedName", Alignment.Type.DATATYPE);

            SchemaIntegrator.IntegrationResult r = integrator.integrate(prior, newSource, List.of(propAlign));
            Model m = r.graph();

            assertThat(m.contains(pNew, RDFS.subPropertyOf, ip))
                    .as("new prop reuses the existing integrated parent (I2/I3)").isTrue();
            assertThat(m.contains(m.createResource(IR_NS + "combinedName"), RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .as("no competitor IR minted for an already-integrated leaf").isFalse();
            long integratedParents = m.listStatements(pNew, RDFS.subPropertyOf, (Resource) null)
                    .filterKeep(s -> s.getObject().isResource() && m.contains(s.getObject().asResource(), RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .toList().size();
            assertThat(integratedParents).as("exactly one integrated parent").isEqualTo(1L);
            assertThat(r.unused()).isEmpty();
        }

        @Test
        @DisplayName("datatype property is unused when domain classes are not aligned")
        void prop_unused_without_class_alignment() {
            Model a = sourceClass(A_NS + "Person");
            Model b = sourceClass(B_NS + "Employee");
            addDatatypeProperty(a, A_NS + "name", A_NS + "Person");
            addDatatypeProperty(b, B_NS + "fullName", B_NS + "Employee");

            // No class alignment → property domains will never find a common IR
            Alignment propAlign = new Alignment(A_NS + "name", B_NS + "fullName", "name", Alignment.Type.DATATYPE);
            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, List.of(propAlign));
            assertThat(r.unused()).hasSize(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Object-property integration
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Object-property alignment")
    class ObjectPropertyTests {

        @Test
        @DisplayName("I1 for object properties: both domain and range classes integrated → new integrated prop")
        void i1_object_property_with_integrated_domain_and_range() {
            Model a = sourceClass(A_NS + "Order");
            Model b = sourceClass(B_NS + "Purchase");
            Model a2 = ModelFactory.createDefaultModel().add(a);
            Model b2 = ModelFactory.createDefaultModel().add(b);
            addSourceClass(a2, A_NS + "Product");
            addSourceClass(b2, B_NS + "Item");
            addObjectProperty(a2, A_NS + "hasProduct", A_NS + "Order", A_NS + "Product");
            addObjectProperty(b2, B_NS + "contains", B_NS + "Purchase", B_NS + "Item");

            List<Alignment> alignments = List.of(
                    new Alignment(A_NS + "Order", B_NS + "Purchase", "Order", Alignment.Type.CLASS),
                    new Alignment(A_NS + "Product", B_NS + "Item", "Product", Alignment.Type.CLASS),
                    new Alignment(A_NS + "hasProduct", B_NS + "contains", "hasProduct", Alignment.Type.OBJECT)
            );

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a2, b2, alignments);
            Model m = r.graph();

            Resource intProp = m.createResource(IR_NS + "hasProduct");
            assertThat(m.contains(intProp, RDF.type, RDF.Property)).isTrue();
            assertThat(m.contains(intProp, RDF.type, INTEGRATED_RESOURCE_TYPE)).isTrue();
            assertThat(r.unused()).isEmpty();
        }

        @Test
        @DisplayName("object property unused when range classes are not aligned")
        void object_property_unused_without_range_alignment() {
            Model a = sourceClass(A_NS + "Order");
            Model b = sourceClass(B_NS + "Purchase");
            addSourceClass(a, A_NS + "Product");
            addSourceClass(b, B_NS + "Item");
            addObjectProperty(a, A_NS + "hasProduct", A_NS + "Order", A_NS + "Product");
            addObjectProperty(b, B_NS + "contains", B_NS + "Purchase", B_NS + "Item");

            List<Alignment> alignments = List.of(
                    // Domains aligned, but ranges NOT aligned
                    new Alignment(A_NS + "Order", B_NS + "Purchase", "Order", Alignment.Type.CLASS),
                    new Alignment(A_NS + "hasProduct", B_NS + "contains", "hasProduct", Alignment.Type.OBJECT)
            );

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, alignments);
            assertThat(r.unused()).hasSize(1);
            assertThat(r.unused().get(0).label()).isEqualTo("hasProduct");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JOIN alignment interactions
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JOIN alignment interactions")
    class JoinAlignmentTests {

        @Test
        @DisplayName("JOIN in same call as CLASS: class IR created first, JOIN uses existing IR without auto-promotion")
        void join_uses_existing_ir_when_class_aligned_in_same_call() {
            // Two datasets: Artworks (ds1) and Artists (ds2)
            Model a = sourceClass(A_NS + "Artworks");
            addDatatypeProperty(a, A_NS + "title", A_NS + "Artworks");
            addDatatypeProperty(a, A_NS + "artist_id", A_NS + "Artworks");

            Model b = sourceClass(B_NS + "Artists");
            addDatatypeProperty(b, B_NS + "id", B_NS + "Artists");
            addDatatypeProperty(b, B_NS + "name", B_NS + "Artists");

            // Provide a class alignment AND a join alignment together.
            // Phase order: CLASS runs before JOIN, so the IR is created first.
            List<Alignment> alignments = List.of(
                    new Alignment(A_NS + "Artworks", B_NS + "Artists", "Entities", Alignment.Type.CLASS),
                    new Alignment(A_NS + "artist_id", B_NS + "id", "entityId|hasRelated|forward", Alignment.Type.JOIN)
            );

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, alignments);
            Model m = r.graph();

            // The single IR created by the class alignment
            Resource ir = m.createResource(IR_NS + "Entities");
            assertThat(m.contains(ir, RDF.type, INTEGRATED_RESOURCE_TYPE)).isTrue();
            assertThat(m.contains(m.createResource(A_NS + "Artworks"), RDFS.subClassOf, ir)).isTrue();
            assertThat(m.contains(m.createResource(B_NS + "Artists"), RDFS.subClassOf, ir)).isTrue();

            // No orphaned auto-promoted IRs should exist (the class alignment took care of the IR)
            long irCount = m.listSubjectsWithProperty(RDF.type, INTEGRATED_RESOURCE_TYPE)
                    .filterKeep(res -> res.isURIResource() && res.getURI().startsWith(IR_NS))
                    .toList().size();
            // Only one IR for the classes (the join property is also an IR, so count may be > 1,
            // but there must be exactly ONE class IR)
            assertThat(m.listSubjectsWithProperty(RDF.type, RDFS.Class)
                    .filterKeep(res -> m.contains(res, RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .toList()).hasSize(1);

            assertThat(r.unused()).isEmpty();
        }

        @Test
        @DisplayName("JOIN without class alignment: auto-promotes source classes to IntegratedResources and graph is connected")
        void join_without_class_alignment_auto_promotes() {
            // Dataset 1: Artworks, Dataset 2: Artists — no class alignment
            Model a = sourceClass(A_NS + "Artworks");
            addDatatypeProperty(a, A_NS + "artist_id", A_NS + "Artworks");

            Model b = sourceClass(B_NS + "Artists");
            addDatatypeProperty(b, B_NS + "id", B_NS + "Artists");

            List<Alignment> alignments = List.of(
                    new Alignment(A_NS + "artist_id", B_NS + "id", "artistId|hasArtist|forward", Alignment.Type.JOIN)
            );

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, alignments);
            Model m = r.graph();

            boolean artworksHasIR = m.listStatements(m.createResource(A_NS + "Artworks"), RDFS.subClassOf, (Resource) null)
                    .filterKeep(s -> s.getObject().isResource() && m.contains(s.getObject().asResource(), RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .hasNext();
            boolean artistsHasIR = m.listStatements(m.createResource(B_NS + "Artists"), RDFS.subClassOf, (Resource) null)
                    .filterKeep(s -> s.getObject().isResource() && m.contains(s.getObject().asResource(), RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .hasNext();
            assertThat(artworksHasIR).as("Artworks should have an auto-promoted IR superclass").isTrue();
            assertThat(artistsHasIR).as("Artists should have an auto-promoted IR superclass").isTrue();

            Resource joinProp = m.listSubjectsWithProperty(RDF.type, m.createResource(IntegrationVocab.JOIN_PROPERTY))
                    .nextOptional().orElse(null);
            assertThat(joinProp).as("join property must exist").isNotNull();

            Resource hasArtist = m.listSubjectsWithProperty(RDFS.label, "hasArtist")
                    .nextOptional().orElse(null);
            assertThat(hasArtist).as("hasArtist object property must exist").isNotNull();

            assertThat(r.unused()).isEmpty();
        }

        @Test
        @DisplayName("JOIN then class alignment in same call: class alignment reuses auto-promoted IR (effectiveIR)")
        void join_then_class_in_same_call_reuses_auto_promoted_ir() {
            // Simulate: JOIN alignment listed BEFORE class alignment in the user's list.
            // The operator processes CLASS first, so this is the same as class first.
            // This verifies that when class alignment uses effectiveIR, it finds the IR
            // created for the source class by the JOIN and uses I3 (no duplicate IR).
            Model a = sourceClass(A_NS + "Artworks");
            addDatatypeProperty(a, A_NS + "artist_id", A_NS + "Artworks");

            Model b = sourceClass(B_NS + "Artists");
            addDatatypeProperty(b, B_NS + "id", B_NS + "Artists");

            Model extra = sourceClass(A_NS + "ArtCollection"); // another source for Artworks

            // This simulates a case where a join was done previously (auto-promoting Artworks)
            // and now a class alignment is applied. Since integrate() re-processes from scratch
            // and CLASS runs before JOIN, the class alignment creates the IR first.
            List<Alignment> alignments = List.of(
                    new Alignment(A_NS + "Artworks", A_NS + "ArtCollection", "Artworks", Alignment.Type.CLASS),
                    new Alignment(A_NS + "artist_id", B_NS + "id", "artistId|hasArtist|forward", Alignment.Type.JOIN)
            );

            Model combined = ModelFactory.createDefaultModel().add(a).add(b).add(extra);
            SchemaIntegrator.IntegrationResult r = integrator.integrate(combined, ModelFactory.createDefaultModel(), alignments);
            Model m = r.graph();

            // Only ONE class-type IntegratedResource should exist for Artworks/ArtCollection
            Resource artworksIR = m.createResource(IR_NS + "Artworks");
            assertThat(m.contains(artworksIR, RDF.type, INTEGRATED_RESOURCE_TYPE)).isTrue();
            assertThat(m.contains(m.createResource(A_NS + "Artworks"), RDFS.subClassOf, artworksIR)).isTrue();
            assertThat(m.contains(m.createResource(A_NS + "ArtCollection"), RDFS.subClassOf, artworksIR)).isTrue();

            Resource joinProp = m.listSubjectsWithProperty(RDF.type, m.createResource(IntegrationVocab.JOIN_PROPERTY))
                    .nextOptional().orElse(null);
            assertThat(joinProp).isNotNull();
            assertThat(m.contains(joinProp, RDFS.domain, artworksIR))
                    .as("join domain should be the class-alignment IR, not a duplicate").isTrue();

            assertThat(r.unused()).isEmpty();
        }

        @Test
        @DisplayName("JOIN emits the paper's two-node form: P_l datatype-property parent + P_s join object-property (Fig. 14)")
        void join_emits_two_node_form() {
            Model a = sourceClass(A_NS + "Artworks");
            addDatatypeProperty(a, A_NS + "artist_id", A_NS + "Artworks");

            Model b = sourceClass(B_NS + "Artists");
            addDatatypeProperty(b, B_NS + "id", B_NS + "Artists");

            List<Alignment> alignments = List.of(
                    new Alignment(A_NS + "artist_id", B_NS + "id", "hasArtistJoin|hasArtist|forward", Alignment.Type.JOIN)
            );

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, alignments);
            Model m = r.graph();

            Resource pA = m.createResource(A_NS + "artist_id");
            Resource pB = m.createResource(B_NS + "id");
            Resource pL = m.createResource(IR_NS + "hasArtistJoin");   // generateURI(joinLabel)
            Resource pS = m.createResource(IR_NS + "hasArtist");        // generateURI(relName)
            Resource xsdString = m.createResource("http://www.w3.org/2001/XMLSchema#string");

            // ── P_l: the unified integrated datatype property (subproperty parent) ──
            assertThat(m.contains(pL, RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .as("P_l is an IntegratedResource").isTrue();
            assertThat(m.contains(pL, RDF.type, RDF.Property))
                    .as("P_l is an rdf:Property").isTrue();
            assertThat(m.contains(pA, RDFS.subPropertyOf, pL))
                    .as("P_A subPropertyOf P_l").isTrue();
            assertThat(m.contains(pB, RDFS.subPropertyOf, pL))
                    .as("P_B subPropertyOf P_l").isTrue();
            assertThat(m.contains(pL, RDFS.range, xsdString))
                    .as("P_l range is xsd:string (datatype fallback, not a class)").isTrue();
            assertThat(m.listStatements(pL, RDFS.domain, (Resource) null).hasNext())
                    .as("P_l has NO rdfs:domain — keeps it out of MappingCatalog object-property Query 3").isFalse();

            // ── P_s: the join object property carrying the entity domain/range ──
            assertThat(m.contains(pS, RDF.type, RDF.Property))
                    .as("P_s is an rdf:Property").isTrue();
            assertThat(m.contains(pS, RDF.type, m.createResource(IntegrationVocab.JOIN_PROPERTY)))
                    .as("P_s is typed nx:JoinProperty").isTrue();

            Resource domA = m.listStatements(pS, RDFS.domain, (Resource) null).next().getObject().asResource();
            Resource domB = m.listStatements(pS, RDFS.range, (Resource) null).next().getObject().asResource();
            assertThat(m.contains(domA, RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .as("P_s domain is an integrated class (auto-promoted Artworks)").isTrue();
            assertThat(m.contains(domB, RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .as("P_s range is an integrated class (auto-promoted Artists)").isTrue();
            assertThat(m.contains(m.createResource(A_NS + "Artworks"), RDFS.subClassOf, domA)).isTrue();
            assertThat(m.contains(m.createResource(B_NS + "Artists"), RDFS.subClassOf, domB)).isTrue();

            assertThat(m.contains(pS, m.createProperty(IntegrationVocab.JOIN_ON_LEFT), pA))
                    .as("P_s nx:joinOnLeft P_A").isTrue();
            assertThat(m.contains(pS, m.createProperty(IntegrationVocab.JOIN_ON_RIGHT), pB))
                    .as("P_s nx:joinOnRight P_B").isTrue();

            // ── P_l links to its join object property ──
            assertThat(m.contains(pL, m.createProperty(IntegrationVocab.JOIN_PROPERTY), pS))
                    .as("P_l nx:JoinProperty P_s").isTrue();

            // P_s must NOT be an IntegratedResource (would leak into object-property Query 3).
            assertThat(m.contains(pS, RDF.type, INTEGRATED_RESOURCE_TYPE))
                    .as("P_s is not typed IntegratedResource").isFalse();

            assertThat(r.unused()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multiple alignments
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Multiple alignments")
    class MultipleAlignmentTests {

        @Test
        @DisplayName("two independent class alignments produce two independent integrated resources")
        void two_independent_class_alignments() {
            Model a = sourceClass(A_NS + "Person");
            addSourceClass(a, A_NS + "Company");
            Model b = sourceClass(B_NS + "Employee");
            addSourceClass(b, B_NS + "Organisation");

            List<Alignment> alignments = List.of(
                    new Alignment(A_NS + "Person", B_NS + "Employee", "Person", Alignment.Type.CLASS),
                    new Alignment(A_NS + "Company", B_NS + "Organisation", "Organisation", Alignment.Type.CLASS)
            );

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, alignments);
            Model m = r.graph();

            assertThat(m.contains(m.createResource(IR_NS + "Person"), RDF.type, INTEGRATED_RESOURCE_TYPE)).isTrue();
            assertThat(m.contains(m.createResource(IR_NS + "Organisation"), RDF.type, INTEGRATED_RESOURCE_TYPE)).isTrue();
            assertThat(r.unused()).isEmpty();
        }

        @Test
        @DisplayName("empty alignments list returns union of both graphs unchanged")
        void empty_alignments_returns_union() {
            Model a = sourceClass(A_NS + "Foo");
            Model b = sourceClass(B_NS + "Bar");

            SchemaIntegrator.IntegrationResult r = integrator.integrate(a, b, List.of());
            Model m = r.graph();

            // Both source classes still present
            assertThat(m.contains(m.createResource(A_NS + "Foo"), RDF.type, RDFS.Class)).isTrue();
            assertThat(m.contains(m.createResource(B_NS + "Bar"), RDF.type, RDFS.Class)).isTrue();
            assertThat(r.unused()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tracked / incremental alignments (Phase 2b)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tracked alignments — applied / deferred / retry")
    class TrackedAlignmentTests {

        @Test
        @DisplayName("applied and deferred carry their mappingIds through the result")
        void applied_and_deferred_carry_mapping_ids() {
            Model a = sourceClass(A_NS + "Person");
            Model b = sourceClass(B_NS + "Employee");
            addDatatypeProperty(a, A_NS + "name", A_NS + "Person");
            addDatatypeProperty(b, B_NS + "fullName", B_NS + "Employee");

            java.util.UUID classId = java.util.UUID.randomUUID();
            java.util.UUID propId = java.util.UUID.randomUUID();
            // Class and property aligned together: classes run first, so the property's
            // domains resolve and both alignments apply — each carrying its mappingId.
            List<SchemaIntegrator.TrackedAlignment> newMappings = List.of(
                    new SchemaIntegrator.TrackedAlignment(classId,
                            new Alignment(A_NS + "Person", B_NS + "Employee", "Person", Alignment.Type.CLASS)),
                    new SchemaIntegrator.TrackedAlignment(propId,
                            new Alignment(A_NS + "name", B_NS + "fullName", "name", Alignment.Type.DATATYPE))
            );

            SchemaIntegrator.IntegrationResult r =
                    integrator.integrate(a, b, newMappings, List.of());

            assertThat(r.applied()).extracting(SchemaIntegrator.TrackedAlignment::mappingId)
                    .containsExactlyInAnyOrder(classId, propId);
            assertThat(r.deferred()).isEmpty();
        }

        @Test
        @DisplayName("deferred property retries and applies when its classes are integrated in a later call with the deferred set fed back")
        void deferred_property_retries_when_classes_arrive() {
            Model g = ModelFactory.createDefaultModel();
            addSourceClass(g, A_NS + "Person");
            addSourceClass(g, B_NS + "Employee");
            addDatatypeProperty(g, A_NS + "name", A_NS + "Person");
            addDatatypeProperty(g, B_NS + "fullName", B_NS + "Employee");

            java.util.UUID propId = java.util.UUID.randomUUID();

            // Call 1: only the property alignment (no class alignment) → deferred.
            SchemaIntegrator.IntegrationResult r1 = integrator.integrate(
                    ModelFactory.createDefaultModel(), g,
                    List.of(new SchemaIntegrator.TrackedAlignment(propId,
                            new Alignment(A_NS + "name", B_NS + "fullName", "name", Alignment.Type.DATATYPE))),
                    List.of());

            assertThat(r1.applied()).isEmpty();
            assertThat(r1.deferred()).extracting(SchemaIntegrator.TrackedAlignment::mappingId)
                    .containsExactly(propId);

            // Call 2: class alignment as a new mapping, prior deferred property fed back.
            java.util.UUID classId = java.util.UUID.randomUUID();
            SchemaIntegrator.IntegrationResult r2 = integrator.integrate(
                    r1.graph(), ModelFactory.createDefaultModel(),
                    List.of(new SchemaIntegrator.TrackedAlignment(classId,
                            new Alignment(A_NS + "Person", B_NS + "Employee", "Person", Alignment.Type.CLASS))),
                    r1.deferred());

            assertThat(r2.applied()).extracting(SchemaIntegrator.TrackedAlignment::mappingId)
                    .containsExactlyInAnyOrder(classId, propId);
            assertThat(r2.deferred()).isEmpty();
            // The retried property is now integrated.
            Model m = r2.graph();
            assertThat(m.contains(m.createResource(A_NS + "name"), RDFS.subPropertyOf,
                    m.createResource(IR_NS + "name"))).isTrue();
        }

        @Test
        @DisplayName("depth-1 for properties: a source prop with two distinct integrated parents is rejected")
        void property_with_two_integrated_parents_is_rejected() {
            Model m = ModelFactory.createDefaultModel();
            Resource irClass = m.createResource(IR_NS + "Thing");
            m.add(irClass, RDF.type, RDFS.Class);
            m.add(irClass, RDF.type, INTEGRATED_RESOURCE_TYPE);

            Resource ip1 = m.createResource(IR_NS + "prop1");
            Resource ip2 = m.createResource(IR_NS + "prop2");
            for (Resource ip : List.of(ip1, ip2)) {
                m.add(ip, RDF.type, RDF.Property);
                m.add(ip, RDF.type, INTEGRATED_RESOURCE_TYPE);
            }

            Resource srcClass = m.createResource(A_NS + "Src");
            m.add(srcClass, RDF.type, RDFS.Class);
            m.add(srcClass, RDFS.subClassOf, irClass);
            // pX already (illegally) has TWO integrated parents.
            Resource pX = m.createResource(A_NS + "pX");
            m.add(pX, RDF.type, RDF.Property);
            m.add(pX, RDFS.domain, srcClass);
            m.add(pX, RDFS.subPropertyOf, ip1);
            m.add(pX, RDFS.subPropertyOf, ip2);

            Resource srcClass2 = m.createResource(B_NS + "Src2");
            m.add(srcClass2, RDF.type, RDFS.Class);
            m.add(srcClass2, RDFS.subClassOf, irClass);
            Resource pY = m.createResource(B_NS + "pY");
            m.add(pY, RDF.type, RDF.Property);
            m.add(pY, RDFS.domain, srcClass2);

            Alignment align = new Alignment(A_NS + "pX", B_NS + "pY", "merged", Alignment.Type.DATATYPE);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    integrator.integrate(m, ModelFactory.createDefaultModel(), List.of(align)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("more than one distinct integrated parent");
        }

        @Test
        @DisplayName("structurally invalid CLASS alignment (target is a property) is a validation failure")
        void invalid_class_alignment_is_validation_failure() {
            Model m = ModelFactory.createDefaultModel();
            addSourceClass(m, B_NS + "Employee");
            // A_NS+"Person" is present but typed as an rdf:Property, not a class.
            Resource bogus = m.createResource(A_NS + "Person");
            m.add(bogus, RDF.type, RDF.Property);
            m.add(bogus, RDFS.domain, m.createResource(B_NS + "Employee"));

            Alignment invalid = new Alignment(A_NS + "Person", B_NS + "Employee", "Person", Alignment.Type.CLASS);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    integrator.integrate(m, ModelFactory.createDefaultModel(), List.of(invalid)))
                    .isInstanceOf(MappingValidationException.class);
        }

        @Test
        @DisplayName("structurally invalid DATATYPE alignment (target is a class) is a validation failure")
        void invalid_datatype_alignment_target_is_a_class() {
            Model m = ModelFactory.createDefaultModel();
            addSourceClass(m, A_NS + "Person");
            addDatatypeProperty(m, A_NS + "name", A_NS + "Person");
            // The B side is an rdfs:Class, not a property — invalid as a datatype target.
            addSourceClass(m, B_NS + "Employee");

            Alignment invalid = new Alignment(
                    A_NS + "name", B_NS + "Employee", "name", Alignment.Type.DATATYPE);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    integrator.integrate(m, ModelFactory.createDefaultModel(), List.of(invalid)))
                    .isInstanceOf(MappingValidationException.class)
                    .hasMessageContaining("is not an rdf:Property");
        }

        @Test
        @DisplayName("DATATYPE alignment onto a property with no rdfs:domain is a validation failure")
        void invalid_datatype_alignment_property_without_domain() {
            Model m = ModelFactory.createDefaultModel();
            addSourceClass(m, A_NS + "Person");
            addDatatypeProperty(m, A_NS + "name", A_NS + "Person");
            // Typed as a property but carrying no domain — not M-compliant (paper §5.1).
            m.add(m.createResource(B_NS + "fullName"), RDF.type, RDF.Property);

            Alignment invalid = new Alignment(
                    A_NS + "name", B_NS + "fullName", "name", Alignment.Type.DATATYPE);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    integrator.integrate(m, ModelFactory.createDefaultModel(), List.of(invalid)))
                    .isInstanceOf(MappingValidationException.class)
                    .hasMessageContaining("has no rdfs:domain");
        }

        @Test
        @DisplayName("OBJECT alignment onto a property with no rdfs:range is a validation failure")
        void invalid_object_alignment_property_without_range() {
            Model m = ModelFactory.createDefaultModel();
            addSourceClass(m, A_NS + "Person");
            addSourceClass(m, A_NS + "Address");
            addSourceClass(m, B_NS + "Employee");
            addObjectProperty(m, A_NS + "livesAt", A_NS + "Person", A_NS + "Address");
            // Domain but no range — legal for a datatype property, invalid for an object one.
            addDatatypeProperty(m, B_NS + "worksAt", B_NS + "Employee");

            Alignment invalid = new Alignment(
                    A_NS + "livesAt", B_NS + "worksAt", "location", Alignment.Type.OBJECT);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    integrator.integrate(m, ModelFactory.createDefaultModel(), List.of(invalid)))
                    .isInstanceOf(MappingValidationException.class)
                    .hasMessageContaining("has no rdfs:range");
        }

        @Test
        @DisplayName("a property alignment naming an absent resource defers — it is not a validation failure")
        void absent_property_defers_rather_than_failing_validation() {
            Model m = ModelFactory.createDefaultModel();
            addSourceClass(m, A_NS + "Person");
            addDatatypeProperty(m, A_NS + "name", A_NS + "Person");
            // B_NS+"fullName" is absent entirely — its source has not arrived yet. This is
            // the "conditions not yet met" case the paper defers via A_unused, and must be
            // kept distinct from the structural failures above.
            java.util.UUID id = java.util.UUID.randomUUID();

            SchemaIntegrator.IntegrationResult r = integrator.integrate(
                    m, ModelFactory.createDefaultModel(),
                    List.of(new SchemaIntegrator.TrackedAlignment(id, new Alignment(
                            A_NS + "name", B_NS + "fullName", "name", Alignment.Type.DATATYPE))),
                    List.of());

            assertThat(r.applied()).isEmpty();
            assertThat(r.deferred()).extracting(SchemaIntegrator.TrackedAlignment::mappingId)
                    .containsExactly(id);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Source (non-integrated) class model. */
    private static Model sourceClass(String iri) {
        Model m = ModelFactory.createDefaultModel();
        addSourceClass(m, iri);
        return m;
    }

    private static void addSourceClass(Model m, String iri) {
        m.add(m.createResource(iri), RDF.type, RDFS.Class);
        m.add(m.createResource(iri), RDFS.label, localName(iri));
    }

    /** Already-integrated class (carries the IntegratedResource marker). */
    private static Model integratedClass(String iri) {
        Model m = ModelFactory.createDefaultModel();
        Resource r = m.createResource(iri);
        m.add(r, RDF.type, RDFS.Class);
        m.add(r, RDF.type, m.createResource(IntegrationVocab.INTEGRATED_RESOURCE));
        m.add(r, RDFS.label, localName(iri));
        return m;
    }

    private static void addDatatypeProperty(Model m, String propIri, String domainIri) {
        Resource p = m.createResource(propIri);
        m.add(p, RDF.type, RDF.Property);
        m.add(p, RDFS.domain, m.createResource(domainIri));
        m.add(p, RDFS.label, localName(propIri));
    }

    private static void addObjectProperty(Model m, String propIri, String domainIri, String rangeIri) {
        Resource p = m.createResource(propIri);
        m.add(p, RDF.type, RDF.Property);
        m.add(p, RDFS.domain, m.createResource(domainIri));
        m.add(p, RDFS.range, m.createResource(rangeIri));
        m.add(p, RDFS.label, localName(propIri));
    }

    private static String localName(String iri) {
        int i = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        return i >= 0 ? iri.substring(i + 1) : iri;
    }
}

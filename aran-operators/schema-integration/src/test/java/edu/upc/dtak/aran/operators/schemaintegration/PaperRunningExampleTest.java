package edu.upc.dtak.aran.operators.schemaintegration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The running example of Flores et al. (2024) reproduced end to end: §5.1 integrates
 * G_CMOA with G_Cooperhewitt (Table 4), then §5.2 adds G_Artists to that result (Table 5).
 *
 * <p>This is the multi-source <em>sequence</em> the implementation plan asks for. The
 * per-invariant unit cases live in {@link SchemaIntegratorTest}; what this pins is that a
 * second integration composes correctly on top of the first one's output.
 *
 * <p><strong>On the shape of G_Artists.</strong> §5.2's narrative — Creator+Artist as I4
 * ("we have two :IntegratedResource of type class"), Nationality+Citizenship as I2 reusing
 * Citizenship — presumes the incoming graph already carries {@code :IntegratedResource} nodes.
 * Aran cannot produce such a graph: bootstrapping emits only raw {@code rdfs:Class}/
 * {@code rdf:Property}, and integrated resources exist solely in the prior integrated graph.
 * So these tests feed the raw source Aran actually produces, and reach the same grouping by
 * I2/I3 reuse.
 *
 * <p>That leaves I4 to arise the way it genuinely can here: not from the new source, but when a
 * <em>later</em> alignment recognises that two separately-integrated concepts are the same
 * ({@link #later_alignment_merges_two_separately_integrated_concepts}).
 */
@DisplayName("Flores et al. (2024) running example — §5.1 then §5.2")
class PaperRunningExampleTest {

    private static final String CMOA = "http://cmoa/";
    private static final String CH = "http://cooperhewitt/";
    private static final String ART = "http://artists/";
    private static final String IR = IntegrationVocab.INTEGRATION_NS;

    private final SchemaIntegrator integrator = new SchemaIntegrator();

    // ── §5.1: G_CMOA ⋈ G_Cooperhewitt, alignments of Table 4 ────────────────────

    private Model cmoa() {
        Model m = ModelFactory.createDefaultModel();
        cls(m, CMOA + "o1");
        cls(m, CMOA + "o2");
        dtp(m, CMOA + "title", CMOA + "o1");
        dtp(m, CMOA + "web_url", CMOA + "o1");
        dtp(m, CMOA + "fullName", CMOA + "o2");
        dtp(m, CMOA + "Nationality", CMOA + "o2"); // deliberately absent from Table 4
        obp(m, CMOA + "creator", CMOA + "o1", CMOA + "o2");
        // Also absent from Table 4: a class and property that stay raw after the first
        // integration, so a later alignment can build a second concept out of them.
        cls(m, CMOA + "o3");
        dtp(m, CMOA + "maker_name", CMOA + "o3");
        return m;
    }

    private Model cooperhewitt() {
        Model m = ModelFactory.createDefaultModel();
        cls(m, CH + "o1");
        cls(m, CH + "o2");
        dtp(m, CH + "title", CH + "o1");
        dtp(m, CH + "url", CH + "o1");
        dtp(m, CH + "personName", CH + "o2");
        obp(m, CH + "participants", CH + "o1", CH + "o2");
        return m;
    }

    /** Table 4 of the paper. */
    private static List<Alignment> table4() {
        return List.of(
                new Alignment(CMOA + "o1", CH + "o1", "Artworks", Alignment.Type.CLASS),
                new Alignment(CMOA + "o2", CH + "o2", "Creator", Alignment.Type.CLASS),
                new Alignment(CMOA + "title", CH + "title", "Title", Alignment.Type.DATATYPE),
                new Alignment(CMOA + "web_url", CH + "url", "URL", Alignment.Type.DATATYPE),
                new Alignment(CMOA + "fullName", CH + "personName", "Name", Alignment.Type.DATATYPE),
                new Alignment(CMOA + "creator", CH + "participants", "Contributors", Alignment.Type.OBJECT));
    }

    private Model firstIntegration() {
        SchemaIntegrator.IntegrationResult r =
                integrator.integrate(cmoa(), cooperhewitt(), table4());
        assertThat(r.deferred()).as("every Table 4 alignment must apply in one pass").isEmpty();
        Model g = r.graph();
        // Fig. 13: two integrated classes and four integrated properties.
        assertThat(integratedClasses(g))
                .containsExactlyInAnyOrder(IR + "Artworks", IR + "Creator");
        assertThat(integratedProperties(g))
                .containsExactlyInAnyOrder(IR + "Title", IR + "URL", IR + "Name", IR + "Contributors");
        assertThat(subClassOf(g, CMOA + "o2")).isEqualTo(IR + "Creator");
        assertThat(subClassOf(g, CH + "o2")).isEqualTo(IR + "Creator");
        assertThat(subPropertyOf(g, CMOA + "fullName")).isEqualTo(IR + "Name");
        assertThat(subPropertyOf(g, CH + "personName")).isEqualTo(IR + "Name");
        return g;
    }

    @Test
    @DisplayName("§5.1: CMOA ⋈ Cooperhewitt builds the integrated graph of Fig. 13")
    void paper_5_1_first_integration() {
        firstIntegration();
    }

    // ── §5.2: add G_Artists on top, alignments of Table 5 ───────────────────────

    /** G_Artists as bootstrapping produces it: raw classes and properties, no IntegratedResource. */
    private static Model artists() {
        Model m = ModelFactory.createDefaultModel();
        cls(m, ART + "Artist");
        dtp(m, ART + "Full_name", ART + "Artist");
        dtp(m, ART + "Citizenship", ART + "Artist");
        return m;
    }

    @Test
    @DisplayName("§5.2: adding Artists folds it into the existing concepts by I2/I3 reuse")
    void paper_5_2_adds_artists() {
        Model gI = firstIntegration();

        // Table 5. The left column names resources of I(G_CMOA, G_Cooperhewitt).
        SchemaIntegrator.IntegrationResult r = integrator.integrate(gI, artists(), List.of(
                new Alignment(IR + "Creator", ART + "Artist", "Artist", Alignment.Type.CLASS),
                new Alignment(CMOA + "Nationality", ART + "Citizenship", "Nationality", Alignment.Type.DATATYPE),
                new Alignment(IR + "Name", ART + "Full_name", "Name", Alignment.Type.DATATYPE)));
        assertThat(r.deferred()).isEmpty();
        Model g = r.graph();

        // Raw Artist has no IR of its own, so I3 reuse folds it under the existing concept
        // rather than minting a replacement — the grouping of Fig. 16, with fewer nodes.
        assertThat(subClassOf(g, ART + "Artist")).isEqualTo(IR + "Creator");
        assertThat(subClassOf(g, CMOA + "o2")).isEqualTo(IR + "Creator");
        assertThat(subClassOf(g, CH + "o2")).isEqualTo(IR + "Creator");
        // Full_name joins the existing Name concept by reuse (Fig. 17's outcome).
        assertThat(subPropertyOf(g, ART + "Full_name")).isEqualTo(IR + "Name");
        assertThat(subPropertyOf(g, CMOA + "fullName")).isEqualTo(IR + "Name");
        // Neither Nationality nor Citizenship was integrated before, so I1 mints one.
        assertThat(subPropertyOf(g, CMOA + "Nationality")).isEqualTo(IR + "Nationality");
        assertThat(subPropertyOf(g, ART + "Citizenship")).isEqualTo(IR + "Nationality");

        assertNoDanglingHierarchy(g);
    }

    @Test
    @DisplayName("a later alignment merging two separately-integrated concepts is I4 (class and property)")
    void later_alignment_merges_two_separately_integrated_concepts() {
        // Stage 1 — CMOA ⋈ Cooperhewitt: builds IR Creator (over o2) and IR Name.
        Model gI = firstIntegration();

        // Stage 2 — add raw Artists, aligned against CMOA's *unaligned leftovers* (o3 and its
        // maker_name). Both sides are raw, so I1 mints a second, separate pair of concepts.
        SchemaIntegrator.IntegrationResult s2 = integrator.integrate(gI, artists(), List.of(
                new Alignment(ART + "Artist", CMOA + "o3", "Maker", Alignment.Type.CLASS),
                new Alignment(ART + "Full_name", CMOA + "maker_name", "MakerName", Alignment.Type.DATATYPE)));
        assertThat(s2.deferred()).isEmpty();
        assertThat(integratedClasses(s2.graph())).contains(IR + "Creator", IR + "Maker");
        assertThat(integratedProperties(s2.graph())).contains(IR + "Name", IR + "MakerName");

        // Stage 3 — the analyst realises the two concepts are the same. Both sides now resolve
        // to *distinct* IntegratedResources, which is the only way I4 can fire in Aran. Note the
        // property label is "Name", so mintIri lands on the IRI the existing Name IR already has.
        SchemaIntegrator.IntegrationResult s3 = integrator.integrate(
                s2.graph(), ModelFactory.createDefaultModel(), List.of(
                        new Alignment(CMOA + "o2", ART + "Artist", "Artist", Alignment.Type.CLASS),
                        new Alignment(CMOA + "fullName", ART + "Full_name", "Name", Alignment.Type.DATATYPE)));
        assertThat(s3.deferred()).isEmpty();
        Model g = s3.graph();

        // Class I4 (Fig. 16): both old IRs are replaced by the newly minted one, and every
        // member of either concept is redirected onto it.
        assertThat(integratedClasses(g))
                .contains(IR + "Artist").doesNotContain(IR + "Creator", IR + "Maker");
        assertThat(subClassOf(g, CMOA + "o2")).isEqualTo(IR + "Artist");
        assertThat(subClassOf(g, CH + "o2")).isEqualTo(IR + "Artist");
        assertThat(subClassOf(g, ART + "Artist")).isEqualTo(IR + "Artist");
        assertThat(subClassOf(g, CMOA + "o3")).isEqualTo(IR + "Artist");

        // Property I4 where the minted IRI collides with one of the merged IRs: the merge
        // target must survive as a usable IntegratedResource rather than be deleted.
        assertThat(integratedProperties(g))
                .as("the I4 merge target must still be an IntegratedResource")
                .contains(IR + "Name");
        assertThat(g.contains(g.createResource(IR + "Name"), RDF.type, RDF.Property))
                .as("the I4 merge target must still be an rdf:Property").isTrue();
        assertThat(integratedProperties(g)).doesNotContain(IR + "MakerName");
        assertThat(subPropertyOf(g, CMOA + "fullName")).isEqualTo(IR + "Name");
        assertThat(subPropertyOf(g, CH + "personName")).isEqualTo(IR + "Name");
        assertThat(subPropertyOf(g, ART + "Full_name")).isEqualTo(IR + "Name");
        assertThat(subPropertyOf(g, CMOA + "maker_name")).isEqualTo(IR + "Name");

        assertNoDanglingHierarchy(g);
    }

    @Test
    @DisplayName("I4 whose label mints the IRI of one of the merged IRs keeps that IR intact")
    void i4_label_colliding_with_an_existing_ir_iri() {
        // The minimal form of the stage-3 case above: a prior integrated graph holding two
        // concepts, merged under a label that mints the IRI the left one already has. The
        // merge target must not be deleted on its way in.
        Model g = ModelFactory.createDefaultModel();
        integratedCls(g, IR + "Person");
        integratedCls(g, IR + "Human");
        cls(g, CMOA + "o1");
        g.add(g.createResource(CMOA + "o1"), RDFS.subClassOf, g.createResource(IR + "Person"));
        cls(g, CH + "o1");
        g.add(g.createResource(CH + "o1"), RDFS.subClassOf, g.createResource(IR + "Human"));

        Model out = integrator.integrate(g, ModelFactory.createDefaultModel(),
                List.of(new Alignment(IR + "Person", IR + "Human", "Person", Alignment.Type.CLASS)))
                .graph();

        assertThat(integratedClasses(out))
                .as("the surviving merge target must still be an IntegratedResource")
                .contains(IR + "Person");
        assertThat(out.contains(out.createResource(IR + "Person"), RDF.type, RDFS.Class)).isTrue();
        assertThat(subClassOf(out, CMOA + "o1")).isEqualTo(IR + "Person");
        assertThat(subClassOf(out, CH + "o1")).isEqualTo(IR + "Person");
        assertNoDanglingHierarchy(out);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Every (sub)class/(sub)property parent must still be a live IntegratedResource. */
    private static void assertNoDanglingHierarchy(Model m) {
        Resource irType = m.createResource(IntegrationVocab.INTEGRATED_RESOURCE);
        for (var pred : List.of(RDFS.subClassOf, RDFS.subPropertyOf)) {
            m.listStatements(null, pred, (RDFNode) null).forEachRemaining(st -> {
                Resource parent = st.getObject().asResource();
                assertThat(m.contains(parent, RDF.type, irType))
                        .as("%s points at %s, which is no longer an IntegratedResource",
                                st.getSubject(), parent)
                        .isTrue();
            });
        }
    }

    private static List<String> integratedClasses(Model m) {
        return m.listSubjectsWithProperty(RDF.type,
                        m.createResource(IntegrationVocab.INTEGRATED_RESOURCE)).toList().stream()
                .filter(r -> m.contains(r, RDF.type, RDFS.Class))
                .map(Resource::getURI).toList();
    }

    private static List<String> integratedProperties(Model m) {
        return m.listSubjectsWithProperty(RDF.type,
                        m.createResource(IntegrationVocab.INTEGRATED_RESOURCE)).toList().stream()
                .filter(r -> m.contains(r, RDF.type, RDF.Property))
                .map(Resource::getURI).toList();
    }

    private static String subClassOf(Model m, String iri) {
        return one(m, iri, RDFS.subClassOf);
    }

    private static String subPropertyOf(Model m, String iri) {
        return one(m, iri, RDFS.subPropertyOf);
    }

    private static String one(Model m, String iri, org.apache.jena.rdf.model.Property pred) {
        var it = m.listStatements(m.createResource(iri), pred, (RDFNode) null).toList();
        assertThat(it).as("%s should have exactly one %s", iri, pred.getLocalName()).hasSize(1);
        return it.get(0).getObject().asResource().getURI();
    }

    private static void cls(Model m, String iri) {
        m.add(m.createResource(iri), RDF.type, RDFS.Class);
        m.add(m.createResource(iri), RDFS.label, local(iri));
    }

    private static void integratedCls(Model m, String iri) {
        cls(m, iri);
        m.add(m.createResource(iri), RDF.type, m.createResource(IntegrationVocab.INTEGRATED_RESOURCE));
    }

    private static void dtp(Model m, String iri, String domain) {
        Resource p = m.createResource(iri);
        m.add(p, RDF.type, RDF.Property);
        m.add(p, RDFS.domain, m.createResource(domain));
        m.add(p, RDFS.label, local(iri));
    }

    private static void obp(Model m, String iri, String domain, String range) {
        dtp(m, iri, domain);
        m.add(m.createResource(iri), RDFS.range, m.createResource(range));
    }

    private static String local(String iri) {
        return iri.substring(Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#')) + 1);
    }
}

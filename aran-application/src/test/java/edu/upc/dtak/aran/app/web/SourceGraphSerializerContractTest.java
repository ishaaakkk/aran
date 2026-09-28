package edu.upc.dtak.aran.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.app.web.dto.GraphDtos;
import edu.upc.dtak.aran.core.service.SourceGraphSerializer;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-module contract the renderer consolidation rests on.
 *
 * <p>{@code SourceGraphSerializer} lives in aran-core and emits an untyped JSON envelope;
 * {@code SourceGraphController} reads that very string back into {@link GraphDtos.SourceGraphResponseDto}
 * whenever a dataset has a cached {@code graph_json}. Nothing but this test connects the two —
 * a renamed record component on either side would compile clean and return a response of nulls
 * to the graph view. It also pins the two render paths (cached JSON vs live Fuseki traversal)
 * to the same output, which is what makes one shared renderer correct rather than merely shorter.
 */
@DisplayName("SourceGraphSerializer ↔ graph DTO contract")
class SourceGraphSerializerContractTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/source/ds-1/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A minimal bootstrapped-source shape: one class, one datatype property, one subClassOf. */
    private static Model sampleGraph() {
        Model m = ModelFactory.createDefaultModel();
        Resource people = m.createResource(NS + "people");
        Resource parent = m.createResource(NS + "party");
        Resource name = m.createResource(NS + "people/name");
        m.add(people, RDF.type, RDFS.Class);
        m.add(parent, RDF.type, RDFS.Class);
        m.add(people, RDFS.subClassOf, parent);
        m.add(name, RDF.type, RDF.Property);
        m.add(name, RDFS.domain, people);
        m.add(name, RDFS.range, XSD.xstring);
        return m;
    }

    @Test
    @DisplayName("the cached JSON envelope deserializes into SourceGraphResponseDto with nothing null")
    void serialized_json_round_trips_into_the_dto() throws Exception {
        String json = SourceGraphSerializer.serialize("ds-1", sampleGraph());

        GraphDtos.SourceGraphResponseDto dto =
                MAPPER.readValue(json, GraphDtos.SourceGraphResponseDto.class);

        assertThat(dto.datasetId()).isEqualTo("ds-1");
        assertThat(dto.graphUri()).isEqualTo("urn:aran:source:ds-1");
        assertThat(dto.tripleCount()).isEqualTo(sampleGraph().size());
        assertThat(dto.empty()).isFalse();

        // Every node field must survive the trip — a renamed component shows up here as a null.
        assertThat(dto.nodes()).isNotEmpty().allSatisfy(n -> {
            assertThat(n.id()).isNotNull();
            assertThat(n.iri()).isNotNull();
            assertThat(n.type()).isNotNull();
            assertThat(n.label()).isNotNull();
        });
        assertThat(dto.edges()).isNotEmpty().allSatisfy(e -> {
            assertThat(e.source()).isNotNull();
            assertThat(e.target()).isNotNull();
            assertThat(e.label()).isNotNull();
        });

        assertThat(dto.nodes()).extracting(GraphDtos.GraphNodeDto::iri)
                .contains(NS + "people", NS + "people/name", XSD.xstring.getURI());
        assertThat(dto.nodes())
                .filteredOn(n -> n.iri().equals(XSD.xstring.getURI()))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo("xsd");
                    assertThat(n.label()).isEqualTo("xsd:string");
                });
        assertThat(dto.nodes())
                .filteredOn(n -> n.iri().equals(NS + "people/name"))
                .singleElement()
                .satisfies(n -> assertThat(n.type()).isEqualTo("datatype"));
        assertThat(dto.edges()).extracting(GraphDtos.GraphEdgeDto::label)
                .containsExactlyInAnyOrder("domain", "range");
    }

    @Test
    @DisplayName("the cached and live source paths render the same graph")
    void cached_json_matches_the_live_traversal() throws Exception {
        Model model = sampleGraph();

        GraphDtos.SourceGraphResponseDto cached = MAPPER.readValue(
                SourceGraphSerializer.serialize("ds-1", model),
                GraphDtos.SourceGraphResponseDto.class);
        SourceGraphSerializer.Graph live = SourceGraphSerializer.build(model, false);

        assertThat(cached.nodes()).extracting(GraphDtos.GraphNodeDto::id)
                .containsExactlyElementsOf(live.nodes().stream()
                        .map(SourceGraphSerializer.GraphNode::id).toList());
        assertThat(cached.edges()).extracting(GraphDtos.GraphEdgeDto::label)
                .containsExactlyElementsOf(live.edges().stream()
                        .map(SourceGraphSerializer.GraphEdge::label).toList());
    }

    @Test
    @DisplayName("includeSubClassOf is the only difference between the source and schema views")
    void subclassof_is_the_only_flag_difference() {
        Model model = sampleGraph();

        SourceGraphSerializer.Graph source = SourceGraphSerializer.build(model, false);
        SourceGraphSerializer.Graph schema = SourceGraphSerializer.build(model, true);

        assertThat(source.edges()).extracting(SourceGraphSerializer.GraphEdge::label)
                .doesNotContain("subClassOf");
        assertThat(schema.edges()).extracting(SourceGraphSerializer.GraphEdge::label)
                .contains("subClassOf");
        assertThat(schema.edges()).containsAll(source.edges());
        assertThat(schema.edges()).hasSize(source.edges().size() + 1);
    }
}

package edu.upc.dtak.aran.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.util.Names;
import org.apache.jena.rdf.model.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Converts a Jena {@link Model} of an RDFS schema graph into the D3-compatible node+edge
 * shape the graph views render.
 *
 * <p>Two consumers, one traversal. {@link #build(Model, boolean)} returns the structure
 * (what {@code SourceGraphController} maps onto its response DTOs); {@link #serialize} wraps
 * the same structure in the response envelope as JSON, which the bootstrapping operator caches
 * on {@code dataset.graph_json} and the controller reads straight back into
 * {@code SourceGraphResponseDto}. That round-trip is why the component names here are part of
 * the wire contract and not free to rename — {@code SourceGraphSerializerContractTest} pins them.
 */
@NullMarked
public class SourceGraphSerializer {

    private static final String XSD_NS  = "http://www.w3.org/2001/XMLSchema#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDF_PROPERTY = "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property";
    private static final String RDF_CONTAINER_MEMBERSHIP = "http://www.w3.org/2000/01/rdf-schema#ContainerMembershipProperty";
    private static final String RDFS_CLASS  = "http://www.w3.org/2000/01/rdf-schema#Class";
    private static final String RDFS_SUBCLASS_OF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String RDFS_DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain";
    private static final String RDFS_RANGE  = "http://www.w3.org/2000/01/rdf-schema#range";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Component names are the JSON field names the frontend reads. */
    public record GraphNode(String id, @Nullable String label, String iri, String type) {}

    /** Component names are the JSON field names the frontend reads. */
    public record GraphEdge(String source, String target, String label) {}

    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {}

    /**
     * Traverse {@code model} into nodes and edges.
     *
     * @param includeSubClassOf when true, {@code rdfs:subClassOf} triples are emitted as edges.
     *                          The integrated schema view needs them to show class hierarchies;
     *                          a bootstrapped source graph has none worth drawing, and the cached
     *                          {@link #serialize} JSON is built without them, so the source view
     *                          must stay false or the cached and uncached paths would disagree.
     */
    public static Graph build(Model model, boolean includeSubClassOf) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();

        StmtIterator it = model.listStatements();
        while (it.hasNext()) {
            Statement stmt = it.next();
            if (!stmt.getSubject().isURIResource()) continue;

            String subjectIri = stmt.getSubject().getURI();
            String predIri    = stmt.getPredicate().getURI();
            RDFNode obj       = stmt.getObject();

            nodes.computeIfAbsent(subjectIri, iri -> node(iri, "class"));

            // rdf:type triples refine the node type
            if (RDF_TYPE.equals(predIri) && obj.isURIResource()) {
                String typeIri = obj.asResource().getURI();
                String refined = switch (typeIri) {
                    case RDFS_CLASS -> "class";
                    case RDF_PROPERTY -> "datatype";
                    case RDF_CONTAINER_MEMBERSHIP -> "object";
                    default -> null;
                };
                if (refined != null) {
                    nodes.computeIfPresent(subjectIri, (iri, n) -> retype(n, refined));
                }
            }

            // domain / range -> edges
            if ((RDFS_DOMAIN.equals(predIri) || RDFS_RANGE.equals(predIri)) && obj.isURIResource()) {
                String targetIri = obj.asResource().getURI();
                if (targetIri.startsWith(XSD_NS)) {
                    // Compact xsd type node
                    nodes.computeIfAbsent(targetIri, iri ->
                            new GraphNode(iri, "xsd:" + iri.substring(XSD_NS.length()), iri, "xsd"));
                } else {
                    nodes.computeIfAbsent(targetIri, iri -> node(iri, "class"));
                }
                edges.add(new GraphEdge(subjectIri, targetIri, Names.localName(predIri)));
            }

            if (includeSubClassOf && RDFS_SUBCLASS_OF.equals(predIri) && obj.isURIResource()) {
                String targetIri = obj.asResource().getURI();
                nodes.computeIfAbsent(targetIri, iri -> node(iri, "class"));
                edges.add(new GraphEdge(subjectIri, targetIri, "subClassOf"));
            }
        }

        return new Graph(List.copyOf(nodes.values()), edges.stream().distinct().toList());
    }

    /**
     * The whole source-graph response as JSON, for caching on {@code dataset.graph_json}.
     * Its field names must stay identical to {@code GraphDtos.SourceGraphResponseDto}'s
     * components — the controller reads this string straight back into that record.
     */
    public static String serialize(String datasetId, Model model) {
        Graph graph = build(model, false);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("datasetId", datasetId);
        response.put("graphUri", "urn:aran:source:" + datasetId);
        response.put("tripleCount", model.size());
        response.put("empty", model.isEmpty());
        response.put("nodes", graph.nodes());
        response.put("edges", graph.edges());

        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize source graph", e);
        }
    }

    private static GraphNode node(String iri, String type) {
        return new GraphNode(iri, Names.localName(iri), iri, type);
    }

    private static GraphNode retype(GraphNode n, String type) {
        return type.equals(n.type()) ? n : new GraphNode(n.id(), n.label(), n.iri(), type);
    }
}

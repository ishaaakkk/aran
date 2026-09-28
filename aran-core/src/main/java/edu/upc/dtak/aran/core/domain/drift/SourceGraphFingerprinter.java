package edu.upc.dtak.aran.core.domain.drift;

import java.util.ArrayList;
import java.util.List;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Reads a {@link SchemaFingerprint} out of a bootstrapped source graph.
 *
 * <p>Works on any {@code Model} carrying the bootstrapping vocabulary, which is what makes drift
 * checking non-destructive: the candidate graph produced by introspecting a new snapshot can be
 * fingerprinted in memory and compared before anything is written to Fuseki.
 */
@NullMarked
public final class SourceGraphFingerprinter {

    /**
     * The bootstrapping vocabulary namespace.
     *
     * <p>Duplicated from {@code WrapperVocab.NS} because {@code aran-core} must not depend on an
     * operator module. {@code WrapperVocabMatchesCoreTest} in {@code aran-operators/bootstrapping}
     * asserts the two are equal — the module that can see both owns the check, so a change to
     * either side fails a build rather than silently producing empty fingerprints.
     */
    public static final String NX_NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";

    private static final String PREFIXES = """
            PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX nx:   <%s>
            """.formatted(NX_NS);

    private SourceGraphFingerprinter() {}

    /**
     * Project a source graph onto its comparable shape.
     *
     * <p>Only classes carrying both {@code nx:format} and {@code nx:iterator} are included: those
     * are the ones bootstrapping minted, and a class without an iterator has no identity to key a
     * diff on. Likewise a property without {@code nx:sourceRef} does not correspond to anything
     * in the source, so it cannot drift.
     */
    public static SchemaFingerprint of(Model graph, String datasetId) {
        List<SchemaFingerprint.ClassShape> classes = new ArrayList<>();
        String classQuery = PREFIXES + """
                SELECT ?cls ?format ?iterator WHERE {
                  ?cls a rdfs:Class ;
                       nx:format ?format ;
                       nx:iterator ?iterator .
                }
                """;
        try (QueryExecution qx = QueryExecutionFactory.create(classQuery, graph)) {
            ResultSet rs = qx.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                String classIri = s.getResource("cls").getURI();
                classes.add(new SchemaFingerprint.ClassShape(
                        classIri,
                        s.getLiteral("format").getString(),
                        s.getLiteral("iterator").getString(),
                        propertiesOf(graph, classIri)));
            }
        }
        return new SchemaFingerprint(datasetId, classes);
    }

    private static List<SchemaFingerprint.PropertyShape> propertiesOf(Model graph, String classIri) {
        String propertyQuery = PREFIXES + """
                SELECT ?prop ?ref ?label ?range WHERE {
                  ?prop a rdf:Property ;
                        rdfs:domain <%s> ;
                        nx:sourceRef ?ref .
                  OPTIONAL { ?prop rdfs:label ?label }
                  OPTIONAL { ?prop rdfs:range ?range }
                }
                """.formatted(classIri);
        List<SchemaFingerprint.PropertyShape> out = new ArrayList<>();
        try (QueryExecution qx = QueryExecutionFactory.create(propertyQuery, graph)) {
            ResultSet rs = qx.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                String ref = s.getLiteral("ref").getString();
                out.add(new SchemaFingerprint.PropertyShape(
                        s.getResource("prop").getURI(),
                        s.contains("label") ? s.getLiteral("label").getString() : ref,
                        ref,
                        uriOrNull(s.get("range"))));
            }
        }
        return out;
    }

    private static @Nullable String uriOrNull(@Nullable RDFNode node) {
        return node != null && node.isURIResource() ? node.asResource().getURI() : null;
    }
}

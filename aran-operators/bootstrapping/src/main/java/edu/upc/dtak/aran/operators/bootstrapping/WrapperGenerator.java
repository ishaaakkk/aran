package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates DuckDB-compatible SQL wrappers from a bootstrapped schema model
 * containing {@link WrapperVocab} metadata.
 *
 * <p>{@link #attachPerClassWrappers(Model)} writes one {@link WrapperVocab#WRAPPER_SQL}
 * triple per {@code rdfs:Class} back into the model. Each wrapper produces exactly the
 * tuples of its class, with columns aliased to property IRIs and the source FROM target
 * left as the {@code ${SNAPSHOT}} placeholder. This is the canonical representation the
 * query rewriter consumes.
 *
 * <p>A second, single-string {@code generateWrapper} once fed {@code Dataset.wrapper} — the
 * display-only third "wrapper" field dropped in {@code V27__drop_dataset_wrapper.sql}. It was
 * deleted with that field's last caller (audit SCHEMA_BOOTSTRAPPING-8).
 */
@NullMarked
public final class WrapperGenerator {

    private static final Logger log = LoggerFactory.getLogger(WrapperGenerator.class);

    private WrapperGenerator() {}

    /**
     * Compute a per-class wrapper for every {@code rdfs:Class} in the model and write
     * it back as a {@link WrapperVocab#WRAPPER_SQL} triple on that class. Idempotent:
     * existing wrapperSql triples are cleared first.
     *
     * @return number of wrappers attached
     */
    public static int attachPerClassWrappers(Model model) {
        // Clear any prior wrapperSql triples (idempotent re-bootstrap).
        model.removeAll(null, WrapperVocab.WRAPPER_SQL, null);

        List<ClassInfo> classes = classesOf(model);
        int n = 0;
        for (ClassInfo c : classes) {
            try {
                String sql = WrapperSqlBuilder.buildForClass(model, c.iri, c.shape(), c.iterator);
                if (sql != null) {
                    Resource cls = model.createResource(c.iri);
                    model.add(cls, WrapperVocab.WRAPPER_SQL, sql);
                    n++;
                }
            } catch (Exception e) {
                log.warn("Failed to build wrapper for class {} (iterator {})", c.iri, c.iterator, e);
            }
        }
        return n;
    }

    // ─── Class enumeration ──────────────────────────────────────────────────

    /**
     * @param shapeName the class's explicit {@code nx:shape}, or {@code null} on a graph written
     *                  before the shape existed (2026-09-07) — see {@link #shape()}
     */
    private record ClassInfo(String iri, String format, String iterator, String shapeName) {
        /**
         * The explicit shape when the graph carries one. Without one — a dataset bootstrapped
         * before {@code nx:shape} existed and never re-bootstrapped — the old inference applies,
         * narrowed to the one case it was ever right about: a JSON document. Every other format
         * is flat. This is the legacy branch, not the rule; the rule is that a connector says.
         */
        WrapperVocab.Shape shape() {
            if (shapeName != null) {
                return WrapperVocab.Shape.valueOf(shapeName);
            }
            return "JSON".equalsIgnoreCase(format)
                    ? WrapperVocab.Shape.DOCUMENT : WrapperVocab.Shape.FLAT;
        }
    }

    private static List<ClassInfo> classesOf(Model model) {
        String sparql =
            "PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
            "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
            "PREFIX nx:   <" + WrapperVocab.NS + ">\n" +
            "SELECT ?cls ?format ?iterator ?shape WHERE {\n" +
            "  ?cls a rdfs:Class ;\n" +
            "       nx:format ?format ;\n" +
            "       nx:iterator ?iterator .\n" +
            "  OPTIONAL { ?cls nx:shape ?shape }\n" +
            "}";
        List<ClassInfo> out = new ArrayList<>();
        try (QueryExecution qx = QueryExecutionFactory.create(sparql, model)) {
            ResultSet rs = qx.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                out.add(new ClassInfo(
                        s.getResource("cls").getURI(),
                        s.getLiteral("format").getString(),
                        s.getLiteral("iterator").getString(),
                        s.contains("shape") ? s.getLiteral("shape").getString() : null));
            }
        }
        return out;
    }
}

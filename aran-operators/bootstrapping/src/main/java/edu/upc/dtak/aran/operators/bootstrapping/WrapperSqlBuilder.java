package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds per-class wrapper SQL from a bootstrapped schema model.
 *
 * <p>Unlike the original {@code WrapperGenerator} (which emitted a single flat
 * SELECT collapsing every leaf in the JSON tree into one denormalised projection),
 * this builder emits <b>one wrapper per {@code rdfs:Class}</b> in the model.
 * Each wrapper:
 * <ul>
 *   <li>produces exactly the tuples of that class (one row per instance),</li>
 *   <li>aliases each property as its full property IRI (stable across sources,
 *       unlike labels which collide), and</li>
 *   <li>uses the literal placeholder {@link WrapperVocab#SNAPSHOT_PLACEHOLDER}
 *       in its FROM clause — substituted by callers (preview / executor).</li>
 * </ul>
 *
 * <p>For JSON, the class's iterator (e.g. {@code "$.artworks[*].creators[*]"})
 * is parsed into a LATERAL UNNEST chain. For CSV, the iterator is the literal
 * {@code "row"} and no UNNEST is needed.
 *
 * <p>The builder is invoked by {@link WrapperGenerator#attachPerClassWrappers}
 * which writes the SQL strings back into the model as {@link WrapperVocab#WRAPPER_SQL}
 * triples — making the schema graph self-describing and carrying the wrappers
 * through the schema-integration union without special handling.
 */
@NullMarked
final class WrapperSqlBuilder {

    private WrapperSqlBuilder() {}

    /**
     * Build one wrapper SQL string for the given source class.
     *
     * @param model    the bootstrapped schema (must contain class + properties)
     * @param classIri the IRI of the class to wrap
     * @param shape    the class's record shape — {@link WrapperVocab.Shape#FLAT} projects one
     *                 column per property, {@link WrapperVocab.Shape#DOCUMENT} walks the iterator
     * @param iterator the iterator path (JSONPath for a document; the relation to read otherwise —
     *                 ignored for a flat class, whose projection needs no path)
     * @return a SELECT statement with {@code ${SNAPSHOT}} as the source-table placeholder,
     *         or {@code null} if the class has no properties (degenerate)
     */
    static String buildForClass(Model model, String classIri, WrapperVocab.Shape shape,
                                String iterator) {
        List<LeafProperty> props = leafPropertiesOf(model, classIri);
        if (props.isEmpty()) return null;

        // The shape is the connector's explicit statement (nx:shape), not something read off the
        // format. This used to be a closed allowlist — CSV, PARQUET, JDBC flat, everything else a
        // document — and each kind that was not literally one of those three got a JSONPath
        // wrapper: JDBC's table name read as a struct field (V45), then Iceberg's "db.researchers"
        // split into s."db"."researchers"."col" (S4-1), which binds against nothing in a scan's
        // output. A format names a reader; whether the rows are flat is a separate fact, and the
        // safe default for a shape nobody declared is the one that cannot invent struct paths.
        return switch (shape) {
            case FLAT -> buildFlat(props);
            case DOCUMENT -> buildJson(props, iterator);
        };
    }

    // ─── flat, row-based sources (CSV / PARQUET / JDBC) ──────────────────────

    private static String buildFlat(List<LeafProperty> props) {
        // One row per record, properties map 1:1 to columns.
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < props.size(); i++) {
            if (i > 0) sql.append(", ");
            LeafProperty p = props.get(i);
            sql.append(quoteId(p.sourceRef))
               .append(" AS ").append(quoteId(p.propertyIri));
        }
        sql.append(" FROM ").append(WrapperVocab.SNAPSHOT_PLACEHOLDER);
        return sql.toString();
    }

    /**
     * JSON wrapper: parse the iterator into LATERAL UNNEST chain + struct-field accesses,
     * then project leaf properties off the resulting row expression.
     *
     * <p>Examples:
     * <pre>
     * iterator             → FROM clause                                             rowExpr
     * "$"                  → FROM ${SNAPSHOT} s                                      s
     * "$[*]"               → FROM ${SNAPSHOT} s                                      s   (read_json_auto unrolls arrays)
     * "$.creator"          → FROM ${SNAPSHOT} s                                      s.creator
     * "$.artworks[*]"      → FROM ${SNAPSHOT} s, UNNEST(s.artworks) AS u1(_r1)       _r1
     * "$.artworks[*].cr"   → FROM ${SNAPSHOT} s, UNNEST(s.artworks) AS u1(_r1)       _r1.cr
     * "$.foo.bar[*].baz[*]"→ FROM ${SNAPSHOT} s, UNNEST(s.foo.bar) AS u1(_r1),       _r2
     *                              UNNEST(_r1.baz) AS u2(_r2)
     * </pre>
     */
    private static String buildJson(List<LeafProperty> props, String iterator) {
        IteratorParse parsed = parseIterator(iterator);

        // For each array-of-primitives property, add a UNNEST lateral clause and map
        // sourceRef → unnested row alias so the SELECT can reference it directly.
        List<String> allLaterals = new java.util.ArrayList<>(parsed.lateralClauses);
        java.util.Map<String, String> arrayAliases = new java.util.LinkedHashMap<>();
        int aliasCounter = parsed.lateralClauses.size();
        // S4-2, the builder's own refusal. JsonBootstrap refuses this shape first; this is the
        // guard for a graph that reached the builder some other way. Two UNNEST laterals over one
        // row are a cross join in DuckDB — every scalar and every aggregate over the class is
        // multiplied by the product of the two lengths — and no wrapper is better than that one.
        List<String> arrays = props.stream().map(p -> p.sourceRef)
                .filter(r -> r.endsWith("[*]")).distinct().toList();
        if (arrays.size() > 1) {
            throw new IllegalStateException("a class with " + arrays.size()
                    + " array-of-primitive properties " + arrays + " cannot be wrapped: unnesting"
                    + " more than one multiplies its rows (a cross join). See S4-2.");
        }
        for (LeafProperty p : props) {
            if (p.sourceRef.endsWith("[*]") && !arrayAliases.containsKey(p.sourceRef)) {
                aliasCounter++;
                String rowAlias = "_r" + aliasCounter;
                String field = p.sourceRef.substring(0, p.sourceRef.length() - 3);
                allLaterals.add("UNNEST(" + parsed.rowExpr + "." + quoteId(field)
                        + ") AS u" + aliasCounter + "(" + rowAlias + ")");
                arrayAliases.put(p.sourceRef, rowAlias);
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < props.size(); i++) {
            if (i > 0) sql.append(", ");
            LeafProperty p = props.get(i);
            String projection = p.sourceRef.endsWith("[*]")
                    ? arrayAliases.get(p.sourceRef)
                    : jsonProjection(parsed.rowExpr, p.sourceRef);
            sql.append(projection).append(" AS ").append(quoteId(p.propertyIri));
        }
        sql.append(" FROM ").append(WrapperVocab.SNAPSHOT_PLACEHOLDER).append(" s");
        for (String clause : allLaterals) {
            sql.append(", ").append(clause);
        }
        return sql.toString();
    }

    private static String jsonProjection(String rowExpr, String sourceRef) {
        return rowExpr + "." + quoteId(sourceRef);
    }

    /**
     * Parse a JSONPath-ish iterator into a row expression and the LATERAL clauses
     * needed to reach it. Grammar handled:
     *   path  := '$' segment*
     *   segment := '.' key            (struct-field access)
     *            | '.' key '[*]'      (struct-field access then list-unnest)
     *            | '[*]'              (root-array unnest — no-op for read_json_auto)
     */
    static IteratorParse parseIterator(String iterator) {
        List<String> lateral = new ArrayList<>();
        String currentRowExpr = "s";
        int aliasCounter = 0;

        String s = iterator;
        if (s == null || s.isEmpty() || s.equals("$") || s.equals("$[*]")) {
            return new IteratorParse(currentRowExpr, lateral);
        }
        if (s.startsWith("$[*].")) s = s.substring(5);
        else if (s.startsWith("$[*]")) s = s.substring(4);
        else if (s.startsWith("$.")) s = s.substring(2);
        else if (s.startsWith("$")) s = s.substring(1);
        if (s.startsWith(".")) s = s.substring(1);

        for (String segment : s.split("\\.")) {
            if (segment.endsWith("[*]")) {
                String field = segment.substring(0, segment.length() - 3);
                aliasCounter++;
                String alias = "_r" + aliasCounter;
                String unnestArg = currentRowExpr + "." + quoteId(field);
                lateral.add("UNNEST(" + unnestArg + ") AS u" + aliasCounter + "(" + alias + ")");
                currentRowExpr = alias;
            } else {
                currentRowExpr = currentRowExpr + "." + quoteId(segment);
            }
        }
        return new IteratorParse(currentRowExpr, lateral);
    }

    /** Result of parsing an iterator: the row-expression to project off, and the LATERAL clauses to add. */
    record IteratorParse(String rowExpr, List<String> lateralClauses) {}

    /**
     * Find every leaf datatype-property of {@code classIri}: properties whose domain
     * is this class and whose range is an xsd:* (not another class). Object properties
     * that point to other source classes are excluded — those become joins, not columns.
     */
    private static List<LeafProperty> leafPropertiesOf(Model model, String classIri) {
        String sparql =
            "PREFIX rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
            "PREFIX rdfs:  <http://www.w3.org/2000/01/rdf-schema#>\n" +
            "PREFIX xsd:   <http://www.w3.org/2001/XMLSchema#>\n" +
            "PREFIX nx:    <" + WrapperVocab.NS + ">\n" +
            "SELECT ?propIri ?sourceRef ?range WHERE {\n" +
            "  ?propIri rdfs:domain <" + classIri + "> ;\n" +
            "           nx:sourceRef ?sourceRef ;\n" +
            "           rdfs:range ?range .\n" +
            "  FILTER ( STRSTARTS(STR(?range), STR(xsd:)) )\n" +   // leaf = xsd-typed
            "}";

        List<LeafProperty> out = new ArrayList<>();
        try (QueryExecution qx = QueryExecutionFactory.create(sparql, model)) {
            ResultSet rs = qx.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                out.add(new LeafProperty(
                        s.getResource("propIri").getURI(),
                        s.getLiteral("sourceRef").getString()));
            }
        }
        return out;
    }

    record LeafProperty(String propertyIri, String sourceRef) {}

    /**
     * Double-quote an SQL identifier (column / alias) and escape embedded quotes.
     * Property IRIs contain colons and slashes, so they MUST be quoted in DuckDB.
     */
    private static String quoteId(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}

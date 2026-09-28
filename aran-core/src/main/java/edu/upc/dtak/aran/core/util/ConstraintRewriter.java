package edu.upc.dtak.aran.core.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rewrites a denial-constraint expression from the names a user authors it with to the names the
 * engine's views actually expose.
 *
 * <p>A {@code ModelConstraint} is written against an integrated class's global-property
 * <em>local names</em> — {@code h_index >= 0} — because that is what the workbench and the
 * catalog show. Every view the engine builds for that class ({@code u_}, {@code int_},
 * {@code q_}) names its columns by the <em>full global property IRI</em>, so the expression has
 * to be re-pointed before it can be spliced into a {@code WHERE} clause or parsed into a Calcite
 * {@code RexNode}.
 *
 * <p><b>Why this lives in core rather than next to its first caller.</b> There are now two
 * consumers of the same rewrite and they must not disagree: {@code DuckDbExecutor.buildDdl}
 * splices the result into the view bodies that <em>enforce</em> the constraint, and
 * {@code OqlPlanner} parses the result into a pulled-up predicate the cost model and
 * {@code FILTER_REDUCE_EXPRESSIONS} <em>reason</em> with. A drifted second copy would let the
 * planner believe a predicate the views do not enforce — which is not a worse estimate, it is a
 * wrong answer. Same rule, and the same reason, as {@link Names}.
 */
public final class ConstraintRewriter {

    private ConstraintRewriter() {
    }

    /**
     * Local property name → full global-property IRI for one integrated class's column vector —
     * the inverse of the display mapping the catalog exposes.
     *
     * <p>First IRI wins on a local-name collision ({@code putIfAbsent}), which is deliberate and
     * is why this is not {@code OqlPlanner.localToGlobal}: that one throws on a collision, and
     * throwing here would fail a query over a class whose constraint never mentions the
     * ambiguous name (F12). Iteration order of {@code globalPropertyIris} therefore decides the
     * winner — callers pass the class's sorted global column vector, so the choice is stable.
     */
    public static Map<String, String> localNameToIri(Collection<String> globalPropertyIris) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String iri : globalPropertyIris) {
            map.putIfAbsent(Names.localName(iri), iri);
        }
        return map;
    }

    /**
     * Rewrite a constraint predicate so its bare identifiers reference the IRI-named columns.
     * Every identifier token that matches a known global-property local name is replaced by the
     * quoted full IRI; string literals, already-quoted identifiers, SQL keywords and function
     * names pass through untouched. Deliberately a small hand-tokeniser (no SQL parser) — v1
     * constraints are simple boolean predicates.
     */
    public static String rewriteToIriColumns(String expression, Map<String, String> localNameToIri) {
        StringBuilder out = new StringBuilder(expression.length() + 32);
        int i = 0, n = expression.length();
        while (i < n) {
            char ch = expression.charAt(i);
            if (ch == '\'') {                       // single-quoted string literal
                int start = i++;
                while (i < n) {
                    if (expression.charAt(i) == '\'') {
                        if (i + 1 < n && expression.charAt(i + 1) == '\'') { i += 2; continue; } // '' escape
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(expression, start, i);
            } else if (ch == '"') {                 // already-quoted identifier — leave as-is
                int start = i++;
                while (i < n && expression.charAt(i) != '"') i++;
                if (i < n) i++;                     // consume closing quote
                out.append(expression, start, i);
            } else if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_')) i++;
                String ident = expression.substring(start, i);
                String iri = localNameToIri.get(ident);
                if (iri != null) {
                    out.append('"').append(iri.replace("\"", "\"\"")).append('"');
                } else {
                    out.append(ident);              // keyword / function / unknown — untouched
                }
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }
}

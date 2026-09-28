package edu.upc.dtak.aran.operators.schemaintegration;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Suggests datatype-property alignments between two datasets using:
 * <ol>
 *   <li><b>Linguistic score</b> — Jaro-Winkler similarity on normalised column names,
 *       boosted when the two names share a meaningful token (e.g. "author" in
 *       {@code author_id} and {@code authorId}). Handles camelCase, snake_case and
 *       acronyms.</li>
 *   <li><b>Value-overlap score</b> — Jaccard containment on the top-10 common values
 *       recorded by the profiling operator, normalised to lowercase. High overlap
 *       strongly indicates the columns represent the same real-world attribute.</li>
 * </ol>
 *
 * <p>Combined score: {@code 0.4 × linguistic + 0.6 × valueOverlap}.
 * Only pairs whose combined score meets the caller-supplied threshold are returned.
 */
@NullMarked
final class AlignmentSuggester {

    record Candidate(
            String iriA,
            String iriB,
            String columnA,
            String columnB,
            String label,
            double linguisticScore,
            double valueScore,
            double score
    ) {}

    private AlignmentSuggester() {}

    /**
     * Returns candidates sorted descending by combined score, filtered to those ≥ threshold.
     *
     * @param dsA       left dataset (must have column profiles loaded)
     * @param dsB       right dataset (must have column profiles loaded)
     * @param propIriA  column name → property IRI map for dsA (from its source graph)
     * @param propIriB  column name → property IRI map for dsB (from its source graph)
     * @param threshold minimum combined score [0,1] to include a candidate
     */
    static List<Candidate> suggest(
            Dataset dsA, Dataset dsB,
            Map<String, String> propIriA, Map<String, String> propIriB,
            double threshold) {

        List<Candidate> results = new ArrayList<>();

        // SNAPSHOT profiles only (ColumnProfile.measuredOverSnapshotRows) — the same filter set
        // join discovery uses, for the same reason (audit JOIN_DISCOVERY-1). Sixty per cent of the
        // combined score is value overlap over commonValues, which a HARVESTED row leaves empty —
        // so such a row can never raise a candidate's score, only add a worse-scored duplicate of
        // one, or invent a name-similarity-only candidate for a column nothing was measured on. The
        // isEmpty() guard immediately below does not exclude it: that field is about the COLUMN's
        // contents, and HarvestedColumnProfiles has no value for it, so it writes a flat false.
        // A WRAPPER_OUTPUT row is excluded by the same predicate rather than incidentally by the
        // propIri lookup below — its columns are named by property IRI, which happens not to be a
        // key of that map today; making the exclusion explicit stops that accident from mattering.
        //
        // Newly URGENT since Flyway V36 (V40/V44) let a SNAPSHOT and a HARVESTED row coexist for one
        // column: a materialized relational dataset then offers each column twice on each side, and
        // nothing here deduplicates (iriA, iriB), so one alignment came out up to four times with
        // three different meaningless scores.
        for (ColumnProfile colA : dsA.columns()) {
            if (!colA.measuredOverSnapshotRows()) continue;
            if (colA.isEmpty()) continue;
            String iriA = propIriA.get(colA.columnName());
            if (iriA == null) continue;

            for (ColumnProfile colB : dsB.columns()) {
                if (!colB.measuredOverSnapshotRows()) continue;
                if (colB.isEmpty()) continue;
                String iriB = propIriB.get(colB.columnName());
                if (iriB == null) continue;

                double ling  = linguisticScore(colA.columnName(), colB.columnName());
                double val   = valueOverlapScore(colA.commonValues(), colB.commonValues());
                double score = 0.4 * ling + 0.6 * val;

                if (score >= threshold) {
                    String label = suggestedLabel(colA.columnName(), colB.columnName());
                    results.add(new Candidate(iriA, iriB,
                            colA.columnName(), colB.columnName(),
                            label, ling, val, score));
                }
            }
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    // ── Linguistic score ──────────────────────────────────────────────────────

    /**
     * Combined linguistic score: 60% Jaro-Winkler on normalised names +
     * 40% token-overlap bonus (binary: 1.0 if any meaningful shared token, 0 otherwise).
     * Tokens shorter than 3 chars are ignored to prevent "id" matching everything.
     */
    static double linguisticScore(String nameA, String nameB) {
        double jw     = jaroWinkler(normalise(nameA), normalise(nameB));
        double bonus  = sharedMeaningfulToken(nameA, nameB) ? 1.0 : 0.0;
        return 0.6 * jw + 0.4 * bonus;
    }

    /** Removes non-alphanumeric chars and lowercases (merges tokens for JW comparison). */
    private static String normalise(String name) {
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /** Splits camelCase, snake_case, kebab-case into lowercase tokens. */
    private static Set<String> tokenise(String name) {
        String[] parts = name.split("[^A-Za-z0-9]+");
        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            // Split camelCase transitions: lowerToUpper and UpperToUpperLower
            String[] camel = part.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
            for (String t : camel) {
                if (!t.isEmpty()) tokens.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static boolean sharedMeaningfulToken(String nameA, String nameB) {
        Set<String> tA = tokenise(nameA);
        Set<String> tB = tokenise(nameB);
        tA.retainAll(tB);
        return tA.stream().anyMatch(t -> t.length() >= 3);
    }

    // ── Jaro-Winkler ─────────────────────────────────────────────────────────

    static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        int len1 = s1.length(), len2 = s2.length();
        int matchWindow = Math.max(len1, len2) / 2 - 1;
        if (matchWindow < 0) matchWindow = 0;

        boolean[] matched1 = new boolean[len1];
        boolean[] matched2 = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int lo = Math.max(0, i - matchWindow);
            int hi = Math.min(i + matchWindow + 1, len2);
            for (int j = lo; j < hi; j++) {
                if (!matched2[j] && s1.charAt(i) == s2.charAt(j)) {
                    matched1[i] = true;
                    matched2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) return 0.0;

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (matched1[i]) {
                while (!matched2[k]) k++;
                if (s1.charAt(i) != s2.charAt(k)) transpositions++;
                k++;
            }
        }

        double jaro = ((double) matches / len1
                + (double) matches / len2
                + (matches - transpositions / 2.0) / matches) / 3.0;

        // Winkler prefix bonus (up to 4 chars)
        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(len1, len2));
        while (prefix < maxPrefix && s1.charAt(prefix) == s2.charAt(prefix)) prefix++;

        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    // ── Value-overlap score ───────────────────────────────────────────────────

    /**
     * Jaccard similarity on top-10 common-value sets, after lowercasing.
     * Returns 0 when either column has no profiled values.
     */
    static double valueOverlapScore(List<String> valuesA, List<String> valuesB) {
        if (valuesA.isEmpty() || valuesB.isEmpty()) return 0.0;

        Set<String> setA = new HashSet<>();
        for (String v : valuesA) setA.add(v.toLowerCase(Locale.ROOT).strip());
        Set<String> setB = new HashSet<>();
        for (String v : valuesB) setB.add(v.toLowerCase(Locale.ROOT).strip());

        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    // ── Label suggestion ──────────────────────────────────────────────────────

    /**
     * Produces a short integrated label from the two column names.
     * Prefers the shorter / more general token; falls back to nameA.
     */
    static String suggestedLabel(String nameA, String nameB) {
        String nA = normalise(nameA);
        String nB = normalise(nameB);
        // Prefer the shorter name as the more general label, but never return an empty one
        // while the other side still has content. normalise() strips everything that is not
        // alphanumeric, so a name like "___" or "%" reduces to "" — and "" is always the
        // shorter string, so the length comparison alone would pick it every time. The
        // empty-check used to guard only the nB branch, which left
        // suggestedLabel("___", "title") returning "" and the alignment unlabelled.
        if (nA.isEmpty()) return nB;
        if (nB.isEmpty()) return nA;
        return nA.length() <= nB.length() ? nA : nB;
    }

    // ── Property IRI map from source graph ────────────────────────────────────

    /**
     * Builds a map from column name → property IRI by scanning the source graph model
     * for all {@code rdf:Property} resources annotated with {@code rdfs:label}.
     */
    static Map<String, String> propertyIriMap(org.apache.jena.rdf.model.Model sourceGraph) {
        Map<String, String> map = new LinkedHashMap<>();
        org.apache.jena.rdf.model.StmtIterator it = sourceGraph.listStatements(
                null,
                org.apache.jena.vocabulary.RDF.type,
                org.apache.jena.vocabulary.RDF.Property);
        while (it.hasNext()) {
            org.apache.jena.rdf.model.Resource prop = it.next().getSubject();
            org.apache.jena.rdf.model.Statement labelStmt = prop.getProperty(org.apache.jena.vocabulary.RDFS.label);
            if (labelStmt != null && labelStmt.getObject().isLiteral()) {
                String label = labelStmt.getString();
                map.putIfAbsent(label, prop.getURI());
            }
        }
        return Collections.unmodifiableMap(map);
    }
}

package edu.upc.dtak.aran.core.domain.drift;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Compares two {@link SchemaFingerprint}s.
 *
 * <p>A pure function: fingerprints in, changes out. No I/O, no Spring, no graph — which is what
 * lets the interesting cases be tested exhaustively without a Docker daemon or a Fuseki.
 *
 * <p><b>Matching.</b> Classes match on iterator and properties on {@code sourceRef}, never on IRI
 * — see {@link SchemaFingerprint} for why. An IRI difference under a matched pair is therefore a
 * finding in its own right ({@link SchemaChangeKind#CLASS_IRI_CHANGED}) rather than a mismatch.
 */
@NullMarked
public final class SchemaDiff {

    /**
     * How much of the rename score comes from being the only candidate. Set so that an
     * unambiguous 1:1 pair clears {@link #RENAME_THRESHOLD} on its own: when a source loses
     * exactly one column and gains exactly one, "renamed" is a far better explanation than a
     * coincidental simultaneous drop and add.
     */
    private static final double SOLE_CANDIDATE_WEIGHT = 0.70;
    private static final double SAME_TYPE_WEIGHT = 0.15;
    private static final double NAME_SIMILARITY_WEIGHT = 0.15;

    /** Below this, a rename is not proposed and the pair is reported as a removal plus an addition. */
    static final double RENAME_THRESHOLD = 0.60;

    private SchemaDiff() {}

    /**
     * Every difference from {@code before} to {@code after}, ordered deterministically so a report
     * reads the same way twice.
     */
    public static List<SchemaChange> between(SchemaFingerprint before, SchemaFingerprint after) {
        List<SchemaChange> changes = new ArrayList<>();
        Map<String, SchemaFingerprint.ClassShape> oldByIterator = byIterator(before);
        Map<String, SchemaFingerprint.ClassShape> newByIterator = byIterator(after);

        for (var entry : oldByIterator.entrySet()) {
            SchemaFingerprint.ClassShape oldClass = entry.getValue();
            SchemaFingerprint.ClassShape newClass = newByIterator.get(entry.getKey());
            if (newClass == null) {
                changes.add(SchemaChange.classRemoved(
                        oldClass.iri(), oldClass.iterator(), oldClass.properties().size()));
            } else {
                diffMatchedClass(oldClass, newClass, changes);
            }
        }
        for (var entry : newByIterator.entrySet()) {
            if (!oldByIterator.containsKey(entry.getKey())) {
                SchemaFingerprint.ClassShape added = entry.getValue();
                changes.add(SchemaChange.classAdded(
                        added.iri(), added.iterator(), added.properties().size()));
            }
        }

        changes.sort(Comparator.comparing(SchemaChange::classIri)
                .thenComparing(c -> c.kind().ordinal())
                .thenComparing(c -> c.propertyIri() == null ? "" : c.propertyIri()));
        return List.copyOf(changes);
    }

    private static void diffMatchedClass(SchemaFingerprint.ClassShape oldClass,
                                         SchemaFingerprint.ClassShape newClass,
                                         List<SchemaChange> changes) {
        if (!oldClass.iri().equals(newClass.iri())) {
            changes.add(SchemaChange.classIriChanged(oldClass.iri(), newClass.iri(), oldClass.iterator()));
        }
        if (!oldClass.format().equals(newClass.format())) {
            changes.add(SchemaChange.formatChanged(oldClass.iri(), oldClass.format(), newClass.format()));
        }

        Map<String, SchemaFingerprint.PropertyShape> oldProps = bySourceRef(oldClass);
        Map<String, SchemaFingerprint.PropertyShape> newProps = bySourceRef(newClass);

        List<SchemaFingerprint.PropertyShape> removed = new ArrayList<>();
        for (var entry : oldProps.entrySet()) {
            SchemaFingerprint.PropertyShape survivor = newProps.get(entry.getKey());
            if (survivor == null) {
                removed.add(entry.getValue());
            } else if (!Objects.equals(entry.getValue().range(), survivor.range())) {
                changes.add(SchemaChange.typeChanged(oldClass.iri(), entry.getValue().iri(),
                        entry.getKey(), entry.getValue().range(), survivor.range()));
            }
        }
        List<SchemaFingerprint.PropertyShape> added = new ArrayList<>();
        for (var entry : newProps.entrySet()) {
            if (!oldProps.containsKey(entry.getKey())) {
                added.add(entry.getValue());
            }
        }

        matchRenames(oldClass.iri(), removed, added, changes);
    }

    /**
     * Pair departures with arrivals where the evidence supports a rename.
     *
     * <p><b>Only the unambiguous 1:1 case is proposed.</b> With one column gone and one arrived,
     * a rename is overwhelmingly the better explanation. With several of each, the evidence
     * available at this point cannot separate them: CSV bootstraps every column as
     * {@code xsd:string} so the declared type discriminates nothing, and profiles — which would
     * settle it, since a renamed column keeps its cardinality and value distribution — do not
     * exist for the candidate yet. Profiling runs <em>after</em> bootstrap
     * ({@code DatasetService.runBootstrapPipeline}), so at drift-check time the new side has none.
     * Reporting the drops and adds separately is the honest outcome; a guess here silently
     * rewires a mapping to the wrong column.
     */
    private static void matchRenames(String classIri,
                                     List<SchemaFingerprint.PropertyShape> removed,
                                     List<SchemaFingerprint.PropertyShape> added,
                                     List<SchemaChange> changes) {
        if (removed.size() == 1 && added.size() == 1) {
            SchemaFingerprint.PropertyShape from = removed.get(0);
            SchemaFingerprint.PropertyShape to = added.get(0);
            boolean sameType = Objects.equals(from.range(), to.range());
            double similarity = nameSimilarity(from.sourceRef(), to.sourceRef());
            double confidence = Math.min(1.0, SOLE_CANDIDATE_WEIGHT
                    + (sameType ? SAME_TYPE_WEIGHT : 0.0)
                    + NAME_SIMILARITY_WEIGHT * similarity);

            if (confidence >= RENAME_THRESHOLD) {
                changes.add(SchemaChange.columnRenamed(classIri, from.iri(), to.iri(),
                        from.sourceRef(), to.sourceRef(), confidence,
                        basis(sameType, similarity)));
                return;
            }
        }
        for (SchemaFingerprint.PropertyShape p : removed) {
            changes.add(SchemaChange.columnRemoved(classIri, p.iri(), p.sourceRef()));
        }
        for (SchemaFingerprint.PropertyShape p : added) {
            changes.add(SchemaChange.columnAdded(classIri, p.iri(), p.sourceRef()));
        }
    }

    private static String basis(boolean sameType, double similarity) {
        StringBuilder sb = new StringBuilder("the only column dropped and the only one added");
        sb.append(sameType ? ", same declared type" : ", but the declared type also changed");
        if (similarity >= 0.5) {
            sb.append(", similar name");
        }
        return sb.toString();
    }

    /**
     * Levenshtein similarity in [0,1], case-insensitive. Weak evidence on its own — {@code venue}
     * and {@code conference} share almost nothing yet are a perfectly ordinary rename — so it only
     * ever adjusts a score that is already carried by the 1:1 structure.
     */
    static double nameSimilarity(String a, String b) {
        String left = a.toLowerCase(Locale.ROOT);
        String right = b.toLowerCase(Locale.ROOT);
        int longest = Math.max(left.length(), right.length());
        if (longest == 0) {
            return 1.0;
        }
        return 1.0 - (double) editDistance(left, right) / longest;
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static Map<String, SchemaFingerprint.ClassShape> byIterator(SchemaFingerprint fingerprint) {
        Map<String, SchemaFingerprint.ClassShape> out = new LinkedHashMap<>();
        for (SchemaFingerprint.ClassShape c : fingerprint.classes()) {
            out.put(c.iterator(), c);
        }
        return out;
    }

    private static Map<String, SchemaFingerprint.PropertyShape> bySourceRef(SchemaFingerprint.ClassShape cls) {
        Map<String, SchemaFingerprint.PropertyShape> out = new LinkedHashMap<>();
        for (SchemaFingerprint.PropertyShape p : cls.properties()) {
            out.put(p.sourceRef(), p);
        }
        return out;
    }

    /** @return whether any change is present — the caller's cheap "is there drift?" question. */
    public static boolean isEmpty(@Nullable List<SchemaChange> changes) {
        return changes == null || changes.isEmpty();
    }
}

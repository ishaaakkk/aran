package edu.upc.dtak.aran.core.domain.drift;

import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Joins each {@link SchemaChange} against the mappings that depend on it, and assigns severity.
 *
 * <p>A pure function over changes and mappings — the caller loads both. This is the half that
 * turns a diff into a decision: without it every re-upload that adds a column looks the same as
 * one that drops a join key.
 *
 * <p><b>Whose mappings count.</b> Severity is driven by {@code ACCEPTED} mappings, because those
 * are the ones fed to compile and therefore the ones present in the active schema. Undecided
 * ({@code SUGGESTED}) mappings are still reported and still lift a finding off {@link
 * DriftSeverity#INFO}, since accepting one that points at a departed column just moves the
 * failure later. {@code REJECTED} mappings are ignored entirely — they are an audit trail, not a
 * dependency.
 */
@NullMarked
public final class DriftImpactAnalyzer {

    private DriftImpactAnalyzer() {}

    /** One impact per change, in the order the changes arrived. */
    public static List<DriftImpact> analyse(List<SchemaChange> changes, List<ModelMapping> mappings) {
        List<DriftImpact> out = new ArrayList<>(changes.size());
        for (SchemaChange change : changes) {
            List<DriftImpact.AffectedMapping> affected = affectedBy(change, mappings);
            out.add(new DriftImpact(change, severityOf(change, affected), affected,
                    reasonFor(change, affected)));
        }
        return List.copyOf(out);
    }

    /**
     * The mappings whose {@code sourceIri} or {@code targetIri} names an IRI this change moved.
     *
     * <p>For {@link SchemaChangeKind#CLASS_IRI_CHANGED} the match is by prefix, not equality: a
     * property IRI is minted as {@code <classIri>/<column>}
     * ({@code BootstrapEngine.sourcePropertyIri}), so re-minting a class IRI moves every property
     * beneath it in one stroke. Matching only the class IRI itself would report one affected
     * mapping where there are twenty.
     */
    private static List<DriftImpact.AffectedMapping> affectedBy(SchemaChange change,
                                                                List<ModelMapping> mappings) {
        List<DriftImpact.AffectedMapping> out = new ArrayList<>();
        for (ModelMapping mapping : mappings) {
            if (ModelMapping.REJECTED.equals(mapping.status())) {
                continue;
            }
            String matched = matchedIri(change, mapping);
            if (matched != null) {
                out.add(new DriftImpact.AffectedMapping(
                        mapping.id(), mapping.kind(), mapping.status(), matched));
            }
        }
        return out;
    }

    private static @org.jspecify.annotations.Nullable String matchedIri(SchemaChange change,
                                                                        ModelMapping mapping) {
        if (change.kind() == SchemaChangeKind.CLASS_IRI_CHANGED) {
            String prefix = change.classIri() + "/";
            for (String iri : List.of(mapping.sourceIri(), mapping.targetIri())) {
                if (iri.equals(change.classIri()) || iri.startsWith(prefix)) {
                    return iri;
                }
            }
            return null;
        }
        String target = change.propertyIri() != null ? change.propertyIri() : change.classIri();
        if (mapping.sourceIri().equals(target)) {
            return mapping.sourceIri();
        }
        return mapping.targetIri().equals(target) ? mapping.targetIri() : null;
    }

    private static DriftSeverity severityOf(SchemaChange change, List<DriftImpact.AffectedMapping> affected) {
        boolean anyAccepted = affected.stream()
                .anyMatch(a -> ModelMapping.ACCEPTED.equals(a.status()));
        boolean anyPending = !affected.isEmpty() && !anyAccepted;

        return switch (change.kind()) {
            // A vanished source class is never merely informational. The integrated class is a
            // UNION ALL over its members, so losing one silently returns fewer rows — the one
            // failure mode in this list that produces neither an error nor a visible symptom.
            case CLASS_REMOVED -> anyAccepted ? DriftSeverity.BREAKING : DriftSeverity.WARNING;

            case CLASS_IRI_CHANGED, COLUMN_REMOVED, COLUMN_RENAMED -> anyAccepted
                    ? DriftSeverity.BREAKING
                    : anyPending ? DriftSeverity.WARNING : DriftSeverity.INFO;

            // The column is still there and still resolves; only its meaning may have moved.
            case TYPE_CHANGED -> affected.isEmpty() ? DriftSeverity.INFO : DriftSeverity.WARNING;

            case COLUMN_ADDED, CLASS_ADDED, FORMAT_CHANGED -> DriftSeverity.INFO;
        };
    }

    private static String reasonFor(SchemaChange change, List<DriftImpact.AffectedMapping> affected) {
        if (affected.isEmpty()) {
            return switch (change.kind()) {
                case CLASS_REMOVED -> "No mapping references this class, but a source dropping out of an "
                        + "integrated class makes queries return fewer rows without any error.";
                case COLUMN_ADDED -> "Nothing to repair — this column is available to map when you want it.";
                case CLASS_ADDED -> "Nothing to repair — a new class is available to enrol.";
                default -> "No mapping depends on this.";
            };
        }

        long accepted = affected.stream().filter(a -> ModelMapping.ACCEPTED.equals(a.status())).count();
        long joins = affected.stream()
                .filter(a -> MappingKinds.JOIN.equals(a.kind()) && ModelMapping.ACCEPTED.equals(a.status()))
                .count();

        StringBuilder sb = new StringBuilder();
        if (accepted > 0) {
            sb.append(accepted).append(accepted == 1 ? " accepted mapping" : " accepted mappings")
                    .append(" depend on this");
            if (joins > 0) {
                sb.append(", ").append(joins == 1 ? "one of which is a join key" : "including " + joins
                                + " join keys")
                        .append(" — a join that cannot bind drops rows rather than raising an error");
            }
            sb.append('.');
        } else {
            sb.append(affected.size()).append(affected.size() == 1 ? " undecided mapping" : " undecided mappings")
                    .append(" point here; accepting one would fail.");
        }
        if (change.replacementIri() != null) {
            sb.append(" Repairable: the replacement IRI is known.");
        }
        return sb.toString();
    }
}

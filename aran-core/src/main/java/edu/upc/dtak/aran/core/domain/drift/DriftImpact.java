package edu.upc.dtak.aran.core.domain.drift;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A {@link SchemaChange} together with what it breaks.
 *
 * @param affected  the mappings referencing the IRI this change moved. Both the accepted ones
 *                  (which are compiled into the active schema, and therefore drive the severity)
 *                  and the undecided ones (which will fail when someone accepts them).
 * @param reason    why this severity, in a sentence — the thing a user reads before deciding
 */
@NullMarked
public record DriftImpact(
        SchemaChange change,
        DriftSeverity severity,
        List<AffectedMapping> affected,
        String reason) {

    public DriftImpact {
        affected = List.copyOf(affected);
    }

    /**
     * One mapping that points at an IRI this change moved.
     *
     * @param kind {@code class} | {@code datatype} | {@code object} | {@code join} —
     *             {@code join} is called out separately because a broken join key degrades
     *             results rather than raising an error
     */
    public record AffectedMapping(UUID mappingId, String kind, String status, String matchedIri) {}

    /** Whether a one-click repair could fix this — true only for renames with a known replacement. */
    public boolean repairable() {
        return change.replacementIri() != null && !affected.isEmpty();
    }
}

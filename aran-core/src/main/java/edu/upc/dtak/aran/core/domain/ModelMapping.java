package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A durable alignment in an {@link IntegrationModel} — the persistent form of the
 * {@code {iriA, iriB, label, type}} object {@code AlignmentsView} used to hold in
 * React state and POST inline to {@code integrate}.
 *
 * <p>Field mapping onto the operator's alignment payload (see {@link MappingKinds}):
 * {@code kind → type}, {@code sourceIri → iriA}, {@code targetIri → iriB},
 * {@code label → label}. For {@code kind=join} the label keeps the packed
 * {@code "label|relationship|direction"} form the operator parses.
 *
 * @param status   {@link #SUGGESTED} | {@link #ACCEPTED} | {@link #REJECTED};
 *                 only ACCEPTED rows are fed to compile
 * @param evidenceJson optional JSON blob of suggestion scores (from
 *                 {@code suggest-alignments}); {@code null} for manual mappings
 */
@NullMarked
public record ModelMapping(
        UUID id,
        UUID tenantId,
        UUID modelId,
        String kind,
        String sourceIri,
        String targetIri,
        @Nullable String label,
        String status,
        @Nullable String evidenceJson,
        @Nullable UUID author,
        @Nullable String comment,
        Instant createdAt
) {

    /** Machine-proposed, awaiting a human decision. */
    public static final String SUGGESTED = "SUGGESTED";
    /** A decision to include this alignment in compile. */
    public static final String ACCEPTED = "ACCEPTED";
    /** A decision to keep this alignment out of compile (retained for the audit trail). */
    public static final String REJECTED = "REJECTED";

    /** @return whether {@code status} is one of the three accepted lifecycle values. */
    public static boolean isValidStatus(String status) {
        return SUGGESTED.equals(status) || ACCEPTED.equals(status) || REJECTED.equals(status);
    }
}

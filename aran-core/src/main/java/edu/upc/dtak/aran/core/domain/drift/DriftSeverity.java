package edu.upc.dtak.aran.core.domain.drift;

import org.jspecify.annotations.NullMarked;

/**
 * How much a {@link SchemaChange} matters, once joined against what depends on it.
 *
 * <p>Severity belongs here rather than on the change itself because the same change has all three
 * answers depending on context: a dropped column nobody mapped is {@link #INFO}, and the identical
 * drop is {@link #BREAKING} when a join key rests on it. A drift check that skips this join is a
 * diff tool that cries wolf on every added column, and gets turned off.
 */
@NullMarked
public enum DriftSeverity {

    /** Worth knowing, nothing depends on it. New columns land here — an opportunity, not a problem. */
    INFO,

    /**
     * Queries keep working; what they return may have changed meaning, or something pending will
     * break when it is accepted.
     */
    WARNING,

    /** Something that is compiled into the active schema no longer resolves. */
    BREAKING;

    /** The more serious of two severities. */
    public DriftSeverity max(DriftSeverity other) {
        return compareTo(other) >= 0 ? this : other;
    }
}

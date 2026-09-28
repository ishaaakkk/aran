package edu.upc.dtak.aran.core.domain;

/**
 * Algorithm-execution state of an accepted mapping, tracked separately from the
 * editorial {@link ModelMapping#status()} (SUGGESTED|ACCEPTED|REJECTED).
 */
public enum MappingApplicationState {
    /** Accepted but not yet integrated into a promoted graph. */
    PENDING,
    /** Integrated into a promoted graph version. */
    APPLIED,
    /** Evaluated but its prerequisites were not met (e.g. domains not integrated). */
    DEFERRED
}

package edu.upc.dtak.aran.core.domain;

/** Lifecycle of an {@link IntegrationRun}. */
public enum IntegrationRunStatus {
    /** Started; the algorithm is executing. */
    RUNNING,
    /** Produced a draft graph version, awaiting promotion. */
    DRAFT,
    /** Its draft was promoted to the active graph. */
    PROMOTED,
    /** Failed before producing (or promoting) a draft. */
    FAILED,
    /**
     * Its draft was abandoned — a newer run for the same model started before this one was
     * promoted. Competing drafts branch off the same promoted baseline rather than forming a
     * sequence, so only the newest is promotable; a superseded run's mapping applications are
     * returned to {@link MappingApplicationState#PENDING} because they never became durable.
     */
    SUPERSEDED
}

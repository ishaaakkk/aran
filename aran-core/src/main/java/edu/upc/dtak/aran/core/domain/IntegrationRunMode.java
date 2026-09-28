package edu.upc.dtak.aran.core.domain;

/** How an {@link IntegrationRun} produced its integrated graph. */
public enum IntegrationRunMode {
    /** Full rebuild from every enrolled source (the {@code compile} path). */
    REBUILD,
    /** One source added on top of a promoted base graph. */
    INCREMENTAL
}

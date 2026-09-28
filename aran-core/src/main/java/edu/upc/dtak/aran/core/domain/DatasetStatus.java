package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

@NullMarked
public enum DatasetStatus {
    PENDING,
    RUNNING,
    READY,
    FAILED,
    CANCELLED,

    /**
     * The source has changed shape in a way that breaks something already compiled into the active
     * schema, and the change was <em>not</em> applied — the previous source graph is still in place
     * and queries still work.
     *
     * <p>Distinct from {@link #FAILED}: nothing went wrong, and there is nothing to retry. A human
     * has to decide whether to repair the affected mappings or accept the new shape.
     */
    DRIFTED
}

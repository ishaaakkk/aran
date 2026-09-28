package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * How a {@link DataProduct} serves its rows (Phase 5, V34).
 *
 * <ul>
 *   <li>{@code MATERIALIZED} — a pinned parquet snapshot in the lake, computed once by
 *       {@code product:materialize} and served until re-materialized. The only mode that existed
 *       before Phase 5; every product created before this field existed is backfilled to this
 *       value (V34's column default) and behaves exactly as it always did.</li>
 *   <li>{@code VIRTUAL} — no snapshot: the product has no materialize step at all, and a read is
 *       meant to expand its OQL through the live GAV pipeline instead, so it is always fresh and
 *       enforces whatever denial constraints are currently active. This value is recorded and
 *       persisted as of this task; the read-time expansion that gives it meaning is wired in a
 *       later phase-5 task.</li>
 * </ul>
 */
@NullMarked
public enum ProductAccessMode {
    MATERIALIZED,
    VIRTUAL
}

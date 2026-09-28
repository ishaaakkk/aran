package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * Lifecycle of a {@link DataProduct}.
 *
 * <ul>
 *   <li>{@code DRAFT} — created and validated, never materialized. A VIRTUAL product never
 *       stops here (Phase 5): it has nothing further to run, so creation carries it straight to
 *       READY — see {@link DataProduct#virtualReady}.</li>
 *   <li>{@code MATERIALIZING} — a materialize job is executing the product's OQL. MATERIALIZED
 *       products only; a VIRTUAL product has no materialize step to run.</li>
 *   <li>{@code READY} — servable, by either of two means {@link DataProduct#accessMode()}
 *       distinguishes: a MATERIALIZED product has a parquet snapshot in the lake and serves
 *       that ({@code snapshotId} set); a VIRTUAL product (Phase 5) has none and serves by
 *       expanding its OQL through the live GAV pipeline at read time ({@code snapshotId} is
 *       {@code null}, enforced by {@link DataProduct}'s compact constructor).</li>
 *   <li>{@code STALE} — a newer schema version went ACTIVE after the last materialization; the
 *       pinned snapshot still serves (information, not breakage). MATERIALIZED products only: a
 *       VIRTUAL product always reads the live catalog, so there is nothing for it to go stale
 *       against — {@code DataProductService.markStaleForVersionChange} skips it outright.</li>
 *   <li>{@code FAILED} — the last materialization failed; {@code lastError} says why.
 *       MATERIALIZED products only, for the same reason as MATERIALIZING.</li>
 * </ul>
 */
@NullMarked
public enum DataProductStatus {
    DRAFT,
    MATERIALIZING,
    READY,
    STALE,
    FAILED
}

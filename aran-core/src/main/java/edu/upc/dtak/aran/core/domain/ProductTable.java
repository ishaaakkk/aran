package edu.upc.dtak.aran.core.domain;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * One data product seen as a SQL table: the name a client writes, the registry row behind it, and
 * the columns recorded at its last materialization.
 *
 * <p>{@code columns} is empty for a product materialized before the registry recorded output
 * schemas (V29); the table is still queryable, because the parquet carries its own header.
 */
@NullMarked
public record ProductTable(String sqlName, DataProduct product, List<ProductColumn> columns) {

    /**
     * Whether a client can select from this table: a MATERIALIZED product has a snapshot to scan,
     * or the product is VIRTUAL — which serves by expanding its OQL at read time and so never has,
     * nor needs, a snapshot (Phase 5). A virtual product must be selectable for that expansion to
     * run at all; {@link #snapshotId()} must therefore never be called on the VIRTUAL branch (it
     * throws), which the query engine's per-access-mode routing enforces.
     *
     * <p>STALE counts, for a materialized product. A stale product is a pinned snapshot that keeps
     * serving while a newer schema version is active — refusing to read it would turn "your data is
     * older than the schema" into "your table vanished", and the staleness is reported instead, on
     * the table and in {@code products._products}.
     */
    public boolean queryable() {
        return product.snapshotId() != null || product.accessMode() == ProductAccessMode.VIRTUAL;
    }

    /**
     * Whether the read-only Iceberg catalog serves this table: a MATERIALIZED product with a
     * snapshot AND recorded columns — Iceberg metadata needs a schema, and a product built before
     * V29 recorded none (it is JDBC-queryable all the same, the parquet carries its own header).
     * A VIRTUAL product has no files for an external engine to read. The one rule: the catalog
     * filters on it, and the products API reports it, so the screen cannot claim a door the catalog
     * then refuses (J6-F4).
     */
    public boolean servedOverIceberg() {
        return product.accessMode() == ProductAccessMode.MATERIALIZED
                && product.snapshotId() != null
                && !columns.isEmpty();
    }

    /** The snapshot to scan. Only call when {@link #queryable()}. */
    public String snapshotId() {
        String snapshotId = product.snapshotId();
        if (snapshotId == null) {
            throw new IllegalStateException(
                    "Data product '" + sqlName + "' has never been materialized — it has no snapshot to read.");
        }
        return snapshotId;
    }
}

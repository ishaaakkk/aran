package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * Copies one Iceberg table's current contents into parquet bytes, reading it live via DuckDB's
 * {@code iceberg} extension — the MATERIALIZED half of Iceberg access.
 *
 * <p>A port, for the same reason {@link ObjectStorageExtractor} is one: the implementation needs
 * DuckDB and lives in {@code aran-operators/bootstrapping}, while the caller
 * ({@code DatasetService}) is in the core, which depends on no operator by construction. Not folded
 * into {@code ObjectStorageExtractor} because the read authenticates differently — the metadata
 * location comes from the catalog (bearer token), the file reads from an S3 secret — and one
 * signature carrying both kinds' nulls is the shape the extractor ports exist to avoid.
 *
 * <p><b>Credential hygiene.</b> The repository carries both credentials; the implementation binds
 * the S3 secret on a scratch DuckDB connection and discards it, and must never log either
 * credential nor place one in an exception message.
 */
@NullMarked
public interface IcebergTableExtractor {

    /** Parquet bytes and how many rows are in them. */
    record Extracted(byte[] parquet, long rowCount) {}

    /**
     * Copy {@code tableIdentifier}'s current snapshot out of {@code repository}'s catalog into
     * parquet.
     *
     * @param repository      the catalog, carrying both the control-plane and data-plane credentials
     * @param tableIdentifier the {@code namespace.table} to copy — membership-checked against the
     *                        catalog's own listing by the caller, never free-form
     * @param maxBytes        ceiling on the parquet produced; over it, the copy is abandoned rather
     *                        than held whole in the backend heap
     */
    Extracted extract(IcebergRestRepository repository, String tableIdentifier, long maxBytes);
}

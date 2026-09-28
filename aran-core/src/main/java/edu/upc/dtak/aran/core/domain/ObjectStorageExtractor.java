package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * Copies one object out of an object store ({@code s3://bucket/key}) into parquet bytes, reading it
 * live over DuckDB's {@code httpfs} — the MATERIALIZED half of object-storage access.
 *
 * <p>A port, for the same reason {@link TableExtractor} is one: the implementation needs DuckDB and
 * lives in {@code aran-operators/bootstrapping} (next to the {@code ObjectStorageConnector} that
 * reads the same object's schema in place), while the caller ({@code DatasetService}) is in the core,
 * which depends on no operator by construction. The two extractors are deliberately not one method —
 * a relational extract authenticates over JDBC and an object extract authenticates with an S3 secret,
 * and folding both into one signature would carry each path's nulls into the other.
 *
 * <p>Bytes rather than a {@code Path}: the caller's next move is {@code lakeClient.uploadToS3}, which
 * already takes a byte array — the same reuse of the existing copy-to-lake tail the JDBC extract path
 * makes.
 *
 * <p><b>Credential hygiene.</b> The repository carries the S3 secret; the implementation binds it on
 * a scratch DuckDB connection and discards it, and must never log it nor place it in an exception
 * message. That contract is part of the port because a leak here would be a leak whatever the
 * implementation.
 */
@NullMarked
public interface ObjectStorageExtractor {

    /** Parquet bytes and how many rows are in them. */
    record Extracted(byte[] parquet, long rowCount) {}

    /**
     * Copy {@code objectKey} out of {@code repository}'s bucket into parquet.
     *
     * @param repository the object store, carrying the bucket and the S3 credentials
     * @param objectKey  the object's key within the bucket (the specific relation to copy — the
     *                   object-store analogue of a table name)
     * @param format     the object's format ({@code PARQUET}/{@code CSV}/{@code JSON}/…), choosing the
     *                   reader; blank degrades to PARQUET
     * @param maxBytes   ceiling on the parquet produced; over it, the copy is abandoned rather than
     *                   held whole in the backend heap
     */
    Extracted extract(ObjectStorageRepository repository, String objectKey, String format,
                      long maxBytes);
}

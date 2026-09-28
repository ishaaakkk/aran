package edu.upc.dtak.aran.core.domain;

/**
 * Supported data repository protocol categories.
 *
 * <p>Every kind here can be ingested from. Three that could only be registered and tested —
 * NoSQL document, key-value and web API — were deleted before v0.1.0 (D1, Flyway V44) rather than
 * carried behind a "connector available" flag, and the flag went with them once nothing could
 * answer {@code false}: a kind that can be registered but never read is the product promising
 * more than the code delivers. A new kind arrives with its connector, as {@code SINGER_TAP} and
 * {@code ICEBERG_REST} did.
 */
public enum RepositoryType {

    /** Any database with a JDBC driver, plus live federation for Postgres and MySQL. */
    RELATIONAL_DB,

    /** Files uploaded to the lake: CSV, JSON, Parquet. Upload-only — no server directory (D1). */
    FILE_SYSTEM,

    /** S3/GCS/Azure, read over DuckDB's httpfs — live (FEDERATED) or copied into the lake (MATERIALIZED). */
    OBJECT_STORAGE,

    /**
     * A SaaS API reached by running a Singer tap the operator installed and allowlisted, through
     * its acquisition mechanism ({@code SingerTapRunner} behind {@code SingerTapExtractor}).
     */
    SINGER_TAP,

    /**
     * An Apache Iceberg REST catalog — the lakehouse control-plane standard (Gap 2), reached through
     * {@code IcebergConnector} over {@code IcebergCatalogClient}. The control plane (catalog HTTP +
     * bearer token) and the data plane (S3-style credentials for the files the metadata points at)
     * are both carried by {@code IcebergRestRepository}.
     */
    ICEBERG_REST
}

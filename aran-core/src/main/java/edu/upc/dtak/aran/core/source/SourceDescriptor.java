package edu.upc.dtak.aran.core.source;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Everything a {@link SourceConnector} needs to identify and introspect one dataset,
 * without depending on JPA, web, or lake transport types.
 *
 * <p>For file-backed sources the {@code bytes} are the already-downloaded snapshot
 * (small, in the lake); {@code contentType} and {@code filenameHint} drive format
 * detection. For live/API sources (added later) the bytes are absent and the
 * connector reaches the system via its own configuration carried elsewhere.
 *
 * @param datasetId    the Aran dataset UUID (as a string), used to namespace source IRIs.
 * @param datasetName  human-readable dataset name; the root class label.
 * @param snapshotId   the lake snapshot id backing this dataset, if any.
 * @param contentType  the snapshot's MIME type, if known.
 * @param filenameHint an optional filename/extension hint for format detection.
 * @param explicitKind a caller-forced source kind (e.g. "JSON"), overriding detection; may be null.
 * @param bytes        the raw source bytes for file sources; null for live/API sources.
 * @param connection   how to reach a JDBC database; null for every other kind.
 * @param objectStorage how to reach an object-storage object ({@code s3://bucket/key}); null for
 *                      every other kind.
 * @param iceberg      how to reach one table of an Iceberg REST catalog; null for every other kind.
 */
@NullMarked
public record SourceDescriptor(
        String datasetId,
        String datasetName,
        @Nullable String snapshotId,
        @Nullable String contentType,
        @Nullable String filenameHint,
        @Nullable String explicitKind,
        byte @Nullable [] bytes,
        @Nullable Connection connection,
        @Nullable ObjectStorage objectStorage,
        @Nullable Iceberg iceberg) {

    /**
     * How to reach a source that is a database rather than a file.
     *
     * <p>Its own record rather than five more nullable fields on the descriptor: a file source has
     * no meaningful value for any of them, and a connector that has to check five nulls to decide
     * whether it was handed a database is one null-check away from trying to introspect one that
     * is not there.
     *
     * <p>The password is here because introspection has to authenticate. It is never logged and
     * never written to the schema graph — {@code toString} is overridden for exactly that reason,
     * since a descriptor lands in exception messages and structured logs by default.
     *
     * @param table the specific table or view to introspect. One dataset is one relation, matching
     *              how every file source works: one dataset, one class.
     * @param engine the {@link RelationalEngine} id ({@code POSTGRESQL}, {@code MYSQL}, …) this URL
     *               speaks, when the caller knows it: introspection loads that engine's driver and
     *               sets its connect timeout from it. Null means "unknown".
     */
    public record Connection(
            String jdbcUrl,
            @Nullable String username,
            @Nullable String password,
            @Nullable String schema,
            String table,
            @Nullable String engine) {

        /** A connection whose engine is not known. */
        public Connection(String jdbcUrl, @Nullable String username, @Nullable String password,
                          @Nullable String schema, String table) {
            this(jdbcUrl, username, password, schema, table, null);
        }

        @Override
        public String toString() {
            return "Connection[jdbcUrl=" + jdbcUrl + ", username=" + username
                    + ", password=***, schema=" + schema + ", table=" + table
                    + ", engine=" + engine + ']';
        }
    }

    /**
     * How to reach a source that is one object in an object store, rather than a file already in the
     * lake or a database.
     *
     * <p>Its own record for the same reason {@link Connection} is: a file source has no meaningful
     * value for any of these, and a connector that had to sift six more nullable fields to tell an
     * S3 object from an upload is one missed null-check away from a wrong read.
     *
     * <p>The secret key is here because introspection has to authenticate. It is never logged and
     * never written to the schema graph — {@code toString} is overridden for exactly that reason,
     * since a descriptor lands in exception messages and structured logs by default.
     *
     * @param provider  {@code "S3"}/{@code "MINIO"}/{@code "GCS"}/… — drives url-style and TLS.
     * @param endpoint  the store endpoint; blank for AWS S3, explicit for MinIO/LocalStack.
     * @param bucket    the bucket the object lives in.
     * @param objectKey the object's key within the bucket. One dataset is one object, matching how
     *                  a file source is one file and a JDBC source is one table.
     * @param format    the object's format ({@code "PARQUET"}/{@code "CSV"}/…), choosing the reader.
     */
    public record ObjectStorage(
            String provider,
            @Nullable String endpoint,
            String bucket,
            String objectKey,
            @Nullable String accessKey,
            @Nullable String secretKey,
            @Nullable String region,
            String format) {

        /** The {@code s3://bucket/key} URL a DuckDB reader function takes. */
        public String s3Url() {
            return "s3://" + bucket + "/" + objectKey;
        }

        /** The credential/endpoint config for the httpfs + S3 secret binding. */
        public DuckDbS3Config toS3Config() {
            return new DuckDbS3Config(provider, endpoint, accessKey, secretKey, region);
        }

        @Override
        public String toString() {
            return "ObjectStorage[provider=" + provider + ", endpoint=" + endpoint
                    + ", bucket=" + bucket + ", objectKey=" + objectKey
                    + ", accessKey=" + accessKey + ", secretKey=***, region=" + region
                    + ", format=" + format + ']';
        }
    }

    /**
     * How to reach one table of an Apache Iceberg REST catalog (Gap 2, consume half).
     *
     * <p>Its own record for the same reason {@link Connection} and {@link ObjectStorage} are. It
     * carries both planes an Iceberg read needs: the catalog half (URI, warehouse, bearer token —
     * what introspection dials) and the data-plane half (S3-style credentials for the files the
     * catalog's metadata points at — what a federated scan authenticates with).
     *
     * <p>The token and secret key are here because introspection and scanning have to
     * authenticate. Never logged and never written to the schema graph — {@code toString} is
     * overridden for exactly that reason.
     *
     * @param tableIdentifier the {@code namespace.table} to introspect. One dataset is one table,
     *                        matching how a JDBC source is one relation.
     */
    public record Iceberg(
            String catalogUri,
            @Nullable String warehouse,
            @Nullable String token,
            String tableIdentifier,
            String provider,
            @Nullable String endpoint,
            @Nullable String accessKey,
            @Nullable String secretKey,
            @Nullable String region) {

        /** The credential/endpoint config for the data plane's httpfs + S3 secret binding. */
        public DuckDbS3Config toS3Config() {
            return new DuckDbS3Config(provider, endpoint, accessKey, secretKey, region);
        }

        @Override
        public String toString() {
            return "Iceberg[catalogUri=" + catalogUri + ", warehouse=" + warehouse
                    + ", token=***, tableIdentifier=" + tableIdentifier
                    + ", provider=" + provider + ", endpoint=" + endpoint
                    + ", accessKey=***, secretKey=***, region=" + region + ']';
        }
    }

    /** A file-backed descriptor: bytes present, no forced kind. */
    public static SourceDescriptor ofFile(String datasetId, String datasetName,
                                          @Nullable String snapshotId, @Nullable String contentType,
                                          @Nullable String filenameHint, @Nullable String explicitKind,
                                          byte[] bytes) {
        return new SourceDescriptor(datasetId, datasetName, snapshotId, contentType,
                filenameHint, explicitKind, bytes, null, null, null);
    }

    /**
     * A source reached over JDBC. Carries no bytes and no snapshot: the whole point of the JDBC
     * path is that introspection asks the catalog and never moves a row.
     */
    public static SourceDescriptor ofJdbc(String datasetId, String datasetName,
                                          @Nullable String explicitKind, Connection connection) {
        return new SourceDescriptor(datasetId, datasetName, null, null, null, explicitKind,
                null, connection, null, null);
    }

    /**
     * A source that is one object in an object store, read live over httpfs. Carries no bytes and no
     * lake snapshot: like the JDBC path, introspection reads the object's metadata in place and
     * never copies it into the lake.
     */
    public static SourceDescriptor ofObjectStorage(String datasetId, String datasetName,
                                                   @Nullable String explicitKind,
                                                   ObjectStorage objectStorage) {
        return new SourceDescriptor(datasetId, datasetName, null, null, null, explicitKind,
                null, null, objectStorage, null);
    }

    /**
     * A source that is one table of an Iceberg REST catalog, read live via DuckDB's
     * {@code iceberg_scan}. Carries no bytes and no lake snapshot: introspection asks the catalog
     * for the table's schema and never moves a row.
     */
    public static SourceDescriptor ofIceberg(String datasetId, String datasetName,
                                             @Nullable String explicitKind, Iceberg iceberg) {
        return new SourceDescriptor(datasetId, datasetName, null, null, null, explicitKind,
                null, null, null, iceberg);
    }
}

package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.util.Names;
import org.jspecify.annotations.NullMarked;

/**
 * The DuckDB read expression for one Iceberg table, given its current metadata location — a pure
 * string builder, in core for the {@code DuckDbS3Config} reason: the bootstrapping connector, the
 * query engine's resolver and the MATERIALIZED extractor must all emit the <em>same</em> scan for
 * the same table, and neither operator module may depend on the other.
 *
 * <p>The expression carries no credential — only the metadata location. The S3 secret that
 * authenticates the underlying file reads travels separately on the connection
 * ({@code ConnectionSetup} / {@code CREATE SECRET}), exactly as object storage's
 * {@code read_parquet('s3://…')} does.
 */
@NullMarked
public final class IcebergScanExpression {

    private IcebergScanExpression() {
    }

    /** {@code iceberg_scan('<metadataLocation>')} with the location SQL-escaped. */
    public static String forMetadataLocation(String metadataLocation) {
        if (metadataLocation == null || metadataLocation.isBlank()) {
            throw new IllegalArgumentException("metadata location must not be blank");
        }
        return "iceberg_scan('" + Names.sqlLiteral(metadataLocation) + "')";
    }

    /**
     * The {@code s3://bucket} prefix of an {@code s3://}/{@code s3a://} metadata location, or
     * {@code null} for any other scheme — the scope the data-plane secret should be bound under,
     * so a query joining two catalogs routes each file read to the right credentials.
     */
    public static @org.jspecify.annotations.Nullable String s3Scope(String metadataLocation) {
        String loc = metadataLocation == null ? "" : metadataLocation.trim();
        String rest;
        if (loc.startsWith("s3://")) {
            rest = loc.substring("s3://".length());
        } else if (loc.startsWith("s3a://")) {
            rest = loc.substring("s3a://".length());
        } else {
            return null;
        }
        int slash = rest.indexOf('/');
        String bucket = slash >= 0 ? rest.substring(0, slash) : rest;
        return bucket.isBlank() ? null : "s3://" + bucket;
    }
}

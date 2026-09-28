package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.util.Names;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Builds the DuckDB {@code httpfs} + S3 secret SQL for reaching an object-storage repository, as a
 * <em>pure string builder</em>: it executes nothing and touches no I/O — it only turns the
 * repository's connection fields into the {@code INSTALL httpfs; LOAD httpfs;} and
 * {@code CREATE SECRET (TYPE S3, …)} statements a scratch DuckDB connection needs before it can
 * read an {@code s3://} object.
 *
 * <h2>Why it lives in aran-core</h2>
 *
 * <p>Two callers must bind the <em>same</em> credentials the <em>same</em> way: bootstrapping's
 * {@code ObjectStorageConnector} (metadata introspection, phase 4A T2) and query-engine's snapshot
 * resolver (federated reads, T3). Both modules depend on aran-core and neither depends on the
 * other, so this is the one place both can reuse — and a second copy would drift like any
 * duplicated definition, except here the drifting text carries a secret, so a divergence is a
 * credential-handling bug rather than a cosmetic one. It mirrors the Python authority in
 * {@code aran-lake/src/aran_lake/query/duckdb_runner.py::_configure_connection}, generalised so an
 * AWS-native bucket uses virtual-hosted-style addressing while every S3-compatible server
 * (MinIO/Garage/GCS/Ceph) uses path-style.
 *
 * <h2>Credential hygiene</h2>
 *
 * <p>{@link #createSecretSql()} embeds the secret key in clear text. <b>Never log it, never put it
 * in an exception message, never return it to any caller other than the DuckDB connection that
 * executes it and discards it.</b> {@link #toString()} redacts the secret so that a config landing
 * in a log line or an exception (both happen to records by default) does not leak it; the redaction
 * is the whole reason this is a class with a hand-written {@code toString} rather than a record.
 */
@NullMarked
public final class DuckDbS3Config {

    private final String provider;
    private final @Nullable String endpoint;
    private final @Nullable String accessKey;
    private final @Nullable String secretKey;
    private final @Nullable String region;

    public DuckDbS3Config(String provider, @Nullable String endpoint, @Nullable String accessKey,
                          @Nullable String secretKey, @Nullable String region) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        this.provider = provider;
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    /** The S3 config carried by an {@link ObjectStorageRepository}. */
    public static DuckDbS3Config from(ObjectStorageRepository repo) {
        return new DuckDbS3Config(repo.provider(), repo.endpoint(), repo.accessKey(),
                repo.secretKey(), repo.region());
    }

    /**
     * The extension statements that must run before any {@code s3://} read, in order. Safe to log —
     * they carry no secret. {@code INSTALL} is a download the first time and a no-op afterwards; the
     * caller may skip it (try {@code LOAD} first) if it wants to avoid the network on a host where
     * httpfs is already installed.
     */
    public List<String> installAndLoadHttpfs() {
        return List.of("INSTALL httpfs", "LOAD httpfs");
    }

    /**
     * The {@code CREATE OR REPLACE SECRET} statement binding these credentials for {@code s3://}
     * reads on the connection it runs on, under the default name {@code aran_s3} and unscoped — the
     * single-secret case (bootstrapping's one-object introspection).
     *
     * <p><b>Contains the secret key.</b> Do not log this string, do not concatenate it into an
     * exception message, do not return it past the connection that executes it. {@code OR REPLACE}
     * so a reused connection can rebind without a duplicate-name error.
     */
    public String createSecretSql() {
        return createSecretSql("aran_s3", null);
    }

    /**
     * As {@link #createSecretSql()}, but under an explicit {@code secretName} and, when
     * {@code scope} is non-blank, a {@code SCOPE '<scope>'} clause.
     *
     * <p>Scoping is what lets one connection carry several S3 secrets at once: a query that joins
     * two object-storage datasets from different repositories (different buckets, different keys)
     * needs one secret per repository, and DuckDB routes an {@code s3://} read to the secret whose
     * scope is the longest matching prefix. The query-engine resolver names each secret after its
     * repository and scopes it to {@code s3://<bucket>}, so distinct repositories never collide and
     * the same repository referenced twice de-duplicates to one secret.
     *
     * <p><b>Contains the secret key.</b> Same rule as the no-arg form — execute and discard, never
     * log, never surface. Only the clauses whose values are present are emitted: an anonymous/public
     * bucket may carry no keys, and DuckDB rejects an empty {@code KEY_ID ''} where an omitted
     * clause is fine.
     */
    public String createSecretSql(String secretName, @Nullable String scope) {
        StringBuilder sb = new StringBuilder("CREATE OR REPLACE SECRET ")
                .append(secretName).append(" (TYPE S3");
        appendClause(sb, "KEY_ID", accessKey);
        appendClause(sb, "SECRET", secretKey);
        appendClause(sb, "REGION", region);
        String host = endpointHost();
        if (host != null) {
            appendClause(sb, "ENDPOINT", host);
        }
        sb.append(", URL_STYLE '").append(pathStyleAddressing() ? "path" : "vhost").append('\'');
        sb.append(", USE_SSL ").append(useSsl());
        appendClause(sb, "SCOPE", scope);
        sb.append(')');
        return sb.toString();
    }

    private static void appendClause(StringBuilder sb, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            sb.append(", ").append(key).append(" '").append(Names.sqlLiteral(value)).append('\'');
        }
    }

    /**
     * Whether S3 requests use path-style addressing ({@code endpoint/bucket/key}) rather than
     * virtual-hosted style ({@code bucket.endpoint/key}). AWS S3 is the only provider that uses
     * virtual-hosted style; every S3-compatible server (MinIO, Garage, GCS's S3 API, Ceph) requires
     * path-style, and a configured custom endpoint is itself the signal of such a server. So only an
     * AWS-native repository — provider {@code S3}/{@code AWS} with no explicit endpoint — is vhost.
     */
    boolean pathStyleAddressing() {
        String p = provider.toUpperCase(Locale.ROOT);
        boolean awsNative = (p.equals("S3") || p.equals("AWS")) && isBlank(endpoint);
        return !awsNative;
    }

    /**
     * TLS on/off, derived from the endpoint scheme. A blank endpoint means an AWS/GCS default host,
     * which is always HTTPS; an explicit {@code http://} endpoint (a local MinIO/LocalStack) is
     * plaintext.
     */
    boolean useSsl() {
        if (isBlank(endpoint)) {
            return true;
        }
        return !endpoint.trim().toLowerCase(Locale.ROOT).startsWith("http://");
    }

    /**
     * The bare {@code host[:port]} for the {@code ENDPOINT} clause — scheme and any path stripped —
     * or {@code null} to let DuckDB use its AWS default ({@code s3.amazonaws.com}). GCS carries a
     * known default host; a MinIO/other repository is always configured with an explicit endpoint
     * (see {@link ObjectStorageRepository}), so a blank one there falls back to the AWS default,
     * which is honest about the misconfiguration rather than guessing.
     */
    @Nullable String endpointHost() {
        if (isBlank(endpoint)) {
            return switch (provider.toUpperCase(Locale.ROOT)) {
                case "GCS" -> "storage.googleapis.com";
                default -> null;
            };
        }
        String host = endpoint.trim().replaceFirst("^(?i)https?://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host.isBlank() ? null : host;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /** Redacts the secret key. See the class-level credential-hygiene note. */
    @Override
    public String toString() {
        return "DuckDbS3Config[provider=" + provider + ", endpoint=" + endpoint
                + ", accessKey=" + accessKey + ", secretKey=***, region=" + region + ']';
    }
}

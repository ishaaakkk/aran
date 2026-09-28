package edu.upc.dtak.aran.core.source;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure S3 secret / httpfs SQL builder. No DuckDB, no network — these pin the string this class
 * hands a scratch connection, the url-style/ssl derivation, and (load-bearing) that the secret
 * never appears in {@link DuckDbS3Config#toString()}.
 */
@DisplayName("DuckDbS3Config — pure S3 secret SQL builder")
class DuckDbS3ConfigTest {

    @Test
    @DisplayName("httpfs is installed then loaded, in that order")
    void installsAndLoadsHttpfs() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "k", "s", "us-east-1");
        assertThat(cfg.installAndLoadHttpfs()).containsExactly("INSTALL httpfs", "LOAD httpfs");
    }

    @Test
    @DisplayName("a MinIO endpoint yields a path-style, plaintext secret with the scheme stripped")
    void minioSecret() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "AKID", "SEKRET", "us-east-1");
        String sql = cfg.createSecretSql();
        assertThat(sql)
                .startsWith("CREATE OR REPLACE SECRET aran_s3 (TYPE S3")
                .contains("KEY_ID 'AKID'")
                .contains("SECRET 'SEKRET'")
                .contains("REGION 'us-east-1'")
                .contains("ENDPOINT 'localhost:9000'")   // scheme stripped
                .contains("URL_STYLE 'path'")
                .contains("USE_SSL false")
                .endsWith(")");
    }

    @Test
    @DisplayName("AWS-native (S3, no endpoint) is virtual-hosted, TLS, and omits the ENDPOINT clause")
    void awsNativeSecret() {
        DuckDbS3Config cfg = new DuckDbS3Config("S3", null, "AKID", "SEKRET", "eu-west-1");
        String sql = cfg.createSecretSql();
        assertThat(sql)
                .contains("URL_STYLE 'vhost'")
                .contains("USE_SSL true")
                .doesNotContain("ENDPOINT");
    }

    @Test
    @DisplayName("an https endpoint keeps TLS on and strips the scheme")
    void httpsEndpointKeepsTls() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "https://minio.example.com:9000", "k", "s", "r");
        String sql = cfg.createSecretSql();
        assertThat(sql).contains("ENDPOINT 'minio.example.com:9000'").contains("USE_SSL true");
    }

    @Test
    @DisplayName("GCS with no endpoint uses its known host and path-style")
    void gcsDefaultHost() {
        DuckDbS3Config cfg = new DuckDbS3Config("GCS", null, "k", "s", "auto");
        String sql = cfg.createSecretSql();
        assertThat(sql).contains("ENDPOINT 'storage.googleapis.com'").contains("URL_STYLE 'path'");
    }

    @Test
    @DisplayName("a blank key omits the clause rather than emitting KEY_ID ''")
    void blankCredentialsAreOmitted() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "", "", "");
        String sql = cfg.createSecretSql();
        assertThat(sql).doesNotContain("KEY_ID").doesNotContain("SECRET '").doesNotContain("REGION");
    }

    @Test
    @DisplayName("a secret containing a quote is doubled, not left to break the statement")
    void secretIsSqlEscaped() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "k", "a'b", "r");
        assertThat(cfg.createSecretSql()).contains("SECRET 'a''b'");
    }

    @Test
    @DisplayName("toString redacts the secret key but not the (identifier-like) access key")
    void toStringRedactsSecret() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "AKID", "TOP-SECRET", "r");
        String s = cfg.toString();
        assertThat(s).doesNotContain("TOP-SECRET").contains("secretKey=***").contains("AKID");
    }

    @Test
    @DisplayName("the named/scoped overload emits the given secret name and a SCOPE clause")
    void namedScopedSecret() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "AKID", "SEKRET", "r");
        String sql = cfg.createSecretSql("aran_s3_repo1", "s3://my-bucket");
        assertThat(sql)
                .startsWith("CREATE OR REPLACE SECRET aran_s3_repo1 (TYPE S3")
                .contains("KEY_ID 'AKID'")
                .contains("SCOPE 's3://my-bucket'")
                .endsWith(")");
    }

    @Test
    @DisplayName("a null or blank scope omits the SCOPE clause (a single unscoped secret)")
    void blankScopeOmitsClause() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "k", "s", "r");
        assertThat(cfg.createSecretSql("aran_s3_x", null)).doesNotContain("SCOPE");
        assertThat(cfg.createSecretSql("aran_s3_x", "  ")).doesNotContain("SCOPE");
    }

    @Test
    @DisplayName("a scope containing a quote is doubled, closing SQL injection through a bucket name")
    void scopeIsSqlEscaped() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "k", "s", "r");
        assertThat(cfg.createSecretSql("aran_s3_x", "s3://a'b"))
                .contains("SCOPE 's3://a''b'");
    }

    @Test
    @DisplayName("the no-arg form is the unscoped aran_s3 secret — no SCOPE clause")
    void noArgFormIsUnscoped() {
        DuckDbS3Config cfg = new DuckDbS3Config("MINIO", "http://localhost:9000", "k", "s", "r");
        assertThat(cfg.createSecretSql())
                .startsWith("CREATE OR REPLACE SECRET aran_s3 (TYPE S3")
                .doesNotContain("SCOPE");
    }

    @Test
    @DisplayName("from(repo) copies the object-storage repository's connection fields")
    void fromRepository() {
        ObjectStorageRepository repo = new ObjectStorageRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "lake", "desc",
                "MINIO", "http://localhost:9000", "bucket", "AKID", "SEKRET", "us-east-1");
        String sql = DuckDbS3Config.from(repo).createSecretSql();
        assertThat(sql).contains("KEY_ID 'AKID'").contains("SECRET 'SEKRET'")
                .contains("ENDPOINT 'localhost:9000'");
    }
}

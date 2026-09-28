package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import edu.upc.dtak.aran.core.domain.ObjectStorageExtractor;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.source.GarageContainer;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The MATERIALIZED object-storage copy against a real S3-compatible store (Garage in Testcontainers):
 * {@link DuckDbObjectStorageExtractor} reads an {@code s3://} parquet object over httpfs and copies it
 * to local parquet bytes — the bytes {@code DatasetService} then uploads as a lake snapshot.
 *
 * <p>Two things are proven: the copy carries the object's real rows (the mechanism works), and no
 * credential surfaces even when the copy fails (the same hygiene the introspection path has). The
 * Garage secret is 32 random bytes, so the no-leak assertion is unambiguous.
 *
 * <p>Docker-tagged so {@code validate.sh --offline} excludes it.
 */
@Tag("docker")
@DisplayName("DuckDbObjectStorageExtractor — copy an s3:// object to parquet over Garage/httpfs")
class DuckDbObjectStorageExtractorGarageTest {

    private static final String BUCKET = "aran-copy";
    private static final String OBJECT_KEY = "people.parquet";

    private static final GarageContainer GARAGE = new GarageContainer().withBucket(BUCKET);
    private static final String SECRET_KEY = GARAGE.secretKey();   // must never surface

    private final ObjectStorageExtractor extractor = new DuckDbObjectStorageExtractor();

    @BeforeAll
    static void up() throws Exception {
        GARAGE.start();
        // The MinIO SDK is only a generic S3 client here; the bucket already exists.
        MinioClient client = MinioClient.builder()
                .endpoint(GARAGE.endpoint())
                .credentials(GARAGE.accessKey(), GARAGE.secretKey())
                .region(GARAGE.region())
                .build();

        Path parquet = Files.createTempFile("aran-people-", ".parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES (1, 'alice'), (2, 'bob')) AS t(id, name)) TO '"
                    + parquet.toAbsolutePath() + "' (FORMAT parquet)");
        }
        client.uploadObject(UploadObjectArgs.builder()
                .bucket(BUCKET).object(OBJECT_KEY)
                .filename(parquet.toAbsolutePath().toString()).build());
        Files.deleteIfExists(parquet);
    }

    @AfterAll
    static void down() {
        GARAGE.stop();
    }

    private ObjectStorageRepository repo() {
        return new ObjectStorageRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "lake", "",
                GarageContainer.PROVIDER, GARAGE.endpoint(), BUCKET,
                GARAGE.accessKey(), GARAGE.secretKey(), GARAGE.region());
    }

    @Test
    @DisplayName("the copy carries the object's real rows into parquet bytes")
    void copiesTheObjectsRows() throws Exception {
        ObjectStorageExtractor.Extracted extracted =
                extractor.extract(repo(), OBJECT_KEY, "PARQUET", 100L * 1024 * 1024);

        assertThat(extracted.rowCount()).isEqualTo(2L);

        // Read the produced bytes back to prove they are the object's rows, not an empty/placeholder
        // parquet — the copy must be the whole object, unlike the metadata-only introspection path.
        Path staged = Files.createTempFile("aran-extract-readback-", ".parquet");
        try {
            Files.write(staged, extracted.parquet());
            List<String> cells = new ArrayList<>();
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT name FROM read_parquet('" + staged.toAbsolutePath() + "') ORDER BY id")) {
                while (rs.next()) {
                    cells.add(rs.getString(1));
                }
            }
            assertThat(cells).containsExactly("alice", "bob");
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @Test
    @DisplayName("a failed copy names the object, never the secret")
    void aFailedCopyLeaksNoSecret() {
        // A missing object exercises the read after the secret is bound: the copy fails, and the
        // credential — correct and in play — must not appear in the exception. DuckDB's httpfs error
        // is about the URL/HTTP status; the extractor wraps it naming only the s3:// object.
        Throwable thrown = catchThrowable(() ->
                extractor.extract(repo(), "does-not-exist.parquet", "PARQUET", 100L * 1024 * 1024));

        assertThat(thrown).isNotNull();
        String everything = stringify(thrown);
        assertThat(everything)
                .describedAs("the secret must not appear anywhere in the failure")
                .doesNotContain(SECRET_KEY);
    }

    @Test
    @DisplayName("a copy over the byte ceiling is abandoned, not returned")
    void ceilingIsEnforced() {
        // A 1-byte ceiling is below any real parquet, so the copy must refuse rather than hand back
        // an over-limit blob the backend would then hold whole in the heap.
        assertThatThrownBy(() -> extractor.extract(repo(), OBJECT_KEY, "PARQUET", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ceiling");
    }

    /** The throwable's whole message chain, so a secret hiding in any cause is caught. */
    private static String stringify(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }
}

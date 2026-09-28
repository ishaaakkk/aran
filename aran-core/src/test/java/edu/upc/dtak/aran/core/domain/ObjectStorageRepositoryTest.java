package edu.upc.dtak.aran.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ObjectStorageRepository} carries live S3-style credentials. A record gets a free
 * {@code toString} that prints every component, so — same discipline as
 * {@link edu.upc.dtak.aran.core.source.SourceDescriptor.ObjectStorage} — this pins that the
 * override actually redacts both {@code accessKey} and {@code secretKey} rather than leaving the
 * default in place, since a repository lands in exception messages and structured logs by
 * default.
 */
@DisplayName("ObjectStorageRepository — toString redacts both credentials")
class ObjectStorageRepositoryTest {

    @Test
    @DisplayName("toString contains neither the access key nor the secret key value")
    void toStringRedactsBothCredentials() {
        ObjectStorageRepository repo = new ObjectStorageRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "lake", "desc", "MINIO", "http://localhost:9000", "my-bucket",
                "AKID-OSR-EXAMPLE", "TOP-SECRET-OSR-VALUE", "us-east-1");

        String s = repo.toString();

        assertThat(s)
                .doesNotContain("AKID-OSR-EXAMPLE")
                .doesNotContain("TOP-SECRET-OSR-VALUE")
                .contains("accessKey=***")
                .contains("secretKey=***")
                // the other fields stay readable — only the two credentials are masked
                .contains("my-bucket")
                .contains("MINIO")
                .contains("lake");
    }
}

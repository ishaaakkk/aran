package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * An object storage repository representing cloud datalakes.
 */
@NullMarked
public record ObjectStorageRepository(
        UUID id,
        UUID projectId,
        UUID tenantId,
        String name,
        String description,
        String provider,    // e.g. "S3", "MINIO", "GCS", "AZURE"
        String endpoint,    // Nullable/empty for AWS, explicit for MinIO/LocalStack
        String bucketName,
        String accessKey,
        String secretKey,
        String region
) implements DataRepository {

    public ObjectStorageRepository {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucket name must not be blank");
        }
    }

    @Override
    public RepositoryType type() {
        return RepositoryType.OBJECT_STORAGE;
    }

    /** Redacts both credentials; the default record {@code toString} would print them in clear text. */
    @Override
    public String toString() {
        return "ObjectStorageRepository[id=" + id + ", projectId=" + projectId
                + ", tenantId=" + tenantId + ", name=" + name + ", description=" + description
                + ", provider=" + provider + ", endpoint=" + endpoint + ", bucketName=" + bucketName
                + ", accessKey=***, secretKey=***, region=" + region + ']';
    }
}

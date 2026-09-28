package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * An Apache Iceberg REST catalog (Gap 2 — lakehouse interoperability, consume half).
 *
 * <p>Its own kind rather than a mode on {@code OBJECT_STORAGE} because the two halves of reaching
 * an Iceberg table have different shapes: the <em>control plane</em> is an HTTP catalog speaking
 * the Iceberg REST protocol (list namespaces/tables, load a table's current metadata location),
 * authenticated with a bearer token; the <em>data plane</em> is the object store the catalog's
 * metadata points into, authenticated with S3-style credentials. One repository carries both,
 * because a table read needs both — and masking, redirect-guarding and screening each half
 * separately is only possible when the model knows which field is which.
 *
 * @param catalogUri the catalog's base URI, up to but excluding {@code /v1} (e.g.
 *                   {@code https://polaris.example/api/catalog}). The client appends the
 *                   spec-defined paths.
 * @param warehouse  the warehouse the catalog should serve, passed to {@code GET /v1/config};
 *                   blank when the catalog serves exactly one.
 * @param token      bearer token for the catalog itself; blank for an unauthenticated catalog.
 * @param provider   data-plane provider ({@code "S3"}/{@code "MINIO"}/…), driving URL style and
 *                   TLS exactly as on {@link ObjectStorageRepository}.
 * @param endpoint   data-plane endpoint; blank for AWS S3, explicit for MinIO/Garage.
 * @param accessKey  data-plane access key; blank when the store is public or the deployment
 *                   grants access another way.
 * @param secretKey  data-plane secret; encrypted at rest, masked on the wire.
 * @param region     data-plane region, when the provider needs one.
 */
@NullMarked
public record IcebergRestRepository(
        UUID id,
        UUID projectId,
        UUID tenantId,
        String name,
        String description,
        String catalogUri,
        String warehouse,
        String token,
        String provider,
        String endpoint,
        String accessKey,
        String secretKey,
        String region
) implements DataRepository {

    public IcebergRestRepository {
        if (catalogUri == null || catalogUri.isBlank()) {
            throw new IllegalArgumentException("catalog URI must not be blank");
        }
        if (provider == null || provider.isBlank()) {
            provider = "S3";
        }
    }

    @Override
    public RepositoryType type() {
        return RepositoryType.ICEBERG_REST;
    }

    /** Redacts both credentials; the default record {@code toString} would print them in clear text. */
    @Override
    public String toString() {
        return "IcebergRestRepository[id=" + id + ", projectId=" + projectId
                + ", tenantId=" + tenantId + ", name=" + name + ", description=" + description
                + ", catalogUri=" + catalogUri + ", warehouse=" + warehouse + ", token=***"
                + ", provider=" + provider + ", endpoint=" + endpoint
                + ", accessKey=***, secretKey=***, region=" + region + ']';
    }
}

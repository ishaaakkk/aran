package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.source.SourceSchema;
import java.io.IOException;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * Object-storage source connector: one object ({@code s3://bucket/key}) in S3, MinIO, GCS or any
 * S3-compatible store, read live over DuckDB's {@code httpfs}.
 *
 * <p>It claims a descriptor by the presence of {@link SourceDescriptor#objectStorage} rather than by
 * a content type or a filename, so it is mutually exclusive with the file connectors (which need
 * bytes) and {@link JdbcConnector} (which needs a connection) by construction — {@code
 * SourceConnectors} enforces that exclusivity at startup.
 *
 * <p>Introspection is metadata-only ({@code DESCRIBE} over the object). Whether a dataset is then
 * read in place or from a lake copy is {@code SourceAccess}'s per-dataset decision, not this
 * connector's.
 */
@NullMarked
@Component
public final class ObjectStorageConnector implements SourceConnector {

    private final ObjectStorageBootstrap objectStorage = new ObjectStorageBootstrap();
    private final OutboundAddressGuard addressGuard;

    public ObjectStorageConnector(OutboundAddressGuard addressGuard) {
        this.addressGuard = addressGuard;
    }

    /**
     * Screen an explicit endpoint before httpfs reaches it (sec-audit F-S1-11). A blank endpoint is
     * AWS S3's public default and needs none; a present one is screened exactly as the registration
     * path screens it ({@code DataRepositoryService.screenOutboundTarget}), so introspect can no
     * longer reach an internal host or the metadata endpoint that test/scan would have refused.
     */
    private void screenEndpoint(SourceDescriptor.ObjectStorage obj) {
        String endpoint = obj.endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            addressGuard.checkUri(endpoint);
        }
    }

    @Override
    public String sourceKind() {
        return "OBJECT_STORAGE";
    }

    @Override
    public boolean supports(SourceDescriptor source) {
        return source.objectStorage() != null;
    }

    @Override
    public SourceSchema introspect(SourceDescriptor source) throws IOException {
        SourceDescriptor.ObjectStorage obj = Objects.requireNonNull(source.objectStorage(),
                "the object-storage connector requires object-storage details");
        screenEndpoint(obj);
        return new SourceSchema(
                objectStorage.bootstrap(obj, source.datasetId(), source.datasetName()));
    }
}

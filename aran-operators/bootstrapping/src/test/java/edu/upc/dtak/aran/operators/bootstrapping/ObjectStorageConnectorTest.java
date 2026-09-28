package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The object-storage connector's claiming logic, endpoint screen and reader dispatch — everything
 * that can be checked without a live store. Reading an actual object (introspect) needs a live S3
 * store and lives in the docker-tagged {@link ObjectStorageConnectorGarageTest}.
 */
@DisplayName("ObjectStorageConnector — claiming, endpoint screening and reader dispatch")
class ObjectStorageConnectorTest {

    private final ObjectStorageConnector connector = new ObjectStorageConnector(new edu.upc.dtak.aran.core.net.OutboundAddressGuard(false));

    private static SourceDescriptor objectDescriptor(String format) {
        return SourceDescriptor.ofObjectStorage("ds", "name", null,
                new SourceDescriptor.ObjectStorage("MINIO", "http://localhost:9000", "bucket",
                        "path/to/object", "k", "s", "us-east-1", format));
    }

    @Test
    @DisplayName("declares the OBJECT_STORAGE kind")
    void kind() {
        assertThat(connector.sourceKind()).isEqualTo("OBJECT_STORAGE");
    }

    @Test
    @DisplayName("claims a descriptor carrying object-storage details, and nothing else")
    void claiming() {
        assertThat(connector.supports(objectDescriptor("PARQUET"))).isTrue();
        assertThat(connector.supports(
                SourceDescriptor.ofFile("d", "d", "s", "text/csv", "x.csv", null, new byte[0])))
                .isFalse();
        assertThat(connector.supports(
                SourceDescriptor.ofJdbc("d", "d", null,
                        new SourceDescriptor.Connection("jdbc:h2:mem:x", "u", "p", null, "t"))))
                .isFalse();
    }

    // The reader dispatch is shared by introspection's DESCRIBE and the MATERIALIZED copy
    // (DuckDbObjectStorageExtractor); without these its only coverage is the docker-tagged MinIO test.
    @Test
    @DisplayName("a parquet object is read with read_parquet over its s3:// URL")
    void readerForParquet() {
        assertThat(ObjectStorageBootstrap.readExpression(
                ObjectStorageBootstrap.normalizeFormat("parquet"), objectLocation()))
                .isEqualTo("read_parquet('s3://bucket/path/to/object')");
    }

    @Test
    @DisplayName("a csv object is read with read_csv_auto over its s3:// URL")
    void readerForCsv() {
        assertThat(ObjectStorageBootstrap.readExpression(
                ObjectStorageBootstrap.normalizeFormat("CSV"), objectLocation()))
                .isEqualTo("read_csv_auto('s3://bucket/path/to/object')");
    }

    @Test
    @DisplayName("an undeclared format is read as parquet, the object store's common case")
    void blankFormatIsParquet() {
        assertThat(ObjectStorageBootstrap.normalizeFormat(null)).isEqualTo("PARQUET");
        assertThat(ObjectStorageBootstrap.normalizeFormat(" ")).isEqualTo("PARQUET");
    }

    private static String objectLocation() {
        return new SourceDescriptor.ObjectStorage("MINIO", "http://localhost:9000", "bucket",
                "path/to/object", "k", "s", "us-east-1", "PARQUET").s3Url();
    }

    @Test
    @DisplayName("introspect screens an explicit endpoint before httpfs reaches it (F-S1-11)")
    void introspectScreensEndpoint() {
        ObjectStorageConnector guarded = new ObjectStorageConnector(new OutboundAddressGuard(false));
        SourceDescriptor toMetadata = SourceDescriptor.ofObjectStorage("ds", "name", null,
                new SourceDescriptor.ObjectStorage("MINIO", "http://169.254.169.254/", "bucket",
                        "obj", "k", "s", "us-east-1", "PARQUET"));
        // 169.254.169.254 (cloud metadata) is refused regardless of the block-private flag, so this
        // proves the connector consults the guard — the endpoint no longer reaches httpfs unscreened.
        assertThatThrownBy(() -> guarded.introspect(toMetadata))
                .isInstanceOf(OutboundAddressGuard.BlockedHostException.class);
    }
}

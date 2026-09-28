package edu.upc.dtak.aran.adapter.iceberg;

import edu.upc.dtak.aran.core.domain.ProductColumn;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The produce-side generator, verified the only way that counts: by reading its own output back
 * through iceberg-core's <em>readers</em> — {@link TableMetadataParser} for the metadata JSON,
 * {@link ManifestFiles} for the Avro — never by string-matching the bytes. What these tests pin is
 * that a spec-following Iceberg client sees one v2 table, one snapshot, one parquet data file with
 * real counts, and the name mapping that lets it read DuckDB-written parquet (no field ids).
 */
@DisplayName("IcebergProductTable — a product snapshot as a readable Iceberg v2 table")
class IcebergProductTableTest {

    private static final String BASE =
            "https://aran.example/api/iceberg/files/11111111-2222-3333-4444-555555555555/snap-1";

    private static IcebergProductTable.ProductSpec spec() {
        return new IcebergProductTable.ProductSpec(
                "papers_per_year",
                "snap-1",
                List.of(
                        new ProductColumn("year", "BIGINT", java.sql.Types.BIGINT),
                        new ProductColumn("title", "VARCHAR", java.sql.Types.VARCHAR),
                        new ProductColumn("score", "DOUBLE", java.sql.Types.DOUBLE),
                        new ProductColumn("amount", "DECIMAL(38,9)", java.sql.Types.DECIMAL),
                        new ProductColumn("active", "BOOLEAN", java.sql.Types.BOOLEAN),
                        new ProductColumn("day", "DATE", java.sql.Types.DATE),
                        new ProductColumn("at", "TIMESTAMP", java.sql.Types.TIMESTAMP)),
                42L,
                123_456L,
                1_756_400_000_000L,
                BASE);
    }

    private static TableMetadata readBack(IcebergProductTable.Artifacts artifacts) {
        String json = new String(
                artifacts.files().get("metadata/" + IcebergProductTable.METADATA_FILE),
                java.nio.charset.StandardCharsets.UTF_8);
        return TableMetadataParser.fromJson(artifacts.metadataLocation(), json);
    }

    /** Every generated file loaded into a fresh IO at its absolute location, as a reader would see it. */
    private static InMemoryFileIO ioOver(IcebergProductTable.Artifacts artifacts) {
        InMemoryFileIO io = new InMemoryFileIO();
        artifacts.files().forEach((relative, bytes) -> io.addFile(BASE + "/" + relative, bytes));
        return io;
    }

    @Test
    @DisplayName("the metadata parses as a v2 table whose schema matches the recorded output schema")
    void metadataParsesWithSchema() {
        TableMetadata metadata = readBack(IcebergProductTable.build(spec()));

        assertThat(metadata.formatVersion()).isEqualTo(2);
        assertThat(metadata.location()).isEqualTo(BASE);
        assertThat(metadata.schema().columns()).extracting(Types.NestedField::name)
                .containsExactly("year", "title", "score", "amount", "active", "day", "at");
        assertThat(metadata.schema().findType("year")).isEqualTo(Types.LongType.get());
        assertThat(metadata.schema().findType("title")).isEqualTo(Types.StringType.get());
        assertThat(metadata.schema().findType("amount")).isEqualTo(Types.DecimalType.of(38, 9));
        assertThat(metadata.schema().findType("at")).isEqualTo(Types.TimestampType.withoutZone());
        assertThat(metadata.spec().isUnpartitioned()).isTrue();
    }

    @Test
    @DisplayName("the name mapping is present — DuckDB parquet has no field ids, readers need it")
    void nameMappingIsPresent() {
        TableMetadata metadata = readBack(IcebergProductTable.build(spec()));

        String mapping = metadata.properties().get("schema.name-mapping.default");
        assertThat(mapping).isNotNull();
        for (String column : List.of("year", "title", "score", "amount", "active", "day", "at")) {
            assertThat(mapping).contains("\"" + column + "\"");
        }
    }

    @Test
    @DisplayName("one snapshot whose manifests resolve to the one parquet data file, counts intact")
    void snapshotResolvesToTheDataFile() {
        IcebergProductTable.Artifacts artifacts = IcebergProductTable.build(spec());
        TableMetadata metadata = readBack(artifacts);
        InMemoryFileIO io = ioOver(artifacts);

        Snapshot snapshot = metadata.currentSnapshot();
        assertThat(snapshot).isNotNull();
        List<ManifestFile> manifests = snapshot.allManifests(io);
        assertThat(manifests).hasSize(1);

        try (var reader = ManifestFiles.read(manifests.getFirst(), io)) {
            List<DataFile> files = new java.util.ArrayList<>();
            reader.forEach(files::add);
            assertThat(files).hasSize(1);
            DataFile file = files.getFirst();
            assertThat(file.location()).isEqualTo(artifacts.dataFileLocation());
            assertThat(file.format()).isEqualTo(FileFormat.PARQUET);
            assertThat(file.recordCount()).isEqualTo(42L);
            assertThat(file.fileSizeInBytes()).isEqualTo(123_456L);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("every generated location lives under the base — no lake URL can appear")
    void locationsStayUnderTheBase() {
        IcebergProductTable.Artifacts artifacts = IcebergProductTable.build(spec());

        assertThat(artifacts.metadataLocation()).startsWith(BASE + "/");
        assertThat(artifacts.dataFileLocation()).startsWith(BASE + "/");
        TableMetadata metadata = readBack(artifacts);
        assertThat(metadata.currentSnapshot().manifestListLocation()).startsWith(BASE + "/");
        // And nothing anywhere mentions a bucket or an s3 scheme.
        String json = new String(
                artifacts.files().get("metadata/" + IcebergProductTable.METADATA_FILE),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("s3://");
    }

    @Test
    @DisplayName("regeneration is stable where it matters: same snapshot id for the same lake snapshot")
    void snapshotIdIsDeterministic() {
        long first = readBack(IcebergProductTable.build(spec())).currentSnapshot().snapshotId();
        long second = readBack(IcebergProductTable.build(spec())).currentSnapshot().snapshotId();
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("a product without a recorded output schema is refused, not served as a lie")
    void emptySchemaRefused() {
        assertThatThrownBy(() -> IcebergProductTable.build(new IcebergProductTable.ProductSpec(
                "t", "snap-1", List.of(), 0L, 1L, 0L, BASE)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

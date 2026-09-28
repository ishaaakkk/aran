package edu.upc.dtak.aran.adapter.iceberg;

import edu.upc.dtak.aran.core.domain.ProductColumn;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.LocationProviders;
import org.apache.iceberg.mapping.MappingUtil;
import org.apache.iceberg.mapping.NameMappingParser;
import org.apache.iceberg.types.Types;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Turns one MATERIALIZED data product's parquet snapshot into a complete, spec-conformant Iceberg
 * table — {@code metadata.json}, manifest list, and manifest — without touching the parquet itself
 * (Gap 2, produce half; the move ADR 002 anticipated: "the snapshot table maps cleanly onto an
 * Iceberg manifest").
 *
 * <p><b>The metadata is written by iceberg-core's own commit machinery</b>, never hand-rolled: a
 * {@link BaseTable} over an in-memory {@link TableOperations} takes a real
 * {@code newFastAppend().commit()}, so the Avro manifests carry exactly the schema and field ids
 * the reference implementation writes. Hand-rolling those files is the kind of claim this
 * repository keeps finding to be false the first time a real reader opens them.
 *
 * <p><b>Name mapping is load-bearing.</b> The parquet was written by DuckDB, which stamps no
 * Iceberg field ids into the file. Readers therefore resolve columns through the
 * {@code schema.name-mapping.default} table property generated here from the schema — without it,
 * a spec-following reader refuses the file or returns all-NULL columns.
 *
 * <p>Everything is generated in memory and returned as bytes keyed by path relative to
 * {@code baseLocation}; the caller (Aran's read-only REST catalog) serves them over its own
 * authenticated HTTP, which is what keeps the "only Aran talks to the lake" invariant intact —
 * no lake URL, bucket name, or credential appears anywhere in the metadata.
 */
@NullMarked
public final class IcebergProductTable {

    /** File name of the (single) table metadata file under {@code baseLocation/metadata/}. */
    public static final String METADATA_FILE = "v1.metadata.json";
    /** File name of the (single) data file under {@code baseLocation/data/}. */
    public static final String DATA_FILE = "data.parquet";

    private IcebergProductTable() {
    }

    /**
     * What the generator needs to know about one product snapshot.
     *
     * @param tableName     the product's table name (from {@code ProductCatalog} — the one naming
     *                      authority; a second derivation here would drift).
     * @param lakeSnapshotId the lake snapshot id backing the product — the identity everything
     *                      deterministic (snapshot id, cache keys) derives from.
     * @param columns       the product's recorded {@code output_schema}.
     * @param rowCount      rows in the snapshot as recorded at materialization.
     * @param fileSizeBytes the parquet's actual size — manifests carry it and readers plan with it,
     *                      so it must be the real number, not a guess.
     * @param lastUpdatedMs the product's materialization instant.
     * @param baseLocation  absolute URI prefix (no trailing slash) every generated location lives
     *                      under — Aran's own file-serving endpoint for this product+snapshot.
     */
    public record ProductSpec(
            String tableName,
            String lakeSnapshotId,
            List<ProductColumn> columns,
            long rowCount,
            long fileSizeBytes,
            long lastUpdatedMs,
            String baseLocation) {
        public ProductSpec {
            if (baseLocation.endsWith("/")) {
                throw new IllegalArgumentException("baseLocation must not end with '/'");
            }
        }
    }

    /**
     * The generated table: where its metadata file lives, where the (externally served) data file
     * must live, and every generated file's bytes keyed by path relative to the base location
     * (e.g. {@code metadata/v1.metadata.json}). The data file itself is NOT in the map — its bytes
     * are the lake snapshot, streamed by the caller.
     */
    public record Artifacts(String metadataLocation, String dataFileLocation,
                            Map<String, byte[]> files) {}

    public static Artifacts build(ProductSpec spec) {
        InMemoryFileIO io = new InMemoryFileIO();
        String base = spec.baseLocation();
        String dataLocation = base + "/data/" + DATA_FILE;

        Schema schema = schemaOf(spec.columns());
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(TableProperties.FORMAT_VERSION, "2");
        properties.put(TableProperties.DEFAULT_NAME_MAPPING,
                NameMappingParser.toJson(MappingUtil.create(schema)));

        TableMetadata initial = TableMetadata.newTableMetadata(
                schema, PartitionSpec.unpartitioned(), SortOrder.unsorted(), base, properties);
        InMemoryOps ops = new InMemoryOps(initial, io, base, snapshotIdFor(spec.lakeSnapshotId()));

        DataFile dataFile = DataFiles.builder(PartitionSpec.unpartitioned())
                .withPath(dataLocation)
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(spec.fileSizeBytes())
                .withRecordCount(spec.rowCount())
                .build();
        new BaseTable(ops, spec.tableName()).newFastAppend().appendFile(dataFile).commit();

        TableMetadata committed = ops.current();
        // Pin the timestamp the product actually materialized at, not "now": regenerating the same
        // snapshot's metadata twice must describe the same event.
        TableMetadata stamped = TableMetadata.buildFrom(committed)
                .setProperties(Map.of("aran.materialized-at-ms", Long.toString(spec.lastUpdatedMs())))
                .build();

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("metadata/" + METADATA_FILE,
                TableMetadataParser.toJson(stamped).getBytes(StandardCharsets.UTF_8));
        Snapshot snapshot = stamped.currentSnapshot();
        files.put(relative(base, snapshot.manifestListLocation()),
                readFully(io, snapshot.manifestListLocation()));
        for (ManifestFile manifest : snapshot.allManifests(io)) {
            files.put(relative(base, manifest.path()), readFully(io, manifest.path()));
        }
        return new Artifacts(base + "/metadata/" + METADATA_FILE, dataLocation, files);
    }

    // --- schema -------------------------------------------------------------

    private static final Pattern DECIMAL_SHAPE =
            Pattern.compile("DECIMAL\\s*\\((\\d+)\\s*,\\s*(\\d+)\\)", Pattern.CASE_INSENSITIVE);

    private static Schema schemaOf(List<ProductColumn> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "a product with no recorded output schema cannot be served as an Iceberg table");
        }
        List<Types.NestedField> fields = new java.util.ArrayList<>(columns.size());
        int id = 1;
        for (ProductColumn column : columns) {
            fields.add(Types.NestedField.optional(id++, column.name(), typeOf(column)));
        }
        return new Schema(fields);
    }

    /**
     * JDBC type code → Iceberg type, with {@code typeName} consulted only for DECIMAL's
     * precision/scale. The effective set is closed: a product's columns come out of the engine's
     * own TRY_CAST pipeline ({@code XsdTypes}: VARCHAR, BIGINT, DECIMAL, DOUBLE, BOOLEAN, DATE,
     * TIMESTAMP), so the default arm is a genuine never-in-practice, kept as string because that
     * is the one type every reader can do something with.
     */
    private static org.apache.iceberg.types.Type typeOf(ProductColumn column) {
        return switch (column.jdbcType()) {
            case java.sql.Types.BIGINT -> Types.LongType.get();
            case java.sql.Types.INTEGER, java.sql.Types.SMALLINT, java.sql.Types.TINYINT ->
                    Types.IntegerType.get();
            case java.sql.Types.DOUBLE, java.sql.Types.FLOAT -> Types.DoubleType.get();
            case java.sql.Types.REAL -> Types.FloatType.get();
            case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> decimalOf(column.typeName());
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> Types.BooleanType.get();
            case java.sql.Types.DATE -> Types.DateType.get();
            case java.sql.Types.TIMESTAMP -> Types.TimestampType.withoutZone();
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> Types.TimestampType.withZone();
            default -> Types.StringType.get();
        };
    }

    /** DuckDB reports e.g. {@code DECIMAL(38,9)}; a bare {@code DECIMAL} takes the engine's default. */
    private static Types.DecimalType decimalOf(@Nullable String typeName) {
        if (typeName != null) {
            Matcher m = DECIMAL_SHAPE.matcher(typeName.toUpperCase(Locale.ROOT));
            if (m.find()) {
                return Types.DecimalType.of(
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
        }
        // DuckDB's TRY_CAST target for xsd:decimal (see XsdTypes) — the one shape a product
        // column without a recorded precision can actually be.
        return Types.DecimalType.of(38, 9);
    }

    // --- plumbing -----------------------------------------------------------

    /** Deterministic snapshot id from the lake snapshot's identity, so regeneration is stable. */
    private static long snapshotIdFor(String lakeSnapshotId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buf = ByteBuffer.wrap(
                    digest.digest(lakeSnapshotId.getBytes(StandardCharsets.UTF_8)));
            return buf.getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String relative(String base, String location) {
        if (!location.startsWith(base + "/")) {
            throw new IllegalStateException(
                    "generated file escaped the base location: " + location);
        }
        return location.substring(base.length() + 1);
    }

    private static byte[] readFully(FileIO io, String location) {
        InputFile file = io.newInputFile(location);
        try (InputStream in = file.newStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read generated file " + location, e);
        }
    }

    /**
     * The minimal {@link TableOperations} the commit machinery needs: metadata lives in a field,
     * every file the machinery writes goes through the in-memory IO under {@code base/metadata/},
     * and snapshot ids are deterministic.
     */
    private static final class InMemoryOps implements TableOperations {
        private TableMetadata current;
        private final InMemoryFileIO io;
        private final String base;
        private final long snapshotId;

        private InMemoryOps(TableMetadata initial, InMemoryFileIO io, String base, long snapshotId) {
            this.current = initial;
            this.io = io;
            this.base = base;
            this.snapshotId = snapshotId;
        }

        @Override
        public TableMetadata current() {
            return current;
        }

        @Override
        public TableMetadata refresh() {
            return current;
        }

        @Override
        public void commit(TableMetadata base, TableMetadata metadata) {
            this.current = metadata;
        }

        @Override
        public FileIO io() {
            return io;
        }

        @Override
        public String metadataFileLocation(String fileName) {
            return base + "/metadata/" + fileName;
        }

        @Override
        public LocationProvider locationProvider() {
            return LocationProviders.locationsFor(base, current.properties());
        }

        @Override
        public long newSnapshotId() {
            return snapshotId;
        }
    }
}

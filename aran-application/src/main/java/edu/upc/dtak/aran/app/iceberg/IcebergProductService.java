package edu.upc.dtak.aran.app.iceberg;

import edu.upc.dtak.aran.adapter.iceberg.IcebergProductTable;
import edu.upc.dtak.aran.app.jdbc.ProductRegistry;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ProductTable;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;

/**
 * The produce half of Gap 2, behind the controllers: which products are Iceberg tables, and the
 * generated metadata + staged data file for each.
 *
 * <h2>Which products qualify</h2>
 *
 * <p>MATERIALIZED products with a snapshot and a recorded {@code output_schema} — three honest
 * exclusions, not arbitrary ones. A VIRTUAL product has no files an external engine could read (its
 * rows exist only when Aran expands its OQL); a never-materialized product has no snapshot; and a
 * pre-V29 product without a recorded schema cannot be described to a reader that (unlike SQL over
 * parquet) requires the schema in the metadata, not in the file.
 *
 * <h2>Caching</h2>
 *
 * <p>Generated artifacts are cached per (product, snapshot, base URL) — a lake snapshot is
 * content-addressed and immutable, so a hit never serves stale metadata; a refresh changes the
 * snapshot id and thereby the whole location prefix. The parquet itself is staged once per
 * snapshot under the JVM temp dir and re-served from there. Manifest file names embed a random
 * commit UUID, so after an eviction a rebuilt set has new names; a client holding the old
 * metadata.json gets a 404 on the old manifest name and recovers by re-loading the table — an
 * accepted cost, recorded in the design doc, cheaper than persisting generated metadata.
 */
@Service
@NullMarked
public class IcebergProductService {

    private static final int ARTIFACT_CACHE_MAX = 64;

    private final ProductRegistry products;
    private final LakeClient lake;

    /** LRU over generated artifact bundles; guarded by its own monitor. */
    private final Map<String, IcebergProductTable.Artifacts> artifactCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, IcebergProductTable.Artifacts> eldest) {
                    return size() > ARTIFACT_CACHE_MAX;
                }
            };

    public IcebergProductService(ProductRegistry products, LakeClient lake) {
        this.products = products;
        this.lake = lake;
    }

    /** The caller's products servable as Iceberg tables, in the catalog's stable name order. */
    public List<ProductTable> icebergTables(AuthenticatedUser caller) {
        return products.catalogFor(caller).tables().stream()
                .filter(ProductTable::servedOverIceberg)
                .toList();
    }

    /** One servable table by its SQL name, case-insensitively (the catalog's own lookup rule). */
    public Optional<ProductTable> icebergTable(AuthenticatedUser caller, String name) {
        return products.catalogFor(caller).byName(name)
                .filter(ProductTable::servedOverIceberg);
    }

    /**
     * The generated Iceberg metadata for one servable table, with every location under
     * {@code filesBaseUrl/{productId}/{snapshotId}}.
     */
    public IcebergProductTable.Artifacts artifactsFor(AuthenticatedUser caller, ProductTable table,
                                                      String filesBaseUrl) {
        String snapshotId = table.snapshotId();
        String base = filesBaseUrl.replaceAll("/+$", "")
                + "/" + table.product().id() + "/" + snapshotId;
        String key = caller.tenantId() + "|" + base;
        synchronized (artifactCache) {
            IcebergProductTable.Artifacts cached = artifactCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        // The manifest must carry the parquet's REAL size — readers plan with it — so the file is
        // staged (and measured) before the metadata is generated. Outside the lock: a lake
        // download must not serialize every other product's metadata read behind it.
        Path data = stagedDataFile(caller, snapshotId);
        long fileSize;
        try {
            fileSize = Files.size(data);
        } catch (IOException e) {
            throw new IllegalStateException("staged parquet for snapshot " + snapshotId
                    + " vanished: " + e.getMessage(), e);
        }
        IcebergProductTable.Artifacts built = IcebergProductTable.build(
                new IcebergProductTable.ProductSpec(
                        table.sqlName(),
                        snapshotId,
                        table.columns(),
                        table.product().rowCount() == null ? 0L : table.product().rowCount(),
                        fileSize,
                        table.product().materializedAt() == null
                                ? 0L : table.product().materializedAt().toEpochMilli(),
                        base));
        synchronized (artifactCache) {
            artifactCache.putIfAbsent(key, built);
            return artifactCache.get(key);
        }
    }

    /**
     * The product's parquet, staged under the JVM temp dir — download-once per snapshot, keyed by
     * the content-addressed snapshot id, published by atomic rename so a concurrent reader never
     * sees a partial file.
     */
    public Path stagedDataFile(AuthenticatedUser caller, String snapshotId) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "aran-iceberg-data");
            Files.createDirectories(dir);
            Path target = dir.resolve(caller.tenantId() + "-" + snapshotId + ".parquet");
            if (Files.exists(target)) {
                return target;
            }
            Path staging = Files.createTempFile(dir, "staging-", ".part");
            lake.downloadSnapshotTo(caller.tenantId(), snapshotId, staging);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                Files.deleteIfExists(staging);
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to stage snapshot " + snapshotId + ": " + e.getMessage(), e);
        }
    }
}

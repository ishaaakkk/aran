package edu.upc.dtak.aran.core.domain;

import java.util.Map;
import java.util.UUID;

public interface LakeClient {

    record InitUploadResponse(String uploadId, String uploadUrl, String contentType) {}

    /** Result of {@link #downloadSnapshot}: bytes plus the content type the lake recorded for them. */
    record SnapshotBytes(byte[] bytes, String contentType) {}

    /**
     * What a tenant is holding in the lake, right now.
     *
     * @param logicalBytes  the sum over every snapshot — what the tenant believes it stored.
     * @param physicalBytes the sum over distinct content hashes. Snapshots are content-addressed,
     *                      so identical bytes uploaded twice occupy the bucket once; this is the
     *                      figure a storage bill is drawn against. The gap between the two is what
     *                      deduplication is saving, which is why both are carried rather than one.
     */
    record StorageUsage(
            long snapshotCount,
            long logicalBytes,
            long physicalBytes,
            java.time.@org.jspecify.annotations.Nullable Instant oldestSnapshotAt,
            java.time.@org.jspecify.annotations.Nullable Instant newestSnapshotAt,
            java.util.List<DatasetStorage> datasets
    ) {
        public static StorageUsage empty() {
            return new StorageUsage(0, 0, 0, null, null, java.util.List.of());
        }
    }

    /** Per-dataset breakdown, largest first. A total says storage grew; this says what grew. */
    record DatasetStorage(UUID datasetId, long snapshotCount, long logicalBytes) {}

    /** A lake-side async job's state, as read from GET /api/jobs/{id}. */
    record LakeJob(String status, Map<String, Object> result, String errorMessage) {
        public boolean isTerminal() {
            return "SUCCEEDED".equals(status) || "FAILED".equals(status);
        }
    }

    /**
     * Initializes the snapshot upload in aran-lake.
     * POST /api/datasets/{id}/snapshots/init
     */
    InitUploadResponse initUpload(UUID tenantId, UUID datasetId, long sizeBytes, String contentType);

    /**
     * Uploads the raw bytes directly to S3/Garage via the presigned PUT URL.
     */
    void uploadToS3(String uploadUrl, byte[] fileBytes, String contentType);

    /**
     * Completes the snapshot upload in aran-lake.
     * POST /api/snapshots/uploads/{id}/complete
     */
    String completeUpload(UUID tenantId, String uploadId, String contentHash, long sizeBytes);

    /**
     * Downloads the raw bytes of a snapshot by resolving the presigned GET URL the
     * lake issues for it and then streaming from object storage.
     */
    SnapshotBytes downloadSnapshot(UUID tenantId, String snapshotId);

    /**
     * Read the tenant's current storage.
     *
     * <p>Aran cannot derive this from its own {@code usage_event} log: events record what moved,
     * a level records what remains, and no amount of summing the first gives the second —
     * especially while nothing ever deletes a snapshot. The lake owns the rows, so the lake is
     * asked.
     *
     * @param datasetLimit how many per-dataset rows to return. Passed explicitly rather than left
     *                     to the lake's default so the caller can tell a full list from a
     *                     truncated one — a breakdown silently cut at N reads as the whole thing.
     */
    StorageUsage storageUsage(UUID tenantId, int datasetLimit);

    /** One snapshot as the lake holds it — enough to decide whether anything still points at it. */
    record SnapshotInventoryItem(String snapshotId, UUID datasetId, long sizeBytes,
                                String contentHash) {}

    /**
     * The tenant's snapshots, paged.
     *
     * <p>Raw rows rather than an answer, because the question "is this still referenced?" is one
     * only Aran can answer — the lake has never heard of a data product or a schema fingerprint.
     */
    java.util.List<SnapshotInventoryItem> snapshotInventory(UUID tenantId, int limit, int offset);

    /**
     * Downloads a snapshot straight to {@code target}, without holding it in the heap.
     *
     * <p>{@link #downloadSnapshot} materialises the whole snapshot as a {@code byte[]}, and every
     * caller that wants a file then writes it out — so a 500 MB snapshot is a 500 MB array per
     * concurrent query, and that, not the 50 MB upload cap, is the real ceiling on snapshot size
     * today. Callers that only need a file on disk should use this instead.
     *
     * <p>The default implementation preserves the old behaviour so existing implementations and
     * test fakes keep working; {@code HttpLakeClient} overrides it to stream.
     *
     * @return the content type the lake recorded for the snapshot
     */
    default String downloadSnapshotTo(UUID tenantId, String snapshotId, java.nio.file.Path target) {
        SnapshotBytes blob = downloadSnapshot(tenantId, snapshotId);
        try {
            java.nio.file.Files.write(target, blob.bytes());
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write snapshot " + snapshotId + " to " + target, e);
        }
        return blob.contentType();
    }

    /**
     * Starts an async lake import of a remote PostgreSQL query result.
     * POST /api/imports
     *
     * @return the lake job id to poll via {@link #getLakeJob}
     */
    String startImport(UUID tenantId, UUID datasetId, String connectionString, String query);

    /**
     * Polls a lake-side async job.
     * GET /api/jobs/{jobId}
     */
    LakeJob getLakeJob(UUID tenantId, String jobId);

    String computeSha256(byte[] bytes);
}

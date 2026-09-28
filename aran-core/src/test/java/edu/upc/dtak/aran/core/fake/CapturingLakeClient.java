package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.LakeClient;
import java.util.UUID;

/**
 * A {@link LakeClient} for service tests that records the upload conversation
 * (content types, bytes, snapshot id) instead of talking HTTP. Download-side
 * methods throw — tests that need them should say so loudly.
 */
public class CapturingLakeClient implements LakeClient {

    public String initContentType;
    public String uploadContentType;
    public byte[] uploadedBytes;
    public String completedSnapshotId;

    @Override
    public InitUploadResponse initUpload(UUID tenantId, UUID datasetId, long sizeBytes, String contentType) {
        this.initContentType = contentType;
        return new InitUploadResponse("upload-1", "https://s3.example/put", contentType);
    }

    @Override
    public void uploadToS3(String uploadUrl, byte[] fileBytes, String contentType) {
        this.uploadContentType = contentType;
        this.uploadedBytes = fileBytes;
    }

    @Override
    public String completeUpload(UUID tenantId, String uploadId, String contentHash, long sizeBytes) {
        this.completedSnapshotId = "snap-" + uploadId;
        return completedSnapshotId;
    }

    @Override
    public SnapshotBytes downloadSnapshot(UUID tenantId, String snapshotId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String startImport(UUID tenantId, UUID datasetId, String connectionString, String query) {
        throw new UnsupportedOperationException();
    }

    @Override
    public LakeJob getLakeJob(UUID tenantId, String jobId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String computeSha256(byte[] bytes) {
        return "sha256-fake";
    }

    /** Whatever a test wants the lake to report holding. Empty unless a test says otherwise. */
    public StorageUsage storageUsage = StorageUsage.empty();

    @Override
    public StorageUsage storageUsage(java.util.UUID tenantId, int datasetLimit) {
        return storageUsage;
    }

    /** What a test wants the lake to say it is holding, snapshot by snapshot. */
    public java.util.List<SnapshotInventoryItem> inventory = java.util.List.of();

    @Override
    public java.util.List<SnapshotInventoryItem> snapshotInventory(
            java.util.UUID tenantId, int limit, int offset) {
        int from = Math.min(offset, inventory.size());
        return inventory.subList(from, Math.min(from + limit, inventory.size()));
    }
}

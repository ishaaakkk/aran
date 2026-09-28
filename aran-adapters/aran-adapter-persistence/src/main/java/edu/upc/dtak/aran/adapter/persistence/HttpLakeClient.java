package edu.upc.dtak.aran.adapter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import edu.upc.dtak.aran.core.domain.LakeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@NullMarked
public class HttpLakeClient implements LakeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLakeClient.class);

    private final String lakeUrl;
    private final String lakeSecret;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpLakeClient(
            // No annotation defaults: a deployment missing aran.lake.* must fail
            // at startup, never silently use the dev shared secret. Dev values
            // live in application.yml.
            @Value("${aran.lake.url}") String lakeUrl,
            @Value("${aran.lake.secret}") String lakeSecret,
            ObjectMapper objectMapper) {
        this(lakeUrl, lakeSecret, objectMapper, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    HttpLakeClient(
            String lakeUrl,
            String lakeSecret,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.lakeUrl = lakeUrl.replaceAll("/+$", "");
        this.lakeSecret = lakeSecret;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Per-request read timeouts. The HttpClient's connectTimeout only bounds establishing the
     * TCP connection — without these, a lake or S3 endpoint that accepts the connection and
     * then stalls hangs the calling thread indefinitely. On the query path that means an OQL
     * query with no upper bound at all. ProfilingOperator and JoinDiscoveryOperator already
     * set per-request timeouts; this client was the one that did not.
     */
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(30);

    /** Snapshot uploads/downloads move real payloads, so they get a far longer ceiling. */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);

    /** Starts a builder for a lake-bound request, forwarding the correlation ID if present. */
    private HttpRequest.Builder lakeRequest(URI uri) {
        var builder = HttpRequest.newBuilder().uri(uri).timeout(CONTROL_TIMEOUT);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        return builder;
    }



    /**
     * Initializes the snapshot upload in aran-lake.
     * POST /api/datasets/{id}/snapshots/init
     */
    public InitUploadResponse initUpload(UUID tenantId, UUID datasetId, long sizeBytes, String contentType) {
        log.info("Initializing snapshot upload in lake for dataset={}, size={}, contentType={}", datasetId, sizeBytes, contentType);
        try {
            Map<String, Object> body = Map.of(
                    "declared_size_bytes", sizeBytes,
                    "content_type", contentType,
                    "format_options", Map.of()
            );
            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = lakeRequest(URI.create(lakeUrl + "/api/datasets/" + datasetId + "/snapshots/init"))
                    .header("Authorization", "Bearer " + lakeSecret)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new RuntimeException("Lake init upload failed with status " + response.statusCode() + ": " + response.body());
            }

            JsonNode node = objectMapper.readTree(response.body());
            String uploadId = node.get("upload_id").asText();
            String uploadUrl = node.get("upload_url").asText();

            return new InitUploadResponse(uploadId, uploadUrl, contentType);
        } catch (Exception e) {
            log.error("Failed to initialize snapshot upload in lake", e);
            throw new RuntimeException("Lake init upload failed", e);
        }
    }

    /**
     * Uploads the raw bytes directly to S3/Garage via the presigned PUT URL.
     */
    public void uploadToS3(String uploadUrl, byte[] fileBytes, String contentType) {
        log.info("Uploading bytes directly to S3 via presigned URL...");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(TRANSFER_TIMEOUT)
                    .header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 204) {
                throw new RuntimeException("S3 direct upload failed with status " + response.statusCode() + ": " + response.body());
            }
            log.info("S3 direct upload succeeded");
        } catch (Exception e) {
            log.error("Failed to upload bytes directly to S3", e);
            throw new RuntimeException("S3 direct upload failed", e);
        }
    }

    /**
     * Completes the snapshot upload in aran-lake.
     * POST /api/snapshots/uploads/{id}/complete
     */
    public String completeUpload(UUID tenantId, String uploadId, String contentHash, long sizeBytes) {
        log.info("Completing snapshot upload in lake for uploadId={}, contentHash={}, size={}", uploadId, contentHash, sizeBytes);
        try {
            Map<String, Object> body = Map.of(
                    "content_hash", contentHash,
                    "actual_size_bytes", sizeBytes
            );
            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = lakeRequest(URI.create(lakeUrl + "/api/snapshots/uploads/" + uploadId + "/complete"))
                    .header("Authorization", "Bearer " + lakeSecret)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new RuntimeException("Lake complete upload failed with status " + response.statusCode() + ": " + response.body());
            }

            JsonNode node = objectMapper.readTree(response.body());
            return node.get("id").asText();
        } catch (Exception e) {
            log.error("Failed to complete snapshot upload in lake", e);
            throw new RuntimeException("Lake complete upload failed", e);
        }
    }

    /**
     * Reads the tenant's current storage from the lake.
     *
     * <p>GET /api/tenants/{tenant_id}/storage. The tenant in the path is decoration; the lake
     * takes its authority from the {@code X-Tenant-Id} header, as on every other route.
     */
    @Override
    public StorageUsage storageUsage(UUID tenantId, int datasetLimit) {
        try {
            HttpRequest request = lakeRequest(
                    URI.create(lakeUrl + "/api/tenants/" + tenantId + "/storage"
                            + "?dataset_limit=" + datasetLimit))
                    .header("Authorization", "Bearer " + lakeSecret)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Lake storage read failed with status "
                        + response.statusCode() + ": " + response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());

            List<DatasetStorage> datasets = new ArrayList<>();
            JsonNode breakdown = node.get("datasets");
            if (breakdown != null && breakdown.isArray()) {
                for (JsonNode d : breakdown) {
                    datasets.add(new DatasetStorage(
                            UUID.fromString(d.get("dataset_id").asText()),
                            d.get("snapshot_count").asLong(),
                            d.get("logical_bytes").asLong()));
                }
            }

            return new StorageUsage(
                    node.get("snapshot_count").asLong(),
                    node.get("logical_bytes").asLong(),
                    node.get("physical_bytes").asLong(),
                    instantOrNull(node.get("oldest_snapshot_at")),
                    instantOrNull(node.get("newest_snapshot_at")),
                    List.copyOf(datasets));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted reading lake storage", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read lake storage for tenant " + tenantId, e);
        }
    }

    /** The lake sends ISO-8601; a tenant holding nothing sends null for both timestamps. */
    private static java.time.@org.jspecify.annotations.Nullable Instant instantOrNull(
            @org.jspecify.annotations.Nullable JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return java.time.OffsetDateTime.parse(node.asText()).toInstant();
    }

    @Override
    public List<SnapshotInventoryItem> snapshotInventory(UUID tenantId, int limit, int offset) {
        try {
            HttpRequest request = lakeRequest(URI.create(lakeUrl
                    + "/api/tenants/" + tenantId + "/snapshot-inventory"
                    + "?limit=" + limit + "&offset=" + offset))
                    .header("Authorization", "Bearer " + lakeSecret)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Lake snapshot inventory failed with status "
                        + response.statusCode() + ": " + response.body());
            }

            List<SnapshotInventoryItem> items = new ArrayList<>();
            for (JsonNode n : objectMapper.readTree(response.body())) {
                items.add(new SnapshotInventoryItem(
                        n.get("id").asText(),
                        UUID.fromString(n.get("dataset_id").asText()),
                        n.get("size_bytes").asLong(),
                        n.get("content_hash").asText()));
            }
            return List.copyOf(items);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted reading lake snapshot inventory", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to read lake snapshot inventory for tenant " + tenantId, e);
        }
    }

    /**
     * Downloads the raw bytes of a snapshot by resolving the presigned GET URL the
     * lake issues for it and then streaming from object storage.
     * <p>
     * GET /api/snapshots/{snapshot_id}/download → presigned S3 URL → GET that URL → bytes.
     */
    public SnapshotBytes downloadSnapshot(UUID tenantId, String snapshotId) {
        log.info("Downloading snapshot bytes from lake for snapshot={}", snapshotId);
        try {
            HttpRequest presignRequest = lakeRequest(URI.create(lakeUrl + "/api/snapshots/" + snapshotId + "/download"))
                    .header("Authorization", "Bearer " + lakeSecret)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> presignResp = httpClient.send(presignRequest, HttpResponse.BodyHandlers.ofString());
            if (presignResp.statusCode() != 200) {
                throw new RuntimeException("Lake presign-download failed with status "
                        + presignResp.statusCode() + ": " + presignResp.body());
            }
            JsonNode node = objectMapper.readTree(presignResp.body());
            String downloadUrl = node.get("download_url").asText();
            String contentType = node.has("content_type") && !node.get("content_type").isNull()
                    ? node.get("content_type").asText()
                    : "application/octet-stream";

            HttpRequest fetchRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(TRANSFER_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<byte[]> fetchResp = httpClient.send(fetchRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (fetchResp.statusCode() != 200) {
                throw new RuntimeException("S3 presigned GET failed with status " + fetchResp.statusCode());
            }
            return new SnapshotBytes(fetchResp.body(), contentType);
        } catch (Exception e) {
            log.error("Failed to download snapshot bytes from lake", e);
            throw new RuntimeException("Snapshot download failed", e);
        }
    }

    /**
     * As {@link #downloadSnapshot}, but streams the object-store response directly into
     * {@code target} instead of buffering the whole snapshot in the heap.
     *
     * <p>{@code BodyHandlers.ofFile} writes as bytes arrive, so peak memory is the HTTP client's
     * buffer rather than the snapshot's size. The presign call still returns a small JSON body and
     * is read normally.
     */
    @Override
    public String downloadSnapshotTo(UUID tenantId, String snapshotId, java.nio.file.Path target) {
        log.info("Streaming snapshot from lake to {} for snapshot={}", target, snapshotId);
        try {
            HttpRequest presignRequest = lakeRequest(
                    URI.create(lakeUrl + "/api/snapshots/" + snapshotId + "/download"))
                    .header("Authorization", "Bearer " + lakeSecret)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> presignResp =
                    httpClient.send(presignRequest, HttpResponse.BodyHandlers.ofString());
            if (presignResp.statusCode() != 200) {
                throw new RuntimeException("Lake presign-download failed with status "
                        + presignResp.statusCode() + ": " + presignResp.body());
            }
            JsonNode node = objectMapper.readTree(presignResp.body());
            String downloadUrl = node.get("download_url").asText();
            String contentType = node.has("content_type") && !node.get("content_type").isNull()
                    ? node.get("content_type").asText()
                    : "application/octet-stream";

            HttpRequest fetchRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(TRANSFER_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<java.nio.file.Path> fetchResp = httpClient.send(fetchRequest,
                    HttpResponse.BodyHandlers.ofFile(target,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                            java.nio.file.StandardOpenOption.WRITE));
            if (fetchResp.statusCode() != 200) {
                throw new RuntimeException("S3 presigned GET failed with status " + fetchResp.statusCode());
            }
            return contentType;
        } catch (Exception e) {
            log.error("Failed to stream snapshot from lake", e);
            throw new RuntimeException("Snapshot download failed", e);
        }
    }

    /**
     * Starts an async lake import of a remote PostgreSQL query result.
     * POST /api/imports → 202 with the lake job id.
     */
    public String startImport(UUID tenantId, UUID datasetId, String connectionString, String query) {
        log.info("Starting lake import for dataset={}", datasetId);
        try {
            Map<String, Object> body = Map.of(
                    "dataset_id", datasetId.toString(),
                    "source", Map.of(
                            "kind", "postgres",
                            "connection_string", connectionString,
                            "query", query
                    )
            );
            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = lakeRequest(URI.create(lakeUrl + "/api/imports"))
                    .header("Authorization", "Bearer " + lakeSecret)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                throw new RuntimeException("Lake import start failed with status " + response.statusCode() + ": " + response.body());
            }

            JsonNode node = objectMapper.readTree(response.body());
            return node.get("job_id").asText();
        } catch (Exception e) {
            log.error("Failed to start lake import for dataset {}", datasetId, e);
            throw new RuntimeException("Lake import start failed", e);
        }
    }

    /**
     * Polls a lake-side async job.
     * GET /api/jobs/{jobId}
     */
    public LakeJob getLakeJob(UUID tenantId, String jobId) {
        try {
            HttpRequest request = lakeRequest(URI.create(lakeUrl + "/api/jobs/" + jobId))
                    .header("Authorization", "Bearer " + lakeSecret)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Lake job poll failed with status " + response.statusCode() + ": " + response.body());
            }

            JsonNode node = objectMapper.readTree(response.body());
            String status = node.get("status").asText();
            Map<String, Object> result = node.has("result") && !node.get("result").isNull()
                    ? objectMapper.convertValue(node.get("result"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                    : Map.of();
            String errorMessage = node.has("error_message") && !node.get("error_message").isNull()
                    ? node.get("error_message").asText()
                    : null;
            return new LakeJob(status, result, errorMessage);
        } catch (Exception e) {
            log.error("Failed to poll lake job {}", jobId, e);
            throw new RuntimeException("Lake job poll failed", e);
        }
    }

    public String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 computation failed", e);
        }
    }
}

package edu.upc.dtak.aran.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.LakeClient;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HttpLakeClient Tests")
class HttpLakeClientTest {

    @Mock
    private HttpClient httpClient;

    private ObjectMapper objectMapper;
    private HttpLakeClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = new HttpLakeClient("http://localhost:8085", "dev-secret", objectMapper, httpClient);
    }

    @Test
    @DisplayName("initUpload returns upload details successfully when lake responds 200")
    @SuppressWarnings("unchecked")
    void init_upload_success() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "upload_id": "upload-123",
                  "upload_url": "http://garage/upload-123"
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        UUID tenantId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        LakeClient.InitUploadResponse res = client.initUpload(tenantId, datasetId, 1024, "text/csv");

        assertThat(res.uploadId()).isEqualTo("upload-123");
        assertThat(res.uploadUrl()).isEqualTo("http://garage/upload-123");
        assertThat(res.contentType()).isEqualTo("text/csv");
    }

    @Test
    @DisplayName("initUpload throws exception when status code is not 200/201")
    @SuppressWarnings("unchecked")
    void init_upload_failure() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("Internal Server Error");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> client.initUpload(UUID.randomUUID(), UUID.randomUUID(), 1024, "text/csv"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Lake init upload failed");
    }

    @Test
    @DisplayName("uploadToS3 PUTs raw bytes successfully when S3 responds 200")
    @SuppressWarnings("unchecked")
    void upload_to_s3_success() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client.uploadToS3("http://garage/upload-123", "hello".getBytes(), "text/csv");

        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("completeUpload completes snapshot and returns new snapshot ID")
    @SuppressWarnings("unchecked")
    void complete_upload_success() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "id": "snapshot-abc"
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        String snapshotId = client.completeUpload(UUID.randomUUID(), "upload-123", "some-hash", 1024);

        assertThat(snapshotId).isEqualTo("snapshot-abc");
    }

    @Test
    @DisplayName("computeSha256 calculates accurate SHA-256 hex string")
    void compute_sha256_success() {
        String hash = client.computeSha256("hello".getBytes());
        // SHA-256 for "hello" is "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertThat(hash).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("downloadSnapshot fetches presign URL then streams bytes from S3")
    @SuppressWarnings("unchecked")
    void download_snapshot_success() throws Exception {
        // First call: presign response from the lake
        HttpResponse<String> presignResp = mock(HttpResponse.class);
        when(presignResp.statusCode()).thenReturn(200);
        when(presignResp.body()).thenReturn("""
                {"download_url":"http://garage/file.csv","content_type":"text/csv"}
                """);

        // Second call: raw bytes from S3
        HttpResponse<byte[]> s3Resp = mock(HttpResponse.class);
        when(s3Resp.statusCode()).thenReturn(200);
        when(s3Resp.body()).thenReturn("id,name\n1,Alice\n".getBytes());

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(presignResp)
                .thenReturn(s3Resp);

        LakeClient.SnapshotBytes result = client.downloadSnapshot(UUID.randomUUID(), "snap-xyz");

        assertThat(result.contentType()).isEqualTo("text/csv");
        assertThat(new String(result.bytes())).isEqualTo("id,name\n1,Alice\n");
    }

    @Test
    @DisplayName("startImport posts to /api/imports and returns the job id on 202")
    @SuppressWarnings("unchecked")
    void start_import_success() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(202);
        when(response.body()).thenReturn("""
                {"job_id":"job-777","status":"PENDING","poll_url":"/api/jobs/job-777"}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        String jobId = client.startImport(UUID.randomUUID(), UUID.randomUUID(),
                "postgresql://u:p@host:5432/db", "SELECT * FROM \"people\"");

        assertThat(jobId).isEqualTo("job-777");
    }

    @Test
    @DisplayName("startImport throws when the lake rejects the import")
    @SuppressWarnings("unchecked")
    void start_import_failure() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("bad request");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> client.startImport(UUID.randomUUID(), UUID.randomUUID(),
                "postgresql://u:p@host:5432/db", "SELECT 1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Lake import start failed");
    }

    @Test
    @DisplayName("getLakeJob reads status + result from GET /api/jobs/{id}")
    @SuppressWarnings("unchecked")
    void get_lake_job_success() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"status":"SUCCEEDED","result":{"snapshot_id":"snap-9","row_count":3},"error_message":null}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        LakeClient.LakeJob job = client.getLakeJob(UUID.randomUUID(), "job-777");

        assertThat(job.status()).isEqualTo("SUCCEEDED");
        assertThat(job.isTerminal()).isTrue();
        assertThat(job.result().get("snapshot_id")).isEqualTo("snap-9");
        assertThat(job.errorMessage()).isNull();
    }

    @Test
    @DisplayName("getLakeJob surfaces the lake's error_message for a failed job")
    @SuppressWarnings("unchecked")
    void get_lake_job_failed() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"status":"FAILED","result":null,"error_message":"ConnectionError: boom"}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        LakeClient.LakeJob job = client.getLakeJob(UUID.randomUUID(), "job-777");

        assertThat(job.status()).isEqualTo("FAILED");
        assertThat(job.isTerminal()).isTrue();
        assertThat(job.result()).isEmpty();
        assertThat(job.errorMessage()).isEqualTo("ConnectionError: boom");
    }

    @Test
    @DisplayName("downloadSnapshot throws RuntimeException when lake returns non-200 on presign")
    @SuppressWarnings("unchecked")
    void download_snapshot_presign_failure() throws Exception {
        HttpResponse<String> errorResp = mock(HttpResponse.class);
        when(errorResp.statusCode()).thenReturn(404);
        when(errorResp.body()).thenReturn("Not Found");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(errorResp);

        assertThatThrownBy(() -> client.downloadSnapshot(UUID.randomUUID(), "snap-missing"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Snapshot download failed");
    }
}


package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.DatasetDtos.DatasetCreatedResponse;
import edu.upc.dtak.aran.app.web.dto.DatasetDtos.DatasetProfilesResponse;
import edu.upc.dtak.aran.app.web.dto.DatasetDtos.DatasetRequest;
import edu.upc.dtak.aran.app.web.dto.DatasetDtos.DatasetResponse;
import edu.upc.dtak.aran.app.web.dto.DatasetDtos.ImportIcebergTableRequest;
import edu.upc.dtak.aran.app.web.dto.DatasetDtos.ImportStreamRequest;
import edu.upc.dtak.aran.app.web.dto.DatasetDtos.ImportTableRequest;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetPreview;
import edu.upc.dtak.aran.core.domain.DatasetRegistration;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.source.DatasetAccessMode;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for dataset definition, listing, scanning, and dynamic previews.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/repositories/{repositoryId}")
public class DatasetController {

    private final DatasetService datasetService;
    private final DataRepositoryService repositoryService;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper objectMapper;

    public DatasetController(
            DatasetService datasetService,
            DataRepositoryService repositoryService,
            CurrentUserResolver currentUser,
            ObjectMapper objectMapper) {
        this.datasetService = datasetService;
        this.repositoryService = repositoryService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/datasets")
    public List<DatasetResponse> listDatasets(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId) {
        AuthenticatedUser caller = currentUser.require();
        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);
        return datasetService.listDatasets(caller, projectId, repositoryId).stream()
                .map(d -> DatasetResponse.from(d, DatasetAccessMode.of(repository, d)))
                .toList();
    }

    /**
     * Register a dataset with no file. For a table a federated repository reads live, the
     * bootstrap starts as a job and its id comes back as {@code bootstrapJobId}, as it does for
     * an upload; otherwise the job id is null.
     */
    @PostMapping("/datasets")
    public ResponseEntity<DatasetCreatedResponse> registerDataset(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody DatasetRequest request) {
        AuthenticatedUser caller = currentUser.require();
        UUID datasetId = UUID.randomUUID();
        Dataset domainModel = request.toDomain(datasetId, repositoryId);
        DatasetRegistration created =
                datasetService.registerDatasetForLiveRead(caller, projectId, repositoryId, domainModel);

        return ResponseEntity
                .created(URI.create("/api/projects/" + projectId + "/repositories/" + repositoryId + "/datasets/" + created.dataset().id()))
                .body(DatasetCreatedResponse.from(created));
    }

    @PostMapping(value = "/datasets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DatasetCreatedResponse> registerDatasetWithFile(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @RequestPart("dataset") String datasetJson,
            @RequestPart("file") MultipartFile file) throws Exception {
        AuthenticatedUser caller = currentUser.require();
        DatasetRequest request = objectMapper.readValue(datasetJson, DatasetRequest.class);

        UUID datasetId = UUID.randomUUID();
        Dataset domainModel = request.toDomain(datasetId, repositoryId);

        byte[] fileBytes = file.getBytes();
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        // F14: the browser's uploaded file name is
        // only ever available here — the dataset's own display name has no reliable
        // extension (e.g. "venues", not "venues.parquet") and format detection was
        // silently falling back to it, which fails whenever the browser also could not
        // sniff a MIME type for the file (parquet is not a browser-native type).
        String originalFilename = file.getOriginalFilename();

        DatasetRegistration created = datasetService.registerDatasetWithFile(caller, projectId, repositoryId, domainModel, fileBytes, contentType, originalFilename);

        return ResponseEntity
                .created(URI.create("/api/projects/" + projectId + "/repositories/" + repositoryId + "/datasets/" + created.dataset().id()))
                .body(DatasetCreatedResponse.from(created));
    }

    /**
     * Import a table from a (PostgreSQL) relational repository as a durable parquet
     * snapshot in the lake, then bootstrap it like any upload. Complementary to live
     * federation — this makes a copy; a federated repository passes queries through.
     */
    @PostMapping("/datasets/import")
    public ResponseEntity<DatasetCreatedResponse> importDatasetFromTable(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody ImportTableRequest request) {
        AuthenticatedUser caller = currentUser.require();
        DatasetRegistration created = datasetService.registerDatasetFromRepositoryTable(
                caller, projectId, repositoryId, request.tableName());
        return ResponseEntity
                .created(URI.create("/api/projects/" + projectId + "/repositories/" + repositoryId + "/datasets/" + created.dataset().id()))
                .body(DatasetCreatedResponse.from(created));
    }

    /**
     * Import one stream of a Singer tap as a parquet snapshot in the lake, then bootstrap it (T6).
     *
     * <p>Its own route rather than a mode on {@code /datasets/import}: a tap and a table are
     * different upstreams reached by different mechanisms, and one endpoint taking either a
     * {@code tableName} or a {@code stream} would be a body whose valid shape depends on a
     * repository the caller has to look up first.
     */
    @PostMapping("/datasets/import-stream")
    public ResponseEntity<DatasetCreatedResponse> importDatasetFromStream(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody ImportStreamRequest request) {
        AuthenticatedUser caller = currentUser.require();
        DatasetRegistration created = datasetService.registerDatasetFromSingerStream(
                caller, projectId, repositoryId, request.stream());
        return ResponseEntity
                .created(URI.create("/api/projects/" + projectId + "/repositories/" + repositoryId + "/datasets/" + created.dataset().id()))
                .body(DatasetCreatedResponse.from(created));
    }

    /**
     * Import one table of an Iceberg REST catalog as a parquet snapshot in the lake, then
     * bootstrap it (Gap 2). Its own route for the same reason {@code /datasets/import-stream} is:
     * a catalog table and a JDBC table are different upstreams reached by different mechanisms.
     * The federated alternative — no copy, {@code iceberg_scan} at query time — goes through the
     * plain {@code POST /datasets} registration with the table identifier as the dataset name.
     */
    @PostMapping("/datasets/import-iceberg")
    public ResponseEntity<DatasetCreatedResponse> importDatasetFromIcebergTable(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody ImportIcebergTableRequest request) {
        AuthenticatedUser caller = currentUser.require();
        DatasetRegistration created = datasetService.registerDatasetFromIcebergTable(
                caller, projectId, repositoryId, request.tableIdentifier());
        return ResponseEntity
                .created(URI.create("/api/projects/" + projectId + "/repositories/" + repositoryId + "/datasets/" + created.dataset().id()))
                .body(DatasetCreatedResponse.from(created));
    }

    @PutMapping("/datasets/{datasetId}")
    public ResponseEntity<DatasetResponse> updateDataset(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID datasetId,
            @Valid @RequestBody DatasetRequest request) {
        AuthenticatedUser caller = currentUser.require();
        Dataset domainModel = request.toDomain(datasetId, repositoryId);
        Dataset updated = datasetService.updateDataset(caller, projectId, repositoryId, domainModel);
        DataRepository repository = repositoryService.getRepository(caller, projectId, repositoryId);
        return ResponseEntity.ok(DatasetResponse.from(updated, DatasetAccessMode.of(repository, updated)));
    }

    @DeleteMapping("/datasets/{datasetId}")
    public ResponseEntity<Void> deleteDataset(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID datasetId) {
        AuthenticatedUser caller = currentUser.require();
        datasetService.deleteDataset(caller, projectId, repositoryId, datasetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/scan")
    public List<String> scanAvailableDatasets(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId) {
        AuthenticatedUser caller = currentUser.require();
        return repositoryService.listAvailableDatasets(caller, projectId, repositoryId);
    }

    @GetMapping("/preview")
    public DatasetPreview previewDataset(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @RequestParam String datasetName) {
        AuthenticatedUser caller = currentUser.require();
        return repositoryService.previewDataset(caller, projectId, repositoryId, datasetName);
    }

    @GetMapping("/datasets/{datasetId}/preview")
    public DatasetPreview previewDatasetById(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID datasetId) {
        AuthenticatedUser caller = currentUser.require();
        return datasetService.previewRawDataset(caller, projectId, repositoryId, datasetId);
    }

    /**
     * The column profiles already stored for a dataset (J3-F1) — read from the registry, never
     * computed: running profiling is the {@code profiling} operator's job, and a read that
     * silently re-profiled would supersede the stored generation it was asked to show.
     */
    @GetMapping("/datasets/{datasetId}/profiles")
    public DatasetProfilesResponse listColumnProfiles(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID datasetId) {
        AuthenticatedUser caller = currentUser.require();
        return DatasetProfilesResponse.from(datasetId,
                datasetService.listColumnProfiles(caller, projectId, repositoryId, datasetId));
    }

    @PostMapping("/datasets/{datasetId}/preview-wrapper")
    public DatasetPreview previewWrapper(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID datasetId,
            @RequestBody java.util.Map<String, String> body) {
        AuthenticatedUser caller = currentUser.require();
        String wrapperSql = body.get("wrapper");
        if (wrapperSql == null || wrapperSql.isBlank()) {
            throw new IllegalArgumentException("wrapper SQL is required");
        }
        return datasetService.previewWrapper(caller, projectId, repositoryId, datasetId, wrapperSql);
    }
}

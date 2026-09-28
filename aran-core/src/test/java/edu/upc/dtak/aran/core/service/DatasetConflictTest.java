package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dataset paths that used to answer an ordinary conflict with a generic 500 (UI audit J2-F12,
 * J4-F13, J2-F21): previewing a dataset with no stored snapshot threw an unmapped
 * {@link IllegalStateException}, and re-uploading under a name the repository already holds
 * uploaded every byte to the lake before the unique constraint refused the row.
 */
@DisplayName("DatasetService conflicts are 409s with a sentence, not 500s")
class DatasetConflictTest {

    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryService repositoryService = mock(DataRepositoryService.class);
    private final LakeClient lakeClient = mock(LakeClient.class);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
    private final AuthenticatedUser caller =
            new AuthenticatedUser(UUID.randomUUID(), "subject", tenantId, TenantRole.OWNER);

    private DatasetService service() {
        return new DatasetService(
                datasetRepository, repositoryService, lakeClient,
                new OperatorOrchestrator(List.of()), mock(JobService.class),
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                mock(IntegrationModelService.class), Optional.empty());
    }

    private void repositoryIs(DataRepository repository) {
        when(repositoryService.getRepository(caller, projectId, repositoryId)).thenReturn(repository);
    }

    private void datasetHasNoSnapshot(String name) {
        when(datasetRepository.findById(any(TenantContext.class), eq(datasetId)))
                .thenReturn(Optional.of(new Dataset(datasetId, repositoryId, name, "", null)));
    }

    private RelationalDatabaseRepository federatedPostgres() {
        return new RelationalDatabaseRepository(repositoryId, projectId, tenantId, "prod", "",
                "POSTGRESQL", "db-host", 5432, "db", "user", "pw", "public", true);
    }

    @Test
    @DisplayName("previewing a live dataset is a NO_SNAPSHOT conflict that says it is read live")
    void previewOfALiveDatasetIsAConflict() {
        repositoryIs(federatedPostgres());
        datasetHasNoSnapshot("venues");

        assertThatThrownBy(() -> service().previewRawDataset(caller, projectId, repositoryId, datasetId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("'venues' is read live")
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ConflictException.NO_SNAPSHOT);
        verifyNoInteractions(lakeClient);
    }

    @Test
    @DisplayName("an object-storage dataset with no copy is an upload that has not landed, not a live read (V35)")
    void objectStorageWithoutSnapshotIsNotCalledLive() {
        // SourceAccess resolves an uncopied object FEDERATED, but nothing enrols a bucket object
        // live yet (UI-OD13: "connect only — upload files"): "query it through its integrated
        // class" would name a class that does not exist.
        repositoryIs(new ObjectStorageRepository(repositoryId, projectId, tenantId, "bucket", "",
                "MINIO", "http://localhost:9000", "bucket", "AKID", "secret", "us-east-1"));
        datasetHasNoSnapshot("events.parquet");

        assertThatThrownBy(() -> service().previewRawDataset(caller, projectId, repositoryId, datasetId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("'events.parquet' has no stored data yet")
                .hasMessageNotContaining("read live");
        verifyNoInteractions(lakeClient);
    }

    @Test
    @DisplayName("a wrapper preview over a dataset whose upload has not landed says so, as a conflict")
    void wrapperPreviewWithoutSnapshotIsAConflict() {
        repositoryIs(new FileSystemRepository(repositoryId, projectId, tenantId, "files", ""));
        datasetHasNoSnapshot("papers");

        assertThatThrownBy(() -> service().previewWrapper(
                caller, projectId, repositoryId, datasetId, "SELECT * FROM ${SNAPSHOT}"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("'papers' has no stored data yet");
        verifyNoInteractions(lakeClient);
    }

    @Test
    @DisplayName("an upload under a name the repository already holds is refused before a byte reaches the lake")
    void duplicateUploadNameIsRefusedBeforeTheUpload() {
        repositoryIs(new FileSystemRepository(repositoryId, projectId, tenantId, "files", ""));
        when(datasetRepository.findAllByRepository(any(TenantContext.class), eq(repositoryId)))
                .thenReturn(List.of(new Dataset(UUID.randomUUID(), repositoryId, "acm.csv", "", "snap-0")));

        Dataset upload = new Dataset(datasetId, repositoryId, "acm.csv", "corrected");
        byte[] bytes = "id\n1\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service().registerDatasetWithFile(
                caller, projectId, repositoryId, upload, bytes, "text/csv", "acm.csv"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("'acm.csv' already exists in the repository 'files'")
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ConflictException.NAME_TAKEN);
        verifyNoInteractions(lakeClient);
    }
}

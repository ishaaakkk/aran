package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UI-OD8 (audit J2-F6): a dataset read live names its source table, so its name is not a label —
 * renaming one points every query over its class at a table that does not exist. The rename is
 * refused with a 409 that says so; an upload's rename still goes through (the UI warns that the
 * next re-bootstrap is refused as BREAKING drift, which d1-schema-drift depends on).
 */
@DisplayName("DatasetService — renaming a dataset read live is refused (UI-OD8)")
class DatasetServiceRenameTest {

    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryService repositoryService = mock(DataRepositoryService.class);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
    /** A repository of the caller's project the dataset does not belong to. */
    private final UUID otherRepositoryId = UUID.randomUUID();
    private final AuthenticatedUser caller =
            new AuthenticatedUser(UUID.randomUUID(), "subject", tenantId, TenantRole.MEMBER);

    private DatasetService service() {
        return new DatasetService(
                datasetRepository, repositoryService, mock(LakeClient.class),
                new OperatorOrchestrator(List.of()), mock(JobService.class),
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                mock(IntegrationModelService.class), Optional.empty());
    }

    private void given(DataRepository repository, String currentName) {
        when(repositoryService.getRepository(caller, projectId, repositoryId)).thenReturn(repository);
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(new Dataset(
                datasetId, repositoryId, currentName, "old description", null, List.of(), null,
                DatasetStatus.READY)));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    private Dataset edit(String name, String description) {
        return new Dataset(datasetId, repositoryId, name, description, null, List.of(), null, null);
    }

    private RelationalDatabaseRepository federatedPostgres() {
        return new RelationalDatabaseRepository(repositoryId, projectId, tenantId, "warehouse", "",
                "POSTGRESQL", "db.internal", 5432, "sales", "aran", "pw", "public", true);
    }

    @Test
    @DisplayName("renaming a dataset read live is refused with LIVE_DATASET_RENAME, and nothing is saved")
    void renamingALiveDatasetIsRefused() {
        given(federatedPostgres(), "orders");

        assertThatThrownBy(() -> service().updateDataset(caller, projectId, repositoryId, edit("orders_v2", "x")))
                .isInstanceOfSatisfying(StateConflictException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(DatasetService.LIVE_DATASET_RENAME);
                    assertThat(e.getMessage()).contains("orders").contains("read live");
                });
        verify(datasetRepository, never()).save(any(), any());
    }

    @Test
    @DisplayName("a live dataset's description can still be edited — only the name is its table")
    void aLiveDatasetsDescriptionStillSaves() {
        given(federatedPostgres(), "orders");

        Dataset saved = service().updateDataset(caller, projectId, repositoryId, edit("orders", "new words"));

        assertThat(saved.description()).isEqualTo("new words");
    }

    @Test
    @DisplayName("a dataset edited through a repository it does not belong to is not found — the guard reads the dataset's own repository")
    void aDatasetEditedThroughAnotherRepositoryIsNotFound() {
        // Review UA5 round 2: the live-rename guard resolved access against the repository named
        // in the URL. Naming an upload repository answered MATERIALIZED, and the live dataset was
        // renamed. The dataset must belong to the repository the path names — and so, through
        // getRepository's own check, to a project the caller can see.
        given(federatedPostgres(), "orders");
        when(repositoryService.getRepository(caller, projectId, otherRepositoryId)).thenReturn(
                new FileSystemRepository(otherRepositoryId, projectId, tenantId, "files", ""));

        assertThatThrownBy(() -> service().updateDataset(caller, projectId, otherRepositoryId, edit("orders_v2", "x")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(datasetRepository, never()).save(any(), any());
    }

    @Test
    @DisplayName("an upload can still be renamed — d1-schema-drift's drift trigger")
    void anUploadCanBeRenamed() {
        given(new FileSystemRepository(repositoryId, projectId, tenantId, "files", ""), "acm");

        Dataset saved = service().updateDataset(caller, projectId, repositoryId, edit("acm-renamed", "x"));

        assertThat(saved.name()).isEqualTo("acm-renamed");
    }
}

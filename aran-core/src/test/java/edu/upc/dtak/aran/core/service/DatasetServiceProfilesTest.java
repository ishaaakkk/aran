package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * J3-F1: the Profile tab reads the column profiles a dataset already has — bootstrap persists
 * them — instead of claiming there are none and inviting a re-run.
 */
class DatasetServiceProfilesTest {

    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryService repositoryService = mock(DataRepositoryService.class);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
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

    private static ColumnProfile profile(String column, String subject) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                10L, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                subject, null);
    }

    @Test
    @DisplayName("returns every stored profile generation of the dataset, after checking the caller may see the repository")
    void returnsStoredProfiles() {
        List<ColumnProfile> stored = List.of(
                profile("id", ColumnProfile.SUBJECT_SNAPSHOT),
                profile("id", ColumnProfile.SUBJECT_HARVESTED));
        when(datasetRepository.findById(any(TenantContext.class), eq(datasetId))).thenReturn(Optional.of(
                new Dataset(datasetId, repositoryId, "orders", "", "snap-1", stored)));

        assertThat(service().listColumnProfiles(caller, projectId, repositoryId, datasetId))
                .extracting(ColumnProfile::subject)
                .containsExactly(ColumnProfile.SUBJECT_SNAPSHOT, ColumnProfile.SUBJECT_HARVESTED);
        verify(repositoryService).getRepository(caller, projectId, repositoryId);
    }

    @Test
    @DisplayName("a dataset id from another repository is not found — the path's repository is not decoration")
    void refusesADatasetOfAnotherRepository() {
        when(datasetRepository.findById(any(TenantContext.class), eq(datasetId))).thenReturn(Optional.of(
                new Dataset(datasetId, UUID.randomUUID(), "orders", "", "snap-1",
                        List.of(profile("id", ColumnProfile.SUBJECT_SNAPSHOT)))));

        assertThatThrownBy(() -> service().listColumnProfiles(caller, projectId, repositoryId, datasetId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an unknown dataset is not found")
    void refusesAnUnknownDataset() {
        when(datasetRepository.findById(any(TenantContext.class), eq(datasetId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listColumnProfiles(caller, projectId, repositoryId, datasetId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * T1.8: dataset deletion must run the model-side cleanup ({@code detachDataset}) BEFORE the row
 * delete — after it, the enrolment count the cleanup logs is gone and (post-V37) the
 * model_source rows have already cascaded away.
 */
class DatasetServiceDeleteTest {

    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryService repositoryService = mock(DataRepositoryService.class);
    private final IntegrationModelService integrationModels = mock(IntegrationModelService.class);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();

    private DatasetService service() {
        return new DatasetService(
                datasetRepository, repositoryService, mock(LakeClient.class),
                new OperatorOrchestrator(List.of()), mock(JobService.class),
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                integrationModels, java.util.Optional.empty());
    }

    @Test
    @DisplayName("delete detaches the dataset from the integration model before deleting the row")
    void deleteDetachesBeforeDeletingTheRow() {
        AuthenticatedUser caller =
                new AuthenticatedUser(UUID.randomUUID(), "subject", tenantId, TenantRole.OWNER);

        service().deleteDataset(caller, projectId, repositoryId, datasetId);

        InOrder order = inOrder(integrationModels, datasetRepository);
        order.verify(integrationModels)
                .detachDataset(any(TenantContext.class), eq(projectId), eq(datasetId));
        order.verify(datasetRepository).delete(any(TenantContext.class), eq(datasetId));
    }
}

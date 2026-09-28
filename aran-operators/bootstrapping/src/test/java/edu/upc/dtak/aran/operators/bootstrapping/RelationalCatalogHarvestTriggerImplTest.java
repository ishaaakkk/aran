package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RelationalCatalogHarvestTriggerImpl} is a two-line composition, but the two lines could
 * still pass the wrong argument to the wrong call — this pins that {@code harvest()}'s result is
 * exactly what {@code persist()} receives, for the same tenant and repository handed in.
 */
class RelationalCatalogHarvestTriggerImplTest {

    private static final TenantContext TENANT = new TenantContext(UUID.randomUUID().toString());

    private static RelationalDatabaseRepository repo() {
        return new RelationalDatabaseRepository(UUID.randomUUID(), UUID.randomUUID(),
                UUID.fromString(TENANT.value()), "repo", "", "POSTGRESQL", "host", 5432,
                "db", "user", "pw", "public", true);
    }

    @Test
    @DisplayName("harvests the repository, then persists exactly that harvest for the same tenant/repository")
    void composesHarvestThenPersist() {
        RelationalCatalogHarvestService harvestService = mock(RelationalCatalogHarvestService.class);
        HarvestPersistenceService persistenceService = mock(HarvestPersistenceService.class);
        RelationalDatabaseRepository repo = repo();
        Harvest harvest = new Harvest(Map.of("t", 1L), Map.of(), java.util.List.of());
        when(harvestService.harvest(repo)).thenReturn(harvest);

        new RelationalCatalogHarvestTriggerImpl(harvestService, persistenceService)
                .harvest(TENANT, repo);

        verify(persistenceService).persist(TENANT, repo, harvest);
    }

    @Test
    @DisplayName("a harvest that resolves to EMPTY is still handed to persist, not special-cased here")
    void doesNotSpecialCaseEmptyHarvestItself() {
        // HarvestPersistenceService already knows how to no-op on Harvest.EMPTY (see its own
        // javadoc/tests) — this class must not duplicate that decision.
        RelationalCatalogHarvestService harvestService = mock(RelationalCatalogHarvestService.class);
        HarvestPersistenceService persistenceService = mock(HarvestPersistenceService.class);
        RelationalDatabaseRepository repo = repo();
        when(harvestService.harvest(repo)).thenReturn(Harvest.EMPTY);

        new RelationalCatalogHarvestTriggerImpl(harvestService, persistenceService)
                .harvest(TENANT, repo);

        verify(persistenceService).persist(TENANT, repo, Harvest.EMPTY);
    }
}

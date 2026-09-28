package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.RelationalCatalogHarvestTrigger;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The {@link RelationalCatalogHarvestTrigger} port, over {@link RelationalCatalogHarvestService}
 * (Task 2's JDBC connection) and {@link HarvestPersistenceService} (Task 3's write path).
 *
 * <p>Holds no harvest or persistence logic of its own — both halves are already best-effort and
 * already independently tested ({@code RelationalCatalogHarvestServiceTest} against a live
 * Postgres, {@code HarvestPersistenceServiceTest} against mocked ports). This class only composes
 * them behind the one method the core (Task 5's caller, {@code DatasetService}) is allowed to see.
 */
@NullMarked
@Component
public final class RelationalCatalogHarvestTriggerImpl implements RelationalCatalogHarvestTrigger {

    private final RelationalCatalogHarvestService harvestService;
    private final HarvestPersistenceService persistenceService;

    public RelationalCatalogHarvestTriggerImpl(RelationalCatalogHarvestService harvestService,
                                                HarvestPersistenceService persistenceService) {
        this.harvestService = harvestService;
        this.persistenceService = persistenceService;
    }

    @Override
    public void harvest(TenantContext tenant, RelationalDatabaseRepository repository) {
        Harvest harvest = harvestService.harvest(repository);
        persistenceService.persist(tenant, repository, harvest);
    }
}

package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.IntegrationRunMode;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.fake.FakeGraphStore;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The by-id model writes act only on a row of the project the URL names.
 *
 * <p>S2-1 gated the model routes on project visibility, but the repository's by-id writes are
 * tenant-wide ({@code findByIdAndTenantId}), so a caller could name a project they CAN see and
 * another project's mapping or constraint id under it. Each by-id write therefore resolves its row
 * inside the URL project first and answers "not yours" as not-found, the shape
 * {@code DataProductService.get(tenant, projectId, id)} gives a product.
 */
@DisplayName("IntegrationModelService — by-id writes stay inside the URL's project")
class IntegrationModelServiceProjectScopeTest {

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectA = UUID.randomUUID();
    private final UUID projectB = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final IntegrationModelService service = new IntegrationModelService(repo,
            new FakeGraphStore(), new OperatorOrchestrator(List.of()), new ObjectMapper(),
            mock(DatasetRepository.class), mock(DataProductService.class));

    private ModelMapping mappingOfB() {
        return service.addMapping(tenant, projectB, MappingKinds.CLASS,
                "http://ex/B1", "http://ex/B2", "PaperB", null, null);
    }

    private ModelConstraint constraintOfB(String origin) {
        IntegrationModel modelB = repo.getOrCreate(tenant, projectB);
        return repo.addConstraint(tenant, new ModelConstraint(UUID.randomUUID(),
                UUID.fromString(tenant.value()), modelB.id(), "http://ex/B1", "a > 1", false,
                Instant.now(), origin));
    }

    @Test
    @DisplayName("deleting another project's mapping through this project's URL is not-found, and"
            + " the mapping survives")
    void deleteMappingOfAnotherProject() {
        ModelMapping b = mappingOfB();

        assertThatThrownBy(() -> service.deleteMapping(tenant, projectA, b.id()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(repo.load(tenant, projectB).mappings()).extracting(ModelMapping::id)
                .containsExactly(b.id());
    }

    @Test
    @DisplayName("deciding another project's mapping through this project's URL is not-found, and"
            + " neither its status nor either model's state moves")
    void setMappingStatusOfAnotherProject() {
        ModelMapping b = mappingOfB();
        IntegrationModel modelB = repo.getOrCreate(tenant, projectB);
        IntegrationRun run = repo.startRun(tenant, modelB.id(), IntegrationRunMode.REBUILD, null, null);
        repo.markMappingsApplied(tenant, run.id(), UUID.randomUUID(), List.of(b.id()));

        assertThatThrownBy(() -> service.setMappingStatus(tenant, projectA, b.id(),
                ModelMapping.REJECTED))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(repo.load(tenant, projectB).mappings()).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(ModelMapping.ACCEPTED));
        assertThat(repo.getOrCreate(tenant, projectA).rebuildRequired())
                .as("A's model is not flagged for a mapping it does not own").isFalse();
    }

    @Test
    @DisplayName("enforcing another project's DISCOVERED constraint through this project's URL is"
            + " refused — the V43 guard is not bypassed by naming a different project")
    void enforceDiscoveredConstraintOfAnotherProject() {
        ModelConstraint b = constraintOfB("DISCOVERED");

        assertThatThrownBy(() -> service.setConstraintEnforced(tenant, projectA, b.id(), true))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(repo.findEnforcedConstraints(tenant)).isEmpty();
    }

    @Test
    @DisplayName("toggling or deleting another project's MANUAL constraint through this project's"
            + " URL is not-found, and the row is unchanged")
    void toggleAndDeleteConstraintOfAnotherProject() {
        ModelConstraint b = constraintOfB("MANUAL");

        assertThatThrownBy(() -> service.setConstraintEnforced(tenant, projectA, b.id(), true))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deleteConstraint(tenant, projectA, b.id()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(repo.load(tenant, projectB).constraints()).singleElement()
                .satisfies(c -> assertThat(c.enforced()).isFalse());
    }

    @Test
    @DisplayName("the owning project's URL still reaches the same rows")
    void ownProjectStillWorks() {
        ModelMapping b = mappingOfB();
        ModelConstraint manual = constraintOfB("MANUAL");
        ModelConstraint discovered = constraintOfB("DISCOVERED");

        service.setMappingStatus(tenant, projectB, b.id(), ModelMapping.REJECTED);
        service.setConstraintEnforced(tenant, projectB, manual.id(), true);
        assertThatThrownBy(() -> service.setConstraintEnforced(tenant, projectB, discovered.id(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence only");

        assertThat(repo.load(tenant, projectB).mappings()).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(ModelMapping.REJECTED));
        assertThat(repo.findEnforcedConstraints(tenant)).extracting(ModelConstraint::id)
                .containsExactly(manual.id());

        service.deleteMapping(tenant, projectB, b.id());
        service.deleteConstraint(tenant, projectB, manual.id());
        assertThat(repo.load(tenant, projectB).mappings()).isEmpty();
        assertThat(repo.load(tenant, projectB).constraints()).extracting(ModelConstraint::id)
                .containsExactly(discovered.id());
    }
}

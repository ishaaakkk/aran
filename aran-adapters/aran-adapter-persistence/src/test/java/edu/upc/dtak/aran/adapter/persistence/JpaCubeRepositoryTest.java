package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.CubeDefinitionEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.CubeDefinitionJpaRepository;
import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The adapter round trip for a cube (V40): what {@link JpaCubeRepository} is worth testing is its
 * hand-written {@link CubeRecord} &harr; {@link CubeDefinitionEntity} translation and its tenant
 * predicates — {@link CubeDefinitionJpaRepository} is a Spring Data interface with no logic of its
 * own.
 *
 * <p>No Testcontainers, following {@code JpaDataProductRepositoryTest}: the mock JPA layer hands
 * back whatever entity it was given, standing in for a save-then-reload of an unmodified row. The
 * other half of the correctness proof — that V40 as Flyway applies it matches what
 * {@code CubeDefinitionEntity} declares — is Hibernate {@code ddl-auto: validate} against a real
 * Postgres, run by {@code ./scripts/validate.sh}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JpaCubeRepository — cube round trip and tenant scoping")
class JpaCubeRepositoryTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantContext TENANT = new TenantContext(TENANT_ID.toString());

    @Mock
    private CubeDefinitionJpaRepository jpa;

    private static CubeRecord record(UUID id, UUID tenantId, UUID projectId, String name) {
        return new CubeRecord(id, tenantId, projectId, name, "monthly revenue",
                "http://ex/Order",
                "{\"measures\":[{\"name\":\"amount\"}],\"dimensions\":[],\"calendar\":null}",
                "[\"" + UUID.randomUUID() + "\"]",
                UUID.randomUUID(), "00000000-0000-0000-0000-000000000002", Instant.now());
    }

    @Test
    @DisplayName("a saved cube reloads with every field it was saved with, JSON included")
    void roundTripsEveryField() {
        JpaCubeRepository repository = new JpaCubeRepository(jpa);
        CubeRecord cube = record(UUID.randomUUID(), TENANT_ID, UUID.randomUUID(), "sales");
        // Stands in for a real INSERT + reload of an unmodified row.
        when(jpa.save(any(CubeDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CubeRecord reloaded = repository.save(TENANT, cube);

        assertThat(reloaded).isEqualTo(cube);
    }

    @Test
    @DisplayName("the definition JSON is stored verbatim — the adapter never parses it")
    void storesDefinitionJsonOpaquely() {
        JpaCubeRepository repository = new JpaCubeRepository(jpa);
        CubeRecord cube = record(UUID.randomUUID(), TENANT_ID, UUID.randomUUID(), "sales");
        when(jpa.save(any(CubeDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        repository.save(TENANT, cube);

        ArgumentCaptor<CubeDefinitionEntity> captor =
                ArgumentCaptor.forClass(CubeDefinitionEntity.class);
        verify(jpa).save(captor.capture());
        assertThat(captor.getValue().getDefinitionJson()).isEqualTo(cube.definitionJson());
        assertThat(captor.getValue().getProductIdsJson()).isEqualTo(cube.productIdsJson());
    }

    @Test
    @DisplayName("a description and a creator may both be absent — both columns are nullable")
    void roundTripsNullDescriptionAndCreator() {
        JpaCubeRepository repository = new JpaCubeRepository(jpa);
        CubeRecord cube = new CubeRecord(UUID.randomUUID(), TENANT_ID, UUID.randomUUID(), "sales",
                null, "http://ex/Order", "{}", "[]", UUID.randomUUID(), null, Instant.now());
        when(jpa.save(any(CubeDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CubeRecord reloaded = repository.save(TENANT, cube);

        assertThat(reloaded.description()).isNull();
        assertThat(reloaded.createdBy()).isNull();
    }

    @Test
    @DisplayName("saving into a tenant the cube does not belong to is refused, not silently rewritten")
    void refusesCrossTenantSave() {
        JpaCubeRepository repository = new JpaCubeRepository(jpa);
        CubeRecord foreign = record(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sales");

        assertThatThrownBy(() -> repository.save(TENANT, foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
        verify(jpa, never()).save(any(CubeDefinitionEntity.class));
    }

    @Test
    @DisplayName("find and list name the tenant themselves — the @Filter is not the boundary")
    void readsAreTenantScoped() {
        JpaCubeRepository repository = new JpaCubeRepository(jpa);
        UUID projectId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        CubeRecord cube = record(id, TENANT_ID, projectId, "sales");
        CubeDefinitionEntity entity = new CubeDefinitionEntity(cube.id(), cube.tenantId(),
                cube.projectId(), cube.name(), cube.description(), cube.factClassIri(),
                cube.definitionJson(), cube.productIdsJson(), cube.schemaVersionId(),
                cube.createdBy(), cube.createdAt());

        when(jpa.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));
        when(jpa.findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(TENANT_ID, projectId))
                .thenReturn(List.of(entity));

        assertThat(repository.find(TENANT, id)).contains(cube);
        assertThat(repository.list(TENANT, projectId)).containsExactly(cube);
    }

    @Test
    @DisplayName("deleting another tenant's cube is a no-op, not a delete")
    void deleteIsTenantScoped() {
        JpaCubeRepository repository = new JpaCubeRepository(jpa);
        UUID id = UUID.randomUUID();
        when(jpa.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.empty());

        repository.delete(TENANT, id);

        verify(jpa, never()).delete(any(CubeDefinitionEntity.class));
    }
}

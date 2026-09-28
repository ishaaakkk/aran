package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.DataProductEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.DataProductJpaRepository;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The adapter round trip for {@code access_mode} (Phase 5, V34): a {@link DataProduct} saved
 * through {@link JpaDataProductRepository} must come back carrying the access mode it was saved
 * with, for both {@link ProductAccessMode} values.
 *
 * <p>No Testcontainers here — {@code persistence/src/test} has no bespoke migration harness, and
 * {@link DataProductJpaRepository} is a Spring Data interface with no logic of its own. What is
 * worth unit-testing is {@link JpaDataProductRepository}'s hand-written entity&lt;-&gt;domain
 * translation (the {@code new DataProductEntity(...)}/{@code toDomain} pair this task edited), so
 * the mock JPA layer here just hands back whatever entity it was given — standing in for a
 * save-then-reload of an unmodified row. The other half of the correctness proof — that V34 as
 * actually applied by Flyway matches what {@code DataProductEntity} declares — is Hibernate
 * {@code ddl-auto: validate} against a real Postgres, run by {@code ./scripts/validate.sh}
 * (Docker), not this test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JpaDataProductRepository — access_mode round trip")
class JpaDataProductRepositoryTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final TenantContext TENANT = new TenantContext(TENANT_ID.toString());

    @Mock
    private DataProductJpaRepository jpa;

    @ParameterizedTest
    @EnumSource(ProductAccessMode.class)
    @DisplayName("a newly saved product reloads with the access mode it was saved with")
    void roundTripsAccessModeOnInsert(ProductAccessMode mode) {
        JpaDataProductRepository repository = new JpaDataProductRepository(jpa);
        UUID id = UUID.randomUUID();
        DataProduct product = DataProduct.draft(id, TENANT_ID, UUID.randomUUID(), "p", null,
                "SELECT Paper.title FROM Paper", UUID.randomUUID(), null, Instant.now(), mode);

        when(jpa.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.empty());
        // Stands in for a real INSERT + reload of an unmodified row.
        when(jpa.save(any(DataProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DataProduct reloaded = repository.save(TENANT, product);

        assertThat(reloaded.accessMode()).isEqualTo(mode);
    }

    @ParameterizedTest
    @EnumSource(ProductAccessMode.class)
    @DisplayName("re-saving an existing row (an update) still reloads with its original access mode")
    void roundTripsAccessModeOnUpdate(ProductAccessMode mode) {
        JpaDataProductRepository repository = new JpaDataProductRepository(jpa);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        DataProduct original = DataProduct.draft(id, TENANT_ID, UUID.randomUUID(), "p", null,
                "SELECT Paper.title FROM Paper", UUID.randomUUID(), null, now, mode);
        DataProductEntity existingRow = new DataProductEntity(
                id, TENANT_ID, original.projectId(), original.name(), original.description(),
                original.oql(), original.schemaVersionId(), mode, original.status(),
                original.snapshotId(), original.rowCount(), original.outputSchema(),
                original.materializedAt(), original.lastError(), original.createdBy(),
                original.createdAt(), null);

        // accessMode is updatable=false (fixed at creation, like schemaVersionId): the update path
        // never calls setAccessMode, so this proves the *existing row's* mode survives a status
        // transition on an already-persisted product, not just a fresh insert.
        when(jpa.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(existingRow));
        when(jpa.save(any(DataProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DataProduct reloaded = repository.save(TENANT, original.withStatus(DataProductStatus.READY));

        assertThat(reloaded.accessMode()).isEqualTo(mode);
    }

    @Test
    @DisplayName("a build's version is written on update and read back (UI-OD12)")
    void roundTripsTheVersionABuildRanAgainst() {
        JpaDataProductRepository repository = new JpaDataProductRepository(jpa);
        UUID id = UUID.randomUUID();
        UUID builtAgainst = UUID.randomUUID();
        Instant now = Instant.now();
        DataProduct draft = DataProduct.draft(id, TENANT_ID, UUID.randomUUID(), "p", null,
                "SELECT Paper.title FROM Paper", UUID.randomUUID(), null, now);
        DataProductEntity existingRow = new DataProductEntity(
                id, TENANT_ID, draft.projectId(), draft.name(), draft.description(), draft.oql(),
                draft.schemaVersionId(), draft.accessMode(), draft.status(), null, null, null,
                null, null, null, now, null);
        when(jpa.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(existingRow));
        when(jpa.save(any(DataProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DataProduct reloaded = repository.save(TENANT, draft.materialized("snap-1", 3L, null, now, builtAgainst));

        assertThat(reloaded.builtAgainstVersionId()).isEqualTo(builtAgainst);
        assertThat(existingRow.getBuiltAgainstVersionId()).isEqualTo(builtAgainst);
    }

    @Test
    @DisplayName("a draft with no explicit mode round-trips as MATERIALIZED")
    void draftDefaultsToMaterializedEndToEnd() {
        JpaDataProductRepository repository = new JpaDataProductRepository(jpa);
        UUID id = UUID.randomUUID();
        DataProduct product = DataProduct.draft(id, TENANT_ID, UUID.randomUUID(), "p", null,
                "SELECT Paper.title FROM Paper", UUID.randomUUID(), null, Instant.now());

        when(jpa.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.empty());
        when(jpa.save(any(DataProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(repository.save(TENANT, product).accessMode())
                .isEqualTo(ProductAccessMode.MATERIALIZED);
    }
}

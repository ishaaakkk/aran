package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code accessMode} (Phase 5, V34). The compat rule this guards: the 9-arg
 * {@link DataProduct#draft} must keep defaulting every existing caller to
 * {@link ProductAccessMode#MATERIALIZED} — nothing else about an existing product's
 * construction changes in this task.
 */
class DataProductTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SCHEMA_VERSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("the pre-existing 9-arg draft defaults to MATERIALIZED")
    void draftDefaultsToMaterialized() {
        DataProduct product = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW);

        assertThat(product.accessMode()).isEqualTo(ProductAccessMode.MATERIALIZED);
    }

    @Test
    @DisplayName("the new 10-arg overload accepts an explicit VIRTUAL mode")
    void draftOverloadAcceptsVirtual() {
        DataProduct product = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW,
                ProductAccessMode.VIRTUAL);

        assertThat(product.accessMode()).isEqualTo(ProductAccessMode.VIRTUAL);
    }

    @Test
    @DisplayName("the new 10-arg overload can also ask for MATERIALIZED explicitly")
    void draftOverloadAcceptsMaterialized() {
        DataProduct product = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW,
                ProductAccessMode.MATERIALIZED);

        assertThat(product.accessMode()).isEqualTo(ProductAccessMode.MATERIALIZED);
    }

    @Test
    @DisplayName("withStatus/failed carry accessMode through untouched, on either mode")
    void accessModeSurvivesLifecycleTransitions() {
        DataProduct virtual = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW,
                ProductAccessMode.VIRTUAL);

        assertThat(virtual.withStatus(DataProductStatus.READY).accessMode())
                .isEqualTo(ProductAccessMode.VIRTUAL);
        assertThat(virtual.failed("boom").accessMode())
                .isEqualTo(ProductAccessMode.VIRTUAL);
    }

    @Test
    @DisplayName("a null accessMode is rejected, same as a null status")
    void rejectsNullAccessMode() {
        assertThatThrownBy(() -> new DataProduct(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, DataProductStatus.DRAFT,
                null, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessMode must not be null");
    }

    // ── Task 2: VIRTUAL ⟹ snapshotId == null ────────────────────────────────────

    @Test
    @DisplayName("constructing a VIRTUAL product with a non-null snapshotId throws")
    void rejectsVirtualWithSnapshotId() {
        assertThatThrownBy(() -> new DataProduct(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, ProductAccessMode.VIRTUAL,
                DataProductStatus.READY, "snap-1", 10L, null, NOW, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIRTUAL")
                .hasMessageContaining("snapshotId");
    }

    @Test
    @DisplayName("materialized() rejects a VIRTUAL product — there is no legal snapshot for one to carry")
    void materializedRejectsVirtualProduct() {
        DataProduct virtual = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW,
                ProductAccessMode.VIRTUAL);

        assertThatThrownBy(() -> virtual.materialized("snap-1", 10L, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIRTUAL");
    }

    @Test
    @DisplayName("virtualReady() reaches READY with the planned schema and no snapshot")
    void virtualReadyReachesServableState() {
        DataProduct virtual = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW,
                ProductAccessMode.VIRTUAL);
        String schema = "[{\"name\":\"title\",\"typeName\":\"VARCHAR\",\"jdbcType\":12}]";

        DataProduct ready = virtual.virtualReady(schema);

        assertThat(ready.status()).isEqualTo(DataProductStatus.READY);
        assertThat(ready.snapshotId()).isNull();
        assertThat(ready.outputSchema()).isEqualTo(schema);
        assertThat(ready.accessMode()).isEqualTo(ProductAccessMode.VIRTUAL);
    }

    @Test
    @DisplayName("virtualReady() refuses a MATERIALIZED product — that mode reaches READY by materializing")
    void virtualReadyRejectsMaterializedProduct() {
        DataProduct materialized = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW);

        assertThatThrownBy(() -> materialized.virtualReady(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VIRTUAL");
    }

    // ── UI-OD12: the version a MATERIALIZED product was last built against ────────────────────

    @Test
    @DisplayName("a build records the schema version it ran against, beside the one the product was defined against")
    void materializedRecordsTheVersionItWasBuiltAgainst() {
        UUID v3 = UUID.randomUUID();
        DataProduct product = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW);
        assertThat(product.builtAgainstVersionId()).as("never built").isNull();

        DataProduct ready = product.materialized("snap-1", 10L, null, NOW, v3);

        assertThat(ready.builtAgainstVersionId()).isEqualTo(v3);
        assertThat(ready.schemaVersionId())
                .as("the definition-time version is a separate fact and does not move")
                .isEqualTo(SCHEMA_VERSION);
    }

    @Test
    @DisplayName("going STALE or failing a later refresh keeps the version the last good build ran against")
    void builtAgainstSurvivesStaleAndFailedTransitions() {
        UUID v3 = UUID.randomUUID();
        DataProduct ready = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW)
                .materialized("snap-1", 10L, null, NOW, v3);

        assertThat(ready.withStatus(DataProductStatus.STALE).builtAgainstVersionId()).isEqualTo(v3);
        assertThat(ready.withStatus(DataProductStatus.MATERIALIZING).builtAgainstVersionId()).isEqualTo(v3);
        assertThat(ready.failed("boom").builtAgainstVersionId()).isEqualTo(v3);
    }

    @Test
    @DisplayName("a VIRTUAL product never records a build — it has none")
    void virtualProductsRecordNoBuild() {
        DataProduct ready = DataProduct.draft(UUID.randomUUID(), TENANT, PROJECT, "p", null,
                "SELECT Paper.title FROM Paper", SCHEMA_VERSION, null, NOW,
                ProductAccessMode.VIRTUAL).virtualReady(null);

        assertThat(ready.builtAgainstVersionId()).isNull();
    }
}

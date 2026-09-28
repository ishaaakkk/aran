package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A data product: a named, versioned OQL view over a <em>pinned</em> schema version. A
 * {@code MATERIALIZED} product (the only mode before Phase 5, and still the default) computes a
 * parquet snapshot in the lake and serves that; a {@code VIRTUAL} product (Phase 5) has no
 * snapshot at all (V18 migration owns the table; V34 adds {@code accessMode}).
 *
 * <p>{@code schemaVersionId} is captured at creation from the tenant's ACTIVE
 * {@link GraphVersion} and never changes, but it is a record, not an execution pin: a
 * MATERIALIZED product's re-materialization always runs its OQL against whatever schema
 * version is ACTIVE at the time, never against this stored id. The id's only job is
 * drift detection — when a newer version goes ACTIVE and no longer matches it, the
 * product flips to {@link DataProductStatus#STALE} but keeps serving its (now
 * possibly-outdated) snapshot until refreshed. Re-pinning is creating a new product
 * (v1 limitation, stated in the UI copy).
 *
 * @param accessMode    {@link ProductAccessMode#MATERIALIZED} or {@link ProductAccessMode#VIRTUAL}
 *                      (V34); fixed at creation, like {@code schemaVersionId}
 * @param snapshotId    the lake snapshot holding the materialized parquet; {@code null} until a
 *                      MATERIALIZED product reaches READY — and permanently {@code null} for a
 *                      VIRTUAL one (Phase 5), which never has a snapshot at all, enforced by the
 *                      compact constructor below
 * @param rowCount      rows in the materialized snapshot; {@code null} until a MATERIALIZED
 *                      product reaches READY — and permanently {@code null} for a VIRTUAL one,
 *                      which has no materialized rows to count
 * @param outputSchema  the product's columns as {@link ProductColumn} JSON (V29), so the JDBC
 *                      {@code products} schema can describe the table without reading the lake —
 *                      described off the parquet file for a MATERIALIZED product, planned from
 *                      the OQL's row type with no execution for a VIRTUAL one (Phase 5);
 *                      {@code null} for a MATERIALIZED product last materialized before V29
 * @param lastError     the last materialization failure; {@code null} unless FAILED
 * @param builtAgainstVersionId the schema version the engine planned the last successful build
 *                      against (UI-OD12, V47) — provenance, not a pin: a refresh always runs under
 *                      whatever is ACTIVE, so this is what a MATERIALIZED product's rows actually
 *                      mean, where {@code schemaVersionId} is only the version it was defined
 *                      against. It survives STALE and a failed refresh (the last good build is what
 *                      is still served), and is {@code null} for a product never built, a VIRTUAL
 *                      product (no build exists), and one last built before V47 recorded it
 */
@NullMarked
public record DataProduct(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String name,
        @Nullable String description,
        String oql,
        UUID schemaVersionId,
        ProductAccessMode accessMode,
        DataProductStatus status,
        @Nullable String snapshotId,
        @Nullable Long rowCount,
        @Nullable String outputSchema,
        @Nullable Instant materializedAt,
        @Nullable String lastError,
        @Nullable UUID createdBy,
        Instant createdAt,
        @Nullable UUID builtAgainstVersionId
) {

    /** The shape before UI-OD12: a product with no recorded build version. */
    public DataProduct(UUID id, UUID tenantId, UUID projectId, String name,
                       @Nullable String description, String oql, UUID schemaVersionId,
                       ProductAccessMode accessMode, DataProductStatus status,
                       @Nullable String snapshotId, @Nullable Long rowCount,
                       @Nullable String outputSchema, @Nullable Instant materializedAt,
                       @Nullable String lastError, @Nullable UUID createdBy, Instant createdAt) {
        this(id, tenantId, projectId, name, description, oql, schemaVersionId, accessMode, status,
                snapshotId, rowCount, outputSchema, materializedAt, lastError, createdBy, createdAt,
                null);
    }

    public DataProduct {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (oql == null || oql.isBlank()) {
            throw new IllegalArgumentException("oql must not be blank");
        }
        if (accessMode == null) {
            throw new IllegalArgumentException("accessMode must not be null");
        }
        if (accessMode == ProductAccessMode.VIRTUAL && snapshotId != null) {
            // The structural fact Phase 5 rests on: a virtual product has no snapshot, ever —
            // it serves by expanding its OQL at read time (see the class javadoc). This is what
            // makes materialized(...) reject a VIRTUAL product below, correctly: there is no
            // legal snapshotId for one to carry.
            throw new IllegalArgumentException("a VIRTUAL product must not have a snapshotId");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    /**
     * A freshly created MATERIALIZED product: validated OQL, pinned version, never materialized.
     *
     * <p>Kept for every pre-Phase-5 caller: delegates to the {@link ProductAccessMode}-taking
     * overload below with {@link ProductAccessMode#MATERIALIZED}, so this signature's behavior is
     * unchanged.
     */
    public static DataProduct draft(UUID id, UUID tenantId, UUID projectId, String name,
                                    @Nullable String description, String oql, UUID schemaVersionId,
                                    @Nullable UUID createdBy, Instant createdAt) {
        return draft(id, tenantId, projectId, name, description, oql, schemaVersionId,
                createdBy, createdAt, ProductAccessMode.MATERIALIZED);
    }

    /** A freshly created product with an explicit {@link ProductAccessMode} (Phase 5). */
    public static DataProduct draft(UUID id, UUID tenantId, UUID projectId, String name,
                                    @Nullable String description, String oql, UUID schemaVersionId,
                                    @Nullable UUID createdBy, Instant createdAt,
                                    ProductAccessMode accessMode) {
        return new DataProduct(id, tenantId, projectId, name, description, oql, schemaVersionId,
                accessMode, DataProductStatus.DRAFT, null, null, null, null, null,
                createdBy, createdAt);
    }

    /** This product with a new status, everything else untouched. */
    public DataProduct withStatus(DataProductStatus newStatus) {
        return new DataProduct(id, tenantId, projectId, name, description, oql, schemaVersionId,
                accessMode, newStatus, snapshotId, rowCount, outputSchema, materializedAt,
                lastError, createdBy, createdAt, builtAgainstVersionId);
    }

    /**
     * This product materialized: READY with a snapshot, row count, output schema and timestamp.
     *
     * @param newOutputSchema the result's columns as {@link ProductColumn} JSON; {@code null} when
     *                        the engine did not report them, which leaves the table listed but
     *                        undescribed rather than failing a materialization over metadata
     */
    public DataProduct materialized(String newSnapshotId, long newRowCount,
                                    @Nullable String newOutputSchema, Instant at,
                                    @Nullable UUID builtAgainst) {
        return new DataProduct(id, tenantId, projectId, name, description, oql, schemaVersionId,
                accessMode, DataProductStatus.READY, newSnapshotId, newRowCount, newOutputSchema,
                at, null, createdBy, createdAt, builtAgainst);
    }

    /** As above, for a caller that cannot say which version it built against (fixtures): none is recorded. */
    public DataProduct materialized(String newSnapshotId, long newRowCount,
                                    @Nullable String newOutputSchema, Instant at) {
        return materialized(newSnapshotId, newRowCount, newOutputSchema, at, null);
    }

    /**
     * This VIRTUAL product reaching its servable state (Phase 5, ruling R1): READY with the
     * columns definition-time OQL planning produced, and no snapshot — a virtual product never
     * materializes. The VIRTUAL counterpart to {@link #materialized}: that method is now
     * MATERIALIZED-only (the compact constructor's {@code accessMode}/{@code snapshotId}
     * invariant rejects a VIRTUAL product carrying a snapshot), and this is VIRTUAL-only for the
     * same reason, checked explicitly here because the constructor has no snapshot-shaped signal
     * to catch the opposite mistake.
     *
     * @param outputSchema the planned columns as {@link ProductColumn} JSON (V29); nullable for
     *                      the same reason {@link #materialized} accepts a null one — an engine
     *                      that reported no columns must not fail creation over metadata
     * @throws IllegalStateException when called on a MATERIALIZED product
     */
    public DataProduct virtualReady(@Nullable String outputSchema) {
        if (accessMode != ProductAccessMode.VIRTUAL) {
            throw new IllegalStateException(
                    "virtualReady is for VIRTUAL products only, got " + accessMode);
        }
        return new DataProduct(id, tenantId, projectId, name, description, oql, schemaVersionId,
                accessMode, DataProductStatus.READY, null, null, outputSchema, null, null,
                createdBy, createdAt);
    }

    /** This product failed to materialize: FAILED carrying the error. */
    public DataProduct failed(String error) {
        return new DataProduct(id, tenantId, projectId, name, description, oql, schemaVersionId,
                accessMode, DataProductStatus.FAILED, snapshotId, rowCount, outputSchema,
                materializedAt, error, createdBy, createdAt, builtAgainstVersionId);
    }
}

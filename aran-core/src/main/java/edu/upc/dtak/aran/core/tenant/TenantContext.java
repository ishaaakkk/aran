package edu.upc.dtak.aran.core.tenant;

import org.jspecify.annotations.NullMarked;

/**
 * Identifies the tenant a unit of work belongs to.
 *
 * <p>Per the multi-tenancy ADR, Aran uses a <strong>shared schema with a
 * discriminator column</strong>. Every tenant-owned row carries this id, and
 * every query is filtered by it (enforced centrally — see
 * {@code aran-adapter-persistence}'s Hibernate filter wiring rather than
 * left to each query). This record is the in-memory representation of that
 * discriminator.
 *
 * @param value the tenant identifier; stable, never blank
 */
@NullMarked
public record TenantContext(String value) {

    public TenantContext {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
    }
}

package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.jspecify.annotations.NullMarked;

/**
 * Drops a tenant's cached mapping catalog so the next build re-reads everything a catalog is
 * derived from — including facts that change <em>without</em> a graph-version promotion.
 *
 * <p>A port, for the same reason as {@link RelationalCatalogHarvestTrigger}: the cache it invalidates
 * lives in {@code MappingCatalogOperator} (aran-operators/query-engine), while the writer that must
 * trigger the invalidation — {@code HarvestPersistenceService} (aran-operators/bootstrapping) — is
 * on the other side of the module graph, and neither operator depends on the other. Both depend on
 * the core, so the core owns the seam.
 *
 * <p><b>Why this exists (Phase 4B review Important 3).</b> {@code MappingCatalogOperator} caches one
 * catalog per tenant, keyed by the active graph version, so a promotion is a cache miss. But a
 * relational catalog harvest writes {@code declared_foreign_key} rows and HARVESTED profiles
 * <em>without</em> promoting the graph — and a refresh-cadence harvest that <em>removes</em> a
 * foreign key the source dropped would otherwise leave the stale DECLARED edge baked into the cached
 * catalog, so join elimination would keep firing on a foreign key that no longer exists until the
 * next unrelated promotion. Invalidating from the harvest-persistence path closes that
 * stale-removal window: the removal reaches the planner on the next catalog build.
 */
@NullMarked
public interface CatalogCacheInvalidator {

    /**
     * Evict {@code tenant}'s cached mapping catalog, if any. Idempotent and cheap — a no-op when
     * nothing is cached — so a caller may invoke it whenever it has changed a fact a catalog build
     * reads, without first checking whether a catalog is currently resident.
     */
    void invalidate(TenantContext tenant);
}

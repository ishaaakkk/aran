package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence port for {@link DeclaredForeignKey} — the DECLARED-tier evidence a relational
 * catalog harvest (Phase 4B) produces. {@code aran-operators/bootstrapping}'s harvest-persistence
 * step writes here after a live harvest; {@code MappingCatalogOperator} (aran-operators/query-engine)
 * reads here at every catalog build to resolve harvested foreign keys into {@code
 * MappingCatalog.ReferentialEdge}s.
 */
@NullMarked
public interface DeclaredForeignKeyRepository {

    /**
     * Every declared foreign key harvested for any relational repository in this tenant — a
     * catalog build's input. Not scoped to one repository because one catalog build resolves
     * edges across every integrated class regardless of which repository backs it.
     */
    List<DeclaredForeignKey> findAllByTenant(TenantContext tenant);

    /**
     * Replaces every foreign key previously harvested for {@code repositoryId} with the freshly
     * harvested set — never a merge. A table dropped from the source database (or a foreign key
     * dropped from a table that remains) must not leave a stale DECLARED edge behind: the harvest
     * that no longer sees it is the authority on what currently holds.
     */
    void replaceForRepository(TenantContext tenant, UUID repositoryId, List<DeclaredForeignKey> foreignKeys);
}

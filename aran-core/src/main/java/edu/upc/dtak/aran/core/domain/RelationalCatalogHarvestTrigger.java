package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.jspecify.annotations.NullMarked;

/**
 * Reads a relational repository's catalog (row counts, column NDV, declared foreign keys) and
 * persists the result for every dataset under it — best-effort, and never throws.
 *
 * <p>A port, because the JDBC connection ({@code RelationalCatalogHarvestService}, Phase 4B Task 2)
 * and the write path ({@code HarvestPersistenceService}, Task 3) both live in {@code
 * aran-operators/bootstrapping}, while the callers here — {@code DatasetService}, at
 * relational-dataset enrolment and on the refresh cadence (Task 5) — are in the core, which
 * depends on no operator by construction. The same reasoning as {@link TableExtractor}.
 *
 * <p>Void, not {@code Harvest}: every caller wants the side effect (fresh HARVESTED profiles and
 * DECLARED foreign-key edges reaching the query planner), never the value, so handing one back
 * would only tempt a caller in the core to inspect or persist it itself — exactly the persistence
 * decision this port exists to keep on the operator side of the boundary.
 */
@NullMarked
public interface RelationalCatalogHarvestTrigger {

    /**
     * Harvest {@code repository}'s catalog and persist whatever was learned.
     *
     * <p>Best-effort end to end: an unreachable host, a rejected password, a missing capability,
     * or a partial catalog read all degrade to a WARN log with no exception reaching the caller —
     * see {@code RelationalCatalogHarvestService} and {@code HarvestPersistenceService}'s own
     * javadoc for the exact degradation contract (including how a partial FK-only failure is now
     * told apart from "this schema genuinely has no foreign keys" and never wipes prior evidence).
     * Cheap enough to call inline, whether on a request thread (enrolment) or inside a job
     * (materialize/refresh) — it reads catalog views only, never table data.
     */
    void harvest(TenantContext tenant, RelationalDatabaseRepository repository);
}

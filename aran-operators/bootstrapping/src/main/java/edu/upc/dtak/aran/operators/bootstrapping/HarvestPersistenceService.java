package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.CatalogCacheInvalidator;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKeyRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Writes a {@link Harvest} into durable storage — the persistence half of Phase 4B Task 3
 * ({@link RelationalCatalogHarvestService}, Task 2, produces the {@code Harvest} but persists
 * nothing). Task 5 (harvest trigger + cadence) is this class's caller: at relational-dataset
 * enrolment and on the refresh cadence, it obtains a fresh {@code Harvest} and calls {@link
 * #persist} here.
 *
 * <p>Two independent writes, because the two feeds have unrelated persistence homes (see the
 * phase's task-3 report for the comparison this class implements the decision of):
 * <ul>
 *   <li><b>Row count / NDV</b> → {@link HarvestedColumnProfiles}, merged onto the matching {@link
 *       Dataset}'s existing {@code columns()} and saved through the ordinary {@link
 *       DatasetRepository} — the same store SNAPSHOT/WRAPPER_OUTPUT profiles already live in, so
 *       {@code ProfileStatsSource}/{@code MappingCatalogOperator} pick it up with no changes of
 *       their own.</li>
 *   <li><b>Declared foreign keys</b> → {@link DeclaredForeignKeyRepository}, a new small store
 *       (Flyway V33) keyed by Aran dataset id rather than table-name string, resolved here where
 *       the concrete {@link RelationalDatabaseRepository} being harvested — and therefore which
 *       datasets its table names refer to — is known.</li>
 * </ul>
 *
 * <h2>A total connection failure must not erase yesterday's good harvest</h2>
 *
 * <p>{@link RelationalCatalogHarvestService#harvest} degrades to {@link Harvest#EMPTY} for a
 * failure it cannot attribute to any one piece — an unreachable host, a rejected password, an
 * unknown engine. Persisting that as this repository's new truth would let one transient network
 * blip during Task 5's refresh cadence wipe every DECLARED edge and HARVESTED profile a previous,
 * successful harvest produced — reverting the planner from "real stats, possibly join-eliminated"
 * to "today's ~100-row guess, never eliminated" for no reason connected to the source database
 * actually changing. {@link #persist} therefore does nothing at all when {@code harvest.equals(
 * Harvest.EMPTY)}: a connection that never succeeded has learned nothing, so it reports nothing,
 * rather than reporting "everything is now unknown."
 *
 * <h2>Stats: additive per table, never a proactive clear</h2>
 *
 * <p>Once the connection <em>did</em> succeed, a single table's absence from this harvest's row
 * counts/NDV (e.g. {@link JdbcCatalogHarvester}'s own per-piece {@code pg_stats} read failing
 * while the connection and other tables succeed) is indistinguishable, in {@link Harvest}'s shape,
 * from "this table was never analyzed." Clearing that dataset's HARVESTED profiles on every such
 * miss would make a query plan's join order flap between real-stats-driven and guess-driven runs
 * on nothing more than incidental per-run flakiness. {@link #persistStats} therefore only ever
 * ADDS or REPLACES a dataset's HARVESTED profiles when this harvest produced at least one for it
 * — never proactively removing them when it produced none. A table genuinely dropped from the
 * source database keeps its last-known stats until the next real profiling pass touches it; that
 * staleness is schema drift, a concern this class defers to {@code FederatedSchemaProbe}, not one
 * a stats harvest should silently paper over by reverting to no evidence at all.
 *
 * <h2>Declared foreign keys: full replace, but only when the FK harvest actually succeeded</h2>
 *
 * <p>{@link DeclaredForeignKeyRepository#replaceForRepository} is called with whatever this
 * harvest resolved, including an empty list, whenever {@link Harvest#fkHarvestSucceeded()} is
 * {@code true} — deliberately more aggressive than the stats policy above, because {@code
 * Harvest.declaredForeignKeys()} being empty is a legitimate value once the FK harvest actually
 * ran — a schema with zero foreign keys is exactly as protocol-representable as one with several —
 * where a completely absent {@code rowCountByTable}/{@code ndvByColumn} entry has a real
 * distinguishable "never analyzed" meaning to fall back on instead.
 *
 * <p><b>The fix this class carries (Phase 4B Task 5, ledgered from Task 3's review):</b> {@code
 * JdbcCatalogHarvester}'s FK read can fail independently, behind its own try/catch, exactly like
 * the stats reads do — so a connection that succeeds but whose FK query alone fails (a narrower
 * permission grant than the stats views, say, or a transient lock) used to look identical to "this
 * database declares no foreign keys," and a later harvest in that state would wipe
 * previously-known-good DECLARED edges. On a recurring refresh cadence (Task 5) a single bad tick
 * would then revert a repository to guess-driven planning indefinitely, behind only a WARN.
 * {@link Harvest#fkHarvestSucceeded()} is the fix: when it is {@code false}, {@link
 * #persistDeclaredForeignKeys} does not call {@code replaceForRepository} at all — it WARNs and
 * leaves whatever was persisted before exactly where it is, the same "preserve, don't wipe,
 * on a piece that didn't complete" policy the stats side already had. Stats stay additive as
 * before; only the declared-FK replace gained this guard.
 *
 * <h2>The two writes fail independently (V44)</h2>
 *
 * <p>{@link #persistStats} runs before {@link #persistDeclaredForeignKeys} and used to be able to
 * abort it: anything the stats save threw propagated out of {@link #persist} before the declared-FK
 * write had run at all. Live on 2026-08-27 that is exactly what happened — a duplicate-key
 * violation on the (then too narrow) {@code column_profile} key, on every repository with more than
 * one enrolled table — and {@code declared_foreign_key} stayed permanently empty as a result,
 * making the whole DECLARED tier unreachable on the natural path. Each dataset's stats write is now
 * contained to that dataset, so the weaker of the two feeds (an {@code ANALYZE} estimate) can never
 * cost the stronger one (a constraint the source database itself enforces) its write. Splitting,
 * rather than merely reversing the order, is the fix: reversing would only exchange which half
 * becomes the victim.
 *
 * <p>Honest limit: that containment is real on the job path (import, refresh, the refresh cadence),
 * where each {@code datasets.save} owns its own transaction, and largely nominal inside a request
 * that is already one — {@code DatasetService} is {@code @Transactional} at class level, and a
 * database error there dooms that transaction whoever catches it. What the catch buys on the
 * request path is a WARN naming which half was lost, not a rescued write.
 */
@NullMarked
@Component
public final class HarvestPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(HarvestPersistenceService.class);

    private final DatasetRepository datasets;
    private final DeclaredForeignKeyRepository declaredForeignKeys;
    /**
     * The query-engine's mapping-catalog cache, so a harvest that changes DECLARED edges or
     * HARVESTED stats without promoting the graph reaches the planner. {@code Optional.empty()} in
     * any wiring that predates Phase 4B review Important 3, or a test slice with no query engine
     * (the 2-arg constructor) — the harvest still persists; only the cache eviction is skipped, the
     * pre-existing "stale until next promotion" behaviour.
     */
    private final Optional<CatalogCacheInvalidator> catalogCache;

    /**
     * Pre-Important-3 shape, kept for this module's direct-construction tests (Spring never sees
     * this one — see the {@code @Autowired} constructor below). Wires no cache invalidator, so a
     * harvest persisted through it does not evict any cached catalog.
     */
    public HarvestPersistenceService(DatasetRepository datasets,
                                      DeclaredForeignKeyRepository declaredForeignKeys) {
        this(datasets, declaredForeignKeys, Optional.empty());
    }

    /**
     * {@code @Autowired} is required once a second constructor exists: with none marked, Spring
     * cannot choose between the two. Marking the full-arity one keeps the container wiring the
     * cache invalidator ({@code MappingCatalogOperator}) as a normal {@code Optional}-optional
     * dependency, while the 2-arg constructor above stays plain Java for tests that predate this
     * field. Mirrors {@code MappingCatalogOperator}'s own two-constructor shape.
     */
    @Autowired
    public HarvestPersistenceService(DatasetRepository datasets,
                                      DeclaredForeignKeyRepository declaredForeignKeys,
                                      Optional<CatalogCacheInvalidator> catalogCache) {
        this.datasets = datasets;
        this.declaredForeignKeys = declaredForeignKeys;
        this.catalogCache = catalogCache;
    }

    /**
     * See the class javadoc for the full persistence contract.
     *
     * <p>Not "never throws" in isolation: {@code declaredForeignKeys.replaceForRepository} is a
     * repository port that may throw, and it is the last thing this method does. The harvest flow's
     * never-throws guarantee is upheld one level up, by {@code DatasetService.harvestCatalog}'s
     * try/catch (a harvest must never fail a registration or refresh); see {@code
     * RelationalCatalogHarvestTrigger}'s javadoc. What this method does guarantee is the "leave prior
     * state untouched on a piece that did not complete" policy the class javadoc describes, and —
     * since V44 — that a failing stats write never reaches the declared-FK write.
     */
    public void persist(TenantContext tenant, RelationalDatabaseRepository repo, Harvest harvest) {
        if (harvest.equals(Harvest.EMPTY)) {
            log.debug("Skipping harvest persistence for repository '{}': nothing was harvested "
                    + "(connection-level failure or no capability) — leaving prior state untouched",
                    repo.id());
            return;
        }
        List<Dataset> repoDatasets = datasets.findAllByRepository(tenant, repo.id());
        boolean statsChanged = persistStats(tenant, repoDatasets, harvest);
        boolean fksChanged = persistDeclaredForeignKeys(tenant, repo, repoDatasets, harvest);
        if (statsChanged || fksChanged) {
            // A harvest changes what a catalog build derives (HARVESTED stats, DECLARED edges)
            // without bumping the graph version the catalog cache keys on — including a harvest that
            // REMOVES a foreign key — so the cache must be evicted explicitly or the change (a
            // dropped FK especially) never reaches the planner until the next promotion. Best-effort
            // and idempotent; absent in a wiring with no query engine. See CatalogCacheInvalidator.
            catalogCache.ifPresent(cache -> cache.invalidate(tenant));
        }
    }

    /**
     * @return whether any dataset's HARVESTED stats were written (a catalog-relevant change).
     *
     * <p>Never throws: one dataset's failed stats write is contained to that dataset (V44). It used
     * to propagate, and the damage was two levels wide — it stopped the remaining datasets of the
     * same repository from being written, and it stopped {@link #persistDeclaredForeignKeys} from
     * running at all, so the DECLARED tier was collateral damage from the weaker of the two feeds.
     * Stats are an ANALYZE estimate; a declared foreign key is a constraint the source database
     * enforces. The stronger evidence must not be lost because the weaker one collided.
     */
    private boolean persistStats(TenantContext tenant, List<Dataset> repoDatasets, Harvest harvest) {
        boolean wrote = false;
        for (Dataset dataset : repoDatasets) {
            List<ColumnProfile> freshHarvested =
                    HarvestedColumnProfiles.forTable(harvest, dataset.id(), dataset.name());
            if (freshHarvested.isEmpty()) {
                continue; // additive-only — see class javadoc
            }
            List<ColumnProfile> merged = new ArrayList<>();
            for (ColumnProfile existing : dataset.columns()) {
                if (!HarvestedColumnProfiles.SUBJECT.equals(existing.subject())) {
                    merged.add(existing); // keep every SNAPSHOT/WRAPPER_OUTPUT row as-is
                }
            }
            merged.addAll(freshHarvested);
            try {
                datasets.save(tenant, new Dataset(
                        dataset.id(), dataset.repositoryId(), dataset.name(), dataset.description(),
                        dataset.snapshotId(), merged, dataset.graphJson(),
                        dataset.bootstrapStatus()));
                wrote = true;
            } catch (RuntimeException e) {
                // Names the half AND the dataset. V44's own log line was DatasetService's
                // "Catalog harvest for repository ... failed", which says neither, and reading it
                // one level up is what left "which write was lost" to be worked out from the SQL.
                log.warn("Could not persist HARVESTED stats for dataset {} ('{}') of repository's "
                                + "harvest — leaving its previous profiles in place, and continuing "
                                + "with the remaining datasets and the declared-FK write: {}",
                        dataset.id(), dataset.name(), e.toString());
            }
        }
        return wrote;
    }

    /** @return whether the declared-FK set was replaced (a catalog-relevant change, incl. a removal). */
    private boolean persistDeclaredForeignKeys(TenantContext tenant, RelationalDatabaseRepository repo,
                                             List<Dataset> repoDatasets, Harvest harvest) {
        if (!harvest.fkHarvestSucceeded()) {
            // The FK piece failed independently (JdbcCatalogHarvester's own try/catch) while the
            // connection — and possibly the stats pieces — succeeded. An empty declaredForeignKeys()
            // here is NOT "this schema has zero FKs", it is "we don't know"; replacing would wipe
            // every previously-good DECLARED edge on nothing more than a permission hiccup or a
            // transient lock, and the refresh cadence would repeat that wipe every tick. See the
            // class javadoc's "fix this class carries" section.
            log.warn("Skipping declared-FK replace for repository '{}': the FK harvest piece failed "
                    + "(connection/stats succeeded) — preserving whatever was persisted before",
                    repo.id());
            return false;
        }
        Map<String, UUID> datasetIdByTable = new HashMap<>();
        for (Dataset dataset : repoDatasets) {
            // first-wins: two datasets sharing one table name under the same repository would be
            // an enrolment bug this method has no business trying to adjudicate.
            datasetIdByTable.putIfAbsent(dataset.name(), dataset.id());
        }

        UUID tenantId = UUID.fromString(tenant.value());
        List<DeclaredForeignKey> resolved = new ArrayList<>();
        for (Harvest.ForeignKey fk : harvest.declaredForeignKeys()) {
            UUID fromId = datasetIdByTable.get(fk.fromTable());
            UUID toId = datasetIdByTable.get(fk.toTable());
            if (fromId == null) {
                log.warn("Dropping a harvested foreign key for repository '{}': no Aran dataset "
                        + "matches table '{}' (the FK's 'from' side)", repo.id(), fk.fromTable());
                continue;
            }
            if (toId == null) {
                log.warn("Dropping a harvested foreign key for repository '{}': no Aran dataset "
                        + "matches table '{}' (the FK's 'to' side)", repo.id(), fk.toTable());
                continue;
            }
            resolved.add(new DeclaredForeignKey(UUID.randomUUID(), tenantId, repo.id(),
                    fromId, fk.fromCols(), toId, fk.toCols()));
        }
        // Always a full replace when the FK harvest succeeded — including with an empty list, which
        // is how a dropped foreign key is removed. That removal is exactly why the cache must be
        // invalidated afterwards (see persist), so this returns true even for an empty resolved set.
        declaredForeignKeys.replaceForRepository(tenant, repo.id(), resolved);
        return true;
    }
}

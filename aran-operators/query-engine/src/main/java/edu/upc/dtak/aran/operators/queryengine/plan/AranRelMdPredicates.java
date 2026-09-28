package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.BuiltInMetadata;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.MetadataHandler;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;

import java.util.List;

/**
 * Constraint-backed {@code Predicates} metadata handler for {@link TableScan}.
 *
 * <p>An <em>enforced</em> denial constraint is already a fact about the data the engine can read:
 * {@code DuckDbExecutor.buildDdl} splices it into every {@code u_} member's body and into
 * {@code int_}, so a violating row is not in the view. This handler tells the planner that fact,
 * which is what turns it from an enforcement mechanism into an optimisation. Consumers are
 * Calcite's own: {@code FILTER_REDUCE_EXPRESSIONS} (see {@link AranRules}) drops a query
 * predicate implied by a constraint and collapses one that contradicts it to empty
 * {@code VALUES}, and {@code RexSimplify} sharpens everything in between.
 *
 * <p><b>Safety property — read before touching the fallback branch.</b> Calcite's own default
 * ({@code RelMdPredicates}) registers its own {@code getPredicates(TableScan, ...)} method. Once
 * this class is chained ahead of it in {@code OqlPlanner.ARAN_METADATA_PROVIDER} for the
 * identical {@code TableScan} parameter type, <em>this</em> method is dispatched exclusively for
 * every table scan — Calcite's own {@code TableScan} method, and by extension its generic
 * {@code RelNode} catch-all, are never reached again for that node. (Verified for calcite-core
 * 1.41.0 in Task 4 by decompiling {@code ReflectiveRelMetadataProvider}/{@code DispatchGenerator}:
 * the generated dispatcher picks exactly one handler per concrete rel class — whichever provider
 * registered it first in chain order — so a competing registration is dead code regardless of
 * what this method returns, {@code null} included. There is no "return null to fall through to
 * the same class in the next provider".) Calcite's own {@code TableScan} method reads, in full:
 *
 * <pre>{@code
 * final BuiltInMetadata.Predicates.Handler handler =
 *     scan.getTable().unwrap(BuiltInMetadata.Predicates.Handler.class);
 * if (handler != null) {
 *   return handler.getPredicates(scan, mq);
 * }
 * return RelOptPredicateList.EMPTY;
 * }</pre>
 *
 * <p>The "no constraints" branch below replicates exactly that, rather than returning
 * {@code RelOptPredicateList.EMPTY} directly: a table that supplies its own {@code Predicates}
 * handler is a documented Calcite extension point, and swallowing it here would silently disable
 * a mechanism a future adapter table might rely on. {@link AranRelMdDistinctRowCount} carries the
 * same reasoning for its own default-replicating fallback.
 *
 * <p>Only {@code TableScan} is registered here; every other rel type (including {@code Filter},
 * {@code Join}, {@code Union} and {@code RelSubset}, which is how a scan is reached mid-planning)
 * keeps Calcite's own inference untouched.
 */
public final class AranRelMdPredicates implements MetadataHandler<BuiltInMetadata.Predicates> {

    /** Reflective provider bound to the {@code getPredicates(TableScan, mq)} method below. */
    public static final RelMetadataProvider SOURCE =
            ReflectiveRelMetadataProvider.reflectiveSource(
                    new AranRelMdPredicates(),
                    BuiltInMetadata.Predicates.Handler.class);

    @Override
    public MetadataDef<BuiltInMetadata.Predicates> getDef() {
        return BuiltInMetadata.Predicates.DEF;
    }

    /**
     * Predicates every row of a {@link TableScan} satisfies.
     *
     * <p>Ordinals need no remapping: {@link OqlPlanner} parses each constraint over the same
     * {@link AranSchema} the query plans over, and a class's {@code int_} table and every one of
     * its {@code u_} members expose the identical global-column vector, so {@code $n} already
     * means the same column here as it did in the probe.
     */
    public RelOptPredicateList getPredicates(TableScan scan, RelMetadataQuery mq) {
        PredicateBackedTable table = scan.getTable().unwrap(PredicateBackedTable.class);
        if (table != null) {
            List<RexNode> predicates = table.pulledUpPredicates();
            if (!predicates.isEmpty()) {
                return RelOptPredicateList.of(scan.getCluster().getRexBuilder(), predicates);
            }
        }
        // No constraints — byte-identical to Calcite's default TableScan handler (see the class
        // javadoc for why this must be computed here rather than deferred to the chain).
        BuiltInMetadata.Predicates.Handler handler =
                scan.getTable().unwrap(BuiltInMetadata.Predicates.Handler.class);
        if (handler != null) {
            return handler.getPredicates(scan, mq);
        }
        return RelOptPredicateList.EMPTY;
    }
}

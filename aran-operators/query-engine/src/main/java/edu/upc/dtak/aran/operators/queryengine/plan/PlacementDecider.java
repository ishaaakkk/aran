package edu.upc.dtak.aran.operators.queryengine.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The physical-placement decision — snapshot leaf or live leaf, per dual-access dataset, chosen
 * by transfer-aware cost computed over the <b>optimized</b> plan (Option A).
 *
 * <h2>Why this runs after Volcano rather than as RelSet alternatives</h2>
 *
 * <p>Within DuckDB reach (T1/T2) the choice is shape-inert: both leaves produce identical rows,
 * so cardinality-driven join ordering cannot move, and the emitted SQL text is the same either
 * way — the choice only changes which read expression the resolver binds under {@code src_}.
 * Registering per-leaf alternatives in the memo would therefore double the leaf search space
 * (P1's bottleneck) to let Volcano pick between plans it cannot tell apart. Deciding from the
 * optimized tree loses nothing and gains the one interaction that matters (A3): the predicates
 * the pass pushed onto each leaf determine that leaf's <em>effective</em> transfer rows, read via
 * {@code RelMetadataQuery} — the same evidence chain that costed the plan. In-memo alternatives
 * become necessary — and get built — when engine conventions make placement shape-relevant
 * (spec phase 6).
 *
 * <h2>The cost, stated exactly</h2>
 *
 * <pre>cost(live)     = λ_live  × Σ effRows(leaf)   effRows = mq.getRowCount(top of the leaf's
 * cost(snapshot) = λ_local × Σ fullRows(leaf)             pushed Filter/Project chain)</pre>
 *
 * summed over every occurrence of the dataset's leaves in the plan (a dataset scanned twice
 * transfers twice). Per-column byte widths cancel in v1 — both sides move the same columns —
 * so the λs are per-<b>row</b> weights; widths enter when projection pruning is priced
 * (follow-up, noted in the design doc). Freshness never appears in either formula: legality is
 * decided <em>before</em> this class runs ({@code Candidate}), per the settled rule.
 */
@NullMarked
public final class PlacementDecider {

    private PlacementDecider() {
    }

    /**
     * One dual-access dataset the operator established both paths for, with freshness legality
     * already decided — this class never re-derives it (freshness gates legality, cost picks).
     *
     * @param snapshotLegal whether the lake copy may serve this query (its declared
     *                      {@code RefreshPolicy} bound holds)
     * @param legality      the human-readable reason for the legality verdict, carried into the
     *                      decision's report so a forced choice is explainable
     */
    public record Candidate(String datasetId, boolean snapshotLegal, String legality) {}

    /**
     * One dataset's outcome: {@code chosen} is an {@code AccessMode} name
     * ({@code MATERIALIZED}|{@code FEDERATED}), costs are in λ-weighted row units.
     */
    public record Decision(String datasetId, String chosen, double liveCost, double snapshotCost,
                           double effectiveRows, double fullRows, String reason) {}

    /**
     * Decide placement for every candidate dataset that actually appears in {@code optimized}.
     * Datasets with no leaf in this plan get no decision (nothing will be resolved for them);
     * non-candidate datasets are untouched (the resolver keeps its {@code SourceAccess} default).
     */
    public static Map<String, Decision> decide(RelNode optimized,
                                               Map<String, Candidate> candidates,
                                               double liveLambda, double localLambda) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, double[]> rowsByDataset = new LinkedHashMap<>(); // [effRows, fullRows]
        collect(optimized, null, optimized.getCluster().getMetadataQuery(),
                candidates, rowsByDataset);

        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : rowsByDataset.entrySet()) {
            Candidate c = candidates.get(e.getKey());
            double effRows = e.getValue()[0];
            double fullRows = e.getValue()[1];
            double liveCost = liveLambda * effRows;
            double snapshotCost = localLambda * fullRows;
            String chosen;
            String reason;
            if (!c.snapshotLegal()) {
                chosen = "FEDERATED";
                reason = "snapshot leaf illegal: " + c.legality();
            } else if (liveCost < snapshotCost) {
                chosen = "FEDERATED";
                reason = "live wins on cost: " + fmt(liveCost) + " < " + fmt(snapshotCost)
                        + " (pushdown keeps " + fmt(effRows) + " of " + fmt(fullRows) + " rows)";
            } else {
                chosen = "MATERIALIZED";
                reason = "snapshot wins on cost: " + fmt(snapshotCost) + " <= " + fmt(liveCost);
            }
            decisions.put(e.getKey(), new Decision(
                    e.getKey(), chosen, liveCost, snapshotCost, effRows, fullRows, reason));
        }
        return decisions;
    }

    /**
     * Walk the tree accumulating per-dataset effective and full rows. {@code chainTop} is the
     * highest node of the pure Filter/Project chain wrapping the scan currently being descended
     * into — the node whose {@code mq.getRowCount} is "what this leaf emits after its pushed
     * predicates". A Join/Union/Aggregate/anything-else resets the chain: rows above it are the
     * plan's business, not the leaf's transfer.
     */
    private static void collect(RelNode node, @Nullable RelNode chainTop, RelMetadataQuery mq,
                                Map<String, Candidate> candidates,
                                Map<String, double[]> rowsByDataset) {
        if (node instanceof TableScan scan) {
            String datasetId = datasetIdOf(scan);
            if (datasetId == null || !candidates.containsKey(datasetId)) {
                return;
            }
            RelNode effNode = chainTop != null ? chainTop : scan;
            Double eff = mq.getRowCount(effNode);
            Double full = mq.getRowCount(scan);
            double[] acc = rowsByDataset.computeIfAbsent(datasetId, k -> new double[2]);
            acc[0] += eff == null ? (full == null ? 0 : full) : eff;
            acc[1] += full == null ? 0 : full;
            return;
        }
        boolean chains = (node instanceof Filter || node instanceof Project)
                && node.getInputs().size() == 1;
        RelNode nextTop = chains ? (chainTop != null ? chainTop : node) : null;
        for (RelNode input : node.getInputs()) {
            collect(input, nextTop, mq, candidates, rowsByDataset);
        }
    }

    /**
     * The distinct dataset ids behind this plan's leaves — what the operator probes for
     * dual-access candidacy, so the placement pass costs work proportional to the QUERY, not the
     * tenant (adversarial-review finding: the first cut probed every dataset in the tenant's
     * graph per statement).
     */
    public static java.util.Set<String> leafDatasets(RelNode optimized) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        collectDatasets(optimized, out);
        return out;
    }

    private static void collectDatasets(RelNode node, java.util.Set<String> out) {
        if (node instanceof TableScan scan) {
            String datasetId = datasetIdOf(scan);
            if (datasetId != null) {
                out.add(datasetId);
            }
            return;
        }
        for (RelNode input : node.getInputs()) {
            collectDatasets(input, out);
        }
    }

    /** The scan's dataset, via the unwrap pattern; null for scans that carry no catalog leaf. */
    private static @Nullable String datasetIdOf(TableScan scan) {
        WrapperTable wrapper = scan.getTable().unwrap(WrapperTable.class);
        if (wrapper != null) {
            return wrapper.source().datasetId();
        }
        SrcTable src = scan.getTable().unwrap(SrcTable.class);
        if (src != null) {
            return src.source().datasetId();
        }
        return null;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15
                ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** For the operator's payload: decisions as a JSON-friendly list. */
    public static List<Map<String, Object>> report(Map<String, Decision> decisions) {
        return decisions.values().stream()
                .<Map<String, Object>>map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("datasetId", d.datasetId());
                    m.put("chosen", d.chosen());
                    m.put("liveCost", d.liveCost());
                    m.put("snapshotCost", d.snapshotCost());
                    m.put("effectiveRows", d.effectiveRows());
                    m.put("fullRows", d.fullRows());
                    m.put("reason", d.reason());
                    return m;
                })
                .toList();
    }
}

package edu.upc.dtak.aran.operators.queryengine.plan;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the optimizer did for one query, and — the part a user must be able to see — whether the
 * plan it answered with is the one it would have chosen given unlimited time.
 *
 * <p>The degradation ladder (D3): {@link Degradation#NONE} is the fully costed plan;
 * {@link Degradation#BEST_SO_FAR} is Volcano's cheapest implemented plan at the moment the budget
 * cancelled it — a correct plan, possibly a worse one; {@link Degradation#STRUCTURAL_FALLBACK}
 * means no implemented plan existed when the budget tripped, so the query was planned by the
 * structural Hep program ({@link AranRules#STRUCTURAL}: predicate placement and projection
 * cleanup, no join reordering) — still correct, never costed. Every consumer that shows a plan
 * shows this too, so a slow query is never mistaken for a well-planned one.
 *
 * @param productions       successful rule productions counted before planning ended
 * @param productionsByRule the same, per rule — D3's step 0, the evidence a later phase split is
 *                          decided on
 * @param budget            the production budget that applied ({@code <= 0}: none)
 * @param tripped           which guard ended planning early, or {@code null} when none did
 */
public record Planning(int productions,
                       Map<String, Integer> productionsByRule,
                       int budget,
                       long elapsedMillis,
                       Degradation degradation,
                       @Nullable Tripped tripped) {

    public enum Degradation { NONE, BEST_SO_FAR, STRUCTURAL_FALLBACK }

    public enum Tripped { PRODUCTION_BUDGET, WALL_CLOCK }

    public Planning {
        productionsByRule = Map.copyOf(productionsByRule);
    }

    public boolean degraded() {
        return degradation != Degradation.NONE;
    }

    /** The payload shape both {@code rewrite-oql} and {@code run-oql} carry under {@code planning}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productions", productions);
        m.put("budget", budget);
        m.put("elapsedMillis", elapsedMillis);
        m.put("degradation", degradation.name());
        m.put("tripped", tripped == null ? null : tripped.name());
        m.put("productionsByRule", productionsByRule);
        return m;
    }
}

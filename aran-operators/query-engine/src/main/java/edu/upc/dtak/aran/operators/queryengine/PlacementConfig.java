package edu.upc.dtak.aran.operators.queryengine;

import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The λ constants for the physical-placement decision — configured first,
 * harness-calibrated later (A4): these defaults are declared guesses, and the design's
 * calibration procedure ({@code make perf} bytes/latency per leaf choice) is what replaces them
 * with measured values. Per-repository overrides arrive with that calibration, not before.
 */
@NullMarked
@Component
public class PlacementConfig {

    private final boolean enabled;
    private final double liveLambda;
    private final double localLambda;
    private final double staleGraceFactor;

    public PlacementConfig(
            @Value("${aran.query.placement.enabled:true}") boolean enabled,
            // Per-ROW weights (byte widths cancel while both leaves move the same columns —
            // PlacementDecider's javadoc). live/local = 25 means a live read must keep under
            // 1/25th of the rows to beat a local snapshot scan.
            @Value("${aran.query.placement.live-lambda:25.0}") double liveLambda,
            @Value("${aran.query.placement.local-lambda:1.0}") double localLambda,
            // A copy under Every(interval) stays legal until staleness exceeds
            // graceFactor × interval — one skipped scheduler tick tolerated ("the interval is a
            // floor, not a guarantee"), a silently broken refresh is not.
            @Value("${aran.query.placement.stale-grace-factor:2.0}") double staleGraceFactor) {
        this.enabled = enabled;
        this.liveLambda = liveLambda;
        this.localLambda = localLambda;
        this.staleGraceFactor = staleGraceFactor;
    }

    /** The shipped defaults — what the convenience constructors and tests wire. */
    public static PlacementConfig defaults() {
        return new PlacementConfig(true, 25.0, 1.0, 2.0);
    }

    public boolean enabled() {
        return enabled;
    }

    public double liveLambda() {
        return liveLambda;
    }

    public double localLambda() {
        return localLambda;
    }

    public double staleGraceFactor() {
        return staleGraceFactor;
    }
}

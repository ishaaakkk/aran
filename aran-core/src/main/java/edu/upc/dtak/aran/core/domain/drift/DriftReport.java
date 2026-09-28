package edu.upc.dtak.aran.core.domain.drift;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The verdict on one dataset: what changed, what it breaks, and whether it is safe to apply.
 *
 * @param baselineHash the fingerprint hash the comparison ran against, or null when the dataset
 *                     has never been fingerprinted — the first check has nothing to compare to and
 *                     is a baseline capture, not a drift finding
 */
@NullMarked
public record DriftReport(
        UUID datasetId,
        @Nullable String baselineHash,
        String candidateHash,
        List<DriftImpact> impacts) {

    public DriftReport {
        impacts = List.copyOf(impacts);
    }

    /** A dataset seen for the first time: nothing to compare, so nothing can have drifted. */
    public static DriftReport baseline(UUID datasetId, String candidateHash) {
        return new DriftReport(datasetId, null, candidateHash, List.of());
    }

    /** True when the candidate is byte-identical in shape to the baseline. */
    public boolean unchanged() {
        return candidateHash.equals(baselineHash);
    }

    /**
     * True when this is the dataset's first fingerprint.
     *
     * <p>{@code @JsonIgnore} because this is the one derived accessor on this record that matches
     * Jackson's bean convention ({@code isX()} → property {@code baseline}). Serialized, it would
     * emit a field the canonical constructor cannot bind, and the report round-trips through JSON
     * twice on the way to the browser — operator to controller, controller to client. The
     * information is not lost: a baseline report is exactly one whose {@code baselineHash} is null.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBaseline() {
        return baselineHash == null;
    }

    /** The worst severity present, or {@link DriftSeverity#INFO} when there is nothing to report. */
    public DriftSeverity verdict() {
        return impacts.stream().map(DriftImpact::severity).reduce(DriftSeverity.INFO, DriftSeverity::max);
    }

    /**
     * Whether applying this candidate would break something already compiled into the active
     * schema — the gate {@code BootstrappingOperator} gets to consult before it clears a graph.
     */
    public boolean breaking() {
        return verdict() == DriftSeverity.BREAKING;
    }

    public List<SchemaChange> changes() {
        return impacts.stream().map(DriftImpact::change).toList();
    }

    /** The impacts a one-click repair could resolve: renames with mappings pointing at the old IRI. */
    public List<DriftImpact> repairable() {
        return impacts.stream().filter(DriftImpact::repairable).toList();
    }

    public long countOf(DriftSeverity severity) {
        return impacts.stream().filter(i -> i.severity() == severity).count();
    }
}

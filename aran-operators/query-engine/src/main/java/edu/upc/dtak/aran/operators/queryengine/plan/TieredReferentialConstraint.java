package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.RelReferentialConstraintImpl;
import org.apache.calcite.util.mapping.IntPair;

import java.util.List;

/**
 * A Calcite {@link RelReferentialConstraint} that also carries a {@link Tier}.
 *
 * <p><b>Why this exists.</b> Phase 4B's binding rule is that an accepted join (a
 * {@code HYPOTHESIS} edge) may only sharpen estimates — it must be structurally impossible for it
 * to drive join elimination. Calcite's own {@link RelReferentialConstraint} has no notion of
 * trust: a constraint is a constraint, and any elimination rule would treat a discovered join as
 * ground truth and drop rows. The tier has to travel <em>with</em> the constraint so the rule that
 * would eliminate a join (Task 4) can read it back and refuse anything below {@code DECLARED}.
 *
 * <p><b>Why a subtype, not a side-map.</b> The elimination rule already holds the constraint
 * object it is considering; recovering the tier is then a field read, with no lookup key to
 * reconstruct from qualified names (lossy) and no ambiguous "entry missing" case to default. It
 * composes rather than extends {@link RelReferentialConstraintImpl} only because that class's
 * constructor is private.
 *
 * <p><b>Un-bypassable.</b> The single recovery point is {@link #tierOf}, and it maps any
 * constraint that is <em>not</em> a {@code TieredReferentialConstraint} to {@link Tier#HYPOTHESIS}
 * — the least-trusted tier. So an untagged constraint, or one of unknown provenance, is treated
 * exactly like a hypothesis and can never be mistaken for a {@code DECLARED} one. The only way to
 * obtain a higher tier is to have constructed the object with it.
 */
final class TieredReferentialConstraint implements RelReferentialConstraint {

    private final RelReferentialConstraint delegate;
    private final Tier tier;

    private TieredReferentialConstraint(RelReferentialConstraint delegate, Tier tier) {
        this.delegate = delegate;
        this.tier = tier;
    }

    static TieredReferentialConstraint of(Tier tier, List<String> sourceQualifiedName,
                                          List<String> targetQualifiedName, List<IntPair> columnPairs) {
        return new TieredReferentialConstraint(
                RelReferentialConstraintImpl.of(sourceQualifiedName, targetQualifiedName, columnPairs),
                tier);
    }

    /**
     * The trust tier of {@code ri}. A {@link TieredReferentialConstraint} returns the tier it was
     * built with; every other {@link RelReferentialConstraint} returns {@link Tier#HYPOTHESIS} —
     * the safe default that keeps an untagged constraint out of any trust-gated elimination.
     */
    static Tier tierOf(RelReferentialConstraint ri) {
        return ri instanceof TieredReferentialConstraint t ? t.tier : Tier.HYPOTHESIS;
    }

    Tier tier() {
        return tier;
    }

    @Override
    public List<String> getSourceQualifiedName() {
        return delegate.getSourceQualifiedName();
    }

    @Override
    public List<String> getTargetQualifiedName() {
        return delegate.getTargetQualifiedName();
    }

    @Override
    public List<IntPair> getColumnPairs() {
        return delegate.getColumnPairs();
    }

    @Override
    public String toString() {
        return tier + " " + delegate;
    }
}

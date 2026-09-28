package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.RelReferentialConstraintImpl;
import org.apache.calcite.util.mapping.IntPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4B, Task 1 (the crux): the tier-carrying mechanism Task 4's join-elimination rule reads
 * back. A {@link TieredReferentialConstraint} is a Calcite {@link RelReferentialConstraint} that
 * also carries a {@link Tier}; {@link TieredReferentialConstraint#tierOf} is the single recovery
 * point.
 *
 * <p><b>Un-bypassable (Task 1 deliverable 3):</b> the only way to obtain a non-{@code HYPOTHESIS}
 * tier is to have constructed the object with it. An untagged {@link RelReferentialConstraint}
 * recovers as {@code HYPOTHESIS} — the weakest, least-trusted tier — so Task 4 (which may eliminate
 * a join only for {@code DECLARED}/{@code VERIFIED}) can never be tricked into eliminating on an
 * edge of unknown provenance or a hypothesis.
 */
class TieredReferentialConstraintTest {

    private static final List<String> ORDER = List.of("int_Order");
    private static final List<String> CUSTOMER = List.of("int_Customer");
    private static final List<IntPair> PAIRS = List.of(IntPair.of(1, 0));

    @Test
    @DisplayName("tierOf recovers the tier a TieredReferentialConstraint was built with")
    void tierOfRecoversTheBuiltTier() {
        RelReferentialConstraint ri =
                TieredReferentialConstraint.of(Tier.HYPOTHESIS, ORDER, CUSTOMER, PAIRS);
        assertThat(TieredReferentialConstraint.tierOf(ri)).isEqualTo(Tier.HYPOTHESIS);
    }

    @Test
    @DisplayName("a HYPOTHESIS constraint can never be read back as DECLARED or VERIFIED")
    void hypothesisNeverReadsAsDeclared() {
        RelReferentialConstraint ri =
                TieredReferentialConstraint.of(Tier.HYPOTHESIS, ORDER, CUSTOMER, PAIRS);
        assertThat(TieredReferentialConstraint.tierOf(ri))
                .isNotEqualTo(Tier.DECLARED)
                .isNotEqualTo(Tier.VERIFIED);
    }

    @Test
    @DisplayName("an untagged (plain Calcite) constraint recovers as HYPOTHESIS — safe default-deny")
    void untaggedConstraintDefaultsToHypothesis() {
        RelReferentialConstraint plain = RelReferentialConstraintImpl.of(ORDER, CUSTOMER, PAIRS);
        assertThat(TieredReferentialConstraint.tierOf(plain))
                .as("unknown provenance must be treated as the least-trusted tier")
                .isEqualTo(Tier.HYPOTHESIS);
    }

    @Test
    @DisplayName("the tier tag does not disturb the Calcite RI payload it wraps")
    void carriesSourceTargetAndColumnPairs() {
        RelReferentialConstraint ri =
                TieredReferentialConstraint.of(Tier.DECLARED, ORDER, CUSTOMER, PAIRS);
        assertThat(ri.getSourceQualifiedName()).isEqualTo(ORDER);
        assertThat(ri.getTargetQualifiedName()).isEqualTo(CUSTOMER);
        assertThat(ri.getColumnPairs()).containsExactly(IntPair.of(1, 0));
    }

    @Test
    @DisplayName("tierOf can recover a DECLARED tier (proves the mechanism is not hard-wired to one tier)")
    void tierOfRecoversDeclared() {
        RelReferentialConstraint ri =
                TieredReferentialConstraint.of(Tier.DECLARED, ORDER, CUSTOMER, PAIRS);
        assertThat(TieredReferentialConstraint.tierOf(ri)).isEqualTo(Tier.DECLARED);
    }
}

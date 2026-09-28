package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3B's constraint origin discriminator: every {@link ModelConstraint} now says how it came
 * to exist — {@code MANUAL} (a human typed it) or {@code DISCOVERED} (persisted as an unenforced
 * candidate by {@code IntegrationModelService.discoverConstraints}). It carries no behavioural
 * difference on its own — see the record's own javadoc — but absence of the discriminator must
 * resolve to {@code MANUAL}, never to an unmarked third state, same discipline as
 * {@code ColumnProfile.subject}.
 */
class ModelConstraintTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MODEL_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.now();

    @Test
    @DisplayName("the compact constructor normalizes a null origin to MANUAL")
    void compactConstructorNormalizesNullOrigin() {
        ModelConstraint constraint = new ModelConstraint(
                ID, TENANT_ID, MODEL_ID, "http://ex/Paper", "h_index >= 0", false, CREATED_AT, null);

        assertThat(constraint.origin()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("a DISCOVERED origin round-trips unchanged")
    void discoveredOriginRoundTrips() {
        ModelConstraint constraint = new ModelConstraint(
                ID, TENANT_ID, MODEL_ID, "http://ex/Paper",
                "NOT (colA = colA AND colB <> colB)", false, CREATED_AT, "DISCOVERED");

        assertThat(constraint.origin()).isEqualTo("DISCOVERED");
    }
}

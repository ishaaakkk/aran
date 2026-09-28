package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A denial/validation constraint owned by an {@link IntegrationModel}.
 *
 * <p>{@code expression} is a SQL boolean predicate over the class's global
 * property columns (the identifiers the {@code int_<Class>} views expose). When
 * {@code enforced} is true the query engine appends it as a {@code WHERE} filter
 * and routes the rejected rows into a quarantine view (wired in M6); until then a
 * constraint is a durable, unenforced record — e.g. a LIMA discovery result.
 *
 * <p>{@code origin} (Phase 3B) is {@code "MANUAL"} (a human typed {@code expression} in the
 * constraints editor) or {@code "DISCOVERED"} ({@code IntegrationModelService.discoverConstraints}
 * persisted it as an unenforced candidate after screening and de-duplicating a LIMA discovery
 * result). <b>It is load-bearing, not just a label</b>: {@code IntegrationModelService
 * .setConstraintEnforced} refuses to enforce a DISCOVERED row, because the miner states its
 * constraint over a PAIR of tuples and {@code expression} can only hold the single-row collapse of
 * that (V43) — a DISCOVERED row is display evidence for a reviewer, and only a MANUAL one
 * ever reaches the {@code int_}/{@code q_} views. The
 * compact constructor defaults a {@code null} origin to {@code "MANUAL"}: every pre-3B row (and
 * every {@code addConstraint} call, which never set this field) was hand-authored, so absence of
 * the discriminator must resolve to that, never to an unmarked third state — same discipline as
 * {@code ColumnProfile.subject} (also Phase 3B).
 */
@NullMarked
public record ModelConstraint(
        UUID id,
        UUID tenantId,
        UUID modelId,
        String classIri,
        String expression,
        boolean enforced,
        Instant createdAt,
        String origin
) {
    public ModelConstraint {
        if (origin == null) {
            origin = "MANUAL";
        }
    }
}

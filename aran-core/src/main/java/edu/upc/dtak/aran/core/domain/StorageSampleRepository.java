package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence port for {@link StorageSample}.
 *
 * <p>Note what is absent: there is no "sum samples" method, and there should never be one. The
 * only meaningful reads of a level are the most recent value and the value as of some earlier
 * moment; anything that aggregates across samples is adding a number to itself.
 */
@NullMarked
public interface StorageSampleRepository {

    StorageSample save(StorageSample sample);

    /** The most recent reading, or empty if this tenant has never been sampled. */
    Optional<StorageSample> latest(UUID tenantId);

    /**
     * The oldest reading at or after {@code since} — the baseline for a growth figure.
     *
     * <p>At or after, not before: a tenant sampled for the first time three days ago has no
     * honest 30-day baseline, and inventing one by reaching further back than the data goes
     * would report growth that was really just the start of measurement.
     */
    Optional<StorageSample> earliestSince(UUID tenantId, Instant since);
}

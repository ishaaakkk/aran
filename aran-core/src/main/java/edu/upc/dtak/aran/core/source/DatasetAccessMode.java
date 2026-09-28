package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * How one dataset is read, as a user needs to know it (J2-F3): from its lake snapshot, live from
 * the source, or live with a lake copy the planner may serve instead.
 *
 * <p>A projection of {@link SourceAccess#resolve} plus the dataset's own copy, never a second
 * rule: the resolver decides live-versus-copy, and {@code LIVE_WITH_COPY} is the one shape where
 * both exist — a table imported from a federated relational repository. Queries still read it
 * live; the copy is served only when the physical-placement post-pass finds it legal (an interval
 * refresh keeping it fresh) and cheaper. Object storage and Iceberg never reach that state: for
 * them the per-dataset copy IS the switch to {@code SNAPSHOT}.
 */
@NullMarked
public enum DatasetAccessMode {
    /** Read from the dataset's lake snapshot (an upload, an import, a copied object). */
    SNAPSHOT,
    /** Read live from the source on every query; nothing is stored in the lake. */
    LIVE,
    /** Read live, with a lake copy the planner may serve while an interval refresh keeps it fresh. */
    LIVE_WITH_COPY;

    public static DatasetAccessMode of(@Nullable DataRepository repository, Dataset dataset) {
        boolean hasCopy = dataset.snapshotId() != null;
        if (SourceAccess.resolve(repository, dataset.name(), hasCopy) == AccessMode.MATERIALIZED) {
            return SNAPSHOT;
        }
        return hasCopy ? LIVE_WITH_COPY : LIVE;
    }
}

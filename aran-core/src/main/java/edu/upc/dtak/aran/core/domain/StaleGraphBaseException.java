package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link GraphStore#replaceDraftFromBase} when the tenant's active graph
 * version has moved off the version an incremental write was based on — i.e. a
 * concurrent promote happened between reading {@code G_I^old} and writing the draft.
 * Surfaced by callers as {@code STALE_INCREMENTAL_BASE}.
 */
@NullMarked
public final class StaleGraphBaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final @Nullable UUID expectedActiveVersionId;
    private final @Nullable UUID actualActiveVersionId;

    public StaleGraphBaseException(@Nullable UUID expectedActiveVersionId,
                                   @Nullable UUID actualActiveVersionId) {
        super("Incremental base is stale: expected active version " + expectedActiveVersionId
                + " but the current active version is " + actualActiveVersionId);
        this.expectedActiveVersionId = expectedActiveVersionId;
        this.actualActiveVersionId = actualActiveVersionId;
    }

    public @Nullable UUID expectedActiveVersionId() {
        return expectedActiveVersionId;
    }

    public @Nullable UUID actualActiveVersionId() {
        return actualActiveVersionId;
    }
}

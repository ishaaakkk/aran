package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What registering a dataset with a file hands back.
 *
 * <p>Bootstrapping no longer happens inside the request, so the dataset returned
 * here is {@code PENDING} — not yet a lie about being ready. The job id is how
 * the caller follows the bootstrap to its real end: {@code GET /api/jobs/{id}}
 * or the SSE stream. It is null only when no bootstrap was started (a dataset
 * registered without a snapshot).
 */
@NullMarked
public record DatasetRegistration(Dataset dataset, @Nullable UUID bootstrapJobId) {
}

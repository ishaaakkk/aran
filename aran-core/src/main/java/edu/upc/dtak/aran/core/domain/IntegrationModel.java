package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A project's durable integration model — the single, tenant-scoped owner of the
 * alignments, per-class wrappers and constraints that used to live only in the
 * {@code AlignmentsView} React state and in one-shot {@code integrate} request bodies.
 *
 * <p>There is at most one model per {@code (tenant, project)} (the V16 unique
 * constraint). Its children — {@link ModelSource}, {@link ModelMapping},
 * {@link ModelConstraint} — cascade off it. Integration becomes
 * {@code compile(model)} rather than a request-body payload.
 *
 * <p>{@code activeIntegrationRunId} points at the {@link IntegrationRun} whose draft is
 * the current active graph (null until the first promotion). {@code rebuildRequired} is
 * set when integrated material is edited underneath an incremental baseline, forcing a
 * full rebuild before the next incremental step.
 */
@NullMarked
public record IntegrationModel(
        UUID id,
        UUID tenantId,
        UUID projectId,
        Instant createdAt,
        Instant updatedAt,
        @Nullable UUID activeIntegrationRunId,
        boolean rebuildRequired
) {
}

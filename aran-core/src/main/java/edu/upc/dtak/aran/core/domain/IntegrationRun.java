package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One execution of the schema-integration algorithm against a model — either a full
 * {@link IntegrationRunMode#REBUILD} or an {@link IntegrationRunMode#INCREMENTAL} step.
 *
 * <p>Tracks algorithm-execution state (RUNNING → DRAFT → PROMOTED, or FAILED) which is
 * distinct from the editorial {@link ModelMapping#status()}. For an incremental run
 * {@code baseVersionId} is the {@code G_I^old} it built on and {@code datasetId} the new
 * source added; both are null for a rebuild.
 */
@NullMarked
public record IntegrationRun(
        UUID id,
        UUID tenantId,
        UUID modelId,
        IntegrationRunMode mode,
        IntegrationRunStatus status,
        @Nullable UUID baseVersionId,
        @Nullable UUID datasetId,
        @Nullable UUID draftVersionId,
        @Nullable UUID promotedVersionId,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        Instant startedAt,
        Instant updatedAt
) {
}

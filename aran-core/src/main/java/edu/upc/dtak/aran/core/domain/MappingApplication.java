package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The algorithm-application state of one accepted {@link ModelMapping}, kept apart from
 * its editorial status. Upserted to {@link MappingApplicationState#PENDING} when a
 * mapping is accepted, then moved to APPLIED/DEFERRED by an {@link IntegrationRun}.
 */
@NullMarked
public record MappingApplication(
        UUID id,
        UUID tenantId,
        UUID modelId,
        UUID mappingId,
        MappingApplicationState state,
        @Nullable UUID runId,
        @Nullable UUID versionId,
        @Nullable String reason,
        Instant updatedAt
) {
}

package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.GraphVersion;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Data-transfer objects for the schema-versions API (M7): history rows and the
 * promote-with-notes / rollback responses.
 */
public final class SchemaVersionDtos {

    private SchemaVersionDtos() {
    }

    /** One row of the versions history. */
    public record VersionSummary(
            String id,
            int versionNumber,
            String status,
            Instant createdAt,
            @Nullable String createdBy,
            @Nullable Instant promotedAt,
            @Nullable String notes) {

        public static VersionSummary from(GraphVersion v) {
            return new VersionSummary(
                    v.id().toString(),
                    v.versionNumber(),
                    v.status(),
                    v.createdAt(),
                    v.createdBy() != null ? v.createdBy().toString() : null,
                    v.promotedAt(),
                    v.notes());
        }
    }

    /** Optional promotion notes carried on the promote request body. */
    public record PromoteRequest(@Nullable String notes) {
    }

    /** Response for generic schema promotion. */
    public record PromoteResponse(
            String id,
            int versionNumber,
            String status,
            @Nullable String notes) {
        public static PromoteResponse from(GraphVersion v) {
            return new PromoteResponse(
                    v.id().toString(),
                    v.versionNumber(),
                    v.status(),
                    v.notes());
        }
    }
}

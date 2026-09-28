package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Domain representation of a tenant's graph schema version.
 */
@NullMarked
public record GraphVersion(
    UUID id,
    UUID tenantId,
    int versionNumber,
    String status, // "DRAFT" | "ACTIVE" | "HISTORICAL"
    Instant createdAt,
    @Nullable UUID createdBy,
    @Nullable Instant promotedAt,
    @Nullable String notes
) {
    public GraphVersion {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}

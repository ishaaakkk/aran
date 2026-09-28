package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A tenant — an organization. The top-level isolation boundary of the
 * platform: every other tenant-owned aggregate ({@link Project}, and the
 * {@link Membership}s that grant access) belongs to exactly one tenant.
 *
 * <p>This is a plain core-owned record with no JPA annotations. The
 * persistence adapter maps between this and its own {@code @Entity} type, so
 * Hibernate never leaks into the core's domain model.
 *
 * @param slug short, URL-safe, human-meaningful handle (unique platform-wide)
 */
@NullMarked
public record Tenant(
        UUID id,
        String slug,
        String name,
        Instant createdAt
) {
    public Tenant {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("tenant slug must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tenant name must not be blank");
        }
    }
}

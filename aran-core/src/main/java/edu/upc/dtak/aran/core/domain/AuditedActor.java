package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Audit metadata for user actions inside a tenant context.
 */
@NullMarked
public record AuditedActor(UUID userId) {
    public AuditedActor {
        if (userId == null) {
            throw new IllegalArgumentException("user id must not be null");
        }
    }
}

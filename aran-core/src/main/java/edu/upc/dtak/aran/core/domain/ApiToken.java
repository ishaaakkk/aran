package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A long-lived credential for programmatic access, bound to one user in one tenant.
 *
 * <p>Never carries the token itself. The secret exists exactly once, in the response to the
 * request that created it; what is stored is {@link #tokenHash} plus a short {@link #tokenPrefix}
 * for display. A token that is lost is replaced, not recovered.
 *
 * @param tokenPrefix the leading, non-secret part of the token — enough to identify a row in a
 *                    list, not enough to authenticate
 * @param expiresAt   {@code null} for a token that never expires
 * @param lastUsedAt  {@code null} until the token authenticates a request
 * @param revokedAt   {@code null} while the token is live
 */
@NullMarked
public record ApiToken(
        UUID id,
        UUID tenantId,
        UUID userId,
        String name,
        String tokenHash,
        String tokenPrefix,
        ApiTokenScope scope,
        @Nullable Instant expiresAt,
        @Nullable Instant lastUsedAt,
        @Nullable Instant revokedAt,
        Instant createdAt
) {

    public ApiToken {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("token name must not be blank");
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("token hash must not be blank");
        }
    }

    /** Whether this token may still authenticate a request. */
    public boolean isUsable(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * Why the token cannot be used, for a log line or an error body. {@code null} when it can.
     *
     * <p>Deliberately not returned to the caller of a failed request: telling an unauthenticated
     * client "that token expired" rather than "unauthorized" confirms the token was real.
     */
    public @Nullable String unusableReason(Instant now) {
        if (revokedAt != null) {
            return "revoked";
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return "expired";
        }
        return null;
    }

    public ApiToken revoked(Instant at) {
        return new ApiToken(id, tenantId, userId, name, tokenHash, tokenPrefix, scope,
                expiresAt, lastUsedAt, at, createdAt);
    }

    public ApiToken used(Instant at) {
        return new ApiToken(id, tenantId, userId, name, tokenHash, tokenPrefix, scope,
                expiresAt, at, revokedAt, createdAt);
    }
}

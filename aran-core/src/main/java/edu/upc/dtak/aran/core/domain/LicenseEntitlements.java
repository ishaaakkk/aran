package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The entitlements that apply to a tenant <em>right now</em>.
 *
 * <p>The pivotal value type in the licensing design. Both
 * {@code LicenseProvider} implementations — the cloud-mode DB lookup and the
 * self-hosted signed-token verifier — return this same record. Everything
 * downstream (entitlement gates, the {@code /api/me} response, the UI) reads
 * only from here and never knows where the values came from.
 *
 * <p>Note on usage vs. limits: this record carries <em>limits</em> — the
 * ceilings the tenant is entitled to. <em>Current usage</em> ("how many users
 * does the tenant have right now?") always comes from the application
 * database, regardless of deployment mode. The gates compose the two. In
 * self-hosted mode this matters: the customer's DB cannot be trusted for
 * limits (they could edit it), but it is the authoritative answer for actual
 * usage.
 *
 * <p>Expiry semantics: when {@code expiresAt} is in the past the tenant
 * enters <em>read-only mode</em>. All read endpoints continue to work; every
 * mutating endpoint rejects with HTTP 402. Soft-expiring rather than hard-
 * stopping is deliberate — commercial disputes are not outages.
 *
 * @param plan          the named tier (informational; limits are authoritative)
 * @param status        active / suspended / expired (see {@link LicenseStatus})
 * @param maxUsers      maximum number of {@link Membership} rows in this tenant
 * @param maxProjects   maximum number of {@link Project} rows in this tenant
 * @param maxStorageBytes       ceiling on bytes held in the lake, or {@code null} for no ceiling.
 *                              Null rather than a sentinel like 0 or {@code Long.MAX_VALUE}: "no
 *                              cap" is the OSS default and has to be unmistakable, because a
 *                              sentinel misread as a limit would refuse every upload on a fresh
 *                              install.
 * @param maxMonthlyEgressBytes ceiling on bytes sent to clients in a rolling 30 days, or
 *                              {@code null} for no ceiling
 * @param expiresAt     when the license stops being valid; {@code null} means
 *                      no expiry (perpetual; rare, typically on-prem unlimited)
 */
@NullMarked
public record LicenseEntitlements(
        UUID tenantId,
        LicensePlan plan,
        LicenseStatus status,
        int maxUsers,
        int maxProjects,
        @Nullable Long maxStorageBytes,
        @Nullable Long maxMonthlyEgressBytes,
        Set<LicenseFeature> features,
        @Nullable Instant expiresAt
) {
    public LicenseEntitlements {
        if (maxUsers < 0 || maxProjects < 0) {
            throw new IllegalArgumentException("license limits must be non-negative");
        }
        if ((maxStorageBytes != null && maxStorageBytes < 0)
                || (maxMonthlyEgressBytes != null && maxMonthlyEgressBytes < 0)) {
            throw new IllegalArgumentException("byte limits must be non-negative when set");
        }
        features = Set.copyOf(features);
    }

    /**
     * Whether mutating operations are currently allowed. False if the license
     * is suspended, or if it has an expiry that has passed. Reads are always
     * allowed regardless — read-only mode, not hard-stop.
     */
    public boolean allowsMutations() {
        if (status != LicenseStatus.ACTIVE) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(Instant.now());
    }

    /** Whether the named feature is enabled for this tenant. */
    public boolean has(LicenseFeature feature) {
        return features.contains(feature);
    }
}

package edu.upc.dtak.aran.core.domain;

/**
 * The administrative status of a license, independent of its expiry date.
 *
 * <ul>
 *   <li>{@link #ACTIVE} — license is in force; mutations allowed if not also
 *       past {@code expiresAt}.</li>
 *   <li>{@link #SUSPENDED} — an explicit admin action (cloud) or a token
 *       claim (self-hosted) has paused the license; same effect as expiry —
 *       read-only mode.</li>
 * </ul>
 *
 * <p>{@code CANCELLED} is intentionally not modelled here: in this codebase,
 * a cancelled license is replaced by a new license row (typically {@code FREE})
 * or simply expired. Keeping the status enum small keeps the gates simple.
 */
public enum LicenseStatus {
    ACTIVE,
    SUSPENDED
}

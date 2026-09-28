package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * The set of optional features the platform sells as gated capabilities.
 *
 * <p>Boolean entitlements live here as enum values; the {@code LicenseEntitlements}
 * record stores the set of enabled features. Keeping the catalogue centralised
 * (rather than scattered string keys) means a new gate gets a compile-time
 * check at every call site, and the admin endpoints / token issuer cannot
 * accidentally enable a misspelled feature.
 *
 * <p>The list is intentionally short. Adding a feature here is a deliberate
 * commercial move — it should require a code change, not a config tweak.
 *
 * <ul>
 *   <li>{@link #AUDIT_LOG_EXPORT} — placeholder example of a paid feature. Replace
 *       / extend with real ones as the product grows.</li>
 * </ul>
 */
@NullMarked
public enum LicenseFeature {
    AUDIT_LOG_EXPORT
}

package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * A licensing tier — the named template the platform sells / ships.
 *
 * <p>A small fixed enum on purpose: plans are <em>configuration</em>, not user
 * data. The set of plans is bounded by what the product sells; making it
 * data-driven invites someone to invent a fifth tier in a hurry without going
 * through pricing, which is how plans drift. The DB schema still stores the
 * plan name as a string so adding {@code BUSINESS} later is a single-line
 * change here plus an enum check on the column — no data migration.
 *
 * <ul>
 *   <li>{@link #FREE} — minimal limits, used for trials and demos.</li>
 *   <li>{@link #PRO} — the standard commercial tier.</li>
 *   <li>{@link #ENTERPRISE} — negotiated, custom limits via per-license overrides.</li>
 * </ul>
 *
 * <p>The concrete limits a tier <em>maps to</em> are not stored here — they
 * come from the {@code LicenseProvider} (DB in cloud mode, signed token in
 * self-hosted mode). The plan is the human-facing label; entitlements are the
 * machine-enforced limits.
 */
@NullMarked
public enum LicensePlan {
    FREE,
    PRO,
    ENTERPRISE
}

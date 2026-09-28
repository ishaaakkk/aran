package edu.upc.dtak.aran.core.licensing;

import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Source-of-truth seam for license entitlements.
 *
 * <p>This is the architectural pivot that makes Aran's hybrid cloud /
 * self-hosted deployment work without two enforcement codebases. Two
 * implementations live in their respective adapter modules:
 *
 * <ul>
 *   <li><b>{@code DatabaseLicenseProvider}</b> (cloud SaaS) — reads
 *       {@code license} rows from Aran's own DB. You operate it, you control
 *       the DB, the DB is the source of truth. Provisioning a tenant inserts
 *       a license row in the same transaction.</li>
 *   <li><b>{@code SignedTokenLicenseProvider}</b> (self-hosted) — reads a
 *       JWT-shaped license token (you issue it, the customer drops it in
 *       config or admin UI), verifies the signature against your public key,
 *       parses out entitlements. The customer's DB is <em>not</em> trusted
 *       for entitlement values.</li>
 * </ul>
 *
 * <p>Both return the same {@link LicenseEntitlements} record. Every gate, the
 * 402 mapping, and the UI display read only from there — they never branch on
 * deployment mode. Selecting the provider is a Spring profile / config
 * property concern, handled in the application module.
 *
 * <p>Critical invariant: limits come from this port; <em>current usage</em>
 * always comes from the application database, regardless of mode. The two
 * are composed by {@code LicenseService} / {@code EntitlementGate}.
 */
@NullMarked
public interface LicenseProvider {

    /**
     * The entitlements applying to the given tenant right now.
     *
     * <p>Implementations are expected to be cheap to call (the service layer
     * still caches in front of this, but a provider that talks to the network
     * on every invocation will hurt). Cloud mode reads a single indexed row;
     * self-hosted mode verifies a cached, parsed token.
     *
     * @return the entitlements, or empty if no license is on file for the
     *         tenant. An empty result is itself a denial — see
     *         {@code LicenseService} for the mapping to {@link
     *         LicenseRequiredException}.
     */
    Optional<LicenseEntitlements> findFor(UUID tenantId);
}

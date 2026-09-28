package edu.upc.dtak.aran.core.licensing;

import edu.upc.dtak.aran.core.domain.LicensePlan;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Cloud-mode license administration port.
 *
 * <p>Deliberately separate from {@link LicenseProvider}. The provider is the
 * <em>read</em> seam: both deployment modes implement it. This port is the
 * <em>write</em> seam, and is implemented only in cloud mode — self-hosted
 * deployments do not issue or mutate licenses from inside the application
 * (the customer's running app is not the license authority).
 *
 * <p>The application module wires this in conditionally: present when running
 * with the {@code cloud} Spring profile, absent otherwise. Admin endpoints
 * that depend on it (tenant creation, license changes) are themselves only
 * exposed under that profile.
 *
 * <p>Implementations should map {@link LicensePlan} values to concrete limits
 * via configuration, so plan-to-limits mapping is one place to change. See
 * {@code application.yml} {@code aran.licensing.plans.*}.
 */
@NullMarked
public interface LicenseAdminPort {

    /**
     * Issue the initial license for a brand-new tenant.
     *
     * <p>Called inside the same transaction as the {@code tenant} row insert.
     * Implementations must reject re-issuance for a tenant that already has
     * an active license (FK + unique constraint at the DB level).
     */
    void issueInitialLicense(UUID tenantId, LicensePlan plan);
}

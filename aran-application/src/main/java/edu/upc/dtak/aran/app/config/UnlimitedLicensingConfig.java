package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicenseFeature;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.LicenseStatus;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Licensing for the OSS single-node install ({@code aran.licensing.mode=unlimited}).
 *
 * <p>The third {@link LicenseProvider}, alongside the cloud DB lookup and the self-hosted
 * signed-token verifier. It answers every tenant with uncapped entitlements and never reads
 * storage, so an install that nobody sells has no quota to breach and no license row to be
 * missing.
 *
 * <h2>Why a provider rather than deleting the gates</h2>
 *
 * <p>The licensing engine stays, and a deployment that does not sell anything treats it as a
 * port with a trivial implementation — subtraction, not surgery. The eight gate call sites in
 * {@code ProjectService}, {@code TenantOwnerService} and {@code TenantAdminService} stay exactly
 * as they are, and switching licensing off stays a matter of choosing an adapter.
 *
 * <h2>What this fixes</h2>
 *
 * <p>{@code compose.standalone.yml} previously ran {@code database} mode, where entitlements come
 * from a {@code license} row. {@link AdminBootstrap} creates the tenant, its owner and their
 * membership — but no license row, because issuing one is
 * {@link LicenseAdminPort}'s job and that is a cloud-only port. A fresh standalone install
 * therefore threw {@code LicenseRequiredException} from the first {@code requireMutationsAllowed},
 * i.e. on creating its first project. Even had a row existed, the only plausible plan for a fresh
 * install is {@code FREE} at 5 users / 3 projects, which is not a limit an operator who owns the
 * hardware ever asked for.
 */
@Configuration
@ConditionalOnProperty(prefix = "aran.licensing", name = "mode", havingValue = "unlimited")
@NullMarked
public class UnlimitedLicensingConfig {

    @Bean
    public LicenseProvider unlimitedLicenseProvider() {
        return new UnlimitedLicenseProvider();
    }

    /**
     * Keeps the platform-admin surface behaving as it does in {@code database} mode:
     * {@code PlatformAdminController} is {@code @ConditionalOnBean(LicenseAdminPort.class)}, so
     * without this bean tenant administration would silently disappear in OSS only.
     *
     * <p>It is no longer needed to make {@code TenantAdminService} constructible — that service now
     * takes the port as an {@code ObjectProvider} — so removing this bean is a safe way to hide
     * tenant administration from a single-tenant install, if that is wanted. It is kept because
     * "the OSS build quietly lacks an admin screen the cloud build has" is a decision worth making
     * deliberately rather than inheriting from a bean's absence.
     */
    @Bean
    public LicenseAdminPort noOpLicenseAdminPort() {
        return new NoOpLicenseAdminPort();
    }

    static class UnlimitedLicenseProvider implements LicenseProvider {

        @Override
        public Optional<LicenseEntitlements> findFor(UUID tenantId) {
            return Optional.of(new LicenseEntitlements(
                    tenantId,
                    LicensePlan.ENTERPRISE,
                    LicenseStatus.ACTIVE,
                    // The quota gates compare `current >= max`, so a sentinel this large is
                    // unreachable rather than merely large. Not modelled as a nullable "no
                    // ceiling" the way the byte limits are: that would change
                    // LicenseEntitlements for the two providers that do enforce counts.
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    // Null is already the no-ceiling convention for bytes; the gates return early.
                    null,
                    null,
                    EnumSet.allOf(LicenseFeature.class),
                    null
            ));
        }
    }

    /**
     * Accepts the call and does nothing. An OSS install has one tenant, created by
     * {@link AdminBootstrap}; nothing needs a license row when the provider above never consults
     * one. Silent rather than throwing, so the platform-admin tenant-creation path keeps working.
     */
    static class NoOpLicenseAdminPort implements LicenseAdminPort {

        @Override
        public void issueInitialLicense(UUID tenantId, LicensePlan plan) {
            // Intentionally empty — see the class javadoc.
        }
    }
}

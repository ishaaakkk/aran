package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicenseFeature;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.licensing.UsageCounter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for the OSS {@code unlimited} licensing mode.
 *
 * <p>Pure unit tests over a real {@link LicenseService} — the point is that the gates themselves,
 * unmodified, stop refusing once this provider is behind them.
 */
@DisplayName("UnlimitedLicensingConfig")
class UnlimitedLicensingConfigTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    /** Usage far above every limit in the shipped `free` plan (5 users / 3 projects). */
    private static final long WELL_PAST_FREE_PLAN = 10_000L;

    private final LicenseProvider provider = new UnlimitedLicensingConfig.UnlimitedLicenseProvider();

    private AuthenticatedUser caller() {
        return new AuthenticatedUser(UUID.randomUUID(), "subject", TENANT_ID, TenantRole.OWNER);
    }

    /** Reports the same large usage for everything, so no gate can pass by accident. */
    private LicenseService serviceAtUsage(long count, long bytes) {
        return new LicenseService(provider, new UsageCounter() {
            @Override public long countUsers(UUID tenantId) { return count; }
            @Override public long countProjects(UUID tenantId) { return count; }
            @Override public long estimatedStorageBytes(UUID tenantId) { return bytes; }
            @Override public long egressBytesSince(UUID tenantId, Instant since) { return bytes; }
        });
    }

    @Test
    @DisplayName("issues entitlements for a tenant with no license row")
    void issuesEntitlementsWithoutARow() {
        // The failure this mode exists to fix: AdminBootstrap creates the standalone tenant
        // without a license row, so `database` mode has nothing to read and denies outright.
        assertThat(provider.findFor(TENANT_ID)).isPresent();
    }

    @Test
    @DisplayName("every gate passes far beyond the free plan's limits")
    void gatesPassBeyondFreePlanLimits() {
        LicenseService licensing = serviceAtUsage(WELL_PAST_FREE_PLAN, Long.MAX_VALUE / 2);
        AuthenticatedUser caller = caller();

        assertThatCode(() -> {
            licensing.requireMutationsAllowed(caller);
            licensing.requireWithinUserLimit(caller);
            licensing.requireWithinProjectLimit(caller);
            licensing.requireWithinStorageLimit(caller, Long.MAX_VALUE / 2);
            licensing.requireWithinEgressLimit(caller);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enables every feature in the catalogue")
    void enablesEveryFeature() {
        // EnumSet.allOf rather than a hand-listed set: a feature added later must not silently
        // become a paywall on an install that has nobody to pay.
        LicenseService licensing = serviceAtUsage(0, 0);
        AuthenticatedUser caller = caller();

        for (LicenseFeature feature : LicenseFeature.values()) {
            assertThatCode(() -> licensing.requireFeature(caller, feature))
                    .as("feature %s", feature)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("reports no byte ceilings and never expires")
    void reportsNoCeilingsAndNoExpiry() {
        LicenseEntitlements e = provider.findFor(TENANT_ID).orElseThrow();

        assertThat(e.tenantId()).isEqualTo(TENANT_ID);
        assertThat(e.maxStorageBytes()).isNull();
        assertThat(e.maxMonthlyEgressBytes()).isNull();
        assertThat(e.expiresAt()).isNull();
        assertThat(e.allowsMutations()).isTrue();
    }

    @Nested
    @DisplayName("bean registration")
    class BeanRegistration {

        // ApplicationContextRunner rather than @SpringBootTest: this asserts the @ConditionalOnProperty
        // wiring only, and a full context would need a datasource and therefore Docker.
        private final ApplicationContextRunner contexts =
                new ApplicationContextRunner().withUserConfiguration(UnlimitedLicensingConfig.class);

        @Test
        @DisplayName("registers a provider and an admin port under mode=unlimited")
        void registersBothBeansWhenUnlimited() {
            contexts.withPropertyValues("aran.licensing.mode=unlimited").run(context -> {
                assertThat(context).hasSingleBean(LicenseProvider.class);
                // Present so TenantAdminService can be constructed — see NoOpLicenseAdminPort.
                assertThat(context).hasSingleBean(LicenseAdminPort.class);
            });
        }

        @Test
        @DisplayName("contributes nothing in the other modes")
        void contributesNothingOtherwise() {
            // Cloud must keep its DatabaseLicenseProvider; a stray unlimited provider here would
            // silently uncap every paying tenant.
            for (String mode : new String[] {"database", "signed-token"}) {
                contexts.withPropertyValues("aran.licensing.mode=" + mode).run(context -> {
                    assertThat(context).doesNotHaveBean(LicenseProvider.class);
                    assertThat(context).doesNotHaveBean(LicenseAdminPort.class);
                });
            }
        }

        @Test
        @DisplayName("contributes nothing when the mode is unset")
        void contributesNothingWhenUnset() {
            contexts.run(context -> assertThat(context).doesNotHaveBean(LicenseProvider.class));
        }
    }

    @Test
    @DisplayName("the no-op admin port accepts issuance without persisting anything")
    void noOpAdminPortIsSilent() {
        // Its only job is to exist: TenantAdminService takes LicenseAdminPort as a constructor
        // dependency, and a mode with no issuer used to make that bean unsatisfiable.
        assertThatCode(() -> new UnlimitedLicensingConfig.NoOpLicenseAdminPort()
                .issueInitialLicense(TENANT_ID, edu.upc.dtak.aran.core.domain.LicensePlan.FREE))
                .doesNotThrowAnyException();
    }
}

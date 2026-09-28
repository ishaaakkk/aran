package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.*;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseExpiredException;
import edu.upc.dtak.aran.core.licensing.LicenseLimitExceededException;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import edu.upc.dtak.aran.core.licensing.LicenseRequiredException;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.licensing.UsageCounter;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LicenseService}.
 *
 * <p>Pure unit tests — no Spring context, no database. {@link LicenseProvider}
 * and {@link UsageCounter} are mocked so each gate can be tested in isolation.
 *
 * <p>These tests cover the gate logic that is the most security-sensitive part
 * of the licensing layer. Every error condition should have a case here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LicenseService")
class LicenseServiceTest {

    @Mock
    LicenseProvider provider;

    @Mock
    UsageCounter usage;

    LicenseService licenseService;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID   = UUID.randomUUID();

    private AuthenticatedUser caller(TenantRole role) {
        return new AuthenticatedUser(USER_ID, "subject", TENANT_ID, role);
    }

    @BeforeEach
    void setUp() {
        licenseService = new LicenseService(provider, usage);
    }

    private LicenseEntitlements activeEntitlements(int maxUsers, int maxProjects) {
        return new LicenseEntitlements(
                TENANT_ID, LicensePlan.PRO, LicenseStatus.ACTIVE,
                maxUsers, maxProjects, null, null, Set.of(), null);
    }

    @Nested
    @DisplayName("requireMutationsAllowed")
    class RequireMutationsAllowed {

        @Test
        @DisplayName("passes when license is active and not expired")
        void passes_for_active_license() {
            when(provider.findFor(TENANT_ID))
                    .thenReturn(Optional.of(activeEntitlements(10, 10)));

            assertThatCode(() -> licenseService.requireMutationsAllowed(caller(TenantRole.OWNER)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws LicenseRequiredException when no license exists")
        void throws_when_no_license() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> licenseService.requireMutationsAllowed(caller(TenantRole.OWNER)))
                    .isInstanceOf(LicenseRequiredException.class);
        }

        @Test
        @DisplayName("throws LicenseExpiredException when license is suspended")
        void throws_when_suspended() {
            var suspended = new LicenseEntitlements(
                    TENANT_ID, LicensePlan.PRO, LicenseStatus.SUSPENDED,
                    10, 10, null, null, Set.of(), null);
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(suspended));

            assertThatThrownBy(() -> licenseService.requireMutationsAllowed(caller(TenantRole.OWNER)))
                    .isInstanceOf(LicenseExpiredException.class);
        }

        @Test
        @DisplayName("throws LicenseExpiredException when license is past expiresAt")
        void throws_when_expired() {
            var expired = new LicenseEntitlements(
                    TENANT_ID, LicensePlan.PRO, LicenseStatus.ACTIVE,
                    10, 10, null, null, Set.of(), Instant.now().minusSeconds(1));
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> licenseService.requireMutationsAllowed(caller(TenantRole.OWNER)))
                    .isInstanceOf(LicenseExpiredException.class);
        }

        @Test
        @DisplayName("passes when license has a future expiresAt")
        void passes_when_not_yet_expired() {
            var future = new LicenseEntitlements(
                    TENANT_ID, LicensePlan.PRO, LicenseStatus.ACTIVE,
                    10, 10, null, null, Set.of(), Instant.now().plusSeconds(3600));
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(future));

            assertThatCode(() -> licenseService.requireMutationsAllowed(caller(TenantRole.OWNER)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("requireWithinUserLimit")
    class RequireWithinUserLimit {

        @Test
        @DisplayName("passes when current users < max")
        void passes_below_limit() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(activeEntitlements(5, 10)));
            when(usage.countUsers(TENANT_ID)).thenReturn(4L);

            assertThatCode(() -> licenseService.requireWithinUserLimit(caller(TenantRole.OWNER)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws LicenseLimitExceededException when at the limit")
        void throws_at_limit() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(activeEntitlements(5, 10)));
            when(usage.countUsers(TENANT_ID)).thenReturn(5L);

            assertThatThrownBy(() -> licenseService.requireWithinUserLimit(caller(TenantRole.OWNER)))
                    .isInstanceOf(LicenseLimitExceededException.class)
                    .hasMessageContaining("maxUsers");
        }

        @Test
        @DisplayName("LicenseLimitExceededException carries correct usage numbers")
        void carries_correct_numbers() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(activeEntitlements(5, 10)));
            when(usage.countUsers(TENANT_ID)).thenReturn(5L);

            assertThatThrownBy(() -> licenseService.requireWithinUserLimit(caller(TenantRole.OWNER)))
                    .isInstanceOfSatisfying(LicenseLimitExceededException.class, ex -> {
                        org.assertj.core.api.Assertions.assertThat(ex.currentUsage()).isEqualTo(5L);
                        org.assertj.core.api.Assertions.assertThat(ex.limit()).isEqualTo(5L);
                        org.assertj.core.api.Assertions.assertThat(ex.limitName()).isEqualTo("maxUsers");
                    });
        }
    }

    @Nested
    @DisplayName("requireFeature")
    class RequireFeature {

        @Test
        @DisplayName("passes when feature is enabled")
        void passes_when_enabled() {
            var withFeature = new LicenseEntitlements(
                    TENANT_ID, LicensePlan.PRO, LicenseStatus.ACTIVE,
                    10, 10, null, null, Set.of(LicenseFeature.AUDIT_LOG_EXPORT), null);
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(withFeature));

            assertThatCode(() -> licenseService.requireFeature(
                    caller(TenantRole.OWNER), LicenseFeature.AUDIT_LOG_EXPORT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws when feature is not enabled")
        void throws_when_not_enabled() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(activeEntitlements(10, 10)));
            // activeEntitlements has empty features set

            assertThatThrownBy(() -> licenseService.requireFeature(
                    caller(TenantRole.OWNER), LicenseFeature.AUDIT_LOG_EXPORT))
                    .isInstanceOf(LicenseLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("cache behaviour")
    class CacheBehaviour {

        @Test
        @DisplayName("second call does not hit the provider again")
        void caches_entitlements() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(activeEntitlements(10, 10)));

            licenseService.entitlementsFor(TENANT_ID);
            licenseService.entitlementsFor(TENANT_ID);

            // Provider should have been called exactly once — second call was cached.
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(1)).findFor(TENANT_ID);
        }

        @Test
        @DisplayName("invalidate forces a fresh provider call")
        void invalidate_forces_fresh_call() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(activeEntitlements(10, 10)));

            licenseService.entitlementsFor(TENANT_ID);
            licenseService.invalidate(TENANT_ID);
            licenseService.entitlementsFor(TENANT_ID);

            org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(2)).findFor(TENANT_ID);
        }
    }

    @Nested
    @DisplayName("byte ceilings")
    class ByteCeilings {

        private LicenseEntitlements withCeilings(Long storage, Long egress) {
            return new LicenseEntitlements(
                    TENANT_ID, LicensePlan.PRO, LicenseStatus.ACTIVE,
                    10, 10, storage, egress, Set.of(), null);
        }

        @Test
        @DisplayName("no ceiling set means no ceiling — the OSS default must never refuse an upload")
        void unsetIsUnlimited() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(withCeilings(null, null)));

            licenseService.requireWithinStorageLimit(TENANT_ID, Long.MAX_VALUE / 2);
            licenseService.requireWithinEgressLimit(TENANT_ID);

            // And the counters are never even consulted: an unset ceiling costs no query.
            verifyNoInteractions(usage);
        }

        @Test
        @DisplayName("an upload that would cross the storage ceiling is refused before it lands")
        void storageCeilingCountsIncomingBytes() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(withCeilings(1000L, null)));
            when(usage.estimatedStorageBytes(TENANT_ID)).thenReturn(900L);

            // 900 held + 200 incoming crosses 1000, and is refused now rather than discovered after.
            assertThatThrownBy(() -> licenseService.requireWithinStorageLimit(TENANT_ID, 200))
                    .isInstanceOf(LicenseLimitExceededException.class);
        }

        @Test
        @DisplayName("an upload that exactly fills the ceiling is allowed")
        void exactlyAtTheCeilingIsAllowed() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(withCeilings(1000L, null)));
            when(usage.estimatedStorageBytes(TENANT_ID)).thenReturn(900L);

            // The limit is what you may hold, not what you must stay under.
            licenseService.requireWithinStorageLimit(TENANT_ID, 100);
        }

        @Test
        @DisplayName("asking with zero incoming bytes tests only whether the tenant is already over")
        void zeroIncomingChecksCurrentState() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(withCeilings(1000L, null)));
            when(usage.estimatedStorageBytes(TENANT_ID)).thenReturn(1001L);

            assertThatThrownBy(() -> licenseService.requireWithinStorageLimit(TENANT_ID, 0))
                    .isInstanceOf(LicenseLimitExceededException.class);
        }

        @Test
        @DisplayName("egress under the ceiling passes; over it is refused")
        void egressCeiling() {
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(withCeilings(null, 5000L)));

            when(usage.egressBytesSince(eq(TENANT_ID), any())).thenReturn(4999L);
            licenseService.requireWithinEgressLimit(TENANT_ID);

            when(usage.egressBytesSince(eq(TENANT_ID), any())).thenReturn(5001L);
            assertThatThrownBy(() -> licenseService.requireWithinEgressLimit(TENANT_ID))
                    .isInstanceOf(LicenseLimitExceededException.class);
        }

        @Test
        @DisplayName("the request that crosses the egress line is allowed to finish")
        void egressIsMeasuredOverCompletedWork() {
            // Measured before the export runs, against what previous ones used. Refusing
            // mid-stream would hand a client a truncated file it cannot tell from a whole one,
            // so the crossing request completes and the next one is refused.
            when(provider.findFor(TENANT_ID)).thenReturn(Optional.of(withCeilings(null, 5000L)));
            when(usage.egressBytesSince(eq(TENANT_ID), any())).thenReturn(5000L);

            licenseService.requireWithinEgressLimit(TENANT_ID);
        }
    }
}

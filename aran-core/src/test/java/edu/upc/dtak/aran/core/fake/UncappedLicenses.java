package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.LicenseStatus;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.licensing.UsageCounter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link LicenseService} that permits everything.
 *
 * <p>For tests of services that now take one but are not testing licensing. Explicitly uncapped
 * rather than a mock returning nulls: a service that started refusing uploads because a mock
 * answered 0 for a limit would fail in a way that looks like the feature under test is broken.
 */
public final class UncappedLicenses {

    private UncappedLicenses() {
    }

    public static LicenseService create() {
        LicenseProvider provider = tenantId -> Optional.of(new LicenseEntitlements(
                tenantId, LicensePlan.ENTERPRISE, LicenseStatus.ACTIVE,
                Integer.MAX_VALUE, Integer.MAX_VALUE,
                null, null,   // no byte ceilings — the OSS default
                java.util.Set.of(), null));

        UsageCounter counter = new UsageCounter() {
            @Override public long countUsers(UUID tenantId) {
                return 0;
            }

            @Override public long countProjects(UUID tenantId) {
                return 0;
            }

            @Override public long estimatedStorageBytes(UUID tenantId) {
                return 0;
            }

            @Override public long egressBytesSince(UUID tenantId, Instant since) {
                return 0;
            }
        };
        return new LicenseService(provider, counter);
    }
}

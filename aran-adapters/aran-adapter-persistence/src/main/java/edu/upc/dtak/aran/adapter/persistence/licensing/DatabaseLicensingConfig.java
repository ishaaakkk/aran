package edu.upc.dtak.aran.adapter.persistence.licensing;

import edu.upc.dtak.aran.adapter.persistence.entity.LicenseEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.LicenseJpaRepository;
import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.LicenseStatus;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cloud-mode licensing — the {@link LicenseProvider} reads from the
 * {@code license} table, and the {@link LicenseAdminPort} writes to it.
 *
 * <p>Both beans are conditional on {@code aran.licensing.mode=database}
 * (the default in cloud deployments; see {@code application.yml}). In
 * self-hosted mode this whole configuration is absent and the
 * {@code SignedTokenLicenseProvider} takes over — without a {@code LicenseAdminPort}
 * bean, the platform-admin write endpoints are simply not exposed.
 *
 * <p>Plan-to-limits mapping comes from {@link LicensePlansProperties};
 * per-tenant overrides on the {@code license} row take precedence when set.
 *
 * <p><b>The beans below are {@code @Bean} methods, not nested {@code @Component}s, and that is
 * load-bearing.</b> This package is inside {@code PersistenceConfiguration}'s
 * {@code @ComponentScan}, and a scan registers an independent nested {@code @Component} on its own
 * metadata — the enclosing class's {@code @ConditionalOnProperty} never gets a say. As
 * {@code @Component}s these two were therefore registered in <em>every</em> licensing mode: in
 * {@code signed-token} that put a second {@link LicenseProvider} beside the token one, which is a
 * {@code NoUniqueBeanDefinitionException} at startup. Nothing caught it because no test and no
 * compose file boots any mode but {@code database}. A {@code @Bean} method is only processed if
 * the enclosing configuration class survives its own condition, which is the behaviour intended
 * here all along.
 */
@Configuration
@ConditionalOnProperty(prefix = "aran.licensing", name = "mode",
        havingValue = "database", matchIfMissing = true)
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        LicensePlansProperties.class)
@NullMarked
public class DatabaseLicensingConfig {

    @Bean
    public LicenseProvider databaseLicenseProvider(LicenseJpaRepository jpa,
                                                   LicensePlansProperties plans) {
        return new DatabaseLicenseProvider(jpa, plans);
    }

    @Bean
    public LicenseAdminPort databaseLicenseAdminPort(LicenseJpaRepository jpa) {
        return new DatabaseLicenseAdminPort(jpa);
    }

    static class DatabaseLicenseProvider implements LicenseProvider {

        private final LicenseJpaRepository jpa;
        private final LicensePlansProperties plans;

        DatabaseLicenseProvider(LicenseJpaRepository jpa, LicensePlansProperties plans) {
            this.jpa = jpa;
            this.plans = plans;
        }

        @Override
        public Optional<LicenseEntitlements> findFor(UUID tenantId) {
            return jpa.findById(tenantId).map(this::toEntitlements);
        }

        private LicenseEntitlements toEntitlements(LicenseEntity e) {
            var defaults = plans.limitsFor(e.getPlan());
            int maxUsers = e.getMaxUsersOverride() != null
                    ? e.getMaxUsersOverride() : defaults.getMaxUsers();
            int maxProjects = e.getMaxProjectsOverride() != null
                    ? e.getMaxProjectsOverride() : defaults.getMaxProjects();
            return new LicenseEntitlements(
                    e.getTenantId(),
                    e.getPlan(),
                    e.getStatus(),
                    maxUsers,
                    maxProjects,
                    defaults.getMaxStorageBytes(),
                    defaults.getMaxMonthlyEgressBytes(),
                    defaults.getFeatures(),
                    e.getExpiresAt()
            );
        }
    }

    static class DatabaseLicenseAdminPort implements LicenseAdminPort {

        private final LicenseJpaRepository jpa;

        DatabaseLicenseAdminPort(LicenseJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        public void issueInitialLicense(UUID tenantId, LicensePlan plan) {
            // FK to tenant + PK on tenant_id together ensure exactly one
            // license per tenant — re-issuance fails at the DB layer rather
            // than silently overwriting.
            jpa.save(new LicenseEntity(
                    tenantId,
                    plan,
                    LicenseStatus.ACTIVE,
                    Instant.now()
            ));
        }
    }
}

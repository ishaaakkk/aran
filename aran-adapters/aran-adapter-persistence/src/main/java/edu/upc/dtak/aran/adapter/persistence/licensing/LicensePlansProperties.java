package edu.upc.dtak.aran.adapter.persistence.licensing;

import edu.upc.dtak.aran.core.domain.LicenseFeature;
import edu.upc.dtak.aran.core.domain.LicensePlan;

import java.util.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration mapping each {@link LicensePlan} to its default entitlement
 * limits. Bound from {@code aran.licensing.plans.*} in {@code application.yml}.
 *
 * <p>The plan-to-limits mapping is configuration on purpose: pricing/packaging
 * changes shouldn't require a code change. Per-tenant overrides live on the
 * {@code license} row and take precedence when set; this class only provides
 * the defaults for a given tier.
 */
@ConfigurationProperties(prefix = "aran.licensing")
public class LicensePlansProperties {

    private Map<String, PlanLimits> plans = new HashMap<>();

    public Map<String, PlanLimits> getPlans() { return plans; }
    public void setPlans(Map<String, PlanLimits> plans) { this.plans = plans; }

    /**
     * Resolve the defaults for the given plan. Throws (rather than returning
     * a "safe default") because a missing plan mapping is a configuration
     * bug — failing loudly at first access is preferable to silently giving
     * everyone unlimited or zero quota.
     */
    public PlanLimits limitsFor(LicensePlan plan) {
        PlanLimits limits = plans.get(plan.name().toLowerCase());
        if (limits == null) {
            throw new IllegalStateException(
                    "no limits configured for plan " + plan +
                    " — add aran.licensing.plans." + plan.name().toLowerCase() +
                    " in application.yml");
        }
        return limits;
    }

    public static class PlanLimits {
        private int maxUsers;
        private int maxProjects;
        /** Null means no ceiling — the default, and what every OSS install runs with. */
        private Long maxStorageBytes;
        private Long maxMonthlyEgressBytes;
        private Set<LicenseFeature> features = new HashSet<>();

        public int getMaxUsers() { return maxUsers; }
        public void setMaxUsers(int maxUsers) { this.maxUsers = maxUsers; }
        public int getMaxProjects() { return maxProjects; }
        public void setMaxProjects(int maxProjects) { this.maxProjects = maxProjects; }
        public Long getMaxStorageBytes() { return maxStorageBytes; }
        public void setMaxStorageBytes(Long maxStorageBytes) { this.maxStorageBytes = maxStorageBytes; }
        public Long getMaxMonthlyEgressBytes() { return maxMonthlyEgressBytes; }
        public void setMaxMonthlyEgressBytes(Long v) { this.maxMonthlyEgressBytes = v; }
        public Set<LicenseFeature> getFeatures() { return features; }
        public void setFeatures(Set<LicenseFeature> features) { this.features = features; }
    }
}

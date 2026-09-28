package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.MembershipJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.ProjectJpaRepository;
import edu.upc.dtak.aran.core.domain.StorageSampleRepository;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.licensing.UsageCounter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of {@link UsageCounter}.
 *
 * <p>Each method is a single indexed {@code COUNT(*)} on a tenant-scoped
 * column. Cheap, but not cached — see the rationale on
 * {@code LicenseService}: a stale count would silently let a quota be
 * breached, which is the one outcome the licensing layer exists to prevent.
 */
@Component
@NullMarked
class JpaUsageCounter implements UsageCounter {

    @PersistenceContext
    private EntityManager entityManager;

    private final MembershipJpaRepository memberships;
    private final ProjectJpaRepository projects;
    private final StorageSampleRepository samples;

    JpaUsageCounter(MembershipJpaRepository memberships, ProjectJpaRepository projects,
                    StorageSampleRepository samples) {
        this.memberships = memberships;
        this.projects = projects;
        this.samples = samples;
    }

    @Override
    public long countUsers(UUID tenantId) {
        return memberships.countByTenantId(tenantId);
    }

    @Override
    public long countProjects(UUID tenantId) {
        return projects.countByTenantId(tenantId);
    }

    @Override
    public long estimatedStorageBytes(UUID tenantId) {
        // The baseline, and the moment it was measured. With no sample the accrual below runs
        // from the epoch, which sums every byte the tenant has ever written — high, but a
        // ceiling that errs high is the safe direction and the first sample corrects it.
        var latest = samples.latest(tenantId);
        long baseline = latest.map(s -> s.physicalBytes()).orElse(0L);
        Instant since = latest.map(s -> s.measuredAt()).orElse(Instant.EPOCH);

        Number accrued = (Number) entityManager.createNativeQuery("""
                SELECT COALESCE(SUM(byte_count), 0)
                  FROM usage_event
                 WHERE tenant_id = :tenantId
                   AND occurred_at > :since
                   AND kind IN (:kinds)
                """)
                .setParameter("tenantId", tenantId)
                .setParameter("since", since)
                // Bytes that landed in the lake — uploads and materializations. Egress is
                // excluded on purpose: sending a copy to a client does not store anything.
                .setParameter("kinds", storedAndIngestKinds())
                .getSingleResult();

        return baseline + accrued.longValue();
    }

    @Override
    public long egressBytesSince(UUID tenantId, Instant since) {
        Number total = (Number) entityManager.createNativeQuery("""
                SELECT COALESCE(SUM(byte_count), 0)
                  FROM usage_event
                 WHERE tenant_id = :tenantId
                   AND occurred_at >= :since
                   AND kind IN (:kinds)
                """)
                .setParameter("tenantId", tenantId)
                .setParameter("since", since)
                .setParameter("kinds", UsageKind.namesWithDirection(UsageKind.Direction.EGRESS))
                .getSingleResult();
        return total.longValue();
    }

    /** Everything that adds bytes to the lake: what arrived, and what Aran wrote there itself. */
    private static java.util.List<String> storedAndIngestKinds() {
        var kinds = new java.util.ArrayList<String>();
        kinds.addAll(UsageKind.namesWithDirection(UsageKind.Direction.INGRESS));
        kinds.addAll(UsageKind.namesWithDirection(UsageKind.Direction.STORED));
        return kinds;
    }
}

package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryTenantRepository implements TenantRepository {

    private final Map<UUID, Tenant> tenants = new HashMap<>();
    private final Map<UUID, Membership> memberships = new HashMap<>();

    @Override
    public Optional<Tenant> findById(UUID tenantId) {
        return Optional.ofNullable(tenants.get(tenantId));
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return tenants.values().stream()
                .filter(t -> t.slug().equals(slug))
                .findFirst();
    }

    @Override
    public Tenant save(Tenant tenant) {
        tenants.put(tenant.id(), tenant);
        return tenant;
    }

    @Override
    public List<Membership> findMembershipsForUser(UUID userId) {
        return memberships.values().stream()
                .filter(m -> m.userId().equals(userId))
                .toList();
    }

    @Override
    public List<Membership> findMembershipsForTenant(UUID tenantId) {
        return memberships.values().stream()
                .filter(m -> m.tenantId().equals(tenantId))
                .toList();
    }

    @Override
    public Optional<Membership> findMembership(UUID userId, UUID tenantId) {
        return memberships.values().stream()
                .filter(m -> m.userId().equals(userId) && m.tenantId().equals(tenantId))
                .findFirst();
    }

    @Override
    public Membership saveMembership(Membership membership) {
        memberships.put(membership.id(), membership);
        return membership;
    }

    @Override
    public void deleteMembership(UUID membershipId) {
        memberships.remove(membershipId);
    }

    @Override
    public long countOwners(UUID tenantId) {
        return memberships.values().stream()
                .filter(m -> m.tenantId().equals(tenantId) && m.role() == TenantRole.OWNER)
                .count();
    }
}

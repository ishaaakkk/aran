package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.MembershipJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.TenantJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.mapper.EntityMapper;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import edu.upc.dtak.aran.core.domain.TenantRole;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link TenantRepository} port.
 *
 * <p>Covers both tenants and the membership rows that connect users to them.
 * Not tenant-filtered: this port answers questions that exist before a tenant
 * context does (which tenants a user may enter).
 */
@Component
@NullMarked
class JpaTenantRepository implements TenantRepository {

    private final TenantJpaRepository tenantJpa;
    private final MembershipJpaRepository membershipJpa;

    JpaTenantRepository(TenantJpaRepository tenantJpa, MembershipJpaRepository membershipJpa) {
        this.tenantJpa = tenantJpa;
        this.membershipJpa = membershipJpa;
    }

    @Override
    public Optional<Tenant> findById(UUID tenantId) {
        return tenantJpa.findById(tenantId).map(EntityMapper::toDomain);
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return tenantJpa.findBySlug(slug).map(EntityMapper::toDomain);
    }

    @Override
    public Tenant save(Tenant tenant) {
        return EntityMapper.toDomain(tenantJpa.save(EntityMapper.toEntity(tenant)));
    }

    @Override
    public List<Membership> findMembershipsForUser(UUID userId) {
        return membershipJpa.findByUserId(userId).stream()
                .map(EntityMapper::toDomain)
                .toList();
    }

    @Override
    public List<Membership> findMembershipsForTenant(UUID tenantId) {
        return membershipJpa.findByTenantId(tenantId).stream()
                .map(EntityMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Membership> findMembership(UUID userId, UUID tenantId) {
        return membershipJpa.findByUserIdAndTenantId(userId, tenantId)
                .map(EntityMapper::toDomain);
    }

    @Override
    public Membership saveMembership(Membership membership) {
        return EntityMapper.toDomain(membershipJpa.save(EntityMapper.toEntity(membership)));
    }

    @Override
    public void deleteMembership(UUID membershipId) {
        // deleteById on Spring Data JPA tolerates absent ids — exactly the
        // idempotent semantics the port contract calls for.
        membershipJpa.deleteById(membershipId);
    }

    @Override
    public long countOwners(UUID tenantId) {
        return membershipJpa.countByTenantIdAndRole(tenantId,
                TenantRole.OWNER);
    }
}

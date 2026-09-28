package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant resolution now comes from Aran's own membership row rather than a JWT claim.
 *
 * <p>The rule these tests pin is that the answer is <em>one</em> tenant or none — never a choice.
 * V23's {@code uq_membership_user} makes a second membership unreachable through the database;
 * this covers what happens if it is reached anyway, because "pick one" is the single behaviour
 * that would silently hand a caller another organisation's data.
 */
class SoleMembershipTest {

    private static final UUID USER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Membership membership(UUID tenantId, TenantRole role) {
        return new Membership(UUID.randomUUID(), USER, tenantId, role, Instant.EPOCH);
    }

    private static IdentityService serviceHolding(Membership... held) {
        TenantRepository tenants = new StubTenantRepository(List.of(held));
        return new IdentityService(new StubUserRepository(), tenants, null);
    }

    @Test
    @DisplayName("one membership resolves to that tenant and role")
    void resolvesTheOneMembership() {
        IdentityService identity = serviceHolding(membership(TENANT_A, TenantRole.OWNER));

        Optional<Membership> resolved = identity.soleMembership(USER);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().tenantId()).isEqualTo(TENANT_A);
        assertThat(resolved.get().role()).isEqualTo(TenantRole.OWNER);
    }

    @Test
    @DisplayName("no membership is empty, not an error — the caller decides what that means")
    void noMembershipIsEmpty() {
        assertThat(serviceHolding().soleMembership(USER)).isEmpty();
    }

    @Test
    @DisplayName("two memberships are refused rather than resolved by picking one")
    void twoMembershipsAreRefused() {
        IdentityService identity = serviceHolding(
                membership(TENANT_A, TenantRole.OWNER),
                membership(TENANT_B, TenantRole.MEMBER));

        assertThatThrownBy(() -> identity.soleMembership(USER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("more than one tenant");
    }

    /** Only {@code findMembershipsForUser} is exercised; the rest is unreachable from this path. */
    private static final class StubTenantRepository implements TenantRepository {
        private final List<Membership> held;

        StubTenantRepository(List<Membership> held) {
            this.held = new ArrayList<>(held);
        }

        @Override public List<Membership> findMembershipsForUser(UUID userId) {
            return held.stream().filter(m -> m.userId().equals(userId)).toList();
        }

        @Override public List<Membership> findMembershipsForTenant(UUID tenantId) {
            return held.stream().filter(m -> m.tenantId().equals(tenantId)).toList();
        }

        @Override public Optional<Membership> findMembership(UUID userId, UUID tenantId) {
            return held.stream()
                    .filter(m -> m.userId().equals(userId) && m.tenantId().equals(tenantId))
                    .findFirst();
        }

        @Override public Tenant save(Tenant tenant) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<Tenant> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<Tenant> findBySlug(String slug) {
            throw new UnsupportedOperationException();
        }

        @Override public Membership saveMembership(Membership membership) {
            throw new UnsupportedOperationException();
        }

        @Override public void deleteMembership(UUID membershipId) {
            throw new UnsupportedOperationException();
        }

        @Override public long countOwners(UUID tenantId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubUserRepository implements UserRepository {
        @Override public User save(User user) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<User> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override public void touchLastSeen(UUID userId, java.time.Instant at) {
        }

        @Override public java.util.Map<UUID, java.time.Instant> lastSeenFor(
                java.util.Collection<UUID> userIds) {
            return java.util.Map.of();
        }

        @Override public Optional<User> findBySubject(String subject) {
            throw new UnsupportedOperationException();
        }
    }
}

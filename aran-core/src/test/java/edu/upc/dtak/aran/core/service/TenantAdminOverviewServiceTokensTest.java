package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.fake.InMemoryApiTokenRepository;
import edu.upc.dtak.aran.core.fake.InMemoryTenantRepository;
import edu.upc.dtak.aran.core.fake.InMemoryUserRepository;
import edu.upc.dtak.aran.core.fake.RecordingAuditPort;
import edu.upc.dtak.aran.core.domain.UsageEventRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit J7-F8: after offboarding Marta her tokens still showed live and counted as active, and an
 * expired token looked exactly like a working one. The admin list now carries a status the server
 * computed, and the overview's active count agrees with it.
 */
@DisplayName("Admin credentials — a token's status is what it can actually do")
class TenantAdminOverviewServiceTokensTest {

    private static final UUID TENANT = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");
    private static final TenantContext CTX = new TenantContext(TENANT.toString());

    private InMemoryTenantRepository tenants;
    private InMemoryUserRepository users;
    private InMemoryApiTokenRepository tokens;
    private TenantAdminOverviewService service;
    private AuthenticatedUser owner;
    private UUID marta;

    @BeforeEach
    void setUp() {
        tenants = new InMemoryTenantRepository();
        users = new InMemoryUserRepository();
        tokens = new InMemoryApiTokenRepository();
        service = new TenantAdminOverviewService(tenants, users,
                noUsage(), tokens, new RecordingAuditPort());

        owner = member("owner@upc.edu", TenantRole.OWNER);
        marta = member("marta@upc.edu", TenantRole.MEMBER).userId();
    }

    /** Consumption is not what this is about; the in-memory fake refuses aggregation outright. */
    private static UsageEventRepository noUsage() {
        UsageEventRepository usage = org.mockito.Mockito.mock(UsageEventRepository.class);
        org.mockito.Mockito.when(usage.usagePerUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(usage.totals(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UsageEventRepository.UsageTotals(0, 0, 0, 0, 0));
        return usage;
    }

    private AuthenticatedUser member(String email, TenantRole role) {
        User u = users.save(new User(UUID.randomUUID(), "sub-" + email, email, email,
                Instant.EPOCH));
        tenants.saveMembership(new Membership(UUID.randomUUID(), u.id(), TENANT, role,
                Instant.EPOCH));
        return new AuthenticatedUser(u.id(), u.subject(), TENANT, role);
    }

    private ApiToken token(UUID userId, String name, Instant expiresAt, Instant revokedAt) {
        ApiToken t = new ApiToken(UUID.randomUUID(), TENANT, userId, name, "hash-" + name,
                "aran_pat_" + name, ApiTokenScope.READ, expiresAt, null, revokedAt, Instant.EPOCH);
        tokens.put(t);
        return t;
    }

    private String statusOf(ApiToken t) {
        return service.tokens(owner).stream()
                .filter(s -> s.id().equals(t.id()))
                .findFirst().orElseThrow().status();
    }

    @Test
    @DisplayName("live, expired and revoked tokens each say so")
    void eachStatus() {
        Instant future = Instant.now().plus(Duration.ofDays(30));
        ApiToken live = token(marta, "live", future, null);
        ApiToken expired = token(marta, "expired", Instant.now().minus(Duration.ofDays(1)), null);
        ApiToken revoked = token(marta, "revoked", future, Instant.now());

        assertThat(statusOf(live)).isEqualTo("active");
        assertThat(statusOf(expired)).isEqualTo("expired");
        assertThat(statusOf(revoked)).isEqualTo("revoked");
    }

    @Test
    @DisplayName("a token whose holder left the tenant is owner_removed, not active")
    void orphanedTokenIsNotActive() {
        ApiToken orphan = token(marta, "notebook", null, null);
        tenants.deleteMembership(tenants.findMembership(marta, TENANT).orElseThrow().id());

        assertThat(statusOf(orphan)).isEqualTo("owner_removed");
    }

    @Test
    @DisplayName("the overview's active count agrees with the statuses")
    void activeCountAgrees() {
        token(marta, "live", null, null);
        token(marta, "expired", Instant.now().minus(Duration.ofDays(1)), null);
        token(owner.userId(), "mine", null, null);
        UUID gone = member("gone@upc.edu", TenantRole.MEMBER).userId();
        token(gone, "orphan", null, null);
        tenants.deleteMembership(tenants.findMembership(gone, TENANT).orElseThrow().id());

        assertThat(service.overview(owner, Duration.ofDays(30)).activeTokenCount()).isEqualTo(2);
        assertThat(service.tokens(owner)).filteredOn(s -> s.status().equals("active")).hasSize(2);
        assertThat(tokens.findByTenant(CTX)).hasSize(4);
    }
}

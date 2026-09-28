package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.UsageEvent;
import edu.upc.dtak.aran.core.domain.UsageEventRepository;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin dashboard's aggregates against a real PostgreSQL.
 *
 * <p>This exists because the aggregates are native queries: nothing in the type system says what
 * the driver hands back for {@code MAX(occurred_at)} over a {@code TIMESTAMPTZ}, and a wrong
 * assumption there is invisible until someone opens {@code /admin}. It once was — the mapping
 * cast to {@code java.sql.Timestamp} while the driver returns {@code java.time.Instant}, so the
 * whole page 500'd with a {@code ClassCastException}.
 */
@DisplayName("Usage aggregates (admin overview)")
class UsageAggregatePersistenceIT extends BaseIntegrationTest {

    @Autowired UsageEventRepository usage;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    @PersistenceContext EntityManager entityManager;

    /** usage_event has real FKs to tenant and app_user, so both rows must exist first. */
    private UUID newTenant() {
        UUID tenantId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        tenants.save(new Tenant(tenantId, "tenant-" + suffix, "Tenant", Instant.now()));
        return tenantId;
    }

    private UUID newUser() {
        UUID userId = UUID.randomUUID();
        String suffix = userId.toString().substring(0, 8);
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));
        return userId;
    }

    private static UsageEvent event(UUID tenantId, UUID userId, UsageKind kind, Instant at,
                                    Long durationMs, Long rows, Long bytes) {
        return new UsageEvent(UUID.randomUUID(), tenantId, userId, kind, at,
                durationMs, rows, bytes, Map.of());
    }

    @Test
    @Transactional
    @DisplayName("usagePerUser returns the last activity instant, aggregated per user")
    void usage_per_user_aggregates() {
        UUID tenantId = newTenant();
        UUID userId = newUser();
        // Postgres stores TIMESTAMPTZ at microsecond precision; comparing nanos would fail for
        // a reason that has nothing to do with what this test is about.
        Instant earlier = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        Instant latest = Instant.now().truncatedTo(ChronoUnit.MICROS);

        usage.save(event(tenantId, userId, UsageKind.UPLOAD, earlier, 50L, 10L, 100L));
        usage.save(event(tenantId, userId, UsageKind.EXPORT, latest, 150L, 5L, 200L));
        // Unattributed work groups under a null user rather than being dropped.
        usage.save(event(tenantId, null, UsageKind.MATERIALIZE, latest, 10L, 7L, 300L));
        entityManager.flush();

        var rows = usage.usagePerUser(tenantId, earlier.minusSeconds(1));

        assertThat(rows).hasSize(2);
        var mine = rows.stream().filter(r -> userId.equals(r.userId())).findFirst().orElseThrow();
        assertThat(mine.events()).isEqualTo(2);
        assertThat(mine.rows()).isEqualTo(15);
        assertThat(mine.bytesIn()).isEqualTo(100);
        assertThat(mine.bytesOut()).isEqualTo(200);
        assertThat(mine.p95DurationMs()).isEqualTo(145);
        assertThat(mine.lastActivityAt()).isEqualTo(latest);

        var unattributed = rows.stream().filter(r -> r.userId() == null).findFirst().orElseThrow();
        assertThat(unattributed.events()).isEqualTo(1);
        assertThat(unattributed.lastActivityAt()).isEqualTo(latest);
    }

    @Test
    @Transactional
    @DisplayName("totals split ingress, egress and stored bytes")
    void totals_split_by_direction() {
        UUID tenantId = newTenant();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        usage.save(event(tenantId, newUser(), UsageKind.UPLOAD, now, 50L, 10L, 100L));
        usage.save(event(tenantId, newUser(), UsageKind.JDBC, now, 20L, 5L, 200L));
        usage.save(event(tenantId, null, UsageKind.MATERIALIZE, now, 10L, 7L, 300L));
        entityManager.flush();

        var totals = usage.totals(tenantId, now.minusSeconds(1));

        assertThat(totals.events()).isEqualTo(3);
        assertThat(totals.rows()).isEqualTo(22);
        assertThat(totals.bytesIn()).isEqualTo(100);
        assertThat(totals.bytesOut()).isEqualTo(200);
        assertThat(totals.bytesStored()).isEqualTo(300);
    }
}

package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The non-by-id graph-version reads must scope themselves to the tenant.
 *
 * <p>{@code CrossTenantByIdIT} already pins {@code findById}. These three did not lean on
 * an explicit predicate at all — they relied on the shared-schema {@code tenantFilter}
 * being in force, and on any path where it is not they returned whichever tenant's row
 * sorted first across the entire table.
 *
 * <p>That is not hypothetical. Promotion is such a path: the browser suite caught one
 * tenant's promote reading another tenant's graph version, stopped only by the write-side
 * guard in {@code EntityMapper.toEntity}, which rejected the foreign version with a 400.
 * The read had already crossed the boundary by then — a path that reads without writing
 * back through that mapper would have had no protection at all.
 *
 * <p>Each test below deliberately makes the OTHER tenant's row the one a globally-sorted
 * query would return, so an unscoped implementation fails rather than passing by luck:
 * tenant B's version carries the higher version number, and B's is the one marked ACTIVE.
 *
 * <p><strong>Be honest about what this does and does not prove.</strong> These tests pass
 * both with and without the explicit predicates, because {@code TenantFilterAspect} is
 * applied in this context and covers the unscoped forms. They pin the CONTRACT — that
 * these reads are tenant-scoped — but they did not, and cannot here, reproduce the
 * original failure, which needed a path where the aspect's filter was not in force.
 * The browser suite is what caught that; `a3-parquet` is its regression test. Do not
 * mistake these passing for proof that the underlying path is safe.
 */
@DisplayName("Graph version reads are tenant-scoped without relying on the Hibernate filter")
class CrossTenantGraphVersionIT extends BaseIntegrationTest {

    @Autowired
    private GraphVersionPort graphVersionPort;

    @Autowired
    private TenantRepository tenantRepository;

    private record TwoTenants(TenantContext a, TenantContext b, UUID aId, UUID bId) {}

    private TwoTenants seed() {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var aId = UUID.randomUUID();
        var bId = UUID.randomUUID();
        tenantRepository.save(new Tenant(aId, "xt-a-" + suffix, "Cross Tenant A", Instant.now()));
        tenantRepository.save(new Tenant(bId, "xt-b-" + suffix, "Cross Tenant B", Instant.now()));
        return new TwoTenants(new TenantContext(aId.toString()), new TenantContext(bId.toString()), aId, bId);
    }

    @Test
    @Transactional
    @DisplayName("findLatest returns this tenant's newest version, not the table's")
    void find_latest_is_tenant_scoped() {
        var t = seed();

        // B's version number is HIGHER, so a query ordering across the whole table would
        // hand B's row back to A.
        graphVersionPort.save(t.a(), new GraphVersion(
                UUID.randomUUID(), t.aId(), 1, "DRAFT", Instant.now(), null, null, "a-v1"));
        graphVersionPort.save(t.b(), new GraphVersion(
                UUID.randomUUID(), t.bId(), 99, "DRAFT", Instant.now(), null, null, "b-v99"));

        var latestForA = graphVersionPort.findLatest(t.a());

        assertThat(latestForA).isPresent();
        assertThat(latestForA.get().tenantId())
                .as("findLatest must never return another tenant's version")
                .isEqualTo(t.aId());
        assertThat(latestForA.get().versionNumber()).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("findCurrentActive does not return another tenant's ACTIVE version")
    void find_current_active_is_tenant_scoped() {
        var t = seed();

        // Only B has an ACTIVE version. A must see none — not B's.
        graphVersionPort.save(t.a(), new GraphVersion(
                UUID.randomUUID(), t.aId(), 1, "DRAFT", Instant.now(), null, null, "a-draft"));
        graphVersionPort.save(t.b(), new GraphVersion(
                UUID.randomUUID(), t.bId(), 1, "ACTIVE", Instant.now(), null, Instant.now(), "b-active"));

        assertThat(graphVersionPort.findCurrentActive(t.a()))
                .as("A has no ACTIVE version; B's must not be borrowed")
                .isEmpty();

        var activeForB = graphVersionPort.findCurrentActive(t.b());
        assertThat(activeForB).isPresent();
        assertThat(activeForB.get().tenantId()).isEqualTo(t.bId());
    }

    @Test
    @Transactional
    @DisplayName("findAll lists only this tenant's versions")
    void find_all_is_tenant_scoped() {
        var t = seed();

        graphVersionPort.save(t.a(), new GraphVersion(
                UUID.randomUUID(), t.aId(), 1, "DRAFT", Instant.now(), null, null, "a-v1"));
        graphVersionPort.save(t.b(), new GraphVersion(
                UUID.randomUUID(), t.bId(), 1, "DRAFT", Instant.now(), null, null, "b-v1"));
        graphVersionPort.save(t.b(), new GraphVersion(
                UUID.randomUUID(), t.bId(), 2, "DRAFT", Instant.now(), null, null, "b-v2"));

        assertThat(graphVersionPort.findAll(t.a()))
                .as("A owns exactly one version")
                .hasSize(1)
                .allSatisfy(v -> assertThat(v.tenantId()).isEqualTo(t.aId()));

        assertThat(graphVersionPort.findAll(t.b()))
                .hasSize(2)
                .allSatisfy(v -> assertThat(v.tenantId()).isEqualTo(t.bId()));
    }
}

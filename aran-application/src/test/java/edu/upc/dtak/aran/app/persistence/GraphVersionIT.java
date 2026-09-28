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

@DisplayName("Graph Versioning database operations and tenant isolation")
class GraphVersionIT extends BaseIntegrationTest {

    @Autowired
    private GraphVersionPort graphVersionPort;

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    @Transactional
    @DisplayName("should save, find, and isolate tenant graph versions")
    void database_isolation_for_graph_versions() {
        var suffix = UUID.randomUUID().toString().substring(0, 8);

        var tenantAId = UUID.randomUUID();
        var tenantBId = UUID.randomUUID();

        tenantRepository.save(new Tenant(tenantAId, "tenant-ga-" + suffix, "Tenant GA", Instant.now()));
        tenantRepository.save(new Tenant(tenantBId, "tenant-gb-" + suffix, "Tenant GB", Instant.now()));

        var ctxA = new TenantContext(tenantAId.toString());
        var ctxB = new TenantContext(tenantBId.toString());

        // Create version 1 (ACTIVE) and 2 (DRAFT) under Tenant A
        var draft1A = new GraphVersion(UUID.randomUUID(), tenantAId, 1, "ACTIVE", Instant.now(), null, Instant.now(), "v1");
        var draft2A = new GraphVersion(UUID.randomUUID(), tenantAId, 2, "DRAFT", Instant.now(), null, null, "v2");
        graphVersionPort.save(ctxA, draft1A);
        graphVersionPort.save(ctxA, draft2A);

        // Create version 1 (DRAFT) under Tenant B
        var draft1B = new GraphVersion(UUID.randomUUID(), tenantBId, 1, "DRAFT", Instant.now(), null, null, "v1b");
        graphVersionPort.save(ctxB, draft1B);

        var activeA = graphVersionPort.findCurrentActive(ctxA);
        assertThat(activeA).isPresent();
        assertThat(activeA.get().id()).isEqualTo(draft1A.id());
        assertThat(activeA.get().status()).isEqualTo("ACTIVE");

        // Tenant B has only a DRAFT version, so it has no active one.
        var activeB = graphVersionPort.findCurrentActive(ctxB);
        assertThat(activeB).isEmpty();

        var latestA = graphVersionPort.findLatest(ctxA);
        assertThat(latestA).isPresent();
        assertThat(latestA.get().id()).isEqualTo(draft2A.id());
        assertThat(latestA.get().versionNumber()).isEqualTo(2);

        var latestB = graphVersionPort.findLatest(ctxB);
        assertThat(latestB).isPresent();
        assertThat(latestB.get().id()).isEqualTo(draft1B.id());
        assertThat(latestB.get().versionNumber()).isEqualTo(1);
    }
}

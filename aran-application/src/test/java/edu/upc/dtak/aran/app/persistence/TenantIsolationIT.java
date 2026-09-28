package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the Hibernate tenantFilter on ProjectEntity prevents
 * cross-tenant project leakage — no explicit WHERE tenant_id in calling code.
 */
@DisplayName("Tenant isolation via Hibernate filter")
class TenantIsolationIT extends BaseIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;

    @Test
    @Transactional
    @DisplayName("findAllVisibleToUser under tenant A never returns tenant B projects")
    void hibernate_filter_isolates_tenants() {
        var suffix = UUID.randomUUID().toString().substring(0, 8);

        var userId    = UUID.randomUUID();
        var tenantAId = UUID.randomUUID();
        var tenantBId = UUID.randomUUID();

        userRepository.save(new User(
                userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));
        tenantRepository.save(new Tenant(
                tenantAId, "tenant-a-" + suffix, "Tenant A", Instant.now()));
        tenantRepository.save(new Tenant(
                tenantBId, "tenant-b-" + suffix, "Tenant B", Instant.now()));

        var ctxA = new TenantContext(tenantAId.toString());
        var ctxB = new TenantContext(tenantBId.toString());

        var projectA = projectRepository.save(ctxA,
                new Project(UUID.randomUUID(), tenantAId, "Project-A-" + suffix,
                        "", userId, Instant.now()));
        projectRepository.addMember(ctxA,
                new ProjectMember(UUID.randomUUID(), projectA.id(), userId, Instant.now()));

        var projectB = projectRepository.save(ctxB,
                new Project(UUID.randomUUID(), tenantBId, "Project-B-" + suffix,
                        "", userId, Instant.now()));
        projectRepository.addMember(ctxB,
                new ProjectMember(UUID.randomUUID(), projectB.id(), userId, Instant.now()));

        // Queried under tenant A's context — no explicit tenant predicate here.
        var visible = projectRepository.findAllVisibleToUser(ctxA, userId);

        // If the Hibernate filter were absent, projectB would also appear (user is member of both).
        assertThat(visible)
                .as("findAllVisibleToUser under tenant A must not return tenant B projects")
                .hasSize(1)
                .first()
                .extracting(Project::id).isEqualTo(projectA.id());

        assertThat(visible)
                .noneMatch(p -> p.tenantId().equals(tenantBId));
    }
}

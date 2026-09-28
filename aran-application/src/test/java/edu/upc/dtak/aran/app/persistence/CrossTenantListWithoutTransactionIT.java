package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two tenant-scoped <em>list</em> queries, exercised the way the dashboard calls them:
 * with no transaction open (audit AUTH_IDENTITY-3).
 *
 * <p><b>Deliberately not {@code @Transactional}, and that is the entire point.</b>
 * {@code TenantFilterAspect} enables the Hibernate {@code tenantFilter} by unwrapping the shared
 * {@code EntityManager} to a {@code Session}; with {@code spring.jpa.open-in-view: false} and no
 * ambient transaction, that unwrap resolves against a throwaway {@code EntityManager} which is not
 * the one the following query runs on. {@code TenantIsolationIT} and {@code CrossTenantByIdIT} are
 * both {@code @Transactional}, so they can only ever demonstrate the case that works — a test
 * shaped like those would pass with the predicate missing.
 * {@code DashboardController.summary} is the production caller with no transaction.
 *
 * <p>The fixture makes two tenants share one parent id. Nothing in the schema forbids it:
 * {@code dataset.repository_id} references {@code data_repository(id)} and
 * {@code data_repository.project_id} references {@code project(id)}, but neither FK is composite
 * with {@code tenant_id}, so a row may name a parent that belongs to another tenant. That is the
 * shape a leak takes when the caller's own argument is the only thing scoping the query, and it is
 * why {@code findAllBy…} must carry the tenant in its predicate rather than trust an ambient
 * filter.
 */
@DisplayName("Cross-tenant list queries outside a transaction")
class CrossTenantListWithoutTransactionIT extends BaseIntegrationTest {

    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID tenantAId;
    private UUID tenantBId;
    private UUID userId;
    private TenantContext ctxA;
    private TenantContext ctxB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        userId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User",
                Instant.now()));

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(new Tenant(tenantAId, "tenant-a-" + suffix, "Tenant A", Instant.now()));
        tenants.save(new Tenant(tenantBId, "tenant-b-" + suffix, "Tenant B", Instant.now()));

        ctxA = new TenantContext(tenantAId.toString());
        ctxB = new TenantContext(tenantBId.toString());
    }

    /**
     * No test transaction means no rollback, so the fixture is removed by hand — child-first,
     * because {@code project.tenant_id} is a plain FK with no {@code ON DELETE CASCADE}, and
     * by {@code tenant_id} rather than by parent, because the whole point of the fixture is rows
     * whose parent belongs to the other tenant.
     */
    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dataset WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM data_repository WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM project WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Test
    @DisplayName("listing a repository's datasets returns only the caller tenant's rows")
    void dataset_list_is_scoped_to_the_calling_tenant() {
        Project projectA = projects.save(ctxA, new Project(
                UUID.randomUUID(), tenantAId, "Project-A", "", userId, Instant.now()));
        DataRepository repoA = repositories.save(ctxA, new FileSystemRepository(
                UUID.randomUUID(), projectA.id(), tenantAId, "repo-a", ""));

        Dataset mine = datasets.save(ctxA, new Dataset(
                UUID.randomUUID(), repoA.id(), "dataset-a", ""));
        // Tenant B's row, hanging off tenant A's repository id — see the class javadoc.
        Dataset foreign = datasets.save(ctxB, new Dataset(
                UUID.randomUUID(), repoA.id(), "dataset-b", ""));

        assertThat(datasets.findAllByRepository(ctxA, repoA.id()))
                .as("tenant A must not see tenant B's dataset under the same repository id")
                .extracting(Dataset::id)
                .containsExactly(mine.id());

        assertThat(datasets.findAllByRepository(ctxB, repoA.id()))
                .as("…and B still sees its own, so this is a boundary and not a blanket denial")
                .extracting(Dataset::id)
                .containsExactly(foreign.id());
    }

    @Test
    @DisplayName("listing a project's repositories returns only the caller tenant's rows")
    void repository_list_is_scoped_to_the_calling_tenant() {
        Project projectA = projects.save(ctxA, new Project(
                UUID.randomUUID(), tenantAId, "Project-A", "", userId, Instant.now()));

        DataRepository mine = repositories.save(ctxA, new FileSystemRepository(
                UUID.randomUUID(), projectA.id(), tenantAId, "repo-a", ""));
        DataRepository foreign = repositories.save(ctxB, new FileSystemRepository(
                UUID.randomUUID(), projectA.id(), tenantBId, "repo-b", ""));

        assertThat(repositories.findAllByProject(ctxA, projectA.id()))
                .as("tenant A must not see tenant B's repository under the same project id")
                .extracting(DataRepository::id)
                .containsExactly(mine.id());

        assertThat(repositories.findAllByProject(ctxB, projectA.id()))
                .extracting(DataRepository::id)
                .containsExactly(foreign.id());
    }
}

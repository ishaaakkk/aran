package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every tenant-scoped port's {@code findById}, asked for another tenant's id,
 * must answer "not found".
 *
 * <p>This is not a theoretical boundary. Hibernate's {@code @Filter} — which the
 * adapters were relying on — is applied to <em>queries</em> but never to a load
 * by primary key: Hibernate resolves those through the persistence context and
 * the entity loader before any filter is consulted. So a bare
 * {@code jpa.findById(id)} returned whatever row had that id, whoever owned it.
 * The adapters now name {@code tenant_id} in the predicate, and these tests are
 * what keep them honest.
 */
@DisplayName("Cross-tenant reads by id")
class CrossTenantByIdIT extends BaseIntegrationTest {

    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired GraphVersionPort graphVersions;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager entityManager;

    private UUID tenantAId;
    private UUID tenantBId;
    private TenantContext ctxA;
    private TenantContext ctxB;
    private UUID userId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        userId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(new Tenant(tenantAId, "tenant-a-" + suffix, "Tenant A", Instant.now()));
        tenants.save(new Tenant(tenantBId, "tenant-b-" + suffix, "Tenant B", Instant.now()));

        ctxA = new TenantContext(tenantAId.toString());
        ctxB = new TenantContext(tenantBId.toString());
    }

    @Test
    @Transactional
    @DisplayName("a project, repository, dataset and graph version owned by B are invisible to A")
    void by_id_reads_do_not_cross_tenants() {
        // Tenant B's world.
        Project projectB = projects.save(ctxB, new Project(
                UUID.randomUUID(), tenantBId, "Project-B", "", userId, Instant.now()));
        DataRepository repoB = repositories.save(ctxB, new FileSystemRepository(
                UUID.randomUUID(), projectB.id(), tenantBId, "repo-b", ""));
        Dataset datasetB = datasets.save(ctxB, new Dataset(
                UUID.randomUUID(), repoB.id(), "dataset-b", ""));
        GraphVersion versionB = graphVersions.save(ctxB, new GraphVersion(
                UUID.randomUUID(), tenantBId, 1, "DRAFT", Instant.now(), userId, null, null));

        // Flush now, from the test thread: the writes must hit the database before
        // we read them back under a different tenant.
        entityManager.flush();
        entityManager.clear();

        assertThat(projects.findById(ctxA, projectB.id()))
                .as("tenant A must not read tenant B's project")
                .isEmpty();
        assertThat(repositories.findById(ctxA, repoB.id()))
                .as("tenant A must not read tenant B's repository")
                .isEmpty();
        assertThat(datasets.findById(ctxA, datasetB.id()))
                .as("tenant A must not read tenant B's dataset")
                .isEmpty();
        assertThat(graphVersions.findById(ctxA, versionB.id()))
                .as("tenant A must not read tenant B's graph version")
                .isEmpty();

        // …and the owner still sees its own rows, so the predicate is a boundary,
        // not a blanket denial.
        assertThat(projects.findById(ctxB, projectB.id())).isPresent();
        assertThat(repositories.findById(ctxB, repoB.id())).isPresent();
        assertThat(datasets.findById(ctxB, datasetB.id())).isPresent();
        assertThat(graphVersions.findById(ctxB, versionB.id())).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("deleting another tenant's dataset or repository by id is a no-op")
    void deletes_do_not_cross_tenants() {
        Project projectB = projects.save(ctxB, new Project(
                UUID.randomUUID(), tenantBId, "Project-B", "", userId, Instant.now()));
        DataRepository repoB = repositories.save(ctxB, new FileSystemRepository(
                UUID.randomUUID(), projectB.id(), tenantBId, "repo-b", ""));
        Dataset datasetB = datasets.save(ctxB, new Dataset(
                UUID.randomUUID(), repoB.id(), "dataset-b", ""));

        entityManager.flush();
        entityManager.clear();

        datasets.delete(ctxA, datasetB.id());
        repositories.delete(ctxA, repoB.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(datasets.findById(ctxB, datasetB.id()))
                .as("tenant A must not be able to delete tenant B's dataset")
                .isPresent();
        assertThat(repositories.findById(ctxB, repoB.id()))
                .as("tenant A must not be able to delete tenant B's repository")
                .isPresent();
    }
}

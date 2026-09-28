package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.domain.Project;
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
 * V37 against a real PostgreSQL: {@code model_source.dataset_id} carries an
 * {@code ON DELETE CASCADE} FK, so deleting a dataset row can no longer strand its enrolment —
 * the orphan whose wrapper the next compile would push into the schema graph as a class over a
 * dataset that does not exist (audit MIGRATIONS-3, register T1.8).
 */
@DisplayName("model_source cascades with its dataset (V37)")
class ModelSourceCascadeIT extends BaseIntegrationTest {

    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired IntegrationModelRepository models;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("deleting the dataset row deletes its model_source enrolment at the database")
    void datasetDeleteCascadesEnrolment() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));
        UUID tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "tenant-" + suffix, "Tenant", Instant.now()));
        TenantContext ctx = new TenantContext(tenantId.toString());

        Project project = projects.save(ctx, new Project(
                UUID.randomUUID(), tenantId, "Project-" + suffix, "", userId, Instant.now()));
        DataRepository repo = repositories.save(ctx, new FileSystemRepository(
                UUID.randomUUID(), project.id(), tenantId, "repo-" + suffix, ""));

        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repo.id(), "orders", ""));

        IntegrationModel model = models.getOrCreate(ctx, project.id());
        models.addSource(ctx, new ModelSource(UUID.randomUUID(), tenantId, model.id(),
                datasetId, "http://www.essi.upc.edu/DTIM/NextiaDI/source/" + datasetId + "/orders",
                null, null, null, false));
        assertThat(models.load(ctx, project.id()).sources()).hasSize(1);

        datasets.delete(ctx, datasetId);
        // Hibernate's auto-flush is query-space-scoped: a model_source SELECT does not force the
        // pending dataset DELETE out, so flush + clear to observe the database's own cascade.
        entityManager.flush();
        entityManager.clear();

        assertThat(models.load(ctx, project.id()).sources())
                .as("the enrolment must die with the dataset row — no application code in between")
                .isEmpty();
    }
}

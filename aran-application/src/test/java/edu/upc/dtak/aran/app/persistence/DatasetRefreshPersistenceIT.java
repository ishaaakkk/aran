package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.source.RefreshPolicy;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V28 against a real PostgreSQL: the round trip, the tenant boundary, the check constraints, and
 * the claim.
 *
 * <p>The check constraints are the reason this needs a database rather than a mock. They encode the
 * same rule as {@code DatasetRefresh}'s compact constructor, deliberately duplicated so a migration
 * or a direct {@code UPDATE} cannot write a row the domain would refuse — and a duplicated rule
 * that nothing tests is a rule that drifts.
 */
@DisplayName("Dataset refresh persistence (V28)")
class DatasetRefreshPersistenceIT extends BaseIntegrationTest {

    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired DatasetRefreshRepository refreshes;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager entityManager;

    private UUID tenantId;
    private TenantContext ctx;
    private UUID repositoryId;
    private UUID projectId;
    private UUID userId;

    @BeforeEach
    @Transactional
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));

        tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "tenant-" + suffix, "Tenant", Instant.now()));
        ctx = new TenantContext(tenantId.toString());

        Project project = projects.save(ctx, new Project(
                UUID.randomUUID(), tenantId, "Project-" + suffix, "", userId, Instant.now()));
        projectId = project.id();
        DataRepository repo = repositories.save(ctx, new FileSystemRepository(
                UUID.randomUUID(), project.id(), tenantId, "repo-" + suffix, ""));
        repositoryId = repo.id();
    }

    private UUID newDataset(String name) {
        return datasets.save(ctx, new Dataset(UUID.randomUUID(), repositoryId, name, "")).id();
    }

    @Test
    @Transactional
    @DisplayName("a manual policy round-trips, and a scheduled one keeps its interval exactly")
    void round_trip() {
        UUID manualId = newDataset("orders");
        UUID scheduledId = newDataset("customers");
        Instant now = Instant.parse("2026-08-15T10:00:00Z");

        refreshes.save(ctx, DatasetRefresh.manual(manualId, tenantId, repositoryId, projectId, userId, "orders"));
        refreshes.save(ctx, DatasetRefresh.manual(scheduledId, tenantId, repositoryId, projectId, userId, "customers")
                .withPolicy(new RefreshPolicy.Every(Duration.ofHours(6)), now));
        entityManager.flush();
        entityManager.clear();

        DatasetRefresh manual = refreshes.findByDataset(ctx, manualId).orElseThrow();
        assertThat(manual.policy()).isInstanceOf(RefreshPolicy.Manual.class);
        assertThat(manual.nextRunAt()).isNull();
        assertThat(manual.sourceTable()).isEqualTo("orders");

        DatasetRefresh scheduled = refreshes.findByDataset(ctx, scheduledId).orElseThrow();
        assertThat(scheduled.policy()).isEqualTo(new RefreshPolicy.Every(Duration.ofHours(6)));
        assertThat(scheduled.nextRunAt()).isEqualTo(now.plus(Duration.ofHours(6)));
    }

    @Test
    @Transactional
    @DisplayName("a dataset with no upstream simply has no row — that is what Never means here")
    void absent_row_is_never() {
        assertThat(refreshes.findByDataset(ctx, newDataset("uploaded.csv"))).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("the database refuses an EVERY row with no interval, independently of the record")
    void check_constraint_holds_the_line() {
        // Written around the domain on purpose: this is the case the check constraint exists for,
        // and the only way to reach it is to bypass the record that would otherwise refuse.
        UUID datasetId = newDataset("orders");
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO dataset_refresh
                        (dataset_id, tenant_id, repository_id, project_id, created_by,
                         source_table, policy, interval_seconds, next_run_at)
                    VALUES (?1, ?2, ?3, ?4, ?5, 'orders', 'EVERY', NULL, now())
                    """)
                    .setParameter(1, datasetId)
                    .setParameter(2, tenantId)
                    .setParameter(3, repositoryId)
                    .setParameter(4, projectId)
                    .setParameter(5, userId)
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("ck_dataset_refresh_policy");
    }

    @Test
    @Transactional
    @DisplayName("the database refuses an interval below the five-minute floor")
    void interval_floor_is_enforced_in_the_database() {
        UUID datasetId = newDataset("orders");
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO dataset_refresh
                        (dataset_id, tenant_id, repository_id, project_id, created_by,
                         source_table, policy, interval_seconds, next_run_at)
                    VALUES (?1, ?2, ?3, ?4, ?5, 'orders', 'EVERY', 30, now())
                    """)
                    .setParameter(1, datasetId)
                    .setParameter(2, tenantId)
                    .setParameter(3, repositoryId)
                    .setParameter(4, projectId)
                    .setParameter(5, userId)
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("ck_dataset_refresh_interval");
    }

    @Test
    @Transactional
    @DisplayName("another tenant cannot read or delete this tenant's refresh policy")
    void tenant_boundary() {
        UUID datasetId = newDataset("orders");
        refreshes.save(ctx, DatasetRefresh.manual(datasetId, tenantId, repositoryId, projectId, userId, "orders"));
        entityManager.flush();
        entityManager.clear();

        TenantContext other = new TenantContext(UUID.randomUUID().toString());
        assertThat(refreshes.findByDataset(other, datasetId)).isEmpty();
        assertThat(refreshes.findAllByRepository(other, repositoryId)).isEmpty();

        refreshes.delete(other, datasetId);
        entityManager.flush();
        entityManager.clear();
        assertThat(refreshes.findByDataset(ctx, datasetId))
                .as("a delete under the wrong tenant must not take the row")
                .isPresent();
    }

    @Test
    @Transactional
    @DisplayName("saving a refresh whose tenant differs from the active context is rejected")
    void save_rejects_tenant_mismatch() {
        UUID datasetId = newDataset("orders");
        DatasetRefresh foreign = DatasetRefresh.manual(
                datasetId, UUID.randomUUID(), repositoryId, projectId, userId, "orders");

        assertThatThrownBy(() -> refreshes.save(ctx, foreign))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("claiming returns only due rows and advances them, so a second poll sees nothing")
    void claim_advances_and_does_not_repeat() {
        // Not @Transactional: claimDue runs in REQUIRES_NEW, so it must be able to commit. Under a
        // test transaction the advance would be rolled back and the second poll would pass for the
        // wrong reason.
        UUID dueId = newDataset("due");
        UUID laterId = newDataset("later");
        UUID manualId = newDataset("manual");
        Instant now = Instant.now();

        refreshes.save(ctx, DatasetRefresh.manual(dueId, tenantId, repositoryId, projectId, userId, "due")
                .withPolicy(new RefreshPolicy.Every(Duration.ofMinutes(30)), now.minus(Duration.ofHours(1))));
        refreshes.save(ctx, DatasetRefresh.manual(laterId, tenantId, repositoryId, projectId, userId, "later")
                .withPolicy(new RefreshPolicy.Every(Duration.ofHours(6)), now));
        refreshes.save(ctx, DatasetRefresh.manual(manualId, tenantId, repositoryId, projectId, userId, "manual"));

        List<DatasetRefresh> claimed = refreshes.claimDue(now, 10);

        assertThat(claimed).extracting(DatasetRefresh::datasetId)
                .as("only the overdue scheduled row; the future one and the manual one are not due")
                .containsExactly(dueId);
        assertThat(claimed.getFirst().nextRunAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));

        // The advance is durable, so the same row is not handed out twice. Without it a scheduler
        // would re-extract the same table on every tick until the refresh happened to finish.
        assertThat(refreshes.claimDue(now, 10)).isEmpty();

        refreshes.delete(ctx, dueId);
        refreshes.delete(ctx, laterId);
        refreshes.delete(ctx, manualId);
    }

    @Test
    @DisplayName("the claim limit bounds one poll, leaving the rest for the next tick")
    void claim_respects_the_limit() {
        Instant now = Instant.now();
        List<UUID> ids = List.of(newDataset("a"), newDataset("b"), newDataset("c"));
        for (UUID id : ids) {
            refreshes.save(ctx, DatasetRefresh.manual(id, tenantId, repositoryId, projectId, userId, "t")
                    .withPolicy(new RefreshPolicy.Every(Duration.ofHours(1)), now.minus(Duration.ofDays(1))));
        }

        assertThat(refreshes.claimDue(now, 2)).hasSize(2);
        assertThat(refreshes.claimDue(now, 2)).hasSize(1);

        ids.forEach(id -> refreshes.delete(ctx, id));
    }

    @Test
    @Transactional
    @DisplayName("deleting the dataset takes its refresh policy with it")
    void cascade_on_dataset_delete() {
        UUID datasetId = newDataset("orders");
        refreshes.save(ctx, DatasetRefresh.manual(datasetId, tenantId, repositoryId, projectId, userId, "orders"));
        entityManager.flush();

        datasets.delete(ctx, datasetId);
        entityManager.flush();
        entityManager.clear();

        assertThat(refreshes.findByDataset(ctx, datasetId))
                .as("an orphaned schedule would keep extracting a table for a dataset that is gone")
                .isEmpty();
    }
}

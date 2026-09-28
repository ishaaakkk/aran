package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKeyRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V33 against a real PostgreSQL: {@code declared_foreign_key} round-trips a {@link
 * DeclaredForeignKey} through Hibernate — including the jsonb-backed {@code fromCols}/{@code
 * toCols} ordinal pairing (a real ordering bug, not a hypothetical one: Task 2's own composite-FK
 * fix landed because {@code information_schema.constraint_column_usage} silently reorders — a
 * jsonb array round trip losing column order the same way would be exactly as dangerous, so it is
 * proven here against a real server rather than assumed from Hibernate's contract) — and {@code
 * replaceForRepository}'s delete-then-insert semantics.
 */
@DisplayName("Declared foreign key persistence (V33)")
class DeclaredForeignKeyPersistenceIT extends BaseIntegrationTest {

    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired DeclaredForeignKeyRepository declaredForeignKeys;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    private TenantContext ctx;
    private UUID repositoryId;
    private UUID employeesDatasetId;
    private UUID departmentsDatasetId;

    @BeforeEach
    @Transactional
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));

        UUID tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "tenant-" + suffix, "Tenant", Instant.now()));
        ctx = new TenantContext(tenantId.toString());

        Project project = projects.save(ctx, new Project(
                UUID.randomUUID(), tenantId, "Project-" + suffix, "", userId, Instant.now()));
        DataRepository repo = repositories.save(ctx, new FileSystemRepository(
                UUID.randomUUID(), project.id(), tenantId, "repo-" + suffix, ""));
        repositoryId = repo.id();

        employeesDatasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(employeesDatasetId, repositoryId, "employees", ""));
        departmentsDatasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(departmentsDatasetId, repositoryId, "departments", ""));
    }

    private DeclaredForeignKey fk(List<String> fromCols, List<String> toCols) {
        return new DeclaredForeignKey(UUID.randomUUID(), UUID.fromString(ctx.value()), repositoryId,
                employeesDatasetId, fromCols, departmentsDatasetId, toCols);
    }

    @Test
    @Transactional
    @DisplayName("a single-column FK round-trips every field, including repositoryId")
    void singleColumnFkRoundTrips() {
        DeclaredForeignKey saved = fk(List.of("dept_id"), List.of("id"));
        declaredForeignKeys.replaceForRepository(ctx, repositoryId, List.of(saved));

        List<DeclaredForeignKey> reloaded = declaredForeignKeys.findAllByTenant(ctx);

        assertThat(reloaded).singleElement().satisfies(fk -> {
            assertThat(fk.repositoryId()).isEqualTo(repositoryId);
            assertThat(fk.fromDatasetId()).isEqualTo(employeesDatasetId);
            assertThat(fk.fromCols()).containsExactly("dept_id");
            assertThat(fk.toDatasetId()).isEqualTo(departmentsDatasetId);
            assertThat(fk.toCols()).containsExactly("id");
        });
    }

    @Test
    @Transactional
    @DisplayName("a composite key's jsonb column arrays preserve ordinal order across a real Postgres round trip")
    void compositeKeyColumnOrderSurvivesJsonbRoundTrip() {
        // Column names chosen, like Task 2's own composite-FK test, so declared order (z, a) and
        // alphabetical order (a, z) disagree — a silent jsonb reorder would flip this and the
        // assertion below would catch it exactly the way Task 2's caught the information_schema one.
        DeclaredForeignKey saved = fk(List.of("pz", "pa"), List.of("z", "a"));
        declaredForeignKeys.replaceForRepository(ctx, repositoryId, List.of(saved));

        List<DeclaredForeignKey> reloaded = declaredForeignKeys.findAllByTenant(ctx);

        assertThat(reloaded).singleElement().satisfies(fk -> {
            assertThat(fk.fromCols()).containsExactly("pz", "pa");
            assertThat(fk.toCols()).containsExactly("z", "a");
        });
    }

    @Test
    @Transactional
    @DisplayName("replaceForRepository deletes the previous set rather than accumulating rows")
    void replaceForRepositoryDeletesThePreviousSet() {
        declaredForeignKeys.replaceForRepository(ctx, repositoryId,
                List.of(fk(List.of("old_col"), List.of("id"))));

        declaredForeignKeys.replaceForRepository(ctx, repositoryId,
                List.of(fk(List.of("dept_id"), List.of("id"))));

        List<DeclaredForeignKey> reloaded = declaredForeignKeys.findAllByTenant(ctx);
        assertThat(reloaded).singleElement()
                .extracting(fk -> fk.fromCols().get(0)).isEqualTo("dept_id");
    }

    @Test
    @Transactional
    @DisplayName("replaceForRepository with an empty list clears every previously declared FK for that repository")
    void replaceWithEmptyListClearsEverything() {
        declaredForeignKeys.replaceForRepository(ctx, repositoryId,
                List.of(fk(List.of("dept_id"), List.of("id"))));

        declaredForeignKeys.replaceForRepository(ctx, repositoryId, List.of());

        assertThat(declaredForeignKeys.findAllByTenant(ctx)).isEmpty();
    }
}

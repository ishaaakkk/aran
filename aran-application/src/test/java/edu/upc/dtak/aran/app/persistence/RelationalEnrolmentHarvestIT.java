package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKeyRepository;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.RelationalCatalogHarvestTrigger;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.Harvest.ColKey;
import edu.upc.dtak.aran.core.source.Harvest.ForeignKey;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.operators.bootstrapping.HarvestPersistenceService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

/**
 * V44 on the path a user actually walks: enrolling a second table of a relational repository
 * through {@code DatasetService.registerDataset} — the JSON registration path, whose inline
 * best-effort catalog harvest re-writes the FIRST table's HARVESTED profiles as a side effect.
 *
 * <p>Live on 2026-08-27 that second call answered HTTP 500 ({@code DataIntegrityViolationException}
 * on {@code uq_column_profile_dataset_name}) and rolled the dataset row back with it, so the table
 * could not be enrolled at all — and because {@code HarvestPersistenceService.persistStats} ran
 * before {@code persistDeclaredForeignKeys}, {@code declared_foreign_key} stayed permanently empty
 * for any repository with more than one table. That is the whole DECLARED trust tier, and with it
 * phase 4B's join elimination, unreachable on the natural path.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> {@code DatasetService} is {@code
 * @Transactional} at class level, and the violation lands at ITS commit — a test that wrapped the
 * call in its own never-committing transaction would watch the flush it is here to provoke never
 * happen. Every row these tests write therefore survives them, so each test mints its own tenant,
 * project and repository rather than sharing the dev seed's.
 *
 * <p>The JDBC half of the harvest is replaced by {@link FixedHarvestConfig}: the defect is in what
 * {@code HarvestPersistenceService} does with a {@link Harvest}, not in reading one — {@code
 * RelationalCatalogHarvestServiceTest} already proves the catalog SQL against a live PostgreSQL.
 * Everything below the trigger (the real persistence service, the real dataset repository, the real
 * schema) is production wiring.
 */
@DisplayName("Relational enrolment → inline catalog harvest (V44)")
class RelationalEnrolmentHarvestIT extends BaseIntegrationTest {

    /**
     * The real {@link HarvestPersistenceService} behind a trigger that returns a harvest the test
     * dictates instead of opening a JDBC connection. {@code @Primary} so {@code DatasetService}'s
     * {@code Optional<RelationalCatalogHarvestTrigger>} injects this one rather than
     * {@code RelationalCatalogHarvestTriggerImpl}.
     */
    @TestConfiguration
    static class FixedHarvestConfig {
        static final AtomicReference<Harvest> NEXT_HARVEST = new AtomicReference<>(Harvest.EMPTY);

        @Bean
        @Primary
        RelationalCatalogHarvestTrigger fixedHarvestTrigger(HarvestPersistenceService persistence) {
            return (tenant, repository) -> persistence.persist(tenant, repository, NEXT_HARVEST.get());
        }
    }

    @Autowired DatasetService datasetService;
    /** The same bean {@code DatasetService.harvestCatalog} calls — see {@code harvestOnTheJobPath}. */
    @Autowired RelationalCatalogHarvestTrigger harvestTrigger;
    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired DeclaredForeignKeyRepository declaredForeignKeys;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    private AuthenticatedUser caller;
    private TenantContext ctx;
    private Project project;
    private RelationalDatabaseRepository repository;

    /** venue(id) ← concert(venue_id): the two-table shape the DECLARED tier needs at minimum. */
    private static final Harvest BOTH_TABLES = new Harvest(
            Map.of("venue", 120L, "concert", 900L),
            Map.of(new ColKey("venue", "id"), 120L,
                    new ColKey("concert", "venue_id"), 118L),
            List.of(new ForeignKey("concert", List.of("venue_id"), "venue", List.of("id"))));

    /**
     * A fresh tenant per test, not the dev seed's. These tests commit (see the class javadoc), and
     * the seeded tenant's project budget is already spent by the rest of the suite — a project
     * created here would make {@code DatasetBootstrapIntegrationIT} fail on {@code maxProjects}
     * instead, which is a worse bug report than the one this class is about.
     */
    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));
        tenants.save(new Tenant(tenantId, "tenant-" + suffix, "Tenant", Instant.now()));

        caller = new AuthenticatedUser(userId, "sub-" + suffix, tenantId, TenantRole.OWNER);
        ctx = new TenantContext(tenantId.toString());
        // Through the port rather than ProjectService: a tenant minted here has no license row, and
        // createProject gates on one. The membership row createProject would also write is added
        // explicitly below — every downstream access check reads it, none of them reads the
        // license.
        project = projects.save(ctx, new Project(
                UUID.randomUUID(), tenantId, "Harvest-" + suffix, "", userId, Instant.now()));
        projects.addMember(ctx, new ProjectMember(
                UUID.randomUUID(), project.id(), userId, Instant.now()));
        repository = (RelationalDatabaseRepository) repositories.save(ctx, new RelationalDatabaseRepository(
                UUID.randomUUID(), project.id(), tenantId, "src-" + suffix, "",
                "POSTGRESQL", "localhost", 5432, "srcdb", "u", "pw", "public", true));
        FixedHarvestConfig.NEXT_HARVEST.set(Harvest.EMPTY);
    }

    private Dataset enrol(String tableName) {
        return datasetService.registerDataset(caller, project.id(), repository.id(),
                new Dataset(UUID.randomUUID(), repository.id(), tableName, "federated " + tableName));
    }

    @Test
    @DisplayName("a second table enrols cleanly even though the harvest re-writes the first one's profiles (V44)")
    void secondTableEnrolsWithoutADuplicateKeyFailure() {
        FixedHarvestConfig.NEXT_HARVEST.set(BOTH_TABLES);

        Dataset venue = enrol("venue");
        // The harvest ran inline for "venue" already, so this second enrolment re-writes its
        // HARVESTED rows — the exact collision that answered 500 live.
        AtomicReference<Dataset> concert = new AtomicReference<>();
        assertThatCode(() -> concert.set(enrol("concert"))).doesNotThrowAnyException();

        assertThat(datasets.findById(ctx, concert.get().id()))
                .as("the dataset row must survive: live it was rolled back with the violation")
                .isPresent();
        assertThat(datasets.findById(ctx, venue.id()).orElseThrow().columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject, ColumnProfile::cardinality)
                .containsExactly(tuple("id", "HARVESTED", 120L));
        assertThat(datasets.findById(ctx, concert.get().id()).orElseThrow().columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject, ColumnProfile::cardinality)
                .containsExactly(tuple("venue_id", "HARVESTED", 118L));
    }

    @Test
    @DisplayName("declared foreign keys land on the natural path once both endpoint tables are enrolled")
    void declaredForeignKeysLandOnTheNaturalPath() {
        FixedHarvestConfig.NEXT_HARVEST.set(BOTH_TABLES);

        Dataset venue = enrol("venue");
        Dataset concert = enrol("concert");

        List<DeclaredForeignKey> fks = declaredForeignKeys.findAllByTenant(ctx).stream()
                .filter(fk -> fk.repositoryId().equals(repository.id()))
                .toList();
        assertThat(fks).as("live, persistStats failed first and this write never ran at all")
                .singleElement().satisfies(fk -> {
                    assertThat(fk.fromDatasetId()).isEqualTo(concert.id());
                    assertThat(fk.fromCols()).containsExactly("venue_id");
                    assertThat(fk.toDatasetId()).isEqualTo(venue.id());
                    assertThat(fk.toCols()).containsExactly("id");
                });
    }

    @Test
    @DisplayName("freyja's SNAPSHOT profile and the harvest's HARVESTED estimate survive each other on one dataset")
    void snapshotProfilesSurviveALaterHarvest() {
        Dataset venue = enrol("venue"); // Harvest.EMPTY — nothing harvested yet

        // Stand in for bootstrap's auto-profiling: exact freyja evidence under the physical name,
        // which is exactly the name the harvest is about to write its own estimate under.
        datasets.save(ctx, new Dataset(venue.id(), repository.id(), "venue", venue.description(),
                null, List.of(new ColumnProfile(
                        UUID.randomUUID(), venue.id(), "id", 0, "INTEGER",
                        120L, null, 1.0, 0.0,
                        0.0, 0L, 0L, 0.0, 0.0,
                        0.0, 0.0, 0.0, 0.0,
                        0, 0, 0.0,
                        0L, 0, 0, 0.0, 0.0,
                        null, null, false, false, List.of(), List.of()))));

        FixedHarvestConfig.NEXT_HARVEST.set(BOTH_TABLES);
        assertThatCode(() -> enrol("concert")).doesNotThrowAnyException();

        assertThat(datasets.findById(ctx, venue.id()).orElseThrow().columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject)
                .containsExactlyInAnyOrder(
                        tuple("id", "SNAPSHOT"),
                        tuple("id", "HARVESTED"));
    }

    // ── the JOB path: a stats failure must not cost the declared-FK write ─────────────────────

    /**
     * The V44 fix's real half, on the path where it is real.
     *
     * <p>{@code harvestTrigger.harvest} is invoked directly here, with no transaction around it —
     * which is precisely the job path's shape. {@code registerDatasetFromRepositoryTable} and
     * {@code refreshDataset} both call {@code harvestCatalog} from inside a {@code jobService}
     * lambda that runs on a worker thread after the submitting transaction has committed, so no
     * transaction is bound: each {@code datasets.save} opens and commits its own, and one that
     * fails rolls back alone.
     *
     * <p>It does <b>not</b> work that way on the request path, and that is not something this fix
     * can change: inside {@code DatasetService}'s class-level transaction a database error dooms
     * the whole transaction, so the declared-FK write is issued but rolled back with everything
     * else. The catch in {@code persistStats} buys attribution there (a WARN naming the half and
     * the dataset), not a rescued write. This test therefore proves the property where it holds,
     * and says plainly where it does not.
     *
     * <p>The failure is provoked with a column name longer than {@code column_profile.column_name}'s
     * {@code VARCHAR(255)} rather than a mock — a source column Aran's schema cannot store is a
     * real thing a harvest can meet, and forcing it through the actual database is what makes
     * "the other writes survived" mean anything.
     */
    @Test
    @DisplayName("on the job path, one dataset's stats failure costs neither the other dataset's stats nor the FK rows")
    void harvestOnTheJobPathKeepsForeignKeysWhenOneDatasetsStatsFail() {
        Dataset venue = enrol("venue");
        Dataset concert = enrol("concert");

        String tooLongForTheColumn = "c".repeat(300);
        Harvest poisoned = new Harvest(
                Map.of("venue", 120L, "concert", 900L),
                Map.of(new ColKey("venue", tooLongForTheColumn), 120L,   // venue's save will fail
                        new ColKey("concert", "venue_id"), 118L),        // concert's must not
                List.of(new ForeignKey("concert", List.of("venue_id"), "venue", List.of("id"))));

        // Both enrolments above ran the trigger with Harvest.EMPTY, which persists nothing — so
        // everything asserted below is this one harvest's doing.
        FixedHarvestConfig.NEXT_HARVEST.set(poisoned);
        assertThatCode(() -> harvestTrigger.harvest(ctx, repository))
                .as("a harvest is best-effort — a failing piece must not surface to its caller")
                .doesNotThrowAnyException();

        assertThat(datasets.findById(ctx, venue.id()).orElseThrow().columns())
                .as("venue's stats write is the one that failed, so it keeps whatever it had — nothing")
                .isEmpty();
        assertThat(datasets.findById(ctx, concert.id()).orElseThrow().columns())
                .as("the loop continued past the failure rather than abandoning the repository")
                .extracting(ColumnProfile::columnName, ColumnProfile::subject, ColumnProfile::cardinality)
                .containsExactly(tuple("venue_id", "HARVESTED", 118L));
        assertThat(declaredForeignKeys.findAllByTenant(ctx).stream()
                .filter(fk -> fk.repositoryId().equals(repository.id())).toList())
                .as("the DECLARED tier is the whole point: it must outlive a stats collision")
                .singleElement().satisfies(fk -> {
                    assertThat(fk.fromDatasetId()).isEqualTo(concert.id());
                    assertThat(fk.toDatasetId()).isEqualTo(venue.id());
                });
    }
}

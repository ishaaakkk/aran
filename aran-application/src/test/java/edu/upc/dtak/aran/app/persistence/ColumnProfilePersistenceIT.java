package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
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
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * V31 against a real PostgreSQL: {@code column_profile.subject}/{@code class_iri} round-trip
 * through Hibernate, and the migration's own {@code DEFAULT 'SNAPSHOT'} clause — not just
 * {@code ColumnProfile}'s compact constructor — backfills a row the application layer never
 * touches (a raw insert, standing in for a row written before this migration existed).
 */
@DisplayName("Column profile subject persistence (V31)")
class ColumnProfilePersistenceIT extends BaseIntegrationTest {

    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager entityManager;

    private TenantContext ctx;
    private UUID repositoryId;

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
    }

    private static ColumnProfile snapshotProfile(String columnName) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 0, "VARCHAR",
                10L, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    private static ColumnProfile wrapperOutputProfile(String columnName, String classIri) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 1, "VARCHAR",
                10L, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                "WRAPPER_OUTPUT", classIri);
    }

    @Test
    @Transactional
    @DisplayName("SNAPSHOT and WRAPPER_OUTPUT profiles keep their subject and class IRI across a save/reload")
    void subjectAndClassIriRoundTrip() {
        UUID datasetId = UUID.randomUUID();
        List<ColumnProfile> columns = List.of(
                snapshotProfile("amount"),
                wrapperOutputProfile("total", "http://ex/Paper"));

        datasets.save(ctx, new Dataset(datasetId, repositoryId, "orders", "", null, columns));
        entityManager.flush();
        entityManager.clear();

        Dataset reloaded = datasets.findById(ctx, datasetId).orElseThrow();

        assertThat(reloaded.columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject, ColumnProfile::classIri)
                .containsExactlyInAnyOrder(
                        tuple("amount", "SNAPSHOT", null),
                        tuple("total", "WRAPPER_OUTPUT", "http://ex/Paper"));
    }

    @Test
    @Transactional
    @DisplayName("V45: the value-hash sample the exact join score reads survives a save/reload, in order")
    void valueHashSampleRoundTrips() {
        UUID datasetId = UUID.randomUUID();
        List<String> sample = List.of("00411460f7c92d21", "006f52e9102a8d3b", "00ac8ed3b4327bdd");
        ColumnProfile sampled = new ColumnProfile(
                UUID.randomUUID(), datasetId, "id", 0, "VARCHAR",
                1000L, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of("1", "10"), List.of(),
                ColumnProfile.SUBJECT_SNAPSHOT, null,
                sample);

        datasets.save(ctx, new Dataset(datasetId, repositoryId, "customers", "", null,
                List.of(sampled, snapshotProfile("name"))));
        entityManager.flush();
        entityManager.clear();

        Dataset reloaded = datasets.findById(ctx, datasetId).orElseThrow();
        assertThat(reloaded.columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::valueHashSample)
                .containsExactlyInAnyOrder(
                        tuple("id", sample),
                        tuple("name", List.of()));
    }

    @Test
    @Transactional
    @DisplayName("a Phase 4B HARVESTED profile round-trips through the same subject VARCHAR(24) column, real Postgres proof")
    void harvestedSubjectRoundTrips() {
        // Not a hand-rolled ColumnProfile: exercises the real Harvest -> ColumnProfile mapper
        // (Phase 4B Task 3) against an actual VARCHAR(24) column, proving the fit is real rather
        // than a character count performed by inspection.
        UUID datasetId = UUID.randomUUID();
        Harvest harvest = new Harvest(
                Map.of("employees", 900L),
                Map.of(new Harvest.ColKey("employees", "id"), 900L),
                List.of());
        List<ColumnProfile> harvested =
                HarvestedColumnProfiles.forTable(harvest, datasetId, "employees");
        assertThat(harvested).as("fixture sanity").isNotEmpty();

        datasets.save(ctx, new Dataset(datasetId, repositoryId, "employees", "", null, harvested));
        entityManager.flush();
        entityManager.clear();

        Dataset reloaded = datasets.findById(ctx, datasetId).orElseThrow();

        assertThat(reloaded.columns()).singleElement().satisfies(cp -> {
            assertThat(cp.subject()).isEqualTo("HARVESTED");
            assertThat(cp.classIri()).isNull();
            assertThat(cp.columnName()).isEqualTo("id");
            assertThat(cp.cardinality()).isEqualTo(900L);
        });
    }

    @Test
    @Transactional
    @DisplayName("SNAPSHOT, WRAPPER_OUTPUT and HARVESTED generations coexist for one physical column name (V36)")
    void sameColumnNameAcrossSubjectsPersists() {
        // The main relational-import flow produces exactly this shape: bootstrap profiles the
        // physical columns as SNAPSHOT, the same job's harvest merges HARVESTED rows under the
        // SAME physical names, and a user wrapper that keeps a physical column name adds a
        // WRAPPER_OUTPUT row. V10's UNIQUE (dataset_id, column_name) forbade it — the collision
        // showed up live as V40 (re-profile never persists twice) and V44 (second
        // relational enrolment fails its harvest).
        UUID datasetId = UUID.randomUUID();
        Harvest harvest = new Harvest(
                Map.of("orders", 500L),
                Map.of(new Harvest.ColKey("orders", "amount"), 480L),
                List.of());
        List<ColumnProfile> mixed = new java.util.ArrayList<>();
        mixed.add(snapshotProfile("amount"));
        mixed.add(wrapperOutputProfile("amount", "http://ex/Order"));
        mixed.addAll(HarvestedColumnProfiles.forTable(harvest, datasetId, "orders"));

        datasets.save(ctx, new Dataset(datasetId, repositoryId, "orders", "", null, mixed));
        entityManager.flush();
        entityManager.clear();

        Dataset reloaded = datasets.findById(ctx, datasetId).orElseThrow();
        assertThat(reloaded.columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject)
                .containsExactlyInAnyOrder(
                        tuple("amount", "SNAPSHOT"),
                        tuple("amount", "WRAPPER_OUTPUT"),
                        tuple("amount", "HARVESTED"));
    }

    @Test
    @Transactional
    @DisplayName("re-saving the same generation replaces it — the V40 repro: clear+addAll must not collide on the identity")
    void reprofilingTheSameGenerationReplacesIt() {
        // V40's live failure: JpaDatasetRepository.save clears and rebuilds the column
        // collection, and Hibernate orders the re-INSERTs before the orphan DELETEs — so writing
        // a fresh row with the SAME (dataset, subject, class_iri, column_name) violated the
        // unique identity even though the old row was on its way out. V36 fixed the cross-subject
        // half; this pins the same-generation half (the flush between clear and re-add).
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "reprofiled", "", null,
                List.of(snapshotProfile("amount"))));
        entityManager.flush();

        // A re-profile: new row ids, same identity.
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "reprofiled", "", null,
                List.of(snapshotProfile("amount"))));
        entityManager.flush();
        entityManager.clear();

        Dataset reloaded = datasets.findById(ctx, datasetId).orElseThrow();
        assertThat(reloaded.columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject)
                .containsExactly(tuple("amount", "SNAPSHOT"));
    }

    @Test
    @DisplayName("re-profiling replaces the generation OUTSIDE a transaction too — the live operator path has none")
    void reprofilingOutsideATransactionReplaces() {
        // Deliberately NOT @Transactional: ProfilingOperator's persist runs with no outer
        // transaction (the operator invoke path opens none), so JpaDatasetRepository.save's
        // find / flush / save each get their own transaction over a DETACHED entity. A
        // transactional test of this path can only ever exercise the case that works — the same
        // trap the audit called out for TenantIsolationIT.
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "live-shape", "", null,
                List.of(snapshotProfile("amount"))));
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "live-shape", "", null,
                List.of(snapshotProfile("amount"))));

        Dataset reloaded = datasets.findById(ctx, datasetId).orElseThrow();
        assertThat(reloaded.columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject)
                .containsExactly(tuple("amount", "SNAPSHOT"));
    }

    @Test
    @Transactional
    @DisplayName("two rows of the SAME generation for one column still collide — NULLS NOT DISTINCT keeps the identity real")
    void duplicateWithinOneGenerationStillRejected() {
        // class_iri is NULL for SNAPSHOT rows. A plain unique constraint treats NULLs as
        // distinct, which would let two SNAPSHOT rows for one column both insert and make the
        // identity a fiction; NULLS NOT DISTINCT (or V10's old constraint, pre-V36) must refuse
        // the second. Written around the domain on purpose, like the DEFAULT test below.
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "dups", ""));
        entityManager.flush();

        String insert = """
                INSERT INTO column_profile (id, dataset_id, tenant_id, column_name, column_index, source_type)
                VALUES (?1, ?2, ?3, 'amount', 0, 'VARCHAR')
                """;
        entityManager.createNativeQuery(insert)
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, datasetId)
                .setParameter(3, UUID.fromString(ctx.value()))
                .executeUpdate();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                entityManager.createNativeQuery(insert)
                        .setParameter(1, UUID.randomUUID())
                        .setParameter(2, datasetId)
                        .setParameter(3, UUID.fromString(ctx.value()))
                        .executeUpdate())
                .as("a second SNAPSHOT row for the same (dataset, column) must violate the unique index")
                .hasMessageContaining("uq_column_profile");
    }

    @Test
    @Transactional
    @DisplayName("the database itself defaults subject to SNAPSHOT for a row written with no subject at all")
    void databaseDefaultsSubjectForARowWrittenWithoutOne() {
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "customers", ""));
        entityManager.flush();

        // Written around the domain on purpose, like DatasetRefreshPersistenceIT's check-constraint
        // tests: the only way to reach "a row with no subject" is to bypass the entity that would
        // otherwise always supply one, and prove the migration's own DEFAULT carries it instead.
        UUID profileId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO column_profile (id, dataset_id, tenant_id, column_name, column_index, source_type)
                VALUES (?1, ?2, ?3, 'legacy_col', 0, 'VARCHAR')
                """)
                .setParameter(1, profileId)
                .setParameter(2, datasetId)
                .setParameter(3, UUID.fromString(ctx.value()))
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        Object[] row = (Object[]) entityManager.createNativeQuery(
                "SELECT subject, class_iri FROM column_profile WHERE id = ?1")
                .setParameter(1, profileId)
                .getSingleResult();

        assertThat(row[0])
                .as("V31's DEFAULT 'SNAPSHOT' clause, not application code, must have backfilled this")
                .isEqualTo("SNAPSHOT");
        assertThat(row[1]).isNull();
    }
}

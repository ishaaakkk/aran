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
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * V40 + V44 against a real PostgreSQL: what {@code column_profile}'s uniqueness key must allow,
 * what it must still forbid, and that re-writing one generation of profiles is an ordinary rewrite
 * rather than a duplicate-key failure.
 *
 * <p>Both defects share one root: V10 keyed the table {@code (dataset_id, column_name)}, back when
 * a dataset had exactly one generation of profiles, and V31 added {@code subject}/{@code class_iri}
 * without widening it. Live consequences (both watched 2026-08-27): a re-profile of any dataset
 * failed with a duplicate key and left the stale numbers in place (V40), and enrolling a second
 * table in a relational repository answered HTTP 500 while {@code declared_foreign_key} stayed
 * permanently empty (V44). {@code HarvestPersistenceService.persistStats} and {@code
 * ProfilingOperator}'s persist tail are both written to merge subjects; only the schema forbade it.
 *
 * <p>Everything here needs a real server: the {@code NULLS NOT DISTINCT} clause V36 depends on has
 * no H2/in-memory equivalent, and Hibernate's INSERT-before-orphan-DELETE ordering — V40's actual
 * mechanism — is invisible without a database to reject the collision.
 */
@DisplayName("Column profile uniqueness key (V36 — V40/V44)")
class ColumnProfileSubjectKeyIT extends BaseIntegrationTest {

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

    /**
     * A fresh id every time, exactly as {@code ProfilingOperator} mints one per parsed freyja
     * column and {@code HarvestedColumnProfiles} does per harvested column: it is what makes a
     * re-write an INSERT of a new row rather than an UPDATE of the old one, and therefore what
     * makes the collision reachable at all.
     */
    private static ColumnProfile profile(String columnName, long cardinality,
                                         String subject, String classIri) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 0, "VARCHAR",
                cardinality, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                subject, classIri);
    }

    private static ColumnProfile snapshot(String columnName, long cardinality) {
        return profile(columnName, cardinality, "SNAPSHOT", null);
    }

    private static ColumnProfile harvested(String columnName, long cardinality) {
        return profile(columnName, cardinality, HarvestedColumnProfiles.SUBJECT, null);
    }

    // ── V40: a second write of the same generation must persist ────────────────────────────────

    @Test
    @Transactional
    @DisplayName("re-profiling the same dataset persists the NEW numbers, not the first pass's (V40)")
    void reprofilingPersistsTheNewNumbers() {
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "concert", "",
                null, List.of(snapshot("venue_id", 10L))));
        entityManager.flush();
        entityManager.clear();

        // Second profiling pass over the same dataset: same subject, same column name, new numbers
        // and new row ids — the exact shape every compile after the first produced live.
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "concert", "",
                null, List.of(snapshot("venue_id", 4242L))));
        entityManager.flush();
        entityManager.clear();

        Dataset reloaded = datasets.findById(ctx, datasetId).orElseThrow();
        assertThat(reloaded.columns()).singleElement().satisfies(cp -> {
            assertThat(cp.columnName()).isEqualTo("venue_id");
            assertThat(cp.cardinality())
                    .as("the second pass's numbers must win — V40 left the first pass's in place")
                    .isEqualTo(4242L);
        });
    }

    @Test
    @Transactional
    @DisplayName("a re-write that drops a column leaves no orphan row behind")
    void reprofilingWithFewerColumnsDropsTheMissingOne() {
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "concert", "",
                null, List.of(snapshot("venue_id", 10L), snapshot("dropped_col", 3L))));
        entityManager.flush();
        entityManager.clear();

        datasets.save(ctx, new Dataset(datasetId, repositoryId, "concert", "",
                null, List.of(snapshot("venue_id", 11L))));
        entityManager.flush();
        entityManager.clear();

        assertThat(datasets.findById(ctx, datasetId).orElseThrow().columns())
                .extracting(ColumnProfile::columnName)
                .containsExactly("venue_id");
    }

    // ── V44: what the widened key must ALLOW ───────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("HARVESTED and SNAPSHOT profiles for the same column coexist on one dataset (V44)")
    void harvestedAndSnapshotCoexistForOneColumn() {
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "concert", "", null,
                List.of(snapshot("venue_id", 900L), harvested("venue_id", 880L))));
        entityManager.flush();
        entityManager.clear();

        assertThat(datasets.findById(ctx, datasetId).orElseThrow().columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject, ColumnProfile::cardinality)
                .containsExactlyInAnyOrder(
                        tuple("venue_id", "SNAPSHOT", 900L),
                        tuple("venue_id", "HARVESTED", 880L));
    }

    @Test
    @Transactional
    @DisplayName("two classes' WRAPPER_OUTPUT profiles for the same column coexist — class_iri is part of the key")
    void twoClassesWrapperOutputProfilesCoexist() {
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "concert", "", null, List.of(
                profile("total", 5L, "WRAPPER_OUTPUT", "http://ex/Paper"),
                profile("total", 7L, "WRAPPER_OUTPUT", "http://ex/Talk"))));
        entityManager.flush();
        entityManager.clear();

        assertThat(datasets.findById(ctx, datasetId).orElseThrow().columns())
                .extracting(ColumnProfile::classIri, ColumnProfile::cardinality)
                .containsExactlyInAnyOrder(
                        tuple("http://ex/Paper", 5L),
                        tuple("http://ex/Talk", 7L));
    }

    // ── V44: what the widened key must still FORBID ────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("two SNAPSHOT rows for one column are still rejected — the NULLS NOT DISTINCT half of V36")
    void twoSnapshotProfilesForOneColumnAreStillRejected() {
        // class_iri is NULL for both, so a plain UNIQUE (dataset_id, subject, class_iri,
        // column_name) would treat the two rows as distinct and enforce NOTHING here. This test is
        // the reason V36 spells NULLS NOT DISTINCT; it fails, loudly, if that clause is ever
        // dropped.
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "concert", "",
                null, List.of(snapshot("venue_id", 900L), snapshot("venue_id", 901L))));

        assertThatThrownBy(entityManager::flush)
                .hasStackTraceContaining("duplicate key value violates unique constraint");
    }

    @Test
    @Transactional
    @DisplayName("two WRAPPER_OUTPUT rows for the SAME class and column are still rejected")
    void twoWrapperOutputProfilesForOneClassAndColumnAreRejected() {
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new Dataset(datasetId, repositoryId, "concert", "", null, List.of(
                profile("total", 5L, "WRAPPER_OUTPUT", "http://ex/Paper"),
                profile("total", 6L, "WRAPPER_OUTPUT", "http://ex/Paper"))));

        assertThatThrownBy(entityManager::flush)
                .hasStackTraceContaining("duplicate key value violates unique constraint");
    }

    @Test
    @Transactional
    @DisplayName("the same column name under two different datasets is unaffected — dataset_id still leads the key")
    void sameColumnNameUnderTwoDatasetsIsFine() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        datasets.save(ctx, new Dataset(first, repositoryId, "concert", "", null,
                List.of(snapshot("venue_id", 1L))));
        datasets.save(ctx, new Dataset(second, repositoryId, "venue", "", null,
                List.of(snapshot("venue_id", 2L))));
        entityManager.flush();
        entityManager.clear();

        assertThat(datasets.findById(ctx, first).orElseThrow().columns()).singleElement()
                .extracting(ColumnProfile::cardinality).isEqualTo(1L);
        assertThat(datasets.findById(ctx, second).orElseThrow().columns()).singleElement()
                .extracting(ColumnProfile::cardinality).isEqualTo(2L);
    }
}

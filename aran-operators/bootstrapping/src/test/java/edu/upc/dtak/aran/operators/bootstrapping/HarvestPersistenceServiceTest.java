package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.upc.dtak.aran.core.domain.CatalogCacheInvalidator;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKeyRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.Harvest.ColKey;
import edu.upc.dtak.aran.core.source.Harvest.ForeignKey;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * {@link HarvestPersistenceService} — the write path {@code RelationalCatalogHarvestService}
 * (Task 2) has none of and Task 5 (harvest trigger + cadence) will call after obtaining a fresh
 * {@link Harvest}. Pure unit tests against mocked ports: the interesting behaviour here is the
 * merge/replace policy, not JDBC (already proven live in {@code
 * RelationalCatalogHarvestServiceTest}).
 */
class HarvestPersistenceServiceTest {

    private static final TenantContext TENANT = new TenantContext(UUID.randomUUID().toString());
    private static final UUID REPO_ID = UUID.randomUUID();

    private static RelationalDatabaseRepository repo() {
        return new RelationalDatabaseRepository(REPO_ID, UUID.randomUUID(), UUID.fromString(TENANT.value()),
                "repo", "", "POSTGRESQL", "host", 5432, "db", "user", "pw", "public", true);
    }

    private static ColumnProfile snapshotProfile(String column) {
        return new ColumnProfile(UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                1L, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null, false, false, List.of(), List.of());
    }

    private static Dataset dataset(UUID id, String name, List<ColumnProfile> columns) {
        return new Dataset(id, REPO_ID, name, "", null, columns, null, null);
    }

    private static List<ILoggingEvent> capturingWarnings(Runnable body) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HarvestPersistenceService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            body.run();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        return captured.list;
    }

    // ── stats: additive-only per table ──────────────────────────────────────────

    @Test
    @DisplayName("a harvested table's row count/NDV lands as HARVESTED profiles, existing SNAPSHOT rows kept")
    void persistsHarvestedProfilesAlongsideExistingSnapshotOnes() {
        UUID datasetId = UUID.randomUUID();
        Dataset employees = dataset(datasetId, "employees", List.of(snapshotProfile("legacy_col")));

        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(employees));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        Harvest harvest = new Harvest(
                Map.of("employees", 900L),
                Map.of(new ColKey("employees", "id"), 900L),
                List.of());

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest);

        ArgumentCaptor<Dataset> saved = ArgumentCaptor.forClass(Dataset.class);
        verify(datasets).save(eq(TENANT), saved.capture());
        assertThat(saved.getValue().columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("legacy_col", "SNAPSHOT"),
                        org.assertj.core.groups.Tuple.tuple("id", "HARVESTED"));
    }

    @Test
    @DisplayName("re-harvesting replaces the previous HARVESTED profile for a column rather than duplicating it")
    void reharvestingReplacesRatherThanDuplicatesHarvestedProfiles() {
        UUID datasetId = UUID.randomUUID();
        ColumnProfile staleHarvested = HarvestedColumnProfiles.forTable(
                new Harvest(Map.of("employees", 100L), Map.of(new ColKey("employees", "id"), 100L), List.of()),
                datasetId, "employees").get(0);
        Dataset employees = dataset(datasetId, "employees", List.of(staleHarvested));

        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(employees));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        Harvest freshHarvest = new Harvest(
                Map.of("employees", 900L),
                Map.of(new ColKey("employees", "id"), 900L),
                List.of());

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), freshHarvest);

        ArgumentCaptor<Dataset> saved = ArgumentCaptor.forClass(Dataset.class);
        verify(datasets).save(eq(TENANT), saved.capture());
        assertThat(saved.getValue().columns()).hasSize(1); // not 2 — the stale row was replaced
        assertThat(saved.getValue().columns().get(0).cardinality()).isEqualTo(900L);
    }

    @Test
    @DisplayName("a dataset this harvest says nothing about is left completely untouched")
    void datasetOutsideTheHarvestIsNeverSaved() {
        UUID untouchedId = UUID.randomUUID();
        Dataset untouched = dataset(untouchedId, "other_table", List.of(snapshotProfile("x")));

        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(untouched));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        // harvests only "employees" — "other_table" is not mentioned anywhere in this Harvest.
        Harvest harvest = new Harvest(
                Map.of("employees", 900L),
                Map.of(new ColKey("employees", "id"), 900L),
                List.of());

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest);

        verify(datasets, never()).save(any(), any());
    }

    @Test
    @DisplayName("Harvest.EMPTY (total connection failure) skips persistence entirely — no dataset read, no writes")
    void totalHarvestFailureIsACompleteNoOp() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), Harvest.EMPTY);

        verify(datasets, never()).findAllByRepository(any(), any());
        verify(datasets, never()).save(any(), any());
        verify(fks, never()).replaceForRepository(any(), any(), any());
    }

    // ── declared foreign keys: full replace, resolved by dataset id ────────────

    @Test
    @DisplayName("a harvested FK resolves both endpoint tables to dataset ids before persisting")
    void resolvesForeignKeyEndpointsToDatasetIds() {
        UUID employeesId = UUID.randomUUID();
        UUID departmentsId = UUID.randomUUID();
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(employeesId, "employees", List.of()),
                dataset(departmentsId, "departments", List.of())));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        Harvest harvest = new Harvest(Map.of(), Map.of(),
                List.of(new ForeignKey("employees", List.of("dept_id"), "departments", List.of("id"))));

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeclaredForeignKey>> captor = ArgumentCaptor.forClass(List.class);
        verify(fks).replaceForRepository(eq(TENANT), eq(REPO_ID), captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(edge -> {
            assertThat(edge.fromDatasetId()).isEqualTo(employeesId);
            assertThat(edge.fromCols()).containsExactly("dept_id");
            assertThat(edge.toDatasetId()).isEqualTo(departmentsId);
            assertThat(edge.toCols()).containsExactly("id");
            assertThat(edge.repositoryId()).isEqualTo(REPO_ID);
        });
    }

    @Test
    @DisplayName("a FK whose endpoint table has no matching dataset is dropped, WARN logged, other FKs unaffected")
    void dropsForeignKeyWithUnmatchedTable() {
        UUID employeesId = UUID.randomUUID();
        UUID departmentsId = UUID.randomUUID();
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(employeesId, "employees", List.of()),
                dataset(departmentsId, "departments", List.of())));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        ForeignKey good = new ForeignKey("employees", List.of("dept_id"), "departments", List.of("id"));
        ForeignKey bad = new ForeignKey("employees", List.of("x"), "unknown_table", List.of("y"));
        Harvest harvest = new Harvest(Map.of(), Map.of(), List.of(bad, good));

        List<ILoggingEvent> warnings = capturingWarnings(() ->
                new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest));

        assertThat(warnings).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("unknown_table");
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeclaredForeignKey>> captor = ArgumentCaptor.forClass(List.class);
        verify(fks).replaceForRepository(eq(TENANT), eq(REPO_ID), captor.capture());
        assertThat(captor.getValue()).singleElement()
                .extracting(DeclaredForeignKey::fromDatasetId).isEqualTo(employeesId);
    }

    @Test
    @DisplayName("a harvest with genuinely zero declared FKs still replaces (clears) whatever was persisted before")
    void zeroDeclaredForeignKeysStillReplacesPreviousState() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(UUID.randomUUID(), "employees", List.of())));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        // Non-empty stats (so the harvest as a whole is not Harvest.EMPTY) but zero FKs. The 3-arg
        // constructor defaults fkHarvestSucceeded=true — this IS the "genuinely zero" case, not the
        // "FK piece failed" case the tests below cover.
        Harvest harvest = new Harvest(Map.of("employees", 10L), Map.of(), List.of());

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest);

        verify(fks).replaceForRepository(TENANT, REPO_ID, List.of());
    }

    // ── durability fix: a partial harvest (FK piece failed) must PRESERVE prior edges ──────────
    //
    // Carried from Task 3's ledgered Important 1: connection + stats succeed but the FK query
    // alone fails (e.g. a narrower grant on information_schema/pg_constraint than pg_stats, or a
    // transient lock) used to degrade declaredForeignKeys() to an empty list — indistinguishable
    // from "genuinely zero FKs" — and replaceForRepository(..., emptyList) would then wipe every
    // previously-harvested DECLARED edge. On the Task 5 refresh cadence a single bad tick would
    // revert a repository to guess-driven planning indefinitely, behind only a WARN. Harvest now
    // carries fkHarvestSucceeded (Task 2 shape change, back-compat via the 3-arg constructor) so
    // this class can tell the two apart and preserve rather than wipe.

    @Test
    @DisplayName("connection+stats succeed, FK query fails (fkHarvestSucceeded=false) "
            + "-> prior DECLARED edges are PRESERVED, not wiped")
    void fkHarvestFailurePreservesPriorDeclaredEdges() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(UUID.randomUUID(), "employees", List.of())));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        // Stats succeeded (so this is NOT Harvest.EMPTY, and persistStats still runs), but the FK
        // piece independently failed: declaredForeignKeys() degrades to empty exactly as it would
        // for "no FKs", and fkHarvestSucceeded=false is the only thing telling them apart.
        Harvest harvest = new Harvest(Map.of("employees", 10L), Map.of(), List.of(), false);

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest);

        verify(fks, never()).replaceForRepository(any(), any(), any());
    }

    @Test
    @DisplayName("stats still persist (additive) even when the FK piece of the same harvest failed")
    void statsStillPersistWhenFkHarvestFailed() {
        UUID datasetId = UUID.randomUUID();
        Dataset employees = dataset(datasetId, "employees", List.of(snapshotProfile("legacy_col")));
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(employees));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        Harvest harvest = new Harvest(
                Map.of("employees", 900L), Map.of(new ColKey("employees", "id"), 900L),
                List.of(), false);

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest);

        ArgumentCaptor<Dataset> saved = ArgumentCaptor.forClass(Dataset.class);
        verify(datasets).save(eq(TENANT), saved.capture());
        assertThat(saved.getValue().columns())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("legacy_col", "SNAPSHOT"),
                        org.assertj.core.groups.Tuple.tuple("id", "HARVESTED"));
        verify(fks, never()).replaceForRepository(any(), any(), any());
    }

    @Test
    @DisplayName("a WARN naming the repository is logged when the FK harvest failed")
    void fkHarvestFailureLogsWarnNamingTheRepository() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of());
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);
        Harvest harvest = new Harvest(Map.of("t", 1L), Map.of(), List.of(), false);

        List<ILoggingEvent> warnings = capturingWarnings(() ->
                new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest));

        assertThat(warnings).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains(REPO_ID.toString());
        });
    }

    // ── V44: the two writes must fail independently ───────────────────────────────────────────
    //
    // persistStats ran first and unguarded, so anything it threw — live, a duplicate-key violation
    // on uq_column_profile_dataset_name — took persistDeclaredForeignKeys down with it before it
    // ever ran. declared_foreign_key therefore stayed permanently empty for any repository with
    // more than one enrolled table, which is the minimum a foreign key needs, and the whole
    // DECLARED tier was unreachable on the natural path. The stats half is the weaker evidence of
    // the two (an ANALYZE estimate); it must never be able to cost the stronger half its write.

    @Test
    @DisplayName("a stats-persistence failure does not stop the declared-FK write (V44)")
    void statsFailureDoesNotTakeTheForeignKeyWriteDown() {
        UUID employeesId = UUID.randomUUID();
        UUID departmentsId = UUID.randomUUID();
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(employeesId, "employees", List.of()),
                dataset(departmentsId, "departments", List.of())));
        when(datasets.save(any(), any())).thenThrow(new IllegalStateException("duplicate key value"));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        Harvest harvest = new Harvest(
                Map.of("employees", 900L), Map.of(new ColKey("employees", "id"), 900L),
                List.of(new ForeignKey("employees", List.of("dept_id"), "departments", List.of("id"))));

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeclaredForeignKey>> captor = ArgumentCaptor.forClass(List.class);
        verify(fks).replaceForRepository(eq(TENANT), eq(REPO_ID), captor.capture());
        assertThat(captor.getValue()).singleElement()
                .extracting(DeclaredForeignKey::fromDatasetId).isEqualTo(employeesId);
    }

    @Test
    @DisplayName("the stats WARN names the stats half and the dataset, so the log says which write was lost")
    void statsFailureWarnsNamingTheDatasetAndTheHalfThatFailed() {
        UUID employeesId = UUID.randomUUID();
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(employeesId, "employees", List.of())));
        when(datasets.save(any(), any())).thenThrow(new IllegalStateException("duplicate key value"));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        Harvest harvest = new Harvest(
                Map.of("employees", 900L), Map.of(new ColKey("employees", "id"), 900L), List.of());

        List<ILoggingEvent> warnings = capturingWarnings(() ->
                new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest));

        assertThat(warnings).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("HARVESTED stats", employeesId.toString());
        });
    }

    @Test
    @DisplayName("one dataset's stats failure does not stop the next dataset's stats from persisting")
    void oneDatasetsStatsFailureDoesNotStopTheNextDataset() {
        UUID employeesId = UUID.randomUUID();
        UUID departmentsId = UUID.randomUUID();
        Dataset employees = dataset(employeesId, "employees", List.of());
        Dataset departments = dataset(departmentsId, "departments", List.of());
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID))
                .thenReturn(List.of(employees, departments));
        when(datasets.save(eq(TENANT), argThat(d -> d != null && "employees".equals(d.name()))))
                .thenThrow(new IllegalStateException("duplicate key value"));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        Harvest harvest = new Harvest(
                Map.of("employees", 900L, "departments", 5L),
                Map.of(new ColKey("employees", "id"), 900L, new ColKey("departments", "id"), 5L),
                List.of());

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), harvest);

        ArgumentCaptor<Dataset> saved = ArgumentCaptor.forClass(Dataset.class);
        verify(datasets, times(2)).save(eq(TENANT), saved.capture());
        assertThat(saved.getAllValues()).extracting(Dataset::name)
                .containsExactlyInAnyOrder("employees", "departments");
    }

    // ── catalog cache invalidation (Important 3): a harvest's changes must reach the planner ──────
    //
    // The catalog cache keys on the graph version, which a harvest does not bump, so a harvest that
    // changes DECLARED edges or HARVESTED stats — a dropped FK most of all — would stay invisible
    // until the next promotion. persist() therefore invalidates the tenant's cache after any write.

    @Test
    @DisplayName("a harvest that replaces the declared FKs invalidates the tenant's catalog cache")
    void invalidatesCatalogCacheAfterAnFkReplace() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(UUID.randomUUID(), "employees", List.of()),
                dataset(UUID.randomUUID(), "departments", List.of())));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);
        CatalogCacheInvalidator cache = mock(CatalogCacheInvalidator.class);

        Harvest harvest = new Harvest(Map.of(), Map.of(),
                List.of(new ForeignKey("employees", List.of("dept_id"), "departments", List.of("id"))));

        new HarvestPersistenceService(datasets, fks, Optional.of(cache)).persist(TENANT, repo(), harvest);

        verify(cache).invalidate(TENANT);
    }

    @Test
    @DisplayName("a harvest that DROPS an FK (empty replace) still invalidates — the removal must reach the planner")
    void invalidatesCatalogCacheWhenAnFkIsDropped() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(UUID.randomUUID(), "employees", List.of())));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);
        CatalogCacheInvalidator cache = mock(CatalogCacheInvalidator.class);

        // fkHarvestSucceeded defaults true, zero FKs → a full replace with an empty list, i.e. the
        // source dropped its last FK. This is precisely the stale-removal window, so it MUST evict.
        Harvest harvest = new Harvest(Map.of(), Map.of(), List.of());

        new HarvestPersistenceService(datasets, fks, Optional.of(cache)).persist(TENANT, repo(), harvest);

        verify(fks).replaceForRepository(TENANT, REPO_ID, List.of());
        verify(cache).invalidate(TENANT);
    }

    @Test
    @DisplayName("Harvest.EMPTY (total failure) touches nothing, so it never invalidates the cache")
    void emptyHarvestDoesNotInvalidate() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);
        CatalogCacheInvalidator cache = mock(CatalogCacheInvalidator.class);

        new HarvestPersistenceService(datasets, fks, Optional.of(cache)).persist(TENANT, repo(), Harvest.EMPTY);

        verify(cache, never()).invalidate(any());
    }

    @Test
    @DisplayName("a harvest that changes nothing (FK piece failed, no matching stats) does not invalidate")
    void noChangeDoesNotInvalidate() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        // The harvest's stats name a table this repository has no dataset for, so persistStats
        // writes nothing; the FK piece failed, so no replace runs either — nothing changed.
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(UUID.randomUUID(), "other_table", List.of())));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);
        CatalogCacheInvalidator cache = mock(CatalogCacheInvalidator.class);

        Harvest harvest = new Harvest(Map.of("employees", 10L), Map.of(), List.of(), false);

        new HarvestPersistenceService(datasets, fks, Optional.of(cache)).persist(TENANT, repo(), harvest);

        verify(datasets, never()).save(any(), any());
        verify(fks, never()).replaceForRepository(any(), any(), any());
        verify(cache, never()).invalidate(any());
    }

    @Test
    @DisplayName("once the FK harvest succeeds again, the replace resumes normally")
    void fkHarvestSucceedingAgainResumesTheReplace() {
        UUID employeesId = UUID.randomUUID();
        UUID departmentsId = UUID.randomUUID();
        DatasetRepository datasets = mock(DatasetRepository.class);
        when(datasets.findAllByRepository(TENANT, REPO_ID)).thenReturn(List.of(
                dataset(employeesId, "employees", List.of()),
                dataset(departmentsId, "departments", List.of())));
        DeclaredForeignKeyRepository fks = mock(DeclaredForeignKeyRepository.class);

        Harvest recovered = new Harvest(Map.of(), Map.of(),
                List.of(new ForeignKey("employees", List.of("dept_id"), "departments", List.of("id"))),
                true);

        new HarvestPersistenceService(datasets, fks).persist(TENANT, repo(), recovered);

        verify(fks).replaceForRepository(eq(TENANT), eq(REPO_ID), any());
    }
}

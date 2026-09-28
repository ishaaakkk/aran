package edu.upc.dtak.aran.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.IntegrationRunMode;
import edu.upc.dtak.aran.core.domain.IntegrationRunStatus;
import edu.upc.dtak.aran.core.domain.MappingApplicationState;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelBundle;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence guarantees for the integration model (V16): tenant isolation, one model
 * per {@code (tenant, project)}, and the idempotent-insert behaviour the service leans on
 * for re-running {@code addSource}/{@code suggest} without duplicating rows.
 */
@DisplayName("Integration model persistence")
class IntegrationModelPersistenceIT extends BaseIntegrationTest {

    @Autowired IntegrationModelRepository repo;
    @Autowired edu.upc.dtak.aran.core.domain.TenantRepository tenants;
    @Autowired edu.upc.dtak.aran.core.domain.UserRepository users;
    @Autowired edu.upc.dtak.aran.core.domain.ProjectRepository projects;
    @Autowired edu.upc.dtak.aran.core.domain.DataRepositoryRepository repositories;
    @Autowired edu.upc.dtak.aran.core.domain.DatasetRepository datasets;

    /**
     * V42 gave {@code integration_model.project_id} a real FK, so a model now needs a real project
     * row — provision the chain up to it (tenant → user → project) and hand back the project id.
     */
    private UUID provisionProject(TenantContext ctx) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.fromString(ctx.value());
        UUID userId = UUID.randomUUID();
        users.save(new edu.upc.dtak.aran.core.domain.User(
                userId, "sub-" + suffix, suffix + "@test.com", "Test", Instant.now()));
        tenants.save(new edu.upc.dtak.aran.core.domain.Tenant(
                tenantId, "tenant-" + suffix, "Tenant", Instant.now()));
        return projects.save(ctx, new edu.upc.dtak.aran.core.domain.Project(
                UUID.randomUUID(), tenantId, "Project-" + suffix, "", userId, Instant.now())).id();
    }

    /**
     * V37 gave {@code model_source.dataset_id} a real FK, so an enrolment needs a real dataset
     * row — a repository and a dataset under the project {@link #provisionProject} made.
     */
    private UUID provisionDataset(TenantContext ctx, UUID projectId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.fromString(ctx.value());
        var repo = repositories.save(ctx, new edu.upc.dtak.aran.core.domain.FileSystemRepository(
                UUID.randomUUID(), projectId, tenantId, "repo-" + suffix, ""));
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new edu.upc.dtak.aran.core.domain.Dataset(datasetId, repo.id(), "ds-" + suffix, ""));
        return datasetId;
    }

    @Test
    @Transactional
    @DisplayName("getOrCreate is one model per (tenant, project) and never crosses tenants")
    void model_is_scoped_and_singleton() {
        var ctxA = new TenantContext(UUID.randomUUID().toString());
        var ctxB = new TenantContext(UUID.randomUUID().toString());
        UUID projectId = provisionProject(ctxA);
        UUID projectB = provisionProject(ctxB);

        IntegrationModel a1 = repo.getOrCreate(ctxA, projectId);
        IntegrationModel a2 = repo.getOrCreate(ctxA, projectId);
        assertThat(a2.id()).as("same tenant+project → same model").isEqualTo(a1.id());

        IntegrationModel b1 = repo.getOrCreate(ctxB, projectB);
        assertThat(b1.id()).as("a different tenant gets its own model").isNotEqualTo(a1.id());

        // Tenant B seeds a source; tenant A must not see it.
        repo.addSource(ctxB, new ModelSource(UUID.randomUUID(),
                UUID.fromString(ctxB.value()), b1.id(), provisionDataset(ctxB, projectB), "http://ex/B",
                null, null, null, false));
        assertThat(repo.load(ctxA, projectId).sources())
                .as("tenant A's bundle never contains tenant B's rows").isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("addSource and addMapping are idempotent on their unique keys")
    void inserts_are_idempotent() {
        var ctx = new TenantContext(UUID.randomUUID().toString());
        UUID projectId = provisionProject(ctx);
        UUID tenantId = UUID.fromString(ctx.value());
        IntegrationModel model = repo.getOrCreate(ctx, projectId);
        UUID datasetId = provisionDataset(ctx, projectId);

        ModelSource first = repo.addSource(ctx, new ModelSource(UUID.randomUUID(),
                tenantId, model.id(), datasetId, "http://ex/Paper", "SELECT 1", null, null, false));
        ModelSource again = repo.addSource(ctx, new ModelSource(UUID.randomUUID(),
                tenantId, model.id(), datasetId, "http://ex/Paper", "SELECT 2", null, null, false));
        assertThat(again.id()).as("repeat add returns the existing row").isEqualTo(first.id());

        repo.addMapping(ctx, new ModelMapping(UUID.randomUUID(), tenantId, model.id(),
                MappingKinds.CLASS, "http://ex/Paper", "http://ex/Article", "Paper",
                ModelMapping.ACCEPTED, null, null, null, Instant.now()));
        repo.addMapping(ctx, new ModelMapping(UUID.randomUUID(), tenantId, model.id(),
                MappingKinds.CLASS, "http://ex/Paper", "http://ex/Article", "Paper again",
                ModelMapping.SUGGESTED, null, null, null, Instant.now()));

        ModelBundle bundle = repo.load(ctx, projectId);
        assertThat(bundle.sources()).hasSize(1);
        assertThat(bundle.mappings()).as("the unique (model, kind, src, tgt) key blocks the duplicate")
                .hasSize(1);
    }

    @Test
    @Transactional
    @DisplayName("wrapper edits and constraint enforcement persist and reload")
    void wrapper_and_constraint_updates_round_trip() {
        var ctx = new TenantContext(UUID.randomUUID().toString());
        UUID projectId = provisionProject(ctx);
        UUID tenantId = UUID.fromString(ctx.value());
        IntegrationModel model = repo.getOrCreate(ctx, projectId);
        UUID datasetId = provisionDataset(ctx, projectId);

        repo.addSource(ctx, new ModelSource(UUID.randomUUID(), tenantId, model.id(),
                datasetId, "http://ex/Paper", null, null, null, false));
        // V30: the verify-wrapper verdict rides in the same write as the sql it describes.
        repo.upsertSourceWrapper(ctx, model.id(), datasetId, "http://ex/Paper", "SELECT edited",
                "EXPANDED", "round-trip matched");

        assertThat(repo.load(ctx, projectId).sources())
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.wrapperSql()).isEqualTo("SELECT edited");
                    assertThat(s.expansionStatus()).isEqualTo("EXPANDED");
                    assertThat(s.expansionReason()).isEqualTo("round-trip matched");
                });

        // Clearing the wrapper clears its verdict too — nothing is left to have been verified.
        repo.upsertSourceWrapper(ctx, model.id(), datasetId, "http://ex/Paper", null, null, null);
        assertThat(repo.load(ctx, projectId).sources())
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.wrapperSql()).isNull();
                    assertThat(s.expansionStatus()).isNull();
                    assertThat(s.expansionReason()).isNull();
                });

        var constraint = repo.addConstraint(ctx, new edu.upc.dtak.aran.core.domain.ModelConstraint(
                UUID.randomUUID(), tenantId, model.id(), "http://ex/Paper", "h_index >= 0",
                false, Instant.now(), "MANUAL"));
        repo.setConstraintEnforced(ctx, constraint.id(), true);

        assertThat(repo.load(ctx, projectId).constraints())
                .singleElement()
                .extracting(edu.upc.dtak.aran.core.domain.ModelConstraint::enforced)
                .isEqualTo(true);
    }

    @Test
    @Transactional
    @DisplayName("V47: the re-seed follows an unedited wrapper and never touches an edited one")
    void reseed_is_gated_on_wrapper_edited() {
        var ctx = new TenantContext(UUID.randomUUID().toString());
        UUID projectId = provisionProject(ctx);
        UUID tenantId = UUID.fromString(ctx.value());
        IntegrationModel model = repo.getOrCreate(ctx, projectId);
        // A REAL dataset row, not a bare UUID: V37 gave model_source.dataset_id an ON DELETE
        // CASCADE foreign key, so an enrolment naming a dataset that does not exist is now
        // refused by the database. This test predates that migration by hours and was written
        // against the bare-UUID shape.
        UUID datasetId = provisionDataset(ctx, projectId);

        // Two classes of one dataset: one still carrying the enrolment seed, one a person edited.
        repo.addSource(ctx, new ModelSource(UUID.randomUUID(), tenantId, model.id(),
                datasetId, "http://ex/Venue", "SELECT \"city\" FROM ${SNAPSHOT}", null, null, false));
        repo.addSource(ctx, new ModelSource(UUID.randomUUID(), tenantId, model.id(),
                datasetId, "http://ex/Paper", null, null, null, false));
        // The wrapper endpoint's own path — and reaching it is what makes the row edited (V37).
        repo.upsertSourceWrapper(ctx, model.id(), datasetId, "http://ex/Paper",
                "SELECT \"title\" FROM ${SNAPSHOT}", "OPAQUE", "hand-written");

        int reseeded = repo.reseedUneditedWrappers(ctx, datasetId, java.util.Map.of(
                "http://ex/Venue", "SELECT \"city_name\" FROM ${SNAPSHOT}",
                "http://ex/Paper", "SELECT \"headline\" FROM ${SNAPSHOT}"));

        assertThat(reseeded).as("only the unedited row is re-seeded").isEqualTo(1);
        assertThat(repo.load(ctx, projectId).sources())
                .filteredOn(s -> s.classIri().equals("http://ex/Venue"))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.wrapperSql()).isEqualTo("SELECT \"city_name\" FROM ${SNAPSHOT}");
                    assertThat(s.wrapperEdited()).isFalse();
                });
        assertThat(repo.load(ctx, projectId).sources())
                .filteredOn(s -> s.classIri().equals("http://ex/Paper"))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.wrapperEdited())
                            .as("a save through the wrapper endpoint marks the row edited")
                            .isTrue();
                    assertThat(s.wrapperSql())
                            .as("and an edited wrapper is never silently replaced")
                            .isEqualTo("SELECT \"title\" FROM ${SNAPSHOT}");
                    assertThat(s.expansionStatus()).isEqualTo("OPAQUE");
                });
    }

    @Test
    @Transactional
    @DisplayName("V32: a constraint's origin persists and reloads, DISCOVERED and MANUAL alike")
    void constraint_origin_round_trips() {
        var ctx = new TenantContext(UUID.randomUUID().toString());
        UUID projectId = provisionProject(ctx);
        UUID tenantId = UUID.fromString(ctx.value());
        IntegrationModel model = repo.getOrCreate(ctx, projectId);

        var discovered = repo.addConstraint(ctx, new edu.upc.dtak.aran.core.domain.ModelConstraint(
                UUID.randomUUID(), tenantId, model.id(), "http://ex/Paper",
                "NOT (colA = colA AND colB <> colB)", false, Instant.now(), "DISCOVERED"));
        var manual = repo.addConstraint(ctx, new edu.upc.dtak.aran.core.domain.ModelConstraint(
                UUID.randomUUID(), tenantId, model.id(), "http://ex/Paper", "h_index >= 0",
                false, Instant.now(), "MANUAL"));

        List<edu.upc.dtak.aran.core.domain.ModelConstraint> reloaded = repo.load(ctx, projectId).constraints();
        assertThat(reloaded).extracting(edu.upc.dtak.aran.core.domain.ModelConstraint::id,
                        edu.upc.dtak.aran.core.domain.ModelConstraint::origin)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(discovered.id(), "DISCOVERED"),
                        org.assertj.core.groups.Tuple.tuple(manual.id(), "MANUAL"));

        // The ADAPTER stays origin-blind: the flag flips on a DISCOVERED row exactly as on a
        // MANUAL one, and `origin` survives it. The refusal to enforce a discovered candidate is a
        // SERVICE-tier policy (IntegrationModelService.setConstraintEnforced, V43) —
        // deliberately not pushed down here, so that the day inter-tuple enforcement lands the
        // storage layer needs no change. Asserted at its own tier by
        // DiscoveredConstraintRoundTripIT.
        repo.setConstraintEnforced(ctx, discovered.id(), true);
        assertThat(repo.load(ctx, projectId).constraints())
                .filteredOn(c -> c.id().equals(discovered.id()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.enforced()).isTrue();
                    assertThat(c.origin()).as("enforcing does not change origin").isEqualTo("DISCOVERED");
                });
    }

    @Test
    @Transactional
    @DisplayName("V19: run lifecycle + mapping-application state + model flags round-trip against Postgres")
    void run_and_application_state_round_trip() {
        var ctx = new TenantContext(UUID.randomUUID().toString());
        UUID projectId = provisionProject(ctx);
        UUID tenantId = UUID.fromString(ctx.value());
        IntegrationModel model = repo.getOrCreate(ctx, projectId);

        ModelMapping mapping = repo.addMapping(ctx, new ModelMapping(UUID.randomUUID(), tenantId,
                model.id(), MappingKinds.CLASS, "http://ex/Paper", "http://ex/Article", "Paper",
                ModelMapping.ACCEPTED, null, null, null, Instant.now()));
        repo.upsertMappingApplicationPending(ctx, model.id(), mapping.id());
        assertThat(repo.acceptedPendingMappings(ctx, model.id())).extracting(ModelMapping::id)
                .containsExactly(mapping.id());

        // Run lifecycle: RUNNING → DRAFT → PROMOTED.
        IntegrationRun run = repo.startRun(ctx, model.id(), IntegrationRunMode.REBUILD, null, null);
        UUID versionId = UUID.randomUUID();
        repo.recordDraft(ctx, run.id(), versionId);
        repo.markRunPromoted(ctx, run.id(), versionId);
        repo.setActiveIntegrationRun(ctx, model.id(), run.id());

        assertThat(repo.latestPromotedRun(ctx, model.id()).orElseThrow().promotedVersionId())
                .isEqualTo(versionId);
        assertThat(repo.getOrCreate(ctx, projectId).activeIntegrationRunId()).isEqualTo(run.id());

        // Application PENDING → APPLIED.
        repo.markMappingsApplied(ctx, run.id(), versionId, List.of(mapping.id()));
        assertThat(repo.acceptedPendingMappings(ctx, model.id())).isEmpty();
        assertThat(repo.findMappingApplication(ctx, mapping.id()).orElseThrow().state())
                .isEqualTo(MappingApplicationState.APPLIED);

        // rebuild_required flag round-trips.
        repo.setRebuildRequired(ctx, model.id(), true);
        assertThat(repo.getOrCreate(ctx, projectId).rebuildRequired()).isTrue();
        repo.setRebuildRequired(ctx, model.id(), false);
        assertThat(repo.getOrCreate(ctx, projectId).rebuildRequired()).isFalse();
    }

    @Test
    @Transactional
    @DisplayName("V19: superseding an abandoned DRAFT run returns its mappings to PENDING")
    void abandoned_draft_run_is_superseded() {
        var ctx = new TenantContext(UUID.randomUUID().toString());
        UUID projectId = provisionProject(ctx);
        UUID tenantId = UUID.fromString(ctx.value());
        IntegrationModel model = repo.getOrCreate(ctx, projectId);

        ModelMapping mapping = repo.addMapping(ctx, new ModelMapping(UUID.randomUUID(), tenantId,
                model.id(), MappingKinds.CLASS, "http://ex/Book", "http://ex/Volume", "Book",
                ModelMapping.ACCEPTED, null, null, null, Instant.now()));
        repo.upsertMappingApplicationPending(ctx, model.id(), mapping.id());

        // A run produces a draft and records the mapping as applied — but is never promoted.
        IntegrationRun abandoned = repo.startRun(ctx, model.id(), IntegrationRunMode.INCREMENTAL,
                UUID.randomUUID(), UUID.randomUUID());
        UUID draftVersionId = UUID.randomUUID();
        repo.recordDraft(ctx, abandoned.id(), draftVersionId);
        repo.markMappingsApplied(ctx, abandoned.id(), draftVersionId, List.of(mapping.id()));
        assertThat(repo.acceptedPendingMappings(ctx, model.id())).isEmpty();

        // Starting the next run supersedes it and releases the mapping back to PENDING.
        repo.supersedeDraftRuns(ctx, model.id());

        assertThat(repo.findRun(ctx, abandoned.id()).orElseThrow().status())
                .isEqualTo(IntegrationRunStatus.SUPERSEDED);
        assertThat(repo.findMappingApplication(ctx, mapping.id()).orElseThrow().state())
                .isEqualTo(MappingApplicationState.PENDING);
        assertThat(repo.acceptedPendingMappings(ctx, model.id())).extracting(ModelMapping::id)
                .containsExactly(mapping.id());

        // A PROMOTED run is durable — superseding must not touch it.
        IntegrationRun promoted = repo.startRun(ctx, model.id(), IntegrationRunMode.REBUILD, null, null);
        UUID promotedVersionId = UUID.randomUUID();
        repo.recordDraft(ctx, promoted.id(), promotedVersionId);
        repo.markRunPromoted(ctx, promoted.id(), promotedVersionId);
        repo.markMappingsApplied(ctx, promoted.id(), promotedVersionId, List.of(mapping.id()));

        repo.supersedeDraftRuns(ctx, model.id());

        assertThat(repo.findRun(ctx, promoted.id()).orElseThrow().status())
                .isEqualTo(IntegrationRunStatus.PROMOTED);
        assertThat(repo.findMappingApplication(ctx, mapping.id()).orElseThrow().state())
                .isEqualTo(MappingApplicationState.APPLIED);
    }
}

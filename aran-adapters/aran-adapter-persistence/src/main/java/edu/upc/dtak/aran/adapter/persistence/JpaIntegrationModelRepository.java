package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.IntegrationModelEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.IntegrationRunEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ModelConstraintEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ModelMappingApplicationEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ModelMappingEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ModelSourceEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.IntegrationModelJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.IntegrationRunJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.ModelConstraintJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.ModelMappingApplicationJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.ModelMappingJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.ModelSourceJpaRepository;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.IntegrationRunMode;
import edu.upc.dtak.aran.core.domain.IntegrationRunStatus;
import edu.upc.dtak.aran.core.domain.MappingApplication;
import edu.upc.dtak.aran.core.domain.MappingApplicationState;
import edu.upc.dtak.aran.core.domain.ModelBundle;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link IntegrationModelRepository} port.
 *
 * <p><b>Tenant scoping is explicit here</b>, mirroring {@code JpaJobRepository}: every
 * finder names {@code tenantId} in its predicate rather than trusting the shared
 * {@code tenantFilter} Hibernate filter alone, because that filter is not consulted on a
 * load by primary key. The filter is still applied (this class is named in
 * {@code TenantFilterAspect}) and stays as defence in depth for the list queries.
 *
 * <p>Children are flat entities keyed by {@code model_id} (not JPA associations); the DB
 * enforces {@code ON DELETE CASCADE}, so removing a model would drop them, but v1 never
 * deletes a model — only its children, via the CRUD methods below.
 */
@Component
@NullMarked
class JpaIntegrationModelRepository implements IntegrationModelRepository {

    private final IntegrationModelJpaRepository models;
    private final ModelSourceJpaRepository sources;
    private final ModelMappingJpaRepository mappings;
    private final ModelConstraintJpaRepository constraints;
    private final IntegrationRunJpaRepository runs;
    private final ModelMappingApplicationJpaRepository applications;

    JpaIntegrationModelRepository(IntegrationModelJpaRepository models,
                                  ModelSourceJpaRepository sources,
                                  ModelMappingJpaRepository mappings,
                                  ModelConstraintJpaRepository constraints,
                                  IntegrationRunJpaRepository runs,
                                  ModelMappingApplicationJpaRepository applications) {
        this.models = models;
        this.sources = sources;
        this.mappings = mappings;
        this.constraints = constraints;
        this.runs = runs;
        this.applications = applications;
    }

    @Override
    public IntegrationModel getOrCreate(TenantContext tenant, UUID projectId) {
        UUID tenantId = tenantId(tenant);
        return models.findByTenantIdAndProjectId(tenantId, projectId)
                .map(this::toDomain)
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    IntegrationModelEntity entity = new IntegrationModelEntity(
                            UUID.randomUUID(), tenantId, projectId, now, now, null, false);
                    return toDomain(models.save(entity));
                });
    }

    @Override
    public ModelBundle load(TenantContext tenant, UUID projectId) {
        IntegrationModel model = getOrCreate(tenant, projectId);
        UUID tenantId = tenantId(tenant);
        List<ModelSource> src = sources.findAllByTenantIdAndModelId(tenantId, model.id())
                .stream().map(this::toDomain).toList();
        List<ModelMapping> map = mappings.findAllByTenantIdAndModelId(tenantId, model.id())
                .stream().map(this::toDomain).toList();
        List<ModelConstraint> con = constraints.findAllByTenantIdAndModelId(tenantId, model.id())
                .stream().map(this::toDomain).toList();
        return new ModelBundle(model, src, map, con);
    }

    @Override
    public List<IntegrationModel> findAllModels(TenantContext tenant) {
        return models.findAllByTenantId(tenantId(tenant)).stream().map(this::toDomain).toList();
    }

    // ── sources ───────────────────────────────────────────────────────────────

    @Override
    public ModelSource addSource(TenantContext tenant, ModelSource source) {
        UUID tenantId = tenantId(tenant);
        return sources.findByTenantIdAndModelIdAndDatasetIdAndClassIri(
                        tenantId, source.modelId(), source.datasetId(), source.classIri())
                .map(this::toDomain)
                .orElseGet(() -> toDomain(sources.save(new ModelSourceEntity(
                        source.id(), tenantId, source.modelId(), source.datasetId(),
                        source.classIri(), source.wrapperSql(),
                        source.expansionStatus(), source.expansionReason(),
                        source.wrapperEdited()))));
    }

    @Override
    public int removeSource(TenantContext tenant, UUID modelId, UUID datasetId) {
        UUID tenantId = tenantId(tenant);
        List<ModelSourceEntity> rows = sources.findAllByTenantIdAndModelIdAndDatasetId(
                tenantId, modelId, datasetId);
        sources.deleteAll(rows);
        return rows.size();
    }

    @Override
    public int downgradeExpandedWrappers(TenantContext tenant, UUID datasetId, String reason) {
        UUID tenantId = tenantId(tenant);
        List<ModelSourceEntity> rows = sources.findAllByTenantIdAndDatasetId(tenantId, datasetId)
                .stream()
                .filter(e -> "EXPANDED".equals(e.getExpansionStatus()))
                .toList();
        for (ModelSourceEntity e : rows) {
            e.setExpansionStatus("OPAQUE");
            e.setExpansionReason(reason);
            sources.save(e);
        }
        return rows.size();
    }

    @Override
    public void upsertSourceWrapper(TenantContext tenant, UUID modelId, UUID datasetId,
                                    String classIri, @Nullable String sql,
                                    @Nullable String expansionStatus, @Nullable String expansionReason) {
        UUID tenantId = tenantId(tenant);
        ModelSourceEntity entity = sources
                .findByTenantIdAndModelIdAndDatasetIdAndClassIri(tenantId, modelId, datasetId, classIri)
                .orElseGet(() -> new ModelSourceEntity(
                        UUID.randomUUID(), tenantId, modelId, datasetId, classIri,
                        null, null, null, false));
        entity.setWrapperSql(sql);
        entity.setExpansionStatus(expansionStatus);
        entity.setExpansionReason(expansionReason);
        // V47: this method is only reachable from the wrapper endpoint, so reaching it IS the
        // edit. Set even when sql is null — clearing the override is a decision a person made,
        // and re-seeding it from the graph behind their back would undo it.
        entity.setWrapperEdited(true);
        sources.save(entity);
    }

    @Override
    public int reseedUneditedWrappers(TenantContext tenant, UUID datasetId,
                                      Map<String, String> wrapperByClassIri) {
        UUID tenantId = tenantId(tenant);
        int reseeded = 0;
        for (ModelSourceEntity entity : sources.findAllByTenantIdAndDatasetId(tenantId, datasetId)) {
            if (entity.isWrapperEdited()) {
                continue;
            }
            String fresh = wrapperByClassIri.get(entity.getClassIri());
            if (fresh == null || fresh.equals(entity.getWrapperSql())) {
                continue;
            }
            entity.setWrapperSql(fresh);
            // The verdict described the text being replaced, so it cannot survive it. Null is the
            // "never verified" state MappingCatalogOperator already defaults to OPAQUE.
            entity.setExpansionStatus(null);
            entity.setExpansionReason(null);
            sources.save(entity);
            reseeded++;
        }
        return reseeded;
    }

    // ── mappings ──────────────────────────────────────────────────────────────

    @Override
    public ModelMapping addMapping(TenantContext tenant, ModelMapping mapping) {
        UUID tenantId = tenantId(tenant);
        return mappings.findByTenantIdAndModelIdAndKindAndSourceIriAndTargetIri(
                        tenantId, mapping.modelId(), mapping.kind(),
                        mapping.sourceIri(), mapping.targetIri())
                .map(this::toDomain)
                .orElseGet(() -> toDomain(mappings.save(new ModelMappingEntity(
                        mapping.id(), tenantId, mapping.modelId(), mapping.kind(),
                        mapping.sourceIri(), mapping.targetIri(), mapping.label(),
                        mapping.status(), mapping.evidenceJson(), mapping.author(),
                        mapping.comment(), mapping.createdAt()))));
    }

    @Override
    public void updateMappingStatus(TenantContext tenant, UUID mappingId, String status) {
        ModelMappingEntity entity = mappings.findByIdAndTenantId(mappingId, tenantId(tenant))
                .orElseThrow(() -> new IllegalArgumentException("mapping not found: " + mappingId));
        entity.setStatus(status);
        mappings.save(entity);
    }

    @Override
    public void deleteMapping(TenantContext tenant, UUID mappingId) {
        mappings.findByIdAndTenantId(mappingId, tenantId(tenant)).ifPresent(mappings::delete);
    }

    // ── constraints ─────────────────────────────────────────────────────────────

    @Override
    public ModelConstraint addConstraint(TenantContext tenant, ModelConstraint constraint) {
        UUID tenantId = tenantId(tenant);
        return toDomain(constraints.save(new ModelConstraintEntity(
                constraint.id(), tenantId, constraint.modelId(), constraint.classIri(),
                constraint.expression(), constraint.enforced(), constraint.createdAt(),
                constraint.origin())));
    }

    @Override
    public void setConstraintEnforced(TenantContext tenant, UUID constraintId, boolean enforced) {
        ModelConstraintEntity entity = constraints.findByIdAndTenantId(constraintId, tenantId(tenant))
                .orElseThrow(() -> new IllegalArgumentException("constraint not found: " + constraintId));
        entity.setEnforced(enforced);
        constraints.save(entity);
    }

    @Override
    public void deleteConstraint(TenantContext tenant, UUID constraintId) {
        constraints.findByIdAndTenantId(constraintId, tenantId(tenant)).ifPresent(constraints::delete);
    }

    @Override
    public List<ModelConstraint> findEnforcedConstraints(TenantContext tenant) {
        return constraints.findAllByTenantIdAndEnforcedTrue(tenantId(tenant))
                .stream().map(this::toDomain).toList();
    }

    // ── integration runs ────────────────────────────────────────────────────────

    @Override
    public IntegrationRun startRun(TenantContext tenant, UUID modelId, IntegrationRunMode mode,
                                   @Nullable UUID baseVersionId, @Nullable UUID datasetId) {
        Instant now = Instant.now();
        IntegrationRunEntity e = new IntegrationRunEntity(
                UUID.randomUUID(), tenantId(tenant), modelId, mode.name(),
                IntegrationRunStatus.RUNNING.name(), baseVersionId, datasetId,
                null, null, null, null, now, now);
        return toDomain(runs.save(e));
    }

    @Override
    public void recordDraft(TenantContext tenant, UUID runId, UUID draftVersionId) {
        IntegrationRunEntity e = requireRun(tenant, runId);
        e.setStatus(IntegrationRunStatus.DRAFT.name());
        e.setDraftVersionId(draftVersionId);
        e.setUpdatedAt(Instant.now());
        runs.save(e);
    }

    @Override
    public void markRunPromoted(TenantContext tenant, UUID runId, UUID promotedVersionId) {
        IntegrationRunEntity e = requireRun(tenant, runId);
        e.setStatus(IntegrationRunStatus.PROMOTED.name());
        e.setPromotedVersionId(promotedVersionId);
        e.setUpdatedAt(Instant.now());
        runs.save(e);
    }

    @Override
    public void markRunFailed(TenantContext tenant, UUID runId, String code, String message) {
        IntegrationRunEntity e = requireRun(tenant, runId);
        e.setStatus(IntegrationRunStatus.FAILED.name());
        e.setErrorCode(code);
        e.setErrorMessage(message);
        e.setUpdatedAt(Instant.now());
        runs.save(e);
    }

    @Override
    public Optional<IntegrationRun> findRun(TenantContext tenant, UUID runId) {
        return runs.findByIdAndTenantId(runId, tenantId(tenant)).map(this::toDomain);
    }

    @Override
    public Optional<IntegrationRun> latestPromotedRun(TenantContext tenant, UUID modelId) {
        return runs.findAllByTenantIdAndModelIdAndStatusOrderByUpdatedAtDesc(
                        tenantId(tenant), modelId, IntegrationRunStatus.PROMOTED.name())
                .stream().findFirst().map(this::toDomain);
    }

    @Override
    public Optional<IntegrationRun> findRunByDraftVersion(TenantContext tenant, UUID draftVersionId) {
        return runs.findFirstByTenantIdAndDraftVersionId(tenantId(tenant), draftVersionId).map(this::toDomain);
    }

    @Override
    public Optional<IntegrationRun> latestDraftRun(TenantContext tenant, UUID modelId) {
        return runs.findAllByTenantIdAndModelIdAndStatusOrderByUpdatedAtDesc(
                        tenantId(tenant), modelId, IntegrationRunStatus.DRAFT.name())
                .stream().findFirst().map(this::toDomain);
    }

    @Override
    public List<MappingApplication> findMappingApplications(TenantContext tenant, UUID modelId) {
        return applications.findAllByTenantIdAndModelId(tenantId(tenant), modelId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void supersedeDraftRuns(TenantContext tenant, UUID modelId) {
        UUID tenantId = tenantId(tenant);
        Instant now = Instant.now();
        for (IntegrationRunEntity run : runs.findAllByTenantIdAndModelIdAndStatusOrderByUpdatedAtDesc(
                tenantId, modelId, IntegrationRunStatus.DRAFT.name())) {
            // The records this run wrote were never promoted — return them to PENDING so the
            // next run re-evaluates them instead of treating them as durably applied.
            for (ModelMappingApplicationEntity app :
                    applications.findAllByTenantIdAndRunId(tenantId, run.getId())) {
                app.setState(MappingApplicationState.PENDING.name());
                app.setRunId(null);
                app.setVersionId(null);
                app.setReason(null);
                app.setUpdatedAt(now);
                applications.save(app);
            }
            run.setStatus(IntegrationRunStatus.SUPERSEDED.name());
            run.setUpdatedAt(now);
            runs.save(run);
        }
    }

    private IntegrationRunEntity requireRun(TenantContext tenant, UUID runId) {
        return runs.findByIdAndTenantId(runId, tenantId(tenant))
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
    }

    // ── mapping application state ─────────────────────────────────────────────────

    @Override
    public void upsertMappingApplicationPending(TenantContext tenant, UUID modelId, UUID mappingId) {
        UUID tenantId = tenantId(tenant);
        ModelMappingApplicationEntity e = applications.findByTenantIdAndMappingId(tenantId, mappingId)
                .orElseGet(() -> new ModelMappingApplicationEntity(
                        UUID.randomUUID(), tenantId, modelId, mappingId,
                        MappingApplicationState.PENDING.name(), null, null, null, Instant.now()));
        e.setState(MappingApplicationState.PENDING.name());
        e.setUpdatedAt(Instant.now());
        applications.save(e);
    }

    @Override
    public void markMappingsApplied(TenantContext tenant, UUID runId, UUID versionId,
                                    Collection<UUID> mappingIds) {
        for (UUID mappingId : mappingIds) {
            ModelMappingApplicationEntity e = requireApplication(tenant, mappingId);
            e.setState(MappingApplicationState.APPLIED.name());
            e.setRunId(runId);
            e.setVersionId(versionId);
            e.setReason(null);
            e.setUpdatedAt(Instant.now());
            applications.save(e);
        }
    }

    @Override
    public void markMappingsDeferred(TenantContext tenant, UUID runId,
                                     Collection<UUID> mappingIds, String reason) {
        for (UUID mappingId : mappingIds) {
            ModelMappingApplicationEntity e = requireApplication(tenant, mappingId);
            e.setState(MappingApplicationState.DEFERRED.name());
            e.setRunId(runId);
            e.setVersionId(null);
            e.setReason(reason);
            e.setUpdatedAt(Instant.now());
            applications.save(e);
        }
    }

    @Override
    public Optional<MappingApplication> findMappingApplication(TenantContext tenant, UUID mappingId) {
        return applications.findByTenantIdAndMappingId(tenantId(tenant), mappingId).map(this::toDomain);
    }

    @Override
    public List<ModelMapping> findMappingsUnderIri(TenantContext tenant, String iriPrefix) {
        return mappings.findAllByTenantIdAndIriPrefix(tenantId(tenant), iriPrefix)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<ModelMapping> acceptedPendingMappings(TenantContext tenant, UUID modelId) {
        return applications.findAcceptedMappingsInState(
                        tenantId(tenant), modelId, MappingApplicationState.PENDING.name())
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<ModelMapping> acceptedDeferredMappings(TenantContext tenant, UUID modelId) {
        return applications.findAcceptedMappingsInState(
                        tenantId(tenant), modelId, MappingApplicationState.DEFERRED.name())
                .stream().map(this::toDomain).toList();
    }

    private ModelMappingApplicationEntity requireApplication(TenantContext tenant, UUID mappingId) {
        return applications.findByTenantIdAndMappingId(tenantId(tenant), mappingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "mapping application not found for mapping: " + mappingId));
    }

    // ── model-level flags ─────────────────────────────────────────────────────────

    @Override
    public void setActiveIntegrationRun(TenantContext tenant, UUID modelId, @Nullable UUID runId) {
        IntegrationModelEntity e = requireModel(tenant, modelId);
        e.setActiveIntegrationRunId(runId);
        e.setUpdatedAt(Instant.now());
        models.save(e);
    }

    @Override
    public void setRebuildRequired(TenantContext tenant, UUID modelId, boolean value) {
        IntegrationModelEntity e = requireModel(tenant, modelId);
        e.setRebuildRequired(value);
        e.setUpdatedAt(Instant.now());
        models.save(e);
    }

    private IntegrationModelEntity requireModel(TenantContext tenant, UUID modelId) {
        UUID tenantId = tenantId(tenant);
        IntegrationModelEntity e = models.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException("model not found: " + modelId));
        if (!e.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("model not found: " + modelId);
        }
        return e;
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private IntegrationModel toDomain(IntegrationModelEntity e) {
        return new IntegrationModel(e.getId(), e.getTenantId(), e.getProjectId(),
                e.getCreatedAt(), e.getUpdatedAt(),
                e.getActiveIntegrationRunId(), e.isRebuildRequired());
    }

    private ModelSource toDomain(ModelSourceEntity e) {
        return new ModelSource(e.getId(), e.getTenantId(), e.getModelId(),
                e.getDatasetId(), e.getClassIri(), e.getWrapperSql(),
                e.getExpansionStatus(), e.getExpansionReason(), e.isWrapperEdited());
    }

    private ModelMapping toDomain(ModelMappingEntity e) {
        return new ModelMapping(e.getId(), e.getTenantId(), e.getModelId(), e.getKind(),
                e.getSourceIri(), e.getTargetIri(), e.getLabel(), e.getStatus(),
                e.getEvidence(), e.getAuthor(), e.getComment(), e.getCreatedAt());
    }

    private ModelConstraint toDomain(ModelConstraintEntity e) {
        return new ModelConstraint(e.getId(), e.getTenantId(), e.getModelId(),
                e.getClassIri(), e.getExpression(), e.isEnforced(), e.getCreatedAt(), e.getOrigin());
    }

    private IntegrationRun toDomain(IntegrationRunEntity e) {
        return new IntegrationRun(e.getId(), e.getTenantId(), e.getModelId(),
                IntegrationRunMode.valueOf(e.getMode()), IntegrationRunStatus.valueOf(e.getStatus()),
                e.getBaseVersionId(), e.getDatasetId(), e.getDraftVersionId(), e.getPromotedVersionId(),
                e.getErrorCode(), e.getErrorMessage(), e.getStartedAt(), e.getUpdatedAt());
    }

    private MappingApplication toDomain(ModelMappingApplicationEntity e) {
        return new MappingApplication(e.getId(), e.getTenantId(), e.getModelId(), e.getMappingId(),
                MappingApplicationState.valueOf(e.getState()), e.getRunId(), e.getVersionId(),
                e.getReason(), e.getUpdatedAt());
    }

    private static UUID tenantId(TenantContext tenant) {
        return UUID.fromString(tenant.value());
    }
}

package edu.upc.dtak.aran.core.fake;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link IntegrationModelRepository} for service tests.
 *
 * <p>Reproduces the two behaviours the tests care about that the real adapter gets from
 * the DB: one model per {@code (tenant, project)}, and idempotent inserts on the source
 * {@code (model, dataset, class)} and mapping {@code (model, kind, source, target)} unique
 * keys — a repeated add returns the existing row rather than duplicating it.
 */
public class InMemoryIntegrationModelRepository implements IntegrationModelRepository {

    private final Map<String, IntegrationModel> modelsByProject = new LinkedHashMap<>();
    private final List<ModelSource> sources = new ArrayList<>();
    private final List<ModelMapping> mappings = new ArrayList<>();
    private final List<ModelConstraint> constraints = new ArrayList<>();
    private final List<IntegrationRun> runs = new ArrayList<>();
    private final List<MappingApplication> applications = new ArrayList<>();

    @Override
    public synchronized IntegrationModel getOrCreate(TenantContext tenant, UUID projectId) {
        String key = tenant.value() + "/" + projectId;
        return modelsByProject.computeIfAbsent(key, k -> new IntegrationModel(
                UUID.randomUUID(), UUID.fromString(tenant.value()), projectId,
                Instant.now(), Instant.now(), null, false));
    }

    @Override
    public synchronized ModelBundle load(TenantContext tenant, UUID projectId) {
        IntegrationModel model = getOrCreate(tenant, projectId);
        return new ModelBundle(model,
                sources.stream().filter(s -> s.modelId().equals(model.id())).toList(),
                mappings.stream().filter(m -> m.modelId().equals(model.id())).toList(),
                constraints.stream().filter(c -> c.modelId().equals(model.id())).toList());
    }

    @Override
    public synchronized List<IntegrationModel> findAllModels(TenantContext tenant) {
        UUID tenantId = UUID.fromString(tenant.value());
        return modelsByProject.values().stream().filter(m -> m.tenantId().equals(tenantId)).toList();
    }

    @Override
    public synchronized ModelSource addSource(TenantContext tenant, ModelSource source) {
        return sources.stream()
                .filter(s -> s.modelId().equals(source.modelId())
                        && s.datasetId().equals(source.datasetId())
                        && s.classIri().equals(source.classIri()))
                .findFirst()
                .orElseGet(() -> {
                    sources.add(source);
                    return source;
                });
    }

    @Override
    public synchronized int removeSource(TenantContext tenant, UUID modelId, UUID datasetId) {
        int before = sources.size();
        sources.removeIf(s -> s.modelId().equals(modelId) && s.datasetId().equals(datasetId));
        return before - sources.size();
    }

    @Override
    public synchronized int downgradeExpandedWrappers(TenantContext tenant, UUID datasetId, String reason) {
        int n = 0;
        for (int i = 0; i < sources.size(); i++) {
            ModelSource s = sources.get(i);
            if (s.datasetId().equals(datasetId) && "EXPANDED".equals(s.expansionStatus())) {
                sources.set(i, new ModelSource(s.id(), s.tenantId(), s.modelId(), s.datasetId(),
                        s.classIri(), s.wrapperSql(), "OPAQUE", reason, s.wrapperEdited()));
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized void upsertSourceWrapper(TenantContext tenant, UUID modelId, UUID datasetId,
                                                 String classIri, String sql,
                                                 String expansionStatus, String expansionReason) {
        for (int i = 0; i < sources.size(); i++) {
            ModelSource s = sources.get(i);
            if (s.modelId().equals(modelId) && s.datasetId().equals(datasetId)
                    && s.classIri().equals(classIri)) {
                // V47: this port method is the user-edit path, so writing through it IS the edit.
                sources.set(i, new ModelSource(s.id(), s.tenantId(), modelId, datasetId, classIri, sql,
                        expansionStatus, expansionReason, true));
                return;
            }
        }
        sources.add(new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                modelId, datasetId, classIri, sql, expansionStatus, expansionReason, true));
    }

    /**
     * V47. The edited gate lives here, exactly as it does in the JPA adapter's own predicate:
     * a fake that re-seeded an edited row would let a test pass while the guarantee the flag
     * exists for was broken.
     */
    @Override
    public synchronized int reseedUneditedWrappers(TenantContext tenant, UUID datasetId,
                                                   Map<String, String> wrapperByClassIri) {
        UUID tenantId = UUID.fromString(tenant.value());
        int reseeded = 0;
        for (int i = 0; i < sources.size(); i++) {
            ModelSource s = sources.get(i);
            // Tenant-scoped like the JPA adapter's own predicate: this list is global across
            // tenants, so a fake that skipped the check would let a cross-tenant re-seed pass here
            // and fail only against Postgres.
            if (!s.tenantId().equals(tenantId) || !s.datasetId().equals(datasetId)
                    || s.wrapperEdited()) {
                continue;
            }
            String fresh = wrapperByClassIri.get(s.classIri());
            if (fresh == null || fresh.equals(s.wrapperSql())) {
                continue;
            }
            sources.set(i, new ModelSource(s.id(), s.tenantId(), s.modelId(), s.datasetId(),
                    s.classIri(), fresh, null, null, false));
            reseeded++;
        }
        return reseeded;
    }

    @Override
    public synchronized ModelMapping addMapping(TenantContext tenant, ModelMapping mapping) {
        return mappings.stream()
                .filter(m -> m.modelId().equals(mapping.modelId())
                        && m.kind().equals(mapping.kind())
                        && m.sourceIri().equals(mapping.sourceIri())
                        && m.targetIri().equals(mapping.targetIri()))
                .findFirst()
                .orElseGet(() -> {
                    mappings.add(mapping);
                    return mapping;
                });
    }

    @Override
    public synchronized void updateMappingStatus(TenantContext tenant, UUID mappingId, String status) {
        for (int i = 0; i < mappings.size(); i++) {
            ModelMapping m = mappings.get(i);
            if (m.id().equals(mappingId)) {
                mappings.set(i, new ModelMapping(m.id(), m.tenantId(), m.modelId(), m.kind(),
                        m.sourceIri(), m.targetIri(), m.label(), status, m.evidenceJson(),
                        m.author(), m.comment(), m.createdAt()));
                return;
            }
        }
    }

    @Override
    public synchronized void deleteMapping(TenantContext tenant, UUID mappingId) {
        mappings.removeIf(m -> m.id().equals(mappingId));
    }

    @Override
    public synchronized ModelConstraint addConstraint(TenantContext tenant, ModelConstraint constraint) {
        constraints.add(constraint);
        return constraint;
    }

    @Override
    public synchronized void setConstraintEnforced(TenantContext tenant, UUID constraintId, boolean enforced) {
        for (int i = 0; i < constraints.size(); i++) {
            ModelConstraint c = constraints.get(i);
            if (c.id().equals(constraintId)) {
                constraints.set(i, new ModelConstraint(c.id(), c.tenantId(), c.modelId(),
                        c.classIri(), c.expression(), enforced, c.createdAt(), c.origin()));
                return;
            }
        }
    }

    @Override
    public synchronized void deleteConstraint(TenantContext tenant, UUID constraintId) {
        constraints.removeIf(c -> c.id().equals(constraintId));
    }

    @Override
    public synchronized List<ModelConstraint> findEnforcedConstraints(TenantContext tenant) {
        UUID tenantId = UUID.fromString(tenant.value());
        return constraints.stream()
                .filter(c -> c.enforced() && c.tenantId().equals(tenantId))
                .toList();
    }

    // ── integration runs ────────────────────────────────────────────────────────

    @Override
    public synchronized IntegrationRun startRun(TenantContext tenant, UUID modelId,
                                                IntegrationRunMode mode,
                                                UUID baseVersionId, UUID datasetId) {
        Instant now = Instant.now();
        IntegrationRun run = new IntegrationRun(UUID.randomUUID(), UUID.fromString(tenant.value()),
                modelId, mode, IntegrationRunStatus.RUNNING, baseVersionId, datasetId,
                null, null, null, null, now, now);
        runs.add(run);
        return run;
    }

    @Override
    public synchronized void recordDraft(TenantContext tenant, UUID runId, UUID draftVersionId) {
        replaceRun(runId, r -> new IntegrationRun(r.id(), r.tenantId(), r.modelId(), r.mode(),
                IntegrationRunStatus.DRAFT, r.baseVersionId(), r.datasetId(), draftVersionId,
                r.promotedVersionId(), r.errorCode(), r.errorMessage(), r.startedAt(), Instant.now()));
    }

    @Override
    public synchronized void markRunPromoted(TenantContext tenant, UUID runId, UUID promotedVersionId) {
        replaceRun(runId, r -> new IntegrationRun(r.id(), r.tenantId(), r.modelId(), r.mode(),
                IntegrationRunStatus.PROMOTED, r.baseVersionId(), r.datasetId(), r.draftVersionId(),
                promotedVersionId, r.errorCode(), r.errorMessage(), r.startedAt(), Instant.now()));
    }

    @Override
    public synchronized void markRunFailed(TenantContext tenant, UUID runId, String code, String message) {
        replaceRun(runId, r -> new IntegrationRun(r.id(), r.tenantId(), r.modelId(), r.mode(),
                IntegrationRunStatus.FAILED, r.baseVersionId(), r.datasetId(), r.draftVersionId(),
                r.promotedVersionId(), code, message, r.startedAt(), Instant.now()));
    }

    @Override
    public synchronized Optional<IntegrationRun> findRun(TenantContext tenant, UUID runId) {
        return runs.stream().filter(r -> r.id().equals(runId)).findFirst();
    }

    @Override
    public synchronized Optional<IntegrationRun> latestPromotedRun(TenantContext tenant, UUID modelId) {
        return runs.stream()
                .filter(r -> r.modelId().equals(modelId) && r.status() == IntegrationRunStatus.PROMOTED)
                .max(Comparator.comparing(IntegrationRun::updatedAt));
    }

    @Override
    public synchronized Optional<IntegrationRun> findRunByDraftVersion(TenantContext tenant, UUID draftVersionId) {
        return runs.stream().filter(r -> draftVersionId.equals(r.draftVersionId())).findFirst();
    }

    @Override
    public synchronized Optional<IntegrationRun> latestDraftRun(TenantContext tenant, UUID modelId) {
        return runs.stream()
                .filter(r -> r.modelId().equals(modelId) && r.status() == IntegrationRunStatus.DRAFT)
                .max(Comparator.comparing(IntegrationRun::updatedAt));
    }

    @Override
    public synchronized List<MappingApplication> findMappingApplications(TenantContext tenant, UUID modelId) {
        return applications.stream().filter(a -> a.modelId().equals(modelId)).toList();
    }

    @Override
    public synchronized void supersedeDraftRuns(TenantContext tenant, UUID modelId) {
        for (IntegrationRun run : List.copyOf(runs)) {
            if (!run.modelId().equals(modelId) || run.status() != IntegrationRunStatus.DRAFT) {
                continue;
            }
            // Records written by an unpromoted draft never became durable — return them to PENDING.
            for (MappingApplication app : List.copyOf(applications)) {
                if (run.id().equals(app.runId())) {
                    upsertApplication(app.mappingId(), existing -> new MappingApplication(
                            app.id(), app.tenantId(), app.modelId(), app.mappingId(),
                            MappingApplicationState.PENDING, null, null, null, Instant.now()));
                }
            }
            replaceRun(run.id(), r -> new IntegrationRun(r.id(), r.tenantId(), r.modelId(), r.mode(),
                    IntegrationRunStatus.SUPERSEDED, r.baseVersionId(), r.datasetId(),
                    r.draftVersionId(), r.promotedVersionId(), r.errorCode(), r.errorMessage(),
                    r.startedAt(), Instant.now()));
        }
    }

    private void replaceRun(UUID runId, java.util.function.UnaryOperator<IntegrationRun> fn) {
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).id().equals(runId)) {
                runs.set(i, fn.apply(runs.get(i)));
                return;
            }
        }
        throw new IllegalArgumentException("run not found: " + runId);
    }

    // ── mapping application state ─────────────────────────────────────────────────

    @Override
    public synchronized void upsertMappingApplicationPending(TenantContext tenant, UUID modelId, UUID mappingId) {
        upsertApplication(mappingId, existing -> new MappingApplication(
                existing != null ? existing.id() : UUID.randomUUID(),
                UUID.fromString(tenant.value()), modelId, mappingId,
                MappingApplicationState.PENDING, null, null, null, Instant.now()));
    }

    @Override
    public synchronized void markMappingsApplied(TenantContext tenant, UUID runId, UUID versionId,
                                                 Collection<UUID> mappingIds) {
        for (UUID mappingId : mappingIds) {
            upsertApplication(mappingId, existing -> new MappingApplication(
                    existing != null ? existing.id() : UUID.randomUUID(),
                    UUID.fromString(tenant.value()), modelIdOf(existing, mappingId), mappingId,
                    MappingApplicationState.APPLIED, runId, versionId, null, Instant.now()));
        }
    }

    @Override
    public synchronized void markMappingsDeferred(TenantContext tenant, UUID runId,
                                                  Collection<UUID> mappingIds, String reason) {
        for (UUID mappingId : mappingIds) {
            upsertApplication(mappingId, existing -> new MappingApplication(
                    existing != null ? existing.id() : UUID.randomUUID(),
                    UUID.fromString(tenant.value()), modelIdOf(existing, mappingId), mappingId,
                    MappingApplicationState.DEFERRED, runId, null, reason, Instant.now()));
        }
    }

    @Override
    public synchronized Optional<MappingApplication> findMappingApplication(TenantContext tenant, UUID mappingId) {
        return applications.stream().filter(a -> a.mappingId().equals(mappingId)).findFirst();
    }

    @Override
    public synchronized List<ModelMapping> findMappingsUnderIri(TenantContext tenant, String iriPrefix) {
        // Mirrors the JPA adapter's LIKE 'prefix%' over both IRI columns, tenant-wide.
        return mappings.stream()
                .filter(m -> m.sourceIri().startsWith(iriPrefix) || m.targetIri().startsWith(iriPrefix))
                .toList();
    }

    @Override
    public synchronized List<ModelMapping> acceptedPendingMappings(TenantContext tenant, UUID modelId) {
        return acceptedMappingsInState(modelId, MappingApplicationState.PENDING);
    }

    @Override
    public synchronized List<ModelMapping> acceptedDeferredMappings(TenantContext tenant, UUID modelId) {
        return acceptedMappingsInState(modelId, MappingApplicationState.DEFERRED);
    }

    private List<ModelMapping> acceptedMappingsInState(UUID modelId, MappingApplicationState state) {
        return mappings.stream()
                .filter(m -> m.modelId().equals(modelId) && ModelMapping.ACCEPTED.equals(m.status()))
                .filter(m -> applications.stream().anyMatch(
                        a -> a.mappingId().equals(m.id()) && a.state() == state))
                .toList();
    }

    private UUID modelIdOf(@org.jspecify.annotations.Nullable MappingApplication existing, UUID mappingId) {
        if (existing != null) return existing.modelId();
        return mappings.stream().filter(m -> m.id().equals(mappingId)).findFirst()
                .map(ModelMapping::modelId).orElseThrow();
    }

    private void upsertApplication(UUID mappingId,
                                   java.util.function.Function<MappingApplication, MappingApplication> fn) {
        for (int i = 0; i < applications.size(); i++) {
            if (applications.get(i).mappingId().equals(mappingId)) {
                applications.set(i, fn.apply(applications.get(i)));
                return;
            }
        }
        applications.add(fn.apply(null));
    }

    // ── model-level flags ─────────────────────────────────────────────────────────

    @Override
    public synchronized void setActiveIntegrationRun(TenantContext tenant, UUID modelId, UUID runId) {
        mutateModel(modelId, m -> new IntegrationModel(m.id(), m.tenantId(), m.projectId(),
                m.createdAt(), Instant.now(), runId, m.rebuildRequired()));
    }

    @Override
    public synchronized void setRebuildRequired(TenantContext tenant, UUID modelId, boolean value) {
        mutateModel(modelId, m -> new IntegrationModel(m.id(), m.tenantId(), m.projectId(),
                m.createdAt(), Instant.now(), m.activeIntegrationRunId(), value));
    }

    private void mutateModel(UUID modelId, java.util.function.UnaryOperator<IntegrationModel> fn) {
        for (Map.Entry<String, IntegrationModel> e : modelsByProject.entrySet()) {
            if (e.getValue().id().equals(modelId)) {
                e.setValue(fn.apply(e.getValue()));
                return;
            }
        }
        throw new IllegalArgumentException("model not found: " + modelId);
    }
}

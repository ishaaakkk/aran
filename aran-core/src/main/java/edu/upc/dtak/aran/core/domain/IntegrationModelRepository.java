package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Persistence <em>port</em> for the integration model and its children.
 *
 * <p>Tenant-scoped exactly like {@link JobRepository} and {@link DatasetRepository}:
 * every call takes the {@link TenantContext} explicitly, and the adapter both names
 * the tenant in its predicates and leaves the shared Hibernate tenant filter in
 * place as defence in depth.
 *
 * <p>There is at most one model per {@code (tenant, project)}; {@link #getOrCreate}
 * makes the read-or-insert atomic from the caller's point of view.
 */
@NullMarked
public interface IntegrationModelRepository {

    /** The project's model, created empty on first access. */
    IntegrationModel getOrCreate(TenantContext tenant, UUID projectId);

    /** The full bundle (model + sources + mappings + constraints) for a project. */
    ModelBundle load(TenantContext tenant, UUID projectId);

    /**
     * Every model the tenant owns, one per project that has ever opened its model. The promote
     * guard's question — "did another project compile the classes this promote would remove?" —
     * is tenant-wide because the ACTIVE graph is (UI-OD2).
     */
    List<IntegrationModel> findAllModels(TenantContext tenant);

    // ── sources ───────────────────────────────────────────────────────────────

    /**
     * Insert a source-class row. Idempotent on the {@code (model, dataset, class)}
     * unique key — a repeated add is a no-op that returns the existing row.
     */
    ModelSource addSource(TenantContext tenant, ModelSource source);

    /**
     * Remove every source-class row a dataset contributes to a model.
     *
     * @return how many rows were removed — 0 for a dataset that was never enrolled, which
     *     {@code detachDataset} uses to keep a no-op detach from flagging a rebuild.
     */
    int removeSource(TenantContext tenant, UUID modelId, UUID datasetId);

    /**
     * Set the editable wrapper SQL of one source class (M6), together with the compile-time
     * verify-wrapper verdict computed for it (Phase 3A) — one write, so the sql and the verdict
     * describing it can never land in two different transactions and drift apart. {@code status}/
     * {@code reason} are {@code null} together exactly when {@code sql} was cleared or has never
     * been verified.
     *
     * <p><b>This is the user-edit path, and it marks the row {@code wrapper_edited} (V47).</b> It
     * backs {@code PUT /model/sources/{datasetId}/wrappers} and nothing else; the enrolment seed
     * goes through {@link #addSource} and a drift re-seed through
     * {@link #reseedUneditedWrappers}, neither of which sets the flag. If a second caller ever
     * needs to write a wrapper without claiming a person wrote it, it needs its own method rather
     * than this one — the flag is only worth anything while exactly one path sets it.
     */
    /**
     * T2: downgrade every {@code EXPANDED} expansion verdict a dataset's enrolments carry —
     * across all models — to {@code OPAQUE} with the given reason. Called on a successful
     * refresh: the certificate described bytes that are gone. Rows in any other status are
     * untouched.
     *
     * @return how many rows were downgraded
     */
    int downgradeExpandedWrappers(TenantContext tenant, UUID datasetId, String reason);

    void upsertSourceWrapper(TenantContext tenant, UUID modelId, UUID datasetId,
                             String classIri, @Nullable String sql,
                             @Nullable String expansionStatus, @Nullable String expansionReason);

    /**
     * Re-seed the stored wrapper of every <b>unedited</b> source row for {@code datasetId}, across
     * every model of the tenant, from a freshly bootstrapped graph (V47).
     *
     * <p>{@code wrapperByClassIri} maps source-class IRI to the {@code nx:wrapperSql} the newest
     * bootstrap produced for it. A row is updated only when its class appears in that map
     * <em>and</em> {@code wrapper_edited} is false; a class the map does not mention is left
     * alone (the bootstrap said nothing about it, which is not the same as "it has no wrapper").
     *
     * <p><b>The edited gate belongs here, not in the caller.</b> The whole point of the flag is
     * that a person's wrapper is never silently replaced, and a guarantee enforced by one
     * predicate inside the repository is one a caller cannot forget. Any verify-wrapper verdict on
     * a re-seeded row is cleared with it: the verdict described the previous text.
     *
     * <p>Tenant-wide rather than per-model for the same reason
     * {@link #findMappingsUnderIri} is: one dataset can be enrolled in several models, and a
     * source-schema change invalidates the stale seed in all of them at once.
     *
     * @return how many rows were re-seeded
     */
    int reseedUneditedWrappers(TenantContext tenant, UUID datasetId,
                               java.util.Map<String, String> wrapperByClassIri);

    // ── mappings ──────────────────────────────────────────────────────────────

    /**
     * Insert a mapping. Idempotent on {@code (model, kind, source, target)} — a
     * repeated add returns the row already there rather than violating the unique
     * key, so re-running {@code suggest} skips already-decided pairs.
     */
    ModelMapping addMapping(TenantContext tenant, ModelMapping mapping);

    void updateMappingStatus(TenantContext tenant, UUID mappingId, String status);

    void deleteMapping(TenantContext tenant, UUID mappingId);

    /**
     * Every mapping the tenant owns, across <em>all</em> its models, whose {@code sourceIri} or
     * {@code targetIri} starts with {@code iriPrefix} — the drift check's "what depends on this
     * source?" query.
     *
     * <p>Tenant-wide rather than per-project because a dataset can be enrolled in several models
     * and a schema change breaks all of them at once; scoping to one project would report a clean
     * bill of health for a source that has just broken someone else's.
     *
     * <p>Deliberately over-inclusive: a prefix also matches sibling IRIs that merely share a stem
     * ({@code …/papers} matches {@code …/papers_archive}). {@code DriftImpactAnalyzer} re-checks
     * each candidate precisely, and the asymmetry is intentional — an extra candidate costs a
     * comparison, a missed one costs a silent break.
     */
    List<ModelMapping> findMappingsUnderIri(TenantContext tenant, String iriPrefix);

    // ── constraints ─────────────────────────────────────────────────────────────

    ModelConstraint addConstraint(TenantContext tenant, ModelConstraint constraint);

    void setConstraintEnforced(TenantContext tenant, UUID constraintId, boolean enforced);

    void deleteConstraint(TenantContext tenant, UUID constraintId);

    /**
     * Every {@code enforced} constraint the tenant owns, across <em>all</em> its models
     * (M6). The active global schema is tenant-global — the query engine has no project in
     * scope at query time — so enforcement is keyed on the constraint's {@code classIri}
     * matching an integrated class in the active catalog, not on a project. Two projects in
     * one tenant that reuse the same integrated class IRI would therefore share the
     * constraint; that is the accepted v1 rule (a single active schema per tenant makes the
     * collision moot in practice).
     */
    List<ModelConstraint> findEnforcedConstraints(TenantContext tenant);

    // ── integration runs (Phase 4) ──────────────────────────────────────────────

    /** Start a new run in {@link IntegrationRunStatus#RUNNING}. */
    IntegrationRun startRun(TenantContext tenant, UUID modelId, IntegrationRunMode mode,
                            @Nullable UUID baseVersionId, @Nullable UUID datasetId);

    /** Move a run to {@link IntegrationRunStatus#DRAFT}, recording the draft version it produced. */
    void recordDraft(TenantContext tenant, UUID runId, UUID draftVersionId);

    /** Move a run to {@link IntegrationRunStatus#PROMOTED}, recording the promoted version. */
    void markRunPromoted(TenantContext tenant, UUID runId, UUID promotedVersionId);

    /** Move a run to {@link IntegrationRunStatus#FAILED} with an error code and message. */
    void markRunFailed(TenantContext tenant, UUID runId, String code, String message);

    Optional<IntegrationRun> findRun(TenantContext tenant, UUID runId);

    /** The most recently promoted run for a model, if any. */
    Optional<IntegrationRun> latestPromotedRun(TenantContext tenant, UUID modelId);

    /**
     * The run that produced a schema version, in any status. A version keeps its id when it is
     * promoted, so this also answers "which model's run made the ACTIVE graph". Empty for a
     * version no run produced — a rollback's re-materialised draft, or one written by the
     * operator directly.
     */
    Optional<IntegrationRun> findRunByDraftVersion(TenantContext tenant, UUID draftVersionId);

    /** The model's newest run still in {@link IntegrationRunStatus#DRAFT} — its promotable draft. */
    Optional<IntegrationRun> latestDraftRun(TenantContext tenant, UUID modelId);

    /** Every mapping application row of a model, so a reader can say which alignments are live. */
    List<MappingApplication> findMappingApplications(TenantContext tenant, UUID modelId);

    /**
     * Retire every outstanding {@link IntegrationRunStatus#DRAFT} run of the model to
     * {@link IntegrationRunStatus#SUPERSEDED}, returning each one's mapping applications to
     * {@link MappingApplicationState#PENDING}.
     *
     * <p>Called when a new run starts. Drafts all branch off the same promoted baseline, so a
     * newer run makes an unpromoted older draft an abandoned alternative — and the applications
     * it recorded never became durable. Without this, an abandoned draft would strand its
     * mappings as APPLIED and the next run would silently omit them.
     */
    void supersedeDraftRuns(TenantContext tenant, UUID modelId);

    // ── mapping application state (Phase 4) ──────────────────────────────────────

    /** Upsert a mapping's application row to {@link MappingApplicationState#PENDING}. */
    void upsertMappingApplicationPending(TenantContext tenant, UUID modelId, UUID mappingId);

    /** Mark the given mappings APPLIED against the promoted version, by the given run. */
    void markMappingsApplied(TenantContext tenant, UUID runId, UUID versionId, Collection<UUID> mappingIds);

    /** Mark the given mappings DEFERRED, recording the reason, by the given run. */
    void markMappingsDeferred(TenantContext tenant, UUID runId, Collection<UUID> mappingIds, String reason);

    Optional<MappingApplication> findMappingApplication(TenantContext tenant, UUID mappingId);

    /** ACCEPTED mappings whose application state is PENDING. */
    List<ModelMapping> acceptedPendingMappings(TenantContext tenant, UUID modelId);

    /** ACCEPTED mappings whose application state is DEFERRED. */
    List<ModelMapping> acceptedDeferredMappings(TenantContext tenant, UUID modelId);

    // ── model-level incremental flags (Phase 4) ─────────────────────────────────

    /** Point the model's active graph at a run (set on promotion), or clear it with null. */
    void setActiveIntegrationRun(TenantContext tenant, UUID modelId, @Nullable UUID runId);

    /** Set/clear the "a full rebuild is required" flag. */
    void setRebuildRequired(TenantContext tenant, UUID modelId, boolean value);
}

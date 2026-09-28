package edu.upc.dtak.aran.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.WrapperSqlValidator;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.JoinLabel;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.IntegrationRunMode;
import edu.upc.dtak.aran.core.domain.IntegrationRunStatus;
import edu.upc.dtak.aran.core.domain.MappingApplication;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelBundle;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ProgressReporter;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.IntegrationLinks;
import edu.upc.dtak.aran.core.util.Names;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The application-facing behaviour of the durable integration model.
 *
 * <p>Where the old flow held alignments in {@code AlignmentsView} state and shipped
 * them inline to the schema-integration operator's {@code integrate} operation, this
 * service owns them as tenant-scoped rows and turns integration into
 * {@code compile(model)}: it reconstructs <em>exactly</em> the alignment payload the
 * operator already consumes and drives the new {@code compile} operation (which is
 * {@code integrate} plus per-class wrapper overrides).
 *
 * <p>It calls the operator through {@link OperatorOrchestrator}, the same core-visible
 * registry {@code OperatorInvocationController} uses — so no separate {@code OperatorInvoker}
 * port is needed (the plan proposed one only as a fallback for the case where the registry
 * was application-only; it is not).
 */
@Service
@NullMarked
public class IntegrationModelService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationModelService.class);

    /** Same IRI as {@code WrapperVocab.WRAPPER_SQL} (bootstrapping module, not a core dependency). */
    private static final String WRAPPER_SQL_IRI = "http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql";
    /**
     * Same namespace as {@link #WRAPPER_SQL_IRI}, same reason it is inlined rather than shared:
     * this module does not depend on query-engine, which reads it back
     * ({@code MappingCatalogOperator}'s QUERY 1). Written by
     * {@code SchemaIntegrationOperator.applyWrapperOverrides} only when a wrapper's verdict is
     * present; absent on any source never verified, which {@link #readCompiledWrapperFacts} below
     * — like QUERY 1 — must default to "not EXPANDED" rather than treat as an error.
     */
    private static final String WRAPPER_EXPANSION_IRI = "http://www.essi.upc.edu/DTIM/NextiaDI/wrapperExpansion";

    /**
     * Same namespace as {@code BootstrapEngine.SOURCE_NS} (bootstrapping module, not a core
     * dependency — same inlining reason as {@link #WRAPPER_SQL_IRI}): every source-class and
     * source-property IRI of a dataset is minted under {@code SOURCE_NS + datasetId + "/"},
     * so that prefix selects exactly one dataset's IRIs — {@link #detachDataset} relies on it.
     */
    private static final String SOURCE_NS = "http://www.essi.upc.edu/DTIM/NextiaDI/source/";

    private final IntegrationModelRepository repository;
    private final GraphStore graphStore;
    private final OperatorOrchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final DatasetRepository datasetRepository;
    private final DataProductService dataProducts;
    /** Gap 2 follow-up: needed only to gate federated wrapper-output profiling (is this dataset
     * actually served federated?); null in a build (or test) assembled without the persistence
     * adapter — that path then WARN-skips. */
    private final @org.jspecify.annotations.Nullable DataRepositoryRepository dataRepositoryRepository;
    private final PromoteGuard promoteGuard;

    /** Pre-Gap-2-follow-up shape, kept for the many direct test constructions: no federated
     * profiling collaborator, so a federated class's compile-tail profiling degrades to the
     * honest WARN-skip it always was. */
    public IntegrationModelService(IntegrationModelRepository repository,
                                   GraphStore graphStore,
                                   OperatorOrchestrator orchestrator,
                                   ObjectMapper objectMapper,
                                   DatasetRepository datasetRepository,
                                   DataProductService dataProducts) {
        this(repository, graphStore, orchestrator, objectMapper, datasetRepository, dataProducts,
                java.util.Optional.empty());
    }

    /** Without the project port the promote guard's refusal cannot name the owning project. */
    public IntegrationModelService(IntegrationModelRepository repository,
                                   GraphStore graphStore,
                                   OperatorOrchestrator orchestrator,
                                   ObjectMapper objectMapper,
                                   DatasetRepository datasetRepository,
                                   DataProductService dataProducts,
                                   java.util.Optional<DataRepositoryRepository> dataRepositoryRepository) {
        this(repository, graphStore, orchestrator, objectMapper, datasetRepository, dataProducts,
                dataRepositoryRepository, java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IntegrationModelService(IntegrationModelRepository repository,
                                   GraphStore graphStore,
                                   OperatorOrchestrator orchestrator,
                                   ObjectMapper objectMapper,
                                   DatasetRepository datasetRepository,
                                   DataProductService dataProducts,
                                   java.util.Optional<DataRepositoryRepository> dataRepositoryRepository,
                                   java.util.Optional<ProjectRepository> projectRepository) {
        this.repository = repository;
        this.graphStore = graphStore;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
        this.datasetRepository = datasetRepository;
        this.dataProducts = dataProducts;
        this.dataRepositoryRepository = dataRepositoryRepository.orElse(null);
        this.promoteGuard = new PromoteGuard(repository, graphStore, projectRepository.orElse(null));
    }

    /** The project's model, created empty on first access, with all children loaded. */
    public ModelBundle getBundle(TenantContext tenant, UUID projectId) {
        repository.getOrCreate(tenant, projectId);
        return repository.load(tenant, projectId);
    }

    /**
     * Where the model stands against the tenant's schema, so the alignments page can say it after
     * a reload instead of forgetting it (audit J4-F2, J4-F5): the draft still awaiting promotion,
     * the version the model's baseline is (an incremental add is refused STALE_INCREMENTAL_BASE
     * unless that is the ACTIVE one), the ACTIVE version itself, and each mapping's application
     * state — which accepted alignment is live, in the draft, pending or deferred.
     */
    public IntegrationState integrationState(TenantContext tenant, UUID projectId) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);
        UUID activeRun = model.activeIntegrationRunId();
        UUID baselineVersionId = activeRun == null ? null
                : repository.findRun(tenant, activeRun).map(IntegrationRun::promotedVersionId).orElse(null);
        return new IntegrationState(
                repository.latestDraftRun(tenant, model.id()).orElse(null),
                baselineVersionId,
                graphStore.currentVersion(tenant),
                repository.findMappingApplications(tenant, model.id()));
    }

    /** @see #integrationState */
    public record IntegrationState(@Nullable IntegrationRun latestDraftRun,
                                   @Nullable UUID baselineVersionId,
                                   @Nullable GraphVersion activeVersion,
                                   List<MappingApplication> applications) {}

    // ─── sources ────────────────────────────────────────────────────────────

    /**
     * Enrol a dataset in the model: verify its bootstrapped source graph exists, then
     * seed one {@link ModelSource} row per {@code rdfs:Class} in that graph, carrying the
     * class's {@code nx:wrapperSql} as the editable wrapper.
     *
     * @return the seeded (or already-present) source rows
     * @throws ResourceNotFoundException if the dataset has no bootstrapped source graph
     */
    public List<ModelSource> addSource(TenantContext tenant, UUID projectId, UUID datasetId) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);

        Model sourceGraph = constructSourceGraph(tenant, datasetId);
        if (sourceGraph.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No bootstrapped source graph for dataset " + datasetId
                            + ". Run the bootstrapping operator first.");
        }

        Property wrapperSql = sourceGraph.createProperty(WRAPPER_SQL_IRI);
        List<ModelSource> seeded = new ArrayList<>();
        ResIterator classes = sourceGraph.listSubjectsWithProperty(RDF.type, RDFS.Class);
        while (classes.hasNext()) {
            Resource cls = classes.nextResource();
            if (!cls.isURIResource()) {
                continue;
            }
            Statement sqlStmt = cls.getProperty(wrapperSql);
            String sql = sqlStmt != null && sqlStmt.getObject().isLiteral()
                    ? sqlStmt.getString() : null;
            // wrapperEdited=false: this string is a copy of what bootstrap produced, not something
            // a person wrote, so a later bootstrap over a changed source schema may replace it
            // (V47). It is the distinction that whole defect turned on.
            ModelSource source = new ModelSource(
                    UUID.randomUUID(), tenantUuid(tenant), model.id(),
                    datasetId, cls.getURI(), sql, null, null, false);
            seeded.add(repository.addSource(tenant, source));
        }
        log.info("Enrolled dataset {} in model {} — {} source classes seeded",
                datasetId, model.id(), seeded.size());
        return seeded;
    }

    /**
     * Re-seed every UNEDITED stored wrapper for {@code datasetId} from the graph the bootstrap
     * just wrote (V47). Called after a successful (re)bootstrap — {@code DatasetService}'s import
     * and refresh both go through the same pipeline, and on a first import there is nothing
     * enrolled yet, so this is a no-op there.
     *
     * <p>The defect this closes: {@link #addSource} seeds {@code model_source.wrapperSql} once, at
     * enrolment, and every later compile pushes that stored copy back over the freshly bootstrapped
     * one ({@code SchemaIntegrationOperator.applyWrapperOverrides}). A refresh that renames or
     * drops a source column regenerates the graph's wrapper correctly — and the next compile
     * immediately overwrote it with the enrolment-time copy, so every query over the class failed
     * with DuckDB's binder error naming a column the snapshot no longer had. An unedited seed
     * carries no user intent, so following the source is strictly better than freezing.
     *
     * <p>Best-effort by contract, like the compile tail's profiling: a re-seed problem must not
     * fail the import/refresh job that produced a perfectly good new snapshot. What it costs is
     * that the stale-seed defect survives until the next refresh, which is what a WARN is for.
     *
     * @return how many rows were re-seeded
     */
    public int reseedUneditedWrappers(TenantContext tenant, UUID datasetId) {
        try {
            Model sourceGraph = constructSourceGraph(tenant, datasetId);
            if (sourceGraph.isEmpty()) {
                return 0;
            }
            Property wrapperSql = sourceGraph.createProperty(WRAPPER_SQL_IRI);
            Map<String, String> byClass = new LinkedHashMap<>();
            for (Statement st : sourceGraph.listStatements(
                    null, wrapperSql, (RDFNode) null).toList()) {
                if (st.getSubject().isURIResource() && st.getObject().isLiteral()) {
                    byClass.put(st.getSubject().getURI(), st.getString());
                }
            }
            if (byClass.isEmpty()) {
                return 0;
            }
            int reseeded = repository.reseedUneditedWrappers(tenant, datasetId, byClass);
            if (reseeded > 0) {
                log.info("Re-seeded {} unedited wrapper(s) for dataset {} from the regenerated"
                        + " source graph", reseeded, datasetId);
            }
            return reseeded;
        } catch (RuntimeException e) {
            log.warn("Could not re-seed unedited wrappers for dataset {} — a later compile may"
                    + " push a stale enrolment-time wrapper over the regenerated one", datasetId, e);
            return 0;
        }
    }

    public void removeSource(TenantContext tenant, UUID projectId, UUID datasetId) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);
        repository.removeSource(tenant, model.id(), datasetId);
        // Removing a source drops integrated material underneath any promoted baseline.
        markRebuildIfBaselineExists(tenant, model.id());
    }

    /**
     * T1.8 — the model-side half of deleting a dataset (or its repository): remove the
     * enrolment, REJECT every mapping referencing the dataset's IRIs, flag the affected models
     * for rebuild, and clear the bootstrap's source graph. Without this, the next compile pushes
     * the orphaned enrolment's wrapper into the schema graph as a class over a dataset that no
     * longer exists — queries over it fail at snapshot resolution with no hint why — and the
     * orphaned ACCEPTED mappings are silently re-deferred on every run forever
     * (audit MIGRATIONS-3 + JOIN_DISCOVERY-5 + SCHEMA_BOOTSTRAPPING-10). The `model_source`
     * rows themselves also die at the database via V37's ON DELETE CASCADE — this method is the
     * semantic cleanup (mappings, flags, graph) the cascade cannot do, called BEFORE the row
     * delete so the enrolment count is still observable. Idempotent: detaching a never-enrolled
     * dataset touches nothing and flags nothing.
     */
    public void detachDataset(TenantContext tenant, UUID projectId, UUID datasetId) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);
        int removedSources = repository.removeSource(tenant, model.id(), datasetId);
        if (removedSources > 0) {
            markRebuildIfBaselineExists(tenant, model.id());
        }

        // Tenant-wide by design: findMappingsUnderIri is not model-scoped, and a mapping in any
        // model that references the dead dataset's IRIs is equally orphaned. REJECT (not delete)
        // keeps the editorial record, same as a human declining the suggestion.
        Set<UUID> touchedModels = new LinkedHashSet<>();
        for (ModelMapping mapping : repository.findMappingsUnderIri(tenant, SOURCE_NS + datasetId + "/")) {
            if (!ModelMapping.REJECTED.equals(mapping.status())) {
                repository.updateMappingStatus(tenant, mapping.id(), ModelMapping.REJECTED);
                touchedModels.add(mapping.modelId());
            }
        }
        // Unconditional per touched model, matching repairRenames: mapping surgery invalidates
        // whatever the model last compiled.
        touchedModels.forEach(modelId -> repository.setRebuildRequired(tenant, modelId, true));

        graphStore.clearGraph(tenant, "urn:aran:source:" + datasetId);

        if (removedSources > 0 || !touchedModels.isEmpty()) {
            log.info("Detached dataset {}: {} enrolment(s) removed, mappings rejected in {} model(s)",
                    datasetId, removedSources, touchedModels.size());
        }
    }

    /**
     * T2 (audit WRAPPER_VERIFY-1): a successful dataset refresh invalidates every {@code EXPANDED}
     * expansion verdict the dataset's enrolments carry — the certificate fingerprinted bytes that
     * are now gone. The rows go retryable-{@code OPAQUE} ("stale:" prefix, which
     * {@code verdictNeedsRefresh} picks up at the next compile), so the badge is honest
     * immediately and the verdict re-earns itself when a compile can check it. The ACTIVE graph's
     * own {@code nx:wrapperExpansion} stamp stays whatever the last compile wrote until the next
     * promote — a documented residual: the planner's own re-probe gates what an expansion may do,
     * and the graph's wrapper text and the snapshot moved together, so this is a badge-honesty
     * window, not a wrong-rows one.
     */
    public void markWrapperVerdictsStale(TenantContext tenant, UUID datasetId) {
        int downgraded = repository.downgradeExpandedWrappers(tenant, datasetId,
                "stale: the snapshot was refreshed after this verdict was computed — it will be"
                        + " re-verified at the next compile");
        if (downgraded > 0) {
            log.info("Downgraded {} EXPANDED wrapper verdict(s) of dataset {} after refresh",
                    downgraded, datasetId);
        }
    }

    public void setWrapper(TenantContext tenant, UUID projectId, UUID datasetId,
                           String classIri, @Nullable String sql) {
        // The stored string is concatenated verbatim into a CREATE VIEW and executed by
        // DuckDB (DuckDbExecutor.buildDdl), so it must pass the same AST whitelist as the
        // DatasetService path. Without this, any authenticated tenant member could store
        // `SELECT * FROM read_csv_auto('/etc/passwd')` and read it back through any query.
        // A null wrapper clears the override and is not SQL, so it is left alone.
        if (sql != null) {
            WrapperSqlValidator.validate(sql);
        }
        IntegrationModel model = repository.getOrCreate(tenant, projectId);

        // The verdict is computed here, once, and saved in the same write as the sql it
        // describes (see upsertSourceWrapper's javadoc) — never a second, separately-fallible
        // write. Clearing the wrapper clears any verdict about it too: there is nothing left to
        // have been verified.
        String expansionStatus = null;
        String expansionReason = null;
        if (sql != null) {
            WrapperVerdict verdict = excuseColumnsTheSourceDropped(tenant, datasetId, classIri,
                    verifyWrapper(tenant, datasetId, classIri, sql));
            // The class's u_ view selects these by name, so the save would succeed and every
            // query over the class would then fail with a binder error. Refused here, where the
            // person who renamed or dropped the alias is still looking at it.
            if (!verdict.missingColumns().isEmpty()) {
                throw new IllegalArgumentException("the wrapper no longer outputs "
                        + verdict.missingColumns().size() + " column(s) its class reads: "
                        + String.join(", ", verdict.missingColumns())
                        + " — keep each one aliased exactly as the generated wrapper does"
                        + " (AS \"<property IRI>\").");
            }
            // verify-wrapper reports this only when its snapshot bound, is the dataset's current
            // copy, and DuckDB named the text's own binding (a misspelt raw column, an unknown
            // function) as the failure — so every query over the class would fail the same way.
            if (verdict.bindError() != null) {
                throw new IllegalArgumentException("the wrapper does not run over its dataset's"
                        + " data: " + verdict.bindError());
            }
            expansionStatus = verdict.status();
            expansionReason = verdict.reason();
        }

        repository.upsertSourceWrapper(tenant, model.id(), datasetId, classIri, sql,
                expansionStatus, expansionReason);
        // A wrapper edit under a promoted baseline changes already-integrated material.
        // Conservative proxy for "already-integrated source": a baseline exists.
        markRebuildIfBaselineExists(tenant, model.id());
    }

    /**
     * The verdict {@link #verifyWrapper} computes, persisted alongside the wrapper it verified.
     *
     * <p>{@code reason} is capped here — the one place every construction site funnels through —
     * to {@link #REASON_MAX_LENGTH} characters, matching V30's {@code expansion_reason
     * VARCHAR(512)} column width; if that column width ever changes, update
     * {@link #REASON_MAX_LENGTH} alongside it. Every reason text below is free-form and
     * unbounded — an operator error message, the operator's own passthrough reason, or a caught
     * exception's message — and this is a {@code setWrapper} contract, not a display nicety: an
     * oversized diagnostic must never make Postgres reject the row, which would fail the save
     * for the sql edit itself over nothing but how long the explanation of a verify problem
     * happened to be — exactly the scenario "the save must always succeed" exists to cover.
     */
    private record WrapperVerdict(String status, @Nullable String reason,
                                  List<String> missingColumns,
                                  Map<String, String> missingSourceColumns,
                                  @Nullable String bindError) {
        private static final int REASON_MAX_LENGTH = 512;

        private WrapperVerdict {
            if (reason != null && reason.length() > REASON_MAX_LENGTH) {
                reason = reason.substring(0, REASON_MAX_LENGTH - 1) + "…";
            }
            missingColumns = List.copyOf(missingColumns);
            missingSourceColumns = Map.copyOf(missingSourceColumns);
        }

        private WrapperVerdict(String status, @Nullable String reason, List<String> missingColumns) {
            this(status, reason, missingColumns, Map.of(), null);
        }

        private WrapperVerdict(String status, @Nullable String reason) {
            this(status, reason, List.of());
        }
    }

    /**
     * Ask {@code query-engine}'s {@code verify-wrapper} operation whether {@code sql} plans
     * safely over its real snapshot — the same orchestrator round-trip {@link #validateExpression}
     * already uses for {@code validate-constraint}, because {@code query-engine} is a
     * Calcite/DuckDB module core cannot depend on directly. ({@code QueryEngineOperator
     * .verifyWrapper}'s own javadoc names this exact call site: "IntegrationModelService's
     * wrapper save path drives this through the orchestrator... the same way validate-constraint
     * backs the constraint editor.")
     *
     * <p>{@code verify-wrapper} is designed to never fail as an operator — every operational
     * problem it hits folds into an {@code OPAQUE} verdict with a reason — but from here the
     * orchestrator call is still a registry/dispatch boundary, so a {@code success=false} result
     * (defensive: today's operator never returns one) or an outright thrown exception both
     * degrade to the same {@code OPAQUE} shape rather than ever turning a wrapper save into a
     * failed request. A user who cannot get a verdict right now must still be able to save
     * their SQL.
     */
    /**
     * Re-verifies wrapper expansion verdicts (V41, V47)
     * at compile, returning the sources with their fresh verdicts so the caller's payload never
     * carries a stale one. An {@code EXPANDED} verdict is a round-trip fingerprint of the
     * snapshot present when the wrapper was SAVED — a refresh or an engine bump invalidates it
     * silently, and the graph stamp is a copy, not a re-verify. Three shapes are (re)verified:
     *
     * <ul>
     *   <li>{@code EXPANDED} — the staleness case. If the re-verify itself cannot run, the row is
     *       DOWNGRADED to {@code OPAQUE}/"stale: could not re-verify…" rather than left standing:
     *       an expansion certificate nobody could check is not a certificate.</li>
     *   <li>{@code null} — bootstrap-seeded wrappers: only an edited save ever verified, so the
     *       common generated {@code SELECT * FROM ${SNAPSHOT}} never earned a verdict at all.</li>
     *   <li>{@code OPAQUE} with a retryable reason ("verify unavailable…", "stale:…") — the class
     *       may simply not have been in the ACTIVE catalog yet (first enrolment pre-promote, and
     *       V41's residue rows); after a promote the retry converges to a real verdict.</li>
     * </ul>
     *
     * <p>A content-determined {@code OPAQUE} (round-trip mismatch, aggregate shape, forbidden
     * construct) is NOT re-verified: opaque is the safe verdict, and paying a snapshot resolve
     * plus two full scans per compile to maybe upgrade one is not worth the compile latency.
     * Failures never fail the compile: {@link #verifyWrapper} folds everything into an OPAQUE
     * verdict, and a persist failure keeps the old row with a WARN.
     */
    private List<ModelSource> refreshWrapperVerdicts(TenantContext tenant, List<ModelSource> sources) {
        List<ModelSource> out = new ArrayList<>(sources.size());
        int reverified = 0;
        long startedAt = System.nanoTime();
        for (ModelSource s : sources) {
            if (s.wrapperSql() == null || s.wrapperSql().isBlank() || !verdictNeedsRefresh(s)) {
                out.add(s);
                continue;
            }
            try {
                WrapperVerdict fresh = excuseColumnsTheSourceDropped(tenant, s.datasetId(),
                        s.classIri(), verifyWrapper(tenant, s.datasetId(), s.classIri(), s.wrapperSql()));
                String status = fresh.status();
                String reason = fresh.reason();
                boolean unavailable = reason != null && reason.startsWith("verify unavailable");
                if ("EXPANDED".equals(s.expansionStatus()) && unavailable) {
                    // Keep the retryable "stale:" prefix (see verdictNeedsRefresh) so a later
                    // compile — after the outage, after the promote — picks it up again.
                    reason = new WrapperVerdict("OPAQUE", "stale: could not re-verify — " + reason).reason();
                    status = "OPAQUE";
                }
                repository.upsertSourceWrapper(tenant, s.modelId(), s.datasetId(), s.classIri(),
                        s.wrapperSql(), status, reason);
                // `wrapperEdited` is carried through unchanged (V47): a re-verify re-reads the
                // SAME text against a possibly-newer snapshot, so it can change the verdict but
                // never who wrote the wrapper. Dropping the flag here would silently make every
                // hand-edited wrapper re-seedable at the next drift refresh.
                out.add(new ModelSource(s.id(), s.tenantId(), s.modelId(), s.datasetId(),
                        s.classIri(), s.wrapperSql(), status, reason, s.wrapperEdited()));
                reverified++;
            } catch (RuntimeException e) {
                log.warn("Wrapper verdict refresh failed for dataset {} class {} — keeping the"
                        + " stored verdict for this compile", s.datasetId(), s.classIri(), e);
                out.add(s);
            }
        }
        if (reverified > 0) {
            log.info("Re-verified {} wrapper verdict(s) at compile in {} ms", reverified,
                    (System.nanoTime() - startedAt) / 1_000_000);
        }
        return out;
    }

    /** See {@link #refreshWrapperVerdicts}: EXPANDED, never-verified, and retryable-OPAQUE rows. */
    private static boolean verdictNeedsRefresh(ModelSource s) {
        String status = s.expansionStatus();
        if (status == null || "EXPANDED".equals(status)) {
            return true;
        }
        String reason = s.expansionReason();
        return "OPAQUE".equals(status) && reason != null
                && (reason.startsWith("verify unavailable") || reason.startsWith("stale:"));
    }

    private WrapperVerdict verifyWrapper(TenantContext tenant, UUID datasetId, String classIri, String sql) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("datasetId", datasetId.toString());
            params.put("classIri", classIri);
            params.put("sql", sql);
            OperatorRequest request = new OperatorRequest(tenant.value(), null, "verify-wrapper", params);
            OperatorResult result = orchestrator.execute("query-engine", request);
            if (!result.success()) {
                return new WrapperVerdict("OPAQUE", "verify unavailable: " + errorDetail(result));
            }
            String status = result.data().getOrDefault("status", "OPAQUE");
            String reason = result.data().get("reason");
            String missing = result.data().get("missingColumns");
            String bindError = result.data().get("bindError");
            return new WrapperVerdict(status, reason == null || reason.isBlank() ? null : reason,
                    missing == null || missing.isBlank() ? List.of() : List.of(missing.split("\n")),
                    sourceColumnsOf(result.data().get("missingSourceColumns")),
                    bindError == null || bindError.isBlank() ? null : bindError);
        } catch (RuntimeException e) {
            log.warn("verify-wrapper invocation failed for dataset {} class {} — saving as OPAQUE",
                    datasetId, classIri, e);
            return new WrapperVerdict("OPAQUE",
                    "verify unavailable: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * {@code verify-wrapper}'s {@code missingSourceColumns} (missing column IRI → raw source
     * column). Unreadable means no column can be excused, so the save refuses as it would have.
     */
    private Map<String, String> sourceColumnsOf(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            log.warn("verify-wrapper returned unreadable missingSourceColumns: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String errorDetail(OperatorResult result) {
        if (result.errorMessage() != null) {
            return result.errorMessage();
        }
        return result.errorCode() != null ? result.errorCode() : "unknown error";
    }

    /** Set {@code rebuild_required} when the model has an established (promoted) baseline. */
    private void markRebuildIfBaselineExists(TenantContext tenant, UUID modelId) {
        if (repository.latestPromotedRun(tenant, modelId).isPresent()) {
            repository.setRebuildRequired(tenant, modelId, true);
        }
    }

    // ─── mappings ─────────────────────────────────────────────────────────────

    /**
     * Add a manual, already-decided (ACCEPTED) mapping.
     *
     * <p>Audit JOIN_DISCOVERY-4 — the label is mandatory here for every kind, not merely
     * recommended. {@link #acceptedAlignmentsJson} serialises a null label as {@code ""} and the
     * schema-integration operator's {@code Alignment} constructor rejects a blank one, so a single
     * label-less accept fails the <em>whole</em> compile with an error naming no mapping, on every
     * future attempt, until the row is deleted by hand. {@code AlignmentsView} disables its Add
     * button while the label box is empty, so nothing the UI can do reaches this — an API client
     * that omits the field does, and it must get a 400 now rather than a bricked model later.
     */
    public ModelMapping addMapping(TenantContext tenant, UUID projectId, String kind,
                                   String sourceIri, String targetIri,
                                   @Nullable String label, @Nullable UUID author,
                                   @Nullable String comment) {
        if (!MappingKinds.isValid(kind)) {
            throw new IllegalArgumentException(
                    "kind must be one of class|datatype|object|join — got: " + kind);
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException(
                    "label must not be blank — an unlabelled mapping cannot be compiled");
        }
        if (MappingKinds.JOIN.equals(kind)) {
            rejectJoinMintingOneIri(label);
        }
        IntegrationModel model = repository.getOrCreate(tenant, projectId);
        rejectMisshapenEnds(tenant, projectId, kind, sourceIri, targetIri);
        rejectDuplicateLabel(tenant, projectId, kind, label, sourceIri, targetIri);
        ModelMapping mapping = new ModelMapping(
                UUID.randomUUID(), tenantUuid(tenant), model.id(), kind,
                sourceIri, targetIri, label, ModelMapping.ACCEPTED,
                null, author, comment, Instant.now());
        ModelMapping persisted = repository.addMapping(tenant, mapping);
        // A newly-accepted mapping is PENDING application until a run integrates it.
        repository.upsertMappingApplicationPending(tenant, model.id(), persisted.id());
        return persisted;
    }

    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";

    /**
     * J4-F6 — the two ends of an alignment must have the shape its kind needs: a class
     * alignment needs a class on both sides, a datatype, object or join alignment a property on
     * both sides, and an {@code xsd:} datatype is never an end at all. The Alignments view let a
     * class node be aligned with a property as "datatype"; the row saved, and every rebuild from
     * then on failed {@code INVALID_ALIGNMENT} with a full IRI — the schema-integration
     * operator's M-compliance check ({@code SchemaIntegrator.validateMCompliance}, paper §5.1).
     *
     * <p>Each end's shape is read exactly as the view draws it — {@link SourceGraphSerializer}'s
     * node type over the enrolled source graphs, the same typing the creator's offer of kinds is
     * computed from — so the door refuses precisely what the creator no longer offers. It is also
     * the reason this reads no triples itself: aran-core's Jena use is frozen debt (H-5,
     * {@code ArchitectureTest}), and the serializer is where the source graph's typing already
     * lives. An end that is no node of any enrolled source graph is left alone, as the operator
     * defers a resource that is "not yet present": it may be an integrated IRI (I2/I3 reuse folds
     * a new source into an existing concept) or belong to a source bootstrapped later. The compile
     * stays the authority; this moves its failure to the request that causes it.
     *
     * <p>Only the graphs that can hold an end are read: every source IRI of a dataset is minted
     * under {@code SOURCE_NS + datasetId + "/"} (the invariant {@link #detachDataset} relies on),
     * so at most two whole-graph CONSTRUCTs run per call, and none for an integrated end. Reading
     * every enrolled graph made discovery's accept loop cost sources × mappings reads.
     */
    private void rejectMisshapenEnds(TenantContext tenant, UUID projectId, String kind,
                                     String sourceIri, String targetIri) {
        for (String iri : List.of(sourceIri, targetIri)) {
            if (iri.startsWith(XSD_NS)) {
                throw new IllegalArgumentException(iri + " is a datatype (xsd), not an element that"
                        + " can be aligned. Align a class with a class, or a property with a property.");
            }
        }
        Set<UUID> owners = new LinkedHashSet<>();
        for (ModelSource source : getBundle(tenant, projectId).sources()) {
            String prefix = SOURCE_NS + source.datasetId() + "/";
            if (sourceIri.startsWith(prefix) || targetIri.startsWith(prefix)) {
                owners.add(source.datasetId());
            }
        }
        Map<String, String> shapeOf = new HashMap<>();
        for (UUID datasetId : owners) {
            for (SourceGraphSerializer.GraphNode node : SourceGraphSerializer
                    .build(constructSourceGraph(tenant, datasetId), false).nodes()) {
                shapeOf.putIfAbsent(node.iri(), node.type());
            }
        }
        boolean wantsClass = MappingKinds.CLASS.equals(kind);
        for (String iri : List.of(sourceIri, targetIri)) {
            String shape = shapeOf.get(iri);
            if (shape == null) {
                continue;
            }
            boolean isClass = "class".equals(shape);
            if (wantsClass && !isClass) {
                throw new IllegalArgumentException(iri + " is a property, but a class alignment"
                        + " needs a class on both sides. Align a class with a class.");
            }
            if (!wantsClass && isClass) {
                throw new IllegalArgumentException(iri + " is a class, but a " + kind
                        + " alignment needs a property on both sides. Align a property with a property.");
            }
        }
    }

    /**
     * S5-3 — the alignment label is not decoration, it IS the integrated IRI
     * ({@code SchemaIntegrator.mintIri} = {@code INTEGRATION_NS + iriFragment(label.trim())}), so
     * within one kind it is a key over the whole integrated graph. Two ACCEPTED mappings sharing
     * one label used to fold two unrelated concepts into a single IntegratedResource whose
     * {@code int_} view is a UNION ALL of unrelated tables — wrong rows, silently.
     *
     * <p>The integrator now refuses that mint, so the model would fail to compile either way; this
     * check exists so the refusal arrives at the door, on the request that creates the duplicate,
     * rather than as a failed run over a model the user believes is saved and correct.
     *
     * <p>Scope, deliberately three-fold:
     * <ul>
     *   <li><em>Within a kind</em> — a class and a property both named {@code Name} mint into
     *       disjoint uses; the paper's own §5.1 does exactly that.</li>
     *   <li><em>Over ACCEPTED rows only</em>, since {@code acceptedAlignmentsJson} is what a
     *       compile sends. A SUGGESTED candidate has minted nothing yet; a REJECTED row has given
     *       its label back.</li>
     *   <li><em>Only when the endpoints are disjoint.</em> Reusing a label to fold a THIRD source
     *       into an existing concept is not a collision, it is how the model grows: the integrator
     *       sees one side already carrying an effective IR and takes the I2/I3 reuse branch, which
     *       mints nothing (the paper's §5.2 folds {@code Full_name} under the existing {@code Name}
     *       IR this way, and every incremental add does). A same-label mapping that shares an
     *       endpoint IRI with an existing one is therefore accepted; one whose endpoints are
     *       disjoint from every same-label mapping describes a <em>different</em> concept, hits I1,
     *       and mints an IRI the first alignment already owns. That is the case refused here.
     *       Same-label mappings are kept connected by this very rule, so membership in the union
     *       of their endpoints is the connected-component test.</li>
     * </ul>
     */
    private void rejectDuplicateLabel(TenantContext tenant, UUID projectId, String kind,
                                      String label, String sourceIri, String targetIri) {
        String candidate = label.trim();
        Set<String> endpointsOfSameLabel = new LinkedHashSet<>();
        for (ModelMapping existing : getBundle(tenant, projectId).mappings()) {
            if (!kind.equals(existing.kind())) continue;
            if (!ModelMapping.ACCEPTED.equals(existing.status())) continue;
            String other = existing.label();
            if (other != null && candidate.equals(other.trim())) {
                endpointsOfSameLabel.add(existing.sourceIri());
                endpointsOfSameLabel.add(existing.targetIri());
            }
        }
        if (endpointsOfSameLabel.isEmpty()) return;
        if (endpointsOfSameLabel.contains(sourceIri) || endpointsOfSameLabel.contains(targetIri)) {
            return; // extends the concept that label already names — I2/I3 reuse, no mint.
        }
        throw new IllegalArgumentException(
                "a " + kind + " mapping labelled '" + candidate + "' already exists in this model"
                        + " over unrelated resources — the label becomes the integrated IRI, so"
                        + " these two would silently become one integrated resource over two"
                        + " different concepts. Choose a distinct label, or align this mapping"
                        + " against a resource the existing one already covers.");
    }

    /**
     * A join mints its datatype property {@code P_l} from the label and its relationship
     * {@code P_s} from the relationship name; named alike, both become one resource, the model
     * compiles, and every query then fails as an ambiguous join property. The integrator refuses
     * the mint too — this puts the refusal on the request that creates the mapping, as
     * {@link #rejectDuplicateLabel} does for D2's cross-mapping collision.
     */
    private static void rejectJoinMintingOneIri(String packedLabel) {
        JoinLabel join = JoinLabel.parse(packedLabel.trim());
        if (join.mintsOneIri()) {
            throw new IllegalArgumentException("the join label '" + join.label().trim()
                    + "' and its relationship name '" + join.propertyName().trim()
                    + "' would mint the same integrated IRI, but the join's datatype property and"
                    + " its relationship must be two resources — give the relationship a name"
                    + " that differs from the label.");
        }
    }

    public void setMappingStatus(TenantContext tenant, UUID projectId, UUID mappingId, String status) {
        if (!ModelMapping.isValidStatus(status)) {
            throw new IllegalArgumentException(
                    "status must be one of SUGGESTED|ACCEPTED|REJECTED — got: " + status);
        }
        IntegrationModel model = requireMapping(tenant, projectId, mappingId);
        // Rejecting a currently-APPLIED mapping removes integrated material → rebuild.
        if (ModelMapping.REJECTED.equals(status) && isApplied(tenant, mappingId)) {
            repository.setRebuildRequired(tenant, model.id(), true);
        }
        repository.updateMappingStatus(tenant, mappingId, status);
        // Accepting a mapping (re)enters it into the PENDING application queue.
        if (ModelMapping.ACCEPTED.equals(status)) {
            repository.upsertMappingApplicationPending(tenant, model.id(), mappingId);
        }
    }

    public void deleteMapping(TenantContext tenant, UUID projectId, UUID mappingId) {
        IntegrationModel model = requireMapping(tenant, projectId, mappingId);
        // Deleting a currently-APPLIED mapping removes integrated material → rebuild.
        if (isApplied(tenant, mappingId)) {
            repository.setRebuildRequired(tenant, model.id(), true);
        }
        repository.deleteMapping(tenant, mappingId);
    }

    /**
     * Resolve a by-id mapping inside the project the URL names, returning that project's model.
     *
     * <p>The repository's by-id writes match on tenant and id only, so without this a project the
     * caller can see is a door to every mapping in the tenant — and {@code GET /api/jobs} used to
     * hand out other projects' {@code appliedMappingIds}. "Not this project's" is
     * {@link ResourceNotFoundException}, 404, the answer {@code DataProductService.get(tenant,
     * projectId, id)} gives a product (S2-1).
     */
    private IntegrationModel requireMapping(TenantContext tenant, UUID projectId, UUID mappingId) {
        ModelBundle bundle = repository.load(tenant, projectId);
        if (bundle.mappings().stream().noneMatch(m -> m.id().equals(mappingId))) {
            throw new ResourceNotFoundException("mapping " + mappingId + " not found");
        }
        return bundle.model();
    }

    /** {@link #requireMapping} for constraints: the row, when it is the URL project's. */
    private ModelConstraint requireConstraint(TenantContext tenant, UUID projectId, UUID constraintId) {
        return repository.load(tenant, projectId).constraints().stream()
                .filter(c -> c.id().equals(constraintId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "constraint " + constraintId + " not found"));
    }

    private boolean isApplied(TenantContext tenant, UUID mappingId) {
        return repository.findMappingApplication(tenant, mappingId)
                .map(a -> a.state() == edu.upc.dtak.aran.core.domain.MappingApplicationState.APPLIED)
                .orElse(false);
    }

    /**
     * Ask the schema-integration operator to suggest datatype-property alignments for a
     * pair of enrolled datasets, and persist each as a {@code SUGGESTED} mapping with the
     * suggestion's scores kept as {@code evidence}. Suggestions for a pair already
     * decided (an existing row on the same {@code (kind, source, target)}) are skipped by
     * the repository's idempotent insert.
     *
     * @return the persisted suggestion rows (existing rows are returned unchanged)
     */
    public List<ModelMapping> suggest(TenantContext tenant, UUID projectId,
                                      UUID leftDatasetId, UUID rightDatasetId,
                                      @Nullable UUID author) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("datasetIdA", leftDatasetId.toString());
        params.put("datasetIdB", rightDatasetId.toString());
        OperatorRequest request = new OperatorRequest(
                tenant.value(), author, "suggest-alignments", params);
        OperatorResult result = orchestrator.execute("schema-integration", request);
        if (!result.success()) {
            throw new JobFailureException(
                    result.errorCode() != null ? result.errorCode() : "SUGGEST_FAILED",
                    result.errorMessage() != null ? result.errorMessage() : "suggestion failed");
        }

        List<ModelMapping> persisted = new ArrayList<>();
        String candidatesJson = result.data().getOrDefault("candidates", "[]");
        try {
            JsonNode arr = objectMapper.readTree(candidatesJson);
            for (JsonNode c : arr) {
                String iriA = c.path("iriA").asText("");
                String iriB = c.path("iriB").asText("");
                if (iriA.isBlank() || iriB.isBlank()) {
                    continue;
                }
                String label = c.path("label").asText(null);
                ModelMapping suggestion = new ModelMapping(
                        UUID.randomUUID(), tenantUuid(tenant), model.id(),
                        MappingKinds.DATATYPE, iriA, iriB, label,
                        ModelMapping.SUGGESTED, c.toString(), author, null, Instant.now());
                persisted.add(repository.addMapping(tenant, suggestion));
            }
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalStateException("suggest-alignments returned malformed candidates JSON", ex);
        }
        return persisted;
    }

    // ─── constraints ────────────────────────────────────────────────────────

    /**
     * Add an (initially unenforced) constraint after validating its expression against the
     * class's global columns via the query engine ({@code validate-constraint}). An invalid
     * expression — bad SQL, unknown column, or an injection attempt — is rejected as a 400
     * (the {@link IllegalArgumentException} maps to RFC-7807 in the app layer) carrying
     * DuckDB's own parse message.
     */
    public ModelConstraint addConstraint(TenantContext tenant, UUID projectId,
                                         String classIri, String expression) {
        validateExpression(tenant, classIri, expression);
        IntegrationModel model = repository.getOrCreate(tenant, projectId);
        ModelConstraint constraint = new ModelConstraint(
                UUID.randomUUID(), tenantUuid(tenant), model.id(),
                classIri, expression, false, Instant.now(), "MANUAL");
        return repository.addConstraint(tenant, constraint);
    }

    private void validateExpression(TenantContext tenant, String classIri, String expression) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("classIri", classIri);
        params.put("expression", expression);
        OperatorRequest request = new OperatorRequest(
                tenant.value(), null, "validate-constraint", params);
        OperatorResult result = orchestrator.execute("query-engine", request);
        if (!result.success()) {
            throw new IllegalArgumentException(
                    result.errorMessage() != null ? result.errorMessage() : "invalid constraint expression");
        }
    }

    /**
     * Flip a constraint's {@code enforced} flag — <b>except that a {@code DISCOVERED} candidate can
     * never be turned ON</b> (V43).
     *
     * <p>LIMA states a denial constraint over a PAIR of tuples ({@code t1.a = t2.a & t1.b <> t2.b});
     * a {@link ModelConstraint} is a predicate over ONE row, and {@link #translateLimaExpression}
     * has no tuple identity to preserve because LIMA's rendered string never carried one. The
     * persisted text is therefore the constraint instantiated at {@code t1 = t2}: a valid weakening,
     * fine to SHOW a reviewer as evidence, but not the guarantee LIMA found. Enforcing it splices
     * that text into the {@code int_} view's WHERE and the {@code q_} quarantine's WHERE NOT, where
     * it is either constant-TRUE (filters nothing, so the accept affordance promises what it cannot
     * deliver) or constant-FALSE (quarantines the entire class), and is NULL — hence dropped from
     * both views — on every NULL-bearing row either way. Until enforcement understands inter-tuple
     * semantics, the honest state for a discovered candidate is display-only.
     *
     * <p>Disabling stays allowed in both directions: the block is about not creating a filter the
     * engine would mis-apply, so a row that is already enforced (a pre-block one, or a MANUAL one)
     * must always be switchable off. {@code MANUAL} constraints are untouched by this method.
     */
    public void setConstraintEnforced(TenantContext tenant, UUID projectId,
                                      UUID constraintId, boolean enforced) {
        // The refusal reads the resolved row. It used to re-scan the URL project's list and fall
        // through on a miss, so naming another project's URL enforced that project's candidate.
        ModelConstraint constraint = requireConstraint(tenant, projectId, constraintId);
        if (enforced && "DISCOVERED".equals(constraint.origin())) {
            throw new IllegalArgumentException(
                    "A discovered constraint is evidence only and cannot be enforced:"
                    + " constraint discovery states its result over a PAIR of rows, and"
                    + " enforcement can only apply a single-row filter, so what is stored"
                    + " here is the candidate collapsed to one row — not the constraint"
                    + " that was found. Enforcing it would filter nothing (or everything)"
                    + " and would silently drop NULL-bearing rows. Inter-tuple enforcement"
                    + " is not implemented; author an equivalent single-row constraint by"
                    + " hand to enforce one.");
        }
        repository.setConstraintEnforced(tenant, constraintId, enforced);
    }

    public void deleteConstraint(TenantContext tenant, UUID projectId, UUID constraintId) {
        requireConstraint(tenant, projectId, constraintId);
        repository.deleteConstraint(tenant, constraintId);
    }

    // ─── compile ──────────────────────────────────────────────────────────────

    /**
     * Full rebuild of the model into a DRAFT graph, wrapped in a REBUILD {@link IntegrationRun}
     * so the run + per-mapping application state are recorded (a later incremental run starts
     * from correct state). The returned map is the operator's, plus {@code integrationRunId}.
     */
    public Map<String, String> rebuildToDraft(TenantContext tenant, UUID projectId,
                                              @Nullable UUID actor) {
        return rebuildToDraft(tenant, projectId, actor, ProgressReporter.NOOP);
    }

    /**
     * {@link #rebuildToDraft(TenantContext, UUID, UUID)}, reporting its real phase boundaries —
     * re-verifying wrappers, integrating, profiling wrapper output — so a rebuild that takes
     * minutes says where it is instead of "waiting for the first stage…" (UI audit J4-F16).
     */
    public Map<String, String> rebuildToDraft(TenantContext tenant, UUID projectId,
                                              @Nullable UUID actor, ProgressReporter progress) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);
        // Both reads, and both BEFORE supersedeDraftRuns below — see rejectStaleEditedWrappers'
        // javadoc: superseding is destructive (it retires an existing DRAFT the user could still
        // promote and returns its mappings to PENDING), so a compile that is about to be refused
        // must not have run it.
        ModelBundle bundle = getBundle(tenant, projectId);
        rejectStaleEditedWrappers(tenant, bundle.sources());

        // Any earlier draft this run displaces is abandoned; release its mapping records first
        // so they are re-evaluated rather than stranded as APPLIED against a dead version.
        repository.supersedeDraftRuns(tenant, model.id());

        // Re-verify verdicts (V41, V47) BEFORE building
        // the payload, so the stamp `applyWrapperOverrides` copies into the draft is the verdict
        // of THIS compile against the CURRENT snapshot — never the one persisted at save time
        // against bytes that may since have been refreshed away.
        progress.stage("re-verifying wrappers of " + bundle.sources().size() + " source class(es)");
        List<ModelSource> sources = refreshWrapperVerdicts(tenant, bundle.sources());

        // sourceDatasetIds = all distinct enrolled datasets (N-ary).
        List<String> datasetIds = new ArrayList<>();
        for (ModelSource s : sources) {
            String id = s.datasetId().toString();
            if (!datasetIds.contains(id)) {
                datasetIds.add(id);
            }
        }

        // classWrappers = {classIri: {sql, expansionStatus}} for sources with an edited wrapper —
        // the verdict rides alongside the sql it was computed for (see applyWrapperOverrides).
        var classWrappers = objectMapper.createObjectNode();
        for (ModelSource s : sources) {
            if (s.wrapperSql() != null && !s.wrapperSql().isBlank()) {
                classWrappers.set(s.classIri(), wrapperOverrideNode(s));
            }
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("sourceDatasetIds", toJson(datasetIds));
        params.put("alignments", acceptedAlignmentsJson(bundle.mappings()).toString());
        params.put("classWrappers", classWrappers.toString());
        params.put("projectId", projectId.toString());
        params.put("modelId", model.id().toString());

        IntegrationRun run = repository.startRun(tenant, model.id(), IntegrationRunMode.REBUILD, null, null);
        Map<String, String> data;
        try {
            progress.stage("integrating " + datasetIds.size() + " dataset(s) into a draft schema");
            OperatorRequest request = new OperatorRequest(tenant.value(), actor, "compile", params);
            OperatorResult result = orchestrator.execute("schema-integration", request);
            if (!result.success()) {
                repository.markRunFailed(tenant, run.id(),
                        result.errorCode() != null ? result.errorCode() : "COMPILE_FAILED",
                        result.errorMessage() != null ? result.errorMessage() : "compile failed");
                throw new JobFailureException(
                        result.errorCode() != null ? result.errorCode() : "COMPILE_FAILED",
                        result.errorMessage() != null ? result.errorMessage() : "compile failed");
            }
            data = result.data();
        } catch (JobFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            repository.markRunFailed(tenant, run.id(), "COMPILE_FAILED", String.valueOf(e.getMessage()));
            throw e;
        }
        recordRunOutcome(tenant, model.id(), run.id(), data);
        progress.stage("profiling wrapper output of the draft");
        triggerWrapperOutputProfiling(tenant, actor, data.get("draftVersionId"));

        Map<String, String> out = new LinkedHashMap<>(data);
        out.put("integrationRunId", run.id().toString());
        return out;
    }

    /**
     * Add exactly one new source on top of the model's promoted baseline, without re-reading
     * the historical sources. Rejects when a rebuild is required, when there is no promoted
     * baseline, or when the baseline is no longer the active graph.
     */
    public Map<String, String> incrementalIntegrateToDraft(TenantContext tenant, UUID projectId,
                                                           UUID datasetId, @Nullable UUID actor) {
        return incrementalIntegrateToDraft(tenant, projectId, datasetId, actor, ProgressReporter.NOOP);
    }

    /** {@link #incrementalIntegrateToDraft(TenantContext, UUID, UUID, UUID)}, reporting its phases. */
    public Map<String, String> incrementalIntegrateToDraft(TenantContext tenant, UUID projectId,
                                                           UUID datasetId, @Nullable UUID actor,
                                                           ProgressReporter progress) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);
        if (model.rebuildRequired()) {
            throw new JobFailureException("REBUILD_REQUIRED",
                    "Integrated material changed; a full rebuild is required before incremental adds.");
        }
        IntegrationRun baseline = repository.latestPromotedRun(tenant, model.id())
                .orElseThrow(() -> new JobFailureException("NO_BASELINE",
                        "No promoted baseline; run a full rebuild and promote it first."));
        UUID baseVersionId = baseline.promotedVersionId();
        GraphVersion active = graphStore.currentVersion(tenant);
        if (baseVersionId == null || active == null || !active.id().equals(baseVersionId)) {
            throw new JobFailureException("STALE_INCREMENTAL_BASE",
                    "The model's baseline is no longer the active graph.");
        }

        // Reads, and before the destructive supersede below for the same reason as in
        // rebuildToDraft — see rejectStaleEditedWrappers' javadoc.
        ModelBundle bundle = getBundle(tenant, projectId);
        rejectStaleEditedWrappers(tenant, bundle.sources().stream()
                .filter(s -> s.datasetId().equals(datasetId)).toList());

        // Any earlier draft this run displaces is abandoned (both branch off the same baseline);
        // release its mapping records before building the payload so they are re-sent.
        repository.supersedeDraftRuns(tenant, model.id());
        // classWrappers only for the new source.
        var classWrappers = objectMapper.createObjectNode();
        for (ModelSource s : bundle.sources()) {
            if (s.datasetId().equals(datasetId) && s.wrapperSql() != null && !s.wrapperSql().isBlank()) {
                classWrappers.set(s.classIri(), wrapperOverrideNode(s));
            }
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("modelId", model.id().toString());
        params.put("baseVersionId", baseVersionId.toString());
        params.put("newDatasetId", datasetId.toString());
        params.put("newMappings",
                acceptedAlignmentsJson(repository.acceptedPendingMappings(tenant, model.id())).toString());
        params.put("deferredMappings",
                acceptedAlignmentsJson(repository.acceptedDeferredMappings(tenant, model.id())).toString());
        params.put("classWrappers", classWrappers.toString());

        IntegrationRun run = repository.startRun(tenant, model.id(),
                IntegrationRunMode.INCREMENTAL, baseVersionId, datasetId);
        Map<String, String> data;
        try {
            progress.stage("integrating the new source onto the promoted baseline");
            OperatorRequest request = new OperatorRequest(
                    tenant.value(), actor, "incremental-integrate", params);
            OperatorResult result = orchestrator.execute("schema-integration", request);
            if (!result.success()) {
                repository.markRunFailed(tenant, run.id(),
                        result.errorCode() != null ? result.errorCode() : "INCREMENTAL_FAILED",
                        result.errorMessage() != null ? result.errorMessage() : "incremental integration failed");
                throw new JobFailureException(
                        result.errorCode() != null ? result.errorCode() : "INCREMENTAL_FAILED",
                        result.errorMessage() != null ? result.errorMessage() : "incremental integration failed");
            }
            data = result.data();
        } catch (JobFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            repository.markRunFailed(tenant, run.id(), "INCREMENTAL_FAILED", String.valueOf(e.getMessage()));
            throw e;
        }
        recordRunOutcome(tenant, model.id(), run.id(), data);
        progress.stage("profiling wrapper output of the draft");
        triggerWrapperOutputProfiling(tenant, actor, data.get("draftVersionId"));

        Map<String, String> out = new LinkedHashMap<>(data);
        out.put("integrationRunId", run.id().toString());
        return out;
    }

    /**
     * Refuse to compile an EDITED wrapper that names a source column the dataset has since lost
     * (V47) — the other half of {@link #reseedUneditedWrappers}, for the wrappers re-seeding must
     * never touch.
     *
     * <p><b>Runs before anything destructive, and the call ORDER is the guarantee — not a
     * detail.</b> Both compile paths must call this before {@link
     * IntegrationModelRepository#supersedeDraftRuns}, not only before {@code startRun}: superseding
     * retires any outstanding DRAFT run to SUPERSEDED and returns its mapping applications to
     * PENDING, so a compile refused after it would have destroyed a compiled-but-unpromoted draft
     * the user could still have promoted — punishing them for an unrelated stale wrapper. Checked
     * first, nothing is recorded, nothing is superseded, nothing is drafted, nothing is promoted,
     * and the schema version currently serving queries keeps serving them. What the user gets instead
     * of DuckDB's {@code Binder Error: Referenced column "city" not found in FROM clause!} at
     * query time — carrying the wrapper's SQL and naming neither the dataset nor the repair — is a
     * message at compile time naming the dataset, the class, the columns that went away and the
     * columns that replaced them.
     *
     * <p><b>Deliberately conservative: it only fires on a column that provably existed and
     * provably went away.</b> "Old" is what the ACTIVE schema graph records for this source class
     * ({@code nx:sourceRef} on its properties — the physical column names), "new" is what the
     * freshly bootstrapped source graph records, and only the difference is looked for, as an
     * exact identifier token, in the wrapper text. A false positive here would fail a legitimate
     * compile, which is worse than the defect: so nothing is inferred about identifiers in general
     * (a function name, a struct path, an alias), and a class with no promoted history — nothing
     * to have lost — is skipped entirely.
     *
     * <p><b>Known limit, precisely scoped:</b> {@code JsonBootstrap} writes {@code nx:sourceRef}
     * as either a bare relative key or {@code key[*]} — never a dotted path. A bare key is a single
     * identifier token, so a removed <em>scalar</em> JSON leaf IS caught here exactly like a CSV
     * column. What is missed is the <b>array-leaf shape {@code key[*]}</b>, which no tokenisation
     * of the wrapper can produce (the wrapper reaches it through {@code UNNEST} and a row alias),
     * so a removed array leaf still fails at query time as before. Flat sources — CSV, PARQUET,
     * every relational import, which is where V47 was found — are covered in full.
     */
    private void rejectStaleEditedWrappers(TenantContext tenant, List<ModelSource> sources) {
        List<ModelSource> edited = sources.stream()
                .filter(s -> s.wrapperEdited() && s.wrapperSql() != null && !s.wrapperSql().isBlank())
                .toList();
        if (edited.isEmpty()) {
            return;
        }
        Model active = graphStore.query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:schema> { ?s ?p ?o } }");
        Map<UUID, Model> sourceGraphs = new LinkedHashMap<>();
        for (ModelSource s : edited) {
            Set<String> promotedRefs = sourceRefsOf(active, s.classIri());
            if (promotedRefs.isEmpty()) {
                continue; // never promoted, or no recorded columns — nothing can have been lost
            }
            Model sourceGraph = sourceGraphs.computeIfAbsent(
                    s.datasetId(), id -> constructSourceGraph(tenant, id));
            Set<String> currentRefs = sourceRefsOf(sourceGraph, s.classIri());
            if (currentRefs.isEmpty()) {
                continue; // the source graph says nothing about this class — do not guess
            }
            Set<String> removed = new LinkedHashSet<>(promotedRefs);
            currentRefs.forEach(removed::remove);
            if (removed.isEmpty()) {
                continue;
            }
            Set<String> mentioned = WrapperSqlValidator.identifierTokens(s.wrapperSql());
            List<String> stale = removed.stream()
                    .filter(col -> mentioned.contains(col.toLowerCase(java.util.Locale.ROOT)))
                    .sorted()
                    .toList();
            if (stale.isEmpty()) {
                continue;
            }
            throw new JobFailureException("WRAPPER_STALE",
                    "Dataset " + s.datasetId() + ": the edited wrapper for class " + s.classIri()
                            + " references column(s) the source no longer has: "
                            + String.join(", ", stale)
                            + ". The source's columns are now: "
                            + currentRefs.stream().sorted().collect(java.util.stream.Collectors.joining(", "))
                            + ". Edit the wrapper on the Wrapper tab and compile again; the active"
                            + " schema keeps serving queries until you do.");
        }
    }

    /**
     * The drift half of the save-time column check. {@code verify-wrapper} reads a class's columns
     * from the ACTIVE schema, which after a source refresh still maps the columns the source lost —
     * so, unexcused, the check refused exactly the edit {@link #rejectStaleEditedWrappers}'
     * {@code WRAPPER_STALE} message asks for, and an edited wrapper over a drifted source had no
     * repair path short of un-enrolling the dataset. A missing column whose raw source column (its
     * ACTIVE {@code nx:sourceRef}, which verify reports alongside) the dataset's current source
     * graph no longer declares — the same promoted-minus-current difference the compile refusal
     * diffs — is not missing, it goes away at the next rebuild; a rename or drop of a column the
     * source still has stays refused.
     *
     * <p>A verdict left with nothing missing is saved retryable ({@code "stale:"}, see
     * {@link #verdictNeedsRefresh}) rather than with the operator's "does not output" reason: it
     * was computed against a class shape the next compile replaces, and a non-retryable reason
     * would outlive that compile and mislabel the wrapper's badge for good. The compile-time
     * re-verify applies the same excuse, since it too runs against the pre-rebuild ACTIVE schema.
     */
    private WrapperVerdict excuseColumnsTheSourceDropped(TenantContext tenant, UUID datasetId,
                                                         String classIri, WrapperVerdict verdict) {
        if (verdict.missingSourceColumns().isEmpty()) {
            return verdict;
        }
        Set<String> currentRefs = sourceRefsOf(constructSourceGraph(tenant, datasetId), classIri);
        if (currentRefs.isEmpty()) {
            return verdict; // the source graph says nothing about this class — do not guess
        }
        List<String> dropped = new ArrayList<>();
        List<String> stillMissing = new ArrayList<>();
        for (String column : verdict.missingColumns()) {
            String raw = verdict.missingSourceColumns().get(column);
            if (raw != null && !currentRefs.contains(raw)) {
                dropped.add(raw);
            } else {
                stillMissing.add(column);
            }
        }
        if (dropped.isEmpty()) {
            return verdict;
        }
        if (!stillMissing.isEmpty()) {
            return new WrapperVerdict(verdict.status(), verdict.reason(), stillMissing);
        }
        return new WrapperVerdict("OPAQUE", "stale: saved while the active schema still maps"
                + " column(s) the source no longer has (" + String.join(", ", dropped)
                + ") — it will be re-verified at the next compile");
    }

    /** The physical column names ({@code nx:sourceRef}) the graph records for one source class. */
    private static Set<String> sourceRefsOf(Model graph, String classIri) {
        Set<String> refs = new LinkedHashSet<>();
        Property sourceRef = graph.createProperty(NS_SOURCE_REF);
        Resource cls = graph.getResource(classIri);
        for (Statement domain : graph.listStatements(null, RDFS.domain, cls).toList()) {
            Statement ref = domain.getSubject().getProperty(sourceRef);
            if (ref != null && ref.getObject().isLiteral()) {
                refs.add(ref.getString());
            }
        }
        return refs;
    }

    private static final String NS_SOURCE_REF = "http://www.essi.upc.edu/DTIM/NextiaDI/sourceRef";

    /**
     * Model-aware promotion of a DRAFT run's draft version. Verifies the run belongs to the
     * project's model and is a DRAFT, promotes its draft, points the model's active run at it,
     * and clears {@code rebuild_required} after a successful full rebuild.
     */
    public GraphVersion promoteRun(TenantContext tenant, UUID projectId, UUID runId, UUID actor) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);
        IntegrationRun run = repository.findRun(tenant, runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        if (!run.modelId().equals(model.id())) {
            throw new IllegalArgumentException("run " + runId + " does not belong to this model");
        }
        UUID draftVersionId = run.draftVersionId();
        if (run.status() == IntegrationRunStatus.SUPERSEDED) {
            throw new PromoteConflictException(PromoteConflictException.DRAFT_SUPERSEDED,
                    "This draft was superseded by a newer compile of the same integration model, "
                            + "which returned its alignments to pending. Promote the newest draft, "
                            + "or rebuild.", List.of());
        }
        if (run.status() != IntegrationRunStatus.DRAFT) {
            // A 409, not a 500: the common way here is a promote retried after the browser gave
            // up waiting while the first one landed (J4-F11). Reloading shows the current state.
            String why = switch (run.status()) {
                case PROMOTED -> "it was already promoted — an earlier attempt may have landed "
                        + "after the page stopped waiting";
                default -> "it is " + run.status();
            };
            throw new ConflictException(ConflictException.NOT_A_DRAFT,
                    "This compile run cannot be promoted: " + why + ". Reload to see the current "
                            + "schema version.");
        }
        if (draftVersionId == null) {
            // A DRAFT run with no draft version is a state no writer produces; not the caller's to fix.
            throw new IllegalStateException("only a DRAFT run with a draft version can be promoted");
        }
        if (run.status() != IntegrationRunStatus.DRAFT || draftVersionId == null) {
            throw new PromoteConflictException(PromoteConflictException.RUN_NOT_DRAFT,
                    "only a DRAFT run with a draft version can be promoted (this run is "
                            + run.status() + ")", List.of());
        }
        promoteGuard.check(tenant, draftVersionId, model.id(), actor);
        GraphVersion promoted = graphStore.promote(tenant, draftVersionId, new AuditedActor(actor));
        repository.markRunPromoted(tenant, runId, promoted.id());
        repository.setActiveIntegrationRun(tenant, model.id(), runId);
        // The draft version's id survives promotion unchanged, so the application rows recorded
        // at draft time already point at the now-active version. A full rebuild re-establishes
        // the whole graph, so any pending rebuild flag is cleared.
        if (run.mode() == IntegrationRunMode.REBUILD) {
            repository.setRebuildRequired(tenant, model.id(), false);
        }
        // Every place a new ACTIVE version appears must stale-mark MATERIALIZED products, and
        // this — the path the frontend always uses for model runs — was the one of the three
        // promote paths that didn't (audit INTEGRATION_COMPILE-3): products stayed READY,
        // silently pinned to the superseded version.
        dataProducts.markStaleForVersionChange(tenant);
        return promoted;
    }

    /**
     * The project whose integration run compiled this draft, if a run did. The generic promote
     * route asks it first, so only that project's members may promote the draft — which also
     * moves the project's run and baseline (review UA5; D11's project gate, at the controller).
     */
    public Optional<UUID> owningProjectOfDraft(TenantContext tenant, UUID draftVersionId) {
        return repository.findRunByDraftVersion(tenant, draftVersionId).map(run -> projectOfRun(tenant, run));
    }

    private UUID projectOfRun(TenantContext tenant, IntegrationRun run) {
        return repository.findAllModels(tenant).stream()
                .filter(m -> m.id().equals(run.modelId()))
                .map(IntegrationModel::projectId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "run " + run.id() + " names a model this tenant does not have"));
    }

    /**
     * The one promote path for a draft named only by its version id — the Schema versions page's
     * {@code POST /api/graph/promote/{id}} (audit J4-F2).
     *
     * <p>A draft an integration run produced is promoted through {@link #promoteRun}, so the run
     * becomes PROMOTED and the model gets its baseline. Before this, that page moved the graph
     * pointer and left the run in DRAFT: the model had no baseline, and the next "add without
     * re-reading the others" was refused NO_BASELINE. A draft no run produced is promoted as it
     * always was, behind the same guard.
     */
    public GraphVersion promoteDraftVersion(TenantContext tenant, UUID draftVersionId, UUID actor) {
        Optional<IntegrationRun> owner = repository.findRunByDraftVersion(tenant, draftVersionId);
        if (owner.isPresent()) {
            return promoteRun(tenant, projectOfRun(tenant, owner.get()), owner.get().id(), actor);
        }
        promoteGuard.check(tenant, draftVersionId, null, actor);
        GraphVersion promoted = graphStore.promote(tenant, draftVersionId, new AuditedActor(actor));
        dataProducts.markStaleForVersionChange(tenant);
        return promoted;
    }

    /**
     * The ACCEPTED-mapping alignment payload the operator parses: the exact
     * {@code {mappingId, iriA, iriB, label, type}} shape, key order matching what
     * {@code AlignmentsView} serialises for a lossless round-trip.
     */
    private ArrayNode acceptedAlignmentsJson(List<ModelMapping> mappings) {
        ArrayNode alignments = objectMapper.createArrayNode();
        for (ModelMapping m : mappings) {
            if (!ModelMapping.ACCEPTED.equals(m.status())) {
                continue;
            }
            var node = objectMapper.createObjectNode();
            node.put("mappingId", m.id() == null ? null : m.id().toString());
            node.put("iriA", m.sourceIri());
            node.put("iriB", m.targetIri());
            node.put("label", m.label() == null ? "" : m.label());
            node.put("type", m.kind());
            alignments.add(node);
        }
        return alignments;
    }

    /** Move each run outcome's mappings to APPLIED/DEFERRED against the draft (= future active) version. */
    private void recordRunOutcome(TenantContext tenant, UUID modelId, UUID runId,
                                  Map<String, String> operatorData) {
        String draftVersionRaw = operatorData.get("draftVersionId");
        if (draftVersionRaw == null) {
            return;
        }
        repository.recordDraft(tenant, runId, UUID.fromString(draftVersionRaw));
        UUID versionId = UUID.fromString(draftVersionRaw);

        List<UUID> applied = parseIdList(operatorData.get("appliedMappingIds"));
        List<UUID> deferred = parseIdList(operatorData.get("deferredMappingIds"));
        for (UUID id : applied) {
            repository.upsertMappingApplicationPending(tenant, modelId, id);
        }
        if (!applied.isEmpty()) {
            repository.markMappingsApplied(tenant, runId, versionId, applied);
        }
        for (UUID id : deferred) {
            repository.upsertMappingApplicationPending(tenant, modelId, id);
        }
        if (!deferred.isEmpty()) {
            repository.markMappingsDeferred(tenant, runId, deferred, "deferred by integration run");
        }
    }

    private static List<UUID> parseIdList(@Nullable String csv) {
        List<UUID> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(UUID.fromString(trimmed));
            }
        }
        return out;
    }

    private static final String NX_PROVENANCE = "http://www.essi.upc.edu/DTIM/NextiaDI/provenance";
    private static final String NX_DATASET_ID = "http://www.essi.upc.edu/DTIM/NextiaDI/datasetId";

    /**
     * The dataset ids already represented in the active integrated graph.
     *
     * <p>The UI's mirror of the operator's {@code SOURCE_ALREADY_INTEGRATED} guard, which rejects
     * an incremental add whose source classes are already present in {@code G_I^old}. Read from
     * the graph itself: every source class carries {@code nx:provenance} (re-affirmed on each
     * integration) and {@code nx:datasetId} (stamped at bootstrap).
     *
     * <p>The query catalog is <strong>not</strong> a valid substitute — it lists only sources that
     * ended up beneath an integrated class, so a source integrated with no accepted alignment is
     * absent from it and the UI would offer it again, producing a doomed job.
     */
    public List<UUID> integratedDatasetIds(TenantContext tenant) {
        return integratedDatasetIds(activeSchemaGraph(tenant));
    }

    /**
     * The active schema graph, read once so a caller needing more than one answer from it pays for
     * one Fuseki round trip rather than one per answer — see {@link #activeModelFacts}.
     */
    private Model activeSchemaGraph(TenantContext tenant) {
        return graphStore.query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:schema> { ?s ?p ?o } }");
    }

    private static List<UUID> integratedDatasetIds(Model active) {
        List<UUID> out = new ArrayList<>();
        for (String predicate : List.of(NX_PROVENANCE, NX_DATASET_ID)) {
            Property p = active.createProperty(predicate);
            for (Statement st : active.listStatements(
                    null, p, (org.apache.jena.rdf.model.RDFNode) null).toList()) {
                if (!st.getObject().isLiteral()) {
                    continue;
                }
                try {
                    UUID id = UUID.fromString(st.getString());
                    if (!out.contains(id)) {
                        out.add(id);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a dataset uuid — skip rather than fail the whole bundle load.
                }
            }
        }
        return out;
    }

    /**
     * The integrated classes the ACTIVE schema serves — what the query engine's catalog is keyed
     * by, and therefore the only class IRIs an enforced constraint can still reach (V42).
     *
     * <p><b>{@code addConstraint} is the only caller</b> — {@code GET /model} needs this answer
     * <em>and</em> {@link #integratedDatasetIds}, and takes both from {@link #activeModelFacts}'s
     * single read instead (V49). Whoever adds a second caller should ask the same question: one
     * answer from this narrowed query, two or more from {@code activeModelFacts}.
     *
     * <p>Read from the graph rather than from {@code query-engine}'s {@code catalog} operation
     * because that operation builds and serialises a full per-class payload (row counts, column
     * maps, join and object properties) to answer a set-membership question.
     * {@link IntegrationLinks#servedIntegratedClasses} walks QUERY 1's own join, so the answer is
     * the catalog's own rule rather than a looser approximation of it.
     *
     * <p>An empty set means the active schema serves nothing at all — never compiled, never
     * promoted, or rolled back to empty. That is <em>not</em> the same as "every constraint is
     * orphaned", and the payload treats it accordingly.
     *
     * <p>The CONSTRUCT is narrowed to the four predicates {@link
     * IntegrationLinks#servedIntegratedClasses} actually reads rather than pulling the whole graph,
     * which is what makes it the cheaper choice for a caller that wants only this. Keep the list in
     * step with what that method walks: {@code nx:wrapperSql} to find the wrappered source classes,
     * both {@code subClassOf} spellings to climb to their integrated class, and {@code rdf:type}
     * for the {@code nx:IntegratedResource} test.
     */
    public Set<String> activeIntegratedClassIris(TenantContext tenant) {
        return IntegrationLinks.servedIntegratedClasses(graphStore.query(tenant, ACTIVE_CLASS_QUERY));
    }

    /**
     * Both active-graph answers {@code GET /model} needs, from <b>one</b> read of the graph.
     *
     * <p><b>Why this exists — V49.</b> V42 added {@link #activeIntegratedClassIris} to that
     * endpoint beside the pre-existing {@link #integratedDatasetIds}, and each opened its own
     * Fuseki round trip, so a page-load endpoint went from one graph query to two. Measured on the
     * e2e stack that cost tens of milliseconds per call — enough that the second of the two model
     * loads a dataset-open fires landed <em>after</em> the wrapper editor had been typed into
     * instead of before, and the editor's seed effect then overwrote the typed SQL. The frontend's
     * unconditional re-seed is the defect and is fixed where it lives; this is the other half:
     * the endpoint should never have paid for two reads of the same graph to begin with.
     *
     * <p>The narrowed {@link #ACTIVE_CLASS_QUERY} still earns its place for
     * {@code addConstraint}, which wants only this answer and would otherwise pull the whole
     * graph for it. Here the full graph is already being read for the dataset ids, and
     * {@link IntegrationLinks#servedIntegratedClasses} consults only the four predicates that
     * query selects — extra triples are never looked at — so the two spellings cannot disagree.
     */
    public ActiveModelFacts activeModelFacts(TenantContext tenant) {
        Model active = activeSchemaGraph(tenant);
        return new ActiveModelFacts(integratedDatasetIds(active),
                IntegrationLinks.servedIntegratedClasses(active));
    }

    /** @see #activeModelFacts */
    public record ActiveModelFacts(List<UUID> integratedDatasetIds, Set<String> activeClassIris) {}

    /**
     * Package-private so a test can run it against a real Jena dataset. Every other graph query in
     * this service is inlined at its call site, and this one is not for a specific reason: the test
     * graph stores ({@code FakeGraphStore}, {@code BaseIntegrationTest.InMemoryGraphStore}) resolve
     * a query by pattern-matching its graph URI and never execute the SPARQL, so a syntax error or
     * a missing predicate in a shaped CONSTRUCT like this one would pass every test and fail only
     * against Fuseki.
     */
    static final String ACTIVE_CLASS_QUERY = """
            PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX nx:   <http://www.essi.upc.edu/DTIM/NextiaDI/>
            CONSTRUCT { ?s ?p ?o }
            WHERE { GRAPH <urn:aran:schema> {
              ?s ?p ?o .
              FILTER (?p IN (nx:wrapperSql, nx:subClassOf, rdfs:subClassOf, rdf:type))
            } }
            """;

    // ─── helpers ──────────────────────────────────────────────────────────────

    private Model constructSourceGraph(TenantContext tenant, UUID datasetId) {
        String graphUri = "urn:aran:source:" + datasetId;
        return graphStore.query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalStateException("failed to serialise compile parameters", ex);
        }
    }

    /**
     * One {@code classWrappers} entry value: {@code {sql, expansionStatus}} — the shape
     * {@code SchemaIntegrationOperator.parseClassWrappers} reads into a {@code WrapperOverride}.
     * {@code expansionStatus} rides through as-is, {@code null} and all: a source never verified
     * carries no stamp into the compiled graph (see {@code applyWrapperOverrides}).
     */
    private com.fasterxml.jackson.databind.node.ObjectNode wrapperOverrideNode(ModelSource s) {
        var node = objectMapper.createObjectNode();
        node.put("sql", s.wrapperSql());
        node.put("expansionStatus", s.expansionStatus());
        return node;
    }

    private static UUID tenantUuid(TenantContext tenant) {
        return UUID.fromString(tenant.value());
    }

    // ─── Phase 3B (Task 3): compile-tail wrapper-output profiling ──────────────

    /** One compiled source class's wrapper fact, read straight from the just-compiled draft graph. */
    private record CompiledWrapperFact(String classIri, String wrapperSql, String datasetIdRaw,
                                       @Nullable String expansionStatus) {}

    /**
     * After a successful compile, invoke wrapper-output profiling (query-engine's {@code profile}
     * operation, {@code subject=WRAPPER_OUTPUT}) for every source class in the just-compiled draft
     * whose verdict is NOT EXPANDED — today, simply every class the draft carries no
     * {@code nx:wrapperExpansion}{@code =EXPANDED} triple for, i.e. every OPAQUE one.
     *
     * <p>Reads the DRAFT graph directly ({@link GraphStore#readVersion}), not the active promoted
     * graph {@code MappingCatalogOperator} serves: a compile produces a draft that has not
     * necessarily been promoted yet, and profiling should not wait on that — by the time someone
     * promotes it, the evidence should already be there.
     *
     * <p>Every failure along the way — a bad draft id, a dataset with no recorded snapshot, an
     * unreachable freyja, freyja's own 400 for an unprofilable wrapper output — is logged WARN and
     * swallowed, never rethrown. A compile must never fail, or even report a degraded result,
     * because profiling hiccuped: the compiled schema is already correct and usable without it,
     * and the next compile (or a later wrapper edit) tries again.
     */
    private void triggerWrapperOutputProfiling(TenantContext tenant, @Nullable UUID actor,
                                               @Nullable String draftVersionIdRaw) {
        if (draftVersionIdRaw == null || draftVersionIdRaw.isBlank()) {
            return;
        }
        List<CompiledWrapperFact> facts;
        try {
            UUID draftVersionId = UUID.fromString(draftVersionIdRaw);
            Model draft = graphStore.readVersion(tenant, draftVersionId);
            facts = readCompiledWrapperFacts(draft);
        } catch (RuntimeException e) {
            log.warn("Wrapper-output profiling skipped for draft '{}': {}", draftVersionIdRaw,
                    e.getMessage(), e);
            return;
        }
        // Per-fact, not around the whole loop: the loop's own body used to share one catch, so one
        // class's profiling blowing up silently starved every class after it in the same compile
        // of the profiling this call exists to trigger for them too.
        for (CompiledWrapperFact fact : facts) {
            try {
                profileOneWrapperOutput(tenant, actor, fact);
            } catch (RuntimeException e) {
                log.warn("Wrapper-output profiling failed for class '{}' (dataset '{}'): {}",
                        fact.classIri(), fact.datasetIdRaw(), e.getMessage(), e);
            }
        }
    }

    /**
     * Every source class in {@code draft} carrying {@code nx:wrapperSql} — the same per-class
     * facts {@code MappingCatalogOperator}'s QUERY 1 reads, narrowed to just what this task needs
     * (no property/join traversal: this only decides which classes to profile). {@code datasetId}
     * resolves the same way QUERY 1's does: {@code nx:datasetId} first, {@code nx:provenance} as
     * the pre-that fallback — a class with neither is skipped, not defaulted, since there is no
     * dataset to resolve a snapshot from.
     */
    private List<CompiledWrapperFact> readCompiledWrapperFacts(Model draft) {
        Property wrapperSqlProp = draft.createProperty(WRAPPER_SQL_IRI);
        Property datasetIdProp = draft.createProperty(NX_DATASET_ID);
        Property provenanceProp = draft.createProperty(NX_PROVENANCE);
        Property expansionProp = draft.createProperty(WRAPPER_EXPANSION_IRI);

        List<CompiledWrapperFact> facts = new ArrayList<>();
        for (Statement st : draft.listStatements(null, wrapperSqlProp, (RDFNode) null).toList()) {
            if (!st.getSubject().isURIResource() || !st.getObject().isLiteral()) {
                continue;
            }
            Resource srcClass = st.getSubject();
            String wrapperSql = st.getString();

            String datasetIdRaw = literalValue(srcClass, datasetIdProp);
            if (datasetIdRaw == null) {
                datasetIdRaw = literalValue(srcClass, provenanceProp);
            }
            if (datasetIdRaw == null) {
                continue;
            }

            facts.add(new CompiledWrapperFact(
                    srcClass.getURI(), wrapperSql, datasetIdRaw, literalValue(srcClass, expansionProp)));
        }
        return facts;
    }

    private static @Nullable String literalValue(Resource subject, Property property) {
        Statement st = subject.getProperty(property);
        return st != null && st.getObject().isLiteral() ? st.getString() : null;
    }

    /**
     * Screens, resolves the dataset's snapshot, and invokes the profiling orchestrator for one
     * NOT-EXPANDED class. Every early return is a WARN, never an exception — see
     * {@link #triggerWrapperOutputProfiling}'s own javadoc for why.
     */
    private void profileOneWrapperOutput(TenantContext tenant, @Nullable UUID actor, CompiledWrapperFact fact) {
        if ("EXPANDED".equals(fact.expansionStatus())) {
            return; // already verified to plan over its own src_ relation; SNAPSHOT profiles suffice.
        }
        if (fact.wrapperSql().isBlank()) {
            return;
        }

        // Defence in depth, same discipline as QueryEngineOperator.verifyWrapper: this text is
        // about to run against a real, unrestricted DuckDB connection (via freyja/the lake), so it
        // gets the same fail-closed screen every other SQL-executing path applies here, ahead of
        // the orchestrator call, independent of whatever screening it may already have passed when
        // it was first saved (setWrapper, or a trusted bootstrap-generated wrapper).
        String forbidden = WrapperSqlValidator.findForbiddenConstruct(fact.wrapperSql());
        if (forbidden != null) {
            log.warn("Skipping wrapper-output profiling for class '{}' (dataset '{}'): {}",
                    fact.classIri(), fact.datasetIdRaw(), forbidden);
            return;
        }

        UUID datasetId;
        try {
            datasetId = UUID.fromString(fact.datasetIdRaw());
        } catch (IllegalArgumentException e) {
            log.warn("Skipping wrapper-output profiling for class '{}': dataset id '{}' is not a UUID",
                    fact.classIri(), fact.datasetIdRaw());
            return;
        }

        Dataset dataset = datasetRepository.findById(tenant, datasetId).orElse(null);
        if (dataset == null) {
            log.warn("Skipping wrapper-output profiling for class '{}': dataset '{}' not found",
                    fact.classIri(), datasetId);
            return;
        }
        if (dataset.snapshotId() == null) {
            // Gap 2 follow-up: no lake snapshot is not "nothing to profile" — a FEDERATED dataset
            // is profiled by executing the suite over the same live binding queries use. Anything
            // else (a registered-but-never-materialized dataset) keeps the honest WARN-skip.
            profileFederatedWrapperOutput(tenant, actor, fact, dataset, datasetId);
            return;
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("snapshotId", dataset.snapshotId());
        params.put("datasetId", datasetId.toString());
        params.put("wrapperSql", fact.wrapperSql());
        params.put("classIri", fact.classIri());
        params.put("subject", "WRAPPER_OUTPUT");
        params.put("varcharOnly", "false");

        OperatorRequest request = new OperatorRequest(tenant.value(), actor, "profile", params);
        OperatorResult result = orchestrator.execute("profiling", request);
        if (!result.success()) {
            // Surfaces freyja's own reason verbatim (e.g. its 400 for a wrapper output column
            // literally named "snapshot") rather than a generic failure message.
            log.warn("Wrapper-output profiling failed for class '{}' (dataset '{}'): {}",
                    fact.classIri(), datasetId,
                    result.errorMessage() != null ? result.errorMessage() : result.errorCode());
        }
    }

    /**
     * Profile a FEDERATED class's wrapper output (Gap 2 follow-up, per the design ruling):
     * profiling is a suite of SQL over the wrapper-as-view, so it executes over the <b>same live
     * binding queries use</b> — the profiling operator builds the dataset's scan
     * ({@code FederatedProfilingBinding}: the {@code iceberg_scan}/{@code postgres_scan} plus its
     * connection setup), freyja runs the suite against the source in place, and only aggregate
     * results cross the wire. Nothing is staged to the lake, whatever the source's size, and the
     * persisted evidence ({@code ColumnProfile.SUBJECT_FEDERATED_LIVE}) is exact over the full
     * relation at profile time.
     *
     * <p>Same best-effort contract as the snapshot path: every early exit is a WARN, never an
     * exception — a compile must not fail because profiling could not run.
     */
    private void profileFederatedWrapperOutput(TenantContext tenant, @Nullable UUID actor,
            CompiledWrapperFact fact, Dataset dataset, UUID datasetId) {
        if (dataRepositoryRepository == null) {
            log.warn("Skipping federated wrapper-output profiling for class '{}': this build has"
                    + " no repository store wired", fact.classIri());
            return;
        }
        DataRepository repo = dataRepositoryRepository
                .findById(tenant, dataset.repositoryId()).orElse(null);
        if (edu.upc.dtak.aran.core.source.SourceAccess.resolve(repo, dataset.name(), false)
                != edu.upc.dtak.aran.core.source.AccessMode.FEDERATED) {
            log.warn("Skipping wrapper-output profiling for class '{}': dataset '{}' has no"
                    + " recorded snapshot and is not served federated", fact.classIri(), datasetId);
            return;
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("federated", "true");
        params.put("datasetId", datasetId.toString());
        params.put("wrapperSql", fact.wrapperSql());
        params.put("classIri", fact.classIri());
        params.put("subject", ColumnProfile.SUBJECT_FEDERATED_LIVE);
        params.put("varcharOnly", "false");
        OperatorResult result = orchestrator.execute("profiling",
                new OperatorRequest(tenant.value(), actor, "profile", params));
        if (!result.success()) {
            log.warn("Federated wrapper-output profiling failed for class '{}' (dataset '{}'): {}",
                    fact.classIri(), datasetId,
                    result.errorMessage() != null ? result.errorMessage() : result.errorCode());
        }
    }

    // ─── Phase 3B (Task 5): DC discovery candidates ────────────────────────────

    /** The {@code limit} every discover request carries — see where it is put on the params map. */
    private static final int DISCOVERY_ROW_LIMIT = 10_000;

    /**
     * Run LIMA denial-constraint discovery (the {@code constraint-discovery} operator, {@code
     * discover} operation) over one source class's wrapper output, translate the result into the
     * class's local-name authoring convention, screen and de-duplicate it, and persist survivors
     * as unenforced, {@code DISCOVERED}-origin {@link ModelConstraint} rows.
     *
     * <p><b>A candidate is display-only evidence, not an enforceable filter</b> (V43): it
     * is listed beside hand-authored constraints for a human to read, but {@link
     * #setConstraintEnforced} refuses to enforce a {@code DISCOVERED} row — see that method for why
     * a single-row instantiation of an inter-tuple constraint cannot be spliced into a {@code WHERE}.
     * A reviewer who agrees with a candidate authors the equivalent single-row constraint by hand.
     *
     * <p>Reads the <em>active</em> promoted graph ({@link #readCompiledWrapperFacts} applied to
     * it), not a draft: unlike Task 3's compile-tail profiling, discovery is a user-triggered,
     * on-demand action over a class that is presumably already live, not a step inside compile.
     *
     * <p>{@code classIri} accepts <b>either namespace</b>, because its two real callers disagree:
     * {@link #setWrapper}/{@link #verifyWrapper} (and every earlier test here) use the SOURCE
     * class IRI, but the constraints tab's "Discover constraints" button — its only production
     * caller — sends {@code selCls.iri}, which the catalog payload populates from {@code
     * MappingCatalog.IntegratedClass.iri} ({@code QueryEngineOperator}'s class listing exposes
     * only that, never a source class IRI). The two namespaces are disjoint by construction
     * (see below), so a filter that only matched the source IRI made the UI's own button a
     * guaranteed, silent zero-candidate no-op: every call resolved no fact, warned, and returned
     * a success summary of {@code 0/0/0} — "discovery complete" with nothing, every time. A fact
     * now matches on {@link #readCompiledWrapperFacts}'s own (always-source) {@code classIri}
     * OR on that source class's {@link #resolveIntegratedClass} equalling the parameter, over
     * every fact for the given {@code datasetId}. One integrated class can be fed by more than
     * one source class (even within one dataset, in principle), so every match is discovered
     * over and folded into one summary, not just the first.
     *
     * <p><b>The persisted constraint's {@code classIri} is the INTEGRATED class, resolved from
     * {@code classIri} — never the source class IRI itself.</b> {@code QueryEngineOperator
     * .enforcedByClass} only ever forwards a constraint into DDL when its {@code classIri} is a
     * key of {@code MappingCatalog.classes()}, which is keyed by integrated-class IRIs
     * ({@code IntegrationVocab.INTEGRATION_NS}) — a namespace disjoint by construction from
     * source-class IRIs ({@code BootstrapEngine.SOURCE_NS}). A constraint persisted under the
     * source class IRI would never be enforceable and would never appear in the per-class
     * constraints UI, silently. See {@link #resolveIntegratedClass}.
     *
     * <p><b>Column identifiers in the persisted expression are the INTEGRATED properties'
     * local names, not the wrapper's own bootstrap-time property names.</b> When a source
     * property is aligned, {@code MappingCatalog.SourceView.columnMap()} keys on the integrated
     * property (QUERY 1's {@code targetProp = COALESCE(?ip, ?srcProp)}), and that is exactly the
     * vocabulary {@code DuckDbExecutor}'s {@code collectGlobalColumns}/{@code localToGlobal}
     * build the constraint-rewrite table from — so an expression naming a source property's own
     * (pre-alignment) local name either rewrites to nothing (a bare identifier the {@code int_}
     * view has no column for — broken DDL at enforce time) or, worse, happens to match a
     * different property's local name (a silent wrong-column collision). See
     * {@link #wrapperOutputLocalNames}/{@link #resolveTargetProperty}.
     *
     * <p>Every early return degrades to a zero-candidate summary, logged WARN when it reflects an
     * actual problem (no wrapperSql found, a forbidden construct, no snapshot, the operator
     * failing) — mirroring {@link #profileOneWrapperOutput}'s "never break the caller" discipline,
     * even though this caller is a foreground request rather than a swallowed compile-tail step:
     * a user re-running discovery on an unpromising class should see "0 discovered", not a 500.
     *
     * <p>An EXPANDED class is skipped exactly like {@link #profileOneWrapperOutput} skips one for
     * profiling: its wrapper is already a verified relational rewrite of the raw snapshot, but
     * LIMA has no equivalent of Volcano's "derive through the operators" for denial constraints —
     * it can only sample an actual materialized table — so v1 does not attempt a different
     * (raw-snapshot, differently-namespaced) discovery for it rather than resolve a subject this
     * method cannot yet translate correctly. Recorded as a concern, not solved here.
     *
     * @return {@code {discovered, screened, persisted, pairOnly, summary}} — the job substrate's
     *         result shape; see {@link #discoverySummary} for what each count means and why
     *         {@code pairOnly} is not folded into {@code screened} (V43) — plus
     *         {@code degraded}/{@code reason} when a matched class could not be discovered over at
     *         all (audit CONSTRAINT_DISCOVERY-12).
     */
    public Map<String, String> discoverConstraints(TenantContext tenant, UUID projectId,
                                                    UUID datasetId, String classIri) {
        IntegrationModel model = repository.getOrCreate(tenant, projectId);

        Model active = graphStore.query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:schema> { ?s ?p ?o } }");

        // classIri may be the SOURCE class IRI (setWrapper/verifyWrapper's convention) or the
        // INTEGRATED class IRI (the constraints tab's actual, only production caller — see the
        // method javadoc). Both are checked; a string can only ever satisfy one, since the two
        // namespaces are disjoint by construction, so this never double-counts one fact.
        List<CompiledWrapperFact> matches = readCompiledWrapperFacts(active).stream()
                .filter(f -> datasetId.toString().equals(f.datasetIdRaw()))
                .filter(f -> classIri.equals(f.classIri())
                        || classIri.equals(resolveIntegratedClass(active, f.classIri())))
                .toList();

        if (matches.isEmpty()) {
            log.warn("Constraint discovery requested for class '{}' (dataset '{}') but no wrapperSql"
                    + " was found in the active schema", classIri, datasetId);
            return discoverySummary(0, 0, 0, 0);
        }

        Dataset dataset = datasetRepository.findById(tenant, datasetId).orElse(null);
        if (dataset == null || dataset.snapshotId() == null) {
            log.warn("Skipping constraint discovery for class '{}': dataset '{}' has no recorded snapshot",
                    classIri, datasetId);
            return discoverySummary(0, 0, 0, 0);
        }

        int discovered = 0;
        int screened = 0;
        int persisted = 0;
        int pairOnly = 0;
        // A zero-candidate summary from an outage and one from a class that genuinely has no
        // constraints were byte-identical, so the UI toasted "discovery complete" either way
        // (audit CONSTRAINT_DISCOVERY-12). Every path that swallows a failure below records why
        // here; the first reason is enough for a toast and keeps the summary a flat string map.
        // Orthogonal to `pairOnly`, which is not a failure at all — see discoverySummary.
        String degradedReason = null;
        for (CompiledWrapperFact fact : matches) {
            // Resolved from the MATCHED FACT's own (always-source) classIri, never from the
            // parameter: the parameter might already be the integrated IRI, and persistence must
            // always land on the integrated class regardless of which namespace the caller used.
            String integratedClassIri = resolveIntegratedClass(active, fact.classIri());
            if (integratedClassIri == null) {
                log.warn("Constraint discovery requested for class '{}' (dataset '{}') but it is not"
                        + " subClassOf any integrated class in the active schema — a candidate"
                        + " persisted under the source class IRI would be unenforceable and"
                        + " invisible to the constraints UI, so nothing is persisted",
                        fact.classIri(), datasetId);
                continue;
            }
            if ("EXPANDED".equals(fact.expansionStatus())) {
                continue;
            }

            String forbidden = WrapperSqlValidator.findForbiddenConstruct(fact.wrapperSql());
            if (forbidden != null) {
                log.warn("Skipping constraint discovery for class '{}' (dataset '{}'): {}",
                        fact.classIri(), datasetId, forbidden);
                continue;
            }

            Map<String, String> params = new LinkedHashMap<>();
            params.put("datasetId", datasetId.toString());
            params.put("wrapperSql", fact.wrapperSql());
            params.put("classIri", fact.classIri());
            // Uncapped, LIMA materializes every wrapper-output row in the JVM heap and its sampler
            // is quadratic in that count — an OOM on a large source, not a slow discovery (audit
            // CONSTRAINT_DISCOVERY-3). Sampling more than this buys nothing a reviewer would act on.
            params.put("limit", String.valueOf(DISCOVERY_ROW_LIMIT));

            OperatorRequest request = new OperatorRequest(tenant.value(), null, "discover", params);
            OperatorResult result = orchestrator.execute("constraint-discovery", request);
            if (!result.success()) {
                String reason = result.errorMessage() != null ? result.errorMessage() : result.errorCode();
                log.warn("Constraint discovery failed for class '{}' (dataset '{}'): {}",
                        fact.classIri(), datasetId, reason);
                if (degradedReason == null) {
                    degradedReason = reason;
                }
                continue;
            }
            // The operator's own "I could not run" verdict (its wrapper path returns success +
            // constraints:[] rather than failing its caller — see discoverOverWrapperOutput).
            if ("true".equals(result.data().get("degraded")) && degradedReason == null) {
                degradedReason = result.data().getOrDefault("reason", "discovery could not complete");
            }

            List<String> raw = parseConstraintsJson(result.data().get("constraints"));
            Map<String, String> iriToLocal = wrapperOutputLocalNames(fact.wrapperSql(), active);
            discovered += raw.size();

            // Re-read fresh per fact (not carried across the loop): when two matched facts share
            // one integrated class, the second fact's dedup must see the first fact's own
            // just-persisted rows, which a set built once before the loop never would.
            Set<String> seen = new LinkedHashSet<>();
            for (ModelConstraint existing : repository.load(tenant, projectId).constraints()) {
                if (integratedClassIri.equals(existing.classIri())) {
                    seen.add(existing.expression());
                }
            }

            for (String limaExpression : raw) {
                String translated = translateLimaExpression(limaExpression, iriToLocal);
                if (translated == null) {
                    screened++;
                    log.warn("Dropped a discovered constraint candidate for class '{}': could not"
                            + " translate into the local-name authoring convention — {}",
                            fact.classIri(), limaExpression);
                    continue;
                }
                String guard = rejectDiscoveredConstraintExpression(translated);
                if (guard != null) {
                    screened++;
                    log.warn("Dropped a discovered constraint candidate for class '{}': {} ({})",
                            fact.classIri(), guard, translated);
                    continue;
                }
                // V43 — the last screen before persistence, and the only one that is about
                // MEANING rather than safety. A candidate that cannot filter a row must never
                // reach the review list: the enforce checkbox next to it is a promise the row
                // cannot keep. Counted separately from `screened` because it is not a defect in
                // the candidate — it is the tuple-pair/single-row mismatch, and the number is
                // what lets the summary say so honestly instead of reporting an empty success.
                String pairOnlyReason = rejectSelfComparisonCandidate(translated);
                if (pairOnlyReason != null) {
                    pairOnly++;
                    log.info("Discovered candidate for class '{}' is not expressible as a row"
                            + " constraint: {} ({})", fact.classIri(), pairOnlyReason, translated);
                    continue;
                }
                if (!seen.add(translated)) {
                    continue; // duplicate of an existing (or already-persisted-this-run) expression
                }
                ModelConstraint candidate = new ModelConstraint(
                        UUID.randomUUID(), tenantUuid(tenant), model.id(),
                        integratedClassIri, translated, false, Instant.now(), "DISCOVERED");
                repository.addConstraint(tenant, candidate);
                persisted++;
            }
        }

        return discoverySummary(discovered, screened, persisted, pairOnly, degradedReason);
    }

    /** An early return that never ran discovery at all: no counts, and no failure to report. */
    private static Map<String, String> discoverySummary(int discovered, int screened,
                                                        int persisted, int pairOnly) {
        return discoverySummary(discovered, screened, persisted, pairOnly, null);
    }

    /**
     * The job substrate's result shape, plus V43's honest account of an empty run and the audit's
     * honest account of a failed one. The two are orthogonal and both are needed: a run can find
     * nothing because there was nothing to find, because everything it found was cross-tuple, or
     * because it never got to look.
     *
     * <p>{@code pairOnly} counts candidates LIMA really did find and this service refused to
     * persist because they are cross-tuple statements with no single-row meaning
     * ({@link #rejectSelfComparisonCandidate}). It is separate from {@code screened} — which
     * counts candidates dropped for a problem (untranslatable, or caught by the injection screen)
     * — because "we found nothing" and "we found things that this product cannot yet express as a
     * row constraint" are different facts, and reporting the second as the first is the empty
     * success V43 exists to stop.
     *
     * <p>{@code degraded}/{@code reason} are present <b>only</b> when something actually failed —
     * their absence is what lets a caller trust a zero as a real zero (audit
     * CONSTRAINT_DISCOVERY-12), so nothing here writes {@code degraded=false}.
     *
     * <p>{@code summary} is the sentence a UI shows verbatim. It is built here, once, rather than
     * left to each caller to phrase: the honest wording is the deliverable, not a presentation
     * detail.
     */
    private static Map<String, String> discoverySummary(int discovered, int screened, int persisted,
                                                        int pairOnly, @Nullable String degradedReason) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("discovered", String.valueOf(discovered));
        summary.put("screened", String.valueOf(screened));
        summary.put("persisted", String.valueOf(persisted));
        summary.put("pairOnly", String.valueOf(pairOnly));
        summary.put("summary", discoveryMessage(persisted, pairOnly, discovered, degradedReason));
        if (degradedReason != null) {
            summary.put("degraded", "true");
            summary.put("reason", degradedReason);
        }
        return summary;
    }

    /** The one sentence {@link #discoverySummary} publishes. See its javadoc for why it lives here. */
    private static String discoveryMessage(int persisted, int pairOnly, int discovered,
                                           @Nullable String degradedReason) {
        // A degraded run outranks every count below: whatever the numbers say, they are a floor,
        // not an answer, and a sentence that reports them as an answer is the empty success both
        // V43 and audit CONSTRAINT_DISCOVERY-12 exist to stop.
        if (degradedReason != null) {
            return "Discovery could not complete — " + degradedReason
                    + ". Any counts below are partial.";
        }
        if (persisted > 0) {
            return persisted + (persisted == 1 ? " candidate" : " candidates")
                    + " ready to review below.";
        }
        if (pairOnly > 0) {
            return "Discovery found " + pairOnly + (pairOnly == 1 ? " dependency" : " dependencies")
                    + " that hold between PAIRS of rows, but none of them can be expressed as a"
                    + " constraint on a single row yet, so nothing was added. Support for"
                    + " row-pair constraints is future work (V43).";
        }
        if (discovered > 0) {
            return "Discovery found " + discovered
                    + (discovered == 1 ? " candidate" : " candidates")
                    + ", but none could be used — see the server log for why.";
        }
        return "Discovery found no candidates for this class.";
    }

    /**
     * Every {@code AS "<iri>"} alias a wrapper's SELECT list carries — a source-property IRI, the
     * exact text LIMA sees as a CSV column header — mapped to the LOCAL NAME a human authors
     * against for that same property: the INTEGRATED-property IRI's local name when the source
     * property is aligned, the source property's own local name otherwise.
     *
     * <p>Mirrors {@code MappingCatalogOperator}'s QUERY 1 {@code targetProp = COALESCE(?ip,
     * ?srcProp)} exactly (via {@link #resolveTargetProperty}) — that {@code targetProp} is what
     * {@code MappingCatalog.SourceView.columnMap()} keys on, what {@code DuckDbExecutor
     * .collectGlobalColumns}/{@code localToGlobal} build the authoring vocabulary from, and what
     * the {@code int_} view's own columns are named by ({@code DuckDbExecutor}'s projection emits
     * {@code srcCol AS "<targetProp>"}). Using the source property's own local name instead — this
     * method's first version did — silently disagreed with that vocabulary for every ALIGNED
     * property: the rewrite either leaves the wrong bare identifier untouched (a column that does
     * not exist in the {@code int_} view — broken DDL) or, worse, coincidentally matches a
     * different property's local name (a wrong-column collision), for every discovered constraint
     * over an aligned property, silently.
     *
     * <p>Skips any IRI whose resolved local name is not a bare SQL identifier ({@code Names
     * .localName} keeps a property's raw fragment, dots and dashes included, since it never
     * sanitizes beyond stripping the namespace). Such a property cannot be referenced in the
     * local-name authoring convention at all — {@link ConstraintRewriter}'s own tokenizer only
     * recognises letters/digits/underscore as identifier characters — which is a pre-existing
     * limitation a hand-authored constraint runs into identically, not a regression this mapping
     * introduces. Excluding the entry here means a candidate mentioning that column is left with
     * an untranslated IRI, which {@link #translateLimaExpression} then rejects as an incomplete
     * translation rather than emitting SQL that could never be re-pointed to the property it names.
     */
    private static final Pattern WRAPPER_ALIAS = Pattern.compile("AS\\s+\"((?:[^\"]|\"\")*)\"",
            Pattern.CASE_INSENSITIVE);

    private static Map<String, String> wrapperOutputLocalNames(String wrapperSql, Model active) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = WRAPPER_ALIAS.matcher(wrapperSql);
        while (m.find()) {
            String srcPropIri = m.group(1).replace("\"\"", "\"");
            String targetPropIri = resolveTargetProperty(active, srcPropIri);
            String local = Names.localName(targetPropIri);
            if (local.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                map.put(srcPropIri, local);
            }
        }
        return map;
    }

    /**
     * The one integrated class {@code srcClassIri} belongs to. Delegates to {@link
     * IntegrationLinks#resolveIntegratedClass} (Phase 4B Task 3 extracted this method's original
     * body there so the harvested-foreign-key mapping could reuse the identical
     * subClassOf/IntegratedResource walk rather than carrying a second copy) — see that class's
     * javadoc for the full resolution contract.
     *
     * <p>A source class carrying {@code nx:wrapperSql} that reaches this method by way of {@link
     * #readCompiledWrapperFacts} is, in a correctly-compiled schema, always subClassOf exactly one
     * such class, so {@code null} here means the active schema does not (yet) integrate this class
     * — compiled but never promoted, a stale caller, or a genuine graph inconsistency.
     */
    private static @Nullable String resolveIntegratedClass(Model active, String srcClassIri) {
        return IntegrationLinks.resolveIntegratedClass(active, srcClassIri);
    }

    /**
     * {@code COALESCE(?ip, ?srcProp)} — QUERY 1's exact rule for a source property's target
     * property. Delegates to {@link IntegrationLinks#resolveTargetProperty}; see that class's
     * javadoc — extracted from this method's original body (Phase 4B Task 3) for reuse by the
     * harvested-foreign-key column mapping.
     */
    private static String resolveTargetProperty(Model active, String srcPropIri) {
        return IntegrationLinks.resolveTargetProperty(active, srcPropIri);
    }

    /**
     * Translate one LIMA-discovered denial-constraint string from wrapper-output-column namespace
     * (its terms are the exact {@code AS "<iri>"} aliases the wrapper's CSV export carried — source
     * property IRIs in production) and LIMA's own predicate syntax into the class's local-name SQL
     * authoring convention — the same one {@link ConstraintRewriter} expects to invert and
     * {@code rejectConstraintExpression} screens.
     *
     * <p>LIMA's shape is fixed and simple ({@code edu.upc.data.schema.Predicate}/{@code Scheduler
     * .populatePredicates}, constraint-discovery module): always exactly {@code "!(" + terms joined
     * by " & " + ")"}, each term {@code "<colX> <OP> <colY>"} with {@code OP} one of {@code
     * EQ,NE,GT,LT,GE,LE}. That lets this be a handful of literal substitutions rather than a parser
     * — the same proportionate choice {@link ConstraintRewriter#rewriteToIriColumns} makes for the
     * inverse direction ("a small hand-tokeniser (no SQL parser) — v1 constraints are simple
     * boolean predicates").
     *
     * <p><b>The result is the constraint instantiated at {@code t1 = t2}, and that is a real loss,
     * not a formatting detail</b> (V43). LIMA quantifies over a pair of distinct tuples but
     * {@code Predicate.toString} renders no {@code t1}/{@code t2} — {@code Schema.buildColumnPairs}
     * only ever pairs a column with itself — so there is no tuple identity in the input for this to
     * preserve. As a ROW filter the output is therefore decided by its operators alone: constant
     * TRUE or FALSE on non-NULL rows, and NULL (hence dropped from both the {@code int_} view and
     * the {@code q_} quarantine) on any NULL-bearing row. That is why {@link #setConstraintEnforced}
     * refuses to enforce what this produces; it is fit to display as evidence, and nothing more.
     *
     * <p>Every column IRI is substituted (longest first, so one IRI can never be swallowed as a
     * substring of another that starts the same way) before the operator words, though the two
     * passes cannot collide: a property's local name would have to literally spell one of the six
     * two-letter operator codes for that to matter, and even then only if it also happened to sit
     * where the grammar puts an operator — the risk is real only in theory.
     *
     * @return the translated SQL predicate, or {@code null} if the expression still contains an
     *         IRI this class's wrapper does not declare (a lookup gap or a non-identifier-safe
     *         local name — see {@link #wrapperOutputLocalNames}) or an operator word this method
     *         does not know, so nothing was silently emitted half-translated.
     */
    private static @Nullable String translateLimaExpression(String limaExpression, Map<String, String> iriToLocal) {
        String expr = limaExpression.trim();
        if (expr.startsWith("!(") && expr.endsWith(")")) {
            expr = "NOT (" + expr.substring(2);
        }

        List<String> iris = new ArrayList<>(iriToLocal.keySet());
        iris.sort(Comparator.comparingInt(String::length).reversed());
        for (String iri : iris) {
            expr = expr.replace(iri, iriToLocal.get(iri));
        }

        expr = expr.replace(" & ", " AND ")
                .replace(" EQ ", " = ")
                .replace(" NE ", " <> ")
                .replace(" GE ", " >= ")
                .replace(" LE ", " <= ")
                .replace(" GT ", " > ")
                .replace(" LT ", " < ");

        if (expr.contains("://") || expr.matches("(?s).*\\b(EQ|NE|GT|LT|GE|LE)\\b.*")) {
            return null; // an IRI or operator token this method could not map — incomplete, drop it
        }
        return expr;
    }

    /**
     * <b>V43.</b> Reject a translated candidate whose every term compares a column with
     * <em>itself</em> — a predicate that cannot filter a single row, whatever the data says.
     *
     * <p><b>The mismatch, in one line: LIMA states its constraints over a PAIR of rows, a
     * {@link ModelConstraint} is a predicate over ONE row, and the translation has no tuple
     * identity to preserve because the string never carried one.</b> {@code Schema
     * .buildColumnPairs} (constraint-discovery) builds only SELF pairs — {@code ColumnPair(col,
     * col)} — and {@code CSVDataset.sample} draws pairs of DISTINCT tuples, which is what makes
     * "no two rows agree on A while differing on B" meaningful. {@code Predicate.toString} then
     * renders {@code x.name + " " + op + " " + y.name} with no t1/t2 anywhere, so an FD arrives
     * here as {@code !(colA EQ colA & colB NE colB)} and {@link #translateLimaExpression} — a
     * literal substitution, correctly — turns it into {@code NOT (colA = colA AND colB <> colB)}.
     *
     * <p>On one row each such term is decided by its operator alone: {@code =}/{@code >=}/{@code <=}
     * are TRUE and {@code <>}/{@code >}/{@code <} are FALSE, so the conjunction is constant and so
     * is its negation. Instantiating a denial constraint at t1 = t2 is a sound <em>weakening</em>,
     * so no wrong row was ever produced — but the review-and-accept affordance led nowhere: a user
     * who read a candidate, agreed with it and enforced it got no filtering, no plan change and no
     * warning.
     *
     * <p><b>And it is not even reliably harmless — it is worse than "vacuous".</b> Under SQL's
     * three-valued logic {@code NOT (a = a AND b <> b)} is TRUE for every row <em>except</em> one
     * where {@code b} — the column of the {@code <>} term — IS NULL. {@code b <> b} is then
     * UNKNOWN, and {@code X AND UNKNOWN} is UNKNOWN whatever {@code a} is (so {@code a}'s own
     * nullity is irrelevant: the row drops whenever {@code b} is NULL), and the negation of
     * UNKNOWN is UNKNOWN.
     *
     * <p>Such a row is <b>not quarantined — it disappears from both views.</b>
     * {@code DuckDbExecutor.buildDdl} filters {@code int_} with {@code WHERE (c)} and fills
     * {@code q_} with {@code WHERE NOT ((c))}; UNKNOWN satisfies neither, which that method's own
     * javadoc states ("A NULL constraint column lands in neither view"). So an enforced
     * "tautology" silently <em>deletes</em> every row with a NULL in the {@code <>} column — not
     * into quarantine where someone could find it, but out of the class entirely. That is the
     * half of V43 that makes never persisting these the right call rather than a tidiness
     * preference.
     *
     * <p><b>This is deliberately NOT total, and that is load-bearing.</b> It rejects only what it
     * can prove trivial: every recognised term must have textually identical operands. A term
     * comparing two <em>different</em> columns ({@code a = b}), a term against a literal, or a term
     * whose shape this does not recognise all make the candidate survive — because such a candidate
     * IS a real row predicate. No such candidate exists today ({@code buildColumnPairs} cannot
     * produce one), so the observable outcome is that discovery persists nothing; the filter is
     * written for the tuple-aware storage that is deferred, not against it, and
     * {@code IntegrationModelServiceDiscoverConstraintsTest} pins the non-totality directly.
     *
     * @return a reason to record, or {@code null} when the candidate is a genuine row predicate
     */
    static @Nullable String rejectSelfComparisonCandidate(String expression) {
        String body = expression.trim();
        if (body.startsWith("NOT (") && body.endsWith(")")) {
            body = body.substring("NOT (".length(), body.length() - 1);
        }
        String[] terms = body.split(" AND ");
        for (String term : terms) {
            Matcher m = COMPARISON_TERM.matcher(term.trim());
            if (!m.matches() || !m.group(1).equals(m.group(3))) {
                // Either a real comparison between two different operands, or a shape this cannot
                // read. Both mean "not provably trivial", and the candidate stands.
                return null;
            }
        }
        return "every term compares a column with itself, so it is constant on a single row"
                + " — LIMA states this over a PAIR of rows and a constraint is a predicate over"
                + " one (V43)";
    }

    /**
     * One {@code <operand> <op> <operand>} term of a translated candidate. The operand pattern is
     * deliberately loose (any run of non-space, non-operator characters) because the only question
     * asked of it is whether the two sides are the same text — see
     * {@link #rejectSelfComparisonCandidate}, which treats anything this does not match as a
     * candidate to keep.
     */
    private static final Pattern COMPARISON_TERM =
            Pattern.compile("([^\\s<>=!]+)\\s*(=|<>|>=|<=|>|<)\\s*([^\\s<>=!]+)");

    /**
     * {@code QueryEngineOperator.rejectConstraintExpression}'s screen, duplicated rather than
     * shared: {@code query-engine} is a Calcite/DuckDB module aran-core cannot depend on (same
     * reason {@link #WRAPPER_SQL_IRI} above is a second copy of a query-engine constant), and this
     * is the security-relevant half only — the injection guard plus {@link WrapperSqlValidator}'s
     * forbidden-construct check — not the EXPLAIN-based column/syntax check {@code
     * validateConstraint} also runs. Skipping EXPLAIN here is deliberate: it would need a real
     * DuckDB connection per candidate, and a discovery run can produce many. If either copy of the
     * security screen changes, change both — {@code ConstraintInjectionTest} pins the twin in
     * query-engine.
     *
     * <p><b>This screen judges SAFETY only, never meaning</b> — meaning is screened separately, and
     * a DISCOVERED row is defended twice over, by two independent mechanisms that were designed
     * apart and are both kept:
     *
     * <ol>
     *   <li>{@link #rejectSelfComparisonCandidate} refuses to PERSIST a candidate whose every term
     *       compares a column with itself (V43). <b>Trivial is not harmless</b> — as a row filter
     *       the single-row instantiation is TRUE or FALSE for every non-NULL row and NULL for every
     *       NULL-bearing one, so it would either filter nothing, quarantine the whole class, or
     *       silently drop the NULL-bearing rows from BOTH the {@code int_} and {@code q_} views.
     *       A review list must not offer an enforce checkbox the row cannot honour.</li>
     *   <li>{@link #setConstraintEnforced} refuses to ENFORCE any {@code DISCOVERED} row at all
     *       (audit CONSTRAINT_DISCOVERY-1, decision 2) — a class-wide gate that does not depend on
     *       this method reading any particular expression shape correctly.</li>
     * </ol>
     *
     * <p>The second is the load-bearing one: it holds for every candidate, including the ones the
     * first is deliberately loose about. The first exists so the review list stays honest rather
     * than full of rows whose only possible answer is "no".</p>
     */
    private static @Nullable String rejectDiscoveredConstraintExpression(String expression) {
        if (expression.isBlank()) {
            return "Constraint expression is empty.";
        }
        if (expression.contains(";") || expression.contains("--") || expression.contains("/*")) {
            return "Constraint may not contain ';', '--' or '/*'.";
        }
        String forbidden = WrapperSqlValidator.findForbiddenConstruct(expression);
        if (forbidden != null) {
            return "Forbidden construct — " + forbidden
                    + ". A constraint may only reference the class's own columns.";
        }
        return null;
    }

    /**
     * {@code constraint-discovery}'s {@code discover} result carries {@code constraints} as a
     * JSON-encoded array *string* (not a nested JSON array) — mirrors {@code suggest}'s own
     * {@code candidates} parsing above, tree-walked rather than bound to a generic type for the
     * same reason: {@code readTree} is what this file already does and already catches.
     */
    private List<String> parseConstraintsJson(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(json);
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String text = n.asText(null);
                if (text != null) {
                    out.add(text);
                }
            }
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            log.warn("Constraint discovery returned a constraints payload this service could not parse: {}",
                    json, ex);
        }
        return out;
    }
}

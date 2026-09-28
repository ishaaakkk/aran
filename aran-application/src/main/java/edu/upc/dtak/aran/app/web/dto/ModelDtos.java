package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.MappingApplication;
import edu.upc.dtak.aran.core.domain.ModelBundle;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.service.IntegrationModelService.IntegrationState;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Data-transfer objects for the integration-model API.
 *
 * <p>The read shapes mirror the V16 tables; a client renders {@code GET
 * /api/projects/{id}/model} straight from {@link ModelBundleDto}. A mapping's
 * {@code evidence} is stored as JSONB and surfaced here as parsed JSON so the
 * suggestion review queue can read the score fields directly.
 */
public final class ModelDtos {

    private ModelDtos() {
    }

    public record ModelBundleDto(
            IntegrationModelDto model,
            List<ModelSourceDto> sources,
            List<ModelMappingDto> mappings,
            List<ModelConstraintDto> constraints,
            /** Datasets already in the active integrated graph — an incremental add of one of
             *  these is rejected as SOURCE_ALREADY_INTEGRATED, so the UI filters them out. */
            List<UUID> integratedDatasetIds,
            // The compiled draft still awaiting promotion, so a reload does not lose it (J4-F2).
            @Nullable DraftRunDto latestDraftRun,
            // The version the model's last promote made ACTIVE; an incremental add is refused
            // STALE_INCREMENTAL_BASE unless it still is activeVersion.
            @Nullable UUID baselineVersionId,
            // The tenant's ACTIVE schema version — tenant-wide, not this project's.
            @Nullable VersionRefDto activeVersion,
            // A compile or incremental add of this project still running, so a reload re-attaches
            // to it instead of offering a second one (review UA5, J4-F5).
            @Nullable CompileJobDto compileJob
    ) {
        /**
         * @param activeClassIris the ACTIVE catalog's integrated classes, used only to decide each
         *                        constraint's {@code orphaned} flag — see {@link ModelConstraintDto}.
         * @param versionOf       looks a schema version up by id: a DRAFT run whose version is no
         *                        longer a DRAFT (promoted through the generic path before J4-F2) is
         *                        not offered as promotable
         */
        public static ModelBundleDto from(ModelBundle bundle, ObjectMapper objectMapper,
                                          List<UUID> integratedDatasetIds,
                                          Set<String> activeClassIris,
                                          IntegrationState state,
                                          Function<UUID, Optional<GraphVersion>> versionOf,
                                          @Nullable Job compileJob) {
            Map<UUID, MappingApplication> applications = new HashMap<>();
            for (MappingApplication a : state.applications()) {
                applications.put(a.mappingId(), a);
            }
            IntegrationRun draftRun = state.latestDraftRun();
            DraftRunDto draft = null;
            if (draftRun != null && draftRun.draftVersionId() != null) {
                Optional<GraphVersion> version = versionOf.apply(draftRun.draftVersionId());
                if (version.isPresent() && "DRAFT".equals(version.get().status())) {
                    draft = new DraftRunDto(draftRun.id(), draftRun.mode().name(),
                            draftRun.draftVersionId(), version.get().versionNumber(), draftRun.updatedAt());
                }
            }
            GraphVersion active = state.activeVersion();
            return new ModelBundleDto(
                    IntegrationModelDto.from(bundle.model()),
                    bundle.sources().stream().map(ModelSourceDto::from).toList(),
                    bundle.mappings().stream()
                            .map(m -> ModelMappingDto.from(m, objectMapper, applications.get(m.id())))
                            .toList(),
                    bundle.constraints().stream().map(c -> ModelConstraintDto.from(c, activeClassIris)).toList(),
                    integratedDatasetIds,
                    draft,
                    state.baselineVersionId(),
                    active == null ? null : new VersionRefDto(active.id(), active.versionNumber()),
                    compileJob == null ? null
                            : new CompileJobDto(compileJob.id(), compileJob.jobType(), compileJob.status().name()));
        }
    }

    /** A running {@code model:compile} or {@code model:incremental-integrate} job; {@code status} PENDING or RUNNING. */
    public record CompileJobDto(UUID jobId, String jobType, String status) {
    }

    /** A DRAFT integration run and the schema version it compiled; {@code mode} is REBUILD or INCREMENTAL. */
    public record DraftRunDto(UUID runId, String mode, UUID draftVersionId, int draftVersionNumber,
                              Instant compiledAt) {
    }

    public record VersionRefDto(UUID id, int versionNumber) {
    }

    /**
     * Where an accepted alignment stands (V19's application row): {@code PENDING} not in any
     * compiled draft yet, {@code APPLIED} compiled into {@code versionId}, or {@code DEFERRED} with
     * the compiler's {@code reason}. {@code versionId} is the version that COMPILED it, not the one
     * serving it: an incremental run re-sends only PENDING/DEFERRED mappings and carries the rest
     * forward, so an APPLIED row is live while the model's baseline is the ACTIVE version and the
     * row is not the pending draft's (J4-F5; {@code IncrementalIntegrationServiceTest}).
     */
    public record MappingApplicationDto(String state, @Nullable UUID versionId, @Nullable String reason) {
        public static @Nullable MappingApplicationDto from(@Nullable MappingApplication a) {
            return a == null ? null : new MappingApplicationDto(a.state().name(), a.versionId(), a.reason());
        }
    }

    /**
     * The model header. {@code activeIntegrationRunId} and {@code rebuildRequired} are the two
     * flags the UI needs to decide between a full rebuild and an incremental add: a null active
     * run means no promoted baseline exists yet (so only a rebuild is possible), and
     * {@code rebuildRequired} means integrated material changed underneath the baseline.
     */
    public record IntegrationModelDto(UUID id, UUID projectId, Instant createdAt, Instant updatedAt,
                                      @Nullable UUID activeIntegrationRunId, boolean rebuildRequired) {
        public static IntegrationModelDto from(IntegrationModel m) {
            return new IntegrationModelDto(m.id(), m.projectId(), m.createdAt(), m.updatedAt(),
                    m.activeIntegrationRunId(), m.rebuildRequired());
        }
    }

    /**
     * {@code expansionStatus}/{@code expansionReason} are Phase 3A's compile-time verify-wrapper
     * verdict for {@code wrapperSql} (V30); both {@code null} means never verified — the UI's
     * wrapper-expansion badge (Task 7) renders that as "not yet checked", not as a third status
     * to special-case alongside EXPANDED/OPAQUE.
     *
     * <p>{@code wrapperEdited} (V47) is false while {@code wrapperSql} is still the copy bootstrap
     * produced — such a wrapper follows the source across a refresh — and true once a person has
     * saved their own through the wrapper endpoint, after which it is never overwritten and a
     * compile validates it against the current source schema instead.
     */
    public record ModelSourceDto(UUID id, UUID datasetId, String classIri, @Nullable String wrapperSql,
                                 @Nullable String expansionStatus, @Nullable String expansionReason,
                                 boolean wrapperEdited) {
        public static ModelSourceDto from(ModelSource s) {
            return new ModelSourceDto(s.id(), s.datasetId(), s.classIri(), s.wrapperSql(),
                    s.expansionStatus(), s.expansionReason(), s.wrapperEdited());
        }
    }

    public enum MappingKindDto {
        @com.fasterxml.jackson.annotation.JsonProperty("class") CLASS,
        @com.fasterxml.jackson.annotation.JsonProperty("datatype") DATATYPE,
        @com.fasterxml.jackson.annotation.JsonProperty("object") OBJECT,
        @com.fasterxml.jackson.annotation.JsonProperty("join") JOIN;

        public static MappingKindDto fromDomain(String kind) {
            return switch (kind) {
                case "class" -> CLASS;
                case "datatype" -> DATATYPE;
                case "object" -> OBJECT;
                case "join" -> JOIN;
                default -> throw new IllegalArgumentException("Unknown kind: " + kind);
            };
        }
    }

    public enum MappingStatusDto {
        SUGGESTED, ACCEPTED, REJECTED;

        public static MappingStatusDto fromDomain(String status) {
            return valueOf(status);
        }
    }

    public record ModelMappingDto(
            UUID id, MappingKindDto kind, String sourceIri, String targetIri,
            @Nullable String label, MappingStatusDto status, @Nullable JsonNode evidence,
            @Nullable UUID author, @Nullable String comment, Instant createdAt,
            // Null on a write's echo and for a mapping never accepted; see the bundle read.
            @Nullable MappingApplicationDto application
    ) {
        public static ModelMappingDto from(ModelMapping m, ObjectMapper objectMapper) {
            return from(m, objectMapper, null);
        }

        public static ModelMappingDto from(ModelMapping m, ObjectMapper objectMapper,
                                           @Nullable MappingApplication application) {
            JsonNode evidence = null;
            if (m.evidenceJson() != null && !m.evidenceJson().isBlank()) {
                try {
                    evidence = objectMapper.readTree(m.evidenceJson());
                } catch (JacksonException ex) {
                    throw new IllegalStateException("corrupt mapping evidence JSON for mapping " + m.id(), ex);
                }
            }
            return new ModelMappingDto(m.id(), MappingKindDto.fromDomain(m.kind()), m.sourceIri(), m.targetIri(),
                    m.label(), MappingStatusDto.fromDomain(m.status()), evidence, m.author(), m.comment(), m.createdAt(),
                    MappingApplicationDto.from(application));
        }
    }

    /**
     * {@code origin} is {@code "MANUAL"} or {@code "DISCOVERED"} (Phase 3B) — a label for the
     * review UI, not a different code path: a DISCOVERED row is enforced through the same
     * {@code PATCH .../constraints/{id}} as a MANUAL one.
     *
     * <p><b>{@code enforced} means enforced <em>and live</em>, not "the stored flag" (V42).</b> A
     * constraint whose {@code classIri} is no longer a class of the ACTIVE catalog — the usual way
     * being an alignment that merges its class into a new integrated one — is skipped by
     * {@code QueryEngineOperator.enforcedByClass}, so it filters nothing and quarantines nothing.
     * The row itself is untouched by the merge, and reporting its stored {@code enforced=true} is
     * what made the API assert a guarantee that had already stopped holding. Such a row is reported
     * {@code orphaned=true} and {@code enforced=false}: a client that reads only the older field
     * now gets the truth, and one that reads {@code orphaned} gets the reason.
     *
     * <p>The stored intent is not lost — it is still in {@code model_constraint}, so a later
     * integration that brings the class back reports the row as enforced again with no user action.
     */
    public record ModelConstraintDto(UUID id, String classIri, String expression,
                                     boolean enforced, Instant createdAt, String origin,
                                     boolean orphaned) {

        /**
         * @param activeClassIris the integrated classes the ACTIVE catalog serves — from
         *                        {@code IntegrationModelService.activeModelFacts} on the bundle
         *                        path (one graph read for this and the dataset ids, V49) and from
         *                        {@code activeIntegratedClassIris} where only this is wanted.
         *                        <b>Empty means "no active schema at all"</b> — nothing has been
         *                        promoted, or it was rolled back to empty — which is not the same
         *                        as "every class is gone", so nothing is marked orphaned in that
         *                        case. Marking every constraint of an un-promoted project orphaned
         *                        would be technically true and practically noise: a constraint can
         *                        legitimately be authored before the first compile
         *                        ({@code validate-constraint} accepts a class it cannot find), and
         *                        V42 is about a class that <em>went away</em>.
         */
        public static ModelConstraintDto from(ModelConstraint c, Set<String> activeClassIris) {
            boolean orphaned = !activeClassIris.isEmpty() && !activeClassIris.contains(c.classIri());
            return new ModelConstraintDto(c.id(), c.classIri(), c.expression(),
                    c.enforced() && !orphaned, c.createdAt(), c.origin(), orphaned);
        }
    }

    // ── request bodies ──────────────────────────────────────────────────────

    public record AddSourceRequest(UUID datasetId) {
    }

    /** Wrapper edit. {@code classIri} rides in the body (not the path) so IRIs with slashes survive. */
    public record WrapperRequest(String classIri, @Nullable String sql) {
    }

    public record AddMappingRequest(MappingKindDto kind, String sourceIri, String targetIri,
                                    @Nullable String label, @Nullable String comment) {
    }

    public record SuggestRequest(UUID leftDatasetId, UUID rightDatasetId) {
    }

    public record MappingStatusRequest(MappingStatusDto status) {
    }

    public record AddConstraintRequest(String classIri, String expression) {
    }

    public record ConstraintEnforcedRequest(boolean enforced) {
    }

    /**
     * {@code POST .../constraints/discover} — {@code classIri} rides in the body like
     * {@link WrapperRequest}, for the same reason (an IRI with slashes must survive).
     */
    public record DiscoverConstraintsRequest(UUID datasetId, String classIri) {
    }

    public record PromoteRunResponse(String versionId, String versionNumber) {
    }
}

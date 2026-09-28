package edu.upc.dtak.aran.adapter.persistence.jpa;

import edu.upc.dtak.aran.adapter.persistence.entity.AuditEventEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.CubeDefinitionEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.TenantGraphVersionEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.DataProductEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.DataRepositoryEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.DatasetEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.DatasetRefreshEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.DatasetSchemaFingerprintEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.DeclaredForeignKeyEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.IntegrationModelEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.IntegrationRunEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.JobEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.LicenseEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ModelConstraintEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ModelMappingApplicationEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ModelMappingEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ModelSourceEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.MembershipEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ProjectEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ProjectMemberEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.RepositoryConnectionCheckEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.TenantEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.UserEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.TenantRole;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repositories.
 *
 * <p>These are an <em>internal</em> detail of the persistence adapter — the
 * core never sees them. The hand-written port implementations in this package
 * delegate to these and map entities &harr; core domain records.
 *
 * <p>Kept in one file because they are small, closely related, and only ever
 * used together by the port adapters next to them.
 */
public final class JpaRepositories {

    private JpaRepositories() {
    }

    /** Users — not tenant-scoped; looked up by Keycloak subject during provisioning. */
    public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {
        @org.springframework.data.jpa.repository.Query(
                "SELECT u.id, u.lastSeenAt FROM UserEntity u WHERE u.id IN :ids")
        java.util.List<Object[]> lastSeenFor(
                @org.springframework.data.repository.query.Param("ids") java.util.Collection<UUID> ids);

        Optional<UserEntity> findBySubject(String subject);

        /**
         * Stamp last-seen without loading the row. A modifying query rather than a read-then-save
         * because this runs on the authentication path: loading and re-persisting a user to write
         * one timestamp would double the cost of the thing being throttled.
         */
        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query(
                "UPDATE UserEntity u SET u.lastSeenAt = :at WHERE u.id = :userId")
        void touchLastSeen(@org.springframework.data.repository.query.Param("userId") UUID userId,
                           @org.springframework.data.repository.query.Param("at") java.time.Instant at);
    }

    /** Tenants — the isolation boundary; not itself tenant-filtered. */
    public interface TenantJpaRepository extends JpaRepository<TenantEntity, UUID> {
        Optional<TenantEntity> findBySlug(String slug);
    }

    /** Memberships — answers "which tenants may this user act in?". */
    public interface MembershipJpaRepository extends JpaRepository<MembershipEntity, UUID> {
        List<MembershipEntity> findByUserId(UUID userId);

        Optional<MembershipEntity> findByUserIdAndTenantId(UUID userId, UUID tenantId);

        List<MembershipEntity> findByTenantId(UUID tenantId);

        /** Count memberships in a tenant — backs {@code UsageCounter.countUsers}. */
        long countByTenantId(UUID tenantId);

        /** Count OWNERs in a tenant — for the last-owner safeguard. */
        long countByTenantIdAndRole(UUID tenantId, TenantRole role);
    }

    /**
     * Projects. List queries go through the {@code tenantFilter} Hibernate filter
     * (enabled per request); the visibility-aware queries additionally join
     * {@code project_member}.
     *
     * <p><b>By-id reads name the tenant explicitly</b> — see the note on
     * {@link #findByIdAndTenantId}: the filter does not apply to a load by primary
     * key, so {@code findById} alone is not a tenant boundary.
     */
    public interface ProjectJpaRepository extends JpaRepository<ProjectEntity, UUID> {

        /**
         * The tenant-safe by-id read.
         *
         * <p>Hibernate consults {@code @Filter} when it plans a <em>query</em>, but a
         * load by primary key is resolved through the persistence context and the
         * entity loader, which never see the filter. A plain {@code findById(id)}
         * therefore returns whichever row holds that id, whoever owns it. Naming
         * {@code tenantId} in the predicate turns the read back into a query, and the
         * boundary becomes real. {@code CrossTenantByIdIT} pins this.
         */
        Optional<ProjectEntity> findByIdAndTenantId(UUID id, UUID tenantId);

        /**
         * Both visibility queries name {@code tenantId} themselves rather than leaving it
         * to the {@code tenantFilter}.
         *
         * <p>Membership is user-to-tenant, and a user may belong to more than one tenant
         * (see {@code /api/me}), so filtering on membership alone answers "every project
         * this person can see anywhere" — not "every project they can see in the tenant
         * this request is bound to". The JWT's {@code tenant_id} is authoritative, so the
         * second is the only correct answer. That the filter usually supplies it is not
         * enough: it demonstrably was not in force on at least one path, which is how a
         * tenant's promote came to read another tenant's graph version.
         */
        @Query("""
                select p from ProjectEntity p
                where p.id = :projectId
                  and p.tenantId = :tenantId
                  and exists (
                      select 1 from ProjectMemberEntity pm
                      where pm.projectId = p.id and pm.userId = :userId
                  )
                """)
        Optional<ProjectEntity> findVisibleToUser(@Param("projectId") UUID projectId,
                                                  @Param("userId") UUID userId,
                                                  @Param("tenantId") UUID tenantId);

        @Query("""
                select p from ProjectEntity p
                where p.tenantId = :tenantId
                  and exists (
                      select 1 from ProjectMemberEntity pm
                      where pm.projectId = p.id and pm.userId = :userId
                  )
                order by p.createdAt desc
                """)
        List<ProjectEntity> findAllVisibleToUser(@Param("userId") UUID userId,
                                                 @Param("tenantId") UUID tenantId);

        /**
         * Count projects in a tenant — backs {@code UsageCounter.countProjects}.
         * Bypasses the @Filter (no session-bound filter inside this query path)
         * by explicit tenant_id predicate.
         */
        long countByTenantId(UUID tenantId);
    }

    /** Project-member visibility rows. */
    public interface ProjectMemberJpaRepository extends JpaRepository<ProjectMemberEntity, UUID> {
        List<ProjectMemberEntity> findByProjectId(UUID projectId);

        Optional<ProjectMemberEntity> findByProjectIdAndUserId(UUID projectId, UUID userId);

        /**
         * One visibility row, scoped through its project's tenant: {@code project_member} carries
         * no tenant column of its own, so the subquery is what keeps a guessed project id from
         * another tenant out of reach.
         */
        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("""
                DELETE FROM ProjectMemberEntity m
                WHERE m.projectId = :projectId AND m.userId = :userId
                  AND m.projectId IN (SELECT p.id FROM ProjectEntity p WHERE p.tenantId = :tenantId)
                """)
        int deleteMembership(@Param("projectId") UUID projectId, @Param("userId") UUID userId,
                             @Param("tenantId") UUID tenantId);

        /** Every visibility row a user holds in one tenant's projects — tenant removal (UI-OD9). */
        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("""
                DELETE FROM ProjectMemberEntity m
                WHERE m.userId = :userId
                  AND m.projectId IN (SELECT p.id FROM ProjectEntity p WHERE p.tenantId = :tenantId)
                """)
        int deleteAllForUserInTenant(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);
    }

    /**
     * Audit-event entries. Append-only — no update or delete is exposed.
     *
     * <p>The read names {@code tenant_id} itself. It previously did not, on the stated
     * grounds that the {@code tenantFilter} made "even findAll-style queries
     * tenant-scoped" — the same assumption that held for graph versions right up until
     * it did not, letting one tenant's promote read another's version. An audit log is
     * the last place to leave that resting on an aspect: an unscoped read returns the
     * most recent events across every tenant in the installation.
     */
    public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {
        List<AuditEventEntity> findAllByTenantIdOrderByOccurredAtDesc(UUID tenantId, Limit limit);

        /**
         * The dashboard feed for a member (UI-OD11): lines that target one of the projects, or
         * carry one as their {@code projectId} metadata. Native because JPQL cannot read a JSONB
         * key; {@code idx_audit_event_tenant_occurred} still serves the tenant + order.
         */
        @Query(value = """
                SELECT * FROM audit_event e
                WHERE e.tenant_id = :tenantId
                  AND ((e.target_type = 'project' AND e.target_id IN (:projectIds))
                       OR (e.metadata ->> 'projectId') IN (:projectIdTexts))
                ORDER BY e.occurred_at DESC
                LIMIT :limit
                """, nativeQuery = true)
        List<AuditEventEntity> findRecentForProjects(@Param("tenantId") UUID tenantId,
                                                     @Param("projectIds") java.util.Collection<UUID> projectIds,
                                                     @Param("projectIdTexts") java.util.Collection<String> projectIdTexts,
                                                     @Param("limit") int limit);

        /**
         * Whether the tenant recorded {@code action} with one metadata key equal to a value — the
         * reinstatement gate's "did this organization remove that user?" (UI-OD9). Native for the
         * same JSONB reason as the feed; tenant-scoped in its own predicate.
         */
        @Query(value = """
                SELECT EXISTS (
                    SELECT 1 FROM audit_event e
                    WHERE e.tenant_id = :tenantId
                      AND e.action = :action
                      AND (e.metadata ->> CAST(:metadataKey AS text)) = :metadataValue)
                """, nativeQuery = true)
        boolean existsByMetadata(@Param("tenantId") UUID tenantId,
                                 @Param("action") String action,
                                 @Param("metadataKey") String metadataKey,
                                 @Param("metadataValue") String metadataValue);
    }

    /** Cloud-mode licenses — one row per tenant, keyed by tenant_id. */
    public interface LicenseJpaRepository extends JpaRepository<LicenseEntity, UUID> {
    }

    /**
     * Data Repositories. Every finder names {@code tenantId}, list queries included —
     * the {@code tenantFilter} Hibernate filter is defence in depth behind them, not the
     * guarantee (see {@link DatasetJpaRepository} for why).
     */
    public interface DataRepositoryJpaRepository extends JpaRepository<DataRepositoryEntity, UUID> {
        List<DataRepositoryEntity> findAllByProjectIdAndTenantId(UUID projectId, UUID tenantId);

        Optional<DataRepositoryEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    }

    /**
     * Datasets. Every finder names {@code tenantId} explicitly, by-id and list alike.
     *
     * <p>By-id, because Hibernate never applies a {@code @Filter} to a load by primary key.
     * By list, because the filter is only reliably enabled inside a transaction: audit
     * AUTH_IDENTITY-3 — {@code TenantFilterAspect} enables it on a {@code Session} unwrapped from
     * the shared {@code EntityManager}, and with {@code open-in-view: false} and no ambient
     * transaction that is a throwaway {@code EntityManager}, not the one the query then runs on.
     * {@code DashboardController.summary} calls both list paths with no transaction.
     * {@code CrossTenantListWithoutTransactionIT} is what pins it.
     */
    public interface DatasetJpaRepository extends JpaRepository<DatasetEntity, UUID> {
        List<DatasetEntity> findAllByRepositoryIdAndTenantId(UUID repositoryId, UUID tenantId);

        Optional<DatasetEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    }

    /**
     * Jobs.
     *
     * <p>Every finder here names {@code tenantId} explicitly rather than leaning on
     * the {@code tenantFilter} Hibernate filter. The filter is applied to queries,
     * but <em>not</em> to a load by primary key (Hibernate resolves those through
     * the session/second-level cache before any filter is consulted), so a plain
     * {@code findById} would happily hand back another tenant's job. The
     * {@code @Filter} on {@code JobEntity} stays as defence in depth for the list
     * queries; these predicates are the actual guarantee.
     */
    public interface JobJpaRepository extends JpaRepository<JobEntity, UUID> {
        Optional<JobEntity> findByIdAndTenantId(UUID id, UUID tenantId);

        List<JobEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Limit limit);

        List<JobEntity> findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(
                UUID tenantId, UUID projectId, Limit limit);

        /**
         * The startup sweep — the first and only reader of V15's {@code idx_job_status}.
         *
         * <p>Deliberately tenant-blind, unlike every other finder here: it runs before any request,
         * so there is no tenant to scope by, and an orphan left standing in some other tenant is an
         * orphan the UI spins on forever. That is safe from the {@code tenantFilter} on
         * {@code JobEntity} twice over — Hibernate does not apply filters to bulk HQL updates, and
         * {@code TenantFilterAspect} only enables the filter when a tenant is bound or is the first
         * argument, neither of which holds at startup.
         */
        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("""
                update JobEntity j
                   set j.status = edu.upc.dtak.aran.core.domain.JobStatus.FAILED,
                       j.errorCode = :errorCode,
                       j.errorMessage = :errorMessage,
                       j.finishedAt = :finishedAt
                 where j.status in (edu.upc.dtak.aran.core.domain.JobStatus.PENDING,
                                    edu.upc.dtak.aran.core.domain.JobStatus.RUNNING)
                """)
        int failNonTerminal(@Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage,
                            @Param("finishedAt") Instant finishedAt);
    }

    /**
     * Data products (V18). Every finder names {@code tenantId} explicitly for the same
     * reason {@link JobJpaRepository}'s do; the {@code @Filter} on the entity stays as
     * defence in depth for the list queries.
     */
    public interface DataProductJpaRepository extends JpaRepository<DataProductEntity, UUID> {
        Optional<DataProductEntity> findByIdAndTenantId(UUID id, UUID tenantId);

        List<DataProductEntity> findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(
                UUID tenantId, UUID projectId);

        List<DataProductEntity> findAllByTenantIdAndStatus(UUID tenantId, DataProductStatus status);

        /** Every product of a tenant, across projects — the JDBC `products` schema is tenant-wide. */
        List<DataProductEntity> findAllByTenantIdOrderByCreatedAtAsc(UUID tenantId);

        /**
         * The startup sweep's product half: every MATERIALIZING row becomes FAILED. Tenant-blind
         * for the same reason as {@link JobJpaRepository#failNonTerminal} — nothing is bound at
         * startup, and a bulk HQL update is not subject to the tenant filter.
         */
        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("""
                update DataProductEntity p
                   set p.status = edu.upc.dtak.aran.core.domain.DataProductStatus.FAILED,
                       p.lastError = :error
                 where p.status = edu.upc.dtak.aran.core.domain.DataProductStatus.MATERIALIZING
                """)
        int failMaterializing(@Param("error") String error);
    }

    /**
     * Cubes (V40). Every finder names {@code tenantId} explicitly for the same reason
     * {@link DataProductJpaRepository}'s do — the {@code tenantFilter} Hibernate filter is not
     * consulted on a load by primary key, and is only reliably enabled inside a transaction.
     */
    public interface CubeDefinitionJpaRepository extends JpaRepository<CubeDefinitionEntity, UUID> {
        Optional<CubeDefinitionEntity> findByIdAndTenantId(UUID id, UUID tenantId);

        /** A project's cubes, newest first — the order {@code CubeRepository.list} promises. */
        List<CubeDefinitionEntity> findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(
                UUID tenantId, UUID projectId);
    }

    /** API tokens (V21). */
    public interface ApiTokenJpaRepository
            extends JpaRepository<edu.upc.dtak.aran.adapter.persistence.entity.ApiTokenEntity, UUID> {

        Optional<edu.upc.dtak.aran.adapter.persistence.entity.ApiTokenEntity>
                findByIdAndTenantId(UUID id, UUID tenantId);

        List<edu.upc.dtak.aran.adapter.persistence.entity.ApiTokenEntity>
                findAllByTenantIdAndUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);

        List<edu.upc.dtak.aran.adapter.persistence.entity.ApiTokenEntity>
                findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

        /**
         * Unscoped by design: this is the authentication lookup, and the token is what establishes
         * the tenant. Every other finder here names tenant_id.
         */
        Optional<edu.upc.dtak.aran.adapter.persistence.entity.ApiTokenEntity>
                findByTokenHash(String tokenHash);
    }

    /**
     * Tenant graph versions. Every read names {@code tenant_id} in its own predicate
     * rather than trusting the {@code tenantFilter} Hibernate filter to be in force.
     *
     * <p>The unscoped forms these replace — {@code findByStatus},
     * {@code findFirstByOrderByVersionNumberDesc} and
     * {@code findAllByOrderByVersionNumberDesc} — returned whichever tenant's row sorted
     * first across the whole table on any path where the filter was not applied, and
     * promotion turned out to be exactly such a path: one tenant's promote read another
     * tenant's graph version, caught only by the write-side guard in
     * {@code EntityMapper.toEntity}. A cross-tenant read is not something to leave
     * resting on an aspect being active.
     */
    public interface TenantGraphVersionJpaRepository extends JpaRepository<TenantGraphVersionEntity, UUID> {
        Optional<TenantGraphVersionEntity> findByIdAndTenantId(UUID id, UUID tenantId);

        Optional<TenantGraphVersionEntity> findByTenantIdAndStatus(UUID tenantId, String status);

        Optional<TenantGraphVersionEntity> findFirstByTenantIdOrderByVersionNumberDesc(UUID tenantId);

        /** All of this tenant's versions, newest number first. */
        List<TenantGraphVersionEntity> findAllByTenantIdOrderByVersionNumberDesc(UUID tenantId);
    }

    /**
     * Integration models. The by-id and by-project reads name the tenant explicitly,
     * because the {@code tenantFilter} Hibernate filter does not apply to a load by
     * primary key (see {@link ProjectJpaRepository#findByIdAndTenantId}); the child
     * finders scope by {@code (tenantId, modelId)} for the same reason.
     */
    public interface IntegrationModelJpaRepository extends JpaRepository<IntegrationModelEntity, UUID> {
        Optional<IntegrationModelEntity> findByTenantIdAndProjectId(UUID tenantId, UUID projectId);

        List<IntegrationModelEntity> findAllByTenantId(UUID tenantId);
    }

    public interface ModelSourceJpaRepository extends JpaRepository<ModelSourceEntity, UUID> {
        List<ModelSourceEntity> findAllByTenantIdAndModelId(UUID tenantId, UUID modelId);

        Optional<ModelSourceEntity> findByTenantIdAndModelIdAndDatasetIdAndClassIri(
                UUID tenantId, UUID modelId, UUID datasetId, String classIri);

        List<ModelSourceEntity> findAllByTenantIdAndModelIdAndDatasetId(
                UUID tenantId, UUID modelId, UUID datasetId);

        /** Every model's rows for one dataset. Two callers, one reason: a source-schema change
         *  invalidates a stale wrapper seed (V47's drift re-seed) and an EXPANDED verdict (T2's
         *  refresh-tail downgrade) in every model at once, so both are model-agnostic. */
        List<ModelSourceEntity> findAllByTenantIdAndDatasetId(UUID tenantId, UUID datasetId);
    }

    public interface ModelMappingJpaRepository extends JpaRepository<ModelMappingEntity, UUID> {
        List<ModelMappingEntity> findAllByTenantIdAndModelId(UUID tenantId, UUID modelId);

        Optional<ModelMappingEntity> findByTenantIdAndModelIdAndKindAndSourceIriAndTargetIri(
                UUID tenantId, UUID modelId, String kind, String sourceIri, String targetIri);

        Optional<ModelMappingEntity> findByIdAndTenantId(UUID id, UUID tenantId);

        /**
         * Every mapping the tenant owns, across all its models, whose source or target IRI starts
         * with the given prefix — the drift check's "what depends on this dataset?" query.
         *
         * <p>Tenant-wide rather than per-model on purpose: a dataset can be enrolled in several
         * projects, and drift breaks all of them at once. Deliberately over-inclusive — a prefix
         * matches sibling IRIs that merely share a stem — because {@code DriftImpactAnalyzer}
         * re-checks each candidate precisely, and missing a dependent is far worse than
         * considering one extra.
         */
        @Query("SELECT m FROM ModelMappingEntity m WHERE m.tenantId = :tenantId "
                + "AND (m.sourceIri LIKE CONCAT(:prefix, '%') OR m.targetIri LIKE CONCAT(:prefix, '%'))")
        List<ModelMappingEntity> findAllByTenantIdAndIriPrefix(@Param("tenantId") UUID tenantId,
                                                               @Param("prefix") String prefix);
    }

    /** Schema fingerprints (V22). Append-only; the newest row per dataset is the baseline. */
    public interface DatasetSchemaFingerprintJpaRepository
            extends JpaRepository<DatasetSchemaFingerprintEntity, UUID> {

        Optional<DatasetSchemaFingerprintEntity>
                findFirstByTenantIdAndDatasetIdAndCapturedViaOrderByCapturedAtDesc(
                        UUID tenantId, UUID datasetId, String capturedVia);

        List<DatasetSchemaFingerprintEntity> findAllByTenantIdAndDatasetIdOrderByCapturedAtDesc(
                UUID tenantId, UUID datasetId, Limit limit);
    }

    /** Dataset refresh policies (V28). */
    public interface DatasetRefreshJpaRepository extends JpaRepository<DatasetRefreshEntity, UUID> {

        Optional<DatasetRefreshEntity> findByDatasetIdAndTenantId(UUID datasetId, UUID tenantId);

        List<DatasetRefreshEntity> findAllByTenantIdAndRepositoryId(UUID tenantId, UUID repositoryId);

        /**
         * The due rows, locked for this transaction and skipped by any other one.
         *
         * <p>Native because {@code FOR UPDATE SKIP LOCKED} has no JPQL spelling, and it is the
         * whole point: Aran can run several instances against one database, and two schedulers
         * that both read the same due row would extract the same table twice and race for the
         * dataset's snapshot pointer. {@code SKIP LOCKED} makes the second instance take the
         * <em>next</em> due row instead of blocking on the first — which is what makes a second
         * instance useful rather than merely harmless.
         *
         * <p>The caller must advance {@code next_run_at} in the same transaction. Holding the lock
         * without doing so would release it at commit with the row still due.
         */
        @Query(value = """
                SELECT * FROM dataset_refresh
                WHERE next_run_at IS NOT NULL AND next_run_at <= :now
                ORDER BY next_run_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, nativeQuery = true)
        List<DatasetRefreshEntity> lockDue(@Param("now") java.time.Instant now, @Param("limit") int limit);
    }

    /**
     * Each repository's last connection check (V46). Keyed by repository id; every finder still
     * names the tenant, since a load by primary key never consults the tenant filter.
     */
    public interface RepositoryConnectionCheckJpaRepository
            extends JpaRepository<RepositoryConnectionCheckEntity, UUID> {
        Optional<RepositoryConnectionCheckEntity> findByRepositoryIdAndTenantId(UUID repositoryId, UUID tenantId);

        /**
         * The checks of {@code repositoryIds} that began no earlier than their repository's last
         * save — an older one describes a configuration that save replaced (J2-F19). A row
         * written before V46 has no save time and keeps every check.
         */
        @org.springframework.data.jpa.repository.Query("""
                SELECT c FROM RepositoryConnectionCheckEntity c, DataRepositoryEntity r
                 WHERE r.id = c.repositoryId
                   AND c.tenantId = :tenantId AND r.tenantId = :tenantId
                   AND c.repositoryId IN :repositoryIds
                   AND (r.configSavedAt IS NULL OR c.testedAt >= r.configSavedAt)
                """)
        List<RepositoryConnectionCheckEntity> findCurrentByTenantIdAndRepositoryIdIn(
                @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                @org.springframework.data.repository.query.Param("repositoryIds")
                java.util.Collection<UUID> repositoryIds);
    }

    /** Declared foreign keys harvested from a live relational catalog (V33, Phase 4B). */
    public interface DeclaredForeignKeyJpaRepository extends JpaRepository<DeclaredForeignKeyEntity, UUID> {

        /** Every declared FK for the tenant — MappingCatalogOperator's per-catalog-build read. */
        List<DeclaredForeignKeyEntity> findAllByTenantId(UUID tenantId);

        /** One repository's current rows — HarvestPersistenceService's replace-scope read. */
        List<DeclaredForeignKeyEntity> findAllByTenantIdAndRepositoryId(UUID tenantId, UUID repositoryId);
    }

    public interface ModelConstraintJpaRepository extends JpaRepository<ModelConstraintEntity, UUID> {
        List<ModelConstraintEntity> findAllByTenantIdAndModelId(UUID tenantId, UUID modelId);

        Optional<ModelConstraintEntity> findByIdAndTenantId(UUID id, UUID tenantId);

        /** Every enforced constraint the tenant owns, across all its models (M6 enforcement). */
        List<ModelConstraintEntity> findAllByTenantIdAndEnforcedTrue(UUID tenantId);
    }

    /** Integration runs (V19). By-id reads name the tenant explicitly, like the other adapters. */
    public interface IntegrationRunJpaRepository extends JpaRepository<IntegrationRunEntity, UUID> {
        Optional<IntegrationRunEntity> findByIdAndTenantId(UUID id, UUID tenantId);

        List<IntegrationRunEntity> findAllByTenantIdAndModelIdAndStatusOrderByUpdatedAtDesc(
                UUID tenantId, UUID modelId, String status);

        /** At most one run records a given draft version — each run writes its own draft. */
        Optional<IntegrationRunEntity> findFirstByTenantIdAndDraftVersionId(UUID tenantId, UUID draftVersionId);
    }

    /** Mapping application state (V19). One row per mapping. */
    public interface ModelMappingApplicationJpaRepository
            extends JpaRepository<ModelMappingApplicationEntity, UUID> {
        Optional<ModelMappingApplicationEntity> findByTenantIdAndMappingId(UUID tenantId, UUID mappingId);

        /** Every application row recorded by a run — used to undo an abandoned draft's records. */
        List<ModelMappingApplicationEntity> findAllByTenantIdAndRunId(UUID tenantId, UUID runId);

        List<ModelMappingApplicationEntity> findAllByTenantIdAndModelId(UUID tenantId, UUID modelId);

        /**
         * ACCEPTED mappings whose application row is in {@code state}, for a model — the
         * join the incremental payload builder reads (accepted-PENDING and accepted-DEFERRED).
         */
        @Query("""
                select m from ModelMappingEntity m, ModelMappingApplicationEntity a
                where m.id = a.mappingId
                  and a.tenantId = :tenantId and a.modelId = :modelId
                  and a.state = :state and m.status = 'ACCEPTED'
                """)
        List<ModelMappingEntity> findAcceptedMappingsInState(
                @Param("tenantId") UUID tenantId, @Param("modelId") UUID modelId,
                @Param("state") String state);
    }
}

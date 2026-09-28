package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.AuditEvent;
import edu.upc.dtak.aran.core.domain.AuditPort;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording side of the audit log — a thin facade over {@link AuditPort} that
 * other services call from inside their existing transactions.
 *
 * <p>Centralised so action identifiers stay consistent across the codebase
 * ({@code project.created}, not {@code project_created} in one place and
 * {@code create_project} in another). Adding a new audited action means
 * adding a method here, not scattering audit-port calls through the codebase.
 *
 * <p>Calls propagate the caller's existing transaction
 * ({@code Propagation.MANDATORY}) — an audit row that exists without the
 * action it audits, or vice versa, is exactly the failure mode this design
 * is meant to prevent.
 */
@Service
@NullMarked
public class AuditService {

    /**
     * The metadata key that ties a line not targeted at a project to one, for the dashboard feed's
     * member scoping (UI-OD11). {@link AuditPort#recentForProjects} matches on it.
     */
    public static final String PROJECT_ID_KEY = "projectId";

    /** Who a {@code tenant.member.removed} line removed; {@link #removedFromTenant} matches on it. */
    static final String REMOVED_USER_ID_KEY = "removedUserId";

    private final AuditPort port;

    public AuditService(AuditPort port) {
        this.port = port;
    }

    /** {@code project.created}. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void projectCreated(AuthenticatedUser actor, UUID projectId, String projectName) {
        record(actor, "project.created", "project", projectId,
                Map.of("name", projectName));
    }

    /** {@code project.member.added}. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void projectMemberAdded(AuthenticatedUser actor, UUID projectId, UUID addedUserId) {
        record(actor, "project.member.added", "project", projectId,
                Map.of("addedUserId", addedUserId.toString()));
    }

    /**
     * {@code tenant.created}. Recorded against the brand-new tenant; the
     * actor may be null if the creation came from a platform-admin path
     * driven by an out-of-band tool rather than an end-user request.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void tenantCreated(@org.jspecify.annotations.Nullable UUID actorId,
                              UUID tenantId, String tenantName,
                              LicensePlan plan) {
        recordRaw(tenantId, actorId, "tenant.created", "tenant", tenantId,
                Map.of("name", tenantName, "plan", plan.name()));
    }

    /** {@code tenant.member.added}. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void tenantMemberAdded(@org.jspecify.annotations.Nullable UUID actorId,
                                  UUID tenantId, UUID addedUserId,
                                  TenantRole role) {
        recordRaw(tenantId, actorId, "tenant.member.added", "tenant", tenantId,
                Map.of("addedUserId", addedUserId.toString(), "role", role.name()));
    }


    /**
     * {@code project.member.added}, for a project handed over because the member leaving the
     * organization was its only member ({@link MemberDepartureService}). The same action as a
     * share, since that is what happened to the project; {@code handedOverFrom} says why, and the
     * line reaches the project's members' dashboards like any other project line.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void projectHandedOver(AuthenticatedUser actor, UUID projectId, UUID toUserId,
                                  UUID fromUserId) {
        record(actor, "project.member.added", "project", projectId,
                Map.of("addedUserId", toUserId.toString(),
                        "handedOverFrom", fromUserId.toString()));
    }

    /** {@code project.member.removed} — the reverse of {@link #projectMemberAdded}. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void projectMemberRemoved(AuthenticatedUser actor, UUID projectId, UUID removedUserId) {
        record(actor, "project.member.removed", "project", projectId,
                Map.of("removedUserId", removedUserId.toString()));
    }

    /**
     * {@code tenant.member.removed}, with what the removal took away besides the membership.
     *
     * <p>Used both for an owner's removal and for the "leave my own membership" path — the two are
     * told apart by comparing the actor with {@code removedUserId}. The counts are the point
     * (UI-OD9): "who revoked the export token?" has the answer "the removal of its holder did",
     * and without them the line says nothing about it.
     *
     * <p>It is also this tenant's own record that it ended this person's membership, which is what
     * {@link #removedFromTenant} reads before a re-add may reinstate the account.
     *
     * @param projectsHandedOver projects only they belonged to, handed to a custodian so none was
     *                           left unreachable ({@link MemberDepartureService})
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void tenantMemberRemoved(AuthenticatedUser actor, UUID removedUserId,
                                    int tokensRevoked, int projectAccessRemoved,
                                    int projectsHandedOver) {
        record(actor, "tenant.member.removed", "tenant", actor.tenantId(),
                Map.of(REMOVED_USER_ID_KEY, removedUserId.toString(),
                        "tokensRevoked", String.valueOf(tokensRevoked),
                        "projectAccessRemoved", String.valueOf(projectAccessRemoved),
                        "projectsHandedOver", String.valueOf(projectsHandedOver)));
    }

    /**
     * Whether {@code tenantId} has ever ended {@code userId}'s membership — removed by an owner or
     * left of their own accord.
     *
     * <p>The gate on reinstatement (UI-OD9): re-adding an email re-enables its account only when
     * this organization is the one that let it go. Any other membership-less account — removed
     * from another organization, or one that signed in and was never added — is not this tenant's
     * to re-enable, and {@link TenantMemberService} refuses it. Read within the tenant, like every
     * audit read: another tenant's history is not consulted and cannot be.
     */
    public boolean removedFromTenant(UUID tenantId, UUID userId) {
        return port.exists(new TenantContext(tenantId.toString()), "tenant.member.removed",
                REMOVED_USER_ID_KEY, userId.toString());
    }

    /**
     * {@code tenant.member.reinstated} — a removed member re-added by email (UI-OD9). Distinct
     * from {@code tenant.member.added} because no account was created: it existed, was disabled,
     * and came back with its own credential.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void tenantMemberReinstated(AuthenticatedUser actor, UUID userId, TenantRole role) {
        record(actor, "tenant.member.reinstated", "tenant", actor.tenantId(),
                Map.of("userId", userId.toString(), "role", role.name()));
    }

    /** {@code tenant.member.role_changed}, with the role before and after. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void tenantMemberRoleChanged(AuthenticatedUser actor, UUID userId,
                                        TenantRole from, TenantRole to) {
        record(actor, "tenant.member.role_changed", "tenant", actor.tenantId(),
                Map.of("userId", userId.toString(), "from", from.name(), "to", to.name()));
    }

    /** {@code tenant.member.password_reset} — an owner replaced a member's password. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void tenantMemberPasswordReset(AuthenticatedUser actor, UUID userId) {
        record(actor, "tenant.member.password_reset", "tenant", actor.tenantId(),
                Map.of("userId", userId.toString()));
    }

    /** {@code token.created}. The secret is never recorded; the name and scope are. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void apiTokenCreated(UUID tenantId, UUID actorId, UUID tokenId, String name,
                                String scope) {
        recordRaw(tenantId, actorId, "token.created", "token", tokenId,
                Map.of("name", name, "scope", scope));
    }

    /**
     * {@code token.revoked}.
     *
     * @param ownerUserId whose token it was — the actor for a self-revoke, a colleague when an
     *                    owner revoked it from the admin area (J7-F8)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void apiTokenRevoked(UUID tenantId, UUID actorId, UUID tokenId, UUID ownerUserId,
                                String name) {
        recordRaw(tenantId, actorId, "token.revoked", "token", tokenId,
                Map.of("ownerUserId", ownerUserId.toString(), "name", name));
    }

    /**
     * {@code schema.promoted}.
     *
     * <p>{@code REQUIRED}, not {@code MANDATORY} like the rest: a promote moves the graph in Fuseki
     * and the version rows in Postgres, which no single transaction spans, and none of its three
     * callers holds one. The row is written once the promote has succeeded — a failed promote has
     * nothing to record. When D5 part A gives promotion a single owner, this call moves into it.
     *
     * @param projectId the project whose model run was promoted, or null for a promote from
     *                  Schema versions, which no project owns. The dashboard feed shows a member
     *                  only lines of projects they can see (UI-OD11), so a null here is a line
     *                  only owners see there.
     */
    @Transactional
    public void schemaPromoted(AuthenticatedUser actor, UUID versionId, int versionNumber,
                               @org.jspecify.annotations.Nullable UUID projectId) {
        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("version", String.valueOf(versionNumber));
        if (projectId != null) {
            metadata.put(PROJECT_ID_KEY, projectId.toString());
        }
        record(actor, "schema.promoted", "schema_version", versionId, Map.copyOf(metadata));
    }

    /**
     * {@code schema.rolled_back} — a historical version re-promoted under a new version number.
     * {@code REQUIRED} for the reason {@link #schemaPromoted} gives.
     */
    @Transactional
    public void schemaRolledBack(AuthenticatedUser actor, UUID newVersionId, int newVersionNumber,
                                 UUID restoredVersionId) {
        record(actor, "schema.rolled_back", "schema_version", newVersionId,
                Map.of("version", String.valueOf(newVersionNumber),
                        "restoredVersionId", restoredVersionId.toString()));
    }

    private void record(AuthenticatedUser actor, String action, String targetType,
                        UUID targetId, Map<String, String> metadata) {
        recordRaw(actor.tenantId(), actor.userId(), action, targetType, targetId, metadata);
    }

    /**
     * Record path used by admin / system actions where there may not be a
     * tenant-scoped {@link AuthenticatedUser} (the actor is a platform admin,
     * or the action is the very creation of the tenant itself).
     */
    private void recordRaw(UUID tenantId, @org.jspecify.annotations.Nullable UUID actorId,
                           String action, String targetType,
                           UUID targetId, Map<String, String> metadata) {
        TenantContext tenant = new TenantContext(tenantId.toString());
        port.record(tenant, new AuditEvent(
                UUID.randomUUID(),
                tenantId,
                actorId,
                action,
                targetType,
                targetId,
                metadata,
                Instant.now()
        ));
    }
}

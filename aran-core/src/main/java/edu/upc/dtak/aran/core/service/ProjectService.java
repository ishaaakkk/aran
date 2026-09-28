package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.*;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import edu.upc.dtak.aran.core.licensing.LicenseExpiredException;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link Project}s — the use-case layer that sits
 * between the web controllers and the persistence ports.
 *
 * <p>This is where the authorization rules and the transactional invariants
 * live, deliberately, so they hold no matter which transport calls in:
 * <ul>
 *   <li><b>creator is server-set</b> — {@code createdBy} comes from the
 *       {@link AuthenticatedUser}, never from the request;</li>
 *   <li><b>creator-sees-own-project invariant</b> — creating a project also
 *       inserts the creator's {@link ProjectMember} row, in the
 *       <em>same transaction</em>;</li>
 *   <li><b>visibility on reads</b> — every read goes through the
 *       membership-aware repository methods, so a user can only ever see
 *       projects they have a {@code ProjectMember} row for;</li>
 *   <li><b>cross-tenant isolation on writes</b> — any user id taken from a
 *       request body (e.g. when adding a project member) is verified to have
 *       a {@link Membership} in the caller's tenant
 *       before being persisted. Without this check, the caller could grant
 *       project access to arbitrary user ids — including users from other
 *       tenants or non-existent ones.</li>
 *   <li><b>license gates on mutations</b> — every mutating method passes
 *       through {@link LicenseService}: a read-only-mode check first, then
 *       the relevant quota gate (project limit on create, no quota on
 *       member-add since the user already counted against {@code maxUsers}
 *       when their membership was inserted).</li>
 *   <li><b>tenant-role gates on sharing</b> — {@link #addMember} requires the
 *       caller to be a tenant {@code OWNER}. Listing, creating, viewing
 *       projects remain open to any tenant {@code MEMBER}.</li>
 * </ul>
 */
@Service
@NullMarked
public class ProjectService implements ProjectAccess {

    private final ProjectRepository projects;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final LicenseService licensing;

    public ProjectService(ProjectRepository projects, TenantRepository tenants,
                          AuditService audit, LicenseService licensing) {
        this.projects = projects;
        this.tenants = tenants;
        this.audit = audit;
        this.licensing = licensing;
    }

    /**
     * Create a project within the caller's current tenant and add the caller
     * as its first member — atomically.
     *
     * @param caller      the resolved, tenant-scoped caller
     * @param name        project name (must be non-blank)
     * @param description project description (may be empty, not null)
     * @return the created project
     */
    @Transactional
    public Project createProject(AuthenticatedUser caller, String name, String description) {
        return createProject(caller, name, description, "#4945FF", "PRIVATE");
    }

    @Transactional
    public Project createProject(AuthenticatedUser caller, String name, String description, String color, String privacy) {
        // License gates run first — cheap, and fail fast before any work.
        licensing.requireMutationsAllowed(caller);
        licensing.requireWithinProjectLimit(caller);

        TenantContext tenant = new TenantContext(caller.tenantId().toString());

        Project project = projects.save(tenant, new Project(
                UUID.randomUUID(),
                caller.tenantId(),
                name,
                description,
                caller.userId(),       // creator is server-set, never trusted from input
                Instant.now(),
                color,
                privacy,
                "ACTIVE",
                0
        ));

        // Invariant: the creator can always see their own project. Same
        // transaction as the save above — either both land or neither does.
        projects.addMember(tenant, new ProjectMember(
                UUID.randomUUID(),
                project.id(),
                caller.userId(),
                Instant.now()
        ));

        // Audit — also same transaction. An audit row without the action it
        // audits, or vice versa, would defeat the point of the trail.
        audit.projectCreated(caller, project.id(), project.name());

        return project;
    }

    /**
     * List the projects in the caller's tenant that the caller can see.
     */
    @Transactional(readOnly = true)
    public List<Project> listVisibleProjects(AuthenticatedUser caller) {
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        return projects.findAllVisibleToUser(tenant, caller.userId());
    }

    /**
     * Fetch a single project the caller can see, or throw.
     *
     * @throws ResourceNotFoundException if the project does not exist in the
     *         tenant <em>or</em> exists but the caller has no
     *         {@link ProjectMember} row for it — the two cases are
     *         deliberately indistinguishable to the caller.
     */
    @Override
    @Transactional(readOnly = true)
    public Project getVisibleProject(AuthenticatedUser caller, UUID projectId) {
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        return projects.findVisibleToUser(tenant, projectId, caller.userId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "project not found: " + projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> visibleProjectIds(AuthenticatedUser caller) {
        return listVisibleProjects(caller).stream().map(Project::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Grant another user visibility of a project the caller can see.
     *
     * <p>Authorization (in order of cheapness, each failing fast):
     * <ol>
     *   <li>License must allow mutations (not expired / suspended).</li>
     *   <li>Caller must be a tenant {@code OWNER}. Sharing is a tenant-
     *       administration action; tenant {@code MEMBER}s use projects but do
     *       not grant access. This is the most-common-in-industry rule for a
     *       two-role model; per-project roles ({@code EDITOR}/{@code VIEWER})
     *       are the natural future extension if the team grows.</li>
     *   <li>Caller must themselves be a member of the project — you cannot
     *       share what you cannot see.</li>
     *   <li>The target user must hold a {@link Membership}
     *       in the caller's tenant — prevents granting access to users from
     *       other tenants or to fabricated ids. Reported as 404 (not 403) so
     *       the caller cannot probe which user ids exist in the tenant.</li>
     * </ol>
     *
     * <p>Note there is <em>no</em> {@code requireWithinUserLimit} call here:
     * adding a project member does not add a tenant user. The quota fires
     * when a tenant {@code Membership} is inserted (see
     * {@code TenantAdminService}), not when project membership is granted.
     *
     * @throws LicenseExpiredException     if the tenant is in read-only mode
     * @throws AccessDeniedException                              if the caller is not a tenant OWNER
     * @throws ResourceNotFoundException                          if the project is not visible to the caller,
     *                                                            or the target user has no membership in the tenant
     */
    @Transactional
    public ProjectMember addMember(AuthenticatedUser caller, UUID projectId, UUID newMemberUserId) {
        // 1. License gate — fail fast on commercial reasons before any DB read.
        licensing.requireMutationsAllowed(caller);

        // 2. Tenant-role gate — only OWNER may share projects.
        if (!caller.canAdministerTenant()) {
            throw new AccessDeniedException(
                    "only tenant OWNERs may add members to a project");
        }

        TenantContext tenant = new TenantContext(caller.tenantId().toString());

        // 3. Caller must be able to see the project to share it.
        getVisibleProject(caller, projectId);

        // 4. Cross-tenant isolation — the target user must belong to the
        //    caller's tenant. 404 (not 403) so callers cannot probe user ids.
        tenants.findMembership(newMemberUserId, caller.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "user is not a member of this tenant: " + newMemberUserId));

        ProjectMember added = projects.addMember(tenant, new ProjectMember(
                UUID.randomUUID(),
                projectId,
                newMemberUserId,
                Instant.now()
        ));

        audit.projectMemberAdded(caller, projectId, newMemberUserId);
        return added;
    }

    /**
     * Take a user's visibility of a project away — the reverse of {@link #addMember}, under the
     * same gates (audit J1-F4: an owner could grant access from no UI and revoke it from none).
     *
     * <p><b>The last member cannot be removed.</b> Visibility <em>is</em> membership, so a project
     * with no members is invisible to everyone — owners included, since {@link #addMember}
     * requires the caller to see the project first. Nothing could ever reach it again.
     *
     * @throws AccessDeniedException     if the caller is not a tenant OWNER
     * @throws ResourceNotFoundException if the project is not visible to the caller, or the user
     *                                   is not one of its members
     * @throws IllegalArgumentException  if the user is the project's only member
     */
    @Transactional
    public void removeMember(AuthenticatedUser caller, UUID projectId, UUID memberUserId) {
        licensing.requireMutationsAllowed(caller);
        if (!caller.canAdministerTenant()) {
            throw new AccessDeniedException(
                    "only tenant OWNERs may remove members from a project");
        }
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        getVisibleProject(caller, projectId);

        List<ProjectMember> members = projects.findMembers(tenant, projectId);
        if (members.stream().noneMatch(m -> m.userId().equals(memberUserId))) {
            throw new ResourceNotFoundException(
                    "user is not a member of this project: " + memberUserId);
        }
        if (members.size() <= 1) {
            throw new IllegalArgumentException("this is the project's only member; a project "
                    + "nobody belongs to is invisible to everyone, owners included");
        }

        projects.removeMember(tenant, projectId, memberUserId);
        audit.projectMemberRemoved(caller, projectId, memberUserId);
    }

    /**
     * List the members of a project the caller can see.
     *
     * @throws ResourceNotFoundException if the project is not visible to the caller
     */
    @Transactional(readOnly = true)
    public List<ProjectMember> listMembers(AuthenticatedUser caller, UUID projectId) {
        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        getVisibleProject(caller, projectId);
        return projects.findMembers(tenant, projectId);
    }
}

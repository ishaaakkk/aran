package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence <em>port</em> for {@link Project} and its {@link ProjectMember}
 * visibility rows.
 *
 * <p>This port <strong>is</strong> tenant-scoped: every method takes an
 * explicit {@link TenantContext}. Even though the adapter also enforces tenant
 * scoping via a Hibernate filter, threading the tenant through the signature
 * keeps the contract honest — there is no tenant-agnostic read of a project.
 *
 * <p>Two layers of access control are reflected here:
 * <ol>
 *   <li><b>Tenant scoping</b> — the {@code TenantContext} parameter; a project
 *       is only ever visible within its owning tenant.</li>
 *   <li><b>Project membership</b> — the {@code *VisibleTo*} methods additionally
 *       restrict to projects the given user has a {@link ProjectMember} row for
 *       (Option A: explicit visibility).</li>
 * </ol>
 */
@NullMarked
public interface ProjectRepository {

    /**
     * Find a project by id within the given tenant — <em>without</em> applying
     * the project-membership visibility check.
     *
     * <p>Use this only where the caller has already established the acting
     * user's right to see it, or for tenant-administration paths. For
     * user-facing reads prefer {@link #findVisibleToUser}.
     */
    Optional<Project> findById(TenantContext tenant, UUID projectId);

    /**
     * Find a project by id within the given tenant, but only if {@code userId}
     * has a {@link ProjectMember} row for it. Returns empty both when the
     * project does not exist and when it exists but is not visible to the user
     * — callers must not distinguish the two (no information leak).
     */
    Optional<Project> findVisibleToUser(TenantContext tenant, UUID projectId, UUID userId);

    /**
     * List every project in the tenant that {@code userId} can see — i.e. has a
     * {@link ProjectMember} row for. This backs the project list screen.
     */
    List<Project> findAllVisibleToUser(TenantContext tenant, UUID userId);

    /**
     * Persist (insert or update) a project.
     *
     * <p>Callers are responsible for also creating the creator's
     * {@link ProjectMember} row via {@link #addMember} — the service layer does
     * both in one transaction so the "creator can always see their project"
     * invariant holds.
     */
    Project save(TenantContext tenant, Project project);

    /**
     * Add a {@link ProjectMember} row, granting {@code userId} visibility of the
     * project. Idempotent: adding an existing member is a no-op.
     */
    ProjectMember addMember(TenantContext tenant, ProjectMember member);

    /**
     * List the members (visibility rows) of a project.
     */
    List<ProjectMember> findMembers(TenantContext tenant, UUID projectId);

    /**
     * Remove {@code userId}'s visibility row for one project.
     *
     * @return whether a row was removed — false when the user was not a member
     */
    boolean removeMember(TenantContext tenant, UUID projectId, UUID userId);

    /**
     * Remove every project visibility row {@code userId} holds in this tenant — what removing
     * them from the tenant does, so re-adding the same email later restores sign-in without
     * silently restoring every project they could once see (UI-OD9).
     *
     * @return how many rows were removed
     */
    int removeAllMembershipsOf(TenantContext tenant, UUID userId);
}

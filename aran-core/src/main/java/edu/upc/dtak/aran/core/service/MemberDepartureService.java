package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What ending a membership ends — one definition for an owner's removal
 * ({@link TenantMemberService#removeMember}) and a member's own leave
 * ({@link TenantOwnerService#leaveCurrentTenant}), which must not drift apart (UI-OD9).
 *
 * <p>The membership goes, every API token the person holds is revoked, and their project
 * visibility rows are dropped, so re-adding the same email later restores none of them.
 *
 * <p><b>No project is left with nobody in it.</b> Visibility <em>is</em> membership: a project
 * whose only member departs is invisible to everyone, owners included, and nothing can reach it
 * again — {@link ProjectService#removeMember} refuses exactly that for one project, and dropping
 * every row at once must not do it for many. So before the rows go, each project the departing
 * person is the <em>only</em> member of is handed to the custodians the caller names: the acting
 * owner on a removal, the organization's owners on a leave. This is Google Workspace's "transfer
 * data" step on deleting a user, taken as the default because the alternative — refusing — is a
 * dead end here: an owner cannot share a project they cannot see.
 */
@Service
@NullMarked
public class MemberDepartureService {

    /** A project named in a result, so the caller can say which ones changed hands. */
    public record ProjectRef(UUID id, String name) {}

    /**
     * What a departure took away and what it handed over.
     *
     * @param projectsHandedOver projects the departing person was the only member of, now shared
     *                           with the custodians — sorted by name
     */
    public record Departure(int tokensRevoked, int projectAccessRemoved,
                            List<ProjectRef> projectsHandedOver) {}

    private final TenantRepository tenants;
    private final ApiTokenService tokens;
    private final ProjectRepository projects;
    private final AuditService audit;

    public MemberDepartureService(TenantRepository tenants, ApiTokenService tokens,
                                  ProjectRepository projects, AuditService audit) {
        this.tenants = tenants;
        this.tokens = tokens;
        this.projects = projects;
        this.audit = audit;
    }

    /**
     * End {@code membership}, in the caller's transaction.
     *
     * @param custodians who takes over a project only the departing person belongs to. Must not
     *                   be empty when such a project exists: that departure is refused rather
     *                   than allowed to orphan the project.
     * @throws AccessDeniedException when a project would be orphaned and nobody can take it over
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Departure depart(AuthenticatedUser actor, Membership membership,
                            Collection<UUID> custodians) {
        TenantContext tenant = new TenantContext(membership.tenantId().toString());
        UUID leaving = membership.userId();

        List<Project> soleMemberOf = projects.findAllVisibleToUser(tenant, leaving).stream()
                .filter(p -> projects.findMembers(tenant, p.id()).stream()
                        .allMatch(m -> m.userId().equals(leaving)))
                .sorted(Comparator.comparing(Project::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<UUID> takers = custodians.stream().filter(id -> !id.equals(leaving)).distinct()
                .toList();
        if (!soleMemberOf.isEmpty() && takers.isEmpty()) {
            throw new AccessDeniedException("nobody else belongs to "
                    + names(soleMemberOf) + " and this organization has no other owner to hand "
                    + "them to; contact platform support");
        }

        for (Project project : soleMemberOf) {
            for (UUID taker : takers) {
                projects.addMember(tenant, new ProjectMember(UUID.randomUUID(), project.id(),
                        taker, Instant.now()));
                audit.projectHandedOver(actor, project.id(), taker, leaving);
            }
        }

        tenants.deleteMembership(membership.id());
        int tokensRevoked = tokens.revokeAllFor(tenant, leaving);
        int projectAccessRemoved = projects.removeAllMembershipsOf(tenant, leaving);
        audit.tenantMemberRemoved(actor, leaving, tokensRevoked, projectAccessRemoved,
                soleMemberOf.size());

        return new Departure(tokensRevoked, projectAccessRemoved,
                soleMemberOf.stream().map(p -> new ProjectRef(p.id(), p.name())).toList());
    }

    private static String names(List<Project> projects) {
        return String.join(", ", projects.stream().map(p -> "'" + p.name() + "'").toList());
    }
}

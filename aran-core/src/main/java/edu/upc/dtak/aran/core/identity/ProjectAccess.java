package edu.upc.dtak.aran.core.identity;

import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * The one question every project-scoped entry point asks first: may this caller see this project?
 *
 * <p>Project visibility is explicit membership — a {@code ProjectMember} row — and the platform
 * documents it as an authorization invariant. It was honoured on the repositories/datasets branch
 * through {@code ProjectService.getVisibleProject} and nowhere else: the model, products and cubes
 * controllers took a bare {@code projectId} from the URL, so any tenant member could read and edit
 * another project's integration model and create, materialize or delete its products and cubes by
 * knowing the UUID (S2-1). This port is D11's shape for closing that: a single definition of the
 * gate, injected where the gate is needed, so a new project-scoped surface has one obvious thing to
 * call and nothing to re-derive.
 *
 * <p>The answer to "no" is {@link ResourceNotFoundException}, never an access-denied — a 403 would
 * confirm that the project exists, and the caller who cannot see it is not owed that.
 */
@NullMarked
public interface ProjectAccess {

    /**
     * @return the project, when it exists in the caller's tenant and the caller is a member of it
     * @throws ResourceNotFoundException otherwise — the two cases are deliberately
     *         indistinguishable to the caller
     */
    Project getVisibleProject(AuthenticatedUser caller, UUID projectId);

    /**
     * The ids of every project in the caller's tenant the caller is a member of — for a
     * tenant-wide listing that must drop other projects' rows, where asking
     * {@link #getVisibleProject} once per row would be a query per row.
     */
    Set<UUID> visibleProjectIds(AuthenticatedUser caller);
}

package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.JobService;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Which jobs a caller may read: the read side's one project gate, shared by the job snapshot,
 * listing and event-stream routes.
 *
 * <p>A project-scoped job is its project's data. Its parameters name the project's datasets, and
 * its result carries what it touched — a compile's {@code appliedMappingIds}, a materialization's
 * product. With reads gated on the tenant alone, any tenant member could list every project's
 * jobs, and those ids were then addressable through by-id routes under a project they could see
 * (S2-1's by-id half). So a job with a project is visible exactly when the project is, and "not
 * visible" is the same 404 as "no such job", never one naming the project. A job with no project
 * (an unscoped operator submission) stays tenant-visible, as it always was.
 */
@Component
@NullMarked
class JobVisibility {

    private final JobService jobs;
    private final ProjectAccess projectAccess;

    JobVisibility(JobService jobs, ProjectAccess projectAccess) {
        this.jobs = jobs;
        this.projectAccess = projectAccess;
    }

    Job requireVisible(AuthenticatedUser caller, UUID jobId) {
        Job job = jobs.find(tenant(caller), jobId).orElseThrow(() -> notFound(jobId));
        UUID projectId = job.projectId();
        if (projectId != null) {
            try {
                projectAccess.getVisibleProject(caller, projectId);
            } catch (ResourceNotFoundException hidden) {
                throw notFound(jobId);
            }
        }
        return job;
    }

    /**
     * The newest {@code limit} jobs, with every job of a project the caller cannot see dropped.
     * Without a project filter the drop happens after the tenant-wide page is read, so a page
     * can hold fewer than {@code limit} jobs; with one, the project itself is gated first.
     */
    List<Job> listRecent(AuthenticatedUser caller, @Nullable UUID projectId, int limit) {
        if (projectId != null) {
            projectAccess.getVisibleProject(caller, projectId);
            return jobs.listRecent(tenant(caller), projectId, limit);
        }
        Set<UUID> visible = projectAccess.visibleProjectIds(caller);
        return jobs.listRecent(tenant(caller), null, limit).stream()
                .filter(job -> job.projectId() == null || visible.contains(job.projectId()))
                .toList();
    }

    private static TenantContext tenant(AuthenticatedUser caller) {
        return new TenantContext(caller.tenantId().toString());
    }

    private static ResourceNotFoundException notFound(UUID jobId) {
        return new ResourceNotFoundException("job " + jobId + " not found");
    }
}

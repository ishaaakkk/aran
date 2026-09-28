package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.DashboardDtos.AuditLine;
import edu.upc.dtak.aran.app.web.dto.DashboardDtos.DashboardSummary;
import edu.upc.dtak.aran.core.domain.AuditEvent;
import edu.upc.dtak.aran.core.domain.AuditPort;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.ProjectService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's read model: counts the system can actually derive and the
 * audit rows it actually wrote.
 *
 * <p>Counts are computed by walking the existing tenant-filtered ports rather
 * than with dedicated COUNT queries. That is a handful of queries per load and
 * is fine at present scale; when a tenant has enough projects for it to hurt,
 * the fix is count methods on the ports, not caching a number the UI then has
 * to trust.
 */
@RestController
@RequestMapping("/api/dashboard")
@NullMarked
public class DashboardController {

    /** Lines shown in the activity feed. */
    private static final int RECENT_AUDIT_LIMIT = 15;

    private final ProjectService projectService;
    private final DataRepositoryRepository repositories;
    private final DatasetRepository datasets;
    private final GraphStore graphStore;
    private final GraphVersionPort versions;
    private final AuditPort audit;
    private final CurrentUserResolver currentUser;

    public DashboardController(ProjectService projectService,
                               DataRepositoryRepository repositories,
                               DatasetRepository datasets,
                               GraphStore graphStore,
                               GraphVersionPort versions,
                               AuditPort audit,
                               CurrentUserResolver currentUser) {
        this.projectService = projectService;
        this.repositories = repositories;
        this.datasets = datasets;
        this.graphStore = graphStore;
        this.versions = versions;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        AuthenticatedUser caller = currentUser.require();
        TenantContext tenant = new TenantContext(caller.tenantId().toString());

        List<Project> visibleProjects = projectService.listVisibleProjects(caller);

        int repositoryCount = 0;
        int datasetCount = 0;
        int readyDatasetCount = 0;
        for (Project project : visibleProjects) {
            List<DataRepository> projectRepos = repositories.findAllByProject(tenant, project.id());
            repositoryCount += projectRepos.size();
            for (DataRepository repo : projectRepos) {
                List<Dataset> repoDatasets = datasets.findAllByRepository(tenant, repo.id());
                datasetCount += repoDatasets.size();
                for (Dataset dataset : repoDatasets) {
                    // Compared as the enum it is. This read `"READY".equals(bootstrapStatus())`,
                    // left over from when the column was a String, so the count was 0 for every
                    // tenant — a wrong number that reads as an empty install rather than a bug.
                    if (dataset.bootstrapStatus() == DatasetStatus.READY) {
                        readyDatasetCount++;
                    }
                }
            }
        }

        GraphVersion active = graphStore.currentVersion(tenant);
        // A compiled draft nobody has promoted yet — the one first-run step the other counts
        // cannot see (J1-F2). Only the newest version can be it: a draft older than the active
        // one was superseded, not left waiting.
        Integer draft = versions.findLatest(tenant)
                .filter(latest -> "DRAFT".equals(latest.status()))
                .filter(latest -> active == null || latest.versionNumber() > active.versionNumber())
                .map(GraphVersion::versionNumber)
                .orElse(null);

        // UI-OD11: an owner sees the tenant's whole feed — it is what /admin/activity shows them
        // anyway. A member sees only lines of projects they can see; tenant governance (members,
        // roles, tokens) and a tenant-wide promote from Schema versions are owner lines, which
        // the dashboard was showing to everyone while /admin gated the same rows to owners.
        List<AuditEvent> events = caller.canAdministerTenant()
                ? audit.recent(tenant, RECENT_AUDIT_LIMIT)
                : audit.recentForProjects(tenant,
                        visibleProjects.stream().map(Project::id).toList(), RECENT_AUDIT_LIMIT);
        List<AuditLine> recentAudit = events.stream()
                .map(AuditLine::from)
                .toList();

        return new DashboardSummary(
                visibleProjects.size(),
                repositoryCount,
                datasetCount,
                readyDatasetCount,
                active != null ? active.versionNumber() : null,
                draft,
                recentAudit);
    }
}

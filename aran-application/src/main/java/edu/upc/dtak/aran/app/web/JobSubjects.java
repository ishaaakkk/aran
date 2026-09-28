package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubjectDto;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.NamedObjectRepository;
import edu.upc.dtak.aran.core.domain.NamedObjectRepository.NamedObject;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names what a job is about (UI audit J8-F7): a job row stores ids in its parameters, and a list of
 * jobs that says "dataset 3f2a…" is not a list anyone can find their failed import in.
 *
 * <p>The subject is the most specific object the parameters name — a dataset, else a product, else
 * the job's project — so an incremental add reads as the dataset it added and a rebuild as the
 * project it rebuilt. Resolved at read time, so it carries today's name; a deleted object keeps
 * its id with a null name rather than vanishing.
 *
 * <p><b>A name is only given for an object whose own project the caller can see.</b> Only jobs
 * {@link JobVisibility} let through arrive here, but that gate is the <em>job's</em> project, and a
 * job with no project (a profiling run, an operator-submitted bootstrap) is visible to the whole
 * tenant while its parameters may name a dataset in a project the caller is not a member of.
 * Those parameters were already tenant-visible; the dataset's name and repository were not, so such
 * a job gets no subject — the list falls back to what its parameters say.
 *
 * <p>One batch per request: the objects of up to a hundred jobs are named with at most three
 * projection queries ({@link NamedObjectRepository}), and the caller's projects are listed only
 * when some object lives outside its own job's project.
 */
@Component
@NullMarked
class JobSubjects {

    private final NamedObjectRepository names;
    private final ProjectAccess projectAccess;

    JobSubjects(NamedObjectRepository names, ProjectAccess projectAccess) {
        this.names = names;
        this.projectAccess = projectAccess;
    }

    /** Each job's subject by job id; a job that names nothing the caller may see has no entry. */
    Map<UUID, JobSubjectDto> subjectsOf(AuthenticatedUser caller, List<Job> jobs) {
        Set<UUID> datasetIds = new HashSet<>();
        Set<UUID> productIds = new HashSet<>();
        Set<UUID> projectIds = new HashSet<>();
        for (Job job : jobs) {
            Ref ref = refOf(job);
            if (ref != null) {
                (switch (ref.kind()) {
                    case "dataset" -> datasetIds;
                    case "product" -> productIds;
                    default -> projectIds;
                }).add(ref.id());
            }
        }

        TenantContext tenant = new TenantContext(caller.tenantId().toString());
        Map<UUID, NamedObject> datasets = names.datasets(tenant, datasetIds);
        Map<UUID, NamedObject> products = names.products(tenant, productIds);
        Map<UUID, NamedObject> projects = names.projects(tenant, projectIds);

        Map<UUID, JobSubjectDto> subjects = new HashMap<>();
        Set<UUID> visible = null;
        for (Job job : jobs) {
            Ref ref = refOf(job);
            if (ref == null) {
                continue;
            }
            NamedObject named = (switch (ref.kind()) {
                case "dataset" -> datasets;
                case "product" -> products;
                default -> projects;
            }).get(ref.id());
            if (named == null) {
                // Gone: the id is what the job's parameters already say, and no more.
                subjects.put(job.id(), new JobSubjectDto(ref.kind(), ref.id(), null, null));
                continue;
            }
            if (!Objects.equals(named.projectId(), job.projectId())) {
                if (visible == null) {
                    visible = projectAccess.visibleProjectIds(caller);
                }
                if (!visible.contains(named.projectId())) {
                    continue;
                }
            }
            subjects.put(job.id(), new JobSubjectDto(ref.kind(), ref.id(), named.name(), named.repositoryId()));
        }
        return subjects;
    }

    private record Ref(String kind, UUID id) {
    }

    private static @Nullable Ref refOf(Job job) {
        UUID datasetId = uuid(job.parameters().get("datasetId"));
        if (datasetId != null) {
            return new Ref("dataset", datasetId);
        }
        UUID productId = uuid(job.parameters().get("productId"));
        if (productId != null) {
            return new Ref("product", productId);
        }
        UUID projectId = job.projectId();
        return projectId == null ? null : new Ref("project", projectId);
    }

    /** A parameter that should hold an id; anything else names nothing. */
    private static @Nullable UUID uuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}

package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.JobDtos.JobDto;
import edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubjectDto;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The read side of the job substrate.
 *
 * <p>Polling this is always a valid alternative to the SSE stream — the row is
 * written before every event is published, so nothing is only ever available as
 * an event.
 *
 * <p>Reads are tenant-scoped by the repository's Hibernate filter, so another
 * tenant's job id is indistinguishable from one that does not exist: both 404.
 * Within the tenant, a project-scoped job is readable only by that project's
 * members ({@link JobVisibility}).
 *
 * <p>Both routes name each job's {@code subject} ({@link JobSubjects}) — what the web UI's jobs
 * drawer lists a job by (UI audit J8-F7).
 */
@RestController
@RequestMapping("/api/jobs")
@NullMarked
public class JobController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final JobVisibility jobs;
    private final JobSubjects subjects;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper objectMapper;

    public JobController(JobVisibility jobs, JobSubjects subjects, CurrentUserResolver currentUser,
                         ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.subjects = subjects;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{jobId}")
    public JobDto get(@PathVariable UUID jobId) {
        AuthenticatedUser caller = currentUser.require();
        Job job = jobs.requireVisible(caller, jobId);
        return JobDto.from(job, objectMapper, subjects.subjectsOf(caller, List.of(job)).get(job.id()));
    }

    @GetMapping
    public List<JobDto> list(@RequestParam(required = false) @Nullable UUID projectId,
                             @RequestParam(required = false) @Nullable Integer limit) {
        AuthenticatedUser caller = currentUser.require();

        int capped = Math.clamp(limit != null ? limit : DEFAULT_LIMIT, 1, MAX_LIMIT);
        List<Job> listed = jobs.listRecent(caller, projectId, capped);
        Map<UUID, JobSubjectDto> named = subjects.subjectsOf(caller, listed);
        return listed.stream()
                .map(job -> JobDto.from(job, objectMapper, named.get(job.id())))
                .toList();
    }
}

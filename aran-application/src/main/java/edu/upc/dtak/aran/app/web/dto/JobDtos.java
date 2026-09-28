package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Data-transfer objects for the jobs API.
 *
 * <p>The wire shape mirrors the {@code job} table one-to-one. A client that
 * missed every SSE event can reconstruct the whole truth from a single
 * {@code GET /api/jobs/{id}} — which is the point of persisting jobs at all.
 */
public final class JobDtos {

    private JobDtos() {
    }

    /**
     * What a job is about, named — so a list of jobs can say "Import · orders" rather than a
     * dataset id (UI audit J8-F7). Resolved when the job is read, so it carries the object's
     * current name.
     *
     * @param kind         {@code dataset}, {@code product} or {@code project}
     * @param name         null when the object has since been deleted
     * @param repositoryId a dataset's repository — what re-running an import or a refresh needs;
     *                     null for every other kind
     */
    public record JobSubjectDto(
            String kind,
            UUID id,
            @Nullable String name,
            @Nullable UUID repositoryId
    ) {
    }

    /**
     * A job as the API renders it.
     *
     * @param result  the task's payload once SUCCEEDED; {@code null} before that
     *                and on failure
     * @param subject what the job is about; resolved by the list and snapshot routes, null on the
     *                event stream (whose client already has it) and for a job about nothing named
     */
    public record JobDto(
            UUID id,
            @Nullable UUID projectId,
            String jobType,
            JobStatus status,
            Map<String, String> parameters,
            @Nullable String progressStage,
            @Nullable Integer progressPercent,
            @Nullable Map<String, String> result,
            @Nullable String errorCode,
            @Nullable String errorMessage,
            @Nullable UUID createdBy,
            Instant createdAt,
            @Nullable Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable JobSubjectDto subject
    ) {
        public static JobDto from(Job job, ObjectMapper objectMapper) {
            return from(job, objectMapper, null);
        }

        @SuppressWarnings("unchecked")
        public static JobDto from(Job job, ObjectMapper objectMapper, @Nullable JobSubjectDto subject) {
            Map<String, String> result = null;
            if (job.resultJson() != null) {
                try {
                    result = objectMapper.readValue(job.resultJson(), Map.class);
                } catch (JacksonException ex) {
                    throw new IllegalStateException("corrupt job result JSON for job " + job.id(), ex);
                }
            }
            return new JobDto(
                    job.id(),
                    job.projectId(),
                    job.jobType(),
                    job.status(),
                    job.parameters(),
                    job.progressStage(),
                    job.progressPercent(),
                    result,
                    job.errorCode(),
                    job.errorMessage(),
                    job.createdBy(),
                    job.createdAt(),
                    job.startedAt(),
                    job.finishedAt(),
                    subject);
        }
    }

    /** The response to a submission: the job exists, its work has not finished. */
    public record JobSubmission(UUID jobId) {
    }
}

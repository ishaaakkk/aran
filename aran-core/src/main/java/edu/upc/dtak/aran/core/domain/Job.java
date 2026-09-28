package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import edu.upc.dtak.aran.core.domain.JobStatus;

/**
 * A durable unit of background work.
 *
 * <p>Jobs are the async backbone: anything that can outlive an HTTP request
 * (bootstrapping a dataset, compiling a model, discovering constraints) gets a
 * row so its status, staged progress, result and error survive a page reload
 * and an application restart. The V15 migration owns the table.
 *
 * <p>{@code status} is a String constant rather than an enum, matching the
 * convention already set by {@link Dataset#bootstrapStatus()}.
 *
 * @param parameters     the operator/task inputs, persisted as JSONB
 * @param progressStage  the stage the task last reported, e.g. {@code "downloading snapshot"}
 * @param progressPercent 0..100 when the task can report one; {@code null} otherwise
 * @param resultJson     the task's result map, already serialised to JSON; {@code null} until SUCCEEDED
 * @param errorCode      a short, stable failure code; {@code null} unless FAILED
 */
@NullMarked
public record Job(
        UUID id,
        UUID tenantId,
        @Nullable UUID projectId,
        String jobType,
        JobStatus status,
        Map<String, String> parameters,
        @Nullable String progressStage,
        @Nullable Integer progressPercent,
        @Nullable String resultJson,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        @Nullable UUID createdBy,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt
) {



    public Job {
        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("jobType must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        parameters = Map.copyOf(parameters);
    }

    /** @return whether this job has reached a state it will never leave. */
    public boolean isTerminal() {
        return status == JobStatus.SUCCEEDED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }

    /** A freshly submitted job, PENDING and not yet started. */
    public static Job pending(UUID id, UUID tenantId, @Nullable UUID projectId, String jobType,
                              Map<String, String> parameters, @Nullable UUID createdBy, Instant createdAt) {
        return new Job(id, tenantId, projectId, jobType, JobStatus.PENDING, parameters,
                null, null, null, null, null, createdBy, createdAt, null, null);
    }
}

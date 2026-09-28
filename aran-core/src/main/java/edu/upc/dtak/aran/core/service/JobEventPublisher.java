package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Job;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Where {@link JobService} pushes every job state change.
 *
 * <p>The transport is not the core's business: aran-application implements this
 * with an SSE emitter registry, and tests implement it with a list. A job that
 * nobody is listening to still runs and still persists — publishing is a
 * notification, never the source of truth.
 */
@NullMarked
public interface JobEventPublisher {

    /** Announce the job's current state. Must not throw; a dead listener is not a job failure. */
    void publish(UUID jobId, Job snapshot);
}

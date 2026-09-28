package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.JobDtos.JobDto;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * The live view of a job: {@code GET /api/jobs/{id}/events}.
 *
 * <p>Sends one {@code job} event per state change, then completes the stream once
 * the job is terminal. The current state is sent immediately on subscribe, so a
 * client that connects late — or after the job already finished — still gets the
 * answer without a separate GET.
 *
 * <p>Authentication is the standard {@code /api/**} JWT chain. Browsers cannot set
 * an {@code Authorization} header on an {@code EventSource}, so
 * {@code SseAccessTokenFilter} lets this one route carry its token as a query
 * parameter instead.
 */
@RestController
@RequestMapping("/api/jobs")
@NullMarked
public class JobEventsController {

    private static final Logger log = LoggerFactory.getLogger(JobEventsController.class);

    /** Long enough for the slowest job we run today; the client reconnects if it lapses. */
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(15).toMillis();

    private final JobVisibility jobs;
    private final SseJobEventPublisher publisher;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper objectMapper;

    public JobEventsController(JobVisibility jobs,
                               SseJobEventPublisher publisher,
                               CurrentUserResolver currentUser,
                               ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.publisher = publisher;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID jobId) {
        AuthenticatedUser caller = currentUser.require();

        // Resolve before opening the stream: an unknown, other-tenant or other-project job must
        // be a 404, not an empty stream that never says anything — nor a live one.
        Job job = jobs.requireVisible(caller, jobId);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        publisher.register(jobId, emitter);

        try {
            emitter.send(SseEmitter.event().name("job")
                    .data(objectMapper.writeValueAsString(JobDto.from(job, objectMapper))));
            if (job.isTerminal()) {
                emitter.complete();
            }
        } catch (IOException ex) {
            log.debug("Client disconnected before the first job event for {}", jobId, ex);
            emitter.completeWithError(ex);
        }
        return emitter;
    }
}

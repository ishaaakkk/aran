package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.web.dto.JobDtos.JobDto;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.service.JobEventPublisher;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans job state changes out to whoever is watching over SSE.
 *
 * <p>Listeners are a convenience, not the record: every event published here was
 * already written to the job row, so a client that never connects — or drops and
 * never comes back — loses nothing it cannot re-read with a GET. That is why a
 * failed send is logged and the emitter dropped, rather than failing the job.
 *
 * <p>On a terminal state the event is sent and then every emitter for that job is
 * completed and forgotten, so the browser's EventSource stops rather than
 * reconnecting forever to a job that will never change again.
 */
@Component
@NullMarked
public class SseJobEventPublisher implements JobEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SseJobEventPublisher.class);

    /** Named so the client can listen for exactly this and ignore keep-alives. */
    private static final String EVENT_NAME = "job";

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> listeners = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SseJobEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Register an emitter for a job. It is removed on completion, timeout or error. */
    public void register(UUID jobId, SseEmitter emitter) {
        listeners.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(ex -> remove(jobId, emitter));
    }

    @Override
    public void publish(UUID jobId, Job snapshot) {
        List<SseEmitter> emitters = listeners.get(jobId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        String payload = objectMapper.writeValueAsString(JobDto.from(snapshot, objectMapper));
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(EVENT_NAME).data(payload));
            } catch (IOException | IllegalStateException ex) {
                // The client went away mid-send. Its loss is recoverable with a GET.
                log.debug("Dropping SSE emitter for job {}", jobId, ex);
                remove(jobId, emitter);
            }
        }

        if (snapshot.isTerminal()) {
            closeAll(jobId);
        }
    }

    private void closeAll(UUID jobId) {
        List<SseEmitter> emitters = listeners.remove(jobId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException ex) {
                log.debug("Completing SSE emitter for job {} failed", jobId, ex);
            }
        }
    }

    private void remove(UUID jobId, SseEmitter emitter) {
        listeners.computeIfPresent(jobId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}

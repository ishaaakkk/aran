package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.service.JobEventPublisher;
import edu.upc.dtak.aran.core.service.JobService;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link JobService}.
 *
 * <p>The service lives in aran-core but is not a {@code @Service}: the executor
 * jobs run on and the transport their events go out over are both application
 * decisions. Here they are {@code ThreadConfig}'s {@code taskExecutor} and the
 * SSE emitter registry; a test wires a direct executor and a list.
 */
@Configuration
public class JobConfig {

    @Bean
    public JobService jobService(JobRepository jobRepository,
                                 @Qualifier("taskExecutor") Executor executor,
                                 JobEventPublisher jobEventPublisher) {
        return new JobService(jobRepository, executor, jobEventPublisher);
    }
}

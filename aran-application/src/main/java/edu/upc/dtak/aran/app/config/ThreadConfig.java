package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.tenant.TenantContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring thread pool configuration ensuring Java 25 ScopedValues (TenantContext)
 * are propagated seamlessly and securely to background execution threads.
 */
@Configuration
@EnableAsync
public class ThreadConfig {

    /**
     * Carries the tenant <em>and</em> everything else the request thread was holding.
     *
     * <p>The tenant half is a {@code ScopedValue} and needs the explicit capture below —
     * {@code SimpleAsyncTaskExecutor} calls {@code decorate()} on the submitting thread, so
     * the binding is still live at that point.
     *
     * <p>The rest is delegated to Spring's {@link ContextPropagatingTaskDecorator}, which
     * carries anything registered with Micrometer's {@code ContextRegistry} — the SLF4J MDC
     * and the observation/trace context included. Without it only the tenant crossed, so
     * every lake call made from a background job shipped no correlation ID, falsifying
     * {@code CorrelationIdFilter}'s stated guarantee that one accompanies "any downstream
     * calls to aran-lake" for exactly the long-running work most worth correlating.
     */
    @Bean
    public TaskDecorator tenantContextTaskDecorator() {
        TaskDecorator contextPropagating = new ContextPropagatingTaskDecorator();
        return runnable -> {
            Runnable withContext = contextPropagating.decorate(runnable);
            if (TenantContextHolder.isBound()) {
                TenantContext tenant = TenantContextHolder.require();
                return () -> TenantContextHolder.runWith(tenant, withContext);
            }
            return withContext;
        };
    }

    /**
     * The executor behind {@code @Async} and the job substrate.
     *
     * <p>{@code spring.threads.virtual.enabled=true} makes Boot serve requests on virtual
     * threads, but declaring any {@code Executor} bean makes
     * {@code TaskExecutionAutoConfiguration} back off, so this bean previously replaced
     * Boot's virtual-thread executor with a 32-thread pool — for exactly the workload that
     * benefits most. Jobs here block for minutes on the freyja and lake sidecars
     * (ProfilingOperator waits up to 15 minutes), so 33 concurrent profiling jobs saturated
     * the pool and the 34th queued behind a quarter-hour call.
     *
     * <p>Virtual threads remove that ceiling: a blocked virtual thread parks without holding
     * an OS thread.
     *
     * <p><b>The decorator is load-bearing, not incidental.</b> {@code TenantContextHolder} is
     * a {@code ScopedValue}, which does not cross a thread boundary on its own. Dropping the
     * decorator would let a background task run with no tenant bound — which, combined with
     * any repository query that lacks an explicit tenant predicate, is precisely the
     * cross-tenant read recorded as finding F17. Keep it
     * attached to whatever executor is used here.
     */
    @Bean(name = "taskExecutor")
    public AsyncTaskExecutor taskExecutor(TaskDecorator tenantContextTaskDecorator) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("aran-task-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(tenantContextTaskDecorator);
        // Unbounded virtual threads would make the JDBC pool the only backpressure, and
        // spring.datasource.hikari.connection-timeout is 1500 ms — contention would surface
        // as user-visible 500s rather than queuing. Cap concurrency well above the pool size
        // so slow sidecar calls still overlap freely, while a runaway fan-out cannot exhaust
        // the database connections.
        executor.setConcurrencyLimit(256);
        return executor;
    }
}

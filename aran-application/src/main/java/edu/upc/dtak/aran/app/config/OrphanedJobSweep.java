package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.JobRepository;
import java.time.Instant;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails the jobs the previous JVM left behind, so a restart cannot leave a row spinning forever.
 *
 * <p>A row that is PENDING at startup can never run: {@code JobService} dispatches after the
 * insert's commit, and that after-commit hook died with the process. A row that is RUNNING has no
 * thread behind it for the same reason. Neither will ever be written again, and the UI cannot tell
 * either apart from a job that is genuinely working — the JobCard just spins.
 *
 * <p>The same goes for a data product a materialize job had marked MATERIALIZING: only that job's
 * body sets the status, so with the job gone the product would read "materializing" forever and
 * its refresh button stay disabled (UI audit J6-F7). It is failed with a reason that says so; a
 * snapshot it already had keeps serving, as after any failed refresh.
 *
 * <p>The sweep is tenant-blind by necessity: startup has no tenant bound, and an orphan is an
 * orphan in whichever tenant owns it. {@code TenantFilterAspect} allows exactly that, enabling the
 * Hibernate filter only when a tenant is bound or is the first argument.
 *
 * <p><b>Deployment posture.</b> This is correct for the one-active-instance posture v0.1.0 ships
 * with. A second instance starting while the first is working would sweep the first's live jobs;
 * distinguishing them needs the instance-id-plus-heartbeat column D7's larger programme brings,
 * not a wider WHERE clause here.
 */
@Component
@NullMarked
public class OrphanedJobSweep implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrphanedJobSweep.class);

    /** Distinct from any code a job body can produce, so an orphan is greppable in the job table. */
    public static final String ERROR_CODE = "ORPHANED_ON_RESTART";

    private static final String ERROR_MESSAGE =
            "This job did not survive a restart of Aran and cannot be resumed. Run it again.";

    /** What an interrupted product's {@code lastError} says. */
    public static final String PRODUCT_ERROR =
            "Materialization was interrupted by a restart of Aran and did not finish. Refresh to run it again.";

    private final JobRepository jobs;
    private final DataProductRepository products;

    public OrphanedJobSweep(JobRepository jobs, DataProductRepository products) {
        this.jobs = jobs;
        this.products = products;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    /** @return how many orphaned job rows were failed. */
    public int sweep() {
        int swept = jobs.failOrphaned(ERROR_CODE, ERROR_MESSAGE, Instant.now());
        if (swept > 0) {
            log.warn("Failed {} job(s) left PENDING or RUNNING by a previous run, with error code {}",
                    swept, ERROR_CODE);
        }
        int interrupted = products.failInterruptedMaterializations(PRODUCT_ERROR);
        if (interrupted > 0) {
            log.warn("Failed {} data product(s) left MATERIALIZING by a previous run", interrupted);
        }
        return swept;
    }
}

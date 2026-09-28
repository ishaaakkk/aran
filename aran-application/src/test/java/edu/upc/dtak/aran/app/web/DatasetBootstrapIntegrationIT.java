package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetPreview;
import edu.upc.dtak.aran.core.domain.DatasetRegistration;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.service.DatasetService;
import edu.upc.dtak.aran.core.service.JobEventPublisher;
import edu.upc.dtak.aran.core.service.JobService;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.service.ProjectService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Registering a dataset with a file, now that bootstrapping is a job.
 *
 * <p>Registration returns a {@code PENDING} dataset and the id of the job that will
 * bootstrap it. The dataset becomes {@code READY} only when that job succeeds, and
 * {@code FAILED} — with the reason readable on the job row — when it does not.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> {@code JobService} holds the
 * task back until the submitting transaction commits, so a test that never commits
 * would be watching a job that never starts. Overriding the executor to run inline
 * then makes that commit the moment the bootstrap has already finished: every
 * assertion below is about persisted state, not about how long a pool took.
 */
@DisplayName("Dataset registration → bootstrap job")
public class DatasetBootstrapIntegrationIT extends BaseIntegrationTest {

    /**
     * The application's {@link JobService} on an executor that runs the job on a fresh
     * thread and waits for it. Primary, so {@code DatasetService} injects this one.
     *
     * <p>Not {@code Runnable::run}: the task is dispatched from {@code afterCommit},
     * where the committed transaction's EntityManager is still bound to the calling
     * thread. A job running there would join that finished transaction and its writes
     * would never be committed — the row would sit at PENDING forever. A separate
     * thread is what production does (a pool), and the join is what makes the test
     * deterministic without sleeping on one.
     */
    @TestConfiguration
    static class DirectExecutorJobConfig {
        @Bean
        @Primary
        JobService directJobService(JobRepository jobRepository, JobEventPublisher publisher) {
            Executor awaitedOffThread = task -> {
                Thread worker = new Thread(task, "test-job-worker");
                worker.start();
                try {
                    worker.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting the job", ex);
                }
            };
            return new JobService(jobRepository, awaitedOffThread, publisher);
        }
    }

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DataRepositoryService dataRepositoryService;

    @Autowired
    private JobRepository jobRepository;

    @MockitoBean
    private LakeClient lakeClient;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * The seeded tenant's FREE plan allows three projects, nothing here rolls back, and the three
     * tests below each create one: start from none, as ProjectControllerIT does, so neither this
     * class's own earlier tests nor whichever class ran before it decides the outcome (the Tier A
     * merge changed the class order and this class met "maxProjects 3 / 3").
     */
    @org.junit.jupiter.api.BeforeEach
    void startWithoutProjects() {
        jdbcTemplate.update("DELETE FROM project_member");
        jdbcTemplate.update("DELETE FROM project");
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("the dataset comes back PENDING with a job id, and is READY once that job succeeds")
    public void testDatasetRegistrationAndAsyncBootstrap() {
        AuthenticatedUser caller = caller();
        Project p = projectService.createProject(caller, "Bootstrap Project", "Desc", "PUBLIC", "#fff");
        DataRepository repo = dataRepositoryService.createRepository(caller, p.id(),
                new FileSystemRepository(UUID.randomUUID(), p.id(), TENANT_ID, "TestRepo", "Desc"));

        byte[] csvBytes = "id,name\n1,Alice\n2,Bob".getBytes(StandardCharsets.UTF_8);
        mockLake(csvBytes, "text/csv");

        Dataset input = new Dataset(UUID.randomUUID(), repo.id(), "testds.csv", "Desc");
        DatasetRegistration registration =
                datasetService.registerDatasetWithFile(caller, p.id(), repo.id(), input, csvBytes, "text/csv");

        // What the caller is handed the moment the upload lands: a dataset that is not
        // bootstrapped yet, and the handle on the job that will bootstrap it.
        assertThat(registration.dataset().bootstrapStatus()).isEqualTo(DatasetStatus.PENDING);
        assertThat(registration.bootstrapJobId()).isNotNull();

        Job job = jobRepository.findById(tenant(), registration.bootstrapJobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.jobType()).isEqualTo("dataset:bootstrap");
        assertThat(job.progressStage()).isEqualTo("done");

        Dataset bootstrapped = reload(caller, p.id(), repo.id(), registration.dataset().id());
        assertThat(bootstrapped.bootstrapStatus()).isEqualTo(DatasetStatus.READY);
        assertThat(bootstrapped.graphJson()).isNotNull().isNotEmpty();
        // COVERAGE GAP, recorded rather than hidden: this used to assert that
        // Dataset.wrapper contained executable SQL, then previewWrapper it and check the
        // IRI-aliased headers and row count. Dataset.wrapper was deleted on 2026-08-16 —
        // it was display-only and nothing executed it, so the assertion was proving a
        // property of the dead path, not of the bootstrap.
        //
        // The equivalent assertion against the wrapper that DOES execute is worth writing:
        // read nx:wrapperSql off a source class in the schema graph and previewWrapper THAT.
        // Not written here because this class needs Docker and could not be run.
    }

    @Test
    @DisplayName("an unparseable file FAILS the dataset and says why on the job")
    public void testCorruptSnapshotFailsBootstrapAndDataset() {
        AuthenticatedUser caller = caller();
        Project p = projectService.createProject(caller, "Broken Project", "Desc", "PUBLIC", "#fff");
        DataRepository repo = dataRepositoryService.createRepository(caller, p.id(),
                new FileSystemRepository(UUID.randomUUID(), p.id(), TENANT_ID, "BrokenRepo", "Desc"));

        byte[] corrupt = "{ this is not json".getBytes(StandardCharsets.UTF_8);
        mockLake(corrupt, "application/json");

        Dataset input = new Dataset(UUID.randomUUID(), repo.id(), "broken.json", "Desc");
        DatasetRegistration registration =
                datasetService.registerDatasetWithFile(caller, p.id(), repo.id(), input, corrupt, "application/json");

        assertThat(registration.dataset().bootstrapStatus()).isEqualTo(DatasetStatus.PENDING);
        assertThat(registration.bootstrapJobId()).isNotNull();

        Job job = jobRepository.findById(tenant(), registration.bootstrapJobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo("BOOTSTRAP_FAILED");
        assertThat(job.errorMessage()).isNotBlank();
        assertThat(job.finishedAt()).isNotNull();

        // And the dataset says so too — no row is left claiming PENDING forever.
        assertThat(reload(caller, p.id(), repo.id(), registration.dataset().id()).bootstrapStatus())
                .isEqualTo(DatasetStatus.FAILED);
    }

    @Test
    @DisplayName("a second upload under a name the repository holds is a NAME_TAKEN conflict, and the lake never sees it (J2-F21)")
    public void duplicateUploadNameIsRefusedBeforeTheLake() {
        AuthenticatedUser caller = caller();
        Project p = projectService.createProject(caller, "Duplicate Upload Project", "Desc", "PRIVATE", "#fff");
        DataRepository repo = dataRepositoryService.createRepository(caller, p.id(),
                new FileSystemRepository(UUID.randomUUID(), p.id(), TENANT_ID, "DupRepo", "Desc"));
        byte[] csvBytes = "id,name\n1,Alice".getBytes(StandardCharsets.UTF_8);
        mockLake(csvBytes, "text/csv");
        datasetService.registerDatasetWithFile(caller, p.id(), repo.id(),
                new Dataset(UUID.randomUUID(), repo.id(), "acm.csv", "first"), csvBytes, "text/csv");
        org.mockito.Mockito.clearInvocations(lakeClient);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> datasetService.registerDatasetWithFile(
                        caller, p.id(), repo.id(),
                        new Dataset(UUID.randomUUID(), repo.id(), "acm.csv", "corrected"), csvBytes, "text/csv"))
                .isInstanceOf(edu.upc.dtak.aran.core.service.ConflictException.class)
                .hasMessageContaining("'acm.csv' already exists");
        org.mockito.Mockito.verifyNoInteractions(lakeClient);
    }

    private AuthenticatedUser caller() {
        return new AuthenticatedUser(USER_ID, "sub", TENANT_ID,
                edu.upc.dtak.aran.core.domain.TenantRole.OWNER);
    }

    private TenantContext tenant() {
        return new TenantContext(TENANT_ID.toString());
    }

    private void mockLake(byte[] bytes, String contentType) {
        when(lakeClient.initUpload(any(UUID.class), any(UUID.class), anyLong(), any()))
                .thenReturn(new LakeClient.InitUploadResponse("upload123", "http://mock", contentType));
        when(lakeClient.computeSha256(bytes)).thenReturn("hash");
        when(lakeClient.completeUpload(any(UUID.class), any(), any(), anyLong()))
                .thenReturn("snapshot123");
        when(lakeClient.downloadSnapshot(any(UUID.class), any()))
                .thenReturn(new LakeClient.SnapshotBytes(bytes, contentType));
    }

    private Dataset reload(AuthenticatedUser caller, UUID projectId, UUID repositoryId, UUID datasetId) {
        List<Dataset> datasets = datasetService.listDatasets(caller, projectId, repositoryId);
        return datasets.stream()
                .filter(d -> d.id().equals(datasetId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("dataset " + datasetId + " vanished"));
    }
}

package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubmission;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The jobs API end-to-end: submit an operator, read the job back, stream it, and
 * confirm another tenant's job is invisible.
 */
@DisplayName("JobController")
@Transactional
class JobControllerIT extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JobRepository jobs;
    @Autowired ObjectMapper objectMapper;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager entityManager;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Write the fixture row to the database now, from the test thread.
     *
     * <p>The test method and the MockMvc request share one transaction, so an
     * unflushed insert would be flushed <em>inside</em> the request — under the
     * caller's tenant scope, where {@code TenantSecurityEventListener} rightly
     * rejects it as a boundary crossing. Flushing here, with no tenant bound,
     * is the same situation the listener allows for background writes.
     */
    private Job persist(TenantContext tenant, Job job) {
        Job saved = jobs.save(tenant, job);
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    @BeforeEach
    void setUp() {
        User alice = userRepository.findBySubject(JwtTestHelper.ALICE_SUBJECT)
                .orElseGet(() -> userRepository.save(new User(
                        UUID.randomUUID(), JwtTestHelper.ALICE_SUBJECT,
                        JwtTestHelper.ALICE_EMAIL, JwtTestHelper.ALICE_NAME, Instant.now())));

        if (tenantRepository.findMembership(alice.id(), TENANT_ID).isEmpty()) {
            tenantRepository.saveMembership(new Membership(
                    UUID.randomUUID(), alice.id(), TENANT_ID, TenantRole.OWNER, Instant.now()));
        }
    }

    @Test
    @DisplayName("POST /submit returns 202 with a job id, and the job is readable immediately")
    void submit_returns_202_and_a_persisted_job() throws Exception {
        String body = mockMvc.perform(post("/api/operators/{operatorId}/submit", "schema-integration")
                        .with(JwtTestHelper.alice(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operation": "ping",
                                  "parameters": {}
                                }
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // Read it back as the DTO the controller declares, so the test breaks if the
        // response shape drifts rather than quietly matching a different field.
        JobSubmission submission = objectMapper.readValue(body, JobSubmission.class);
        assertThat(submission.jobId()).isNotNull();

        // The row exists the moment submit returns — that is the whole contract.
        mockMvc.perform(get("/api/jobs/{id}", submission.jobId()).with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submission.jobId().toString()))
                .andExpect(jsonPath("$.jobType").value("operator:schema-integration:ping"));
    }

    @Test
    @DisplayName("POST /submit on an unknown operator is a 404, not a job that fails later")
    void submit_unknown_operator_is_404() throws Exception {
        mockMvc.perform(post("/api/operators/{operatorId}/submit", "no-such-operator")
                        .with(JwtTestHelper.alice(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "operation": "ping", "parameters": {} }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/jobs/{id} for another tenant's job is 404")
    void other_tenants_job_is_not_found() throws Exception {
        UUID otherTenantId = UUID.randomUUID();
        var otherCtx = new TenantContext(otherTenantId.toString());
        Job foreign = persist(otherCtx, Job.pending(UUID.randomUUID(), otherTenantId, null,
                "test:foreign", Map.of(), null, Instant.now()));

        mockMvc.perform(get("/api/jobs/{id}", foreign.id()).with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/jobs lists this tenant's jobs, newest first")
    void list_returns_tenant_jobs() throws Exception {
        var ctx = new TenantContext(TENANT_ID.toString());
        Job job = persist(ctx, Job.pending(UUID.randomUUID(), TENANT_ID, null,
                "test:listed", Map.of(), null, Instant.now()));

        mockMvc.perform(get("/api/jobs?limit=50").with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + job.id() + "')]").exists());
    }

    @Test
    @DisplayName("GET /api/jobs/{id}/events streams text/event-stream")
    void events_endpoint_is_an_event_stream() throws Exception {
        var ctx = new TenantContext(TENANT_ID.toString());
        Job job = persist(ctx, new Job(UUID.randomUUID(), TENANT_ID, null, "test:terminal",
                JobStatus.SUCCEEDED, Map.of(), "done", 100, "{\"rows\":\"1\"}", null, null,
                null, Instant.now(), Instant.now(), Instant.now()));

        // A job that is already terminal streams its state once and completes, so the
        // request finishes without the test having to wait on an open connection.
        var result = mockMvc.perform(get("/api/jobs/{id}/events", job.id())
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:job")));
    }

    @Test
    @DisplayName("GET /api/jobs/{id}/events for another tenant's job is 404")
    void events_for_other_tenants_job_is_not_found() throws Exception {
        UUID otherTenantId = UUID.randomUUID();
        var otherCtx = new TenantContext(otherTenantId.toString());
        Job foreign = persist(otherCtx, Job.pending(UUID.randomUUID(), otherTenantId, null,
                "test:foreign-stream", Map.of(), null, Instant.now()));

        mockMvc.perform(get("/api/jobs/{id}/events", foreign.id())
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isNotFound());
    }
}

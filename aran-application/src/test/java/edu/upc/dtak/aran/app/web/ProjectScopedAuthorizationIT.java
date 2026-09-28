package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.JobRepository;
import edu.upc.dtak.aran.core.domain.JobStatus;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelBundle;
import edu.upc.dtak.aran.core.domain.ModelConstraint;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S2-1: project visibility is an authorization invariant ({@code ProjectService}'s javadoc), and
 * the repositories/datasets branch honours it — but the model, products and cubes controllers took
 * a bare {@code projectId} from the URL and never asked. A tenant MEMBER with no
 * {@code ProjectMember} row could read and edit another project's integration model, and create,
 * materialize or delete its products and cubes, by knowing the project UUID. Cross-tenant isolation
 * held throughout; this is the intra-tenant half.
 *
 * <p>Same-tenant, two people: alice owns a project bob cannot see. The answer bob must get is the
 * one {@code getVisibleProject} gives everywhere else — 404, indistinguishable from "no such
 * project" — never 403, which would confirm the project exists.
 */
// The cube routes exist only where the deployment enabled them (aran.cubes.enabled, default
// false — see CubeController). Without this the cube half of this test would pass for the wrong
// reason: bob's 404 would be "no such route", not "no such project", and alice's 200 would fail.
@org.springframework.test.context.TestPropertySource(properties = "aran.cubes.enabled=true")
@DisplayName("Project-scoped routes — model, products, cubes and jobs are gated on project visibility")
class ProjectScopedAuthorizationIT extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired DataProductRepository products;
    @Autowired GraphVersionPort graphVersions;
    @Autowired IntegrationModelRepository models;
    @Autowired JobRepository jobs;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;
    @Autowired JdbcTemplate jdbc;

    @Autowired LicenseAdminPort licenseAdmin;

    /**
     * A tenant of this test's own, on a plan whose project limit three projects do not reach. The
     * shared seeded tenant is on FREE, and its limit is exactly what other ITs assert, so creating
     * projects there is a 402 waiting to happen in a full run.
     */
    private UUID tenantId;
    private String ownerSubject;
    private String bobSubject;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "authz-" + suffix, "Authz " + suffix, Instant.now()));
        licenseAdmin.issueInitialLicense(tenantId, LicensePlan.PRO);

        ownerSubject = "owner-" + suffix;
        User owner = users.save(new User(UUID.randomUUID(), ownerSubject, ownerSubject + "@example.com",
                "Owner", Instant.now()));
        tenants.saveMembership(new Membership(
                UUID.randomUUID(), owner.id(), tenantId, TenantRole.OWNER, Instant.now()));

        bobSubject = "bob-" + suffix;
        User bob = users.save(new User(UUID.randomUUID(), bobSubject, bobSubject + "@example.com",
                "Bob Member", Instant.now()));
        tenants.saveMembership(new Membership(
                UUID.randomUUID(), bob.id(), tenantId, TenantRole.MEMBER, Instant.now()));
    }

    /** The tenant role is read from the membership row, so one JWT shape serves both people. */
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor owner() {
        return JwtTestHelper.subject(ownerSubject, tenantId);
    }

    @Test
    @DisplayName("a tenant member who is not a project member gets 404 from the project's model, products and cubes")
    void non_member_is_told_the_project_does_not_exist() throws Exception {
        UUID project = createProjectAs(owner(), "alice-private");

        // The owner sees all three surfaces...
        mockMvc.perform(get("/api/projects/{p}/model", project).with(owner()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects/{p}/products", project).with(owner()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects/{p}/cubes", project).with(owner()))
                .andExpect(status().isOk());

        // ...and bob, same tenant, no ProjectMember row, is told there is no such project.
        var bob = JwtTestHelper.subject(bobSubject, tenantId);
        mockMvc.perform(get("/api/projects/{p}/model", project).with(bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/{p}/products", project).with(bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/{p}/cubes", project).with(bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/{p}/cubes/evidence", project).with(bob))
                .andExpect(status().isNotFound());

        // Writes too — this is the half that changed query results for everyone in the project.
        mockMvc.perform(post("/api/projects/{p}/products", project).with(bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p\",\"description\":\"\",\"oql\":\"SELECT X.a FROM X\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/{p}/model/compile", project).with(bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/{p}/model/constraints", project).with(bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classIri\":\"http://x/C\",\"expression\":\"a > 1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a project a member CAN see does not let them reach another project's product by id")
    void a_visible_project_is_not_a_door_to_another_projects_rows() throws Exception {
        UUID alicesProject = createProjectAs(owner(), "alice-only");
        UUID bobsProject = createProjectAs(JwtTestHelper.subject(bobSubject, tenantId), "bob-own");

        TenantContext tenant = new TenantContext(tenantId.toString());
        GraphVersion version = graphVersions.save(tenant, new GraphVersion(
                UUID.randomUUID(), tenantId, 1, "DRAFT", Instant.now(), null, null, null));
        DataProduct alicesProduct = products.save(tenant, DataProduct.draft(
                UUID.randomUUID(), tenantId, alicesProject, "orders", "", "SELECT X.a FROM X",
                version.id(), null, Instant.now()));

        mockMvc.perform(get("/api/projects/{p}/products/{id}", alicesProject, alicesProduct.id())
                        .with(owner()))
                .andExpect(status().isOk());
        // Bob names a project he is a member of, and someone else's product id under it.
        mockMvc.perform(get("/api/projects/{p}/products/{id}", bobsProject, alicesProduct.id())
                        .with(JwtTestHelper.subject(bobSubject, tenantId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a project a member CAN see does not let them decide, enforce or delete another"
            + " project's mappings and constraints by id")
    void a_visible_project_is_not_a_door_to_another_projects_model_rows() throws Exception {
        UUID alicesProject = createProjectAs(owner(), "alice-model");
        UUID bobsProject = createProjectAs(JwtTestHelper.subject(bobSubject, tenantId), "bob-model");

        TenantContext tenant = new TenantContext(tenantId.toString());
        IntegrationModel alicesModel = models.getOrCreate(tenant, alicesProject);
        ModelMapping mapping = models.addMapping(tenant, new ModelMapping(UUID.randomUUID(),
                tenantId, alicesModel.id(), MappingKinds.CLASS, "http://ex/A", "http://ex/B",
                "Paper", ModelMapping.ACCEPTED, null, null, null, Instant.now()));
        ModelConstraint discovered = models.addConstraint(tenant, new ModelConstraint(
                UUID.randomUUID(), tenantId, alicesModel.id(), "http://ex/Paper", "a = a", false,
                Instant.now(), "DISCOVERED"));
        ModelConstraint manual = models.addConstraint(tenant, new ModelConstraint(
                UUID.randomUUID(), tenantId, alicesModel.id(), "http://ex/Paper", "a > 1", false,
                Instant.now(), "MANUAL"));

        // Under her own URL the V43 refusal holds, as it always did.
        mockMvc.perform(patch("/api/projects/{p}/model/constraints/{id}", alicesProject, discovered.id())
                        .with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enforced\":true}"))
                .andExpect(status().isBadRequest());

        // Bob names the project he can see and alice's row ids under it.
        var bob = JwtTestHelper.subject(bobSubject, tenantId);
        mockMvc.perform(patch("/api/projects/{p}/model/mappings/{id}", bobsProject, mapping.id())
                        .with(bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/{p}/model/mappings/{id}", bobsProject, mapping.id())
                        .with(bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/projects/{p}/model/constraints/{id}", bobsProject, discovered.id())
                        .with(bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enforced\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/projects/{p}/model/constraints/{id}", bobsProject, manual.id())
                        .with(bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enforced\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/{p}/model/constraints/{id}", bobsProject, manual.id())
                        .with(bob))
                .andExpect(status().isNotFound());

        ModelBundle after = models.load(tenant, alicesProject);
        assertThat(after.mappings()).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(ModelMapping.ACCEPTED));
        assertThat(after.constraints()).extracting(ModelConstraint::id)
                .containsExactlyInAnyOrder(discovered.id(), manual.id());
        assertThat(models.findEnforcedConstraints(tenant))
                .as("nothing bob sent reached the tenant's enforced set").isEmpty();
    }

    @Test
    @DisplayName("another project's jobs are not listed, read or streamed — they carry that"
            + " project's row ids (a compile's appliedMappingIds)")
    void another_projects_jobs_are_invisible() throws Exception {
        UUID alicesProject = createProjectAs(owner(), "alice-jobs");
        UUID bobsProject = createProjectAs(JwtTestHelper.subject(bobSubject, tenantId), "bob-jobs");

        TenantContext tenant = new TenantContext(tenantId.toString());
        UUID appliedMapping = UUID.randomUUID();
        Job alicesJob = jobs.save(tenant, new Job(UUID.randomUUID(), tenantId, alicesProject,
                "model:compile", JobStatus.SUCCEEDED, Map.of("projectId", alicesProject.toString()),
                "done", 100, "{\"appliedMappingIds\":\"[\\\"" + appliedMapping + "\\\"]\"}",
                null, null, null, Instant.now(), Instant.now(), Instant.now()));
        Job bobsJob = jobs.save(tenant, Job.pending(UUID.randomUUID(), tenantId, bobsProject,
                "model:compile", Map.of(), null, Instant.now()));

        mockMvc.perform(get("/api/jobs/{id}", alicesJob.id()).with(owner()))
                .andExpect(status().isOk());

        var bob = JwtTestHelper.subject(bobSubject, tenantId);
        mockMvc.perform(get("/api/jobs/{id}", alicesJob.id()).with(bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jobs/{id}/events", alicesJob.id()).with(bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jobs").param("projectId", alicesProject.toString()).with(bob))
                .andExpect(status().isNotFound());
        String listing = mockMvc.perform(get("/api/jobs").param("limit", "100").with(bob))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).contains(bobsJob.id().toString())
                .doesNotContain(alicesJob.id().toString())
                .doesNotContain(appliedMapping.toString());
    }

    @Test
    @DisplayName("a job names its dataset, product or project for a member — and names nothing for"
            + " a tenant member outside the project (J8-F7)")
    void job_subjects_are_named_only_for_the_objects_project_members() throws Exception {
        UUID alicesProject = createProjectAs(owner(), "alice-subjects");
        TenantContext tenant = new TenantContext(tenantId.toString());
        DataRepository repo = repositories.save(tenant,
                new FileSystemRepository(UUID.randomUUID(), alicesProject, tenantId, "uploads", ""));
        Dataset payroll = datasets.save(tenant, new Dataset(UUID.randomUUID(), repo.id(), "payroll_2026", ""));
        GraphVersion version = graphVersions.save(tenant, new GraphVersion(
                UUID.randomUUID(), tenantId, 1, "DRAFT", Instant.now(), null, null, null));
        DataProduct product = products.save(tenant, DataProduct.draft(
                UUID.randomUUID(), tenantId, alicesProject, "top_authors", "", "SELECT X.a FROM X",
                version.id(), null, Instant.now()));

        Job bootstrap = jobs.save(tenant, Job.pending(UUID.randomUUID(), tenantId, alicesProject,
                "dataset:bootstrap", Map.of("datasetId", payroll.id().toString()), null, Instant.now()));
        Job materialize = jobs.save(tenant, Job.pending(UUID.randomUUID(), tenantId, alicesProject,
                "product:materialize", Map.of("productId", product.id().toString()), null, Instant.now()));
        Job rebuild = jobs.save(tenant, Job.pending(UUID.randomUUID(), tenantId, alicesProject,
                "model:compile", Map.of(), null, Instant.now()));
        // No project: every tenant member lists it — but its dataset is alice's project's.
        Job profile = jobs.save(tenant, Job.pending(UUID.randomUUID(), tenantId, null,
                "operator:profiling:profile", Map.of("datasetId", payroll.id().toString()), null, Instant.now()));

        String asOwner = mockMvc.perform(get("/api/jobs").param("limit", "100").with(owner()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(subjectOf(asOwner, bootstrap)).containsEntry("kind", "dataset")
                .containsEntry("name", "payroll_2026").containsEntry("repositoryId", repo.id().toString());
        assertThat(subjectOf(asOwner, materialize)).containsEntry("kind", "product")
                .containsEntry("name", "top_authors");
        assertThat(subjectOf(asOwner, rebuild)).containsEntry("kind", "project");
        assertThat(subjectOf(asOwner, rebuild).get("name")).asString().startsWith("alice-subjects-");
        assertThat(subjectOf(asOwner, profile)).containsEntry("name", "payroll_2026");
        // The single-job route names it the same way.
        mockMvc.perform(get("/api/jobs/{id}", bootstrap.id()).with(owner()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.subject.name").value("payroll_2026"));

        String asBob = mockMvc.perform(get("/api/jobs").param("limit", "100")
                        .with(JwtTestHelper.subject(bobSubject, tenantId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(asBob).contains(profile.id().toString())
                .doesNotContain("payroll_2026")
                .doesNotContain(repo.id().toString());
        assertThat(subjectOf(asBob, profile)).isNull();
    }

    /** The `subject` of `job` in a GET /api/jobs listing, or null when it has none. */
    private static Map<String, Object> subjectOf(String listing, Job job) {
        java.util.List<Map<String, Object>> subjects = com.jayway.jsonpath.JsonPath.read(
                listing, "$[?(@.id == '" + job.id() + "')].subject");
        assertThat(subjects).hasSize(1);
        return subjects.getFirst();
    }

    @Test
    @DisplayName("V42: an integration_model row cannot point at a project that does not exist")
    void integration_model_rows_need_a_real_project() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO integration_model (id, tenant_id, project_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), tenantId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createProjectAs(
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor as,
            String name) throws Exception {
        String body = mockMvc.perform(post("/api/projects").with(as)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "-" + UUID.randomUUID() + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }
}

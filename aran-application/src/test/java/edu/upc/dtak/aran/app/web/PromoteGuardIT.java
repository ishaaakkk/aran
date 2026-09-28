package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.IntegrationRunMode;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.service.PromoteConflictException;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * UI-OD2 and J4-F2 over HTTP, against Postgres: two projects' integration models in one tenant,
 * their runs and application rows in the real tables, compared through the real routes.
 *
 * <p>The graph store is the suite's in-memory one (no Fuseki here). What this proves that the
 * core unit test cannot: that {@code findRunByDraftVersion}/{@code latestDraftRun}/
 * {@code findAllModels} are right in SQL, that both promote routes reach the guard, and that the
 * refusal leaves as a 409 carrying {@code errorCode} and the class IRIs — the shape the promote
 * dialog renders.
 */
@DisplayName("Promote guard — two models in one tenant, over HTTP (UI-OD2, J4-F2)")
class PromoteGuardIT extends BaseIntegrationTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/integration/";

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired IntegrationModelRepository models;
    @Autowired GraphVersionPort graphVersions;
    @Autowired InMemoryGraphStore graphStore;
    @Autowired LicenseAdminPort licenseAdmin;

    private UUID tenantId;
    private UUID userId;
    private String subject;
    private TenantContext tenant;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = UUID.randomUUID();
        tenant = new TenantContext(tenantId.toString());
        tenants.save(new Tenant(tenantId, "promote-" + suffix, "Promote " + suffix, Instant.now()));
        licenseAdmin.issueInitialLicense(tenantId, LicensePlan.PRO);
        subject = "owner-" + suffix;
        User owner = users.save(new User(UUID.randomUUID(), subject, subject + "@example.com",
                "Owner", Instant.now()));
        userId = owner.id();
        tenants.saveMembership(new Membership(UUID.randomUUID(), userId, tenantId, TenantRole.OWNER, Instant.now()));
    }

    private JwtRequestPostProcessor owner() {
        return JwtTestHelper.subject(subject, tenantId);
    }

    private UUID createProject(String name) throws Exception {
        String body = mockMvc.perform(post("/api/projects").with(owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "-" + UUID.randomUUID() + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    /**
     * What a compile leaves behind, written the way the service writes it: a draft version holding
     * the given classes, and a DRAFT run of the project's model pointing at it.
     */
    private IntegrationRun compiled(UUID project, String... classes) {
        Model graph = ModelFactory.createDefaultModel();
        for (String c : classes) {
            graph.add(graph.createResource(NS + c), RDF.type, RDFS.Class);
        }
        GraphVersion draft = graphStore.replaceDraft(tenant, graph, new AuditedActor(userId));
        // The in-memory store keeps versions to itself; the bundle reads them through the port.
        graphVersions.save(tenant, new GraphVersion(draft.id(), tenantId, draft.versionNumber(), "DRAFT",
                draft.createdAt(), userId, null, null));
        IntegrationModel model = models.getOrCreate(tenant, project);
        IntegrationRun run = models.startRun(tenant, model.id(), IntegrationRunMode.REBUILD, null, null);
        models.recordDraft(tenant, run.id(), draft.id());
        return models.findRun(tenant, run.id()).orElseThrow();
    }

    @Test
    @DisplayName("project B's promote that would remove project A's classes is refused 409 on both routes, naming them")
    void anotherProjectsClassesAreNotRemoved() throws Exception {
        UUID projectA = createProject("a");
        UUID projectB = createProject("b");
        IntegrationRun runA = compiled(projectA, "Author", "Paper");
        mockMvc.perform(post("/api/projects/{p}/model/runs/{r}/promote", projectA, runA.id()).with(owner()))
                .andExpect(status().isOk());

        IntegrationRun runB = compiled(projectB, "Venue");
        mockMvc.perform(post("/api/projects/{p}/model/runs/{r}/promote", projectB, runB.id()).with(owner()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(PromoteConflictException.REMOVES_OTHER_MODELS_CLASSES))
                .andExpect(jsonPath("$.classes", containsInAnyOrder(NS + "Author", NS + "Paper")))
                .andExpect(jsonPath("$.detail", containsString("Author, Paper")))
                // The owner is named — the caller is a member of project A — and the way forward said.
                .andExpect(jsonPath("$.projects[0]", startsWith("a-")))
                .andExpect(jsonPath("$.detail", containsString("project \"a-")))
                .andExpect(jsonPath("$.detail", containsString("To promote")));
        mockMvc.perform(post("/api/graph/promote/{d}", runB.draftVersionId()).with(owner()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(PromoteConflictException.REMOVES_OTHER_MODELS_CLASSES));

        assertThat(graphStore.currentVersion(tenant).id())
                .as("the tenant still reads project A's schema")
                .isEqualTo(runA.draftVersionId());

        // B's draft is still there to be seen after a reload, with the number the dialog shows.
        mockMvc.perform(get("/api/projects/{p}/model", projectB).with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestDraftRun.runId").value(runB.id().toString()))
                .andExpect(jsonPath("$.activeVersion.id").value(runA.draftVersionId().toString()));
    }

    @Test
    @DisplayName("review UA5: a class two projects promoted leaves only once the other project compiled without it")
    void aSharedClassNeedsBothProjects() throws Exception {
        UUID projectA = createProject("a");
        UUID projectB = createProject("b");
        IntegrationRun runA = compiled(projectA, "Author");
        mockMvc.perform(post("/api/projects/{p}/model/runs/{r}/promote", projectA, runA.id()).with(owner()))
                .andExpect(status().isOk());
        // B follows the refusal's advice and carries A's class too — then drops it.
        IntegrationRun shared = compiled(projectB, "Author", "Venue");
        mockMvc.perform(post("/api/projects/{p}/model/runs/{r}/promote", projectB, shared.id()).with(owner()))
                .andExpect(status().isOk());
        IntegrationRun dropsAuthor = compiled(projectB, "Venue");

        // B owns ACTIVE, but A's last promote still carries Author: refused, not a silent removal.
        mockMvc.perform(post("/api/projects/{p}/model/runs/{r}/promote", projectB, dropsAuthor.id()).with(owner()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.classes", containsInAnyOrder(NS + "Author")))
                .andExpect(jsonPath("$.detail", containsString("This project promoted Author too")));

        // A compiles a draft without Author — its newest compile releases it — and B's promote goes through.
        compiled(projectA, "Venue");
        mockMvc.perform(post("/api/projects/{p}/model/runs/{r}/promote", projectB, dropsAuthor.id()).with(owner()))
                .andExpect(status().isOk());
        assertThat(graphStore.currentVersion(tenant).id()).isEqualTo(dropsAuthor.draftVersionId());
    }

    @Test
    @DisplayName("review UA5: a tenant member outside the project cannot promote its draft from Schema versions — 404, nothing moves")
    void aNonMemberCannotPromoteAProjectsDraft() throws Exception {
        UUID project = createProject("private");
        IntegrationRun run = compiled(project, "Author");
        String outsider = "outsider-" + UUID.randomUUID().toString().substring(0, 8);
        User member = users.save(new User(UUID.randomUUID(), outsider, outsider + "@example.com",
                "Outsider", Instant.now()));
        tenants.saveMembership(new Membership(UUID.randomUUID(), member.id(), tenantId, TenantRole.MEMBER, Instant.now()));
        // The suite's in-memory graph store is one for every tenant, so "unchanged" is the check.
        GraphVersion before = graphStore.currentVersion(tenant);

        mockMvc.perform(post("/api/graph/promote/{d}", run.draftVersionId()).with(JwtTestHelper.subject(outsider, tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", containsString("project you are not a member of")));

        assertThat(graphStore.currentVersion(tenant)).as("nothing was promoted").isEqualTo(before);
        assertThat(models.findRun(tenant, run.id()).orElseThrow().status().name()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("J4-F2: a model draft promoted from Schema versions becomes the model's baseline")
    void genericPromoteDelegatesToTheRunThatOwnsTheDraft() throws Exception {
        UUID project = createProject("solo");
        IntegrationRun run = compiled(project, "Author");

        mockMvc.perform(post("/api/graph/promote/{d}", run.draftVersionId()).with(owner()))
                .andExpect(status().isOk());

        IntegrationModel model = models.getOrCreate(tenant, project);
        assertThat(model.activeIntegrationRunId())
                .as("the run owns the promoted draft, so the model has its baseline")
                .isEqualTo(run.id());
        mockMvc.perform(get("/api/projects/{p}/model", project).with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineVersionId").value(run.draftVersionId().toString()))
                .andExpect(jsonPath("$.latestDraftRun").doesNotExist());
    }
}

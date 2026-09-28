package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;

import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link ProjectController}.
 *
 * <p>Uses a real Postgres container (via {@link BaseIntegrationTest}) and {@link JwtTestHelper} to
 * build mock JWTs — no Keycloak required.
 *
 * <p><b>Every test gets its own tenant.</b> The container is shared across the whole class (and
 * the whole module) and nothing rolls back between tests, so a class-wide tenant makes each test's
 * result depend on which ones ran before it: {@code returns_empty_list} would see the project
 * {@code creates_project} left behind, and the license-limit test would consume the quota the
 * creation test needs. A fresh tenant per test removes the ordering dependency and is what lets
 * {@code returns_empty_list} assert <em>empty</em> rather than merely <em>is an array</em> — the
 * shape assertion it used to make could not fail.
 *
 * <p>The tenant role of a caller comes from its {@code membership} row, not from the token, so
 * each role under test is seeded as a real membership.
 */
@DisplayName("ProjectController")
class ProjectControllerIT extends BaseIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    LicenseAdminPort licenseAdmin;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** The tenant for the current test. */
    UUID tenantId;

    /** Seeded MEMBER of {@link #tenantId} — the caller for tenant-role gate tests. */
    static final String BOB_SUBJECT_PREFIX = "bob-";
    String bobSubject;

    /** Seeded user with NO membership in {@link #tenantId} — the target for cross-tenant tests. */
    UUID carolId;

    /**
     * Nothing rolls back here, and the seeded tenant's FREE plan allows three projects: what this
     * class leaves behind is what the next class to create a project in that tenant runs into
     * (DatasetBootstrapIntegrationIT hit "maxProjects 3 / 3" once the Tier A merge changed the
     * class order). Leave the tenant as this class found it: without projects.
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM project_member");
        jdbcTemplate.update("DELETE FROM project");
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        jdbcTemplate.update("DELETE FROM project_member");
        jdbcTemplate.update("DELETE FROM project");
        jdbcTemplate.update("DELETE FROM membership WHERE tenant_id = ? AND user_id != '00000000-0000-0000-0000-000000000002'", tenantId);

        var alice = findOrCreateUser(JwtTestHelper.ALICE_SUBJECT,
                JwtTestHelper.ALICE_EMAIL, JwtTestHelper.ALICE_NAME);
        if (tenantRepository.findMembership(alice.id(), tenantId).isEmpty()) {
            tenantRepository.saveMembership(new Membership(
                    UUID.randomUUID(), alice.id(), tenantId, TenantRole.OWNER, Instant.now()));
        }

        bobSubject = BOB_SUBJECT_PREFIX + UUID.randomUUID();
        var bob = findOrCreateUser(bobSubject, bobSubject + "@example.com", "Bob Member");
        tenantRepository.saveMembership(new Membership(
                UUID.randomUUID(), bob.id(), tenantId, TenantRole.MEMBER, Instant.now()));

        // Carol exists as a user but is deliberately given no membership in this tenant.
        String carolSubject = "carol-" + UUID.randomUUID();
        carolId = findOrCreateUser(carolSubject, carolSubject + "@example.com", "Carol Outsider").id();
    }

    private User findOrCreateUser(String subject, String email, String name) {
        return userRepository.findBySubject(subject)
                .orElseGet(() -> userRepository.save(new User(
                        UUID.randomUUID(), subject, email, name, Instant.now())));
    }

    /** Creates a project as alice and returns its id. */
    private UUID createProjectAsAlice(String name) throws Exception {
        String body = mockMvc.perform(post("/api/projects")
                        .with(JwtTestHelper.alice(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"desc\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    @Nested
    @DisplayName("GET /api/projects")
    class ListProjects {

        @Test
        @DisplayName("returns an empty list when the tenant has no projects")
        void returns_empty_list() throws Exception {
            mockMvc.perform(get("/api/projects")
                            .with(JwtTestHelper.alice(tenantId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("lists only the projects the caller can see")
        void lists_only_visible_projects() throws Exception {
            createProjectAsAlice("Alice Project");
            mockMvc.perform(get("/api/projects")
                            .with(JwtTestHelper.alice(tenantId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Alice Project"));

            // Bob is in the tenant but is not a member of the project, so he sees nothing.
            // This is the assertion that distinguishes visibility filtering from tenant filtering.
            mockMvc.perform(get("/api/projects")
                            .with(JwtTestHelper.subject(bobSubject, tenantId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("returns 401 when no token provided")
        void returns_401_without_token() throws Exception {
            mockMvc.perform(get("/api/projects"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("returns 403 when user has no tenant membership")
        void returns_403_when_no_membership() throws Exception {
            mockMvc.perform(get("/api/projects")
                            .with(JwtTestHelper.noTenantUser()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /api/projects")
    class CreateProject {

        @Test
        @DisplayName("creates a project and returns 201")
        void creates_project() throws Exception {
            mockMvc.perform(post("/api/projects")
                            .with(JwtTestHelper.alice(tenantId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Test Project", "description": "desc"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Test Project"))
                    .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                    .andExpect(jsonPath("$.createdBy").isNotEmpty());
        }

        @Test
        @DisplayName("returns 400 when name is blank")
        void returns_400_for_blank_name() throws Exception {
            mockMvc.perform(post("/api/projects")
                            .with(JwtTestHelper.alice(tenantId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "", "description": "desc"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid request"));
        }

        @Test
        @DisplayName("returns 402 when the FREE plan's project limit is exceeded")
        void returns_402_when_project_limit_exceeded() throws Exception {
            // FREE allows 3. Created through the API rather than inserted, so the usage counter
            // the gate reads is the one these creations actually moved.
            createProjectAsAlice("P1");
            createProjectAsAlice("P2");
            createProjectAsAlice("P3");

            mockMvc.perform(post("/api/projects")
                            .with(JwtTestHelper.alice(tenantId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "P4", "description": "one too many"}
                                    """))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.title").value("License limit exceeded"))
                    .andExpect(jsonPath("$.limit").value("maxProjects"));
        }
    }

    @Nested
    @DisplayName("POST /api/projects/{id}/members")
    class AddMember {

        @Test
        @DisplayName("returns 403 when caller is MEMBER not OWNER")
        void returns_403_for_non_owner() throws Exception {
            UUID projectId = createProjectAsAlice("Shared Project");

            // Bob is a tenant MEMBER with a real membership row, so he clears the membership
            // check and is rejected by the OWNER gate in ProjectService.addMember — which is the
            // rule under test. A caller with no membership would 403 one step earlier and prove
            // nothing about the gate.
            mockMvc.perform(post("/api/projects/{id}/members", projectId)
                            .with(JwtTestHelper.subject(bobSubject, tenantId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + carolId + "\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 404 when target user has no tenant membership")
        void returns_404_when_target_not_tenant_member() throws Exception {
            UUID projectId = createProjectAsAlice("Owner Project");

            // Carol is a real user with no membership in this tenant. 404 rather than 403 is
            // deliberate: it stops the endpoint being used to probe which user ids exist.
            mockMvc.perform(post("/api/projects/{id}/members", projectId)
                            .with(JwtTestHelper.alice(tenantId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + carolId + "\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an OWNER can add a tenant member, so the 403 above is about the role")
        void owner_can_add_tenant_member() throws Exception {
            UUID projectId = createProjectAsAlice("Owner Project");

            mockMvc.perform(post("/api/projects/{id}/members", projectId)
                            .with(JwtTestHelper.alice(tenantId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + userRepository.findBySubject(bobSubject)
                                    .orElseThrow().id() + "\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/projects")
                            .with(JwtTestHelper.subject(bobSubject, tenantId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }
}

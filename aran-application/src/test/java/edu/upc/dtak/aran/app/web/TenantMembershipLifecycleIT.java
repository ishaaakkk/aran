package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenRepository;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The member lifecycle an owner drives from the admin area, over HTTP against real Postgres — the
 * paths the audit found broken (J7-F2, J7-F8, J7-F1, J1-F4) and the feed scoping ruled in UI-OD11.
 *
 * <p>The identity provider is {@code BaseIntegrationTest}'s stub, which remembers the accounts it
 * created by email as Keycloak does; the real Keycloak half of reinstatement is
 * {@code KeycloakAdminAdapterIT}.
 */
@DisplayName("Tenant membership lifecycle — remove, reinstate, revoke, audit, feed")
class TenantMembershipLifecycleIT extends BaseIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final TenantContext CTX = new TenantContext(TENANT.toString());

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired ApiTokenService tokens;
    @Autowired ApiTokenRepository tokenStore;
    @Autowired IdentityProviderPort identityProvider;

    private String email;

    /**
     * Nothing rolls back here, and the seeded tenant's FREE plan allows three projects: what this
     * class leaves behind is what the next class to create a project in that tenant runs into
     * (DatasetBootstrapIntegrationIT hit "maxProjects 3 / 3" once the Tier A merge changed the
     * class order). Leave the tenant as this class found it: without projects.
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
    }

    @BeforeEach
    void setUp() {
        // The tenant is shared with other ITs and nothing rolls back; start from alice alone, as
        // ProjectControllerIT does, so FREE's limits and earlier rows cannot decide the outcome.
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM membership WHERE tenant_id = ? AND user_id != ?",
                TENANT, UUID.fromString(JwtTestHelper.ALICE_SUBJECT));
        email = "marta-" + UUID.randomUUID() + "@example.org";
    }

    private String createMember(int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/tenant/members")
                        .with(JwtTestHelper.alice(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Marta\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private UUID createProject(String name) throws Exception {
        String body = mockMvc.perform(post("/api/projects")
                        .with(JwtTestHelper.alice(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void addToProject(UUID projectId, UUID userId) throws Exception {
        mockMvc.perform(post("/api/projects/{id}/members", projectId)
                        .with(JwtTestHelper.alice(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\"}"))
                .andExpect(status().isCreated());
    }

    private List<String> activityActions() throws Exception {
        String body = mockMvc.perform(get("/api/tenant/admin/activity?limit=100")
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].action");
    }

    @Test
    @DisplayName("J7-F2 / UI-OD9: remove then re-add the same email reinstates — never a 500")
    void removeThenReAddReinstates() throws Exception {
        String created = createMember(201);
        UUID martaId = UUID.fromString(JsonPath.read(created, "$.member.userId"));
        assertThat((String) JsonPath.read(created, "$.temporaryPassword")).isNotBlank();
        UUID projectId = createProject("papers");
        addToProject(projectId, martaId);
        ApiTokenService.IssuedToken token =
                tokens.create(CTX, martaId, "notebook", ApiTokenScope.READ, null);

        mockMvc.perform(delete("/api/tenant/members/{id}", martaId)
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokensRevoked").value(1))
                .andExpect(jsonPath("$.projectAccessRemoved").value(1))
                .andExpect(jsonPath("$.projectsHandedOver.length()").value(0));

        ApiToken stored = tokenStore.findById(CTX, token.token().id()).orElseThrow();
        assertThat(stored.revokedAt()).as("removal revokes the token, not merely strands it")
                .isNotNull();

        String again = createMember(201);
        assertThat((Boolean) JsonPath.read(again, "$.reinstated")).isTrue();
        assertThat((String) JsonPath.read(again, "$.temporaryPassword"))
                .as("a new credential, never the one from before the removal").isNotBlank();
        assertThat((String) JsonPath.read(again, "$.member.userId")).isEqualTo(martaId.toString());

        // Reinstated without the project access the removal dropped.
        mockMvc.perform(get("/api/projects/{id}/members", projectId)
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        assertThat(activityActions())
                .contains("tenant.member.added", "tenant.member.removed",
                        "tenant.member.reinstated", "project.member.added");
    }

    @Test
    @DisplayName("UI-OD9 review: removing the only member of a project hands it to the owner")
    void soleMemberProjectIsHandedOver() throws Exception {
        UUID martaId = UUID.fromString(JsonPath.read(createMember(201), "$.member.userId"));
        User marta = users.findById(martaId).orElseThrow();
        String body = mockMvc.perform(post("/api/projects")
                        .with(JwtTestHelper.subject(marta.subject(), TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sales\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID sales = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(delete("/api/tenant/members/{id}", martaId)
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectsHandedOver[0].id").value(sales.toString()))
                .andExpect(jsonPath("$.projectsHandedOver[0].name").value("Sales"));

        // Still reachable: Alice now sees it, so it can be shared on or cleaned up.
        String projects = mockMvc.perform(get("/api/projects").with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(projects, "$[*].id");
        assertThat(ids).contains(sales.toString());
    }

    @Test
    @DisplayName("UI-OD9 review: an account another organization removed is a 409 here, not re-enabled")
    void accountRemovedElsewhereIsAConflict() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, slug, name, created_at) VALUES (?, ?, ?, ?)",
                otherTenant, "b-" + otherTenant.toString().substring(0, 8), "B",
                Timestamp.from(Instant.now()));
        String subject = identityProvider.createUser(email, email, "Marta");
        User marta = users.save(new User(UUID.randomUUID(), subject, email, "Marta",
                Instant.now()));
        // Tenant B's record of removing her — the only kind of record there is, and not ours.
        jdbc.update("""
                INSERT INTO audit_event (tenant_id, action, target_type, target_id, metadata)
                VALUES (?, 'tenant.member.removed', 'tenant', ?,
                        jsonb_build_object('removedUserId', ?::text))""",
                otherTenant, otherTenant, marta.id().toString());

        mockMvc.perform(post("/api/tenant/members")
                        .with(JwtTestHelper.alice(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Marta\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers
                        .containsString("was not removed from this organization")));

        Integer memberships = jdbc.queryForObject(
                "SELECT count(*) FROM membership WHERE user_id = ?", Integer.class, marta.id());
        assertThat(memberships).isZero();
    }

    @Test
    @DisplayName("J7-F2: adding someone already in the organization is a 409 that says so")
    void alreadyAMemberIsAConflict() throws Exception {
        createMember(201);

        mockMvc.perform(post("/api/tenant/members")
                        .with(JwtTestHelper.alice(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Marta\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers
                        .containsString("already a member")));
    }

    @Test
    @DisplayName("J7-F8: an owner revokes one member's token; the list and the trail both show it")
    void ownerRevokesOneToken() throws Exception {
        UUID martaId = UUID.fromString(JsonPath.read(createMember(201), "$.member.userId"));
        ApiTokenService.IssuedToken leaked =
                tokens.create(CTX, martaId, "public-notebook", ApiTokenScope.READ, null);

        mockMvc.perform(delete("/api/tenant/admin/tokens/{id}", leaked.token().id())
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isNoContent());

        String listed = mockMvc.perform(get("/api/tenant/admin/tokens")
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> statuses = JsonPath.read(listed,
                "$[?(@.id == '" + leaked.token().id() + "')].status");
        assertThat(statuses).containsExactly("revoked");
        assertThat(tokens.authenticate(leaked.secret())).isEmpty();
        assertThat(activityActions()).contains("token.revoked");
    }

    @Test
    @DisplayName("J7-F8: a member cannot use the owner's revoke door")
    void memberCannotRevokeAsOwner() throws Exception {
        UUID martaId = UUID.fromString(JsonPath.read(createMember(201), "$.member.userId"));
        User marta = users.findById(martaId).orElseThrow();
        ApiTokenService.IssuedToken alices =
                tokens.create(CTX, aliceId(), "alice-cli-" + UUID.randomUUID(),
                        ApiTokenScope.READ, null);

        mockMvc.perform(delete("/api/tenant/admin/tokens/{id}", alices.token().id())
                        .with(JwtTestHelper.subject(marta.subject(), TENANT)))
                .andExpect(status().isForbidden());
        assertThat(tokens.authenticate(alices.secret())).isPresent();
    }

    private UUID aliceId() {
        return users.findBySubject(JwtTestHelper.ALICE_SUBJECT).orElseThrow().id();
    }

    @Test
    @DisplayName("J1-F4: an owner removes a project member, who then no longer sees the project")
    void removeProjectMember() throws Exception {
        UUID martaId = UUID.fromString(JsonPath.read(createMember(201), "$.member.userId"));
        User marta = users.findById(martaId).orElseThrow();
        UUID projectId = createProject("papers");
        addToProject(projectId, martaId);

        mockMvc.perform(delete("/api/projects/{id}/members/{userId}", projectId, martaId)
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/projects").with(JwtTestHelper.subject(marta.subject(), TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(activityActions()).contains("project.member.removed");
    }

    @Test
    @DisplayName("J1-F4: the project's last member cannot be removed")
    void lastProjectMemberStays() throws Exception {
        UUID projectId = createProject("solo");

        mockMvc.perform(delete("/api/projects/{id}/members/{userId}", projectId, aliceId())
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("UI-OD11: a member's dashboard feed holds only lines of projects they can see")
    void memberFeedIsScoped() throws Exception {
        UUID martaId = UUID.fromString(JsonPath.read(createMember(201), "$.member.userId"));
        User marta = users.findById(martaId).orElseThrow();
        UUID visible = createProject("visible");
        UUID hidden = createProject("hidden");
        addToProject(visible, martaId);

        String summary = mockMvc.perform(get("/api/dashboard/summary")
                        .with(JwtTestHelper.subject(marta.subject(), TENANT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> targets = JsonPath.read(summary, "$.recentAudit[*].target");
        List<String> actions = JsonPath.read(summary, "$.recentAudit[*].action");

        assertThat(targets).isNotEmpty().allMatch(t -> t.equals("project:" + visible));
        assertThat(targets).noneMatch(t -> t.contains(hidden.toString()));
        assertThat(actions).noneMatch(a -> a.startsWith("tenant."));

        // The owner's feed is the tenant's: the member lines are there.
        String ownerSummary = mockMvc.perform(get("/api/dashboard/summary")
                        .with(JwtTestHelper.alice(TENANT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ownerActions = JsonPath.read(ownerSummary, "$.recentAudit[*].action");
        assertThat(ownerActions).contains("tenant.member.added");
    }
}

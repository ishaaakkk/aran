package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.sql.Driver;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UI audit J2-F1, against a real password-protected PostgreSQL: a stored
 * repository tests green through the route the repositories list now uses, while the GET body
 * posted back to the body-only route — the list's old path — is refused instead of dialing the
 * {@code ********} mask. J2-F19: the list reads the recorded check and dials nothing.
 *
 * <p>The source is this suite's own database (its coordinates come from the Spring environment, so
 * both container modes work): it has a real password, which is all the finding is about.
 * {@link ClasspathDriverConfig} hands back the driver already on the test classpath instead of the
 * pinned Maven Central download, as {@code SchemaQualifiedJdbcPreviewIT} does.
 */
@Import(RepositoryConnectionCheckIT.ClasspathDriverConfig.class)
@Transactional
@DisplayName("Stored-repository connection tests (J2-F1, J2-F19)")
class RepositoryConnectionCheckIT extends BaseIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TestConfiguration
    static class ClasspathDriverConfig {
        @Bean
        @Primary
        DriverDownloader classpathDriverDownloader(@Value("${spring.datasource.url}") String url) {
            return new DriverDownloader() {
                @Override public Driver loadDriver(String engine) throws Exception {
                    return DriverManager.getDriver(url);
                }
                @Override public void unloadDriver(String engine) {
                    // Nothing to release: no sandboxed classloader here.
                }
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired DataRepositoryRepository repositoryStore;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @Value("${spring.datasource.url}") String datasourceUrl;
    @Value("${spring.datasource.username}") String datasourceUser;
    @Value("${spring.datasource.password}") String datasourcePassword;

    private UUID repoId;
    private URI source;

    @BeforeEach
    void setUp() {
        User alice = userRepository.findBySubject(JwtTestHelper.ALICE_SUBJECT)
                .orElseGet(() -> userRepository.save(new User(UUID.randomUUID(),
                        JwtTestHelper.ALICE_SUBJECT, JwtTestHelper.ALICE_EMAIL,
                        JwtTestHelper.ALICE_NAME, Instant.now())));
        if (tenantRepository.findMembership(alice.id(), TENANT_ID).isEmpty()) {
            tenantRepository.saveMembership(new Membership(
                    UUID.randomUUID(), alice.id(), TENANT_ID, TenantRole.OWNER, Instant.now()));
        }
        TenantContext tenant = new TenantContext(TENANT_ID.toString());
        if (projectRepository.findById(tenant, PROJECT_ID).isEmpty()) {
            projectRepository.save(tenant, new Project(PROJECT_ID, TENANT_ID,
                    "Connection check project", "", alice.id(), Instant.now()));
            projectRepository.addMember(tenant,
                    new ProjectMember(UUID.randomUUID(), PROJECT_ID, alice.id(), Instant.now()));
        }

        // jdbc:postgresql://host:port/db?… — the part after "jdbc:" parses as a URI.
        source = URI.create(datasourceUrl.substring("jdbc:".length()));
        repoId = UUID.randomUUID();
        repositoryStore.save(tenant, new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "password-protected-pg", "", "POSTGRESQL", source.getHost(), source.getPort(),
                source.getPath().substring(1), datasourceUser, datasourcePassword, "public", false));
        entityManager.flush();
    }

    private String relationalBody(String host, String password) {
        return """
                {"type":"RELATIONAL_DB","name":"password-protected-pg","description":"",
                 "engine":"POSTGRESQL","host":"%s","port":%d,"database":"%s",
                 "username":"%s","password":"%s","schema":"public","federated":false}
                """.formatted(host, source.getPort(), source.getPath().substring(1), datasourceUser, password);
    }

    @Test
    @DisplayName("the stored repository tests green, and the list then shows that check without dialing")
    void storedRepositoryTestsGreenAndIsRecorded() throws Exception {
        mockMvc.perform(get("/api/projects/{p}/repositories", PROJECT_ID).with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')].password".formatted(repoId)).value("********"))
                .andExpect(jsonPath("$[?(@.id=='%s')].lastConnectionCheck".formatted(repoId))
                        .value(org.hamcrest.Matchers.contains(nullValue())));

        mockMvc.perform(post("/api/projects/{p}/repositories/{r}/test-connection", PROJECT_ID, repoId)
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(containsString("Connected")))
                .andExpect(jsonPath("$.testedAt").value(notNullValue()));

        mockMvc.perform(get("/api/projects/{p}/repositories", PROJECT_ID).with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')].lastConnectionCheck.success".formatted(repoId))
                        .value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    @DisplayName("the GET body posted back to the body-only route is refused, not dialed with the mask")
    void maskedBodyIsRefused() throws Exception {
        mockMvc.perform(post("/api/projects/{p}/repositories/test-connection", PROJECT_ID)
                        .with(JwtTestHelper.alice(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(relationalBody(source.getHost(), "********")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Password not provided"));
    }

    @Test
    @DisplayName("the edit dialog's settings are tested with the stored password behind the mask")
    void editsKeepTheStoredPassword() throws Exception {
        mockMvc.perform(post("/api/projects/{p}/repositories/{r}/test-connection", PROJECT_ID, repoId)
                        .with(JwtTestHelper.alice(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(relationalBody(source.getHost(), "********")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("a masked password aimed at a new host is a 400, not a probe (F-CRED-5)")
    void maskedPasswordCannotBeRedirected() throws Exception {
        mockMvc.perform(post("/api/projects/{p}/repositories/{r}/test-connection", PROJECT_ID, repoId)
                        .with(JwtTestHelper.alice(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(relationalBody("attacker.invalid", "********")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Re-enter the credential")));
    }

    @Test
    @DisplayName("a save forgets the last check, and deleting the repository deletes it (V46 cascade)")
    void saveForgetsAndDeleteCascades() throws Exception {
        mockMvc.perform(post("/api/projects/{p}/repositories/{r}/test-connection", PROJECT_ID, repoId)
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk());
        entityManager.flush();
        assertEquals(1, checkRows());

        mockMvc.perform(put("/api/projects/{p}/repositories/{r}", PROJECT_ID, repoId)
                        .with(JwtTestHelper.alice(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(relationalBody(source.getHost(), "********")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastConnectionCheck").value(nullValue()));
        entityManager.flush();
        assertEquals(0, checkRows());

        mockMvc.perform(post("/api/projects/{p}/repositories/{r}/test-connection", PROJECT_ID, repoId)
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk());
        entityManager.flush();
        mockMvc.perform(delete("/api/projects/{p}/repositories/{r}", PROJECT_ID, repoId)
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isNoContent());
        entityManager.flush();
        assertEquals(0, checkRows());
    }

    @Test
    @DisplayName("a check that began before the repository's last save is not shown — a test racing a save")
    void aCheckOlderThanTheLastSaveIsNotShown() throws Exception {
        mockMvc.perform(post("/api/projects/{p}/repositories/{r}/test-connection", PROJECT_ID, repoId)
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk());
        entityManager.flush();

        // A save that lands after the test read its configuration — here straight through the
        // port, so the service's own delete of the check does not hide what the query does.
        TenantContext tenant = new TenantContext(TENANT_ID.toString());
        repositoryStore.save(tenant, new RelationalDatabaseRepository(repoId, PROJECT_ID, TENANT_ID,
                "password-protected-pg", "", "POSTGRESQL", "moved.invalid", source.getPort(),
                source.getPath().substring(1), datasourceUser, datasourcePassword, "public", false));
        entityManager.flush();
        assertEquals(1, checkRows());

        mockMvc.perform(get("/api/projects/{p}/repositories", PROJECT_ID).with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')].lastConnectionCheck".formatted(repoId))
                        .value(org.hamcrest.Matchers.contains(nullValue())));
    }

    @Test
    @DisplayName("a tenant member outside the project cannot dial its stored credentials: 404")
    void nonMemberCannotTestAStoredRepository() throws Exception {
        String bobSubject = "bob-" + UUID.randomUUID();
        User bob = userRepository.save(new User(UUID.randomUUID(), bobSubject, bobSubject + "@example.com",
                "Bob Member", Instant.now()));
        tenantRepository.saveMembership(new Membership(
                UUID.randomUUID(), bob.id(), TENANT_ID, TenantRole.MEMBER, Instant.now()));

        mockMvc.perform(post("/api/projects/{p}/repositories/{r}/test-connection", PROJECT_ID, repoId)
                        .with(JwtTestHelper.subject(bobSubject, TENANT_ID)))
                .andExpect(status().isNotFound());
        entityManager.flush();
        assertEquals(0, checkRows());
    }

    @Test
    @DisplayName("another project's repository cannot be tested through this project's route")
    void anotherProjectsRepositoryIsNotTestable() throws Exception {
        TenantContext tenant = new TenantContext(TENANT_ID.toString());
        UUID otherProject = UUID.randomUUID();
        User alice = userRepository.findBySubject(JwtTestHelper.ALICE_SUBJECT).orElseThrow();
        projectRepository.save(tenant, new Project(otherProject, TENANT_ID, "Other project " + otherProject,
                "", alice.id(), Instant.now()));
        projectRepository.addMember(tenant,
                new ProjectMember(UUID.randomUUID(), otherProject, alice.id(), Instant.now()));
        UUID otherRepo = UUID.randomUUID();
        repositoryStore.save(tenant, new RelationalDatabaseRepository(otherRepo, otherProject, TENANT_ID,
                "other-pg", "", "POSTGRESQL", source.getHost(), source.getPort(),
                source.getPath().substring(1), datasourceUser, datasourcePassword, "public", false));
        entityManager.flush();

        mockMvc.perform(post("/api/projects/{p}/repositories/{r}/test-connection", PROJECT_ID, otherRepo)
                        .with(JwtTestHelper.alice(TENANT_ID)))
                .andExpect(status().isForbidden());
        entityManager.flush();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repository_connection_check WHERE repository_id = ?", Integer.class, otherRepo);
        assertEquals(0, rows == null ? 0 : rows);
    }

    private int checkRows() {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repository_connection_check WHERE repository_id = ?", Integer.class, repoId);
        return rows == null ? 0 : rows;
    }
}

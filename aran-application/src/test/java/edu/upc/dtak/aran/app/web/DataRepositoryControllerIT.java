package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.*;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("DataRepositoryController")
@Transactional
class DataRepositoryControllerIT extends BaseIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    DataRepositoryRepository dataRepositoryStore;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private User alice;

    @BeforeEach
    void setUp() {
        alice = userRepository.findBySubject(JwtTestHelper.ALICE_SUBJECT)
                .orElseGet(() -> userRepository.save(new User(
                        UUID.randomUUID(),
                        JwtTestHelper.ALICE_SUBJECT,
                        JwtTestHelper.ALICE_EMAIL,
                        JwtTestHelper.ALICE_NAME,
                        Instant.now()
                )));

        if (tenantRepository.findMembership(alice.id(), TENANT_ID).isEmpty()) {
            tenantRepository.saveMembership(new Membership(
                    UUID.randomUUID(),
                    alice.id(),
                    TENANT_ID,
                    TenantRole.OWNER,
                    Instant.now()
            ));
        }

        TenantContext tenantCtx = new TenantContext(TENANT_ID.toString());

        if (projectRepository.findById(tenantCtx, PROJECT_ID).isEmpty()) {
            Project project = new Project(
                    PROJECT_ID,
                    TENANT_ID,
                    "Repository Test Project",
                    "For repository testing",
                    alice.id(),
                    Instant.now()
            );
            projectRepository.save(tenantCtx, project);
            projectRepository.addMember(tenantCtx, new ProjectMember(
                    UUID.randomUUID(),
                    PROJECT_ID,
                    alice.id(),
                    Instant.now()
            ));
        }
    }

    @Nested
    @DisplayName("GET /api/projects/{projectId}/repositories")
    class ListRepositories {

        @Test
        @DisplayName("returns empty list when no repositories registered")
        void returns_empty_list() throws Exception {
            mockMvc.perform(get("/api/projects/{projectId}/repositories", PROJECT_ID)
                            .with(JwtTestHelper.alice(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("returns repositories successfully")
        void returns_repositories() throws Exception {
            TenantContext tenantCtx = new TenantContext(TENANT_ID.toString());
            UUID repoId = UUID.randomUUID();
            RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                    repoId, PROJECT_ID, TENANT_ID, "Test RDB", "Desc",
                    "POSTGRESQL", "localhost", 5432, "test", "user", "pass", "public", false
            );
            dataRepositoryStore.save(tenantCtx, repo);

            mockMvc.perform(get("/api/projects/{projectId}/repositories", PROJECT_ID)
                            .with(JwtTestHelper.alice(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(repoId.toString()))
                    .andExpect(jsonPath("$[0].name").value("Test RDB"))
                    .andExpect(jsonPath("$[0].type").value("RELATIONAL_DB"))
                    .andExpect(jsonPath("$[0].engine").value("POSTGRESQL"));

            // Cleanup
            dataRepositoryStore.delete(tenantCtx, repoId);
        }

        @Test
        @DisplayName("verifies that repository credentials are encrypted at rest in the database")
        void credentials_are_encrypted_at_rest() throws Exception {
            TenantContext tenantCtx = new TenantContext(TENANT_ID.toString());
            UUID repoId = UUID.randomUUID();
            RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                    repoId, PROJECT_ID, TENANT_ID, "Secure DB", "Desc",
                    "POSTGRESQL", "localhost", 5432, "test", "user", "my-super-secret-password-123", "public", false
            );
            dataRepositoryStore.save(tenantCtx, repo);
            entityManager.flush();

            // 1. Query the database directly via SQL to verify the password is encrypted (is not plaintext, and does not contain "my-super-secret-password-123")
            String rawPassword = jdbcTemplate.queryForObject(
                    "SELECT password FROM data_repository WHERE id = ?",
                    String.class,
                    repoId
            );
            assertNotNull(rawPassword);
            assertFalse(rawPassword.contains("my-super-secret-password-123"));

            // 2. Query via the repository store (JPA) to verify transparent decryption works
            Optional<DataRepository> fetchedOpt = dataRepositoryStore.findById(tenantCtx, repoId);
            assertTrue(fetchedOpt.isPresent());
            RelationalDatabaseRepository fetched = (RelationalDatabaseRepository) fetchedOpt.get();
            assertEquals("my-super-secret-password-123", fetched.password());

            // Cleanup
            dataRepositoryStore.delete(tenantCtx, repoId);
        }
    }

    @Nested
    @DisplayName("Write Isolation Security")
    class WriteIsolationSecurity {
        @Test
        @DisplayName("saving repository with mismatched tenantId throws IllegalArgumentException")
        void save_mismatched_tenantId_throws_IllegalArgumentException() {
            TenantContext tenantCtx = new TenantContext(TENANT_ID.toString());
            UUID repoId = UUID.randomUUID();
            UUID mismatchedTenantId = UUID.randomUUID(); // Forged tenant ID
            RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                    repoId, PROJECT_ID, mismatchedTenantId, "Forged DB", "Desc",
                    "POSTGRESQL", "localhost", 5432, "test", "user", "pass", "public", false
            );

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
                dataRepositoryStore.save(tenantCtx, repo);
            }, "Should reject forged tenant ID");
        }
    }

    @Nested
    @DisplayName("POST /api/projects/{projectId}/repositories")
    class CreateRepository {

        @Test
        @DisplayName("creates a relational database repository successfully")
        void creates_relational_repository() throws Exception {
            mockMvc.perform(post("/api/projects/{projectId}/repositories", PROJECT_ID)
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "type": "RELATIONAL_DB",
                                      "name": "Production Database",
                                      "description": "My primary RDB",
                                      "engine": "POSTGRESQL",
                                      "host": "localhost",
                                      "port": 5432,
                                      "database": "aran_prod",
                                      "username": "sa",
                                      "password": "secret",
                                      "schema": "public"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Production Database"))
                    .andExpect(jsonPath("$.type").value("RELATIONAL_DB"))
                    .andExpect(jsonPath("$.engine").value("POSTGRESQL"))
                    .andExpect(jsonPath("$.host").value("localhost"))
                    .andExpect(jsonPath("$.port").value(5432))
                    .andExpect(jsonPath("$.database").value("aran_prod"));
        }

        @Test
        @DisplayName("creates a file system repository from a name alone — the kind is upload-only (D1)")
        void creates_file_system_repository() throws Exception {
            mockMvc.perform(post("/api/projects/{projectId}/repositories", PROJECT_ID)
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "type": "FILE_SYSTEM",
                                      "name": "Local Storage",
                                      "description": "Uploaded files"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Local Storage"))
                    .andExpect(jsonPath("$.type").value("FILE_SYSTEM"))
                    .andExpect(jsonPath("$.rootPath").doesNotExist());
        }

        @Test
        @DisplayName("a root path in the body is ignored and opens nothing: scan lists no file, preview reads none (D1)")
        void file_system_root_path_opens_nothing() throws Exception {
            // This body is SOURCE_REGISTRATION-2's exploit: register /etc, scan it, preview a file
            // under it. T3.2 answered with an allowlist; D1 removed the read itself, so the old
            // field is accepted — pre-D1 clients keep working — and leads nowhere.
            String created = mockMvc.perform(post("/api/projects/{projectId}/repositories", PROJECT_ID)
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "type": "FILE_SYSTEM",
                                      "name": "Server Config",
                                      "description": "not mine to read",
                                      "rootPath": "/etc"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.rootPath").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            String repositoryId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

            mockMvc.perform(get("/api/projects/{projectId}/repositories/{repositoryId}/scan",
                            PROJECT_ID, repositoryId)
                            .with(JwtTestHelper.alice(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
            for (String name : new String[] {"passwd", "/etc/passwd", "../etc/passwd"}) {
                mockMvc.perform(get("/api/projects/{projectId}/repositories/{repositoryId}/preview",
                                PROJECT_ID, repositoryId)
                                .param("datasetName", name)
                                .with(JwtTestHelper.alice(TENANT_ID)))
                        .andExpect(status().is(org.hamcrest.Matchers.greaterThanOrEqualTo(400)))
                        .andExpect(jsonPath("$.headers").doesNotExist())
                        .andExpect(jsonPath("$.detail").value(
                                org.hamcrest.Matchers.containsString("not available")));
            }
        }

        @Test
        @DisplayName("a deleted repository kind is refused on the wire and nothing is stored (D1)")
        void deleted_kinds_are_refused() throws Exception {
            long before = jdbcTemplate.queryForObject("SELECT count(*) FROM data_repository", Long.class);
            // Each body is one the kind's own DTO accepted with a 201 before the deletion (every
            // field its bean validation required, unresolvable hosts so the create-time address
            // screen stays lenient). A skeletal body would be refused by bean validation whether
            // or not the kind exists, and would pin nothing; the title separates the two causes.
            for (String body : new String[] {
                    """
                    {"type": "NOSQL_DOCUMENT", "name": "gone-nosql", "engine": "MONGODB",
                     "connectionString": "mongodb://mongo.internal:27017/orders",
                     "databaseName": "orders", "collectionName": "lines"}
                    """,
                    """
                    {"type": "KEY_VALUE", "name": "gone-kv", "engine": "REDIS",
                     "host": "cache.internal", "port": 6379}
                    """,
                    """
                    {"type": "WEB_API", "name": "gone-api",
                     "baseUrl": "https://api.internal/v1", "authType": "NONE"}
                    """}) {
                mockMvc.perform(post("/api/projects/{projectId}/repositories", PROJECT_ID)
                                .with(JwtTestHelper.alice(TENANT_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.title").value("Malformed request body"));
            }
            // The class is @Transactional: an unflushed JPA insert is invisible to this count.
            entityManager.flush();
            assertEquals(before,
                    jdbcTemplate.queryForObject("SELECT count(*) FROM data_repository", Long.class));
        }
    }

    @Nested
    @DisplayName("DELETE /api/projects/{projectId}/repositories/{repositoryId}")
    class DeleteRepository {

        @Test
        @DisplayName("removes the repository")
        void deletes_repository() throws Exception {
            TenantContext tenantCtx = new TenantContext(TENANT_ID.toString());
            UUID repoId = UUID.randomUUID();
            RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                    repoId, PROJECT_ID, TENANT_ID, "Delete Me", "Desc",
                    "POSTGRESQL", "localhost", 5432, "test", "user", "pass", "public", false
            );
            dataRepositoryStore.save(tenantCtx, repo);

            mockMvc.perform(delete("/api/projects/{projectId}/repositories/{repositoryId}", PROJECT_ID, repoId)
                            .with(JwtTestHelper.alice(TENANT_ID)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/projects/{projectId}/repositories", PROJECT_ID)
                            .with(JwtTestHelper.alice(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("PUT /api/projects/{projectId}/repositories/{repositoryId}")
    class UpdateRepository {

        @Test
        @DisplayName("updates repository and preserves password if masked as asterisks")
        void updates_repository_preserves_password() throws Exception {
            TenantContext tenantCtx = new TenantContext(TENANT_ID.toString());
            UUID repoId = UUID.randomUUID();
            RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                    repoId, PROJECT_ID, TENANT_ID, "Orig DB", "Desc",
                    "POSTGRESQL", "localhost", 5432, "test", "user", "secret-password", "public", false
            );
            dataRepositoryStore.save(tenantCtx, repo);
            entityManager.flush();

            // 1. Perform GET to verify password is returned masked as "********"
            mockMvc.perform(get("/api/projects/{projectId}/repositories", PROJECT_ID)
                            .with(JwtTestHelper.alice(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].password").value("********"));

            // 2. Perform PUT with masked password to update name and description
            mockMvc.perform(put("/api/projects/{projectId}/repositories/{repositoryId}", PROJECT_ID, repoId)
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "type": "RELATIONAL_DB",
                                      "name": "Updated DB",
                                      "description": "Updated Desc",
                                      "engine": "POSTGRESQL",
                                      "host": "localhost",
                                      "port": 5432,
                                      "database": "test",
                                      "username": "user",
                                      "password": "********",
                                      "schema": "public"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated DB"))
                    .andExpect(jsonPath("$.description").value("Updated Desc"))
                    .andExpect(jsonPath("$.password").value("********"));

            // 3. Verify in database that actual password was preserved (is still "secret-password")
            Optional<DataRepository> fetchedOpt = dataRepositoryStore.findById(tenantCtx, repoId);
            assertTrue(fetchedOpt.isPresent());
            RelationalDatabaseRepository fetched = (RelationalDatabaseRepository) fetchedOpt.get();
            assertEquals("secret-password", fetched.password());

            // Cleanup
            dataRepositoryStore.delete(tenantCtx, repoId);
        }
    }

    @Nested
    @DisplayName("POST /api/projects/{projectId}/repositories/test-connection")
    class TestConnection {

        @Test
        @DisplayName("a file system connection test probes no path, whichever one the body names (D1)")
        void tests_fs_connection_probes_nothing() throws Exception {
            // An absent directory used to fail with "Directory not found" and /etc with "Root path
            // is not permitted" — each answer a fact about the server's filesystem. Both now get
            // the same answer, which says nothing about either path.
            for (String rootPath : new String[] {"/tmp/aran-absent-path-for-sure-xyz", "/etc"}) {
                mockMvc.perform(post("/api/projects/{projectId}/repositories/test-connection", PROJECT_ID)
                                .with(JwtTestHelper.alice(TENANT_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "type": "FILE_SYSTEM",
                                          "name": "Temp Space",
                                          "rootPath": "%s"
                                        }
                                        """.formatted(rootPath)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.message").value(
                                org.hamcrest.Matchers.containsString("reads no server directory")));
            }
        }
    }
}

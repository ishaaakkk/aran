package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J2-F3 over real HTTP: the dataset list says how each dataset is read, so the UI stops telling a
 * live Postgres table's user that its data "was uploaded". Nothing connects to the database —
 * access is decided from the repository's configuration and the dataset's own copy.
 */
@DisplayName("GET …/datasets — accessMode")
class DatasetAccessModeIT extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;

    private UUID tenantId;
    private String subject;
    private UUID projectId;
    private TenantContext tenant;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "access-" + suffix, "Access " + suffix, Instant.now()));
        tenant = new TenantContext(tenantId.toString());
        subject = "owner-" + suffix;
        User owner = users.save(new User(UUID.randomUUID(), subject, subject + "@example.com", "Owner",
                Instant.now()));
        tenants.saveMembership(new Membership(UUID.randomUUID(), owner.id(), tenantId, TenantRole.OWNER,
                Instant.now()));
        Project project = projects.save(tenant, new Project(
                UUID.randomUUID(), tenantId, "access-" + suffix, "", owner.id(), Instant.now()));
        projectId = project.id();
        projects.addMember(tenant, new ProjectMember(UUID.randomUUID(), projectId, owner.id(), Instant.now()));
    }

    @Test
    @DisplayName("an upload reads SNAPSHOT")
    void uploadIsSnapshot() throws Exception {
        DataRepository files = repositories.save(tenant, new FileSystemRepository(
                UUID.randomUUID(), projectId, tenantId, "files", ""));
        datasets.save(tenant, new Dataset(UUID.randomUUID(), files.id(), "acm.csv", "", "snap-1"));

        mockMvc.perform(get("/api/projects/{p}/repositories/{r}/datasets", projectId, files.id())
                        .with(JwtTestHelper.subject(subject, tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accessMode").value("SNAPSHOT"));
    }

    @Test
    @DisplayName("a table on a federated Postgres repository reads LIVE, and LIVE_WITH_COPY once imported")
    void federatedTableIsLive() throws Exception {
        DataRepository pg = repositories.save(tenant, new RelationalDatabaseRepository(
                UUID.randomUUID(), projectId, tenantId, "prod", "", "POSTGRESQL", "db.example", 5432,
                "shop", "reader", "secret", "public", true));
        datasets.save(tenant, new Dataset(UUID.randomUUID(), pg.id(), "venues", "", null));
        datasets.save(tenant, new Dataset(UUID.randomUUID(), pg.id(), "orders", "", "snap-2"));

        mockMvc.perform(get("/api/projects/{p}/repositories/{r}/datasets", projectId, pg.id())
                        .with(JwtTestHelper.subject(subject, tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'venues')].accessMode").value("LIVE"))
                .andExpect(jsonPath("$[?(@.name == 'orders')].accessMode").value("LIVE_WITH_COPY"));
    }
}

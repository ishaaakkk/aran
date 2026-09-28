package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J3-F1 against real Postgres: {@code GET …/datasets/{d}/profiles} reads the profiles bootstrap,
 * compile and harvest already persisted, grouped by what was profiled, with the planner's own key
 * evidence rule on each group — and is gated exactly like every other dataset route.
 */
@DisplayName("GET …/datasets/{datasetId}/profiles")
class DatasetProfilesControllerIT extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositories;
    @Autowired DatasetRepository datasets;

    private UUID tenantId;
    private String ownerSubject;
    private String outsiderSubject;
    private UUID projectId;
    private UUID repositoryId;
    private UUID otherRepositoryId;
    private UUID datasetId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "profiles-" + suffix, "Profiles " + suffix, Instant.now()));
        TenantContext tenant = new TenantContext(tenantId.toString());

        ownerSubject = "owner-" + suffix;
        User owner = users.save(new User(UUID.randomUUID(), ownerSubject, ownerSubject + "@example.com",
                "Owner", Instant.now()));
        tenants.saveMembership(new Membership(UUID.randomUUID(), owner.id(), tenantId, TenantRole.OWNER,
                Instant.now()));
        // Same tenant, not a member of the project: must be told it does not exist.
        outsiderSubject = "outsider-" + suffix;
        User outsider = users.save(new User(UUID.randomUUID(), outsiderSubject,
                outsiderSubject + "@example.com", "Outsider", Instant.now()));
        tenants.saveMembership(new Membership(UUID.randomUUID(), outsider.id(), tenantId, TenantRole.MEMBER,
                Instant.now()));

        Project project = projects.save(tenant, new Project(
                UUID.randomUUID(), tenantId, "profiles-" + suffix, "", owner.id(), Instant.now()));
        projectId = project.id();
        projects.addMember(tenant, new ProjectMember(UUID.randomUUID(), projectId, owner.id(), Instant.now()));

        DataRepository repo = repositories.save(tenant, new FileSystemRepository(
                UUID.randomUUID(), projectId, tenantId, "files-" + suffix, ""));
        repositoryId = repo.id();
        DataRepository other = repositories.save(tenant, new FileSystemRepository(
                UUID.randomUUID(), projectId, tenantId, "other-" + suffix, ""));
        otherRepositoryId = other.id();

        datasetId = UUID.randomUUID();
        datasets.save(tenant, new Dataset(datasetId, repositoryId, "orders", "", "snap-1", List.of(
                profile("id", 0, ColumnProfile.SUBJECT_SNAPSHOT),
                profile("amount", 1, ColumnProfile.SUBJECT_SNAPSHOT),
                profile("id", 0, ColumnProfile.SUBJECT_HARVESTED))));
    }

    private ColumnProfile profile(String column, int index, String subject) {
        return new ColumnProfile(
                UUID.randomUUID(), datasetId, column, index, "BIGINT",
                12L, 3.1, 1.0, 0.0,
                1.0, 1L, 1L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.08,
                2, 1, 1.5,
                12L, 1, 1, 1.0, 0.0,
                "1", "12",
                false, false,
                List.of("1", "2"), List.of(),
                subject, null);
    }

    @Test
    @DisplayName("answers the stored generations, snapshot first, with key evidence only on freyja's own measurement")
    void answersStoredProfiles() throws Exception {
        mockMvc.perform(get("/api/projects/{p}/repositories/{r}/datasets/{d}/profiles",
                        projectId, repositoryId, datasetId)
                        .with(JwtTestHelper.subject(ownerSubject, tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(datasetId.toString()))
                .andExpect(jsonPath("$.groups", hasSize(2)))
                .andExpect(jsonPath("$.groups[0].subject").value("SNAPSHOT"))
                .andExpect(jsonPath("$.groups[0].keyEvidence").value(true))
                .andExpect(jsonPath("$.groups[0].columns", hasSize(2)))
                .andExpect(jsonPath("$.groups[0].columns[0].columnName").value("id"))
                .andExpect(jsonPath("$.groups[0].columns[0].cardinality").value(12))
                .andExpect(jsonPath("$.groups[0].columns[0].emptyColumn").value(false))
                .andExpect(jsonPath("$.groups[0].columns[0].valueHashSample").doesNotExist())
                .andExpect(jsonPath("$.groups[1].subject").value("HARVESTED"))
                .andExpect(jsonPath("$.groups[1].keyEvidence").value(false));
    }

    @Test
    @DisplayName("a dataset named under a repository it does not belong to is not found")
    void refusesTheWrongRepository() throws Exception {
        mockMvc.perform(get("/api/projects/{p}/repositories/{r}/datasets/{d}/profiles",
                        projectId, otherRepositoryId, datasetId)
                        .with(JwtTestHelper.subject(ownerSubject, tenantId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a tenant member outside the project is told the project does not exist")
    void refusesANonMember() throws Exception {
        mockMvc.perform(get("/api/projects/{p}/repositories/{r}/datasets/{d}/profiles",
                        projectId, repositoryId, datasetId)
                        .with(JwtTestHelper.subject(outsiderSubject, tenantId)))
                .andExpect(status().isNotFound());
    }
}

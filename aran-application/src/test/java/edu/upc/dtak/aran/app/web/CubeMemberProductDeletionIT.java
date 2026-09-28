package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.domain.cube.CubeRepository;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D12: a cube's star product cannot be deleted from under its cube — through the real controller,
 * exception mapping and Postgres-backed cube row (whose member list is JSON the adapter never
 * interprets). Deleting the cube is still how the star goes.
 */
// Same property set as ProjectScopedAuthorizationIT, so the two share one cached context.
@TestPropertySource(properties = "aran.cubes.enabled=true")
@DisplayName("Cube members — a star product is deleted with its cube, not on its own")
class CubeMemberProductDeletionIT extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired LicenseAdminPort licenseAdmin;
    @Autowired DataProductRepository products;
    @Autowired CubeRepository cubes;
    @Autowired GraphVersionPort graphVersions;

    @Test
    @DisplayName("DELETE of a member product is a 400 naming the cube; DELETE of the cube removes it")
    void member_product_is_refused_until_its_cube_is_gone() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "cubes-" + suffix, "Cubes " + suffix, Instant.now()));
        licenseAdmin.issueInitialLicense(tenantId, LicensePlan.PRO);
        String ownerSubject = "cube-owner-" + suffix;
        User owner = users.save(new User(UUID.randomUUID(), ownerSubject,
                ownerSubject + "@example.com", "Owner", Instant.now()));
        tenants.saveMembership(new Membership(
                UUID.randomUUID(), owner.id(), tenantId, TenantRole.OWNER, Instant.now()));
        var asOwner = JwtTestHelper.subject(ownerSubject, tenantId);

        String projectBody = mockMvc.perform(post("/api/projects").with(asOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cubes-" + suffix + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID project = UUID.fromString(com.jayway.jsonpath.JsonPath.read(projectBody, "$.id"));

        TenantContext tenant = new TenantContext(tenantId.toString());
        GraphVersion version = graphVersions.save(tenant, new GraphVersion(
                UUID.randomUUID(), tenantId, 1, "ACTIVE", Instant.now(), null, null, null));
        DataProduct fact = products.save(tenant, DataProduct.draft(
                UUID.randomUUID(), tenantId, project, "sales_fact", "", "SELECT S.a FROM S",
                version.id(), null, Instant.now()));
        UUID cubeId = UUID.randomUUID();
        cubes.save(tenant, new CubeRecord(cubeId, tenantId, project, "sales", null,
                "http://aran/global#Sales", "{\"measures\":[],\"dimensions\":[]}",
                "[\"" + fact.id() + "\"]", version.id(), null, Instant.now()));

        mockMvc.perform(delete("/api/projects/{p}/products/{id}", project, fact.id()).with(asOwner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("cube 'sales'")));
        assertThat(products.findById(tenant, fact.id())).isPresent();

        mockMvc.perform(delete("/api/projects/{p}/cubes/{id}", project, cubeId).with(asOwner))
                .andExpect(status().isNoContent());
        assertThat(cubes.find(tenant, cubeId)).isEmpty();
        assertThat(products.findById(tenant, fact.id())).isEmpty();
    }
}

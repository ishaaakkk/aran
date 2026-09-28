package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.domain.cube.CubeRepository;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the products screen reads about a product it did not compute itself (audit A12), through
 * the real controller and a Postgres that has applied V47:
 *
 * <ul>
 *   <li>UI-OD12 — the version its last build ran under, beside the one it was defined against;</li>
 *   <li>J6-F4 — the table it is served as, normalised by {@code ProductCatalog}, and whether the
 *       Iceberg catalog serves it;</li>
 *   <li>J6-F17 — the cube whose star it belongs to, so delete can be disabled before the click.</li>
 * </ul>
 */
// Same property set as CubeMemberProductDeletionIT / ProjectScopedAuthorizationIT: one cached context.
@TestPropertySource(properties = "aran.cubes.enabled=true")
@DisplayName("Data product provenance — built-against version, table name, Iceberg door, cube membership")
class DataProductProvenanceIT extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired LicenseAdminPort licenseAdmin;
    @Autowired DataProductRepository products;
    @Autowired CubeRepository cubes;
    @Autowired GraphVersionPort graphVersions;

    private record Fixture(TenantContext tenant, UUID tenantId, UUID project, RequestPostProcessor asOwner,
                           GraphVersion v1, GraphVersion v2) {}

    private Fixture fixture() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "prov-" + suffix, "Prov " + suffix, Instant.now()));
        licenseAdmin.issueInitialLicense(tenantId, LicensePlan.PRO);
        String ownerSubject = "prov-owner-" + suffix;
        User owner = users.save(new User(UUID.randomUUID(), ownerSubject,
                ownerSubject + "@example.com", "Owner", Instant.now()));
        tenants.saveMembership(new Membership(
                UUID.randomUUID(), owner.id(), tenantId, TenantRole.OWNER, Instant.now()));
        var asOwner = JwtTestHelper.subject(ownerSubject, tenantId);

        String projectBody = mockMvc.perform(post("/api/projects").with(asOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"prov-" + suffix + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID project = UUID.fromString(com.jayway.jsonpath.JsonPath.read(projectBody, "$.id"));

        TenantContext tenant = new TenantContext(tenantId.toString());
        GraphVersion v1 = graphVersions.save(tenant, new GraphVersion(
                UUID.randomUUID(), tenantId, 1, "HISTORICAL", Instant.now(), null, null, null));
        GraphVersion v2 = graphVersions.save(tenant, new GraphVersion(
                UUID.randomUUID(), tenantId, 2, "ACTIVE", Instant.now(), null, null, null));
        return new Fixture(tenant, tenantId, project, asOwner, v1, v2);
    }

    @Test
    @DisplayName("a product defined against v1 and rebuilt under v2 reads back both, from Postgres and over the API")
    void builtAgainstVersionRoundTripsThroughV47AndTheApi() throws Exception {
        Fixture f = fixture();
        DataProduct built = products.save(f.tenant(), DataProduct.draft(
                        UUID.randomUUID(), f.tenantId(), f.project(), "Papers per year (2024)", "",
                        "SELECT P.a FROM P", f.v1().id(), null, Instant.now())
                .materialized("snap-1", 12L, null, Instant.now(), f.v2().id()));

        assertThat(products.findById(f.tenant(), built.id()).orElseThrow().builtAgainstVersionId())
                .as("V47 persisted the column").isEqualTo(f.v2().id());
        // A later status move (promote → STALE) must not drop it.
        products.save(f.tenant(), built.withStatus(DataProductStatus.STALE));
        assertThat(products.findById(f.tenant(), built.id()).orElseThrow().builtAgainstVersionId())
                .isEqualTo(f.v2().id());

        mockMvc.perform(get("/api/projects/{p}/products/{id}", f.project(), built.id()).with(f.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersionId").value(f.v1().id().toString()))
                .andExpect(jsonPath("$.builtAgainstVersionId").value(f.v2().id().toString()))
                .andExpect(jsonPath("$.tableName").value("Papers_per_year__2024_"))
                .andExpect(jsonPath("$.memberOfCube").value(nullValue()));
    }

    @Test
    @DisplayName("servedOverIceberg is the catalog's own rule: a build with recorded columns yes, one without no")
    void servedOverIcebergFollowsTheCatalogRule() throws Exception {
        Fixture f = fixture();
        String columns = ProductColumn.toJson(List.of(new ProductColumn("year", "INTEGER", Types.INTEGER)));
        DataProduct withColumns = products.save(f.tenant(), DataProduct.draft(
                        UUID.randomUUID(), f.tenantId(), f.project(), "served", "", "SELECT P.a FROM P",
                        f.v2().id(), null, Instant.now())
                .materialized("snap-1", 3L, columns, Instant.now(), f.v2().id()));
        // Built before V29 recorded columns: JDBC still reads its parquet, the Iceberg catalog does not list it.
        DataProduct preV29 = products.save(f.tenant(), DataProduct.draft(
                        UUID.randomUUID(), f.tenantId(), f.project(), "unrecorded", "", "SELECT P.a FROM P",
                        f.v2().id(), null, Instant.now())
                .materialized("snap-2", 3L, null, Instant.now(), f.v2().id()));
        DataProduct draft = products.save(f.tenant(), DataProduct.draft(
                UUID.randomUUID(), f.tenantId(), f.project(), "draft", "", "SELECT P.a FROM P",
                f.v2().id(), null, Instant.now()));

        mockMvc.perform(get("/api/projects/{p}/products", f.project()).with(f.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + withColumns.id() + "')].servedOverIceberg").value(contains(true)))
                .andExpect(jsonPath("$[?(@.id == '" + preV29.id() + "')].servedOverIceberg").value(contains(false)))
                .andExpect(jsonPath("$[?(@.id == '" + draft.id() + "')].servedOverIceberg").value(contains(false)));
        mockMvc.perform(get("/api/projects/{p}/products/{id}", f.project(), withColumns.id()).with(f.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servedOverIceberg").value(true));
    }

    @Test
    @DisplayName("the list names each product's cube, so delete can be disabled before the click")
    void listNamesTheCubeHoldingAStarMember() throws Exception {
        Fixture f = fixture();
        DataProduct fact = products.save(f.tenant(), DataProduct.draft(
                UUID.randomUUID(), f.tenantId(), f.project(), "sales_fact", "", "SELECT S.a FROM S",
                f.v2().id(), null, Instant.now()));
        DataProduct loose = products.save(f.tenant(), DataProduct.draft(
                UUID.randomUUID(), f.tenantId(), f.project(), "loose", "", "SELECT S.a FROM S",
                f.v2().id(), null, Instant.now()));
        cubes.save(f.tenant(), new CubeRecord(UUID.randomUUID(), f.tenantId(), f.project(), "sales", null,
                "http://aran/global#Sales", "{\"measures\":[],\"dimensions\":[]}",
                "[\"" + fact.id() + "\"]", f.v2().id(), null, Instant.now()));

        mockMvc.perform(get("/api/projects/{p}/products", f.project()).with(f.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + fact.id() + "')].memberOfCube").value("sales"))
                .andExpect(jsonPath("$[?(@.id == '" + loose.id() + "')].memberOfCube").value(contains(nullValue())))
                .andExpect(jsonPath("$[?(@.id == '" + fact.id() + "')].tableName").value("sales_fact"))
                .andExpect(jsonPath("$[?(@.id == '" + loose.id() + "')].builtAgainstVersionId").value(contains(nullValue())));
    }
}

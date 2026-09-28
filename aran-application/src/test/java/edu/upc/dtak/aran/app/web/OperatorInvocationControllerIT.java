package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.app.JwtTestHelper;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.transaction.annotation.Transactional;

@DisplayName("OperatorInvocationController")
@Transactional
class OperatorInvocationControllerIT extends BaseIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TenantRepository tenantRepository;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    }

    @Nested
    @DisplayName("GET /api/operators")
    class ListOperators {

        @Test
        @DisplayName("returns list of active operators when authorized")
        void returns_operators_list() throws Exception {
            mockMvc.perform(get("/api/operators")
                            .with(JwtTestHelper.alice(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[*].operatorId", hasItem("bootstrapping")))
                    .andExpect(jsonPath("$[*].operatorId", hasItem("join-discovery")))
                    .andExpect(jsonPath("$[*].operatorId", hasItem("schema-integration")))
                    .andExpect(jsonPath("$[?(@.operatorId=='bootstrapping')].displayName").value("Bootstrapping"))
                    .andExpect(jsonPath("$[?(@.operatorId=='bootstrapping')].operations[*].name", hasItem("bootstrap")));
        }

        @Test
        @DisplayName("fails with 401 when unauthenticated")
        void fails_unauthenticated() throws Exception {
            mockMvc.perform(get("/api/operators"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api/operators/{operatorId}/invoke")
    class InvokeOperator {

        @Test
        @DisplayName("invokes bootstrapping ping successfully")
        void invokes_bootstrapping_ping() throws Exception {
            mockMvc.perform(post("/api/operators/{operatorId}/invoke", "bootstrapping")
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "operation": "ping",
                                      "parameters": {}
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("ok"))
                    .andExpect(jsonPath("$.data.operator").value("bootstrapping"));
        }

        @Test
        @DisplayName("invokes join-discovery ping successfully")
        void invokes_join_discovery_ping() throws Exception {
            mockMvc.perform(post("/api/operators/{operatorId}/invoke", "join-discovery")
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "operation": "ping",
                                      "parameters": {}
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("ok"))
                    .andExpect(jsonPath("$.data.operator").value("join-discovery"));
        }

        @Test
        @DisplayName("returns 422 for unsupported operation")
        void returns_422_unsupported_operation() throws Exception {
            mockMvc.perform(post("/api/operators/{operatorId}/invoke", "bootstrapping")
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "operation": "unknown",
                                      "parameters": {}
                                    }
                                    """))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_OPERATION"));
        }

        @Test
        @DisplayName("returns 422 for unknown operator")
        void returns_422_unknown_operator() throws Exception {
            mockMvc.perform(post("/api/operators/{operatorId}/invoke", "unknown-operator")
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "operation": "ping",
                                      "parameters": {}
                                    }
                                    """))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("OPERATOR_NOT_FOUND"));
        }

        @Test
        @DisplayName("returns 400 when a required manifest parameter is missing")
        void returns_400_missing_required_parameter() throws Exception {
            mockMvc.perform(post("/api/operators/{operatorId}/invoke", "query-engine")
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "operation": "run-oql",
                                      "parameters": {}
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("oql")));
        }

        @Test
        @DisplayName("invokes schema-integration ping successfully")
        void invokes_schema_integration_ping() throws Exception {
            mockMvc.perform(post("/api/operators/{operatorId}/invoke", "schema-integration")
                            .with(JwtTestHelper.alice(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "operation": "ping",
                                      "parameters": {}
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("ok"))
                    .andExpect(jsonPath("$.data.operator").value("schema-integration"));
        }
    }
}

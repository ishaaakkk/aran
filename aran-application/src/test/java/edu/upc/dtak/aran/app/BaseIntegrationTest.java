package edu.upc.dtak.aran.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import org.jspecify.annotations.Nullable;
import org.mockito.Mockito;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Spring Boot integration tests.
 *
 * <p>Starts a real PostgreSQL container (via Testcontainers) and wires its
 * coordinates into the Spring context before it loads. Flyway applies all
 * migrations, including the dev-profile seed, so tests start with the same
 * baseline schema as a fresh local dev environment.
 *
 * <p>Keycloak is NOT started — tests that need JWT authentication use
 * Spring Security Test's {@code @WithMockUser} or provide a test JWT via
 * a mock server (see {@code JwtTestHelper} when you add one). The OIDC
 * issuer URL is set to a non-resolving test value; tests that need it must
 * configure a mock authorization server.
 *
 * <p>This class is annotated with {@code @AutoConfigureMockMvc} so subclasses
 * can inject {@code MockMvc} directly with {@code @Autowired}.
 */
// Tagged so `validate.sh --offline` can exclude everything that needs a Docker daemon without
// naming individual classes. JUnit tags are inherited by subclasses, so every integration test
// picks this up automatically and any NEW Docker-free test in this module runs offline by
// default — the safe direction, since a forgotten tag makes a test run rather than vanish.
@org.junit.jupiter.api.Tag("docker")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration"
        }
)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import({
    BaseIntegrationTest.TestIdentityConfig.class,
    BaseIntegrationTest.TestGraphConfig.class
})
public abstract class BaseIntegrationTest {

    // Written by the session setup when the sandbox can't do the
    // bridge networking Testcontainers needs.
    // Its presence means a Postgres is already listening on a fixed,
    // host-networked port and Testcontainers should not try to start its own.
    private static final Path CONTAINER_INFO_PATH = Path.of(".container-info.json");

    private record ContainerInfo(String jdbcUrl, String username, String password) {}

    private static ContainerInfo readPreexistingContainerInfo() {
        try {
            JsonNode info = new ObjectMapper().readTree(Files.readString(CONTAINER_INFO_PATH));
            String jdbcUrl = "jdbc:postgresql://%s:%d/%s".formatted(
                    info.get("host").asText(), info.get("port").asInt(), info.get("dbname").asText());
            return new ContainerInfo(jdbcUrl, info.get("user").asText(), info.get("password").asText());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + CONTAINER_INFO_PATH, e);
        }
    }

    static final PostgreSQLContainer<?> postgres =
            Files.exists(CONTAINER_INFO_PATH)
                    ? null
                    : new PostgreSQLContainer<>("postgres:17")
                            .withDatabaseName("aran_test")
                            .withUsername("aran")
                            .withPassword("aran");

    static {
        if (postgres != null) {
            postgres.start();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // One JVM, one address, no bearer header on a MockMvc request: the whole suite would share
        // the 60/min anonymous bucket (and alice the 300/min user bucket). The limiter itself is
        // proven by RateLimitingFilterTest; here it must not decide a test's outcome.
        // RateLimitingIntegrationIT, which proves the limiter, puts the real ceilings back through
        // these system properties in its @BeforeAll (its @TestPropertySource gives it a context of
        // its own, created after that, so no other class sees them).
        for (String tier : new String[] {"anon-per-minute", "authed-per-minute", "pre-auth-token-per-minute"}) {
            registry.add("aran.security.rate-limit." + tier,
                    () -> System.getProperty("aran.test.rate-limit." + tier, "1000000"));
        }
        if (postgres != null) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            ContainerInfo info = readPreexistingContainerInfo();
            registry.add("spring.datasource.url", () -> info.jdbcUrl());
            registry.add("spring.datasource.username", () -> info.username());
            registry.add("spring.datasource.password", () -> info.password());
        }

        // One Postgres serves EVERY Spring context this suite caches, and each context brings its
        // own Hikari pool sized by production's ARAN_DB_POOL_SIZE default of 20 against the
        // container's default max_connections of 100. A handful of contexts therefore exhausts the
        // server — any test class adding a @TestConfiguration or a @MockitoBean forks another one —
        // and the symptom lands on whichever class loads last, as Flyway failing with "FATAL: sorry,
        // too many clients already" for a limit some unrelated class crossed. Observed for real
        // while adding one IT class. A test context serves one test thread; four connections is
        // generous for that, and the cap is what keeps adding a test class from being a capacity
        // decision.
        //
        // minimum-idle carries as much of that as the cap does: Hikari defaults it TO
        // maximumPoolSize, so without it every cached context would hold its full four connections
        // open for the rest of the run whether or not its own tests are still executing — the cap
        // would bound the peak but not the resting cost. One idle connection per context, grown on
        // demand.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);

        // Point the JWT issuer and OAuth2 client at dummy values and explicit URIs
        // to prevent Spring Security from attempting external OIDC discovery on startup.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost:9999/realms/aran-test/protocol/openid-connect/certs");
        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.client.provider.keycloak.authorization-uri", () -> "http://localhost:9999/realms/aran-test/protocol/openid-connect/auth");
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", () -> "http://localhost:9999/realms/aran-test/protocol/openid-connect/token");
        registry.add("spring.security.oauth2.client.provider.keycloak.jwk-set-uri", () -> "http://localhost:9999/realms/aran-test/protocol/openid-connect/certs");

        // Use database licensing mode for integration tests
        registry.add("aran.licensing.mode", () -> "database");
        registry.add("aran.identity.provider", () -> "mock");
    }

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @TestConfiguration
    public static class TestIdentityConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        edu.upc.dtak.aran.core.domain.IdentityProviderPort testIdentityProvider() {
            return new edu.upc.dtak.aran.core.domain.IdentityProviderPort() {
                /**
                 * Email → subject for accounts this stub created, so re-adding a removed member
                 * finds the account the way Keycloak would (UI-OD9) instead of minting a second.
                 */
                private final java.util.Map<String, String> byEmail =
                        new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public String createUser(String username, String email, String displayName) {
                    String subject = java.util.UUID.randomUUID().toString();
                    byEmail.put(email.toLowerCase(java.util.Locale.ROOT), subject);
                    return subject;
                }

                @Override
                public java.util.Optional<String> findSubjectByEmail(String email) {
                    return java.util.Optional.ofNullable(
                            byEmail.get(email.toLowerCase(java.util.Locale.ROOT)));
                }

                @Override
                public void setTemporaryPassword(String subject, String password) {}

                @Override
                public void disableUser(String subject) {}

                @Override
                public void enableUser(String subject) {}

                @Override
                public void deleteUser(String subject) {}

                @Override
                public java.util.Optional<String> findSubjectByUsername(String username) {
                    if ("alice".equals(username)) {
                        return java.util.Optional.of("00000000-0000-0000-0000-000000000002");
                    }
                    return java.util.Optional.empty();
                }
            };
        }
    }

    @TestConfiguration
    public static class TestGraphConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        public InMemoryGraphStore inMemoryGraphStore() {
            return new InMemoryGraphStore();
        }
    }

    public static class InMemoryGraphStore implements edu.upc.dtak.aran.core.domain.GraphStore {
        private static final java.util.regex.Pattern GRAPH_URI = java.util.regex.Pattern.compile("GRAPH <([^>]+)>");
        private static final java.util.regex.Pattern SOURCE_URI = java.util.regex.Pattern.compile("urn:aran:source:([0-9a-fA-F\\-]+)");
        private static final String ACTIVE = "urn:aran:schema";

        private final java.util.Map<String, org.apache.jena.rdf.model.Model> graphs = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.List<GraphVersion> versions = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicReference<GraphVersion> lastDraft = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicLong lastDraftSize = new java.util.concurrent.atomic.AtomicLong();

        public void stage(java.util.UUID datasetId, org.apache.jena.rdf.model.Model model) {
            graphs.put("urn:aran:source:" + datasetId, model);
        }

        public @Nullable GraphVersion lastDraft() {
            return lastDraft.get();
        }

        public long lastDraftSize() {
            return lastDraftSize.get();
        }

        @Override
        public org.apache.jena.rdf.model.Model query(edu.upc.dtak.aran.core.tenant.TenantContext t, String sparql) {
            java.util.regex.Matcher m = GRAPH_URI.matcher(sparql);
            if (m.find()) {
                org.apache.jena.rdf.model.Model stored = graphs.get(m.group(1));
                if (stored != null) return stored;
            }
            java.util.regex.Matcher m2 = SOURCE_URI.matcher(sparql);
            if (m2.find()) {
                org.apache.jena.rdf.model.Model stored = graphs.get("urn:aran:source:" + m2.group(1));
                if (stored != null) return stored;
            }
            return org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        }

        @Override
        public org.apache.jena.rdf.model.Model readVersion(edu.upc.dtak.aran.core.tenant.TenantContext t, java.util.UUID versionId) {
            return copy(graphs.getOrDefault("urn:aran:schema:version:" + versionId,
                    org.apache.jena.rdf.model.ModelFactory.createDefaultModel()));
        }

        @Override
        public GraphVersion replaceDraft(
                edu.upc.dtak.aran.core.tenant.TenantContext t,
                org.apache.jena.rdf.model.Model full,
                edu.upc.dtak.aran.core.domain.AuditedActor actor) {
            java.util.UUID id = java.util.UUID.randomUUID();
            int next = versions.stream().mapToInt(GraphVersion::versionNumber).max().orElse(0) + 1;
            graphs.put("urn:aran:schema:version:" + id, copy(full));
            GraphVersion draft = new GraphVersion(
                    id, java.util.UUID.fromString(t.value()), next, "DRAFT",
                    java.time.Instant.now(), actor.userId(), null, "Draft " + next);
            versions.add(draft);
            lastDraft.set(draft);
            lastDraftSize.set(full.size());
            return draft;
        }

        @Override
        public GraphVersion promote(
                edu.upc.dtak.aran.core.tenant.TenantContext t,
                java.util.UUID draftId,
                edu.upc.dtak.aran.core.domain.AuditedActor actor) {
            GraphVersion draft = versions.stream()
                    .filter(v -> v.id().equals(draftId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("draft not found: " + draftId));
            graphs.put(ACTIVE, copy(graphs.getOrDefault("urn:aran:schema:version:" + draftId,
                    org.apache.jena.rdf.model.ModelFactory.createDefaultModel())));
            versions.replaceAll(v -> "ACTIVE".equals(v.status())
                    ? new GraphVersion(v.id(), v.tenantId(), v.versionNumber(), "HISTORICAL",
                            v.createdAt(), v.createdBy(), v.promotedAt(), v.notes())
                    : v);
            GraphVersion promoted = new GraphVersion(
                    draft.id(), draft.tenantId(), draft.versionNumber(), "ACTIVE",
                    draft.createdAt(), draft.createdBy(), java.time.Instant.now(), draft.notes());
            versions.replaceAll(v -> v.id().equals(draft.id()) ? promoted : v);
            return promoted;
        }

        @Override
        public @Nullable GraphVersion currentVersion(edu.upc.dtak.aran.core.tenant.TenantContext t) {
            return versions.stream().filter(v -> "ACTIVE".equals(v.status()))
                    .reduce((a, b) -> b).orElse(null);
        }

        @Override
        public void addTriples(edu.upc.dtak.aran.core.tenant.TenantContext t, String graphUri, org.apache.jena.rdf.model.Model triples) {
            graphs.computeIfAbsent(graphUri, k -> org.apache.jena.rdf.model.ModelFactory.createDefaultModel()).add(triples);
        }

        @Override
        public void clearGraph(edu.upc.dtak.aran.core.tenant.TenantContext t, String graphUri) {
            graphs.remove(graphUri);
        }

        @Override
        public void provision(edu.upc.dtak.aran.core.tenant.TenantContext t) { }

        private static org.apache.jena.rdf.model.Model copy(org.apache.jena.rdf.model.Model m) {
            return org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(m);
        }
    }
}

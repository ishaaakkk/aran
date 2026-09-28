package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.DatasetPreview;
import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.DataRepositoryService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2-7 against a real multi-schema PostgreSQL: what the scan offers is what the preview reads.
 *
 * <p>A repository's {@code schema} scopes {@code listAvailableDatasets} — it is the
 * {@code schemaPattern} handed to {@code DatabaseMetaData.getTables} — but the preview's SQL was
 * {@code SELECT * FROM "<table>"}, unqualified, resolved against the connection's default search
 * path. Two schemas holding a {@code venue} table is therefore not an exotic case: it is the shape
 * that makes the wrong rows come back under the right name, silently, with no error to notice.
 * With no same-named relation on the search path the same code fails outright, so a repository
 * whose schema is set could not be previewed at all.
 *
 * <p>The source database is a SECOND PostgreSQL container, not the one {@link BaseIntegrationTest}
 * runs Aran's own schema on: this test needs to create schemas and tables freely, and Flyway owns
 * the other one. {@link SourceDriverConfig} then makes {@code DriverDownloader} hand back the
 * driver already on the test classpath instead of downloading a pinned jar from Maven Central —
 * the download path has its own tests and is not what this one is about.
 *
 * <p><b>What this proves</b>: the preview path, end to end through the real service, real
 * repository rows and a real server, reads the configured schema. <b>What it does not</b>: the two
 * import paths. The lake-native one runs its SELECT inside {@code aran-lake}, which is not in this
 * process, and the generic JDBC one ends in a lake upload; their SQL is pinned instead by
 * {@code DatasetLakeImportQualificationTest} and {@code JdbcTableExtractorSchemaTest}.
 */
@Import(SchemaQualifiedJdbcPreviewIT.SourceDriverConfig.class)
@DisplayName("JDBC preview reads the repository's schema (S2-7)")
class SchemaQualifiedJdbcPreviewIT extends BaseIntegrationTest {

    /** The relation on the default search path — the one an unqualified read finds. */
    private static final int PUBLIC_ROWS = 2;
    /** The relation in the schema the repository names — the one the user picked. */
    private static final int REPORTING_ROWS = 5;

    private static final PostgreSQLContainer<?> SOURCE =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("srcdb")
                    .withUsername("src")
                    .withPassword("src-pw");

    /**
     * The postgres driver from this module's test classpath, standing in for the sandboxed download.
     * {@code @Primary} so {@code DataRepositoryService} injects it rather than
     * {@code HttpJdbcDriverDownloader}, which would reach Maven Central for a jar this JVM already
     * has.
     */
    @TestConfiguration
    static class SourceDriverConfig {
        @Bean
        @Primary
        DriverDownloader classpathDriverDownloader() {
            return new DriverDownloader() {
                @Override public Driver loadDriver(String engine) throws Exception {
                    return DriverManager.getDriver(SOURCE.getJdbcUrl());
                }
                @Override public void unloadDriver(String engine) {
                    // Nothing to release: no sandboxed classloader here.
                }
            };
        }
    }

    @Autowired DataRepositoryService repositories;
    @Autowired ProjectRepository projects;
    @Autowired DataRepositoryRepository repositoryStore;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    private AuthenticatedUser caller;
    private TenantContext ctx;
    private Project project;

    @BeforeAll
    static void seedTwoSchemasWithTheSameTable() throws Exception {
        SOURCE.start();
        try (Connection conn = DriverManager.getConnection(
                     SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
             Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE public.venue (id INTEGER, name TEXT)");
            s.execute("INSERT INTO public.venue VALUES (1, 'wrong-a'), (2, 'wrong-b')");

            s.execute("CREATE SCHEMA reporting");
            s.execute("CREATE TABLE reporting.venue (id INTEGER, name TEXT)");
            s.execute("INSERT INTO reporting.venue VALUES "
                    + "(1, 'right-a'), (2, 'right-b'), (3, 'right-c'), (4, 'right-d'), (5, 'right-e')");

            // A name the scan lists but the preview's old '^[A-Za-z_][A-Za-z0-9_]{0,63}$' gate
            // rejected — the second half of S2-7's evidence.
            s.execute("CREATE TABLE reporting.\"year end\" (id INTEGER)");
            s.execute("INSERT INTO reporting.\"year end\" VALUES (1)");
        }
    }

    /** Ryuk would reap it at JVM exit; stopping here keeps the suite's container count honest. */
    @AfterAll
    static void stopSource() {
        SOURCE.stop();
    }

    /**
     * A fresh tenant and project per test, minted through the ports rather than through
     * {@code ProjectService} — a tenant created here has no license row, and {@code createProject}
     * gates on one. The membership row it would also write is added explicitly; every access check
     * below reads that, none of them reads the license. Same reasoning as
     * {@code RelationalEnrolmentHarvestIT}.
     */
    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        users.save(new User(userId, "sub-" + suffix, suffix + "@test.com", "Test User", Instant.now()));
        tenants.save(new Tenant(tenantId, "tenant-" + suffix, "Tenant", Instant.now()));

        caller = new AuthenticatedUser(userId, "sub-" + suffix, tenantId, TenantRole.OWNER);
        ctx = new TenantContext(tenantId.toString());
        project = projects.save(ctx, new Project(
                UUID.randomUUID(), tenantId, "S2-7-" + suffix, "", userId, Instant.now()));
        projects.addMember(ctx, new ProjectMember(
                UUID.randomUUID(), project.id(), userId, Instant.now()));
    }

    private UUID repositoryWithSchema(String schema) {
        return repositoryStore.save(ctx, new RelationalDatabaseRepository(
                UUID.randomUUID(), project.id(), caller.tenantId(), "source-" + schema, "",
                "POSTGRESQL", SOURCE.getHost(), SOURCE.getFirstMappedPort(), "srcdb",
                SOURCE.getUsername(), SOURCE.getPassword(), schema, false)).id();
    }

    @Test
    @DisplayName("the preview returns the configured schema's rows, not the search path's")
    void preview_reads_the_configured_schema() {
        UUID repositoryId = repositoryWithSchema("reporting");

        assertThat(repositories.listAvailableDatasets(caller, project.id(), repositoryId))
                .as("the scan is schema-scoped, which is what makes the table name ambiguous")
                .contains("venue")
                .doesNotContain("pg_stat_statements");

        DatasetPreview preview = repositories.previewDataset(caller, project.id(), repositoryId, "venue");

        assertThat(preview.rows())
                .as("public.venue holds %d rows of 'wrong-*'; reporting.venue holds %d of 'right-*'",
                        PUBLIC_ROWS, REPORTING_ROWS)
                .hasSize(5)
                .allSatisfy(row -> assertThat(row.get(1).toString()).startsWith("right-"));
    }

    @Test
    @DisplayName("a repository with no schema still previews the search path's table")
    void preview_without_a_schema_is_unchanged() {
        UUID repositoryId = repositoryWithSchema("");

        DatasetPreview preview = repositories.previewDataset(caller, project.id(), repositoryId, "venue");

        assertThat(preview.rows())
                .hasSize(PUBLIC_ROWS)
                .allSatisfy(row -> assertThat(row.get(1).toString()).startsWith("wrong-"));
    }

    @Test
    @DisplayName("a table the scan lists but the old name regex rejected is previewable")
    void preview_accepts_every_name_the_scan_offered() {
        UUID repositoryId = repositoryWithSchema("reporting");

        assertThat(repositories.listAvailableDatasets(caller, project.id(), repositoryId))
                .contains("year end");
        assertThat(repositories.previewDataset(caller, project.id(), repositoryId, "year end").rows())
                .hasSize(1);
    }

    @Test
    @DisplayName("a table outside the repository's schema is refused, however well-formed its name")
    void preview_refuses_a_table_the_scan_did_not_offer() {
        // The membership check is what replaces the regex, so it has to be at least as strict:
        // 'pg_class' is a perfectly legal identifier that this repository does not expose.
        UUID repositoryId = repositoryWithSchema("reporting");

        assertThatThrownBy(() ->
                repositories.previewDataset(caller, project.id(), repositoryId, "pg_class"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the scan's own schema scoping is what this depends on")
    void scan_is_scoped_to_the_repositorys_schema() {
        assertThat(repositories.listAvailableDatasets(caller, project.id(), repositoryWithSchema("public")))
                .contains("venue")
                .doesNotContain("year end");
        assertThat(repositories.listAvailableDatasets(caller, project.id(), repositoryWithSchema("reporting")))
                .contains("venue", "year end");
    }
}

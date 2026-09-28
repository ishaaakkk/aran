package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V44 against a real PostgreSQL: rows of the three deleted repository kinds go, with their
 * datasets and everything hanging off those, and nothing else does.
 *
 * <p>The rows matter because the entities are gone. {@code data_repository} is SINGLE_TABLE, so a
 * surviving {@code KEY_VALUE} row is a discriminator Hibernate can no longer resolve, and the read
 * of that project's repository list fails instead of skipping it. The migration's own statements
 * are run against rows seeded here, since a fresh test database has none of these kinds to delete.
 */
@DisplayName("Connector-less repository kinds are deleted (V44)")
class ConnectorlessRepositoryKindsDeletedIT extends BaseIntegrationTest {

    private static final String MIGRATION = "db/migration/V44__delete_connectorless_repository_kinds.sql";

    @Autowired JdbcTemplate jdbc;
    @Autowired DataRepositoryRepository repositories;

    /** The migration's statements, comments stripped. V44 holds no string literal with a semicolon. */
    private static List<String> migrationStatements() {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("V44 is not on the test classpath: " + MIGRATION, e);
        }
        String withoutComments = sql.lines()
                .map(line -> line.replaceAll("--.*$", ""))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private UUID insertRepository(UUID tenantId, UUID projectId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO data_repository (id, project_id, tenant_id, type, name) VALUES (?, ?, ?, ?, ?)",
                id, projectId, tenantId, type, "v44-" + type);
        return id;
    }

    private UUID insertDataset(UUID tenantId, UUID repositoryId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dataset (id, repository_id, tenant_id, name) VALUES (?, ?, ?, ?)",
                id, repositoryId, tenantId, "orders");
        return id;
    }

    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }

    @Test
    @Transactional
    @DisplayName("stub-kind repositories, their datasets and the datasets' children go; a live kind stays")
    void deletesOnlyTheThreeKinds() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, slug, name, created_at) VALUES (?, ?, ?, ?)",
                tenantId, "v44-" + tenantId.toString().substring(0, 8), "V44", Timestamp.from(Instant.now()));
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, subject, email, display_name) VALUES (?, ?, ?, ?)",
                userId, "v44-" + userId, "v44@example.org", "V44");
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project (id, tenant_id, name, created_by) VALUES (?, ?, ?, ?)",
                projectId, tenantId, "V44 project", userId);

        // Complete enough for RelationalDatabaseRepository's own checks, since it is read back below.
        UUID survivor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO data_repository (id, project_id, tenant_id, type, name, engine, host, port, database_name)
                VALUES (?, ?, ?, 'RELATIONAL_DB', 'v44-live', 'POSTGRESQL', 'db.internal', 5432, 'warehouse')""",
                survivor, projectId, tenantId);
        UUID survivorDataset = insertDataset(tenantId, survivor);

        List<UUID> stubs = List.of(
                insertRepository(tenantId, projectId, "NOSQL_DOCUMENT"),
                insertRepository(tenantId, projectId, "KEY_VALUE"),
                insertRepository(tenantId, projectId, "WEB_API"));
        UUID stubDataset = insertDataset(tenantId, stubs.getFirst());
        // A grandchild, so the cascade the migration relies on is exercised rather than assumed.
        jdbc.update("""
                INSERT INTO dataset_schema_fingerprint (id, tenant_id, dataset_id, fingerprint_hash, fingerprint)
                VALUES (?, ?, ?, ?, '{}'::jsonb)""",
                UUID.randomUUID(), tenantId, stubDataset, "0".repeat(64));

        migrationStatements().forEach(jdbc::execute);

        for (UUID stub : stubs) {
            assertThat(count("data_repository", "id", stub)).as("stub repository %s", stub).isZero();
        }
        assertThat(count("dataset", "id", stubDataset)).isZero();
        assertThat(count("dataset_schema_fingerprint", "dataset_id", stubDataset)).isZero();

        assertThat(count("data_repository", "id", survivor)).isEqualTo(1);
        assertThat(count("dataset", "id", survivorDataset)).isEqualTo(1);

        // What the rows would have broken: the JPA read of the project's repositories.
        List<DataRepository> listed = repositories.findAllByProject(new TenantContext(tenantId.toString()), projectId);
        assertThat(listed).extracting(DataRepository::id).containsExactly(survivor);
    }
}

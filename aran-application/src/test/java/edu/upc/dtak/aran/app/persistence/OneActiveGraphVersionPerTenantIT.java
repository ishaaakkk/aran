package edu.upc.dtak.aran.app.persistence;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V43's partial unique index against a real PostgreSQL: at most one ACTIVE schema version per
 * tenant, and the repair that makes the index appliable to a database that already broke the rule.
 *
 * <p>The defect this closes is a tenant-wide outage rather than a wrong row.
 * {@code findByTenantIdAndStatus} returns an {@code Optional}, so a second ACTIVE row does not
 * shade one answer — it makes every {@code currentVersion()} for that tenant throw
 * {@code IncorrectResultSizeDataAccessException}, taking the schema, its queries, its products and
 * its cubes down together.
 *
 * <p>This has to be an integration test. A partial index has no JPA {@code @UniqueConstraint}
 * form, and {@code ddl-auto: validate} compares tables and columns only — nothing in the Java
 * build can observe whether the rule is enforced, so a real server rejecting the row is the only
 * available proof. The storage schema is owned by migrations.
 *
 * <p><strong>What it does not prove.</strong> Nothing here exercises {@code promote}: the paths
 * that could write the second row — two concurrent promotes, each demoting the same outgoing
 * version — are untouched, and after this migration the losing promote fails with a constraint
 * violation that no caller handles. The index converts silent corruption into a loud failure; it is
 * not the transaction D5 sequences after the alias deletion, and it says nothing about promote's
 * opposite failure mode, a window with *zero* ACTIVE rows. The repair is also run against rows this
 * test seeds, not against a database found corrupted in the wild.
 */
@DisplayName("One ACTIVE graph version per tenant (V43)")
class OneActiveGraphVersionPerTenantIT extends BaseIntegrationTest {

    private static final String MIGRATION = "db/migration/V43__one_active_graph_version_per_tenant.sql";

    @Autowired JdbcTemplate jdbc;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, slug, name, created_at) VALUES (?, ?, ?, ?)",
                id, "v43-" + id.toString().substring(0, 8), "V43 Tenant", Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertVersion(UUID tenantId, int versionNumber, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tenant_graph_version (id, tenant_id, version_number, status, created_at)
                VALUES (?, ?, ?, ?, ?)""",
                id, tenantId, versionNumber, status, Timestamp.from(Instant.now()));
        return id;
    }

    private String statusOf(UUID versionId) {
        return jdbc.queryForObject(
                "SELECT status FROM tenant_graph_version WHERE id = ?", String.class, versionId);
    }

    /**
     * The migration's own text, not a copy of it. A repair asserted against a hand-transcribed
     * UPDATE proves that the transcription works, which is the one thing nobody needs to know: the
     * statement that has to be right is the one Flyway runs.
     *
     * <p>Safe to split on {@code ;} here because V43 holds exactly two statements and neither
     * contains a string literal with a semicolon or a {@code --} sequence in it. The single-match
     * check below is what fails, loudly, if that ever stops being true.
     */
    private static String migrationStatementStartingWith(String prefix) {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("V43 is not on the test classpath: " + MIGRATION, e);
        }
        String withoutComments = sql.lines()
                .map(line -> line.replaceAll("--.*$", ""))
                .collect(Collectors.joining("\n"));
        var matches = Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(s -> s.toUpperCase(Locale.ROOT).startsWith(prefix))
                .toList();
        assertThat(matches)
                .as("exactly one statement in V43 starts with '%s'", prefix)
                .hasSize(1);
        return matches.getFirst();
    }

    @Test
    @Transactional
    @DisplayName("a second ACTIVE version for the same tenant is refused by the database")
    void secondActiveVersionForOneTenantIsRefused() {
        UUID tenantId = tenant();
        insertVersion(tenantId, 1, "ACTIVE");

        assertThatThrownBy(() -> insertVersion(tenantId, 2, "ACTIVE"))
                .as("two ACTIVE rows make every currentVersion() for this tenant throw")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    @DisplayName("each tenant keeps its own ACTIVE version — the index is per tenant, not global")
    void twoTenantsMayEachHaveAnActiveVersion() {
        UUID first = tenant();
        UUID second = tenant();

        UUID activeOfFirst = insertVersion(first, 7, "ACTIVE");
        UUID activeOfSecond = insertVersion(second, 7, "ACTIVE");

        assertThat(statusOf(activeOfFirst)).isEqualTo("ACTIVE");
        assertThat(statusOf(activeOfSecond)).isEqualTo("ACTIVE");
    }

    @Test
    @Transactional
    @DisplayName("one ACTIVE version coexists with any number of DRAFT and HISTORICAL ones")
    void draftAndHistoricalVersionsAreUnconstrained() {
        UUID tenantId = tenant();

        insertVersion(tenantId, 1, "HISTORICAL");
        insertVersion(tenantId, 2, "HISTORICAL");
        UUID active = insertVersion(tenantId, 3, "ACTIVE");
        insertVersion(tenantId, 4, "DRAFT");

        // A second DRAFT: the partial predicate must not reach anything but ACTIVE, or the ordinary
        // rebuild-to-draft flow (which leaves earlier drafts in place) would start failing.
        assertThatCode(() -> insertVersion(tenantId, 5, "DRAFT")).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenant_graph_version WHERE tenant_id = ?", Integer.class, tenantId))
                .isEqualTo(5);
        assertThat(statusOf(active)).isEqualTo("ACTIVE");
    }

    @Test
    @Transactional
    @DisplayName("the migration's repair leaves the HIGHEST version ACTIVE, and the index then applies")
    void theMigrationsRepairKeepsTheHighestVersionActive() {
        // Reproduce the pre-V43 state the repair exists for. The DDL is rolled back with the test,
        // as Postgres makes DDL transactional; nothing outside this method sees the index missing.
        jdbc.execute("DROP INDEX uq_tenant_graph_version_one_active");

        UUID tenantId = tenant();
        UUID older = insertVersion(tenantId, 4, "ACTIVE");
        UUID newest = insertVersion(tenantId, 5, "ACTIVE");
        UUID draft = insertVersion(tenantId, 6, "DRAFT");

        // A healthy neighbour whose ACTIVE version number is LOWER than the demoted row's, so a
        // repair written against the table instead of per tenant would demote it too.
        UUID healthyTenant = tenant();
        UUID healthyActive = insertVersion(healthyTenant, 1, "ACTIVE");

        jdbc.execute(migrationStatementStartingWith("UPDATE"));

        assertThat(statusOf(newest))
                .as("the last-promoted version is the one urn:aran:schema actually holds")
                .isEqualTo("ACTIVE");
        assertThat(statusOf(older)).isEqualTo("HISTORICAL");
        assertThat(statusOf(draft)).as("the repair touches ACTIVE rows only").isEqualTo("DRAFT");
        assertThat(statusOf(healthyActive))
                .as("a tenant that was never broken keeps its ACTIVE version")
                .isEqualTo("ACTIVE");

        // The migration's own second statement, run on the repaired data: if the repair had left two
        // ACTIVE rows anywhere, this is where V43 would fail on a real deployment.
        assertThatCode(() -> jdbc.execute(migrationStatementStartingWith("CREATE UNIQUE INDEX")))
                .doesNotThrowAnyException();
    }
}

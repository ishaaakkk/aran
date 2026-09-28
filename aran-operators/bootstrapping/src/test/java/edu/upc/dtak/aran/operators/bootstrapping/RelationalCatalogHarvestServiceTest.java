package edu.upc.dtak.aran.operators.bootstrapping;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.Harvest.ColKey;
import edu.upc.dtak.aran.core.source.Harvest.ForeignKey;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link RelationalCatalogHarvestService} against a real PostgreSQL (Testcontainers): reltuples
 * row counts (including the "never analyzed" omission), both signs of {@code pg_stats.n_distinct},
 * a single-column and a composite declared foreign key, and the best-effort guarantees around a
 * column with no stats and a rejected connection.
 *
 * <p>Docker-tagged so {@code validate.sh --offline} excludes it; run it with
 * {@code ./gradlew :aran-operators:bootstrapping:test} on a host with Docker.
 *
 * <p>{@link #DRIVERS} resolves the PostgreSQL driver already on this module's test classpath
 * (needed for Testcontainers itself) rather than downloading one — no sandboxed classloader here.
 * What this proves is {@link RelationalCatalogHarvestService}'s own wiring (engine lookup, URL
 * templating, and — the point of two of the three tests below — that a harvest never throws) and
 * {@link PostgresCatalogHarvestQueries}' SQL against a real catalog, not {@code
 * HttpJdbcDriverDownloader}'s download path, which has its own tests.
 *
 * <p>The {@code parent}/{@code child} tables exist to prove, against a live server rather than a
 * synthetic row, that a composite foreign key's columns come back correctly paired — parent's key
 * order is {@code (z, a)}, and the harvest must pair {@code pz -> z}, {@code pa -> a} by the
 * {@code conkey}/{@code confkey} array positions, not by row-arrival order. The {@code collide}
 * schema proves the other half of the OID-based identity fix: two tables ({@code orders},
 * {@code invoices}) each declaring a constraint named {@code fk_ref} that references a different
 * table must resolve to their own tables, which an earlier constraint-name-keyed harvest got wrong.
 */
@Tag("docker")
@DisplayName("RelationalCatalogHarvestService — catalog harvest over a real PostgreSQL")
class RelationalCatalogHarvestServiceTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("harvest_test")
                    .withUsername("harvest")
                    .withPassword("harvest-pw");

    private static final int EMPLOYEE_COUNT = 900;
    private static final int DEPARTMENT_COUNT = 5;

    private static final DriverDownloader DRIVERS = new DriverDownloader() {
        @Override
        public Driver loadDriver(String engine) throws Exception {
            return DriverManager.getDriver(POSTGRES.getJdbcUrl());
        }

        @Override
        public void unloadDriver(String engine) {
            // No sandboxed classloader to release — see the class javadoc.
        }
    };

    private final RelationalCatalogHarvestService service = new RelationalCatalogHarvestService(DRIVERS, new edu.upc.dtak.aran.core.net.OutboundAddressGuard(false));

    @BeforeAll
    static void up() throws Exception {
        POSTGRES.start();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = conn.createStatement()) {

            s.execute("CREATE TABLE departments (id SERIAL PRIMARY KEY, name TEXT NOT NULL)");
            s.execute("CREATE TABLE employees ("
                    + "id SERIAL PRIMARY KEY, "
                    + "dept_id INTEGER NOT NULL REFERENCES departments(id), "
                    + "status TEXT NOT NULL, "
                    // Never written below, so ANALYZE records it as entirely NULL (null_frac 1).
                    + "nickname TEXT)");
            // Never ANALYZE'd below — the "a table with no stats" case.
            s.execute("CREATE TABLE logs (id SERIAL PRIMARY KEY, message TEXT)");

            // Composite FK, declared so the referenced columns are NOT alphabetical: parent's own
            // key order is (z, a); alphabetical order would be (a, z). See the class javadoc.
            s.execute("CREATE TABLE parent (z INTEGER NOT NULL, a INTEGER NOT NULL, PRIMARY KEY (z, a))");
            s.execute("CREATE TABLE child ("
                    + "id SERIAL PRIMARY KEY, "
                    + "pz INTEGER NOT NULL, "
                    + "pa INTEGER NOT NULL, "
                    + "FOREIGN KEY (pz, pa) REFERENCES parent(z, a))");
            s.execute("INSERT INTO parent (z, a) VALUES (1, 10), (2, 20)");
            s.execute("INSERT INTO child (pz, pa) VALUES (1, 10), (2, 20)");

            for (int i = 1; i <= DEPARTMENT_COUNT; i++) {
                s.execute("INSERT INTO departments (name) VALUES ('dept-" + i + "')");
            }
            // status cycles through exactly 3 literal values -> ANALYZE should report a small
            // positive n_distinct. dept_id cycles through every department -> exercises the FK
            // without needing to be unique itself.
            String[] statuses = {"ACTIVE", "INACTIVE", "PENDING"};
            StringBuilder insert = new StringBuilder("INSERT INTO employees (dept_id, status) VALUES ");
            for (int i = 0; i < EMPLOYEE_COUNT; i++) {
                if (i > 0) {
                    insert.append(", ");
                }
                int deptId = (i % DEPARTMENT_COUNT) + 1;
                insert.append('(').append(deptId).append(", '").append(statuses[i % statuses.length]).append("')");
            }
            s.execute(insert.toString());
            s.execute("INSERT INTO logs (message) VALUES ('never analyzed')");

            s.execute("ANALYZE departments");
            s.execute("ANALYZE employees");

            // A separate schema whose two referencing tables each declare a constraint with the
            // SAME name (fk_ref) pointing at DIFFERENT referenced tables — legal on PostgreSQL,
            // where a constraint name is unique only per table. Harvested on its own (repoInSchema)
            // so the "public" assertions above stay exact. This is the live proof of the OID-based
            // identity fix: the old name-based harvest cross-attributed same-named constraints.
            s.execute("CREATE SCHEMA collide");
            s.execute("CREATE TABLE collide.customers (id INTEGER PRIMARY KEY)");
            s.execute("CREATE TABLE collide.vendors (id INTEGER PRIMARY KEY)");
            s.execute("CREATE TABLE collide.orders (customer_id INTEGER, "
                    + "CONSTRAINT fk_ref FOREIGN KEY (customer_id) REFERENCES collide.customers(id))");
            s.execute("CREATE TABLE collide.invoices (vendor_id INTEGER, "
                    + "CONSTRAINT fk_ref FOREIGN KEY (vendor_id) REFERENCES collide.vendors(id))");
        }
    }

    @AfterAll
    static void down() {
        POSTGRES.stop();
    }

    private static RelationalDatabaseRepository repo(String username, String password) {
        return repoInSchema("public", username, password);
    }

    private static RelationalDatabaseRepository repoInSchema(String schema, String username, String password) {
        return new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "harvest-test-repo", "", "POSTGRESQL",
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName(),
                username, password, schema, false);
    }

    @Test
    @DisplayName("reltuples row count, both n_distinct sign branches, and the declared FKs")
    void harvests_stats_and_declared_fk() {
        Harvest harvest = service.harvest(repo(POSTGRES.getUsername(), POSTGRES.getPassword()));

        Long employeeRows = harvest.rowCountByTable().get("employees");
        assertThat(employeeRows).isNotNull().isGreaterThan(0L);
        // ANALYZE fully scans a table this small, so reltuples should be exact or very close —
        // asserted with tolerance rather than pinned to 900, since reltuples is documented as an
        // estimate even when PostgreSQL's sampler happens to read every row.
        assertThat((double) employeeRows).isCloseTo(EMPLOYEE_COUNT, within(EMPLOYEE_COUNT * 0.05));

        // employees.id is the primary key: every sampled value is distinct, so ANALYZE stores
        // n_distinct = -1 ("scales with the row count"). Asserting equality against the table's
        // own harvested row count (rather than a hardcoded 900) tests the actual sign-conversion
        // formula, and holds regardless of exactly what ANALYZE measured reltuples as.
        Long idNdv = harvest.ndvByColumn().get(new ColKey("employees", "id"));
        assertThat(idNdv).isEqualTo(employeeRows);

        // employees.status cycles through exactly 3 literal values -> ANALYZE stores that small
        // positive count directly (the other n_distinct sign branch).
        Long statusNdv = harvest.ndvByColumn().get(new ColKey("employees", "status"));
        assertThat(statusNdv).isEqualTo(3L);

        // pg_stats.null_frac, read off the same rows: a NOT NULL column and an all-NULL one.
        assertThat(harvest.nullFractionByColumn())
                .containsEntry(new ColKey("employees", "status"), 0.0)
                .containsEntry(new ColKey("employees", "nickname"), 1.0);

        // The composite FK's columns must come back paired by declared position (pz -> z,
        // pa -> a) — NOT alphabetically (which would wrongly report pz -> a, pa -> z). See the
        // class javadoc: this is the exact live-server behaviour that motivated ordinal pairing.
        assertThat(harvest.declaredForeignKeys()).containsExactlyInAnyOrder(
                new ForeignKey("employees", List.of("dept_id"), "departments", List.of("id")),
                new ForeignKey("child", List.of("pz", "pa"), "parent", List.of("z", "a")));
    }

    @Test
    @DisplayName("two tables' same-named constraints are attributed to their OWN tables, never cross-attributed")
    void same_named_constraints_on_two_tables_resolve_to_the_correct_table() {
        // orders.fk_ref -> customers, invoices.fk_ref -> vendors: same constraint NAME, different
        // OIDs, different referenced tables. The old constraint-name-keyed harvest would merge these
        // and record both against whichever arrived first; the pg_constraint OID identity keeps them
        // apart. This is the live counterpart to the pure-parser
        // same_named_constraints_on_two_tables_are_not_cross_attributed test.
        Harvest harvest = service.harvest(
                repoInSchema("collide", POSTGRES.getUsername(), POSTGRES.getPassword()));

        assertThat(harvest.declaredForeignKeys()).containsExactlyInAnyOrder(
                new ForeignKey("orders", List.of("customer_id"), "customers", List.of("id")),
                new ForeignKey("invoices", List.of("vendor_id"), "vendors", List.of("id")));
    }

    @Test
    @DisplayName("a table ANALYZE never touched contributes no NDV or row count, and the harvest does not throw")
    void no_stats_is_absent_not_a_throw() {
        Harvest harvest = service.harvest(repo(POSTGRES.getUsername(), POSTGRES.getPassword()));

        assertThat(harvest.ndvByColumn().keySet()).noneMatch(key -> key.table().equals("logs"));
        // reltuples is PostgreSQL's -1 "never vacuumed or analyzed" sentinel for a fresh table —
        // omitted, not reported as a confirmed zero. Reaching this line at all is also part of the
        // assertion: no stats must not throw.
        assertThat(harvest.rowCountByTable()).doesNotContainKey("logs").containsKey("employees");
    }

    @Test
    @DisplayName("a rejected password degrades to an empty harvest behind a WARN, never a throw")
    void connection_failure_is_warn_and_empty() {
        List<ILoggingEvent> events = capturingWarnings(() -> {
            Harvest harvest = service.harvest(repo(POSTGRES.getUsername(), "definitely-wrong-password"));

            assertThat(harvest).isEqualTo(Harvest.EMPTY);
        });

        assertWarned(events, "POSTGRESQL");
    }

    @Test
    @DisplayName("a blackholed host (packets silently dropped, not refused) fails within the bounded "
            + "connect timeout, never the OS's own multi-minute default")
    void blackholed_host_fails_fast_not_after_the_os_default() {
        // 192.0.2.1 is TEST-NET-1 (RFC 5737): reserved for documentation/testing, guaranteed
        // non-routable, and — unlike a real host that is merely down, which answers "connection
        // refused" in milliseconds — silently drops every packet. Confirmed against a raw socket in
        // this exact environment: an unbounded connect() to it takes ~75s (the OS's own TCP retry
        // schedule), not milliseconds, so this is a faithful stand-in for the production scenario
        // CONNECT_TIMEOUT_SECONDS exists for. The 25s bound is comfortably above the configured 10s
        // and far below that ~75s default — this is the end-to-end proof that a harvest against a
        // dead database returns EMPTY within a bound, whatever the mechanism.
        //
        // Note this does NOT by itself pin that OUR connectionProperties() is what provides the
        // bound: PGJDBC (both 42.7.5, the pinned production version, and 42.7.10, resolved on this
        // module's test classpath) defaults its own connectTimeout property to "10" seconds even
        // when nothing sets it — verified directly against both jars while building this fix. That
        // default is what actually explains a JdbcCatalogHarvester-unaware DriverManager.getDriver()
        // call also landing at ~10s. RelationalCatalogHarvestServiceConnectTimeoutTest is the test
        // that specifically regresses if connectionProperties() stops setting the property — it is
        // the one that failed when this fix was temporarily reverted during development; this test
        // failed only the outcome-level bound, which the driver's own default would have satisfied
        // either way. Setting it explicitly here is still correct: it makes the guarantee visible in
        // Aran's own code and testable independently of a third-party driver's undocumented default,
        // which is exactly what a version bump or a future non-Postgres harvest engine could change.
        RelationalDatabaseRepository blackholed = new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "blackholed-repo", "", "POSTGRESQL", "192.0.2.1", 5432, "db",
                "user", "pw", "public", false);

        Harvest harvest = assertTimeoutPreemptively(
                Duration.ofSeconds(25), () -> service.harvest(blackholed));

        assertThat(harvest).isEqualTo(Harvest.EMPTY);
    }

    /** Runs {@code body} with {@link RelationalCatalogHarvestService}'s WARNs captured. */
    private static List<ILoggingEvent> capturingWarnings(Runnable body) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(RelationalCatalogHarvestService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            body.run();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        return captured.list;
    }

    private static void assertWarned(List<ILoggingEvent> events, String... fragments) {
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(fragments);
        });
    }
}

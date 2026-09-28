package edu.upc.dtak.aran.core.domain.drift;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.FederatedScan;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The live reads, pinned character-for-character.
 *
 * <p>The MySQL setup is the one watched working against MySQL 8.4 and MariaDB 11 in
 * {@code AbstractFederatedMySqlFamilyIT}; the Postgres one, which took the same attached-catalog
 * shape so no view body carries its DSN, against Postgres 16 in {@code FederatedPostgresViewBodyTest}.
 * No offline test can dial either, so the strings
 * themselves are the contract. A drift here does not fail a build — it changes where federated
 * queries connect, and freyja's allowlist ({@code freyja/executors/scan.py}) admits these shapes
 * and nothing else.
 */
class FederatedScanExpressionTest {

    private static final UUID REPO_ID = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");

    private static RelationalDatabaseRepository repo(String engine, String password) {
        return new RelationalDatabaseRepository(
                REPO_ID, UUID.randomUUID(), UUID.randomUUID(),
                "warehouse", "", engine, "db.example.org", 5432, "research",
                "aran", password, "public", true);
    }

    @Test
    @DisplayName("postgres: a read-only ATTACH through a temporary secret; the expression names schema and table only")
    void postgresExpression() {
        // Not postgres_scan('<dsn>', …) any more: the expression is a src_ view's body, and DuckDB
        // returns every view body through its catalogue. The password now lives only in a secret
        // (which duckdb_secrets() redacts), and the extension quotes it for libpq itself — a space
        // or key=value in it has no DSN grammar to inject into (F-S1-5, watched on DuckDB 1.5.5).
        FederatedScan scan = FederatedScanExpression.forRepository(repo("POSTGRESQL", "s3cret"), "papers");
        assertThat(scan.expression())
                .isEqualTo("aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0.\"public\".\"papers\"");
        assertThat(scan.setupSql()).containsExactly(
                "INSTALL postgres",
                "LOAD postgres",
                "CREATE OR REPLACE TEMPORARY SECRET aran_pg_0f1e2d3c4b5a69788796a5b4c3d2e1f0"
                        + " (TYPE postgres, HOST 'db.example.org', PORT 5432, DATABASE 'research',"
                        + " USER 'aran', PASSWORD 's3cret')",
                "ATTACH IF NOT EXISTS '' AS aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0"
                        + " (TYPE postgres, SECRET aran_pg_0f1e2d3c4b5a69788796a5b4c3d2e1f0, READ_ONLY)");
    }

    @Test
    @DisplayName("mysql: a read-only ATTACH through a temporary secret; the expression is a bare three-part name")
    void mysqlScan() {
        FederatedScan scan = FederatedScanExpression.forRepository(repo("MYSQL", "s3cret"), "papers");
        assertThat(scan.expression())
                .isEqualTo("aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0.\"research\".\"papers\"");
        assertThat(scan.setupSql()).containsExactly(
                "INSTALL mysql",
                "LOAD mysql",
                "SET mysql_aggregate_pushdown_enabled=false",
                "CREATE OR REPLACE TEMPORARY SECRET aran_mysql_0f1e2d3c4b5a69788796a5b4c3d2e1f0"
                        + " (TYPE mysql, HOST 'db.example.org', PORT 5432, DATABASE 'research',"
                        + " USER 'aran', PASSWORD 's3cret')",
                "ATTACH IF NOT EXISTS '' AS aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0"
                        + " (TYPE mysql, SECRET aran_mysql_0f1e2d3c4b5a69788796a5b4c3d2e1f0, READ_ONLY)");
    }

    @Test
    @DisplayName("mariadb rides the mysql read")
    void mariadbUsesMysql() {
        assertThat(FederatedScanExpression.forRepository(repo("MARIADB", "p"), "t").setupSql())
                .isEqualTo(FederatedScanExpression.forRepository(repo("MYSQL", "p"), "t").setupSql());
    }

    @Test
    @DisplayName("engine matching is case-insensitive, as the inline code's equalsIgnoreCase was")
    void engineCaseInsensitive() {
        assertThat(FederatedScanExpression.forRepository(repo("postgresql", "p"), "t").extension())
                .isEqualTo("postgres");
    }

    @Test
    @DisplayName("an engine with no federated scanner yields null rather than a broken expression")
    void unsupportedEngine() {
        assertThat(FederatedScanExpression.forRepository(repo("ORACLE", "p"), "t")).isNull();
        assertThat(FederatedScanExpression.extensionFor("ORACLE")).isNull();
    }

    @Test
    @DisplayName("a quote in a password or table name is escaped, not injected")
    void escapesQuotes() {
        // Both engines: the password is a plain SQL literal in the secret, the table a quoted
        // identifier.
        FederatedScan postgres =
                FederatedScanExpression.forRepository(repo("POSTGRESQL", "pa'ss"), "ta\"ble");
        assertThat(postgres.setupSql()).anySatisfy(s -> assertThat(s).contains("PASSWORD 'pa''ss'"));
        assertThat(postgres.expression()).endsWith(".\"public\".\"ta\"\"ble\"");
        FederatedScan mysql = FederatedScanExpression.forRepository(repo("MYSQL", "pa'ss"), "ta\"ble");
        assertThat(mysql.setupSql()).anySatisfy(s -> assertThat(s).contains("PASSWORD 'pa''ss'"));
        assertThat(mysql.expression()).endsWith(".\"research\".\"ta\"\"ble\"");
    }

    @Test
    @DisplayName("the extension names match what the scans install")
    void extensionNames() {
        assertThat(FederatedScanExpression.extensionFor("POSTGRESQL")).isEqualTo("postgres");
        assertThat(FederatedScanExpression.extensionFor("MYSQL")).isEqualTo("mysql");
        assertThat(FederatedScanExpression.extensionFor("MARIADB")).isEqualTo("mysql");
        assertThat(FederatedScanExpression.forRepository(repo("MYSQL", "p"), "t").extension())
                .isEqualTo("mysql");
    }
}

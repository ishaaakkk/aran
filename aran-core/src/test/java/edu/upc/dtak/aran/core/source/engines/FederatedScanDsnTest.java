package edu.upc.dtak.aran.core.source.engines;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.FederatedScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a federated read's credentials live. Neither engine spells a DSN any more: both put the
 * credentials into a {@code CREATE SECRET} whose fields the extension quotes for its own client, so
 * the keyword-injection class (sec-audit F-S1-5 — a password of
 * {@code secret host=attacker sslmode=disable} redirecting the scan) has no connection-string
 * grammar left to inject into. What remains is the SQL literal, and a quote in it must not end it.
 *
 * <p>The other half is that the expression carries none of it: the expression becomes a
 * {@code src_} view's body, and DuckDB returns every view body through {@code duckdb_views()},
 * {@code information_schema.views}, {@code sqlite_master} and {@code pg_views}. A
 * {@code postgres_scan('dbname=… password=…', …)} body was one catalogue read away from any
 * client of that connection.
 */
class FederatedScanDsnTest {

    private static RelationalDatabaseRepository repo(String engine, String password) {
        return new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src", "desc", engine, "db.internal", 5432, "northwind",
                "aran", password, "public", true);
    }

    @Test
    @DisplayName("Postgres: the expression, and so every view body built from it, carries no credential")
    void postgresExpressionCarriesNoCredential() {
        FederatedScan scan = new PostgresEngine()
                .federatedScan(repo("POSTGRESQL", "s3cr3t-sentinel"), "t").orElseThrow();
        assertThat(scan.expression())
                .doesNotContain("s3cr3t-sentinel")
                .doesNotContain("password")
                .doesNotContain("db.internal")
                .doesNotContain("northwind");
        assertThat(scan.setupSql())
                .filteredOn(s -> !s.startsWith("CREATE OR REPLACE TEMPORARY SECRET"))
                .noneSatisfy(s -> assertThat(s).contains("s3cr3t-sentinel"));
    }

    @Test
    @DisplayName("Postgres: a keyword-injecting password is one SQL literal in the secret")
    void postgresPasswordIsASecretLiteral() {
        FederatedScan scan = new PostgresEngine()
                .federatedScan(repo("POSTGRESQL", "x' host=attacker sslmode=disable '"), "t")
                .orElseThrow();
        assertThat(scan.setupSql())
                .anySatisfy(s -> assertThat(s)
                        .contains("PASSWORD 'x'' host=attacker sslmode=disable ''')"));
        assertThat(scan.expression()).doesNotContain("attacker");
    }

    @Test
    @DisplayName("MySQL: no DSN at all — the password is one SQL literal in the secret, whatever it holds")
    void mysqlPasswordIsASecretLiteral() {
        var scan = new MySqlEngine()
                .federatedScan(repo("MYSQL", "x' host=attacker '"), "t")
                .orElseThrow();
        assertThat(scan.setupSql())
                .anySatisfy(s -> assertThat(s).contains("PASSWORD 'x'' host=attacker ''')"));
        assertThat(scan.expression()).doesNotContain("attacker");
        assertThat(scan.setupSql())
                .filteredOn(s -> !s.startsWith("CREATE OR REPLACE TEMPORARY SECRET"))
                .noneSatisfy(s -> assertThat(s).contains("attacker"));
    }

    @Test
    @DisplayName("a blank Postgres schema reads the default one rather than a zero-length identifier")
    void blankPostgresSchemaReadsPublic() {
        RelationalDatabaseRepository blank = new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src", "desc", "POSTGRESQL", "db.internal", 5432, "northwind",
                "aran", "p", " ", true);
        assertThat(new PostgresEngine().federatedScan(blank, "t").orElseThrow().expression())
                .endsWith(".\"public\".\"t\"");
    }
}

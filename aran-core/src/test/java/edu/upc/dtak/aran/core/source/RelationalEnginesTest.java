package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.RelationalEngine.BulkExtract;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The engine registry, and the capability matrix it publishes.
 *
 * <p>These tests are the executable form of the source-access capability table —
 * the design says Oracle cannot be federated and MySQL has no native lake extract, and here that
 * is asserted rather than asserted-in-prose.
 */
class RelationalEnginesTest {

    private static final RelationalEngines REGISTRY = RelationalEngines.defaults();

    private static RelationalDatabaseRepository repo(String engine) {
        return new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src", "", engine, "db.example.org", 5432, "warehouse",
                "aran", "s3cret", "public", true);
    }

    @Nested
    @DisplayName("the registry")
    class Registry {

        @ParameterizedTest
        @ValueSource(strings = {"POSTGRESQL", "MYSQL", "MARIADB", "ORACLE", "HBASE"})
        @DisplayName("ships the engines the registration form offers")
        void ships_the_offered_engines(String id) {
            assertThat(REGISTRY.find(id)).isPresent();
        }

        @Test
        @DisplayName("lookup is case-insensitive — repository rows are not normalised")
        void case_insensitive() {
            assertThat(REGISTRY.find("postgresql")).isPresent();
            assertThat(REGISTRY.find("PostgreSQL")).isPresent();
        }

        @Test
        @DisplayName("an unknown engine is empty, not an exception")
        void unknown_engine_is_empty() {
            // A repository row can name an engine a later build dropped. Throwing would take the
            // whole project's repository list down with it.
            assertThat(REGISTRY.find("COBOL")).isEmpty();
            assertThat(REGISTRY.find(null)).isEmpty();
        }

        @Test
        @DisplayName("two engines sharing an id fail fast rather than shadow each other")
        void duplicate_ids_rejected() {
            RelationalEngine dup = new RelationalEngine() {
                public String id() { return "POSTGRESQL"; }
                public String displayName() { return "Impostor"; }
                public int defaultPort() { return 1; }
                public String quoteIdentifier(String s) { return s; }
            };
            assertThatThrownBy(() -> new RelationalEngines(
                    List.of(new edu.upc.dtak.aran.core.source.engines.PostgresEngine(), dup)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("POSTGRESQL");
        }
    }

    @Nested
    @DisplayName("the capability matrix")
    class Capabilities {

        @ParameterizedTest
        @CsvSource({
                "POSTGRESQL, postgres",
                "MYSQL,      mysql",
                "MARIADB,    mysql",
        })
        @DisplayName("federatable engines name their DuckDB extension")
        void federatable(String id, String extension) {
            assertThat(REGISTRY.find(id).orElseThrow().duckDbExtension()).contains(extension);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ORACLE", "HBASE"})
        @DisplayName("an engine with no scanner declares no extension and no scan — the default")
        void not_federatable(String id) {
            RelationalEngine engine = REGISTRY.find(id).orElseThrow();

            assertThat(engine.duckDbExtension()).isEmpty();
            assertThat(engine.federatedScan(repo(id), "papers")).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({
                "POSTGRESQL, LAKE_NATIVE",
                "MYSQL,      JDBC_GENERIC",
                "MARIADB,    JDBC_GENERIC",
                "ORACLE,     JDBC_GENERIC",
                "HBASE,      NONE",
        })
        @DisplayName("each engine declares how, if at all, its rows reach the lake")
        void bulk_extract_matrix(String id, BulkExtract expected) {
            assertThat(REGISTRY.find(id).orElseThrow().bulkExtract()).isEqualTo(expected);
        }

        @Test
        @DisplayName("HBase claims nothing until somebody runs Phoenix against it")
        void hbase_claims_nothing() {
            // Phoenix IS a JDBC driver, so JDBC_GENERIC might work. Claiming a capability from a
            // driver's existence is how this list acquired four kinds that ingest nothing.
            assertThat(REGISTRY.find("HBASE").orElseThrow().bulkExtract()).isEqualTo(BulkExtract.NONE);
        }
    }

    @Nested
    @DisplayName("scan expressions and quoting")
    class Sql {

        @Test
        @DisplayName("Postgres reads an attached catalog: schema and table in the expression, no credential")
        void postgres_attached_catalog() {
            // The expression becomes a src_ view's body, and DuckDB hands every view body back
            // through duckdb_views()/information_schema.views — so the DSN a postgres_scan carried
            // was one catalogue read away from any client of that connection.
            RelationalDatabaseRepository repo = repo("POSTGRESQL");
            Optional<FederatedScan> scan = REGISTRY.find("POSTGRESQL").orElseThrow()
                    .federatedScan(repo, "papers");

            assertThat(scan).isPresent();
            assertThat(scan.get().attachAlias()).isEqualTo(FederatedScan.attachAlias(repo.id()));
            assertThat(scan.get().expression())
                    .isEqualTo(scan.get().attachAlias() + ".\"public\".\"papers\"")
                    .doesNotContain("s3cret")
                    .doesNotContain("warehouse");
            assertThat(scan.get().setupSql()).first().isEqualTo("INSTALL postgres");
            assertThat(scan.get().setupSql()).last().asString()
                    .startsWith("ATTACH IF NOT EXISTS '' AS " + scan.get().attachAlias())
                    .contains("TYPE postgres")
                    .endsWith("READ_ONLY)");
        }

        @Test
        @DisplayName("MySQL reads an attached catalog: the database is the schema, and the expression names no credential")
        void mysql_attached_catalog() {
            RelationalDatabaseRepository repo = repo("MYSQL");
            FederatedScan scan = REGISTRY.find("MYSQL").orElseThrow()
                    .federatedScan(repo, "papers").orElseThrow();

            assertThat(scan.attachAlias()).isEqualTo(FederatedScan.attachAlias(repo.id()));
            assertThat(scan.expression())
                    .isEqualTo(scan.attachAlias() + ".\"warehouse\".\"papers\"")
                    .doesNotContain("s3cret")
                    .doesNotContain("public");
            assertThat(scan.setupSql()).first().isEqualTo("INSTALL mysql");
            assertThat(scan.setupSql()).last().asString()
                    .startsWith("ATTACH IF NOT EXISTS '' AS " + scan.attachAlias())
                    .endsWith("READ_ONLY)");
        }

        @Test
        @DisplayName("MariaDB reuses MySQL's read rather than copying it")
        void mariadb_reuses_mysql() {
            RelationalDatabaseRepository repo = repo("MARIADB");
            FederatedScan maria = REGISTRY.find("MARIADB").orElseThrow()
                    .federatedScan(repo, "papers").orElseThrow();

            assertThat(maria.setupSql()).isEqualTo(REGISTRY.find("MYSQL").orElseThrow()
                    .federatedScan(repo, "papers").orElseThrow().setupSql());
        }

        @Test
        @DisplayName("a quote inside an identifier is escaped, not merely wrapped")
        void identifier_escaping() {
            // Legal in PostgreSQL, and an injection vector if only wrapped.
            assertThat(REGISTRY.find("POSTGRESQL").orElseThrow().quoteIdentifier("a\"b"))
                    .isEqualTo("\"a\"\"b\"");
            assertThat(REGISTRY.find("MYSQL").orElseThrow().quoteIdentifier("a`b"))
                    .isEqualTo("`a``b`");
        }

        @ParameterizedTest
        @CsvSource({
                "POSTGRESQL, \"reporting\".\"papers\"",
                "ORACLE,     \"reporting\".\"papers\"",
                "SQLSERVER,  [reporting].[papers]",
        })
        @DisplayName("a table is qualified with the repository's schema, in the engine's own quoting")
        void qualify_uses_the_engine_quoting(String id, String expected) {
            assertThat(REGISTRY.find(id).orElseThrow().qualify("reporting", "papers"))
                    .isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"MYSQL", "MARIADB"})
        @DisplayName("MySQL and MariaDB read the bare table: the database is the schema, and the Schema field is ignored")
        void mysql_family_ignores_the_schema(String id) {
            // RegisterRepositoryDialog pre-fills "public" for every engine, and both drivers ignore
            // the schemaPattern the scan passes, so a MySQL repository carrying "public" still
            // lists its tables. Qualifying with it read `public`.`orders` — a database that does
            // not exist — and failed every preview and import (watched on MySQL 8.4 and MariaDB 11).
            RelationalEngine engine = REGISTRY.find(id).orElseThrow();

            assertThat(engine.qualify("public", "orders")).isEqualTo("`orders`");
            assertThat(engine.qualify("reporting", "papers")).isEqualTo("`papers`");
            assertThat(engine.qualify("", "papers")).isEqualTo("`papers`");
            assertThat(engine.qualify(null, "papers")).isEqualTo("`papers`");
        }

        @Test
        @DisplayName("no schema means the bare quoted table, not an empty qualifier")
        void qualify_without_a_schema() {
            // "".papers is a syntax error, and repository rows carry "" rather than null for an
            // unset schema (EntityMapper normalises it), so both spellings must degrade the
            // same way.
            RelationalEngine postgres = REGISTRY.find("POSTGRESQL").orElseThrow();

            assertThat(postgres.qualify(null, "papers")).isEqualTo("\"papers\"");
            assertThat(postgres.qualify("", "papers")).isEqualTo("\"papers\"");
            assertThat(postgres.qualify("   ", "papers")).isEqualTo("\"papers\"");
        }

        @Test
        @DisplayName("both halves are escaped, so a hostile schema name cannot break out either")
        void qualify_escapes_both_parts() {
            assertThat(REGISTRY.find("POSTGRESQL").orElseThrow().qualify("a\"b", "c\"d"))
                    .isEqualTo("\"a\"\"b\".\"c\"\"d\"");
            assertThat(REGISTRY.find("SQLSERVER").orElseThrow().qualify("a]b", "c]d"))
                    .isEqualTo("[a]]b].[c]]d]");
            // MySQL drops the schema half, so only the table's escaping is left to check.
            assertThat(REGISTRY.find("MYSQL").orElseThrow().qualify("a`b", "c`d"))
                    .isEqualTo("`c``d`");
        }

        @Test
        @DisplayName("a password containing a quote cannot break out of the secret literal")
        void credentials_are_escaped() {
            RelationalDatabaseRepository hostile = new RelationalDatabaseRepository(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "src", "", "POSTGRESQL", "h", 5432, "db", "u", "pa'ss", "public", true);

            FederatedScan scan = REGISTRY.find("POSTGRESQL").orElseThrow()
                    .federatedScan(hostile, "t").orElseThrow();
            // The doubled quote keeps the password one SQL literal; the expression never sees it.
            assertThat(scan.setupSql()).anySatisfy(s -> assertThat(s).contains("PASSWORD 'pa''ss')"));
            assertThat(scan.expression()).doesNotContain("pa'ss").doesNotContain("pa''ss");
        }
    }
}

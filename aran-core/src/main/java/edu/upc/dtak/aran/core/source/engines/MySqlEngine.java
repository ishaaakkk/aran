package edu.upc.dtak.aran.core.source.engines;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.core.source.RelationalEngine;
import edu.upc.dtak.aran.core.util.Names;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * MySQL, and MariaDB by subclassing — the two share one wire protocol, and since 2026-09-24 one
 * live-watched read path (MySQL 8.4 and MariaDB 11, {@code FederatedMySqlBootstrapIT} /
 * {@code FederatedMariaDbBootstrapIT}).
 */
@NullMarked
public class MySqlEngine implements RelationalEngine {

    @Override
    public String id() {
        return "MYSQL";
    }

    @Override
    public String displayName() {
        return "MySQL";
    }

    @Override
    public int defaultPort() {
        return 3306;
    }

    /** Backticks, and a backtick inside an identifier is escaped by doubling it. */
    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /**
     * The bare table, whatever the repository's {@code schema} says: the database is the schema
     * here (as in {@link #federatedScan}), and the connection already has {@code repo.database()}
     * as its default through the JDBC URL template.
     *
     * <p>Qualifying with the schema field would read {@code `public`.`orders`}, since
     * {@code RegisterRepositoryDialog} pre-fills {@code public} for every engine, and that
     * database does not exist. The scan is no guide to what the field holds: Connector/J and
     * MariaDB Connector/J ignore the {@code schemaPattern} {@code getTables} is handed, so they
     * list the tables whatever it says. {@code AbstractFederatedMySqlFamilyIT} previews and
     * extracts such a repository against both servers.
     */
    @Override
    public String qualify(@Nullable String schema, String table) {
        return quoteIdentifier(table);
    }

    @Override
    public Optional<String> duckDbExtension() {
        return Optional.of("mysql");
    }

    /**
     * An attached catalog, not a table function: DuckDB 1.5.5's mysql extension has no
     * {@code mysql_scan}, and its connection-string grammar is not the libpq one the old DSN was
     * written in. Both failed against a live MySQL (V52). The credentials go into a
     * {@code CREATE SECRET}, so the extension quotes them for its own client itself and no DSN is
     * ever spelled here; the expression names the attached table and carries no credential.
     *
     * <p>The database is the schema: the repository's {@code schema} field is not consulted, as
     * it never was for MySQL. The three-part name is deliberate — a two-part {@code alias.table}
     * would also read as {@code schema.table} in whichever catalog is current.
     *
     * <p>{@code mysql_aggregate_pushdown_enabled=false}: with it on, DuckDB 1.5.5 fails any
     * aggregate over a <em>view</em> of an attached MySQL table with an internal binder error
     * ("Failed to bind column reference"), and every Aran read is through a view — the
     * {@code src_}/{@code w_}/{@code int_} stack, verify-wrapper's {@code COUNT(*)} fingerprint,
     * freyja's {@code snapshot}. Filter and projection pushdown are unaffected. Pinned by the
     * aggregate test in {@code AbstractFederatedMySqlFamilyIT}.
     */
    @Override
    public Optional<FederatedScan> federatedScan(RelationalDatabaseRepository repo, String table) {
        String alias = FederatedScan.attachAlias(repo.id());
        String secret = "aran_mysql_" + repo.id().toString().replace("-", "");
        return Optional.of(FederatedScan.attachedCatalog("mysql", alias,
                alias + "." + Names.sqlIdentifier(repo.database()) + "." + Names.sqlIdentifier(table),
                List.of(
                        "SET mysql_aggregate_pushdown_enabled=false",
                        // TEMPORARY is the default today; spelled out because a PERSISTENT
                        // secret would write the password under ~/.duckdb.
                        "CREATE OR REPLACE TEMPORARY SECRET " + secret + " (TYPE mysql"
                                + ", HOST '" + literal(repo.host()) + '\''
                                + ", PORT " + repo.port()
                                + ", DATABASE '" + literal(repo.database()) + '\''
                                + ", USER '" + literal(repo.username()) + '\''
                                + ", PASSWORD '" + literal(repo.password()) + "')",
                        "ATTACH IF NOT EXISTS '' AS " + alias
                                + " (TYPE mysql, SECRET " + secret + ", READ_ONLY)")));
    }

    private static String literal(@Nullable String value) {
        return Names.sqlLiteral(value == null ? "" : value);
    }

    /** No native lake path; the generic JDBC extract is what MySQL gets. */
    @Override
    public BulkExtract bulkExtract() {
        return BulkExtract.JDBC_GENERIC;
    }
}

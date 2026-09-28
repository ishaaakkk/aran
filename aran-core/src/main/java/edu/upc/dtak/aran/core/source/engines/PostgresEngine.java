package edu.upc.dtak.aran.core.source.engines;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.CatalogHarvestQueries;
import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.core.source.RelationalEngine;
import edu.upc.dtak.aran.core.util.Names;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** PostgreSQL: the only engine with both access modes today. */
@NullMarked
public final class PostgresEngine implements RelationalEngine {

    @Override
    public String id() {
        return "POSTGRESQL";
    }

    @Override
    public String displayName() {
        return "PostgreSQL";
    }

    @Override
    public int defaultPort() {
        return 5432;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public Optional<String> duckDbExtension() {
        return Optional.of("postgres");
    }

    /**
     * An attached catalog, the shape {@link MySqlEngine} already reads through — not
     * {@code postgres_scan('<dsn>', …)}. The expression becomes a {@code src_} view's body, and
     * DuckDB hands every view body back through {@code duckdb_views()},
     * {@code information_schema.views}, {@code sqlite_master} and {@code pg_views}, so a DSN in it
     * was the source's password one catalogue read away from any client of that connection. Here
     * the credentials go into a {@code CREATE SECRET} ({@code duckdb_secrets()} redacts the
     * password), the database is attached {@code READ_ONLY} under the reserved
     * {@link FederatedScan#attachAlias} — which the SQL screen refuses in every client statement —
     * and the expression is a three-part name that carries no credential.
     *
     * <p>No DSN is spelled here: the extension quotes each secret field for libpq itself, so the
     * keyword-injection class (sec-audit F-S1-5, a password of {@code x host=attacker}) has no
     * connection-string grammar to inject into — watched on DuckDB 1.5.5 with a password holding a
     * quote, a backslash and {@code host=}. What remains is the SQL literal, and
     * {@link Names#sqlLiteral} keeps a quote from ending it.
     *
     * <p>A blank schema reads {@code public}, the default a connection's search path starts with;
     * {@code postgres_scan} with an empty schema failed outright ("does not contain any columns").
     */
    @Override
    public Optional<FederatedScan> federatedScan(RelationalDatabaseRepository repo, String table) {
        String alias = FederatedScan.attachAlias(repo.id());
        String secret = "aran_pg_" + repo.id().toString().replace("-", "");
        String schema = repo.schema() == null || repo.schema().isBlank() ? "public" : repo.schema();
        return Optional.of(FederatedScan.attachedCatalog("postgres", alias,
                alias + "." + Names.sqlIdentifier(schema) + "." + Names.sqlIdentifier(table),
                List.of(
                        // TEMPORARY is the default today; spelled out because a PERSISTENT
                        // secret would write the password under ~/.duckdb.
                        "CREATE OR REPLACE TEMPORARY SECRET " + secret + " (TYPE postgres"
                                + ", HOST '" + literal(repo.host()) + '\''
                                + ", PORT " + repo.port()
                                + ", DATABASE '" + literal(repo.database()) + '\''
                                + ", USER '" + literal(repo.username()) + '\''
                                + ", PASSWORD '" + literal(repo.password()) + "')",
                        "ATTACH IF NOT EXISTS '' AS " + alias
                                + " (TYPE postgres, SECRET " + secret + ", READ_ONLY)")));
    }

    private static String literal(@Nullable String value) {
        return Names.sqlLiteral(value == null ? "" : value);
    }

    /**
     * The lake pulls Postgres natively via asyncpg, reading declared types off a prepared
     * statement before any row moves. Better than the generic JDBC path, so it keeps it.
     */
    @Override
    public BulkExtract bulkExtract() {
        return BulkExtract.LAKE_NATIVE;
    }

    /** {@code pg_class}/{@code pg_stats}/{@code information_schema} — see the implementation's javadoc. */
    @Override
    public Optional<CatalogHarvestQueries> catalogHarvestQueries() {
        return Optional.of(new PostgresCatalogHarvestQueries());
    }
}

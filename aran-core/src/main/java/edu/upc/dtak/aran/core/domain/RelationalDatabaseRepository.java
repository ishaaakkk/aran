package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A relational database repository accessible via JDBC.
 */
@NullMarked
public record RelationalDatabaseRepository(
        UUID id,
        UUID projectId,
        UUID tenantId,
        String name,
        String description,
        String engine,      // e.g. "POSTGRESQL", "MYSQL", "ORACLE"
        String host,
        int port,
        String database,
        String username,
        String password,
        String schema,
        boolean federated
) implements DataRepository {

    // host and database are spliced verbatim into the JDBC URL template
    // (jdbc:mysql://{host}:{port}/{database}) and into the Postgres and MySQL
    // secrets (whose shape freyja's allowlist mirrors from these two patterns), so a
    // value carrying URL-property or connection-string syntax injects live driver options — the
    // sec-audit F-S1-3/F-MALSRC-1/F-S1-5 vector, e.g. database=db?allowLoadLocalInfile=true, which
    // turns a rogue MySQL server into a local-file read of the Aran host. A strict allowlist is the
    // screen: no '?', '&', ';', '=', whitespace, quotes, slash or backslash reaches the template.
    // host additionally admits ':' and '[' ']' for bracketed IPv6.
    private static final java.util.regex.Pattern SAFE_HOST =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._:\\[\\]-]+$");
    private static final java.util.regex.Pattern SAFE_DATABASE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._-]+$");

    public RelationalDatabaseRepository {
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("engine must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database name must not be blank");
        }
        if (!SAFE_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException(
                    "host contains characters not allowed in a connection target");
        }
        if (!SAFE_DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException(
                    "database name contains characters not allowed in a connection target");
        }
    }

    @Override
    public RepositoryType type() {
        return RepositoryType.RELATIONAL_DB;
    }
}

package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.source.DuckDbS3Config;
import edu.upc.dtak.aran.core.source.FederatedScan;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The credential-bearing setup a DuckDB connection needs <em>before</em> a federated query can
 * run: {@code httpfs} installed/loaded once plus one {@code CREATE SECRET} per distinct
 * object-storage repository the query touches, and one secret + read-only {@code ATTACH} per
 * distinct relational (Postgres or MySQL-family) repository.
 *
 * <h2>Why this carrier exists</h2>
 *
 * <p>The read expression a query executes — {@code read_parquet('s3://bucket/key')}, or an
 * attached catalog's {@code aran_attach_….schema.table} — carries no credential and is safe to
 * show in a plan or DDL. The credential lives in a separate {@code CREATE SECRET} that must exist
 * on the connection first. Only the query-engine resolver has the repository (and thus its keys),
 * but the resolver produces <em>strings</em>, not a live connection; {@link DuckDbExecutor} owns
 * the connection but sees only DDL text, never the keys. So the resolver fills this carrier as a
 * side effect of resolving each dataset (the same way it appends to its temp-file list), and the
 * executor runs it during {@link DuckDbExecutor#prepare()}.
 *
 * <p>A single query can join datasets from several repositories, so the secrets are
 * de-duplicated by name (one per repository) and each is scoped to its bucket — DuckDB routes an
 * {@code s3://} read to the secret whose scope is the longest matching prefix, letting distinct
 * repositories coexist on one connection. Attached catalogs de-duplicate by their alias, which is
 * per repository too.
 *
 * <h2>Credential hygiene</h2>
 *
 * <p>The secret-bearing {@code CREATE SECRET} string is built inside {@link #applyTo} (or held
 * inside a {@link FederatedScan}) and executed immediately; it is never returned or logged.
 * {@link #toString} reports only counts, so a carrier landing in a log line or an exception
 * cannot leak. If binding a secret fails, {@link #applyTo} raises an exception naming only the
 * bucket scope or the statement's index and keyword — never the statement, never a secret
 * statement's driver message (a DuckDB secret error can echo option values). This mirrors
 * {@code ObjectStorageBootstrap}'s handling of the same statement.
 */
@NullMarked
final class ConnectionSetup {

    /** repository config carrying the credentials, plus the bucket scope, keyed by secret name. */
    private record Secret(DuckDbS3Config config, @Nullable String scope) {}

    /** A bearer token for httpfs reads of {@code http(s)://} locations (Gap 2: an Iceberg table
     * whose metadata another catalog serves over HTTP), plus its URL-prefix scope. */
    private record HttpSecret(String bearerToken, @Nullable String scope) {}

    // Insertion-ordered and keyed by secret name so the same repository referenced twice collapses
    // to one CREATE SECRET, and the order is deterministic for tests and for the connection.
    private final Map<String, Secret> secretsByName = new LinkedHashMap<>();
    private final Map<String, HttpSecret> httpSecretsByName = new LinkedHashMap<>();
    private final Map<String, FederatedScan> attachmentsByAlias = new LinkedHashMap<>();

    /**
     * Register the {@code httpfs} + S3-secret setup for one object-storage repository. Idempotent
     * per {@code secretName}: a repository referenced by several datasets is added once.
     *
     * @param config     the repository's S3 config (the credential source; never logged)
     * @param secretName the DuckDB secret name — unique per repository
     * @param scope      the {@code s3://<bucket>} prefix this secret authenticates, or null/blank
     */
    void addObjectStorageSecret(DuckDbS3Config config, String secretName, @Nullable String scope) {
        secretsByName.putIfAbsent(secretName, new Secret(config, scope));
    }

    /**
     * Register a bearer token for httpfs reads of {@code http(s)://} locations under {@code scope}
     * (Gap 2). Same idempotency and hygiene contract as the S3 form: the token never appears in a
     * returned string, a log line, or an exception.
     */
    void addHttpBearerSecret(String bearerToken, String secretName, @Nullable String scope) {
        httpSecretsByName.putIfAbsent(secretName, new HttpSecret(bearerToken, scope));
    }

    /**
     * Register the setup of a scan that reads an attached catalog (Postgres, MySQL, MariaDB) — its
     * extension, settings, secret and {@code ATTACH}. Idempotent per alias, i.e. per repository.
     * A table-function scan has nothing to attach and is ignored: its extension is loaded from
     * the DDL by {@link DuckDbExecutor#ensureFederationExtensions}.
     */
    void addAttachedCatalog(FederatedScan scan) {
        String alias = scan.attachAlias();
        if (alias != null) {
            attachmentsByAlias.putIfAbsent(alias, scan);
        }
    }

    boolean isEmpty() {
        return secretsByName.isEmpty() && httpSecretsByName.isEmpty() && attachmentsByAlias.isEmpty();
    }

    /**
     * Run the setup on {@code stmt}'s connection: {@code INSTALL httpfs; LOAD httpfs;} once when an
     * S3 or HTTP secret is registered, then each {@code CREATE SECRET}, then each attached
     * catalog's own setup. No-op when empty.
     *
     * <p><b>Never log a statement executed here.</b> The httpfs statements are safe; the
     * {@code CREATE SECRET} statements carry the secret key, so a failure is re-raised naming only
     * the bucket scope, or the index and keyword of the attached catalog's failing statement.
     */
    void applyTo(Statement stmt) throws SQLException {
        if (!secretsByName.isEmpty() || !httpSecretsByName.isEmpty()) {
            // httpfs load is credential-free and identical for every repo — reuse the one
            // authority (DuckDbS3Config) rather than re-spelling the statements here. The
            // http-bearer case has no stored S3 config to take them from, so a scratch one
            // supplies the same two statements.
            DuckDbS3Config anyConfig = secretsByName.isEmpty()
                    ? new DuckDbS3Config("S3", null, null, null, null)
                    : secretsByName.values().iterator().next().config();
            for (String ext : anyConfig.installAndLoadHttpfs()) {
                stmt.execute(ext);
            }
        }
        for (Map.Entry<String, Secret> e : secretsByName.entrySet()) {
            Secret s = e.getValue();
            // Built here and dropped after execute — the secret-bearing string never lives in a
            // field, a return value, or a log line.
            String createSecret = s.config().createSecretSql(e.getKey(), s.scope());
            try {
                stmt.execute(createSecret);
            } catch (SQLException ex) {
                // Neither the statement nor ex.getMessage() (which can echo the secret) is surfaced
                // — only the bucket scope, which is not a credential.
                throw new SQLException("failed to bind S3 credentials for scope '" + s.scope() + "'");
            }
        }
        for (Map.Entry<String, HttpSecret> e : httpSecretsByName.entrySet()) {
            HttpSecret s = e.getValue();
            // One authority for the statement text (DuckDbHttpSecret) — built here and dropped
            // after execute, same discipline as the S3 form above.
            String createSecret = edu.upc.dtak.aran.core.source.DuckDbHttpSecret
                    .createSecretSql(e.getKey(), s.bearerToken(), s.scope());
            try {
                stmt.execute(createSecret);
            } catch (SQLException ex) {
                // Same rule as above: the statement and the driver's message can carry the token.
                throw new SQLException(
                        "failed to bind HTTP bearer credentials for scope '" + s.scope() + "'");
            }
        }
        for (Map.Entry<String, FederatedScan> e : attachmentsByAlias.entrySet()) {
            List<String> setup = e.getValue().setupSql();
            for (int i = 0; i < setup.size(); i++) {
                try {
                    stmt.execute(setup.get(i));
                } catch (SQLException ex) {
                    throw new SQLException("failed to attach live source '" + e.getKey() + "': "
                            + FederatedScan.setupFailureMessage(i, setup.get(i), ex.getMessage()));
                }
            }
        }
    }

    /** Redacts everything but counts. See the class-level credential-hygiene note. */
    @Override
    public String toString() {
        return "ConnectionSetup[secrets=" + secretsByName.size()
                + ", httpSecrets=" + httpSecretsByName.size()
                + ", attachedCatalogs=" + attachmentsByAlias.size()
                + ", httpfs=" + !(secretsByName.isEmpty() && httpSecretsByName.isEmpty()) + ']';
    }
}

package edu.upc.dtak.aran.core.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One federated table's live read: the {@link #expression()} a view selects from, plus the
 * ordered {@link #setupSql()} its DuckDB connection must run first. The query resolver, the
 * verify-wrapper binding, the drift probe and federated profiling all bind this one value, so
 * none of them can connect somewhere the others do not.
 *
 * <h2>Two shapes</h2>
 *
 * <ul>
 *   <li><b>Attached catalog</b> (Postgres, MySQL, MariaDB — every engine today): the credentials
 *       go into a {@code CREATE SECRET}, the database is attached {@code READ_ONLY} under an alias
 *       from {@link #attachAlias(UUID)}, and the expression is a plain
 *       {@code alias."schema"."table"} reference that carries no credential — so no plan, DDL,
 *       explain output, driver error or <em>view body</em> built from it can either. The last
 *       one is why Postgres moved here: an expression becomes a {@code src_} view's body, and
 *       DuckDB returns every view body through {@code duckdb_views()} and its compatibility
 *       catalogues. (MySQL had no choice: DuckDB 1.5.5's mysql extension registers no
 *       {@code mysql_scan} at all.)</li>
 *   <li><b>Table function</b> ({@code <ext>_scan('dsn', …)}): the expression is the whole
 *       binding and the setup is only the scanner extension. No engine emits one any more, and
 *       one that carries a credential must not: the credential would ride into every view body
 *       built over it.</li>
 * </ul>
 *
 * <h2>The reserved alias prefix</h2>
 *
 * <p>An attached catalog is a standing capability on its connection: any statement that runs
 * there can name it, and through it read every table the repository's account can see — not
 * only the enrolled one. So every alias starts with {@link #ATTACH_ALIAS_PREFIX}, which
 * {@code WrapperSqlValidator.findForbiddenConstruct} refuses in any statement it screens, and
 * which {@code DuckDbExecutor.isFederated} reads as "this read reaches the network".
 *
 * <h2>Credential hygiene</h2>
 *
 * <p>{@link #setupSql()} carries the password of an attached catalog; a table-function
 * {@link #expression()} would carry a DSN. Execute them and discard them — never log them, never put
 * them in an exception. {@link #toString()} redacts to counts, and
 * {@link #setupFailureMessage} is the one way to report a setup statement that failed.
 */
@NullMarked
public final class FederatedScan {

    /**
     * The prefix of every catalog alias an attached-catalog scan creates. Reserved: a screened
     * statement naming an identifier that starts with it is refused.
     */
    public static final String ATTACH_ALIAS_PREFIX = "aran_attach_";

    private static final Pattern ALIAS = Pattern.compile(
            Pattern.quote(ATTACH_ALIAS_PREFIX) + "[a-z0-9_]+");

    private final String extension;
    private final String expression;
    private final List<String> connectionSql;
    private final @Nullable String attachAlias;

    private FederatedScan(String extension, String expression, List<String> connectionSql,
                          @Nullable String attachAlias) {
        this.extension = extension;
        this.expression = expression;
        this.connectionSql = List.copyOf(connectionSql);
        this.attachAlias = attachAlias;
    }

    /** A table-function scan whose only setup is loading {@code extension}. */
    public static FederatedScan tableFunction(String extension, String expression) {
        return new FederatedScan(extension, expression, List.of(), null);
    }

    /**
     * A scan over a catalog {@code connectionSql} attaches as {@code alias}.
     *
     * @param connectionSql the statements after the extension load, ending with the
     *                      {@code ATTACH}; may carry credentials
     */
    public static FederatedScan attachedCatalog(String extension, String alias, String expression,
                                                List<String> connectionSql) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("an attach alias must be " + ATTACH_ALIAS_PREFIX
                    + " followed by lower-case letters, digits and underscores");
        }
        if (connectionSql.isEmpty()) {
            throw new IllegalArgumentException("an attached-catalog scan needs its ATTACH");
        }
        return new FederatedScan(extension, expression, connectionSql, alias);
    }

    /**
     * The alias a repository's attached catalog lives under — derived from its id, so every
     * dataset of one repository shares one attachment on a connection and two repositories never
     * collide.
     */
    public static String attachAlias(UUID repositoryId) {
        return ATTACH_ALIAS_PREFIX + repositoryId.toString().replace("-", "");
    }

    /** The DuckDB extension the scan needs ({@code postgres}, {@code mysql}). */
    public String extension() {
        return extension;
    }

    /** What a view selects from. May carry a DSN for a table-function scan. */
    public String expression() {
        return expression;
    }

    /**
     * Every statement the connection must run before {@link #expression()} can bind, in order:
     * the extension's {@code INSTALL}/{@code LOAD}, then — for an attached catalog — its
     * settings, secret and {@code ATTACH}. May carry credentials.
     */
    public List<String> setupSql() {
        List<String> out = new ArrayList<>(2 + connectionSql.size());
        out.add("INSTALL " + extension);
        out.add("LOAD " + extension);
        out.addAll(connectionSql);
        return List.copyOf(out);
    }

    /** The alias this scan attaches, or {@code null} for a table-function scan. */
    public @Nullable String attachAlias() {
        return attachAlias;
    }

    /**
     * The message to raise when setup statement {@code index} failed with {@code driverMessage}.
     *
     * <p>A {@code CREATE SECRET}'s driver message is withheld entirely: DuckDB's parser errors
     * print the statement after the first line and can quote the offending token on it, and that
     * statement holds the password. Every other setup statement carries no credential — an
     * {@code ATTACH} names its secret, not its contents — so the first line of the driver's
     * message survives, which is what tells a user "Access denied" from "Unknown server host".
     */
    public static String setupFailureMessage(int index, String statement,
                                             @Nullable String driverMessage) {
        String trimmed = statement.strip();
        String keyword = trimmed.isEmpty() ? "?" : trimmed.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        String head = "federated setup statement " + index + " (" + keyword + ") failed";
        if (isSecretStatement(trimmed) || driverMessage == null || driverMessage.isBlank()) {
            return head;
        }
        return head + ": " + driverMessage.strip().lines().findFirst().orElse("");
    }

    private static boolean isSecretStatement(String statement) {
        String upper = statement.toUpperCase(Locale.ROOT);
        return upper.startsWith("CREATE") && upper.contains("SECRET");
    }

    /** Redacts everything but counts. See the class-level credential-hygiene note. */
    @Override
    public String toString() {
        return "FederatedScan[extension=" + extension + ", setupStatements="
                + (2 + connectionSql.size()) + ", attached=" + (attachAlias != null) + ']';
    }
}

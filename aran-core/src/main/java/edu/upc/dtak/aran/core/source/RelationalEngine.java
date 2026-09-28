package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One relational engine, and what Aran can actually do with it.
 *
 * <h2>Every capability defaults to absent</h2>
 *
 * <p>That is the load-bearing decision. A new engine implements the four required methods and
 * overrides only what it can genuinely do; anything left alone degrades to an honest "no" rather
 * than to a failure at query time. The arrangement this replaces failed the other way —
 * {@code JdbcConnector} declares a binding strategy nothing implements, and the user finds out
 * when a query says "No snapshot recorded for dataset".
 *
 * <h2>Adding an engine</h2>
 *
 * <ol>
 *   <li>Add its {@code DriverMetadata} to {@code JdbcDriverRegistry} — coordinates, driver class,
 *       URL template, and the SHA-256 of the JAR.</li>
 *   <li>Implement this interface as a {@code @Component}.</li>
 * </ol>
 *
 * <p>There is no step 3. If a future engine needs one, this interface is missing a method — that
 * is the acceptance test for the design.
 */
@NullMarked
public interface RelationalEngine {

    /** Identity, as stored in {@link RelationalDatabaseRepository#engine()}. Upper case. */
    String id();

    /** What a form should call it. */
    String displayName();

    /** The port to prefill when a user picks this engine. */
    int defaultPort();

    /**
     * How this engine spells a quoted identifier — {@code "x"} for most, {@code `x`} for MySQL.
     *
     * <p>Implementations must also escape the quote character itself. A table called {@code a"b}
     * is legal in PostgreSQL and is an injection vector if it is only wrapped rather than escaped.
     */
    String quoteIdentifier(String identifier);

    /**
     * How this engine spells {@code schema.table} — the only place a relation name is turned into
     * SQL, so that every path that reads rows reads the same relation the scan offered.
     *
     * <p>A repository's {@code schema} scopes what {@code listAvailableDatasets} lists (it is the
     * {@code schemaPattern} handed to {@code DatabaseMetaData.getTables}), so the table name a user
     * picks is only unique <em>within that schema</em>. Reading it back unqualified resolves it
     * against the connection's default search path instead: with a same-named relation there the
     * wrong rows are previewed and imported silently, and with none the read fails outright — S2-7,
     * which is why this method exists rather than three call sites each calling
     * {@link #quoteIdentifier} on the table alone.
     *
     * <p>A blank or absent schema yields the bare quoted table: {@code "".foo} is a syntax error,
     * and an unset schema is stored as {@code ""} rather than null.
     *
     * <p>An engine with no schema level between the connection's database and its tables
     * overrides this to ignore {@code schema}. That is {@code MySqlEngine}, and MariaDB through
     * it: there the database is the schema, the drivers ignore the {@code schemaPattern} too, and
     * the {@code public} the registration form pre-fills names a database that does not exist.
     */
    default String qualify(@Nullable String schema, String table) {
        return schema == null || schema.isBlank()
                ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    // ---- capabilities: absent unless overridden ----

    /**
     * The DuckDB extension a federated scan needs ({@code postgres}, {@code mysql}), when this
     * engine has one.
     */
    default Optional<String> duckDbExtension() {
        return Optional.empty();
    }

    /**
     * The live read for one table — expression plus connection setup — when this engine can be
     * federated.
     *
     * <p>Implementations own their own credential-string assembly, and it must stay the single
     * copy: two would let the drift probe connect somewhere subtly different from where queries
     * go, and then report no drift forever.
     */
    default Optional<FederatedScan> federatedScan(RelationalDatabaseRepository repo, String table) {
        return Optional.empty();
    }

    /** How, if at all, this engine's rows can be copied into the lake. */
    default BulkExtract bulkExtract() {
        return BulkExtract.NONE;
    }

    /**
     * The SQL (and how to read it back) for a live catalog harvest — approximate row counts,
     * approximate column NDV, and declared foreign keys — when this engine has a catalog that
     * offers them. Absent by default, the same shape as {@link #duckDbExtension()}: a harvest is
     * an optimisation input, and an engine that cannot offer one degrades to {@link Harvest#EMPTY}
     * rather than a caller ever needing to know which engines can.
     *
     * <p>This capability is deliberately pure — see {@link CatalogHarvestQueries}'s javadoc for
     * why building the SQL and parsing its result stay engine-specific knowledge in aran-core,
     * while actually opening a connection and running it lives in aran-operators/bootstrapping.
     */
    default Optional<CatalogHarvestQueries> catalogHarvestQueries() {
        return Optional.empty();
    }

    /**
     * How a snapshot of this engine's rows is produced. The two mechanisms are not equivalent, so
     * the capability names which one rather than answering yes/no.
     */
    enum BulkExtract {

        /** No path. A repository can be registered and introspected; a dataset cannot be filled. */
        NONE,

        /**
         * {@code aran-lake} pulls it natively. Streaming, batched, and type-faithful — it reads the
         * declared schema off a prepared statement before a row moves and keeps {@code NUMERIC} as
         * text to avoid precision loss. PostgreSQL only, and deliberately not replaced by the
         * generic path: substituting a worse mechanism for a uniform one is a downgrade wearing a
         * refactor's clothes.
         */
        LAKE_NATIVE,

        /**
         * Aran reads it over JDBC and writes parquet. Works for any driver, with declared SQL types
         * from {@code ResultSetMetaData} — better than inferring from JSON, worse than
         * {@link #LAKE_NATIVE}'s hand-written map.
         */
        JDBC_GENERIC;

        /**
         * Whether this build can actually perform the mechanism, as opposed to an engine merely
         * naming the one it would use.
         *
         * <p>The two are not the same thing and conflating them is how {@code MATERIALIZE_TO_LAKE}
         * came to be declared by a connector while nothing implemented it — a capability the user
         * discovered was imaginary at query time. So this stays a separate question from
         * {@link RelationalEngine#bulkExtract()}, and a mechanism only answers yes here once a
         * caller actually invokes it: {@link #JDBC_GENERIC} flipped when
         * {@code DatasetService.registerDatasetByJdbcExtract} started calling
         * {@code TableExtractor}.
         *
         * <p>{@link #NONE} is not "unimplemented" — it is an engine truthfully saying it has no
         * extract at all, which is why it answers false alongside a mechanism that is merely
         * unbuilt. The two are indistinguishable to a caller, and should be: neither can run.
         */
        public boolean isImplemented() {
            return this == LAKE_NATIVE || this == JDBC_GENERIC;
        }
    }
}

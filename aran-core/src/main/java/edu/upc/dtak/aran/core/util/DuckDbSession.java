package edu.upc.dtak.aran.core.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.jspecify.annotations.NullMarked;

/**
 * The only place this platform opens a DuckDB connection — and the reason it is a single place:
 * every session is pinned to <b>UTC</b> before it runs anything.
 *
 * <h2>Why the pin</h2>
 *
 * <p>DuckDB resolves {@code TIMESTAMPTZ} casts and {@code TIMESTAMP} → {@code TIMESTAMPTZ}
 * promotion against the session's {@code TimeZone} setting, which defaults to the host's zone.
 * Unpinned, {@code CAST('2020-06-01 12:00:00+02:00' AS TIMESTAMPTZ)} renders
 * {@code 2020-06-01 12:00:00+02} on a laptop in Madrid and {@code 2020-06-01 10:00:00+00} in a
 * container in UTC — the same query, two answers. <b>It decides row membership, not only
 * rendering</b>: comparing a stored {@code TIMESTAMPTZ} (what {@code postgres_scan} returns for a
 * {@code timestamptz} column, and what a parquet {@code isAdjustedToUTC} column reads as) with a
 * naive {@code TIMESTAMP} literal promotes the literal in the session zone, so
 * {@code TIMESTAMPTZ '2020-06-01 10:00:00+00' = TIMESTAMP '2020-06-01 10:00:00'} is false in
 * Madrid and true in UTC — the same {@code WHERE} clause, different rows. The concrete damage is
 * that a wrapper-verify fingerprint
 * ({@code COUNT(*)} plus an order-insensitive row hash) computed on a developer's machine
 * disagrees with the one computed in the container, and the wrapper is demoted to {@code OPAQUE}
 * for a reason that has nothing to do with the wrapper.
 *
 * <p>This is decision D3's pre-tag line: "{@code SET
 * TimeZone='UTC'} on every DuckDB session (engine, product SQL, verify, cursor, freyja, the
 * lake)". The two Python sidecars pin their own sessions the same way — see
 * {@code freyja/executors/duckdb_session.py} and
 * {@code aran_lake.query.duckdb_session} — because a process boundary does not make the zone
 * agree by itself.
 *
 * <h2>Why the pin must happen at open</h2>
 *
 * <p>Both lockdown sequences ({@code DuckDbExecutor.lockDownFilesystem} and
 * {@code DatasetService.lockDownFilesystem}) end with {@code SET lock_configuration=true}, after
 * which no {@code SET} is accepted. A pin applied later in a connection's life would silently do
 * nothing on exactly the paths that execute user SQL.
 *
 * <h2>The invariant, and what enforces it</h2>
 *
 * <p>No other main-source file may name the JDBC URL. What proves it is not this paragraph but
 * {@code DuckDbSessionArchitectureTest}, which walks every {@code src/main/java} tree and fails
 * on any other file containing {@code jdbc:duckdb} — run the same check by hand with:
 *
 * <pre>{@code LC_ALL=C grep -rn "jdbc:duckdb" --include="*.java" aran-core aran-operators \
 *     aran-application aran-adapters}</pre>
 *
 * <p>This class deliberately holds no policy beyond the pin: no budget, no memory limit, no
 * lockdown. D4 sends those, and this URL with them, to a new bottom module after the tag ("put
 * the DuckDB factory in core" is on its <em>do not</em> list, and core's own build file records
 * the same intent as H-6). Keeping the pin to one file now is what makes that a one-file move
 * rather than a nineteen-site sweep repeated.
 *
 * <p>It also holds nothing DuckDB-typed: {@code java.sql} plus a {@code Class.forName}, exactly
 * as the operator modules do it, so no consumer gains a compile-time dependency on the driver.
 */
@NullMarked
public final class DuckDbSession {

    /** In-memory, private to the connection. No main-source file may spell this out again. */
    private static final String URL = "jdbc:duckdb:";

    private static final String DRIVER = "org.duckdb.DuckDBDriver";

    private DuckDbSession() {}

    /**
     * Open an in-memory DuckDB connection pinned to UTC.
     *
     * <p>The driver class is loaded first: several callers reach DuckDB with no compile-time
     * dependency on it, so {@code DriverManager} has nothing registered until this happens.
     *
     * @throws SQLException if the driver is not on the classpath, or the connection or the pin
     *                      fails — the connection is closed before this is raised, so a failed
     *                      pin cannot leave an unpinned session in a caller's hands
     */
    public static Connection open() throws SQLException {
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
            throw new SQLException("DuckDB driver not on the classpath", e);
        }
        Connection conn = DriverManager.getConnection(URL);
        try (Statement st = conn.createStatement()) {
            st.execute("SET TimeZone='UTC'");
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return conn;
    }
}

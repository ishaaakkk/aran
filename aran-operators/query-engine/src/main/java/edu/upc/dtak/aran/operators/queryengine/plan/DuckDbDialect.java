package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.DuckDBSqlDialect;

/**
 * Thin override of Calcite's {@link DuckDBSqlDialect}.
 *
 * <p>Calcite 1.41.0's {@code DuckDBSqlDialect} does not override offset/fetch unparsing, so it
 * falls back to the SQL-standard {@code FETCH NEXT n ROWS ONLY} clause. DuckDB does not accept
 * that syntax for a bare LIMIT (no OFFSET) in this Calcite/DuckDB combination; it expects
 * {@code LIMIT n [OFFSET m]}. This override restores that behaviour without touching anything
 * else the base dialect gets right (identifier quoting, etc).
 *
 * <p>The base {@code SqlDialect.supportsCharSet()} returns {@code true} (CHARACTER SET is
 * emitted); {@code DuckDBSqlDialect} does not override it. We override it to {@code false} here
 * so no {@code CHARACTER SET} clause is ever emitted, matching the retired {@code AranSqlDialect}.
 * Not exercised by the current OQL surface (no CHAR/VARCHAR literal casts reach SQL generation),
 * but kept for parity and to guard against a future regression if that changes.
 */
public final class DuckDbDialect extends DuckDBSqlDialect {

    public static final DuckDbDialect DEFAULT = new DuckDbDialect(DuckDBSqlDialect.DEFAULT_CONTEXT);

    public DuckDbDialect(Context context) {
        super(context);
    }

    /** DuckDB uses {@code LIMIT n [OFFSET m]}, not SQL-standard {@code FETCH FIRST n ROWS ONLY}. */
    @Override
    public void unparseOffsetFetch(SqlWriter writer, SqlNode offset, SqlNode fetch) {
        unparseFetchUsingLimit(writer, offset, fetch);
    }

    /** Faithful to the retired {@code AranSqlDialect}: never emit a {@code CHARACTER SET} clause. */
    @Override
    public boolean supportsCharSet() {
        return false;
    }
}

package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies a relation out of any JDBC database and writes it as parquet.
 *
 * <p>The {@code JDBC_GENERIC} mechanism that
 * MySQL, MariaDB and Oracle declare. Until this existed, PostgreSQL was the only engine that could
 * move a row — the lake's importer is built on asyncpg and speaks nothing else.
 *
 * <h2>Why DuckDB writes the parquet</h2>
 *
 * <p>Aran does not hand-roll a parquet writer: rows are staged in an in-process DuckDB table and
 * {@code COPY … TO … (FORMAT PARQUET)} does the encoding. DuckDB is already a dependency and is
 * already how every snapshot is read, so the write and the read agree by construction.
 *
 * <h2>Types come from the source, not from a guess</h2>
 *
 * <p>Column types are taken from {@link ResultSetMetaData} — the database's own declaration, read
 * before any row moves. That places this between the two other mechanisms rather than beside them:
 * better than a Singer tap, whose JSON is type-inferred from a sample, and worse than the lake's
 * Postgres path, which has a hand-written type map per source type.
 *
 * <p>{@code NUMERIC}/{@code DECIMAL} become text, following the same reasoning the lake's
 * {@code _PG_TO_ARROW} records: a fixed-point column with a precision DuckDB cannot hold would be
 * silently rounded, and silently wrong money is worse than obviously stringy money. Oracle's
 * {@code NUMBER} is the case that forces it — it has no lossless DuckDB equivalent at all.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It takes an open {@link Connection} rather than a URL and credentials. Loading a driver is
 * {@code JdbcDriverRegistry}'s job, with pinned coordinates and SHA-256 hashes in a sandboxed
 * classloader; duplicating any of that here would be a second, weaker copy of a security control.
 */
@NullMarked
public final class JdbcBulkExtractor {

    private static final Logger log = LoggerFactory.getLogger(JdbcBulkExtractor.class);

    /** Rows staged per DuckDB round trip. Large enough to amortise, small enough to bound memory. */
    private static final int BATCH_ROWS = 1_000;

    /** The result of one extraction. The caller owns {@code parquet} and must delete it. */
    public record Extract(Path parquet, long rowCount) {}

    /** The extract exceeded the caller's byte ceiling and was abandoned. */
    public static final class ExtractTooLargeException extends RuntimeException {
        public ExtractTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Run {@code query} against {@code source} and write the rows to {@code target} as parquet.
     *
     * @param source   an open connection the caller owns and closes
     * @param query    the SELECT to extract. Callers build this from a validated relation name —
     *                 this class does not quote or validate it, because it cannot know the
     *                 source's identifier rules and guessing would be a false assurance
     * @param target   the parquet file to write
     * @param maxBytes ceiling on the written file; exceeded means the extract is abandoned
     * @return the file and the number of rows in it
     */
    public Extract extractToParquet(Connection source, String query, Path target, long maxBytes)
            throws SQLException, java.io.IOException {

        long rows = 0;
        try (Connection duck = DuckDbSession.open();
             Statement sourceStatement = source.createStatement();
             ResultSet rs = sourceStatement.executeQuery(query)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            if (columnCount == 0) {
                throw new SQLException("query returned no columns: " + query);
            }
            List<String> columns = new ArrayList<>(columnCount);
            List<Integer> sqlTypes = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                columns.add(meta.getColumnLabel(i));
                sqlTypes.add(meta.getColumnType(i));
            }

            try (Statement ddl = duck.createStatement()) {
                ddl.execute(createTableSql(columns, sqlTypes, meta));
            }

            String insert = "INSERT INTO staged VALUES ("
                    + IntStream.range(0, columnCount).mapToObj(i -> "?").collect(Collectors.joining(", "))
                    + ")";
            try (PreparedStatement ps = duck.prepareStatement(insert)) {
                int pending = 0;
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        ps.setObject(i, stageable(rs.getObject(i), sqlTypes.get(i - 1)));
                    }
                    ps.addBatch();
                    rows++;
                    if (++pending == BATCH_ROWS) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    ps.executeBatch();
                }
            }

            try (Statement copy = duck.createStatement()) {
                copy.execute("COPY staged TO '" + Names.sqlLiteral(target.toAbsolutePath().toString())
                        + "' (FORMAT PARQUET)");
            }
        }

        // Checked after the write, as the lake's materialize_max_bytes is: DuckDB gives no reliable
        // size estimate before COPY, and a ceiling enforced on a guess would either refuse valid
        // extracts or let the one that matters through.
        long written = Files.size(target);
        if (written > maxBytes) {
            Files.deleteIfExists(target);
            throw new ExtractTooLargeException(
                    "extract produced " + written + " bytes, over the " + maxBytes + " limit");
        }
        log.info("extracted {} rows to {} ({} bytes)", rows, target.getFileName(), written);
        return new Extract(target, rows);
    }

    private static String createTableSql(List<String> columns, List<Integer> sqlTypes,
                                         ResultSetMetaData meta) throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE staged (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('"').append(columns.get(i).replace("\"", "\"\"")).append('"')
               .append(' ')
               .append(duckDbType(sqlTypes.get(i), meta.getPrecision(i + 1), meta.getScale(i + 1)));
        }
        return sql.append(')').toString();
    }

    /**
     * The DuckDB column type for a JDBC type.
     *
     * <p>Unknown types become {@code VARCHAR} rather than failing the extract. A source column Aran
     * cannot model exactly is still worth carrying as text — the alternative refuses the whole
     * table for one exotic column, which is a worse answer to "I could not type this".
     */
    static String duckDbType(int sqlType, int precision, int scale) {
        return switch (sqlType) {
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.REAL -> "FLOAT";
            case Types.FLOAT, Types.DOUBLE -> "DOUBLE";
            // Text, not DECIMAL. See the class Javadoc: Oracle NUMBER has no lossless DuckDB
            // equivalent, and silently rounded money is worse than stringy money.
            case Types.NUMERIC, Types.DECIMAL -> "VARCHAR";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMPTZ";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BLOB";
            default -> "VARCHAR";
        };
    }

    /** Convert a JDBC value into something DuckDB's driver will bind, preserving exactness. */
    private static Object stageable(Object value, int sqlType) {
        if (value == null) {
            return null;
        }
        if (sqlType == Types.NUMERIC || sqlType == Types.DECIMAL) {
            // toPlainString rather than toString: the latter can emit scientific notation, which
            // would make the text column's contents depend on the magnitude of the number.
            return value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
        }
        return value;
    }
}

package edu.upc.dtak.aran.app.jdbc;

import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.MetaImpl;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One executed OQL statement, held open so JDBC can page through its rows.
 *
 * <h2>Why a file and not the paged operator</h2>
 *
 * <p>The workbench's {@code run-oql} pages by re-running the query with an {@code OFFSET}, capped
 * at 1,000 rows a page. Both properties are wrong for JDBC: a client that asks for a
 * {@code ResultSet} expects the whole result, and re-execution means page 2 of a query without a
 * total order need not continue page 1. So the statement runs <em>once</em>, through the same
 * uncapped {@code materialize-oql} operation the export endpoint and data products use, and this
 * class reads the resulting parquet back with a cursor that stays open across fetches.
 *
 * <p>DuckDB appears here only as a parquet reader. No Aran view, wrapper or catalog is
 * materialised in this connection — the planning and the GAV unfolding already happened
 * server-side, and this file is their output.
 *
 * <h2>Lifetime</h2>
 *
 * <p>The cursor owns a temp file and a database connection, so an abandoned statement leaks both.
 * {@link AranMeta} therefore holds cursors in an expiring cache as well as closing them on
 * {@code closeStatement}: JDBC clients are entitled to walk away, and a driver that crashes never
 * sends the close.
 */
@NullMarked
final class AranCursor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AranCursor.class);

    /** What a client gets when it does not ask for a specific fetch size. */
    static final int DEFAULT_FETCH_SIZE = 100;

    private final Path parquet;
    private final Connection duckdb;
    private final Statement statement;
    private final ResultSet rows;
    private final Meta.Signature signature;

    private long delivered;
    private boolean exhausted;
    private boolean closed;

    private AranCursor(Path parquet, Connection duckdb, Statement statement, ResultSet rows,
                       Meta.Signature signature) {
        this.parquet = parquet;
        this.duckdb = duckdb;
        this.statement = statement;
        this.rows = rows;
        this.signature = signature;
    }

    /**
     * Open a cursor over {@code parquet}, the file {@code materialize-oql} just wrote.
     *
     * @param oql the query text, carried into the signature so a client that asks what it ran
     *            gets OQL back rather than the generated SQL
     */
    static AranCursor open(Path parquet, String oql) throws SQLException {
        Connection duckdb = DuckDbSession.open();
        Statement statement = null;
        ResultSet rows = null;
        try {
            statement = duckdb.createStatement();
            rows = statement.executeQuery(
                    "SELECT * FROM read_parquet('" + Names.sqlLiteral(parquet.toString()) + "')");
            Meta.Signature signature = signatureOf(rows.getMetaData(), oql);
            return new AranCursor(parquet, duckdb, statement, rows, signature);
        } catch (SQLException e) {
            closeQuietly(rows, statement, duckdb);
            throw e;
        }
    }

    Meta.Signature signature() {
        return signature;
    }

    /** Rows handed out so far — the only offset a later fetch may legally ask for. */
    long delivered() {
        return delivered;
    }

    /**
     * The next {@code maxRows} rows, as a frame.
     *
     * <p>A frame that fills exactly is never marked done even when the result happens to end
     * there: proving it would need a row this fetch cannot deliver, so the client learns it from
     * the empty frame that follows.
     */
    Meta.Frame nextFrame(int maxRows) throws SQLException {
        if (closed) {
            throw new SQLException("statement is closed");
        }
        int limit = maxRows <= 0 ? DEFAULT_FETCH_SIZE : maxRows;
        long frameOffset = delivered;

        List<Object> batch = new ArrayList<>(Math.min(limit, 1024));
        while (batch.size() < limit && rows.next()) {
            batch.add(readRow());
        }
        if (batch.size() < limit) {
            exhausted = true;
        }
        delivered += batch.size();
        return new Meta.Frame(frameOffset, exhausted, batch);
    }

    private List<Object> readRow() throws SQLException {
        int columns = signature.columns.size();
        List<Object> row = new ArrayList<>(columns);
        for (int i = 1; i <= columns; i++) {
            row.add(avaticaValue(rows.getObject(i)));
        }
        return Collections.unmodifiableList(row);
    }

    /**
     * Convert a JDBC value to something Avatica can put on the wire.
     *
     * <p>Avatica serialises dates and times as the primitives JDBC defines for them — days since
     * the epoch, milliseconds since midnight, milliseconds since the epoch — and the driver
     * rebuilds the {@code java.sql} types on the client. Sending the objects instead produces
     * either a serialisation failure or a string where the client expects a number.
     *
     * <p>Anything with no wire form becomes its {@code toString()}: DuckDB's nested types (STRUCT,
     * LIST, MAP) have no JDBC scalar equivalent, and a readable string beats a failed query.
     */
    static Object avaticaValue(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Integer i -> i;
            case Long l -> l;
            case Short s -> s;
            case Byte b -> b;
            case Double d -> d;
            case Float f -> f;
            case BigDecimal d -> d;
            case byte[] bytes -> bytes;
            case java.sql.Date d -> (int) d.toLocalDate().toEpochDay();
            case java.sql.Time t -> (int) (t.toLocalTime().toNanoOfDay() / 1_000_000L);
            // NOT ts.getTime(): DuckDB's JDBC driver hands back a java.sql.Timestamp built from
            // its own naive (zone-free) TIMESTAMP value via a default-TimeZone-based conversion
            // (the same shape as java.sql.Date.valueOf/Timestamp.valueOf), so .getTime() carries
            // that machine's UTC offset baked in — off by exactly the local zone's offset (DST
            // included) everywhere but a UTC-default JVM, found live on a Europe/Madrid box
            // while proving V46's fix returns CORRECT VALUES, not just avoids throwing.
            // .toLocalDateTime() is the TZ-neutral decode (same role .toLocalDate()/.toLocalTime()
            // play for Date/Time above); re-encoding it against UTC matches the LocalDateTime case
            // three lines down, so both routes into avaticaValue agree on the same wire number.
            // One residual gap this does not (and cannot, from here) recover: a wall-clock value
            // that falls in the local zone's spring-forward gap is already lost by the time it
            // reaches this method — DuckDB's own JDBC driver produced the (wrong-by-an-hour)
            // java.sql.Timestamp before avaticaValue ever sees it. Only reading the column as
            // LocalDateTime directly (getObject(i, LocalDateTime.class), bypassing java.sql.Timestamp
            // entirely) would sidestep it; not attempted here since every fixture value in
            // JdbcRealClientIT is deliberately gap-safe.
            case Timestamp ts -> ts.toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli();
            case LocalDate d -> (int) d.toEpochDay();
            case LocalTime t -> (int) (t.toNanoOfDay() / 1_000_000L);
            case LocalDateTime dt -> dt.toInstant(ZoneOffset.UTC).toEpochMilli();
            default -> value.toString();
        };
    }

    private static Meta.Signature signatureOf(ResultSetMetaData meta, String oql) throws SQLException {
        List<ColumnMetaData> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(MetaImpl.columnMetaData(
                    meta.getColumnLabel(i),
                    i - 1,
                    avaticaTypeFor(meta.getColumnType(i), meta.getColumnTypeName(i)),
                    meta.isNullable(i) != ResultSetMetaData.columnNoNulls));
        }
        // LIST rather than ARRAY: a frame's rows survive the JSON transport as lists, and the
        // ARRAY cursor would cast them to Object[] on arrival.
        return Meta.Signature.create(columns, oql, List.of(), Meta.CursorFactory.LIST,
                Meta.StatementType.SELECT);
    }

    /**
     * The Avatica type to advertise for a {@code java.sql.Types} constant.
     *
     * <p>Built explicitly rather than through Avatica's class-based helper: that helper looks the
     * Java class up in a fixed table which has no entry for {@code BigDecimal} or {@code byte[]},
     * and answers a missing entry with a {@link NullPointerException}. DECIMAL is not exotic —
     * DuckDB gives a bare decimal literal that type — so the lookup has to be one this code owns.
     *
     * <p>A type with no scalar representation is declared VARCHAR, which pairs with
     * {@link #avaticaValue} stringifying it. DuckDB's nested types (STRUCT, LIST, MAP) land here.
     */
    static ColumnMetaData.AvaticaType avaticaTypeFor(int sqlType, String typeName) {
        ColumnMetaData.Rep rep = switch (sqlType) {
            case Types.BOOLEAN, Types.BIT -> ColumnMetaData.Rep.BOOLEAN;
            case Types.TINYINT -> ColumnMetaData.Rep.BYTE;
            case Types.SMALLINT -> ColumnMetaData.Rep.SHORT;
            case Types.INTEGER -> ColumnMetaData.Rep.INTEGER;
            case Types.BIGINT -> ColumnMetaData.Rep.LONG;
            case Types.REAL -> ColumnMetaData.Rep.FLOAT;
            case Types.FLOAT, Types.DOUBLE -> ColumnMetaData.Rep.DOUBLE;
            case Types.NUMERIC, Types.DECIMAL -> ColumnMetaData.Rep.NUMBER;
            // V46: the wire value is a number (avaticaValue flattens Date/Time/Timestamp to
            // epoch-day / millis-of-day / epoch-millis), not a java.sql.* object — so the Rep
            // must be the NUMERIC one, matching what actually goes on the wire. The
            // JAVA_SQL_DATE/TIME/TIMESTAMP Reps tell the client "expect an actual java.sql.*
            // instance already", which selects an accessor that casts the incoming number and
            // throws (ClassCastException on getObject/getString/the typed getter alike — V46).
            // Rep.INTEGER/Rep.LONG select Avatica's own
            // DateFromNumberAccessor/TimeFromNumberAccessor/TimestampFromNumberAccessor instead —
            // its documented convention for a server that sends temporal values as numbers. The
            // declared JDBC type below is untouched (still Types.DATE/TIME/TIMESTAMP): only the
            // wire Rep changes.
            //
            // This numeric-Rep rule does NOT extend to TIME_WITH_TIMEZONE/TIMESTAMP_WITH_TIMEZONE
            // (deliberately absent below, falling through to the `default -> Rep.STRING` case).
            // Avatica 1.27's own createAccessors accepts ONLY Rep.STRING for those two type ids —
            // anything else is an AssertionError that kills the whole statement, not just the
            // column. avaticaValue already sends a String for them (DuckDB hands back
            // OffsetTime/OffsetDateTime for a *_WITH_TIMEZONE column, landing on the `default ->
            // value.toString()` branch below), so Rep.STRING is not a gap — it is the one Rep that
            // was already correct, and the pairing is exercised by a real TIMESTAMPTZ column read
            // through the sources tier in JdbcRealClientIT.
            case Types.DATE -> ColumnMetaData.Rep.INTEGER;
            case Types.TIME -> ColumnMetaData.Rep.INTEGER;
            case Types.TIMESTAMP -> ColumnMetaData.Rep.LONG;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    ColumnMetaData.Rep.BYTE_STRING;
            default -> ColumnMetaData.Rep.STRING;
        };
        int declared = rep == ColumnMetaData.Rep.STRING && sqlType != Types.VARCHAR
                && sqlType != Types.CHAR && sqlType != Types.LONGVARCHAR
                ? Types.VARCHAR : sqlType;
        String declaredName = declared == sqlType ? typeName : "VARCHAR";
        return ColumnMetaData.scalar(declared, declaredName, rep);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(rows, statement, duckdb);
        try {
            Files.deleteIfExists(parquet);
        } catch (Exception e) {
            // A leftover temp file costs disk, never correctness — but it is worth knowing about,
            // because a steady drip of them means statements are not being closed.
            log.warn("could not delete JDBC result file {}", parquet, e);
        }
    }

    private static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception ignore) {
                    // Best effort; the next one still has to be closed.
                }
            }
        }
    }
}

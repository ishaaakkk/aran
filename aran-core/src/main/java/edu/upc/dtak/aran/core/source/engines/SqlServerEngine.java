package edu.upc.dtak.aran.core.source.engines;

import edu.upc.dtak.aran.core.source.RelationalEngine;
import org.jspecify.annotations.NullMarked;

/**
 * Microsoft SQL Server: introspectable and extractable, not federatable.
 *
 * <p>Written to test this SPI's own acceptance criterion — that adding an engine is a driver-
 * registry entry plus one class plus one registry line, and no third place. It was.
 *
 * <p>{@code federatedScan} and {@code duckDbExtension} are simply not overridden: DuckDB has no
 * SQL Server scanner, and the absent defaults say so without this class having to mention it.
 */
@NullMarked
public final class SqlServerEngine implements RelationalEngine {

    @Override
    public String id() {
        return "SQLSERVER";
    }

    @Override
    public String displayName() {
        return "SQL Server";
    }

    @Override
    public int defaultPort() {
        return 1433;
    }

    /**
     * Bracket quoting. SQL Server also accepts ANSI double quotes, but only when
     * {@code QUOTED_IDENTIFIER} is ON — which is a session setting Aran does not control, so
     * brackets are the form that works regardless. A {@code ]} inside an identifier is escaped by
     * doubling it.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    @Override
    public BulkExtract bulkExtract() {
        return BulkExtract.JDBC_GENERIC;
    }
}

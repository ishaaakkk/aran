package edu.upc.dtak.aran.core.source.engines;

import edu.upc.dtak.aran.core.source.RelationalEngine;
import org.jspecify.annotations.NullMarked;

/**
 * HBase via the Phoenix thin client.
 *
 * <p>Phoenix <em>is</em> a JDBC driver, so {@link BulkExtract#JDBC_GENERIC} might work. It stays
 * {@link BulkExtract#NONE} until somebody runs it: Phoenix's JDBC surface is partial, and claiming
 * a capability on the strength of a driver existing is precisely how this list acquired four kinds
 * that connect and ingest nothing.
 */
@NullMarked
public final class HBaseEngine implements RelationalEngine {

    @Override
    public String id() {
        return "HBASE";
    }

    @Override
    public String displayName() {
        return "HBase (Phoenix)";
    }

    @Override
    public int defaultPort() {
        return 2181;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}

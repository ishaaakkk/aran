package edu.upc.dtak.aran.core.source.engines;

import edu.upc.dtak.aran.core.source.RelationalEngine;
import org.jspecify.annotations.NullMarked;

/**
 * Oracle: introspectable and extractable, not federatable.
 *
 * <p>DuckDB has no Oracle scanner, so {@code federatedScan} and {@code duckDbExtension} are left
 * at their absent defaults and the honest "no" falls out.
 */
@NullMarked
public final class OracleEngine implements RelationalEngine {

    @Override
    public String id() {
        return "ORACLE";
    }

    @Override
    public String displayName() {
        return "Oracle";
    }

    @Override
    public int defaultPort() {
        return 1521;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public BulkExtract bulkExtract() {
        return BulkExtract.JDBC_GENERIC;
    }
}

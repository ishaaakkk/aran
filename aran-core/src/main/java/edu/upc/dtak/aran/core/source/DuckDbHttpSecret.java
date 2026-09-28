package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.util.Names;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The DuckDB {@code CREATE SECRET (TYPE HTTP, BEARER_TOKEN …)} statement that authenticates httpfs
 * reads of {@code http(s)://} locations — a pure string builder, in core for the
 * {@link DuckDbS3Config} reason: the query engine's {@code ConnectionSetup} and federated
 * profiling's binding builder must bind the <em>same</em> token the <em>same</em> way, and a second
 * spelling of a credential-bearing statement is a credential-handling bug waiting to drift.
 *
 * <p><b>The returned string contains the token.</b> Execute it and discard it — never log it, never
 * put it in an exception message, never return it past the connection (or the freyja request body)
 * it authenticates.
 */
@NullMarked
public final class DuckDbHttpSecret {

    private DuckDbHttpSecret() {
    }

    /**
     * {@code CREATE OR REPLACE SECRET <name> (TYPE HTTP, BEARER_TOKEN '<token>'[, SCOPE
     * '<scope>'])}. {@code OR REPLACE} so a reused connection can rebind without a duplicate-name
     * error; the scope clause is omitted when blank.
     */
    public static String createSecretSql(String secretName, String bearerToken,
                                         @Nullable String scope) {
        StringBuilder sb = new StringBuilder("CREATE OR REPLACE SECRET ")
                .append(secretName).append(" (TYPE HTTP, BEARER_TOKEN '")
                .append(Names.sqlLiteral(bearerToken)).append('\'');
        if (scope != null && !scope.isBlank()) {
            sb.append(", SCOPE '").append(Names.sqlLiteral(scope)).append('\'');
        }
        return sb.append(')').toString();
    }
}

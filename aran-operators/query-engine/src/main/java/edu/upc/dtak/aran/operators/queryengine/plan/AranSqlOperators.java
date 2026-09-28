package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.fun.SqlLibraryOperators;

/**
 * The SQL operators this planner emits that {@code SqlStdOperatorTable} does not carry.
 *
 * <p>One name, one place. An operator reached for by its Calcite library constant at each call
 * site is an operator nobody can pin a dialect behaviour on, and the behaviour here — that
 * {@code RelToSqlConverter} emits it as DuckDB expects — is exactly what had to be proven before
 * {@code OqlPlanner} could build a plan around it.
 */
public final class AranSqlOperators {

    private AranSqlOperators() {
    }

    /**
     * {@code TRY_CAST(<expr> AS <type>)} — a cast that yields NULL on a value it cannot convert
     * rather than failing the statement. The expansion path needs it for the same reason
     * {@code DuckDbExecutor.buildDdl} emits it into every {@code u_} view body: a dirty cell must
     * degrade to NULL — which is what the NULL-padding branch beside it always produced — never
     * fail a query that used to run.
     *
     * <p><b>Calcite's own operator, not a bespoke {@code SqlFunction}.</b> Calcite 1.41 already
     * ships {@code TRY_CAST} as a {@code SqlCastFunction}, so it arrives with the cast syntax
     * ({@code x AS type}, not {@code f(x, type)}), the cast return-type inference, and the cast
     * operand checking already correct. Building a lookalike would have meant re-deriving all
     * three and getting the unparse subtly different from the text {@code buildDdl} emits.
     *
     * <p>Verified rather than assumed, because the risk was real and specific: a
     * {@code RexBuilder.makeCall(targetType, TRY_CAST, operand)} call carries the target type as
     * its <em>return type</em>, not as an operand, so nothing guarantees a priori that the
     * unparser recovers it — a converter that dropped it would emit {@code TRY_CAST("x")}, or
     * normalise the whole call back to a plain {@code CAST}. {@code OqlPlannerExpansionTest
     * .tryCastUnparsesAsDuckDbTryCast} pins what Calcite 1.41 + {@link DuckDbDialect} actually
     * produce: {@code TRY_CAST("x" AS BIGINT)}. No {@code unparseCall} hook in the dialect was
     * needed, and none should be added while that test passes.
     */
    public static final SqlFunction TRY_CAST = SqlLibraryOperators.TRY_CAST;
}

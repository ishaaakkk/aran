package edu.upc.dtak.aran.core.operator;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * The core contract every Aran capability operator implements.
 *
 * <p>Operators are registered automatically via Spring Boot component scanning
 * and dependency injection — eliminating runtime classloaders and complex dynamic registers.
 */
@NullMarked
public interface Operator {

    /**
     * Unique identifier for this operator (e.g., "bootstrapping", "join-discovery").
     */
    String getOperatorId();

    /**
     * Human-readable display name for user interfaces.
     */
    String getDisplayName();

    /**
     * Executes the requested capability operation.
     *
     * @param request the parameters and active tenant boundaries for the execution
     * @return the unified outcome of the operator invocation
     */
    OperatorResult execute(OperatorRequest request);

    /**
     * Executes the operation, reporting stage/percent progress as it goes.
     *
     * <p>The default ignores {@code progress} and delegates to
     * {@link #execute(OperatorRequest)} — an operator with nothing true to say
     * about its own progress needs no change. Operators that run as jobs
     * override this and call {@code progress.stage(...)} at their real phase
     * boundaries.
     *
     * @param progress where to report phases; callers with nowhere to send them
     *                 pass {@link ProgressReporter#NOOP}
     */
    default OperatorResult execute(OperatorRequest request, ProgressReporter progress) {
        return execute(request);
    }

    /**
     * Returns a machine-readable manifest describing this operator's operations
     * and their parameter schemas. The default returns a permissive manifest with
     * no operations listed — override in concrete operators to provide real schemas.
     */
    default OperatorManifest manifest() {
        return new OperatorManifest(getOperatorId(), getDisplayName(), List.of());
    }
}

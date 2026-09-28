package edu.upc.dtak.aran.core.operator;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates the execution parameters for an {@link Operator#execute(OperatorRequest)} call.
 *
 * @param tenantId   the active tenant context resolved by Aran security filters.
 * @param userId     the authenticated user's id, when one is known (operators that
 *                   record audit/actor information rely on this). May be {@code null}
 *                   only for system-internal invocations.
 * @param operation  the operator-defined operation key (e.g. "ping", "profile").
 * @param parameters key-value config and settings arguments for the run.
 */
@NullMarked
public record OperatorRequest(
        String tenantId,
        @Nullable UUID userId,
        String operation,
        Map<String, String> parameters
) {
    public OperatorRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        parameters = Map.copyOf(parameters);
    }

    /**
     * Backwards-compatible constructor for operator callsites that do not need to
     * carry an authenticated user (e.g. ping or pre-existing tests). New callers
     * should prefer the canonical four-argument form.
     */
    public OperatorRequest(String tenantId, String operation, Map<String, String> parameters) {
        this(tenantId, null, operation, parameters);
    }
}

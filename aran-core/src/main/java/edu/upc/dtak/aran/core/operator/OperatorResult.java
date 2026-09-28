package edu.upc.dtak.aran.core.operator;

import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The unified output resulting from an {@link Operator#execute(OperatorRequest)} call.
 */
@NullMarked
public record OperatorResult(
        boolean success,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        Map<String, String> data
) {
    public OperatorResult {
        data = Map.copyOf(data);
    }

    public static OperatorResult success(Map<String, String> data) {
        return new OperatorResult(true, null, null, data);
    }

    public static OperatorResult failure(String errorCode, String errorMessage) {
        return new OperatorResult(false, errorCode, errorMessage, Map.of());
    }
}

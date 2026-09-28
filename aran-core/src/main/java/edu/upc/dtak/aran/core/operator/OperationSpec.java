package edu.upc.dtak.aran.core.operator;

import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A single operation an operator supports, with its parameter schema.
 *
 * @param longRunning whether callers should prefer {@code /submit} (job substrate)
 *                     over {@code /invoke} (synchronous) for this operation.
 */
@NullMarked
public record OperationSpec(
        String name,
        @Nullable String description,
        boolean longRunning,
        List<ParameterSpec> parameters
) {}

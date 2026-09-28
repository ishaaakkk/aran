package edu.upc.dtak.aran.core.operator;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Describes one entry of an {@link OperationSpec}'s parameter map. Operator
 * parameters are always transported as strings (see {@link OperatorRequest#parameters()}),
 * so {@code type} here is documentation for callers (e.g. "string", "uuid",
 * "json-array"), not a wire-level constraint.
 */
@NullMarked
public record ParameterSpec(
        String name,
        String type,
        boolean required,
        @Nullable String description,
        @Nullable String defaultValue
) {
    /** Convenience factory for a required string parameter. */
    public static ParameterSpec required(String name, String description) {
        return new ParameterSpec(name, "string", true, description, null);
    }

    /** Convenience factory for an optional string parameter with a default. */
    public static ParameterSpec optional(String name, String description, @Nullable String defaultValue) {
        return new ParameterSpec(name, "string", false, description, defaultValue);
    }
}

package edu.upc.dtak.aran.core.operator;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Machine-readable description of an operator: its identity and the operations
 * it exposes, each with its own parameter schema.
 */
@NullMarked
public record OperatorManifest(
        String operatorId,
        String displayName,
        List<OperationSpec> operations
) {}

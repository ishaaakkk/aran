package edu.upc.dtak.aran.operators.schemaintegration;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown when an {@link Alignment} is structurally invalid against the M-compliance
 * rules of Flores et al. (2024) §5.1 — e.g. a CLASS alignment whose targets are
 * properties, or a property alignment whose property lacks a domain. This is a hard
 * validation failure, distinct from an alignment that is merely deferred because its
 * prerequisites are not yet integrated.
 */
@NullMarked
public final class MappingValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Alignment alignment;

    public MappingValidationException(Alignment alignment, String reason) {
        super("Invalid " + alignment.type() + " alignment (" + alignment.label() + "): " + reason);
        this.alignment = alignment;
    }

    public Alignment alignment() {
        return alignment;
    }
}

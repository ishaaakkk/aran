package edu.upc.dtak.aran.operators.schemaintegration;

import org.jspecify.annotations.NullMarked;

import java.util.Locale;
import java.util.Objects;

/**
 * A user-provided semantic correspondence between two graph elements
 * (the {@code (R_A, R_B, ℓ)} triple of the paper).
 *
 * @param iriA  the IRI of the left-hand resource
 * @param iriB  the IRI of the right-hand resource
 * @param label the human-readable label for the resulting integrated resource
 * @param type  one of {@link Type}
 */
@NullMarked
public record Alignment(String iriA, String iriB, String label, Type type) {

    public enum Type {
        CLASS, DATATYPE, OBJECT, JOIN;

        public static Type from(String s) {
            Objects.requireNonNull(s, "alignment type must not be null");
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "class" -> CLASS;
                case "datatype", "datatypeproperty", "datatype_property" -> DATATYPE;
                case "object", "objectproperty", "object_property" -> OBJECT;
                case "join", "joinproperty", "join_property" -> JOIN;
                default -> throw new IllegalArgumentException(
                        "alignment type must be one of: class, datatype, object, join — got: " + s);
            };
        }
    }

    public Alignment {
        Objects.requireNonNull(iriA, "iriA");
        Objects.requireNonNull(iriB, "iriB");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        if (iriA.isBlank() || iriB.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("Alignment fields must not be blank");
        }
    }
}

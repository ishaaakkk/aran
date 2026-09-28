package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * The allowed {@code kind} values for a {@link ModelMapping}.
 *
 * <p>These are the <em>exact</em> alignment type strings the schema-integration
 * operator switches on — see {@code Alignment.Type.from(String)} in
 * {@code edu.upc.dtak.aran.operators.schemaintegration.Alignment}. The integration
 * model must round-trip losslessly through {@code compile}, so v1 invents no new
 * names: a mapping's {@code kind} is fed back verbatim as the alignment
 * {@code type}, and for {@link #JOIN} the mapping's {@code label} keeps the packed
 * {@code "label|relationship|direction"} form the operator parses.
 */
@NullMarked
public final class MappingKinds {

    public static final String CLASS = "class";
    public static final String DATATYPE = "datatype";
    public static final String OBJECT = "object";
    public static final String JOIN = "join";

    private MappingKinds() {
    }

    /** @return whether {@code kind} is one of the four accepted alignment types. */
    public static boolean isValid(String kind) {
        return CLASS.equals(kind) || DATATYPE.equals(kind)
                || OBJECT.equals(kind) || JOIN.equals(kind);
    }
}

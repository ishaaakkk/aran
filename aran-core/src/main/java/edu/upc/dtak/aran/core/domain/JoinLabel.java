package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.util.Names;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A {@link MappingKinds#JOIN} mapping's packed label, {@code "label|relationship|direction"},
 * unpacked. The one parser for it: the schema integrator mints the join's two IRIs from these
 * parts and the model service refuses a label whose parts would mint one IRI twice, so a second
 * copy of the split would let the check and the mint disagree about the same string.
 *
 * @param label        the join label — the integrated datatype property {@code P_l} is minted
 *                     from it
 * @param relationship the relationship name, or null when none was given
 * @param direction    {@code forward} unless the third part says otherwise
 */
@NullMarked
public record JoinLabel(String label, @Nullable String relationship, String direction) {

    public static JoinLabel parse(String packed) {
        if (!packed.contains("|")) {
            return new JoinLabel(packed, null, "forward");
        }
        String[] parts = packed.split("\\|");
        return new JoinLabel(parts[0],
                parts.length > 1 ? parts[1] : null,
                parts.length > 2 ? parts[2] : "forward");
    }

    /** What the join object property {@code P_s} is minted from: the relationship, else {@code <label>_join}. */
    public String propertyName() {
        return relationship != null && !relationship.isBlank() ? relationship : label + "_join";
    }

    /**
     * Whether {@code P_l} and {@code P_s} would be minted as one IRI. Both are
     * {@code INTEGRATION_NS + Names.iriFragment(name.trim())}, so a relationship named like its
     * label — or spelled so that only characters the fragment rewrites differ — makes the join's
     * datatype property and its object property the same resource.
     */
    public boolean mintsOneIri() {
        return Names.iriFragment(label.trim()).equals(Names.iriFragment(propertyName().trim()));
    }
}

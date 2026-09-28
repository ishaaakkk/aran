package edu.upc.dtak.aran.core.domain.cube;

import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * The one rule for the names a user chooses when shaping a cube.
 *
 * <p>Cube and dimension names are not decoration: creating a cube creates one data product per
 * star table, named {@code <cube>_fact} and {@code <cube>_<dimension>}, and those names end up as
 * SQL table names in the JDBC {@code products} tree and as identifiers in every export format.
 * So the alphabet is the intersection of what all of them accept — lowercase, digits and
 * underscore, starting with a letter — checked at the door instead of sanitised silently, because
 * a silently sanitised name is a name the user cannot find again.
 */
@NullMarked
public final class CubeNames {

    /** 63 characters total: one letter plus at most 62, which keeps {@code <cube>_fact} short of
     * every identifier limit the star's products pass through. */
    public static final Pattern STAR_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private CubeNames() {
    }

    /** @throws IllegalArgumentException when {@code name} is not a legal star name */
    public static void requireStarName(String kind, String name) {
        if (name == null || !STAR_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(kind + " name '" + name + "' must be lowercase "
                    + "letters, digits and underscores, starting with a letter (max 63 chars) — "
                    + "it becomes a table name");
        }
    }
}

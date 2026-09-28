package edu.upc.dtak.aran.core.domain.cube;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * One dimension of a cube: an integrated class reached from the fact over a join property of the
 * schema graph, optionally with a hierarchy over its own properties.
 *
 * <p>{@code joinPropertyIri} is the identity of the relationship, not a re-derivation of it — the
 * join contract says the planner consumes {@code MappingCatalog
 * .JoinProperty} directly, and so does this. The join columns on either side come from the
 * evidence edge that property resolves to; a cube never stores its own copy of them.
 *
 * @param name           the dimension's name; becomes the {@code <cube>_<name>} star product
 * @param classIri       the integrated class the dimension members come from
 * @param joinPropertyIri the schema-graph join property connecting the fact to that class
 * @param unknownMember  whether fact rows with a NULL foreign key are kept as an explicit
 *                       "unknown" member. Kimball's answer to the drill-down that stops
 *                       reconciling with the topline (§5.2); definition-only in v1 — nothing
 *                       materializes the member yet
 * @param hierarchy      the dimension class's property IRIs as levels, <b>coarsest first</b>
 *                       (country before city), matching the wizard's "coarsest on top" stack and
 *                       the {@code qb4o:parentLevel} wiring {@link CubeQb4olap} emits from it.
 *                       Empty means a flat dimension, which is legitimate
 * @param allowUnverified whether the user insisted on a join the platform cannot show to be
 *                       to-one (a {@code HYPOTHESIS} edge). Default false: a to-many join
 *                       multiplies fact rows and double-counts every measure, so it is refused
 *                       unless someone takes responsibility for it
 */
@NullMarked
public record CubeDimension(
        String name,
        String classIri,
        String joinPropertyIri,
        boolean unknownMember,
        List<String> hierarchy,
        boolean allowUnverified
) {

    public CubeDimension {
        CubeNames.requireStarName("dimension", name);
        if (classIri == null || classIri.isBlank()) {
            throw new IllegalArgumentException(
                    "dimension '" + name + "': classIri must not be blank");
        }
        if (joinPropertyIri == null || joinPropertyIri.isBlank()) {
            throw new IllegalArgumentException(
                    "dimension '" + name + "': joinPropertyIri must not be blank");
        }
        // A missing hierarchy is a flat dimension, which is the common case — so absence reads as
        // "no levels" rather than as an error, including when it arrives as a null from JSON.
        hierarchy = hierarchy == null ? List.of() : List.copyOf(hierarchy);
    }

    /** A flat dimension over a join the platform could verify. */
    public static CubeDimension flat(String name, String classIri, String joinPropertyIri) {
        return new CubeDimension(name, classIri, joinPropertyIri, false, List.of(), false);
    }
}

package edu.upc.dtak.aran.core.domain.cube;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/**
 * A generated time dimension over one date column of the fact class — Power BI's auto date
 * hierarchy, made explicit instead of silent.
 *
 * <p>Definition-only in v1: this is recorded, emitted into {@code cube.yaml} and projected into
 * the QB4OLAP description, but no calendar table is generated and no level is computed. The
 * levels are therefore a closed set rather than free text — a level nothing can project is a
 * promise the star does not keep, so an unknown one is rejected here rather than emitted into an
 * export that would carry it onward.
 *
 * <p>A {@code day} boundary depends on a timezone and this record pins none; the platform's
 * stated answer is UTC (§5.6), which is a documented limit, not a resolved question.
 *
 * @param fromColumnIri the fact class's date property the levels are derived from
 * @param levels        the levels to generate, coarsest first, from {@link #LEVELS}
 */
@NullMarked
public record CubeCalendar(String fromColumnIri, List<String> levels) {

    /** The levels v1 names; ordered coarsest to finest, which is also the order a hierarchy
     * rolls up in. */
    public static final List<String> LEVELS = List.of("year", "quarter", "month", "day");

    public CubeCalendar {
        if (fromColumnIri == null || fromColumnIri.isBlank()) {
            throw new IllegalArgumentException("calendar: fromColumnIri must not be blank");
        }
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException(
                    "calendar: at least one level is required, from " + LEVELS);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> normalised = new ArrayList<>();
        for (String level : levels) {
            String lower = level == null ? "" : level.toLowerCase(Locale.ROOT);
            if (!LEVELS.contains(lower)) {
                throw new IllegalArgumentException(
                        "calendar: unknown level '" + level + "' — v1 generates " + LEVELS);
            }
            if (!seen.add(lower)) {
                throw new IllegalArgumentException(
                        "calendar: level '" + lower + "' is repeated");
            }
            normalised.add(lower);
        }
        levels = List.copyOf(normalised);
    }
}

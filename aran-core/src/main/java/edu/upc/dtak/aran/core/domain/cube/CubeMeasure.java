package edu.upc.dtak.aran.core.domain.cube;

import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One measure of a cube: either a column of the fact class with an aggregator, or — when
 * {@link Additivity#RATIO} — an expression evaluated <em>after</em> aggregation.
 *
 * <p>The two shapes are mutually exclusive, and the compact constructor enforces that rather
 * than leaving a half-filled measure for the YAML/QB4OLAP emitters to interpret. A measure
 * carrying both a column and an {@code expr} has no defined meaning, and the projections would
 * each guess differently.
 *
 * <p><b>A RATIO's {@code expr} is not parsed or validated in v1.</b> It is carried through to
 * {@code cube.yaml} and to the QB4OLAP description verbatim, as a definition-only field; nothing
 * plans it, so a nonsense expression fails wherever a consumer first evaluates it rather than at
 * cube creation. Validating it means a post-aggregation expression language over measure names,
 * which is real work and is not in v1.
 *
 * @param name       the measure's name as consumers see it (YAML key, TMDL/DAX measure name)
 * @param columnIri  the fact class's property this aggregates; {@code null} for RATIO
 * @param expr       the post-aggregation expression; {@code null} unless RATIO
 * @param aggregator SUM, AVG, MIN, MAX or COUNT; {@code null} for RATIO
 */
@NullMarked
public record CubeMeasure(
        String name,
        @Nullable String columnIri,
        @Nullable String expr,
        @Nullable String aggregator,
        Additivity additivity
) {

    /** The aggregators QB4OLAP names and DuckDB, DAX and metric views all agree on. */
    public static final Set<String> AGGREGATORS = Set.of("SUM", "AVG", "MIN", "MAX", "COUNT");

    public CubeMeasure {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("measure name must not be blank");
        }
        if (additivity == null) {
            throw new IllegalArgumentException("measure '" + name + "': additivity must be set");
        }
        if (additivity == Additivity.RATIO) {
            if (expr == null || expr.isBlank()) {
                throw new IllegalArgumentException(
                        "measure '" + name + "': a RATIO measure is derived — it must carry expr");
            }
            if (columnIri != null || aggregator != null) {
                throw new IllegalArgumentException("measure '" + name + "': a RATIO measure is "
                        + "computed after aggregation — it must carry neither columnIri nor "
                        + "aggregator");
            }
        } else {
            if (columnIri == null || columnIri.isBlank()) {
                throw new IllegalArgumentException(
                        "measure '" + name + "': columnIri must name a property of the fact class");
            }
            if (aggregator == null || !AGGREGATORS.contains(aggregator)) {
                throw new IllegalArgumentException("measure '" + name + "': aggregator must be one "
                        + "of " + AGGREGATORS + ", was " + aggregator);
            }
            if (expr != null) {
                throw new IllegalArgumentException("measure '" + name + "': expr belongs to a "
                        + "RATIO measure — a column measure is defined by its aggregator");
            }
        }
    }

    /** A column measure with an aggregator. */
    public static CubeMeasure of(String name, String columnIri, String aggregator,
                                 Additivity additivity) {
        return new CubeMeasure(name, columnIri, null, aggregator, additivity);
    }

    /** A derived measure, computed from other aggregates after aggregation. */
    public static CubeMeasure ratio(String name, String expr) {
        return new CubeMeasure(name, null, expr, null, Additivity.RATIO);
    }
}

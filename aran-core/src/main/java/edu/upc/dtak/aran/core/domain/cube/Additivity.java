package edu.upc.dtak.aran.core.domain.cube;

/**
 * How a measure may be re-aggregated — the property BI tools get silently wrong when the model
 * does not state it.
 *
 * <p>This is metadata, not behaviour: v1 records it, projects it into {@code cube.yaml} and the
 * QB4OLAP description, and shows it in the UI. Nothing in the query path reads it yet — a
 * SEMI_ADDITIVE measure is still summed if a client sums it. The point of recording it now is
 * that the alternative is a dashboard that is wrong with our name on the semantic model.
 */
public enum Additivity {

    /** Summable across every dimension (an amount, a quantity). */
    ADDITIVE,

    /**
     * Summable across every dimension <em>except</em> time (a balance, an inventory level):
     * over time it wants LAST/FIRST/AVG instead.
     */
    SEMI_ADDITIVE,

    /** Never re-aggregable from aggregates (a unit price, a percentage). */
    NON_ADDITIVE,

    /**
     * Derived after aggregation from other aggregates — {@code SUM(a)/SUM(b)}, the shape a DAX
     * measure and a Unity Catalog metric view both express. A RATIO measure therefore has an
     * {@code expr} and neither a column nor an aggregator; see {@link CubeMeasure}.
     */
    RATIO
}

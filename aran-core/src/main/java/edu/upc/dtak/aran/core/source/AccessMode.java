package edu.upc.dtak.aran.core.source;

/**
 * How a source's rows reach the query engine.
 *
 * <p>The standard dichotomy in data integration — answer the query against the sources, or against
 * a copy built in advance — and the vocabulary this codebase already speaks on both sides
 * ({@code RelationalDatabaseRepository.federated()}; {@code materialize-oql},
 * {@code materializedAt}, {@code MATERIALIZING}).
 *
 * <p>Two values, not three. An earlier {@code BindingStrategy} split the second into
 * {@code LAKE_FILE} and {@code MATERIALIZE_TO_LAKE}, which are not distinct: at query time both
 * download a snapshot and read it with the format function, and the resolver has two branches
 * accordingly. What differs between an uploaded CSV and an extracted Oracle table is whether an
 * upstream exists to refresh <em>from</em> — a freshness question, not an access one.
 */
public enum AccessMode {

    /**
     * The query reaches the source where it lives — a table of an attached Postgres or MySQL
     * catalog, {@code read_parquet('s3://…')}, {@code iceberg_scan}. Zero copy, always current, and predicates can be pushed
     * down where the reader supports it.
     */
    FEDERATED,

    /**
     * The rows are read from a copy in the lake. How that copy got there, and how often it is
     * remade, is a separate axis: an uploaded file's copy has no upstream and never refreshes,
     * an extracted table's does.
     */
    MATERIALIZED
}

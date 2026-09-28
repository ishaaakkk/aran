package edu.upc.dtak.aran.core.domain.drift;

import org.jspecify.annotations.NullMarked;

/** What a {@link SchemaChange} says happened between two {@link SchemaFingerprint}s. */
@NullMarked
public enum SchemaChangeKind {

    /** A source class the previous fingerprint did not have — e.g. a new nested array in JSON. */
    CLASS_ADDED,

    /**
     * A source class that is gone.
     *
     * <p>The most dangerous kind in a GAV union: an integrated class is a {@code UNION ALL} over
     * its per-source members, so losing one member makes queries return <em>fewer rows with no
     * error</em>. Nothing downstream can notice this on its own.
     */
    CLASS_REMOVED,

    /**
     * The class is the same source structure but its IRI was re-minted — which happens when the
     * dataset is renamed, because {@code BootstrapEngine} interpolates the dataset name into every
     * IRI. Breaking for every mapping beneath it, and systematically repairable.
     */
    CLASS_IRI_CHANGED,

    /** A column present in the new source and not the old. Never breaking; often an opportunity. */
    COLUMN_ADDED,

    /** A column that is gone, with no plausible replacement. */
    COLUMN_REMOVED,

    /** A column that is gone alongside exactly one arrival that plausibly replaces it. */
    COLUMN_RENAMED,

    /**
     * The column survived but its declared {@code rdfs:range} changed. Queries keep running; what
     * they mean may not — {@code MAX(year)} over a string is lexicographic.
     */
    TYPE_CHANGED,

    /** The class's source format changed (e.g. the same dataset re-uploaded as Parquet, not CSV). */
    FORMAT_CHANGED
}

package edu.upc.dtak.aran.core.domain;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Models the core relational profile metrics computed for a single column of a dataset.
 *
 * <p>{@code subject}/{@code classIri} are Phase 3B's discriminator for what was profiled:
 * {@code "SNAPSHOT"} (today's only real shape — Freyja over the dataset's raw snapshot, one row
 * per physical column, {@code classIri} irrelevant and left {@code null}) or
 * {@code "WRAPPER_OUTPUT"} (a row profiling one source class's resolved wrapper output instead,
 * {@code classIri} naming which class). The convenience constructor below — and the compact
 * constructor, for a canonical-shape caller that passes {@code null} — default {@code subject}
 * to {@code "SNAPSHOT"}: every pre-3B call site profiled a snapshot and nothing else, so absence
 * of the discriminator must resolve to that, never to an unmarked third state. Same discipline as
 * {@code MappingCatalog.SourceView.expansionStatus} (Phase 3A).
 *
 * <p>{@code valueHashSample} is freyja's bottom-k by hash of the column's distinct values
 * ({@code freyja/profiling/value_sketch.py}): what the {@code exact} join score estimates value
 * overlap from, since {@code commonValues} is chosen by frequency and a unique column's is only its
 * lexicographic prefix. The constructors without it default it to empty, which join discovery reads
 * as "no sample" — such a column is scored only where its {@code commonValues} are its whole value
 * set.
 */
@NullMarked
public record ColumnProfile(
        UUID id,
        UUID datasetId,
        String columnName,
        int columnIndex,
        String sourceType,

        long cardinality,
        @Nullable Double entropy,
        double uniqueness,
        double incompleteness,

        double frequencyAvg,
        long frequencyMin,
        long frequencyMax,
        double frequencySd,
        double frequencyIqr,

        double valPctMin,
        double valPctMax,
        double valPctStd,
        double constancy,

        int lenMaxWord,
        int lenMinWord,
        double lenAvgWord,

        long numberWords,
        int wordsCntMax,
        int wordsCntMin,
        double wordsCntAvg,
        double wordsCntSd,

        @Nullable String firstWord,
        @Nullable String lastWord,

        boolean isEmpty,
        boolean isBinary,

        List<String> commonValues,
        List<String> commonValuesSoundex,

        String subject,
        @Nullable String classIri,

        List<String> valueHashSample
) {
    public ColumnProfile {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        commonValues = List.copyOf(commonValues);
        commonValuesSoundex = List.copyOf(commonValuesSoundex);
        valueHashSample = List.copyOf(valueHashSample);
        if (subject == null) {
            subject = SUBJECT_SNAPSHOT;
        }
    }

    /**
     * Exact-uniqueness evidence: every value in the profiled column was distinct. Freyja computes
     * {@code uniqueness} as exact {@code COUNT(DISTINCT)/COUNT(*)} and rounds to 9 decimals, so
     * {@code >= 1.0} cannot be produced by a near-unique column below ~1e9 rows — one real
     * duplicate lands at most at 0.999999999. This is the single key-exactness test both planner
     * statistics use; a looser threshold here would let join elimination change row multiplicity.
     * Callers must still screen the {@code subject}: a {@code HARVESTED} row's uniqueness is a
     * pg_stats estimate and never exact, whatever it reads.
     */
    public boolean exactlyUnique() {
        return uniqueness >= 1.0;
    }

    /**
     * Whether this row may testify that a column is a key. Only freyja's own measurements can: a
     * {@link #SUBJECT_HARVESTED} row is a {@code pg_stats} estimate, and a {@link
     * #SUBJECT_FEDERATED_LIVE} row, though exact when taken, describes a live relation whose
     * distinctness decays the moment the source commits. Both still feed row counts and NDV —
     * only key testimony is withheld, because a stale "unique" plus a DECLARED edge would let join
     * elimination change row multiplicity. The single definition both planner statistics and the
     * Profile tab read, so the UI's "not key evidence" label cannot drift from the planner's rule.
     */
    public boolean admissibleAsKeyEvidence() {
        return !SUBJECT_HARVESTED.equals(subject) && !SUBJECT_FEDERATED_LIVE.equals(subject);
    }

    /** The shape before the value sample: a profile with no sample to estimate overlap from. */
    public ColumnProfile(
            UUID id, UUID datasetId, String columnName, int columnIndex, String sourceType,
            long cardinality, @Nullable Double entropy, double uniqueness, double incompleteness,
            double frequencyAvg, long frequencyMin, long frequencyMax, double frequencySd, double frequencyIqr,
            double valPctMin, double valPctMax, double valPctStd, double constancy,
            int lenMaxWord, int lenMinWord, double lenAvgWord,
            long numberWords, int wordsCntMax, int wordsCntMin, double wordsCntAvg, double wordsCntSd,
            @Nullable String firstWord, @Nullable String lastWord,
            boolean isEmpty, boolean isBinary,
            List<String> commonValues, List<String> commonValuesSoundex,
            String subject, @Nullable String classIri) {
        this(id, datasetId, columnName, columnIndex, sourceType,
                cardinality, entropy, uniqueness, incompleteness,
                frequencyAvg, frequencyMin, frequencyMax, frequencySd, frequencyIqr,
                valPctMin, valPctMax, valPctStd, constancy,
                lenMaxWord, lenMinWord, lenAvgWord,
                numberWords, wordsCntMax, wordsCntMin, wordsCntAvg, wordsCntSd,
                firstWord, lastWord,
                isEmpty, isBinary,
                commonValues, commonValuesSoundex,
                subject, classIri,
                List.of());
    }

    /**
     * Convenience constructor for the pre-3B shape: every caller that predates the subject
     * discriminator profiled a snapshot, so it defaults to {@code SNAPSHOT} with no class IRI.
     */
    public ColumnProfile(
            UUID id, UUID datasetId, String columnName, int columnIndex, String sourceType,
            long cardinality, @Nullable Double entropy, double uniqueness, double incompleteness,
            double frequencyAvg, long frequencyMin, long frequencyMax, double frequencySd, double frequencyIqr,
            double valPctMin, double valPctMax, double valPctStd, double constancy,
            int lenMaxWord, int lenMinWord, double lenAvgWord,
            long numberWords, int wordsCntMax, int wordsCntMin, double wordsCntAvg, double wordsCntSd,
            @Nullable String firstWord, @Nullable String lastWord,
            boolean isEmpty, boolean isBinary,
            List<String> commonValues, List<String> commonValuesSoundex) {
        this(id, datasetId, columnName, columnIndex, sourceType,
                cardinality, entropy, uniqueness, incompleteness,
                frequencyAvg, frequencyMin, frequencyMax, frequencySd, frequencyIqr,
                valPctMin, valPctMax, valPctStd, constancy,
                lenMaxWord, lenMinWord, lenAvgWord,
                numberWords, wordsCntMax, wordsCntMin, wordsCntAvg, wordsCntSd,
                firstWord, lastWord,
                isEmpty, isBinary,
                commonValues, commonValuesSoundex,
                SUBJECT_SNAPSHOT, null);
    }

    /**
     * The {@code subject} of a row freyja produced over the dataset's own snapshot — one row per
     * physical column, the shape every pre-3B call site wrote and the compact constructor still
     * defaults to.
     */
    public static final String SUBJECT_SNAPSHOT = "SNAPSHOT";

    /**
     * The {@code subject} of a row a relational catalog harvest produced (Phase 4B) rather than
     * freyja. Declared here, next to the discriminator's other values, and re-exported as {@code
     * HarvestedColumnProfiles.SUBJECT} — the writer — so the two cannot drift apart.
     */
    public static final String SUBJECT_HARVESTED = "HARVESTED";

    /**
     * The {@code subject} of a row freyja produced by executing the profiling suite over a
     * <em>federated class's live binding</em> (Gap 2 follow-up, per the design ruling): the
     * wrapper-as-view is bound over the same scan queries use ({@code iceberg_scan}/{@code
     * postgres_scan}), the profiling SQL runs against the source in place, and only aggregates
     * cross the wire — nothing is staged to the lake, whatever the source's size. The numbers are
     * exact over the full relation <em>at profile time</em>; they are still never eligible as key
     * evidence, because a live source keeps moving after profiling and a stale "unique" plus a
     * DECLARED edge would eliminate a join unsafely — the same decay argument that excludes
     * {@link #SUBJECT_HARVESTED}. Its NDV/null-fraction/row-count evidence feeds the CBO exactly
     * as a {@code WRAPPER_OUTPUT} row's does.
     */
    public static final String SUBJECT_FEDERATED_LIVE = "FEDERATED_LIVE";

    /**
     * Whether this row is a freyja <em>measurement of the snapshot's own physical columns</em> —
     * the only evidence join discovery and alignment may consume. Both other subjects are excluded,
     * for two independent reasons:
     *
     * <p>A {@code HARVESTED} row comes from {@code pg_class.reltuples} and {@code pg_stats
     * .n_distinct} — a distinct-count estimate and a derived uniqueness, and <b>nothing else</b>.
     * Every other component is a structural zero, {@code null} or empty list ({@code
     * HarvestedColumnProfiles} has no value to put there), including {@code commonValues} and
     * {@code isEmpty}. A zero is not "no evidence" to an algorithm that averages, ranks or scores
     * it — it is evidence of zero.
     *
     * <p>A {@code WRAPPER_OUTPUT} row (Phase 3B, written at every compile) profiles a source
     * class's resolved wrapper output, whose column names are full <em>property IRIs</em>. Those
     * duplicate each physical column under a second name, and a candidate minted from an
     * IRI-shaped column name resolves against no source graph, so it can never be applied — audit
     * JOIN_DISCOVERY-1.
     *
     * <p>Neither is caught by {@link #isEmpty()}, which asks whether the COLUMN is empty and is a
     * flat {@code false} on a harvested row. A consumer that only wants NDV or a row count (the
     * mapping catalog's cost evidence) reads any subject happily, and does.
     */
    public boolean measuredOverSnapshotRows() {
        return SUBJECT_SNAPSHOT.equals(subject);
    }
}

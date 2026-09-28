package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Translates a {@link Harvest} into {@link ColumnProfile} rows a federated relational dataset can
 * carry on its {@code Dataset.columns()} — the persistence home Phase 4B Task 3 chose over a
 * parallel harvested-stats store (see the phase's task-3 report for the comparison).
 *
 * <p>Reusing {@code column_profile} means zero planner changes: once these rows are persisted,
 * {@code ProfileStatsSource.statsFor} returns them exactly as it returns a SNAPSHOT profile, and
 * {@code MappingCatalogOperator}'s existing {@code remapProfiles} step renames each row (keyed by
 * {@code ColumnProfile.columnName()}) from its raw physical name into the columnMap-value
 * namespace via the very same {@code nx:sourceRef} lookup a SNAPSHOT profile already goes through
 * — this class never needs to know about that translation. The one filter that step applies —
 * {@code subject != WRAPPER_OUTPUT} — already passes {@link #SUBJECT} through unchanged.
 *
 * <p><b>Row count, without a dedicated field.</b> {@code ProfileStatsSource.estimateRowCount}
 * derives a dataset's row count from {@code max(cardinality / uniqueness)} across its profiles —
 * the formula a real distinct-count/uniqueness pair already satisfies exactly for the tightest
 * (most unique) column. This class exploits that formula rather than fighting it: for every
 * harvested column it sets {@code cardinality = ndv} and computes {@code uniqueness = ndv /
 * rowCount} itself (never freyja's independently-measured value), so {@code cardinality /
 * uniqueness} recovers the harvested {@code rowCount} <em>exactly</em>, for every column, not just
 * the tightest one. No synthetic "row-count-only" placeholder profile is needed when at least one
 * column's NDV was harvested — see the class javadoc's Limitation note for the one case this does
 * not cover.
 *
 * <p><b>Limitation (documented, not implemented):</b> if a harvest obtains a table's row count
 * ({@code reltuples}) but zero column-level NDV ({@code pg_stats} empty or unreadable for every
 * column of that table), no profile carries the row count at all — {@link #forTable} returns an
 * empty list and the dataset's row count silently stays whatever it was before this harvest ran.
 * {@code RelationalCatalogHarvestServiceTest} (Task 2) characterizes this combination as
 * "structurally close to unreachable via real ANALYZE" (the same statement populates both catalogs
 * together), so a synthetic placeholder column — which would need a sentinel name guaranteed never
 * to collide with a real harvested column — was judged not worth the complexity for a case this
 * unlikely. Revisit if a real warehouse ever separates the two.
 */
@NullMarked
public final class HarvestedColumnProfiles {

    /**
     * The Phase 3B {@code subject} discriminator value for a harvest-sourced profile. Defined on
     * {@link ColumnProfile}, next to {@link ColumnProfile#measuredOverSnapshotRows()} — the
     * predicate every measurement reader filters on — so the one writer here and those readers
     * cannot drift apart.
     */
    public static final String SUBJECT = ColumnProfile.SUBJECT_HARVESTED;

    // ColumnProfile.sourceType() must be non-blank (its compact constructor rejects blank), but a
    // catalog harvest carries no physical-type information the way freyja's own profiling does —
    // reltuples/pg_stats are silent on column types. "UNKNOWN" is honest about that rather than
    // guessing a SQL type this class has no evidence for.
    private static final String SOURCE_TYPE = "UNKNOWN";

    private HarvestedColumnProfiles() {}

    /**
     * One {@link ColumnProfile} per column of {@code table} that {@code harvest} has NDV evidence
     * for — empty when the table was not harvested, or was harvested for row count only (see the
     * class javadoc's Limitation note).
     *
     * @param datasetId the Aran dataset these profiles are being attached to (the caller's own —
     *                  this class does no lookup, table→dataset resolution is the caller's job,
     *                  exactly as {@code HarvestPersistenceService} does it by matching {@code
     *                  Dataset.name()} against {@code table})
     * @param table     the physical table name — an unqualified key into {@code
     *                  harvest.rowCountByTable()}/{@code harvest.ndvByColumn()}, exactly as
     *                  {@code Harvest}'s own javadoc specifies
     */
    public static List<ColumnProfile> forTable(Harvest harvest, UUID datasetId, String table) {
        Long rowCount = harvest.rowCountByTable().get(table);
        List<ColumnProfile> profiles = new ArrayList<>();
        int index = 0;
        for (Map.Entry<Harvest.ColKey, Long> e : harvest.ndvByColumn().entrySet()) {
            if (!e.getKey().table().equals(table)) {
                continue;
            }
            long ndv = e.getValue();
            // uniqueness is DERIVED from rowCount here, not independently measured — that is what
            // makes cardinality/uniqueness recover rowCount exactly (see class javadoc). Absent or
            // non-positive rowCount falls back to treating the column as fully unique (uniqueness
            // 1.0), matching estimateRowCount's own "no reliable ratio" branch (cardinality itself
            // becomes the estimate) rather than dividing by zero or a negative.
            double uniqueness = (rowCount != null && rowCount > 0) ? (ndv / (double) rowCount) : 1.0;
            // The harvested NULL fraction, where the catalog had one. It was written as 0.0 for
            // every column, which told the planner's null-aware join estimate that no key is ever
            // NULL on a federated source.
            double incompleteness = harvest.nullFractionByColumn().getOrDefault(e.getKey(), 0.0);
            profiles.add(new ColumnProfile(
                    UUID.randomUUID(), datasetId, e.getKey().column(), index++, SOURCE_TYPE,
                    ndv, null, uniqueness, incompleteness,
                    0.0, 0L, 0L, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0,
                    0, 0, 0.0,
                    0L, 0, 0, 0.0, 0.0,
                    null, null,
                    false, false,
                    List.of(), List.of(),
                    SUBJECT, null));
        }
        return profiles;
    }
}

package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Resolves per-dataset profiling statistics from the persistence layer.
 *
 * Injected into {@link edu.upc.dtak.aran.operators.queryengine.MappingCatalogOperator}
 * so that {@code MappingCatalog.SourceView.rowCount} is populated at catalog-build time
 * (when the tenant context is available).  The planner then reads it from the catalog
 * without needing a live repository reference.
 */
@Component
public final class ProfileStatsSource {

    private final DatasetRepository datasetRepository;

    public ProfileStatsSource(DatasetRepository datasetRepository) {
        this.datasetRepository = datasetRepository;
    }

    /** A dataset's profile-derived statistics: estimated row count and its column profiles. */
    public record DatasetStats(long rowCount, List<ColumnProfile> profiles) {
        static final DatasetStats EMPTY = new DatasetStats(0L, List.of());

        public boolean isEmpty() {
            return rowCount <= 0 && profiles.isEmpty();
        }
    }

    /**
     * Both statistics from a single load.
     *
     * <p>This used to be two methods — {@code rowCountFor} and {@code profilesFor} — and the
     * catalog builder called both for every source view. Each did its own
     * {@code findById}, and {@code DatasetEntity.columns} is {@code @Fetch(SUBSELECT)}, so a
     * schema of C classes over S sources cost 4·C·S queries to read S datasets. The row count is
     * derived from the very profiles the second call re-fetched.
     *
     * <p>Returns {@link DatasetStats#EMPTY} when the dataset has no profile or the ID is not a
     * valid UUID.
     */
    public DatasetStats statsFor(TenantContext tenant, String datasetId) {
        try {
            UUID uid = UUID.fromString(datasetId);
            return datasetRepository.findById(tenant, uid)
                    .map(ds -> {
                        List<ColumnProfile> profiles = ds.columns();
                        return new DatasetStats(estimateRowCount(profiles), profiles);
                    })
                    .orElse(DatasetStats.EMPTY);
        } catch (IllegalArgumentException e) {
            return DatasetStats.EMPTY;
        }
    }

    /**
     * Derives total row count from column-level profile metrics.
     * Formula: for each column, {@code rowCount ≈ cardinality / uniqueness}.
     * Returns the maximum estimate across all columns (the key column gives the tightest bound).
     *
     * <p>Public (Phase 3B, Task 3): {@code MappingCatalogOperator} — a different package — needs
     * to re-derive a row count from a profiles list it has already filtered (the
     * {@code remapProfiles} result, once WRAPPER_OUTPUT rows for another class are excluded from
     * it), rather than from {@link #statsFor}'s unfiltered one.
     */
    public static long estimateRowCount(List<ColumnProfile> profiles) {
        if (profiles.isEmpty()) return 0L;
        double maxEst = 0.0;
        for (ColumnProfile cp : profiles) {
            double est;
            if (cp.uniqueness() > 1e-6) {
                est = cp.cardinality() / cp.uniqueness();
            } else {
                est = cp.cardinality();
            }
            maxEst = Math.max(maxEst, est);
        }
        return (long) maxEst;
    }

    /**
     * One class's {@code WRAPPER_OUTPUT}-subject profiles out of an already-loaded dataset's full
     * profile list, with row count re-derived from just that subset — Phase 3B's preferred
     * evidence for an OPAQUE source view, which cannot be profiled at the physical-column level
     * {@link #statsFor}'s SNAPSHOT profiles describe.
     *
     * <p>Takes the profiles {@link #statsFor} already fetched rather than issuing a second
     * repository load per (dataset, class) pair — see this class's own javadoc for why a second
     * {@code findById} per source view would reopen the very N+1 the single-load merge closed.
     *
     * <p>{@link DatasetStats#EMPTY} when this class has no WRAPPER_OUTPUT profiles (yet) — the
     * caller's cue to fall back to the SNAPSHOT path instead.
     */
    public static DatasetStats wrapperOutputFor(List<ColumnProfile> profiles, String classIri) {
        return subjectProfilesFor(profiles, classIri, "WRAPPER_OUTPUT");
    }

    /**
     * As {@link #wrapperOutputFor}, for the {@code FEDERATED_LIVE} subject (Gap 2 follow-up):
     * the same wrapper-shaped, class-scoped evidence, computed by executing the profiling suite
     * over the federated class's live binding — exact over the full relation at profile time. A
     * class only ever has one of the two — a lake snapshot and a live binding are mutually
     * exclusive per {@code SourceAccess} — but the caller still asks in preference order so a
     * stale row of the other subject cannot shadow fresh evidence.
     */
    public static DatasetStats federatedLiveFor(List<ColumnProfile> profiles, String classIri) {
        return subjectProfilesFor(profiles, classIri, "FEDERATED_LIVE");
    }

    private static DatasetStats subjectProfilesFor(List<ColumnProfile> profiles, String classIri,
                                                   String subject) {
        List<ColumnProfile> matching = profiles.stream()
                .filter(p -> subject.equals(p.subject()) && classIri.equals(p.classIri()))
                .toList();
        return matching.isEmpty() ? DatasetStats.EMPTY : new DatasetStats(estimateRowCount(matching), matching);
    }
}

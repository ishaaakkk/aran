package edu.upc.dtak.aran.core.source;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/**
 * Best-effort live catalog statistics a {@link RelationalEngine} could harvest: approximate row
 * counts, approximate column NDV (number of distinct values), and declared foreign keys.
 *
 * <p>"Best-effort" describes every field, not a caveat bolted onto them: a catalog harvest never
 * blocks a query or a plan on the source database being reachable, so a piece that could not be
 * read is simply absent from the map rather than making the whole {@code Harvest} an error. See
 * {@code CatalogHarvestQueries}'s javadoc for how the SQL is built and parsed, and {@code
 * JdbcCatalogHarvester} (aran-operators/bootstrapping) for where the connection is actually
 * opened — this record itself carries no I/O.
 *
 * <p>Declared foreign keys are the DECLARED trust tier: a constraint the source database itself
 * enforces, stronger evidence than a discovered/accepted join (HYPOTHESIS tier) but not proof the
 * data is currently clean (that would be VERIFIED, from actually checking the rows).
 *
 * <h2>{@code fkHarvestSucceeded} — telling "no FKs" apart from "couldn't tell"</h2>
 *
 * <p>{@code declaredForeignKeys} degrading to empty is overloaded: it is the honest answer for a
 * schema that really has zero foreign keys, and it is also what {@code JdbcCatalogHarvester}'s own
 * per-piece try/catch produces when the FK query alone fails (a narrower grant on {@code
 * information_schema}/{@code pg_constraint} than the stats views, say, or a transient lock) while
 * the connection and the row-count/NDV pieces succeed. Those two situations must not be
 * indistinguishable to a caller that persists this: {@code HarvestPersistenceService} replaces
 * every previously-known DECLARED edge with whatever {@code declaredForeignKeys} says, so
 * conflating "genuinely none" with "the read failed" would let one bad tick of a recurring refresh
 * wipe good evidence for no reason connected to the source schema actually changing. {@code
 * fkHarvestSucceeded} is the flag that keeps the two apart: {@code true} means {@code
 * declaredForeignKeys} is this harvest's real, trustworthy answer (including a genuinely empty
 * one); {@code false} means the FK piece itself did not complete and {@code declaredForeignKeys}
 * here carries no information a caller should act on.
 *
 * @param rowCountByTable     approximate row count per table (e.g. PostgreSQL's {@code
 *                            pg_class.reltuples}), keyed by the table's unqualified name
 * @param ndvByColumn         approximate distinct-value count per column that has stats, keyed by
 *                            {@link ColKey}; a column with no stats yet is simply absent, not zero
 * @param declaredForeignKeys foreign keys the source database declares — meaningful only when
 *                            {@code fkHarvestSucceeded} is {@code true}; see above
 * @param fkHarvestSucceeded  whether the FK piece of this harvest actually completed, as opposed
 *                            to failing independently behind its own try/catch while the
 *                            connection and the other pieces succeeded
 * @param nullFractionByColumn fraction of NULLs per analyzed column (PostgreSQL's {@code
 *                            pg_stats.null_frac}), keyed like {@code ndvByColumn}; absent, not
 *                            zero, for a column with no stats
 */
@NullMarked
public record Harvest(
        Map<String, Long> rowCountByTable,
        Map<ColKey, Long> ndvByColumn,
        List<ForeignKey> declaredForeignKeys,
        boolean fkHarvestSucceeded,
        Map<ColKey, Double> nullFractionByColumn) {

    /**
     * No catalog was reachable, or the engine has no harvest capability at all. The same "absent
     * degrades to an honest no" shape as {@link RelationalEngine}'s other capabilities.
     */
    public static final Harvest EMPTY = new Harvest(Map.of(), Map.of(), List.of(), false, Map.of());

    /** Defensive copies: a caller handed a mutable map/list must not be able to mutate this after. */
    public Harvest {
        rowCountByTable = Map.copyOf(rowCountByTable);
        ndvByColumn = Map.copyOf(ndvByColumn);
        declaredForeignKeys = List.copyOf(declaredForeignKeys);
        nullFractionByColumn = Map.copyOf(nullFractionByColumn);
    }

    /** A harvest without null-fraction evidence — every call site written before it was read. */
    public Harvest(Map<String, Long> rowCountByTable, Map<ColKey, Long> ndvByColumn,
                   List<ForeignKey> declaredForeignKeys, boolean fkHarvestSucceeded) {
        this(rowCountByTable, ndvByColumn, declaredForeignKeys, fkHarvestSucceeded, Map.of());
    }

    /**
     * Back-compat for every call site written before {@code fkHarvestSucceeded} existed (Task 2/3,
     * and any test not specifically about this distinction): defaults to {@code true}, i.e. "{@code
     * declaredForeignKeys} is this harvest's authoritative answer" — the behaviour every such site
     * already assumed, since the failure mode this field distinguishes did not have a name yet.
     */
    public Harvest(Map<String, Long> rowCountByTable, Map<ColKey, Long> ndvByColumn,
                   List<ForeignKey> declaredForeignKeys) {
        this(rowCountByTable, ndvByColumn, declaredForeignKeys, true);
    }

    /**
     * One column, identified by its table and column name, exactly as the source database spells
     * them (no case-folding, no IRI-mangling — this is a harvest-internal key, not a graph term).
     */
    public record ColKey(String table, String column) {}

    /**
     * One foreign key the source database declares. {@code fromCols}/{@code toCols} are ordered
     * and the same length: position {@code i} of {@code fromCols} references position {@code i}
     * of {@code toCols} — the composite-key case, alongside the single-column case where both
     * lists hold exactly one element.
     */
    public record ForeignKey(String fromTable, List<String> fromCols, String toTable, List<String> toCols) {

        public ForeignKey {
            fromCols = List.copyOf(fromCols);
            toCols = List.copyOf(toCols);
            if (fromCols.isEmpty() || fromCols.size() != toCols.size()) {
                throw new IllegalArgumentException(
                        "fromCols and toCols must be non-empty and the same size: "
                        + fromCols + " vs " + toCols);
            }
        }
    }
}

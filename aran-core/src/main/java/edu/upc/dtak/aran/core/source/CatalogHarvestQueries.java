package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.source.Harvest.ColKey;
import edu.upc.dtak.aran.core.source.Harvest.ForeignKey;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/**
 * The SQL an engine's catalog harvest runs, and how to read back what it returns.
 *
 * <p>A pure, per-engine capability, exactly like {@link RelationalEngine#quoteIdentifier} and
 * {@link RelationalEngine#federatedScan}: it builds SQL text and turns rows into {@link Harvest}
 * pieces, and touches no I/O. It has to be per-engine because both halves are — PostgreSQL's
 * {@code pg_stats.n_distinct} sign convention (negative means "ratio of the row count", positive
 * means "this many values") has no MySQL equivalent, and a different engine's declared-FK catalog
 * is shaped differently again.
 *
 * <h2>Why rows are {@code List<Map<String,Object>>}, not a {@code ResultSet}</h2>
 *
 * <p>Running the SQL over a live JDBC connection is a separate concern that belongs where the
 * sandboxed driver machinery already lives — {@code JdbcCatalogHarvester} in
 * aran-operators/bootstrapping, mirroring the {@code JdbcBulkExtractor}/{@code JdbcTableExtractor}
 * split (connection-taking logic vs. driver-resolving wiring). Taking rows as plain data rather
 * than {@code java.sql.ResultSet} keeps this interface — and every implementation of it — testable
 * with synthetic data and keeps {@code java.sql} out of aran-core entirely. Each row maps a column
 * label (lower-cased by the caller) to its value, i.e. exactly what {@code
 * ResultSetMetaData.getColumnLabel} plus {@code ResultSet.getObject} would hand over for that row.
 *
 * <h2>Best-effort</h2>
 *
 * <p>Implementations should assume rows can be empty (no stats yet, an unreachable catalog view)
 * but should not need to guard against malformed rows from their own SQL — a harvest that fails
 * partway degrades one piece of {@link Harvest} to empty rather than the whole thing; see {@code
 * JdbcCatalogHarvester}'s per-piece error handling.
 */
@NullMarked
public interface CatalogHarvestQueries {

    /** Approximate row count per table in {@code schema} (e.g. PostgreSQL's {@code pg_class.reltuples}). */
    String rowCountSql(String schema);

    /** Turn {@link #rowCountSql} rows into a row count by (unqualified) table name. */
    Map<String, Long> parseRowCounts(List<Map<String, Object>> rows);

    /** Approximate distinct-value count per analyzed column in {@code schema}. */
    String ndvSql(String schema);

    /**
     * Turn {@link #ndvSql} rows into an NDV by column. A column ANALYZE (or its engine's
     * equivalent) has not yet touched simply has no row here — that is absence, not failure, and
     * callers must not treat a missing {@link ColKey} as zero.
     */
    Map<ColKey, Long> parseNdv(List<Map<String, Object>> rows);

    /**
     * Turn the same {@link #ndvSql} rows into a NULL fraction by column, for an engine whose
     * statistics carry one. Absent means unknown, never zero; the default knows none.
     */
    default Map<ColKey, Double> parseNullFractions(List<Map<String, Object>> rows) {
        return Map.of();
    }

    /** The referencing side (table + columns) of every declared foreign key in {@code schema}. */
    String foreignKeyFromColumnsSql(String schema);

    /** The referenced side (table + columns) of every declared foreign key in {@code schema}. */
    String foreignKeyToColumnsSql(String schema);

    /**
     * Turn {@link #foreignKeyFromColumnsSql} and {@link #foreignKeyToColumnsSql} rows into {@link
     * ForeignKey}s, one referencing column correctly paired to one referenced column per position.
     *
     * <p><b>A foreign key must be identified by something table-unique, never by constraint name
     * alone.</b> A constraint name is not schema-unique on every engine — on PostgreSQL it is unique
     * only per table, so two tables can each carry {@code CONSTRAINT fk ...} — and grouping rows by
     * name alone would merge them and record a foreign key against the wrong table. Carry an
     * identity that cannot collide across tables (PostgreSQL: {@code pg_constraint.oid}; see {@code
     * PostgresCatalogHarvestQueries}) and group on it, with the referencing/referenced tables coming
     * from that same identity rather than from an ambiguous name.
     *
     * <p><b>Column pairing must be by an ordinal both queries can agree on, never by row-arrival
     * order.</b> A composite key's rows are not guaranteed to arrive in declared-column order, so an
     * implementation that zips the two row lists positionally will silently swap columns. Carry
     * whatever ordinal pairs the two sides (PostgreSQL: the {@code WITH ORDINALITY} position of the
     * parallel {@code conkey}/{@code confkey} arrays) as an extra column and pair on it explicitly.
     * When a foreign key's rows disagree on the referencing/referenced table, carry duplicate or
     * missing ordinals, or cannot be paired completely, drop that whole foreign key (with a WARN)
     * rather than emit a guessed or shrunken pairing — best-effort means empty or partial, never
     * wrong.
     */
    List<ForeignKey> parseForeignKeys(List<Map<String, Object>> fromRows, List<Map<String, Object>> toRows);
}

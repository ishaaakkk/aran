package edu.upc.dtak.aran.core.source.engines;

import edu.upc.dtak.aran.core.source.CatalogHarvestQueries;
import edu.upc.dtak.aran.core.source.Harvest.ColKey;
import edu.upc.dtak.aran.core.source.Harvest.ForeignKey;
import edu.upc.dtak.aran.core.util.Names;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL's catalog harvest: {@code pg_class.reltuples} for approximate row counts, {@code
 * pg_stats.n_distinct} for approximate column NDV, and {@code information_schema} for declared
 * foreign keys.
 *
 * <p>Every query here reads a catalog <em>view</em>, never a table's own rows — a harvest is as
 * cheap against a billion-row table as an empty one, the same promise {@code JdbcBootstrap} makes
 * for schema introspection and for the same reason: this can run from a web request or a
 * background job without becoming an accidental table scan on someone's production database.
 *
 * <h2>The {@code n_distinct} sign</h2>
 *
 * <p>PostgreSQL's ANALYZE stores {@code pg_stats.n_distinct} two ways: a positive value is an
 * absolute count of distinct values (small/enum-like columns — ANALYZE saw the same handful of
 * values repeat and just counts them); a negative value is {@code -n_distinct} <em>times the row
 * count</em> — ANALYZE's way of saying "this looks like it scales with table size" and the
 * reliable case is a column that is (or looks like) a key: a column where every sampled value was
 * distinct reports exactly {@code -1}, meaning "every row is distinct". {@link
 * #ndvFromNDistinct(double, double)} is the one place that arithmetic happens; it returns {@code
 * null} — omit the column, not zero it — when the row count needed to resolve a ratio is itself
 * unknown (see the next section).
 *
 * <h2>An unknown row count is omitted, not zero</h2>
 *
 * <p>PostgreSQL reports {@code pg_class.reltuples} as a negative sentinel for a table that has
 * never been vacuumed or analyzed — "no estimate", not "confirmed empty". {@link
 * #parseRowCounts} omits that table from the map entirely rather than reporting {@code 0L}, the
 * same "absent, not zero" contract {@link #parseNdv} already had to have for a column with no
 * {@code pg_stats} row: a caller (cost estimation, elimination) must be able to tell "never
 * analyzed" apart from "confirmed empty" by whether the key is present at all.
 *
 * <h2>Declared foreign keys: identified by OID, via {@code pg_constraint}</h2>
 *
 * <p>An earlier version of this class read {@code information_schema} and identified each foreign
 * key by {@code constraint_name} alone. That is unsound on PostgreSQL: a constraint name is unique
 * only <em>per table</em> ({@code pg_constraint}'s {@code (conrelid, conname)}), not per schema, so
 * two different tables can each carry {@code CONSTRAINT fk ...}. The SQL-standard
 * {@code information_schema} views assume schema-unique names and offer no referencing-table column
 * on {@code referential_constraints} at all, so same-named constraints' rows cross-join and merge,
 * and a foreign key is recorded against whichever table's rows happened to arrive first — a
 * <em>wrong</em> DECLARED-tier edge, which is worse than a missing one: it is exactly the tier
 * callers are expected to trust without re-checking. A same-named unique constraint on two
 * different referenced tables, and an unqualified table name colliding across schemas, are the same
 * bug on the referenced side.
 *
 * <p>Both FK queries therefore read {@code pg_constraint} directly and carry {@code con.oid} — the
 * constraint's own OID, globally unique — as {@code constraint_id}. {@link
 * #parseForeignKeys(List, List)} groups strictly by that OID, so two constraints named alike on
 * different tables never merge, and the referencing/referenced tables come from {@code conrelid}/
 * {@code confrelid} by OID (not by an ambiguous name), immune to same-name collisions by
 * construction. The referencing and referenced column lists are {@code conkey}/{@code confkey} —
 * parallel arrays that PostgreSQL keeps in matching order (element {@code i} of {@code conkey}
 * references element {@code i} of {@code confkey}) — unnested {@code WITH ORDINALITY}, so the
 * per-position pairing is the array index itself, never row-arrival order. The parser still
 * defensively drops (with a WARN) any constraint whose rows disagree on the referencing/referenced
 * table, carry duplicate or missing ordinals, or cannot be fully paired, rather than emit a guessed
 * or shrunken key. Best-effort means empty or partial, never wrong.
 */
@NullMarked
public final class PostgresCatalogHarvestQueries implements CatalogHarvestQueries {

    private static final Logger log = LoggerFactory.getLogger(PostgresCatalogHarvestQueries.class);

    @Override
    public String rowCountSql(String schema) {
        return "SELECT c.relname AS table_name, c.reltuples AS row_count "
                + "FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = '" + Names.sqlLiteral(schema) + "' "
                + "AND c.relkind IN ('r', 'p')"; // ordinary + partitioned tables; not views/indexes
    }

    @Override
    public Map<String, Long> parseRowCounts(List<Map<String, Object>> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String table = (String) row.get("table_name");
            if (table == null) {
                continue;
            }
            double reltuples = toDouble(row.get("row_count"));
            if (reltuples < 0) {
                // See the class javadoc's "An unknown row count is omitted, not zero" section.
                continue;
            }
            counts.put(table, Math.round(reltuples));
        }
        return counts;
    }

    @Override
    public String ndvSql(String schema) {
        return "SELECT s.tablename AS table_name, s.attname AS column_name, "
                + "s.n_distinct AS n_distinct, s.null_frac AS null_frac, c.reltuples AS row_count "
                + "FROM pg_stats s "
                + "JOIN pg_namespace n ON n.nspname = s.schemaname "
                + "JOIN pg_class c ON c.relname = s.tablename AND c.relnamespace = n.oid "
                + "WHERE s.schemaname = '" + Names.sqlLiteral(schema) + "'";
        // No row at all here for a column ANALYZE has not touched — pg_stats only has rows for
        // analyzed columns, which is exactly the "no stats -> absent, not zero" contract.
    }

    @Override
    public Map<ColKey, Long> parseNdv(List<Map<String, Object>> rows) {
        Map<ColKey, Long> ndv = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String table = (String) row.get("table_name");
            String column = (String) row.get("column_name");
            if (table == null || column == null) {
                continue;
            }
            double nDistinct = toDouble(row.get("n_distinct"));
            double rowCount = toDouble(row.get("row_count"));
            Long value = ndvFromNDistinct(nDistinct, rowCount);
            if (value != null) {
                ndv.put(new ColKey(table, column), value);
            }
        }
        return ndv;
    }

    @Override
    public Map<ColKey, Double> parseNullFractions(List<Map<String, Object>> rows) {
        Map<ColKey, Double> fractions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String table = (String) row.get("table_name");
            String column = (String) row.get("column_name");
            if (table == null || column == null || !(row.get("null_frac") instanceof Number n)) {
                continue;
            }
            fractions.put(new ColKey(table, column), Math.max(0.0, Math.min(1.0, n.doubleValue())));
        }
        return fractions;
    }

    /** See the class javadoc's "The n_distinct sign" section. {@code null} means "omit this column". */
    private static @Nullable Long ndvFromNDistinct(double nDistinct, double rowCount) {
        if (nDistinct < 0) {
            if (rowCount < 0) {
                // The ratio needs a real row count to become a count, and reltuples is itself
                // unknown here (see parseRowCounts) — nothing honest to multiply by.
                return null;
            }
            return Math.max(0L, Math.round(-nDistinct * rowCount));
        }
        return Math.max(0L, Math.round(nDistinct));
    }

    @Override
    public String foreignKeyFromColumnsSql(String schema) {
        // pg_constraint, not information_schema: con.oid identifies the FK uniquely (a constraint
        // name is unique only per table on PostgreSQL), conrelid names the referencing table by OID,
        // and unnest(conkey) WITH ORDINALITY gives each referencing column its array position — the
        // shared pairing ordinal with the referenced side. See the class javadoc.
        return "SELECT con.oid AS constraint_id, con.conname AS constraint_name, "
                + "rel.relname AS from_table, att.attname AS from_column, "
                + "k.ord AS ordinal_position, k.ord AS to_ordinal "
                + "FROM pg_constraint con "
                + "JOIN pg_class rel ON rel.oid = con.conrelid "
                + "JOIN pg_namespace nsp ON nsp.oid = con.connamespace "
                + "CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) "
                + "JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum "
                + "WHERE con.contype = 'f' "
                + "AND nsp.nspname = '" + Names.sqlLiteral(schema) + "' "
                + "ORDER BY con.oid, k.ord";
    }

    @Override
    public String foreignKeyToColumnsSql(String schema) {
        // Same FK rows as the from-side query (scoped by the referencing constraint's own namespace,
        // con.connamespace), but confrelid/confkey resolve the REFERENCED table and columns by OID.
        // Because confrelid is a specific table OID, the referenced table is exact — a same-named
        // table in another schema, or a same-named unique constraint on another table, cannot
        // resolve wrong the way the old information_schema name-join could. k.ord is the position
        // within confkey, which matches the from-side conkey position element-for-element.
        return "SELECT con.oid AS constraint_id, con.conname AS constraint_name, "
                + "frel.relname AS to_table, fatt.attname AS to_column, k.ord AS to_ordinal "
                + "FROM pg_constraint con "
                + "JOIN pg_class frel ON frel.oid = con.confrelid "
                + "JOIN pg_namespace nsp ON nsp.oid = con.connamespace "
                + "CROSS JOIN LATERAL unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord) "
                + "JOIN pg_attribute fatt ON fatt.attrelid = con.confrelid AND fatt.attnum = k.attnum "
                + "WHERE con.contype = 'f' "
                + "AND nsp.nspname = '" + Names.sqlLiteral(schema) + "' "
                + "ORDER BY con.oid, k.ord";
    }

    /** One referencing column: its own declared position, name, and which referenced position it maps to. */
    private record FromCol(long ordinalPosition, String column, @Nullable Long toOrdinal) {}

    @Override
    public List<ForeignKey> parseForeignKeys(
            List<Map<String, Object>> fromRows, List<Map<String, Object>> toRows) {

        // The grouping identity is the constraint's own OID (pg_constraint.oid, aliased
        // constraint_id) — globally unique, so two same-named constraints on different tables can
        // never merge. See the class javadoc. The constraint name is kept only for WARN messages.
        Map<Long, String> nameById = new LinkedHashMap<>();
        Map<Long, String> fromTableById = new LinkedHashMap<>();
        Map<Long, List<FromCol>> fromColsById = new LinkedHashMap<>();
        // A constraint that must be dropped rather than emitted: an ambiguous table, a duplicate or
        // missing ordinal, or any malformed row on either side. Skipping the WHOLE FK — never just
        // the offending column — is what keeps a partial read from shrinking a composite key into a
        // narrower, over-claiming edge.
        Set<Long> skip = new HashSet<>();

        for (Map<String, Object> row : fromRows) {
            Long id = toLongOrNull(row.get("constraint_id"));
            if (id == null) {
                continue; // can't attribute this row to any constraint — nothing safe to do with it
            }
            String name = asString(row.get("constraint_name"));
            if (name != null) {
                nameById.putIfAbsent(id, name);
            }
            String table = asString(row.get("from_table"));
            String column = asString(row.get("from_column"));
            Long ordinalPosition = toLongOrNull(row.get("ordinal_position"));
            if (table == null || column == null || ordinalPosition == null) {
                // A malformed referencing row skips the whole FK, not just this column.
                skip.add(id);
                continue;
            }
            // >1 distinct referencing table for one constraint OID cannot happen against a real
            // pg_constraint (conrelid is a single OID), but if a future catalog read ever produced
            // it, refuse rather than attribute to whichever table arrived first.
            String previousTable = fromTableById.putIfAbsent(id, table);
            if (previousTable != null && !previousTable.equals(table)) {
                skip.add(id);
            }
            fromColsById.computeIfAbsent(id, unused -> new ArrayList<>())
                    .add(new FromCol(ordinalPosition, column, toLongOrNull(row.get("to_ordinal"))));
        }
        for (Map.Entry<Long, List<FromCol>> entry : fromColsById.entrySet()) {
            List<FromCol> cols = entry.getValue();
            long distinctOrdinals = cols.stream().map(FromCol::ordinalPosition).distinct().count();
            if (distinctOrdinals != cols.size()) {
                // Two referencing columns at the same declared position — the column list cannot be
                // ordered unambiguously, so the FK is dropped rather than guessed.
                skip.add(entry.getKey());
            }
            // Defensive, not load-bearing: foreignKeyFromColumnsSql already ORDERs by k.ord.
            // Re-sorting here means a future change to that ORDER BY fails safe (a wrong fromCols
            // order) rather than silently, the same instinct behind pairing by ordinal on the
            // referenced side.
            cols.sort(Comparator.comparingLong(FromCol::ordinalPosition));
        }

        Map<Long, String> toTableById = new LinkedHashMap<>();
        Map<Long, Map<Long, String>> toColumnByOrdinal = new LinkedHashMap<>();
        for (Map<String, Object> row : toRows) {
            Long id = toLongOrNull(row.get("constraint_id"));
            if (id == null) {
                continue;
            }
            String table = asString(row.get("to_table"));
            String column = asString(row.get("to_column"));
            Long ordinal = toLongOrNull(row.get("to_ordinal"));
            if (table == null || column == null || ordinal == null) {
                skip.add(id);
                continue;
            }
            String previousTable = toTableById.putIfAbsent(id, table);
            if (previousTable != null && !previousTable.equals(table)) {
                // Two distinct referenced tables for one constraint OID — same impossibility, same
                // refusal as the referencing side above.
                skip.add(id);
            }
            Map<Long, String> byOrdinal =
                    toColumnByOrdinal.computeIfAbsent(id, unused -> new LinkedHashMap<>());
            String previous = byOrdinal.putIfAbsent(ordinal, column);
            if (previous != null && !previous.equals(column)) {
                // Two different referenced columns claim the same position — a catalog-read anomaly,
                // not a pairing this class will guess through.
                skip.add(id);
            }
        }

        List<ForeignKey> foreignKeys = new ArrayList<>();
        for (Map.Entry<Long, List<FromCol>> entry : fromColsById.entrySet()) {
            Long id = entry.getKey();
            String name = nameById.getOrDefault(id, "oid=" + id);
            List<FromCol> fromCols = entry.getValue();
            Map<Long, String> toByOrdinal = toColumnByOrdinal.get(id);

            if (skip.contains(id)) {
                log.warn("catalog harvest: skipping foreign key {} — its rows did not resolve to a "
                        + "single referencing/referenced table with a complete, unambiguous set of "
                        + "ordinals", name);
                continue;
            }
            if (toByOrdinal == null) {
                log.warn("catalog harvest: skipping foreign key {} — no referenced columns could "
                        + "be resolved for it", name);
                continue;
            }

            List<String> fromColumns = new ArrayList<>(fromCols.size());
            List<String> toColumns = new ArrayList<>(fromCols.size());
            boolean pairable = true;
            for (FromCol fromCol : fromCols) {
                String toColumn = fromCol.toOrdinal() == null ? null : toByOrdinal.get(fromCol.toOrdinal());
                if (toColumn == null) {
                    pairable = false;
                    break;
                }
                fromColumns.add(fromCol.column());
                toColumns.add(toColumn);
            }

            if (!pairable) {
                // Best-effort means empty/partial, never guessed: one referencing column whose
                // target position could not be resolved drops the whole FK rather than being
                // paired with a guess.
                log.warn("catalog harvest: skipping foreign key {} — a referencing column's "
                        + "ordinal did not match any referenced column", name);
                continue;
            }
            foreignKeys.add(new ForeignKey(
                    fromTableById.get(id), fromColumns, toTableById.get(id), toColumns));
        }
        return foreignKeys;
    }

    private static @Nullable String asString(@Nullable Object value) {
        return value instanceof String s ? s : null;
    }

    private static @Nullable Long toLongOrNull(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static double toDouble(@Nullable Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}

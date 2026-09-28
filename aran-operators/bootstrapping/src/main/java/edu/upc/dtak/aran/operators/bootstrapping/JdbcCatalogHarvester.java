package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.source.CatalogHarvestQueries;
import edu.upc.dtak.aran.core.source.Harvest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one engine's {@link CatalogHarvestQueries} over an already-open connection and assembles
 * the {@link Harvest}.
 *
 * <p>Takes a {@link Connection} rather than a {@code RelationalDatabaseRepository}, the same
 * choice {@code JdbcBulkExtractor} makes and for the same reason: opening a connection is a
 * distinct concern (driver resolution, credentials, the sandboxed classloader in {@code
 * JdbcDriverRegistry}/{@code DriverDownloader}) that belongs in {@code
 * RelationalCatalogHarvestService}, and duplicating any of it here would be a second, weaker copy
 * of a security control. Keeping this class connection-agnostic is also what lets it be tested
 * directly against a real Postgres (Testcontainers) without touching driver-download machinery at
 * all.
 *
 * <h2>Best-effort, two levels deep</h2>
 *
 * <p>Each of the three harvest pieces — row counts, NDV, declared foreign keys — runs and is
 * parsed independently, in its own try/catch: one piece's catalog view being unreadable (a
 * permission wrinkle on {@code pg_stats}, say) degrades only that piece to empty, not the whole
 * harvest, matching the "empty/<em>partial</em>" wording of the best-effort contract. The other,
 * outer level — the connection itself being unreachable — is {@code
 * RelationalCatalogHarvestService}'s job; this class never opens or closes one.
 *
 * <p>The declared-FK piece additionally reports whether it succeeded ({@link
 * Harvest#fkHarvestSucceeded()}), because unlike row counts/NDV — where a missing entry already
 * means "not analyzed yet" — an empty FK list is also the legitimate answer for a schema with no
 * foreign keys at all, so failure and "genuinely none" need a separate signal. See that field's
 * javadoc and {@code HarvestPersistenceService}, the caller that acts on it.
 */
@NullMarked
final class JdbcCatalogHarvester {

    private static final Logger log = LoggerFactory.getLogger(JdbcCatalogHarvester.class);

    Harvest harvest(Connection conn, CatalogHarvestQueries queries, String schema) {
        Map<String, Long> rowCounts = tryHarvest("row counts",
                () -> queries.parseRowCounts(query(conn, queries.rowCountSql(schema))))
                .orElseGet(Map::of);

        // One read of the column statistics feeds both NDV and the NULL fraction.
        List<Map<String, Object>> columnStats = tryHarvest("column statistics",
                () -> query(conn, queries.ndvSql(schema)))
                .orElseGet(List::of);
        Map<Harvest.ColKey, Long> ndv = tryHarvest("column NDV",
                () -> queries.parseNdv(columnStats))
                .orElseGet(Map::of);
        Map<Harvest.ColKey, Double> nullFractions = tryHarvest("column null fractions",
                () -> queries.parseNullFractions(columnStats))
                .orElseGet(Map::of);

        // Kept as an Optional (not immediately collapsed to a List) so its presence can carry the
        // fkHarvestSucceeded signal through to the Harvest below — see that field's javadoc for why
        // "empty" and "failed" must stay distinguishable here, one layer above where they are
        // produced.
        Optional<List<Harvest.ForeignKey>> foreignKeyResult = tryHarvest("declared foreign keys", () ->
                queries.parseForeignKeys(
                        query(conn, queries.foreignKeyFromColumnsSql(schema)),
                        query(conn, queries.foreignKeyToColumnsSql(schema))));

        return new Harvest(rowCounts, ndv, foreignKeyResult.orElseGet(List::of),
                foreignKeyResult.isPresent(), nullFractions);
    }

    /**
     * Runs {@code body}, degrading to empty on any failure — a bad catalog query must never take
     * down the two harvest pieces either side of it. Catches {@link RuntimeException} as well as
     * {@link SQLException}: the caller is a {@link CatalogHarvestQueries} implementation this
     * class does not control, and "never throws" is the whole point of best-effort.
     */
    private <T> Optional<T> tryHarvest(String what, SqlSupplier<T> body) {
        try {
            return Optional.of(body.get());
        } catch (RuntimeException | SQLException e) {
            log.warn("catalog harvest of {} failed — {}", what, e.getMessage());
            return Optional.empty();
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    /**
     * Runs {@code sql} and returns its rows as column-label-to-value maps, labels lower-cased so
     * a {@link CatalogHarvestQueries} implementation never has to think about a driver's default
     * casing (PostgreSQL returns unquoted aliases lower-case already; a future engine's driver
     * might not).
     */
    private static List<Map<String, Object>> query(Connection conn, String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            List<String> labels = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                labels.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>(columnCount * 2);
                for (int i = 1; i <= columnCount; i++) {
                    row.put(labels.get(i - 1), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }
}

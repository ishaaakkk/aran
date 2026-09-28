package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.source.CatalogHarvestQueries;
import edu.upc.dtak.aran.core.source.Harvest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcCatalogHarvester}'s per-piece independence — in particular the FK piece's success
 * signal ({@link Harvest#fkHarvestSucceeded()}), which is what {@link HarvestPersistenceService}
 * (the durability fix, Phase 4B Task 5) relies on to tell "this schema genuinely has no FKs" apart
 * from "the FK query itself failed while the connection and the other pieces succeeded".
 *
 * <p>The {@link Connection}/{@link Statement}/{@link ResultSet} here are mocked to always hand
 * back one empty row set — what actually distinguishes success from failure in these tests is
 * {@link CatalogHarvestQueries#parseForeignKeys}, a fake this test controls directly, standing in
 * for a real {@code PostgresCatalogHarvestQueries} throwing on a permission-denied or malformed
 * row set. Testing at this seam (rather than against Testcontainers, as {@code
 * RelationalCatalogHarvestServiceTest} does for the happy path) is what makes "the FK query alone
 * fails while everything else succeeds" a two-line fixture instead of a live-database permission
 * dance.
 */
class JdbcCatalogHarvesterTest {

    private final JdbcCatalogHarvester harvester = new JdbcCatalogHarvester();

    /** A CatalogHarvestQueries whose FK parse step can be told to fail or return zero independently. */
    private static final class FakeQueries implements CatalogHarvestQueries {
        boolean failForeignKeys;
        boolean noForeignKeys;

        @Override public String rowCountSql(String schema) {
            return "SELECT 1";
        }

        @Override public Map<String, Long> parseRowCounts(List<Map<String, Object>> rows) {
            return Map.of("t", 42L);
        }

        @Override public String ndvSql(String schema) {
            return "SELECT 1";
        }

        @Override public Map<Harvest.ColKey, Long> parseNdv(List<Map<String, Object>> rows) {
            return Map.of(new Harvest.ColKey("t", "c"), 7L);
        }

        @Override public String foreignKeyFromColumnsSql(String schema) {
            return "SELECT 1";
        }

        @Override public String foreignKeyToColumnsSql(String schema) {
            return "SELECT 1";
        }

        @Override public List<Harvest.ForeignKey> parseForeignKeys(
                List<Map<String, Object>> fromRows, List<Map<String, Object>> toRows) {
            if (failForeignKeys) {
                // Stands in for a real driver's SQLException on a permission-denied catalog view —
                // JdbcCatalogHarvester's tryHarvest() catches RuntimeException too (see its javadoc),
                // so an unchecked exception here is a faithful substitute.
                throw new IllegalStateException("permission denied for table pg_constraint");
            }
            return noForeignKeys
                    ? List.of()
                    : List.of(new Harvest.ForeignKey("t", List.of("x"), "u", List.of("y")));
        }
    }

    /** A connection whose every statement executes to one empty result set, regardless of SQL text. */
    private static Connection alwaysEmptyConnection() throws SQLException {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(0);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(false);
        Statement stmt = mock(Statement.class);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);
        return conn;
    }

    @Test
    @DisplayName("row counts and NDV succeed, the FK piece fails independently: "
            + "fkHarvestSucceeded=false, FKs empty, other pieces intact")
    void fkPieceFailureDoesNotTakeDownTheOtherPieces() throws SQLException {
        FakeQueries queries = new FakeQueries();
        queries.failForeignKeys = true;

        Harvest harvest = harvester.harvest(alwaysEmptyConnection(), queries, "public");

        assertThat(harvest.fkHarvestSucceeded()).isFalse();
        assertThat(harvest.declaredForeignKeys()).isEmpty();
        assertThat(harvest.rowCountByTable()).containsEntry("t", 42L);
        assertThat(harvest.ndvByColumn()).containsEntry(new Harvest.ColKey("t", "c"), 7L);
    }

    @Test
    @DisplayName("when every piece succeeds, fkHarvestSucceeded is true and the declared FKs are carried through")
    void allPiecesSucceedingReportsFkHarvestSucceeded() throws SQLException {
        Harvest harvest = harvester.harvest(alwaysEmptyConnection(), new FakeQueries(), "public");

        assertThat(harvest.fkHarvestSucceeded()).isTrue();
        assertThat(harvest.declaredForeignKeys())
                .containsExactly(new Harvest.ForeignKey("t", List.of("x"), "u", List.of("y")));
    }

    @Test
    @DisplayName("a schema with genuinely zero FKs also reports fkHarvestSucceeded=true — empty is not failure")
    void genuinelyZeroForeignKeysStillSucceeds() throws SQLException {
        FakeQueries queries = new FakeQueries();
        queries.noForeignKeys = true;

        Harvest harvest = harvester.harvest(alwaysEmptyConnection(), queries, "public");

        assertThat(harvest.fkHarvestSucceeded()).isTrue();
        assertThat(harvest.declaredForeignKeys()).isEmpty();
    }
}

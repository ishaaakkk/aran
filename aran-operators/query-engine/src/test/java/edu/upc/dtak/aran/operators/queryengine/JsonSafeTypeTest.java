package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Type fidelity across the JDBC boundary.
 *
 * <p>Values used to be {@code toString()}'d on the way out of DuckDB, so an INTEGER column
 * reached the browser as {@code "42"} and the workbench sorted numeric columns
 * lexicographically. These tests pin which types survive as JSON natives and — just as
 * important — which are deliberately stringified because JSON would round them.
 */
class JsonSafeTypeTest {

    // ── Preserved ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("integers, booleans and strings keep their type")
    void primitivesArePreserved() {
        assertThat(DuckDbExecutor.jsonSafe(42)).isEqualTo(42).isInstanceOf(Integer.class);
        assertThat(DuckDbExecutor.jsonSafe(true)).isEqualTo(true).isInstanceOf(Boolean.class);
        assertThat(DuckDbExecutor.jsonSafe("txt")).isEqualTo("txt");
        assertThat(DuckDbExecutor.jsonSafe(1.5d)).isEqualTo(1.5d).isInstanceOf(Double.class);
    }

    @Test
    @DisplayName("null stays null rather than becoming the string \"null\"")
    void nullStaysNull() {
        assertThat(DuckDbExecutor.jsonSafe(null)).isNull();
    }

    // ── Deliberately stringified, because JSON would lose data ───────────────

    @Test
    @DisplayName("a BIGINT past JavaScript's safe-integer range becomes a string")
    void unsafeLongIsStringified() {
        // 2^53 - 1 is the largest integer a JS Number represents exactly. Below it, keep the
        // number; above, JSON.parse would silently round, so a string is the honest answer.
        assertThat(DuckDbExecutor.jsonSafe(9007199254740991L)).isEqualTo(9007199254740991L);
        assertThat(DuckDbExecutor.jsonSafe(9007199254740992L)).isEqualTo("9007199254740992");
        assertThat(DuckDbExecutor.jsonSafe(-9007199254740992L)).isEqualTo("-9007199254740992");
    }

    @Test
    @DisplayName("DECIMAL is stringified rather than silently rounded")
    void highPrecisionIsStringified() {
        assertThat(DuckDbExecutor.jsonSafe(new BigDecimal("1.50"))).isEqualTo("1.50");
    }

    @Test
    @DisplayName("a HUGEINT past the safe-integer range is stringified")
    void unsafeHugeIntIsStringified() {
        assertThat(DuckDbExecutor.jsonSafe(new BigInteger("170141183460469231731687303715884105727")))
                .isEqualTo("170141183460469231731687303715884105727");
        assertThat(DuckDbExecutor.jsonSafe(new BigInteger("9007199254740992"))).isEqualTo("9007199254740992");
    }

    @Test
    @DisplayName("Long.MIN_VALUE is stringified — abs() cannot detect it")
    void longMinValueIsStringified() {
        // Math.abs(Long.MIN_VALUE) is Long.MIN_VALUE, so an abs-based range check waves through
        // the one value furthest outside the range.
        assertThat(DuckDbExecutor.jsonSafe(Long.MIN_VALUE)).isEqualTo("-9223372036854775808");
    }

    // ── HUGEINT within range: numeric, because DuckDB 1.5 made it the common case ────

    @Test
    @DisplayName("a HUGEINT inside the safe-integer range stays a number")
    void safeHugeIntIsPreserved() {
        assertThat(DuckDbExecutor.jsonSafe(new BigInteger("-1"))).isEqualTo(-1L);
        assertThat(DuckDbExecutor.jsonSafe(new BigInteger("42"))).isEqualTo(42L);
        assertThat(DuckDbExecutor.jsonSafe(new BigInteger("9007199254740991")))
                .isEqualTo(9007199254740991L);
    }

    @Test
    @DisplayName("DuckDB 1.5 infers HUGEINT for small JSON integers — they must still be numbers")
    void jsonSourcedIntegersSurviveHugeIntInference() throws Exception {
        // DuckDB 1.3 typed this column BIGINT (Long); 1.5.5 types it HUGEINT (BigInteger). The
        // engine's inference is free to change — what must not change is that a column of small
        // integers leaves the API as JSON numbers. Reading through read_json_auto rather than
        // asserting on a literal is the point: a literal would not have caught the 1.3 → 1.5
        // change, because the shift is in what the reader infers, not in what the type does.
        Class.forName("org.duckdb.DuckDBDriver");
        java.nio.file.Path json = java.nio.file.Files.createTempFile("aran-jsonsafe", ".json");
        java.nio.file.Files.writeString(json, "[{\"h_index\":5},{\"h_index\":-1}]");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT h_index FROM read_json_auto('" + json.toAbsolutePath() + "') ORDER BY 1")) {
            rs.next();
            assertThat(DuckDbExecutor.jsonSafe(rs.getObject(1))).isEqualTo(-1L);
            rs.next();
            assertThat(DuckDbExecutor.jsonSafe(rs.getObject(1))).isEqualTo(5L);
        }
    }

    @Test
    @DisplayName("NaN and infinity are stringified — they are not legal JSON numbers")
    void nonFiniteDoublesAreStringified() {
        assertThat(DuckDbExecutor.jsonSafe(Double.NaN)).isEqualTo("NaN");
        assertThat(DuckDbExecutor.jsonSafe(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
    }

    @Test
    @DisplayName("temporal and composite values fall back to their readable toString")
    void complexTypesAreStringified() {
        assertThat(DuckDbExecutor.jsonSafe(java.time.LocalDate.of(2021, 3, 4))).isEqualTo("2021-03-04");
        assertThat(DuckDbExecutor.jsonSafe(java.time.LocalTime.of(11, 22, 33))).isEqualTo("11:22:33");
    }

    // ── Against a real DuckDB result set ─────────────────────────────────────

    @Test
    @DisplayName("a real DuckDB row keeps numeric columns numeric end to end")
    void realResultSetPreservesTypes() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT 9 AS n, 100 AS big, 'txt' AS t, true AS b, NULL::INTEGER AS nul")) {
            rs.next();
            assertThat(DuckDbExecutor.jsonSafe(rs.getObject(1))).isInstanceOf(Integer.class);
            assertThat(DuckDbExecutor.jsonSafe(rs.getObject(2))).isInstanceOf(Integer.class);
            assertThat(DuckDbExecutor.jsonSafe(rs.getObject(3))).isInstanceOf(String.class);
            assertThat(DuckDbExecutor.jsonSafe(rs.getObject(4))).isInstanceOf(Boolean.class);
            assertThat(DuckDbExecutor.jsonSafe(rs.getObject(5))).isNull();
        }
    }

    @Test
    @DisplayName("numeric ordering is now possible — the bug that motivated this")
    void numericColumnsCompareNumerically() {
        // As strings these sort "100" < "20" < "9", which is what the workbench did.
        Object nine = DuckDbExecutor.jsonSafe(9);
        Object twenty = DuckDbExecutor.jsonSafe(20);
        Object hundred = DuckDbExecutor.jsonSafe(100);
        assertThat(((Integer) nine)).isLessThan((Integer) twenty);
        assertThat(((Integer) twenty)).isLessThan((Integer) hundred);
    }
}

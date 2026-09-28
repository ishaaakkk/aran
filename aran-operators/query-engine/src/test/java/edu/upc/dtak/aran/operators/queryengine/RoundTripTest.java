package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoundTrip}'s comparator, exercised against a real in-process DuckDB
 * ({@link DuckDbTestSupport}) rather than mocked JDBC — the fingerprint SQL is DuckDB-specific
 * ({@code hash()} over a row alias), so the only honest test runs it for real.
 */
class RoundTripTest extends DuckDbTestSupport {

    /** {@link RoundTrip#fingerprintSql} is a pinned string, not a behaviour — DuckDB is not needed. */
    @Test
    @DisplayName("fingerprintSql is pinned to COUNT + COALESCE(SUM(hash(\"__aran_rt\")), 0) over"
            + " the inner SQL, aliased to the unshadowable \"__aran_rt\"")
    void fingerprintSqlIsPinned() {
        assertThat(RoundTrip.fingerprintSql("SELECT * FROM t"))
                .isEqualTo("SELECT COUNT(*) AS c, COALESCE(SUM(hash(\"__aran_rt\")), 0) AS h FROM "
                        + "(SELECT * FROM t) AS \"__aran_rt\"");
    }

    @Test
    @DisplayName("the same rows in a different order verify as EXPANDED — order-insensitive by design")
    void equalFixturesInDifferentOrderAreExpanded() throws Exception {
        registerJsonView("t1",
                "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},{\"id\":3,\"name\":\"c\"}]");

        RoundTrip.Verdict verdict = RoundTrip.verify(connection,
                "SELECT id, name FROM t1 ORDER BY id ASC",
                "SELECT id, name FROM t1 ORDER BY id DESC");

        assertThat(verdict).isEqualTo(new RoundTrip.Verdict("EXPANDED", null));
    }

    @Test
    @DisplayName("a differing row verifies as OPAQUE with a round-trip mismatch reason")
    void differingRowIsOpaqueWithMismatchReason() throws Exception {
        registerJsonView("leftSide", "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]");
        registerJsonView("rightSide", "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"different\"}]");

        RoundTrip.Verdict verdict = RoundTrip.verify(connection,
                "SELECT id, name FROM leftSide", "SELECT id, name FROM rightSide");

        assertThat(verdict.status()).isEqualTo("OPAQUE");
        assertThat(verdict.reason()).as("a mismatch must say why").contains("round-trip mismatch");
    }

    @Test
    @DisplayName("both sides empty verify as EXPANDED — the COALESCE guard, not a NULL-vs-NULL fluke")
    void bothSidesEmptyAreExpandedViaCoalesceGuard() throws Exception {
        registerJsonView("t2", "[{\"id\":1}]");

        RoundTrip.Verdict verdict = RoundTrip.verify(connection,
                "SELECT * FROM t2 WHERE id > 100",
                "SELECT * FROM t2 WHERE id > 200");

        assertThat(verdict).isEqualTo(new RoundTrip.Verdict("EXPANDED", null));
    }

    @Test
    @DisplayName("a wrapper column literally named 't' no longer shadows the row alias — two rows"
            + " that agree on 't' but differ on a second column fingerprint differently")
    void columnLiterallyNamedTNoLongerShadowsTheRowAlias() {
        // Before the fix, `FROM (...) AS t` + `hash(t)` let DuckDB resolve `hash(t)` to the
        // COLUMN "t" (both queries: 1) instead of the row alias "t" — collapsing the oracle to
        // COUNT + one column's hash and hiding the difference in "other" entirely. This is the
        // exact shape that used to collide: both sides count=1 and (pre-fix) hash(t)=hash(1).
        RoundTrip.Verdict verdict = RoundTrip.verify(connection,
                "SELECT 1 AS t, 'a' AS other",
                "SELECT 1 AS t, 'b' AS other");

        assertThat(verdict.status())
                .as("a column named 't' must not shadow the whole-row hash — the two rows"
                        + " genuinely differ in 'other' and the fingerprint must see it")
                .isEqualTo("OPAQUE");
    }
}

package edu.upc.dtak.aran.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The session pin: every DuckDB connection this platform opens runs in UTC.
 *
 * <p><b>What these assertions do and do not prove.</b> They prove the contract — a session from
 * {@link DuckDbSession#open()} reports {@code UTC} and renders zone-dependent expressions the UTC
 * way — and on a host whose zone is not UTC they fail without the pin (verified on macOS with the
 * system zone Europe/Madrid: an unpinned session reports {@code Europe/Madrid} and renders
 * {@code 2020-06-01 12:00:00+02}). On a host that is already UTC — most containers, and CI —
 * they pass whether or not anything pinned the session, which is exactly why
 * {@link DuckDbSessionArchitectureTest} exists: the structural guard, not this one, is what
 * catches a new connection site that forgot the pin.
 */
class DuckDbSessionTest {

    /** A cast whose result text depends entirely on the session's TimeZone setting. */
    private static final String ZONED_CAST =
            "SELECT CAST('2020-06-01 12:00:00+02:00' AS TIMESTAMPTZ)::VARCHAR,"
                    + " (TIMESTAMP '2020-06-01 12:00:00')::TIMESTAMPTZ::VARCHAR";

    private static String setting(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('TimeZone')")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("a session opened through the helper reports TimeZone=UTC")
    void opensInUtc() throws Exception {
        try (Connection conn = DuckDbSession.open()) {
            assertThat(setting(conn)).isEqualTo("UTC");
        }
    }

    @Test
    @DisplayName("zone-dependent casts render as UTC instants, not as the host's local time")
    void rendersUtcInstants() throws Exception {
        try (Connection conn = DuckDbSession.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(ZONED_CAST)) {
            rs.next();
            // Same instant, printed in the session's zone: 12:00+02 is 10:00 UTC.
            assertThat(rs.getString(1)).isEqualTo("2020-06-01 10:00:00+00");
            // A naive TIMESTAMP promoted to TIMESTAMPTZ is *interpreted* in the session's zone,
            // so this one moves the instant rather than only the rendering.
            assertThat(rs.getString(2)).isEqualTo("2020-06-01 12:00:00+00");
        }
    }

    @Test
    @DisplayName("a predicate over a TIMESTAMPTZ value does not change truth value with the host")
    void zoneDoesNotDecidePredicates() throws Exception {
        // The damaging case, and the reason this is not a display concern: comparing a stored
        // TIMESTAMPTZ (what postgres_scan hands back for `timestamptz`, and what a parquet
        // isAdjustedToUTC column reads as) against a naive TIMESTAMP literal promotes the literal
        // in the session's zone. The same WHERE clause is false in Europe/Madrid and true in UTC,
        // so an unpinned session changes which rows a query returns — not how they are printed.
        try (Connection conn = DuckDbSession.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TIMESTAMPTZ '2020-06-01 10:00:00+00' = TIMESTAMP '2020-06-01 10:00:00'")) {
            rs.next();
            assertThat(rs.getBoolean(1)).isTrue();
        }
    }

    @Test
    @DisplayName("the probe above is sensitive to the setting — it is not vacuous on a UTC host")
    void probeIsZoneSensitive() throws Exception {
        try (Connection conn = DuckDbSession.open();
             Statement st = conn.createStatement()) {
            // Deliberately unpin, on this connection only: if the renderings and the predicate
            // above were constants rather than answers about the session, this control would still
            // see UTC and the tests above would prove nothing on a UTC-zoned CI host.
            st.execute("SET TimeZone='Europe/Madrid'");
            try (ResultSet rs = st.executeQuery(ZONED_CAST)) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("2020-06-01 12:00:00+02");
                assertThat(rs.getString(2)).isEqualTo("2020-06-01 12:00:00+02");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT TIMESTAMPTZ '2020-06-01 10:00:00+00' = TIMESTAMP '2020-06-01 10:00:00'")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("the pin survives the lockdown sequence, which locks configuration afterwards")
    void survivesLockdown() throws Exception {
        // Ordering contract: DuckDbExecutor.lockDownFilesystem ends with lock_configuration=true,
        // after which no SET is accepted. The pin therefore has to happen at open, and this is the
        // test that fails if someone moves it later in a connection's life.
        try (Connection conn = DuckDbSession.open()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET enable_external_access=false");
                st.execute("SET lock_configuration=true");
            }
            assertThat(setting(conn)).isEqualTo("UTC");
        }
    }
}

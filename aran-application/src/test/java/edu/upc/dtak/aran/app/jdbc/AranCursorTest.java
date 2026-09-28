package edu.upc.dtak.aran.app.jdbc;

import org.apache.calcite.avatica.ColumnMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V46 regression guard. {@code JdbcRealClientIT} proves this end to end but needs Docker, so it
 * cannot run in {@code validate.sh --offline}; a revert of either fix there would go undetected
 * until someone happens to run the Docker gate on a non-UTC machine. This class is the offline-
 * runnable tripwire: plain JUnit, no Spring context, no Docker, package-private access to
 * {@link AranCursor}'s static methods (same package, deliberately not made public for this).
 */
class AranCursorTest {

    private TimeZone originalDefaultTimeZone;

    @BeforeEach
    void captureDefaultTimeZone() {
        originalDefaultTimeZone = TimeZone.getDefault();
    }

    /**
     * A test that force-sets the JVM default TimeZone must always restore it, or it leaks into
     * every other test in the same JVM/fork — order-dependent failures are exactly the kind of
     * bug this class exists to prevent, not introduce.
     */
    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalDefaultTimeZone);
    }

    @Test
    @DisplayName("DATE/TIME/TIMESTAMP advertise the numeric Reps, not JAVA_SQL_*")
    void dateTimeTimestampUseNumericReps() {
        assertThat(AranCursor.avaticaTypeFor(Types.DATE, "DATE").rep)
                .isEqualTo(ColumnMetaData.Rep.INTEGER);
        assertThat(AranCursor.avaticaTypeFor(Types.TIME, "TIME").rep)
                .isEqualTo(ColumnMetaData.Rep.INTEGER);
        assertThat(AranCursor.avaticaTypeFor(Types.TIMESTAMP, "TIMESTAMP").rep)
                .isEqualTo(ColumnMetaData.Rep.LONG);
    }

    @Test
    @DisplayName("the declared JDBC type is untouched by the Rep fix — only the wire Rep changes")
    void declaredTypeUnchangedForPlainTemporalTypes() {
        ColumnMetaData.AvaticaType date = AranCursor.avaticaTypeFor(Types.DATE, "DATE");
        assertThat(date.id).isEqualTo(Types.DATE);
        assertThat(date.name).isEqualTo("DATE");

        ColumnMetaData.AvaticaType timestamp = AranCursor.avaticaTypeFor(Types.TIMESTAMP, "TIMESTAMP");
        assertThat(timestamp.id).isEqualTo(Types.TIMESTAMP);
        assertThat(timestamp.name).isEqualTo("TIMESTAMP");
    }

    /**
     * TIME_WITH_TIMEZONE/TIMESTAMP_WITH_TIMEZONE are deliberately absent from the numeric-Rep
     * case (review finding I1): Avatica 1.27's {@code createAccessors} accepts only
     * {@code Rep.STRING} for those two type ids — anything else throws an {@code AssertionError}
     * that kills the whole statement, not just the column. They must fall through to the
     * {@code default -> Rep.STRING} branch, which also flips the declared type to VARCHAR —
     * matching what {@link AranCursor#avaticaValue} already sends for them (DuckDB hands back an
     * {@code OffsetTime}/{@code OffsetDateTime}, landing on the {@code default -> value.toString()}
     * case).
     */
    @Test
    @DisplayName("TIME_WITH_TIMEZONE/TIMESTAMP_WITH_TIMEZONE fall through to Rep.STRING, not a numeric Rep")
    void withTimezoneVariantsUseStringRep() {
        ColumnMetaData.AvaticaType time = AranCursor.avaticaTypeFor(
                Types.TIME_WITH_TIMEZONE, "TIME WITH TIME ZONE");
        assertThat(time.rep).isEqualTo(ColumnMetaData.Rep.STRING);
        assertThat(time.id).as("declared type flips to VARCHAR, pairing with avaticaValue's toString()")
                .isEqualTo(Types.VARCHAR);
        assertThat(time.name).isEqualTo("VARCHAR");

        ColumnMetaData.AvaticaType timestamp = AranCursor.avaticaTypeFor(
                Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE");
        assertThat(timestamp.rep).isEqualTo(ColumnMetaData.Rep.STRING);
        assertThat(timestamp.id).isEqualTo(Types.VARCHAR);
        assertThat(timestamp.name).isEqualTo("VARCHAR");
    }

    /**
     * The regression guard for the second V46 fix (§2 of the task report): before it,
     * {@code avaticaValue}'s {@code Timestamp} case read {@code ts.getTime()} directly, which is
     * correct only when the JVM's default TimeZone happens to be UTC — everywhere else the wire
     * value silently carried that zone's offset (DST included). Forcing a handful of very
     * different non-UTC default zones here is what makes this test fail on a reverted
     * {@code ts.getTime()} regardless of which machine or CI runner it runs on (unlike
     * {@code JdbcRealClientIT}, which only caught this by accident of running on a
     * non-UTC-default dev machine).
     */
    @Test
    @DisplayName("avaticaValue(Timestamp) is correct under a forced non-UTC default TimeZone")
    void timestampWireValueIsTimezoneIndependent() {
        LocalDateTime wallClock = LocalDateTime.of(2024, 3, 15, 10, 30, 0);
        long expectedWireMillis = wallClock.toInstant(ZoneOffset.UTC).toEpochMilli();

        for (String zoneId : new String[] {"UTC", "America/Los_Angeles", "Pacific/Kiritimati", "Europe/Madrid"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
            // Timestamp.valueOf(LocalDateTime) is the same default-TimeZone-keyed construction
            // DuckDB's own JDBC driver uses for a naive TIMESTAMP column (see AranCursor's
            // comment on this case) — building the fixture this way, not via `new
            // Timestamp(millis)`, is what actually exercises the bug ts.getTime() had.
            Timestamp ts = Timestamp.valueOf(wallClock);
            Object wireValue = AranCursor.avaticaValue(ts);
            assertThat(wireValue).as("wire value for zone " + zoneId).isEqualTo(expectedWireMillis);
        }
    }

    @Test
    @DisplayName("avaticaValue(Date)/avaticaValue(Time) stay correct under a forced non-UTC default TimeZone too")
    void dateAndTimeWireValuesAreTimezoneIndependent() {
        java.time.LocalDate localDate = java.time.LocalDate.of(2024, 3, 15);
        java.time.LocalTime localTime = java.time.LocalTime.of(23, 59, 1);
        int expectedDateWire = (int) localDate.toEpochDay();
        int expectedTimeWire = (int) (localTime.toNanoOfDay() / 1_000_000L);

        for (String zoneId : new String[] {"UTC", "America/Los_Angeles", "Pacific/Kiritimati"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
            java.sql.Date d = java.sql.Date.valueOf(localDate);
            java.sql.Time t = java.sql.Time.valueOf(localTime);
            assertThat(AranCursor.avaticaValue(d)).as("date wire value for zone " + zoneId)
                    .isEqualTo(expectedDateWire);
            assertThat(AranCursor.avaticaValue(t)).as("time wire value for zone " + zoneId)
                    .isEqualTo(expectedTimeWire);
        }
    }
}

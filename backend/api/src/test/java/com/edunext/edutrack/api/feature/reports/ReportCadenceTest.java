package com.edunext.edutrack.api.feature.reports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-065 · the two questions a cadence answers, tested apart.
 *
 * <p>"When does it fire" and "what does it cover" are different, and conflating
 * them is the bug this file exists to catch. A weekly report that fires on
 * Monday and covers <em>this</em> week covers one day, and it draws as a
 * catastrophic drop in activity that nobody can explain.
 */
class ReportCadenceTest {

    /** Asia/Kolkata, because a half-hour offset breaks anything that assumes whole hours. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int SEND_HOUR = 6;

    @Nested
    @DisplayName("what the report covers")
    class Period {

        @Test
        @DisplayName("daily covers yesterday, never today")
        void dailyCoversYesterday() {
            ReportCadence.Period period = ReportCadence.DAILY.periodEnding(LocalDate.of(2026, 8, 19));

            assertThat(period.from()).isEqualTo(LocalDate.of(2026, 8, 18));
            assertThat(period.to()).isEqualTo(LocalDate.of(2026, 8, 18));
        }

        /**
         * The failure this whole class is written against. A 06:00 Monday run
         * covering "this week" covers six hours of a Monday nobody has worked.
         */
        @Test
        @DisplayName("weekly covers the previous Monday to Sunday, not the current week")
        void weeklyCoversTheCompletedWeek() {
            // 19 Aug 2026 is a Wednesday.
            ReportCadence.Period period = ReportCadence.WEEKLY.periodEnding(LocalDate.of(2026, 8, 19));

            assertThat(period.from()).isEqualTo(LocalDate.of(2026, 8, 10));
            assertThat(period.to()).isEqualTo(LocalDate.of(2026, 8, 16));
            assertThat(period.from().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
            assertThat(period.to().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
        }

        /**
         * The boundary case: run on the Monday the schedule actually fires. The
         * week reported must be the one that just ended, not the one starting
         * that morning.
         */
        @Test
        @DisplayName("a Monday run reports the week that just ended")
        void weeklyOnAMondayLooksBack() {
            ReportCadence.Period period = ReportCadence.WEEKLY.periodEnding(LocalDate.of(2026, 8, 17));

            assertThat(period.from()).isEqualTo(LocalDate.of(2026, 8, 10));
            assertThat(period.to()).isEqualTo(LocalDate.of(2026, 8, 16));
        }

        @Test
        @DisplayName("monthly covers the whole previous calendar month")
        void monthlyCoversLastMonth() {
            ReportCadence.Period period = ReportCadence.MONTHLY.periodEnding(LocalDate.of(2026, 8, 1));

            assertThat(period.from()).isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(period.to()).isEqualTo(LocalDate.of(2026, 7, 31));
        }

        /**
         * A fixed 30 would drop a day of every long month and invent one every
         * February. February is where it shows first and worst.
         */
        @Test
        @DisplayName("monthly gets February right, and a leap February too")
        void monthlyRespectsMonthLength() {
            assertThat(ReportCadence.MONTHLY.periodEnding(LocalDate.of(2026, 3, 1)).to())
                    .as("2026 is not a leap year")
                    .isEqualTo(LocalDate.of(2026, 2, 28));
            assertThat(ReportCadence.MONTHLY.periodEnding(LocalDate.of(2028, 3, 1)).to())
                    .as("2028 is")
                    .isEqualTo(LocalDate.of(2028, 2, 29));
        }

        @Test
        @DisplayName("monthly on the 1st of January reports December of the year before")
        void monthlyCrossesTheYear() {
            ReportCadence.Period period = ReportCadence.MONTHLY.periodEnding(LocalDate.of(2027, 1, 1));

            assertThat(period.from()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(period.to()).isEqualTo(LocalDate.of(2026, 12, 31));
        }
    }

    @Nested
    @DisplayName("when it next fires")
    class NextRun {

        @Test
        @DisplayName("daily lands on the send hour, tomorrow, once today's has passed")
        void dailyAfterTheHour() {
            ZonedDateTime after = ZonedDateTime.of(2026, 8, 19, 7, 0, 0, 0, IST);

            ZonedDateTime next = ReportCadence.DAILY.nextRunAfter(after, IST, SEND_HOUR);

            assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 20));
            assertThat(next.getHour()).isEqualTo(SEND_HOUR);
        }

        @Test
        @DisplayName("daily lands today when the send hour is still ahead")
        void dailyBeforeTheHour() {
            ZonedDateTime after = ZonedDateTime.of(2026, 8, 19, 5, 0, 0, 0, IST);

            assertThat(ReportCadence.DAILY.nextRunAfter(after, IST, SEND_HOUR).toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 8, 19));
        }

        /**
         * 🔴 The property that keeps the sweep from looping.
         *
         * <p>The runner advances {@code next_run_at} after a run. If the next
         * time could equal the one just used, the schedule would be immediately
         * due again and would send the same report over and over until the hour
         * passed — a mail storm, on a feature whose entire visible output is
         * mail.
         */
        @Test
        @DisplayName("the next run is strictly after the instant asked about, at the exact send hour")
        void neverReturnsTheSameInstant() {
            ZonedDateTime exactlyOnTheHour = ZonedDateTime.of(2026, 8, 19, SEND_HOUR, 0, 0, 0, IST);

            for (ReportCadence cadence : ReportCadence.values()) {
                assertThat(cadence.nextRunAfter(exactlyOnTheHour, IST, SEND_HOUR))
                        .as(cadence.name())
                        .isAfter(exactlyOnTheHour);
            }
        }

        @Test
        @DisplayName("weekly always lands on a Monday")
        void weeklyLandsOnMonday() {
            // Started on a Wednesday: the next firing is the following Monday,
            // not seven days from now.
            ZonedDateTime after = ZonedDateTime.of(2026, 8, 19, 9, 0, 0, 0, IST);

            ZonedDateTime next = ReportCadence.WEEKLY.nextRunAfter(after, IST, SEND_HOUR);

            assertThat(next.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
            assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        }

        @Test
        @DisplayName("monthly always lands on the 1st")
        void monthlyLandsOnTheFirst() {
            ZonedDateTime after = ZonedDateTime.of(2026, 8, 19, 9, 0, 0, 0, IST);

            ZonedDateTime next = ReportCadence.MONTHLY.nextRunAfter(after, IST, SEND_HOUR);

            assertThat(next.getDayOfMonth()).isEqualTo(1);
            assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        }

        /**
         * The organisation's clock, not the server's. A schedule computed in
         * UTC for a team in Asia/Kolkata fires at 11:30 local, and its "Monday"
         * starts five and a half hours early — so the week it reports on is not
         * the week the team worked.
         */
        @Test
        @DisplayName("the send hour is local, so the stored instant carries the zone's offset")
        void theHourIsLocalNotUtc() {
            ZonedDateTime after = ZonedDateTime.of(2026, 8, 19, 3, 0, 0, 0, IST);

            ZonedDateTime next = ReportCadence.DAILY.nextRunAfter(after, IST, SEND_HOUR);

            assertThat(next.getHour()).isEqualTo(SEND_HOUR);
            // 06:00 IST is 00:30 UTC. Asserted as the instant, because that is
            // what reaches the DATETIME(6) column.
            assertThat(next.toInstant())
                    .isEqualTo(java.time.Instant.parse("2026-08-19T00:30:00Z"));
        }
    }

    @Test
    @DisplayName("an unknown cadence is empty rather than an exception")
    void unknownCadence() {
        assertThat(ReportCadence.of("FORTNIGHTLY")).isEmpty();
        assertThat(ReportCadence.of(null)).isEmpty();
        assertThat(ReportCadence.of("  ")).isEmpty();
        assertThat(ReportCadence.of("weekly")).contains(ReportCadence.WEEKLY);
    }
}

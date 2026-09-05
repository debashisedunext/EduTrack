package com.edunext.edutrack.worker.onboarding.digest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-114 · the working-day arithmetic the digest's threshold is measured in.
 *
 * <p>No database: a {@link ObDigestCalendar.Snapshot} is the calendar already
 * read, and the whole point of separating it is that the maths on top can be
 * checked against dates somebody can verify by looking at a calendar.
 *
 * <p>The dates below are real: <b>2026-09-05 is a Saturday</b>, 2026-09-07 a
 * Monday, and the week of 2026-09-07 runs Monday to Friday with nothing in it.
 * Every assertion is against a fixed date, never against "now", so none of this
 * changes meaning depending on the day the suite runs.
 */
class ObDigestCalendarSnapshotTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The usual: Saturday and Sunday off, no holidays. */
    private static ObDigestCalendar.Snapshot weekends() {
        return new ObDigestCalendar.Snapshot(
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of(), IST);
    }

    // ────────────────────────────────────────────────────── the working day

    @Test
    @DisplayName("weekly-off days and holidays are both non-working")
    void bothHalvesOfTheCalendarCount() {
        ObDigestCalendar.Snapshot calendar = new ObDigestCalendar.Snapshot(
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                Set.of(LocalDate.of(2026, 9, 9)), IST);

        assertThat(calendar.isWorkingDay(LocalDate.of(2026, 9, 7))).isTrue();    // Monday
        assertThat(calendar.isWorkingDay(LocalDate.of(2026, 9, 5))).isFalse();   // Saturday
        assertThat(calendar.isWorkingDay(LocalDate.of(2026, 9, 9))).isFalse();   // the holiday
    }

    // ───────────────────────────────────────────────────────── the threshold

    @Test
    @DisplayName("the cutoff walks back over a weekend, so a Friday deadline is not chased on Monday")
    void theWeekendDoesNotCountAgainstAnybody() {
        // Monday 2026-09-07 at 08:30 IST. Two working days back is Thursday the
        // 3rd — not Saturday the 5th. A step that missed a Friday deadline has
        // been late for one working day on Monday morning, not for three days,
        // and mailing its manager about it is the digest crying wolf.
        Instant monday = LocalDate.of(2026, 9, 7).atTime(8, 30).atZone(IST).toInstant();

        Instant cutoff = weekends().workingDaysBefore(monday, 2);

        assertThat(cutoff)
                .isEqualTo(LocalDate.of(2026, 9, 3).atTime(8, 30).atZone(IST).toInstant());
    }

    @Test
    @DisplayName("the cutoff keeps the time of day")
    void theHourIsPreserved() {
        // "Overdue by more than two working days" at half past eight means
        // "due before half past eight two working days ago". Flooring to
        // midnight would pull in everything that fell due that morning.
        Instant wednesday = LocalDate.of(2026, 9, 9).atTime(8, 30).atZone(IST).toInstant();

        assertThat(weekends().workingDaysBefore(wednesday, 1))
                .isEqualTo(LocalDate.of(2026, 9, 8).atTime(8, 30).atZone(IST).toInstant());
    }

    @Test
    @DisplayName("a holiday inside the window pushes the cutoff back a further day")
    void holidaysMoveTheCutoff() {
        ObDigestCalendar.Snapshot calendar = new ObDigestCalendar.Snapshot(
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                Set.of(LocalDate.of(2026, 9, 8)), IST);
        Instant thursday = LocalDate.of(2026, 9, 10).atTime(8, 30).atZone(IST).toInstant();

        // Wednesday the 9th and Monday the 7th — Tuesday the 8th is the holiday.
        assertThat(calendar.workingDaysBefore(thursday, 2))
                .isEqualTo(LocalDate.of(2026, 9, 7).atTime(8, 30).atZone(IST).toInstant());
    }

    @Test
    @DisplayName("a calendar with no working day at all terminates instead of hanging")
    void theWalkIsBounded() {
        // A misconfigured calendar must not be an infinite loop in a scheduled
        // method. Being wrong in the direction of "nothing matches" is safe;
        // never returning is not.
        ObDigestCalendar.Snapshot everyDayOff = new ObDigestCalendar.Snapshot(
                Set.of(DayOfWeek.values()), Set.of(), IST);
        Instant now = LocalDate.of(2026, 9, 7).atTime(8, 30).atZone(IST).toInstant();

        assertThat(everyDayOff.workingDaysBefore(now, 2)).isBefore(now);
    }

    @Test
    @DisplayName("a threshold of zero is the instant itself")
    void zeroWalksNowhere() {
        Instant now = LocalDate.of(2026, 9, 7).atTime(8, 30).atZone(IST).toInstant();
        assertThat(weekends().workingDaysBefore(now, 0)).isEqualTo(now);
    }

    // ───────────────────────────────────────────────────── "stuck for N days"

    @Test
    @DisplayName("how long a stall has lasted counts working days only")
    void aStallOverAWeekendIsNotThreeDays() {
        // Stalled on Friday the 4th, read on Tuesday the 8th: Friday and Monday.
        // Saying "4 working days" for a weekend is how a digest loses its
        // reader's trust in the numbers it prints.
        assertThat(weekends().workingDaysBetween(
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 8)))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a stall that started today is zero, not one")
    void todayIsZero() {
        // The scheduler renders 0 as "today" rather than "0 working days".
        assertThat(weekends().workingDaysBetween(
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7)))
                .isZero();
    }

    @Test
    @DisplayName("a date in the future counts as zero rather than going negative")
    void theCountNeverGoesBackwards() {
        assertThat(weekends().workingDaysBetween(
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 7)))
                .isZero();
    }

    @Test
    @DisplayName("a stall entirely inside a weekend is zero working days")
    void aWeekendAloneIsNoTime() {
        assertThat(weekends().workingDaysBetween(
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 7)))
                .isZero();
    }
}

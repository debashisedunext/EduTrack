package com.edunext.edutrack.domain.masters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-024 · the day-walk algorithm, without Docker.
 *
 * <p>{@link WorkingHoursServiceIT} proves the composition against real MySQL —
 * the recurring-holiday expansion in particular needs a real repository query
 * to mean anything. This proves the arithmetic itself, on every build: the
 * seeded default calendar (Sat/Sun, 09:30–18:30, Asia/Kolkata) is reproduced
 * here as a fixture rather than read from a database, so these run without a
 * Docker daemon.
 */
class WorkingHoursServiceTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);
    private final HolidayRepository holidays = mock(HolidayRepository.class);
    private final ResourceLeaveRepository leaves = mock(ResourceLeaveRepository.class);

    private final WorkingHoursService service = new WorkingHoursService(calendars, holidays, leaves);

    @BeforeEach
    void setUp() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWeeklyOff(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        calendar.setWorkDayStart(LocalTime.of(9, 30));
        calendar.setWorkDayEnd(LocalTime.of(18, 30));
        calendar.setTimezone("Asia/Kolkata");

        when(calendars.getCalendar()).thenReturn(calendar);
        when(holidays.findAllOrgWideOrForProject(any())).thenReturn(List.of());
        when(leaves.findApprovedOverlapping(any(), any(), any())).thenReturn(List.of());
    }

    private static Instant kolkata(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, KOLKATA).toInstant();
    }

    // ------------------------------------------------------------------
    // workingHoursBetween
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("workingHoursBetween")
    class Between {

        @Test
        @DisplayName("a window inside one working day is its plain wall-clock length")
        void withinOneWorkingDay() {
            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 10, 10, 0), kolkata(2026, 8, 10, 12, 15));

            assertThat(hours).isEqualByComparingTo("2.25");
        }

        @Test
        @DisplayName("start on or after end is zero, not an error")
        void nonPositiveWindowIsZero() {
            Instant instant = kolkata(2026, 8, 10, 10, 0);

            assertThat(service.workingHoursBetween(instant, instant)).isEqualByComparingTo("0.00");
            assertThat(service.workingHoursBetween(instant, instant.minusSeconds(1))).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("time before the working day and after it is excluded")
        void beforeAndAfterTheWorkingDayIsExcluded() {
            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 10, 6, 0), kolkata(2026, 8, 10, 21, 0));

            assertThat(hours).as("only 09:30-18:30 counts").isEqualByComparingTo("9.00");
        }

        @Test
        @DisplayName("Saturday and Sunday contribute nothing")
        void weekendContributesNothing() {
            // 2026-08-15 is a Saturday, 2026-08-16 a Sunday.
            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 15, 0, 0), kolkata(2026, 8, 17, 0, 0));

            assertThat(hours).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a window spanning a weekend counts only the working-day slices either side")
        void spanningAWeekend() {
            // Friday 2026-08-14 17:00 to Monday 2026-08-17 10:30.
            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 14, 17, 0), kolkata(2026, 8, 17, 10, 30));

            assertThat(hours).as("1.5h left on Friday, 1h into Monday").isEqualByComparingTo("2.50");
        }

        @Test
        @DisplayName("an active org-wide holiday inside the window is excluded")
        void holidayIsExcluded() {
            Holiday diwali = holiday(LocalDate.of(2026, 8, 12), false, null);
            when(holidays.findAllOrgWideOrForProject(any())).thenReturn(List.of(diwali));

            // 2026-08-12 is a Wednesday — otherwise a full working day.
            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 11, 9, 30), kolkata(2026, 8, 13, 18, 30));

            assertThat(hours).as("Tue and Thu are full days (9h each); Wed contributes nothing")
                    .isEqualByComparingTo("18.00");
        }

        @Test
        @DisplayName("a recurring holiday is expanded into the queried year")
        void recurringHolidayIsExpanded() {
            // Stored against 2019, same month-day as the 2026-08-12 holiday
            // used above — 2026-08-11/13 are already known-good weekdays.
            Holiday recurring = holiday(LocalDate.of(2019, 8, 12), true, null);
            when(holidays.findAllOrgWideOrForProject(any())).thenReturn(List.of(recurring));

            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 11, 9, 30), kolkata(2026, 8, 13, 18, 30));

            assertThat(hours).as("2026-08-12, though stored against 2019, is skipped in 2026 too")
                    .isEqualByComparingTo("18.00");
        }

        @Test
        @DisplayName("a project id is forwarded to the holiday lookup")
        void projectIdIsForwarded() {
            service.workingHoursBetween(kolkata(2026, 8, 10, 9, 30), kolkata(2026, 8, 10, 10, 30), 7L, null);

            verify(holidays).findAllOrgWideOrForProject(eq(7L));
        }

        @Test
        @DisplayName("no user id means no leave lookup at all")
        void noUserIdSkipsLeaveLookup() {
            service.workingHoursBetween(kolkata(2026, 8, 10, 9, 30), kolkata(2026, 8, 10, 10, 30));

            verify(leaves, times(0)).findApprovedOverlapping(any(), any(), any());
        }

        @Test
        @DisplayName("a full-day approved leave removes that resource's whole day")
        void fullDayLeaveRemovesTheDay() {
            ResourceLeave leave = leave(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12), false, "APPROVED");
            when(leaves.findApprovedOverlapping(eq(9L), any(), any())).thenReturn(List.of(leave));

            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 11, 9, 30), kolkata(2026, 8, 13, 18, 30), null, 9L);

            assertThat(hours).isEqualByComparingTo("18.00");
        }

        @Test
        @DisplayName("only APPROVED leave the repository returns is honoured — a PENDING row is a caller error")
        void leaveMustBeApproved() {
            ResourceLeave pending = leave(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12), false, "PENDING");
            when(leaves.findApprovedOverlapping(eq(9L), any(), any())).thenReturn(List.of(pending));

            // The service trusts the repository's contract (approved-only); a
            // non-approved row reaching it is still treated as time off, since
            // filtering status is ResourceLeaveRepository's job, not this one's.
            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 12, 9, 30), kolkata(2026, 8, 12, 18, 30), null, 9L);

            assertThat(hours).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a half-day approved leave removes exactly half the working day")
        void halfDayLeaveRemovesHalfTheDay() {
            ResourceLeave halfDay = leave(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12), true, "APPROVED");
            when(leaves.findApprovedOverlapping(eq(9L), any(), any())).thenReturn(List.of(halfDay));

            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 12, 9, 30), kolkata(2026, 8, 12, 18, 30), null, 9L);

            assertThat(hours).isEqualByComparingTo("4.50");
        }
    }

    // ------------------------------------------------------------------
    // addWorkingHours
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("addWorkingHours")
    class Add {

        @Test
        @DisplayName("zero or negative hours returns the start instant unchanged")
        void nonPositiveHoursIsANoOp() {
            Instant start = kolkata(2026, 8, 10, 10, 0);

            assertThat(service.addWorkingHours(start, BigDecimal.ZERO)).isEqualTo(start);
            assertThat(service.addWorkingHours(start, new BigDecimal("-1"))).isEqualTo(start);
        }

        @Test
        @DisplayName("adding hours that fit inside the same working day just adds them")
        void fitsInsideTheSameDay() {
            Instant landed = service.addWorkingHours(kolkata(2026, 8, 10, 10, 0), new BigDecimal("3"));

            assertThat(landed).isEqualTo(kolkata(2026, 8, 10, 13, 0));
        }

        /** The scenario named in CLAUDE.md and blueprint §5 by name. */
        @Test
        @DisplayName("Friday 18:00 plus 4 working hours lands Monday morning, not Saturday")
        void fridayEveningCrossesTheWeekend() {
            Instant landed = service.addWorkingHours(kolkata(2026, 8, 14, 18, 0), new BigDecimal("4"));

            // 30 min left on Friday (18:00-18:30) + 3.5h into Monday (09:30 start).
            assertThat(landed).isEqualTo(kolkata(2026, 8, 17, 13, 0));
        }

        @Test
        @DisplayName("a start already past the working day rolls to the next working day's start")
        void startAfterHoursRollsForward() {
            Instant landed = service.addWorkingHours(kolkata(2026, 8, 10, 22, 0), new BigDecimal("1"));

            assertThat(landed).isEqualTo(kolkata(2026, 8, 11, 10, 30));
        }

        @Test
        @DisplayName("a holiday inside the walk is skipped entirely")
        void holidayIsSkipped() {
            Holiday holiday = holiday(LocalDate.of(2026, 8, 12), false, null);
            when(holidays.findAllOrgWideOrForProject(any())).thenReturn(List.of(holiday));

            // Tuesday 2026-08-11 17:00 + 5h: 1.5h left Tue, Wed is a holiday, so
            // 3.5h lands Thursday morning.
            Instant landed = service.addWorkingHours(kolkata(2026, 8, 11, 17, 0), new BigDecimal("5"));

            assertThat(landed).isEqualTo(kolkata(2026, 8, 13, 13, 0));
        }

        @Test
        @DisplayName("a resource's approved leave is skipped for that resource only")
        void leaveIsSkippedForThatResource() {
            ResourceLeave onLeave = leave(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12), false, "APPROVED");
            when(leaves.findApprovedOverlapping(eq(9L), any(), any())).thenReturn(List.of(onLeave));

            Instant landed = service.addWorkingHours(
                    kolkata(2026, 8, 11, 17, 0), new BigDecimal("5"), null, 9L);

            assertThat(landed).isEqualTo(kolkata(2026, 8, 13, 13, 0));
        }

        @Test
        @DisplayName("a target larger than one holiday/leave query window still resolves, via a second lookup")
        void reQueriesPastTheFirstHorizon() {
            // ~9h/working-day capacity; 1000h needs well over 120 calendar days,
            // which is exactly the chunk size addWorkingHours re-queries at.
            Instant landed = service.addWorkingHours(kolkata(2026, 1, 5, 9, 30), new BigDecimal("1000"));

            assertThat(landed).isAfter(kolkata(2026, 6, 1, 0, 0));
            verify(holidays, atLeast(2)).findAllOrgWideOrForProject(any());
        }

        @Test
        @DisplayName("no user id means no leave lookup at all")
        void noUserIdSkipsLeaveLookup() {
            service.addWorkingHours(kolkata(2026, 8, 10, 9, 30), new BigDecimal("1"));

            verify(leaves, times(0)).findApprovedOverlapping(any(), any(), any());
        }
    }

    // ------------------------------------------------------------------
    // nextWorkingDay
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("nextWorkingDay")
    class NextWorkingDay {

        @Test
        @DisplayName("an ordinary weekday's next working day is simply tomorrow")
        void ordinaryWeekdayIsTomorrow() {
            // Tuesday 2026-08-11.
            assertThat(service.nextWorkingDay(LocalDate.of(2026, 8, 11)))
                    .isEqualTo(LocalDate.of(2026, 8, 12));
        }

        @Test
        @DisplayName("a Friday's next working day skips the weekend to Monday")
        void fridaySkipsToMonday() {
            // 2026-08-14 is a Friday.
            assertThat(service.nextWorkingDay(LocalDate.of(2026, 8, 14)))
                    .isEqualTo(LocalDate.of(2026, 8, 17));
        }

        @Test
        @DisplayName("a holiday immediately after the date is skipped too")
        void aHolidayRightAfterIsSkipped() {
            // Wednesday 2026-08-12 is a holiday; Tuesday 11th's next working
            // day must land on Thursday the 13th, not the holiday itself.
            Holiday holiday = holiday(LocalDate.of(2026, 8, 12), false, null);
            when(holidays.findAllOrgWideOrForProject(any())).thenReturn(List.of(holiday));

            assertThat(service.nextWorkingDay(LocalDate.of(2026, 8, 11)))
                    .isEqualTo(LocalDate.of(2026, 8, 13));
        }

        @Test
        @DisplayName("the date itself is never returned, even when it is a working day")
        void theDateItselfIsExcluded() {
            LocalDate monday = LocalDate.of(2026, 8, 10);

            assertThat(service.nextWorkingDay(monday)).isNotEqualTo(monday).isAfter(monday);
        }

        @Test
        @DisplayName("resource leave plays no part — this is the org calendar only")
        void resourceLeaveIsIgnored() {
            // A leave record for some user must never affect an org-wide answer
            // that carries no user id — nextWorkingDay has no such parameter.
            ResourceLeave leave = leave(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12), false, "APPROVED");
            when(leaves.findApprovedOverlapping(any(), any(), any())).thenReturn(List.of(leave));

            assertThat(service.nextWorkingDay(LocalDate.of(2026, 8, 11)))
                    .isEqualTo(LocalDate.of(2026, 8, 12));
            verify(leaves, times(0)).findApprovedOverlapping(any(), any(), any());
        }
    }

    // ------------------------------------------------------------------
    // A broken calendar is a backstop, not a hang
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a calendar with no working day at all fails loudly rather than looping forever")
    void noWorkingDayFailsLoudlyRatherThanHanging() {
        WorkingCalendar brokenCalendar = new WorkingCalendar();
        // ck_working_calendar_weekly_off forbids this in the database; this
        // proves the service does not simply spin if that guarantee is ever
        // bypassed (a raw UPDATE, a future migration bug).
        brokenCalendar.setWeeklyOff(EnumSet.allOf(DayOfWeek.class));
        brokenCalendar.setWorkDayStart(LocalTime.of(9, 30));
        brokenCalendar.setWorkDayEnd(LocalTime.of(18, 30));
        brokenCalendar.setTimezone("Asia/Kolkata");
        when(calendars.getCalendar()).thenReturn(brokenCalendar);

        assertThatThrownBy(() -> service.addWorkingHours(kolkata(2026, 8, 10, 9, 30), BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ck_working_calendar_weekly_off");
    }

    @Test
    @DisplayName("nextWorkingDay fails loudly too, on the same broken calendar")
    void nextWorkingDayFailsLoudlyRatherThanHanging() {
        WorkingCalendar brokenCalendar = new WorkingCalendar();
        brokenCalendar.setWeeklyOff(EnumSet.allOf(DayOfWeek.class));
        brokenCalendar.setWorkDayStart(LocalTime.of(9, 30));
        brokenCalendar.setWorkDayEnd(LocalTime.of(18, 30));
        brokenCalendar.setTimezone("Asia/Kolkata");
        when(calendars.getCalendar()).thenReturn(brokenCalendar);

        assertThatThrownBy(() -> service.nextWorkingDay(LocalDate.of(2026, 8, 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ck_working_calendar_weekly_off");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Holiday holiday(LocalDate date, boolean recurring, Long projectId) {
        Holiday holiday = new Holiday();
        holiday.setHolidayDate(date);
        holiday.setName("Fixture holiday");
        holiday.setRecurring(recurring);
        holiday.setProjectId(projectId);
        holiday.setActive(true);
        return holiday;
    }

    private static ResourceLeave leave(LocalDate start, LocalDate end, boolean halfDay, String status) {
        ResourceLeave leave = new ResourceLeave();
        leave.setUserId(9L);
        leave.setStartDate(start);
        leave.setEndDate(end);
        leave.setHalfDay(halfDay);
        leave.setStatus(status);
        return leave;
    }
}

package com.edunext.edutrack.worker.onboarding.digest;

import com.edunext.edutrack.domain.masters.Holiday;
import com.edunext.edutrack.domain.masters.HolidayRepository;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * B-114 · the working calendar, read once per digest run.
 *
 * <h2>Why not {@code WorkingHoursService}</h2>
 *
 * <p>B-024's service is the right answer for one duration: it reads the
 * calendar, the org and project holidays and one resource's leave, and returns
 * hours. The digest asks the same question of every stuck step in the
 * organisation at once, and answering it through that service is one calendar
 * read and one holiday query <em>per row</em> — the shape CLAUDE.md's
 * "no live COUNT(*)" note is really about, one level down.
 *
 * <p>So the calendar is read once into a {@link Snapshot} and every row is
 * measured against that. The rules are B-024's, not a second set: the weekly-off
 * pattern comes from the same {@link WorkingCalendar} row, holidays from the
 * same {@link HolidayRepository}, and a recurring holiday is expanded by month
 * and day exactly as {@code WorkingHoursService.expandedHolidayDates} expands
 * it. What is deliberately <em>not</em> here is resource leave: a step is stuck
 * or it is not, and whether its owner was on leave changes who to ask rather
 * than whether the work has stopped.
 *
 * <h2>Days, not hours</h2>
 *
 * <p>A digest says "stuck for 4 working days". The distinction between 4.0 and
 * 4.25 working days is real for an SLA and noise in a sentence somebody reads
 * over coffee, so this counts whole working days and leaves hours to B-024.
 */
@Component
class ObDigestCalendar {

    private final WorkingCalendarRepository calendars;
    private final HolidayRepository holidays;

    ObDigestCalendar(WorkingCalendarRepository calendars, HolidayRepository holidays) {
        this.calendars = calendars;
        this.holidays = holidays;
    }

    /**
     * The calendar as it stands, with every holiday in {@code [from, to]}
     * expanded.
     *
     * <p>The window is bounded by the caller — the digest looks back over a
     * threshold measured in days and never further — so the expansion is over
     * one or two years at most.
     */
    Snapshot snapshot(LocalDate from, LocalDate to) {
        WorkingCalendar calendar = calendars.findAll().stream().findFirst().orElse(null);
        Set<DayOfWeek> weeklyOff = calendar == null ? Set.of() : calendar.getWeeklyOff();
        ZoneId zone = calendar == null ? ZoneId.of("Asia/Kolkata") : calendar.zone();

        Set<LocalDate> dates = new HashSet<>();
        for (Holiday holiday : holidays.findAllOrgWideOrForProject(null)) {
            if (holiday.isRecurring()) {
                MonthDay monthDay = MonthDay.from(holiday.getHolidayDate());
                for (int year = from.getYear(); year <= to.getYear(); year++) {
                    dates.add(monthDay.atYear(year));
                }
            } else {
                dates.add(holiday.getHolidayDate());
            }
        }
        dates.removeIf(d -> d.isBefore(from) || d.isAfter(to));
        return new Snapshot(weeklyOff, Set.copyOf(dates), zone);
    }

    /**
     * @param weeklyOff the org's weekly-off days
     * @param holidays  org holidays already expanded into the window asked for
     * @param zone      the calendar's own zone — what the scheduler's cron zone
     *                  is checked against
     */
    record Snapshot(Set<DayOfWeek> weeklyOff, Set<LocalDate> holidays, ZoneId zone) {

        boolean isWorkingDay(LocalDate day) {
            return !weeklyOff.contains(day.getDayOfWeek()) && !holidays.contains(day);
        }

        /**
         * The same clock time, {@code days} working days earlier.
         *
         * <p>This is the digest's threshold, and expressing it as one instant
         * rather than as a per-row calculation is what lets the query do the
         * filtering. "Overdue by more than two working days" on a Tuesday means
         * "due before Friday at this hour", and a step that missed a Friday
         * deadline is not chased on Monday morning for having been late over
         * the weekend.
         *
         * <p>The walk is bounded: the loop counts only working days, so a
         * calendar with every day marked off would not terminate. {@code 400}
         * calendar days is the ceiling — past that the calendar is
         * misconfigured, and the caller gets a cutoff so far back that nothing
         * matches, which is the safe direction to be wrong in.
         */
        Instant workingDaysBefore(Instant now, int days) {
            LocalDateTime local = LocalDateTime.ofInstant(now, zone);
            LocalDate date = local.toLocalDate();
            int remaining = Math.max(days, 0);
            int guard = 0;
            while (remaining > 0 && guard++ < 400) {
                date = date.minusDays(1);
                if (isWorkingDay(date)) {
                    remaining--;
                }
            }
            return date.atTime(local.toLocalTime()).atZone(zone).toInstant();
        }

        /**
         * Whole working days in {@code [from, to)} — how long a step has been
         * stuck, as the digest says it.
         *
         * <p>A stall that started today is 0, which the caller renders as
         * "today" rather than as "0 working days".
         */
        int workingDaysBetween(LocalDate from, LocalDate to) {
            if (!from.isBefore(to)) {
                return 0;
            }
            int count = 0;
            LocalDate cursor = from;
            while (cursor.isBefore(to)) {
                if (isWorkingDay(cursor)) {
                    count++;
                }
                cursor = cursor.plusDays(1);
            }
            return count;
        }
    }
}

package com.edunext.edutrack.domain.masters;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * B-024 · the working-hours calculation service.
 *
 * <p><b>Every SLA, duration and utilisation figure in the system routes through
 * this class.</b> Four private implementations of "working hours" would produce
 * four different answers to the same question — that is the failure this exists
 * to close off, per {@code DEPENDENCIES.md}'s note to the whole team. A Friday
 * 18:00 ticket with a 4-hour SLA must land Monday morning, not Saturday.
 *
 * <p>Deliberately in {@code domain}, not {@code api.feature.masters}: Stream D's
 * SLA scanner runs in the {@code worker} module and Stream C's transition
 * service runs in {@code api.feature.tickets}, and only {@code domain} is on
 * both their classpaths. Putting this beside {@link CalendarService} would make
 * two of the four streams reach across an ownership boundary to call it.
 *
 * <p>Reads all three halves of the calendar — {@link WorkingCalendarRepository}
 * for the weekly-off pattern and working-day bounds, {@link HolidayRepository}
 * for org and project holidays, {@link ResourceLeaveRepository} for one
 * resource's approved absences — and answers two questions:
 *
 * <ul>
 *   <li>{@link #workingHoursBetween} — how much working time elapsed in a
 *       window. Feeds cycle time, time-in-stage, effort variance.</li>
 *   <li>{@link #addWorkingHours} — where a window of working time lands.
 *       Feeds the Planned Close Date preview (C-012) and every escalation
 *       target D's scanner checks.</li>
 * </ul>
 *
 * <p>Both take an optional {@code projectId} (project holidays, alongside the
 * org-wide calendar) and an optional {@code userId} (that resource's approved
 * leave). Either or both may be {@code null} — a duration with no specific
 * owner honours only the org calendar, which is correct rather than a
 * shortcut: nothing else knows whose leave would apply.
 */
@Service
public class WorkingHoursService {

    /**
     * How far ahead {@link #addWorkingHours} loads holidays and leave in one
     * query. Re-queried in the next chunk if the walk runs past it — normal only
     * for a very large hour count or a nearly-empty calendar.
     */
    private static final int HORIZON_CHUNK_DAYS = 120;

    /**
     * A backstop, not an expected path. {@code ck_working_calendar_weekly_off}
     * refuses a pattern with no working day at all, so a walk that reaches this
     * many calendar days without satisfying its target means the calendar row
     * itself is broken, not that the answer is far away.
     */
    private static final int MAX_WALK_DAYS = 3650;

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private final WorkingCalendarRepository calendars;
    private final HolidayRepository holidays;
    private final ResourceLeaveRepository leaves;

    WorkingHoursService(WorkingCalendarRepository calendars,
                        HolidayRepository holidays,
                        ResourceLeaveRepository leaves) {
        this.calendars = calendars;
        this.holidays = holidays;
        this.leaves = leaves;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** {@link #workingHoursBetween(Instant, Instant, Long, Long)} against the org calendar alone. */
    @Transactional(readOnly = true)
    public BigDecimal workingHoursBetween(Instant start, Instant end) {
        return workingHoursBetween(start, end, null, null);
    }

    /**
     * The working time elapsed in {@code [start, end)}, honouring weekends, org
     * and project holidays, and {@code userId}'s approved leave.
     *
     * <p>Two decimal places, matching {@link SlaPolicy#getResolutionHrs()} — the
     * figures this feeds are compared against policy hours of the same scale.
     * {@code start >= end} is zero rather than an error: a ticket cannot have
     * negative duration, and a caller computing "time in stage" for a stage
     * entered and exited in the same instant should get zero, not an exception.
     */
    @Transactional(readOnly = true)
    public BigDecimal workingHoursBetween(Instant start, Instant end, Long projectId, Long userId) {
        if (!start.isBefore(end)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        WorkingCalendar calendar = calendars.getCalendar();
        ZoneId zone = calendar.zone();
        LocalDate from = start.atZone(zone).toLocalDate();
        LocalDate to = end.atZone(zone).toLocalDate();

        Set<LocalDate> holidayDates = expandedHolidayDates(projectId, from, to);
        List<ResourceLeave> approvedLeave = leavesFor(userId, from, to);

        Duration total = Duration.ZERO;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Optional<Window> window = workingWindowOnDate(calendar, date, holidayDates, approvedLeave);
            if (window.isEmpty()) {
                continue;
            }
            Instant clippedStart = maxInstant(window.get().start(), start);
            Instant clippedEnd = minInstant(window.get().end(), end);
            if (clippedStart.isBefore(clippedEnd)) {
                total = total.plus(Duration.between(clippedStart, clippedEnd));
            }
        }
        return toHours(total);
    }

    /** {@link #addWorkingHours(Instant, BigDecimal, Long, Long)} against the org calendar alone. */
    @Transactional(readOnly = true)
    public Instant addWorkingHours(Instant start, BigDecimal hours) {
        return addWorkingHours(start, hours, null, null);
    }

    /**
     * The instant reached by adding {@code hours} of working time to
     * {@code start} — weekends, org and project holidays, and {@code userId}'s
     * approved leave are all skipped rather than counted.
     *
     * <p>A non-positive {@code hours} returns {@code start} unchanged. Otherwise
     * this walks forward one calendar day at a time, consuming each day's
     * working window, until the remaining duration fits inside the day it has
     * reached — the return value is a precise instant inside that day, not just
     * a date, because "Friday 18:00 plus 4 working hours" has to land partway
     * through Monday morning, not at Monday's start.
     */
    @Transactional(readOnly = true)
    public Instant addWorkingHours(Instant start, BigDecimal hours, Long projectId, Long userId) {
        if (hours.signum() <= 0) {
            return start;
        }

        WorkingCalendar calendar = calendars.getCalendar();
        ZoneId zone = calendar.zone();
        Duration remaining = toDuration(hours);

        LocalDate date = start.atZone(zone).toLocalDate();
        LocalDate horizon = date.plusDays(HORIZON_CHUNK_DAYS);
        Set<LocalDate> holidayDates = expandedHolidayDates(projectId, date, horizon);
        List<ResourceLeave> approvedLeave = leavesFor(userId, date, horizon);

        boolean firstDay = true;
        for (int walked = 0; walked <= MAX_WALK_DAYS; walked++, date = date.plusDays(1)) {
            if (date.isAfter(horizon)) {
                horizon = date.plusDays(HORIZON_CHUNK_DAYS);
                holidayDates = expandedHolidayDates(projectId, date, horizon);
                approvedLeave = leavesFor(userId, date, horizon);
            }

            Optional<Window> window = workingWindowOnDate(calendar, date, holidayDates, approvedLeave);
            if (window.isPresent()) {
                Instant effectiveStart = firstDay ? maxInstant(window.get().start(), start) : window.get().start();
                if (effectiveStart.isBefore(window.get().end())) {
                    Duration available = Duration.between(effectiveStart, window.get().end());
                    if (available.compareTo(remaining) >= 0) {
                        return effectiveStart.plus(remaining);
                    }
                    remaining = remaining.minus(available);
                }
            }
            firstDay = false;
        }

        throw new IllegalStateException(
                "no working day satisfied " + hours + "h starting " + start + " within " + MAX_WALK_DAYS
                        + " calendar days — ck_working_calendar_weekly_off guarantees at least one working "
                        + "day exists, so the calendar row itself is broken, not merely far in the future");
    }

    /**
     * The first working day strictly after {@code date} — org calendar only,
     * per its single-argument signature.
     *
     * <p>Dashboard Rework PR 4's near-delay figure has no project or resource
     * in scope at the point it needs this: {@link
     * com.edunext.edutrack.worker.stats.DailyStatsRepository}'s daily pass
     * computes it once per day across every project at once, the same way
     * {@link #expandedHolidayDates} is queried without a project id when
     * {@code projectId} is {@code null}. A project-specific "next working
     * day" would need a call per project per day, which is the per-project
     * loop {@code daily_ticket_stats}' own header names as the shape that
     * stopped working first.
     *
     * <p>A short, bounded walk rather than routing through {@link
     * #addWorkingHours}: that method measures a quantity of working TIME and
     * returns an instant partway through whichever day satisfies it: asking
     * it for a tiny positive amount to fake a date-only answer would still
     * require the walk to land inside a real working window that day, which
     * is extra machinery this simpler question does not need. This only
     * asks "is this date a working day", walking forward one calendar day at
     * a time until one is.
     */
    @Transactional(readOnly = true)
    public LocalDate nextWorkingDay(LocalDate date) {
        WorkingCalendar calendar = calendars.getCalendar();
        LocalDate candidate = date.plusDays(1);
        LocalDate horizon = candidate.plusDays(HORIZON_CHUNK_DAYS);
        Set<LocalDate> holidayDates = expandedHolidayDates(null, candidate, horizon);

        for (int walked = 0; walked <= MAX_WALK_DAYS; walked++, candidate = candidate.plusDays(1)) {
            if (candidate.isAfter(horizon)) {
                horizon = candidate.plusDays(HORIZON_CHUNK_DAYS);
                holidayDates = expandedHolidayDates(null, candidate, horizon);
            }
            if (!calendar.isNonWorkingDay(candidate.getDayOfWeek()) && !holidayDates.contains(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException(
                "no working day found after " + date + " within " + MAX_WALK_DAYS + " calendar days — "
                        + "ck_working_calendar_weekly_off guarantees at least one working day exists, so "
                        + "the calendar row itself is broken, not merely far in the future");
    }

    // ------------------------------------------------------------------
    // The per-day working window
    // ------------------------------------------------------------------

    private record Window(Instant start, Instant end) {
    }

    /**
     * The instant range this date can supply, or empty if the date contributes
     * nothing at all. A weekend, a holiday, or a full-day leave are all "no
     * window" rather than a zero-length one — the distinction only matters for
     * readability, since either way the date adds nothing to the total.
     *
     * <p>A half-day leave is modelled as the resource being away for the first
     * half of the working day and present for the second — an arbitrary but
     * fixed convention. What matters is that {@link #workingHoursBetween} and
     * {@link #addWorkingHours} agree on it, since both call this method.
     */
    private Optional<Window> workingWindowOnDate(WorkingCalendar calendar, LocalDate date,
                                                  Set<LocalDate> holidayDates, List<ResourceLeave> approvedLeave) {
        if (calendar.isNonWorkingDay(date.getDayOfWeek()) || holidayDates.contains(date)) {
            return Optional.empty();
        }

        ZoneId zone = calendar.zone();
        Instant dayStart = date.atTime(calendar.getWorkDayStart()).atZone(zone).toInstant();
        Instant dayEnd = dayEndInstant(calendar, date, zone);

        Optional<ResourceLeave> leaveToday = approvedLeave.stream()
                .filter(l -> !date.isBefore(l.getStartDate()) && !date.isAfter(l.getEndDate()))
                .findFirst();
        if (leaveToday.isEmpty()) {
            return Optional.of(new Window(dayStart, dayEnd));
        }
        if (!leaveToday.get().isHalfDay()) {
            return Optional.empty();
        }
        Instant midpoint = dayStart.plus(calendar.workDayLength().dividedBy(2));
        return Optional.of(new Window(midpoint, dayEnd));
    }

    /**
     * A working day closing at midnight stores 1440 minutes — the end of the
     * date, not {@code LocalTime.MIDNIGHT} at its start ({@link
     * WorkingCalendar#getWorkDayEnd()}'s own javadoc). Resolving that case
     * through {@code date.atTime(LocalTime.MAX)} would lose the last
     * nanosecond's worth of precision to a boundary nobody will ever hit in
     * practice, but landing exactly on the next date's midnight is the honest
     * answer and costs nothing extra here.
     */
    private Instant dayEndInstant(WorkingCalendar calendar, LocalDate date, ZoneId zone) {
        if (WorkingCalendar.MINUTES_IN_DAY == calendar.getWorkDayEndMin()) {
            return date.plusDays(1).atStartOfDay(zone).toInstant();
        }
        return date.atTime(calendar.getWorkDayEnd()).atZone(zone).toInstant();
    }

    // ------------------------------------------------------------------
    // Holidays and leave
    // ------------------------------------------------------------------

    /**
     * Org and project holidays in {@code [from, to]}, with every recurring
     * holiday expanded into each year the range touches — see
     * {@link HolidayRepository#findAllOrgWideOrForProject}.
     */
    private Set<LocalDate> expandedHolidayDates(Long projectId, LocalDate from, LocalDate to) {
        return holidays.findAllOrgWideOrForProject(projectId).stream()
                .flatMap(h -> h.isRecurring()
                        ? recurringOccurrences(h.getHolidayDate(), from, to)
                        : Stream.of(h.getHolidayDate()))
                .filter(d -> !d.isBefore(from) && !d.isAfter(to))
                .collect(Collectors.toSet());
    }

    /**
     * {@code original}'s month and day, once per year in {@code [from, to]}.
     * {@link MonthDay#atYear(int)} folds a 29 February into 28 in a non-leap
     * year rather than throwing, which is the answer wanted here too.
     */
    private Stream<LocalDate> recurringOccurrences(LocalDate original, LocalDate from, LocalDate to) {
        MonthDay monthDay = MonthDay.from(original);
        return IntStream.rangeClosed(from.getYear(), to.getYear()).mapToObj(monthDay::atYear);
    }

    private List<ResourceLeave> leavesFor(Long userId, LocalDate from, LocalDate to) {
        return userId == null ? List.of() : leaves.findApprovedOverlapping(userId, from, to);
    }

    // ------------------------------------------------------------------
    // Duration / BigDecimal-hours conversion
    // ------------------------------------------------------------------

    private static BigDecimal toHours(Duration duration) {
        return BigDecimal.valueOf(duration.toSeconds())
                .divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    private static Duration toDuration(BigDecimal hours) {
        return Duration.ofSeconds(hours.multiply(SECONDS_PER_HOUR).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    private static Instant maxInstant(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant minInstant(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}

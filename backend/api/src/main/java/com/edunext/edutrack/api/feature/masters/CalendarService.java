package com.edunext.edutrack.api.feature.masters;

import com.edunext.edutrack.domain.masters.Holiday;
import com.edunext.edutrack.domain.masters.HolidayRepository;
import com.edunext.edutrack.domain.masters.ResourceLeave;
import com.edunext.edutrack.domain.masters.ResourceLeaveRepository;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * B-023 · S-14, the working calendar.
 *
 * <p>Three things that look separate to an Admin and are one thing to the
 * working-hours service: the weekly-off pattern, org and project holidays, and
 * per-resource leave. B-024 reads all three to answer a single question — is
 * this instant working time — so they live behind one service rather than three.
 *
 * <p><b>Nothing here computes durations.</b> That is B-024's job, deliberately:
 * CLAUDE.md's rule is that every SLA, duration and utilisation figure routes
 * through one implementation, and a second one growing here because it was
 * convenient is exactly how four answers to the same question appear.
 */
@Service
public class CalendarService {

    /** Matches {@code Limit} in the contract. */
    static final int MAX_LIMIT = 200;
    static final int DEFAULT_LIMIT = 50;

    private final WorkingCalendarRepository calendars;
    private final HolidayRepository holidays;
    private final ResourceLeaveRepository leaves;
    private final CalendarMapper mapper;

    CalendarService(WorkingCalendarRepository calendars,
                    HolidayRepository holidays,
                    ResourceLeaveRepository leaves,
                    CalendarMapper mapper) {
        this.calendars = calendars;
        this.holidays = holidays;
        this.leaves = leaves;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // The working week
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CalendarDtos.WorkingWeek workingWeek() {
        return mapper.toWorkingWeek(calendars.getCalendar());
    }

    /**
     * Replaces the working week.
     *
     * <p>The zone is parsed before anything is written. An unknown id would
     * otherwise store cleanly — the column is a plain {@code VARCHAR} — and fail
     * later inside B-024, where the stack trace says nothing about a calendar
     * edit made days earlier.
     */
    @Transactional
    public CalendarDtos.WorkingWeek updateWorkingWeek(CalendarDtos.WorkingWeekUpdate update) {
        if (!update.workDayEnd().isAfter(update.workDayStart())) {
            throw new CalendarValidationException(
                    "workDayEnd", "the working day must end after it starts");
        }
        try {
            ZoneId.of(update.timezone());
        } catch (DateTimeException e) {
            throw new CalendarValidationException(
                    "timezone", "'" + update.timezone() + "' is not a known IANA time-zone id");
        }

        WorkingCalendar calendar = calendars.getCalendar();
        mapper.applyWorkingWeek(update, calendar);
        return mapper.toWorkingWeek(calendars.save(calendar));
    }

    // ------------------------------------------------------------------
    // The read model B-024 consumes
    // ------------------------------------------------------------------

    /**
     * Holidays for a window plus the working week, in one response.
     *
     * <p>Both halves together because a caller computing working hours needs
     * both, and two round trips would let them disagree by the width of a
     * concurrent edit.
     *
     * <p>Recurring holidays are returned as stored — one row, not one per year.
     * Expanding them across years is the working-hours service's job, which is
     * why {@code HolidayRepository} says so on both of its queries.
     */
    @Transactional(readOnly = true)
    public CalendarDtos.CalendarData calendar(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfYear(1);
        LocalDate end = to != null ? to : start.plusYears(1);
        WorkingCalendar week = calendars.getCalendar();

        return new CalendarDtos.CalendarData(
                mapper.toHolidays(holidays.findByHolidayDateBetweenAndIsActiveTrue(start, end)),
                mapper.toIsoDays(week.getWeeklyOff()),
                week.getWorkDayStart(),
                week.getWorkDayEnd());
    }

    // ------------------------------------------------------------------
    // Holidays
    // ------------------------------------------------------------------

    /**
     * {@code uq_holidays (holiday_date, project_id)} is the real guard; this
     * turns its violation into a 409 naming the clash rather than a 500. The
     * constraint still runs — two concurrent creates would race past this check
     * and one of them must lose at the database, not here.
     */
    @Transactional
    public CalendarDtos.Holiday createHoliday(CalendarDtos.HolidayWrite write) {
        Holiday holiday = mapper.toHolidayEntity(write);
        try {
            return mapper.toHoliday(holidays.saveAndFlush(holiday));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateHolidayException(write.date(), write.projectId());
        }
    }

    @Transactional
    public Optional<CalendarDtos.Holiday> updateHoliday(long id, CalendarDtos.HolidayPatch patch) {
        return holidays.findById(id).map(holiday -> {
            mapper.applyUpdate(patch, holiday);
            try {
                return mapper.toHoliday(holidays.saveAndFlush(holiday));
            } catch (DataIntegrityViolationException e) {
                throw new DuplicateHolidayException(holiday.getHolidayDate(), holiday.getProjectId());
            }
        });
    }

    /**
     * A hard delete. Nothing references a holiday by id — the working-hours
     * service reads a set of dates for a window — so removing one makes that day
     * workable from the next calculation. Figures already recorded are stored,
     * not recomputed, so history does not move.
     */
    @Transactional
    public boolean deleteHoliday(long id) {
        if (!holidays.existsById(id)) {
            return false;
        }
        holidays.deleteById(id);
        return true;
    }

    // ------------------------------------------------------------------
    // Resource leave
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CalendarDtos.ResourceLeave> leaves(Long userId, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().minusMonths(1);
        LocalDate end = to != null ? to : start.plusYears(1);

        if (userId != null) {
            return mapper.toLeaves(leaves.findApprovedOverlapping(userId, start, end));
        }
        return mapper.toLeaves(leaves.findAll());
    }

    @Transactional
    public CalendarDtos.ResourceLeave createLeave(CalendarDtos.ResourceLeaveWrite write) {
        rejectInvertedRange(write);
        return mapper.toLeave(leaves.save(mapper.toLeaveEntity(write)));
    }

    /**
     * The range check runs against the <em>merged</em> row, not the request. A
     * patch that moves only {@code startDate} past the stored {@code endDate}
     * would otherwise pass — the request alone has nothing to compare.
     */
    @Transactional
    public Optional<CalendarDtos.ResourceLeave> updateLeave(long id, CalendarDtos.ResourceLeavePatch patch) {
        return leaves.findById(id).map(leave -> {
            mapper.applyUpdate(patch, leave);
            if (leave.getEndDate().isBefore(leave.getStartDate())) {
                throw new CalendarValidationException("endDate", "leave cannot end before it starts");
            }
            return mapper.toLeave(leaves.save(leave));
        });
    }

    @Transactional
    public boolean deleteLeave(long id) {
        if (!leaves.existsById(id)) {
            return false;
        }
        leaves.deleteById(id);
        return true;
    }

    /**
     * {@code ck_resource_leaves_range} enforces this too. Checking here is what
     * turns it into a 400 naming {@code endDate} instead of a 500 carrying a
     * driver message.
     */
    private void rejectInvertedRange(CalendarDtos.ResourceLeaveWrite write) {
        if (write.endDate().isBefore(write.startDate())) {
            throw new CalendarValidationException("endDate", "leave cannot end before it starts");
        }
    }

    /** A create or edit the caller can fix by changing a named field. */
    static class CalendarValidationException extends RuntimeException {

        private final String field;

        CalendarValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** {@code uq_holidays (holiday_date, project_id)} already holds that day. */
    static class DuplicateHolidayException extends RuntimeException {

        DuplicateHolidayException(LocalDate date, Long projectId) {
            super(projectId == null
                    ? "an org-wide holiday already exists on " + date
                    : "project " + projectId + " already has a holiday on " + date);
        }
    }
}

package com.edunext.edutrack.api.feature.masters;

import com.edunext.edutrack.domain.masters.Holiday;
import com.edunext.edutrack.domain.masters.HolidayRepository;
import com.edunext.edutrack.domain.masters.ResourceLeave;
import com.edunext.edutrack.domain.masters.ResourceLeaveRepository;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-023 · the rules the database cannot express as a message.
 *
 * <p>Every check here is duplicated by a constraint —
 * {@code ck_working_calendar_day_bounds}, {@code ck_resource_leaves_range},
 * {@code uq_holidays}. The constraint is what actually guarantees the data; this
 * layer exists so the caller gets a 400 naming a field instead of a 500 carrying
 * a driver message, and these tests are about that difference.
 *
 * <p>The real generated mapper is used rather than a mock. It is the piece most
 * likely to be silently wrong — mocking it would assert that the service calls a
 * mapper, which is not the part that breaks.
 */
class CalendarServiceTest {

    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);
    private final HolidayRepository holidays = mock(HolidayRepository.class);
    private final ResourceLeaveRepository leaves = mock(ResourceLeaveRepository.class);
    private final CalendarMapper mapper = new CalendarMapperImpl();

    private final CalendarService service =
            new CalendarService(calendars, holidays, leaves, mapper);

    private WorkingCalendar stored;

    @BeforeEach
    void setUp() {
        stored = new WorkingCalendar();
        stored.setWeeklyOff(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        stored.setWorkDayStart(LocalTime.of(9, 30));
        stored.setWorkDayEnd(LocalTime.of(18, 30));
        stored.setTimezone("Asia/Kolkata");
        when(calendars.getCalendar()).thenReturn(stored);
        when(calendars.save(any(WorkingCalendar.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static CalendarDtos.WorkingWeekUpdate update(Set<Integer> off, LocalTime start,
                                                         LocalTime end, String zone) {
        return new CalendarDtos.WorkingWeekUpdate(off, start, end, zone);
    }

    // ------------------------------------------------------------------
    // The working week
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the working week reads back as ISO day numbers, sorted")
    void readsTheWorkingWeek() {
        CalendarDtos.WorkingWeek week = service.workingWeek();

        assertThat(week.weeklyOff())
                .as("6 = Saturday, 7 = Sunday under ISO-8601")
                .containsExactly(6, 7);
        assertThat(week.workDayStart()).isEqualTo(LocalTime.of(9, 30));
        assertThat(week.timezone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    @DisplayName("a working day that ends before it starts is refused by field name")
    void refusesAnInvertedWorkingDay() {
        assertThatThrownBy(() -> service.updateWorkingWeek(
                update(Set.of(6, 7), LocalTime.of(18, 30), LocalTime.of(9, 30), "Asia/Kolkata")))
                .isInstanceOf(CalendarService.CalendarValidationException.class)
                .hasMessageContaining("must end after it starts");

        verify(calendars, never()).save(any());
    }

    @Test
    @DisplayName("a zero-length working day is refused too")
    void refusesAZeroLengthDay() {
        assertThatThrownBy(() -> service.updateWorkingWeek(
                update(Set.of(6, 7), LocalTime.of(9, 30), LocalTime.of(9, 30), "Asia/Kolkata")))
                .isInstanceOf(CalendarService.CalendarValidationException.class);
    }

    /**
     * The column is a plain {@code VARCHAR}, so an unknown zone stores cleanly
     * and fails later inside the working-hours service, where nothing in the
     * stack trace mentions a calendar edit made days earlier.
     */
    @Test
    @DisplayName("an unknown time-zone id is caught here, not inside the SLA engine")
    void refusesAnUnknownTimezone() {
        assertThatThrownBy(() -> service.updateWorkingWeek(
                update(Set.of(6, 7), LocalTime.of(9, 30), LocalTime.of(18, 30), "Mars/Olympus")))
                .isInstanceOf(CalendarService.CalendarValidationException.class)
                .hasMessageContaining("not a known IANA time-zone id");

        verify(calendars, never()).save(any());
    }

    /**
     * The regression test for a bug that returned the right answer while saving
     * nothing.
     *
     * <p>MapStruct's default collection strategy mutates what the getter returns
     * — {@code getWeeklyOff().clear()} then {@code addAll(...)}. That getter
     * derives a fresh {@code EnumSet} from the stored ISO numbers on every call,
     * so the clear-and-add landed on a throwaway object. The endpoint answered
     * 200 with the new week in the body and the entity never changed.
     *
     * <p>Asserting on the <em>entity</em> is the whole point: the returned DTO
     * looked correct throughout.
     */
    @Test
    @DisplayName("the replace reaches the entity, not just the response body")
    void theWorkingWeekActuallyPersists() {
        service.updateWorkingWeek(
                update(Set.of(7), LocalTime.of(9, 0), LocalTime.of(17, 0), "UTC"));

        assertThat(stored.getWeeklyOff())
                .as("mutating a derived getter's return value would leave this at Sat/Sun")
                .containsExactly(DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("a valid replace writes the days through DayOfWeek, not raw numbers")
    void appliesAValidWorkingWeek() {
        CalendarDtos.WorkingWeek saved = service.updateWorkingWeek(
                update(Set.of(5, 6), LocalTime.of(8, 0), LocalTime.of(17, 0), "Asia/Dubai"));

        assertThat(saved.weeklyOff()).containsExactly(5, 6);
        assertThat(stored.getWeeklyOff())
                .as("a Fri/Sat weekend, resolved to the enum rather than left as ints")
                .containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
        assertThat(stored.getWorkDayStart()).isEqualTo(LocalTime.of(8, 0));
        assertThat(stored.getTimezone()).isEqualTo("Asia/Dubai");
    }

    // ------------------------------------------------------------------
    // Holidays
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a duplicate date becomes a 409-shaped failure, not a driver exception")
    void translatesTheUniqueConstraint() {
        when(holidays.saveAndFlush(any(Holiday.class)))
                .thenThrow(new DataIntegrityViolationException("uq_holidays"));

        assertThatThrownBy(() -> service.createHoliday(new CalendarDtos.HolidayWrite(
                LocalDate.of(2026, 8, 15), "Independence Day", null, false, true)))
                .isInstanceOf(CalendarService.DuplicateHolidayException.class)
                .hasMessageContaining("org-wide holiday already exists");
    }

    @Test
    @DisplayName("a project holiday names the project in the clash message")
    void namesTheProjectOnAProjectClash() {
        when(holidays.saveAndFlush(any(Holiday.class)))
                .thenThrow(new DataIntegrityViolationException("uq_holidays"));

        assertThatThrownBy(() -> service.createHoliday(new CalendarDtos.HolidayWrite(
                LocalDate.of(2026, 8, 15), "Team offsite", 3L, false, true)))
                .hasMessageContaining("project 3");
    }

    @Test
    @DisplayName("the flags default when the request omits them")
    void appliesFlagDefaults() {
        when(holidays.saveAndFlush(any(Holiday.class))).thenAnswer(i -> i.getArgument(0));

        CalendarDtos.Holiday created = service.createHoliday(new CalendarDtos.HolidayWrite(
                LocalDate.of(2026, 12, 25), "Christmas", null, null, null));

        assertThat(created.isRecurring()).isFalse();
        assertThat(created.isActive()).isTrue();
    }

    @Test
    @DisplayName("editing only the name leaves the date alone")
    void partialUpdateDoesNotBlankTheDate() {
        Holiday existing = new Holiday();
        existing.setId(4L);
        existing.setHolidayDate(LocalDate.of(2026, 8, 15));
        existing.setName("Independance Day");
        existing.setActive(true);
        when(holidays.findById(4L)).thenReturn(Optional.of(existing));
        when(holidays.saveAndFlush(any(Holiday.class))).thenAnswer(i -> i.getArgument(0));

        service.updateHoliday(4L, new CalendarDtos.HolidayPatch(
                null, "Independence Day", null, null, null));

        assertThat(existing.getName()).isEqualTo("Independence Day");
        assertThat(existing.getHolidayDate())
                .as("a NOT NULL column would otherwise be blanked by a partial edit")
                .isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    @DisplayName("deleting something that is not there reports absence rather than throwing")
    void deleteReportsAbsence() {
        when(holidays.existsById(99L)).thenReturn(false);

        assertThat(service.deleteHoliday(99L)).isFalse();
        verify(holidays, never()).deleteById(99L);
    }

    // ------------------------------------------------------------------
    // Leave
    // ------------------------------------------------------------------

    @Test
    @DisplayName("leave cannot end before it starts")
    void refusesAnInvertedLeaveRange() {
        assertThatThrownBy(() -> service.createLeave(new CalendarDtos.ResourceLeaveWrite(
                7L, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1),
                "PLANNED", false, "APPROVED", null)))
                .isInstanceOf(CalendarService.CalendarValidationException.class)
                .hasMessageContaining("cannot end before it starts");

        verify(leaves, never()).save(any());
    }

    @Test
    @DisplayName("single-day leave is allowed — start equals end")
    void allowsSingleDayLeave() {
        when(leaves.save(any(ResourceLeave.class))).thenAnswer(i -> i.getArgument(0));

        CalendarDtos.ResourceLeave created = service.createLeave(new CalendarDtos.ResourceLeaveWrite(
                7L, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10),
                "SICK", true, null, "flu"));

        assertThat(created.startDate()).isEqualTo(created.endDate());
        assertThat(created.isHalfDay()).isTrue();
    }

    /**
     * Blueprint §5: only approved leave stops the clock. Defaulting to
     * {@code APPROVED} matches the column default an Admin-entered record
     * implies — an Admin recording leave is not filing a request.
     */
    @Test
    @DisplayName("status defaults to APPROVED when omitted")
    void defaultsLeaveStatus() {
        when(leaves.save(any(ResourceLeave.class))).thenAnswer(i -> i.getArgument(0));

        CalendarDtos.ResourceLeave created = service.createLeave(new CalendarDtos.ResourceLeaveWrite(
                7L, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12), null, null, null, null));

        assertThat(created.status()).isEqualTo("APPROVED");
    }
}

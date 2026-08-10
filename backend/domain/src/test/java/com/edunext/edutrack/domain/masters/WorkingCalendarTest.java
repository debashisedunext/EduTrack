package com.edunext.edutrack.domain.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-023 · the ISO day-number conversion, without Docker.
 *
 * <p>{@link WorkingCalendarIT} proves the constraints and the round trip against
 * real MySQL. This proves the mapping itself, in surefire, on every build —
 * including on a machine with no Docker daemon. The whole defect this task fixes
 * was a numbering mismatch, so the numbering deserves a test that always runs.
 */
class WorkingCalendarTest {

    /**
     * The mapping, stated as a table. If a future refactor introduces
     * arithmetic on the day number, this is what fails.
     */
    @ParameterizedTest(name = "{0} is ISO day {1}")
    @CsvSource({
            "MONDAY,    1",
            "TUESDAY,   2",
            "WEDNESDAY, 3",
            "THURSDAY,  4",
            "FRIDAY,    5",
            "SATURDAY,  6",
            "SUNDAY,    7",
    })
    @DisplayName("each day maps to its ISO-8601 number in both directions")
    void mapsEveryDayBothWays(DayOfWeek day, int iso) {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWeeklyOff(EnumSet.of(day));

        assertThat(calendar.getWeeklyOffDays())
                .as("written as the ISO number, which is DayOfWeek.getValue()")
                .containsExactly(iso);
        assertThat(calendar.getWeeklyOff())
                .as("and read back as the same day")
                .containsExactly(day);
    }

    @Test
    @DisplayName("[6, 7] is Saturday and Sunday, not Sunday and Saturday-of-a-0-based-week")
    void theStandardWeekend() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWeeklyOffDays(List.of(6, 7));

        assertThat(calendar.getWeeklyOff())
                .containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(calendar.isNonWorkingDay(DayOfWeek.SUNDAY)).isTrue();
        assertThat(calendar.isNonWorkingDay(DayOfWeek.MONDAY)).isFalse();
    }

    /**
     * The old contract's value. The database refuses to store it
     * ({@code ck_working_calendar_weekly_off}); this is what happens if one ever
     * reaches the entity anyway — it throws rather than quietly resolving to
     * some day.
     */
    @Test
    @DisplayName("a 0 from the old JavaScript convention throws instead of guessing")
    void zeroIsNotADay() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWeeklyOffDays(List.of(0, 6));

        assertThatThrownBy(calendar::getWeeklyOff)
                .isInstanceOf(java.time.DateTimeException.class)
                .hasMessageContaining("DayOfWeek");
    }

    @Test
    @DisplayName("an unset pattern reads as no days off, not null")
    void nullReadsAsEmpty() {
        assertThat(new WorkingCalendar().getWeeklyOff()).isEmpty();
    }

    @Test
    @DisplayName("setting null clears the pattern rather than storing null")
    void settingNullStoresAnEmptyArray() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWeeklyOff(null);

        assertThat(calendar.getWeeklyOffDays())
                .as("the column is NOT NULL, so an empty array is the storable form")
                .isEmpty();
    }

    @Test
    @DisplayName("the id defaults to the singleton key")
    void idDefaultsToOne() {
        assertThat(new WorkingCalendar().getId()).isEqualTo(WorkingCalendar.SINGLETON_ID);
    }

    // ------------------------------------------------------------------
    // The working day, stored as minutes
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}:{1} is {2} minutes from midnight")
    @CsvSource({
            "0,  0,     0",
            "9,  30,  570",
            "18, 30, 1110",
            "23, 59, 1439",
    })
    @DisplayName("a wall-clock boundary round-trips through the minute encoding")
    void roundTripsTheWorkingDay(int hour, int minute, int expectedMinutes) {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWorkDayStart(LocalTime.of(hour, minute));

        assertThat(calendar.getWorkDayStartMin()).isEqualTo((short) expectedMinutes);
        assertThat(calendar.getWorkDayStart()).isEqualTo(LocalTime.of(hour, minute));
    }

    @Test
    @DisplayName("the working day length is the gap between the boundaries")
    void computesTheWorkingDayLength() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWorkDayStart(LocalTime.of(9, 30));
        calendar.setWorkDayEnd(LocalTime.of(18, 30));

        assertThat(calendar.workDayLength()).isEqualTo(Duration.ofHours(9));
    }

    /**
     * A day closing at midnight is 1440 minutes in, not 0. Reading it back as
     * {@code LocalTime.MIDNIGHT} would make the working day nine hours negative.
     */
    @Test
    @DisplayName("a midnight close is the end of the day, not the start of it")
    void midnightCloseIsTheEndOfTheDay() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWorkDayStart(LocalTime.of(9, 30));
        calendar.setWorkDayEnd(LocalTime.MAX);

        assertThat(calendar.getWorkDayEndMin()).isEqualTo((short) 1440);
        assertThat(calendar.getWorkDayEnd()).isEqualTo(LocalTime.MAX);
        assertThat(calendar.workDayLength()).isPositive();
    }
}

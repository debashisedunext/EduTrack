package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-063 · the pivot, the week boundary and the two figures that are easy to get
 * quietly wrong.
 *
 * <p>Everything here is arithmetic over rows the repository hands back, so it
 * runs without a container. {@code TimesheetIT} covers the half this cannot:
 * that the query returns those rows in the first place, and that the visibility
 * rule holds against real reporting lines and project memberships.
 */
class TimesheetServiceTest {

    private final TimesheetRepository repository = mock(TimesheetRepository.class);
    private final Profile360Repository people = mock(Profile360Repository.class);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);
    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);

    private final TimesheetService service =
            new TimesheetService(repository, people, workingHours, calendars);

    private static final CallerIdentity ADMIN = new CallerIdentity(1L, "ADMIN", List.of());

    /** Monday 10 August 2026 through Sunday the 16th. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);

    @BeforeEach
    void visible() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setTimezone("Asia/Kolkata");
        when(calendars.getCalendar()).thenReturn(calendar);

        when(people.isVisibleTo(anyLong(), anyLong(), anyString())).thenReturn(true);
        when(people.subject(anyLong())).thenReturn(Optional.of(new Profile360Repository.Subject(
                7L, "Ravi Kumar", "ravi.kumar", "ravi@example.test", "DEVELOPER",
                "Delivery", "Engineer", true, LocalDate.of(2024, 1, 1), "Meera Nair")));

        // Nine hours every day unless a test says otherwise — the seeded
        // calendar's 09:30–18:30. Weekends are set to zero where they matter.
        when(workingHours.workingHoursBetween(any(), any(), isNull(), anyLong()))
                .thenReturn(new BigDecimal("9.00"));
    }

    @Nested
    @DisplayName("the week the server answers")
    class Week {

        @Test
        @DisplayName("any date inside the week resolves to the same Monday–Sunday")
        void anyDateInTheWeek() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of());

            // Thursday. A client should never have to compute a week boundary,
            // because the moment it computes a different one from the server the
            // grid pages through weeks its own totals do not cover.
            TimesheetDtos.Timesheet thursday = week(LocalDate.of(2026, 8, 13));
            TimesheetDtos.Timesheet monday = week(MONDAY);

            assertThat(thursday.weekStart()).isEqualTo("2026-08-10");
            assertThat(thursday.weekEnd()).isEqualTo("2026-08-16");
            assertThat(monday.weekStart()).isEqualTo(thursday.weekStart());
        }

        @Test
        @DisplayName("a Sunday belongs to the week that started six days earlier, not the next one")
        void sundayIsTheEndOfItsWeek() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of());

            // The off-by-one that a Sunday-first calendar would introduce, and
            // the reason the boundary is resolved server-side at all.
            assertThat(week(LocalDate.of(2026, 8, 16)).weekStart()).isEqualTo("2026-08-10");
        }

        @Test
        @DisplayName("always seven days, including the ones with nothing on them")
        void sevenDays() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of());

            TimesheetDtos.Timesheet sheet = week(MONDAY);

            assertThat(sheet.days()).hasSize(7);
            assertThat(sheet.days().get(0).date()).isEqualTo("2026-08-10");
            assertThat(sheet.days().get(6).date()).isEqualTo("2026-08-16");
        }
    }

    @Nested
    @DisplayName("the pivot — what makes this stage-aware")
    class Pivot {

        @Test
        @DisplayName("splits one ticket into a row per stage and iteration")
        void stageAndIterationArePartOfTheKey() {
            // The whole point of the task. Six hours on one ticket reads very
            // differently when four of them are a second pass through QA, and a
            // row keyed on ticket alone cannot say so.
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry("DEV", 1, 1, MONDAY, "2.00"),
                    entry("QA", 1, 1, MONDAY.plusDays(1), "1.00"),
                    entry("QA", 2, 1, MONDAY.plusDays(2), "3.00")));

            List<TimesheetDtos.Row> rows = week(MONDAY).rows();

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(TimesheetDtos.Row::stage, TimesheetDtos.Row::iterationNo)
                    .containsExactlyInAnyOrder(
                            tuple("DEV", 1),
                            tuple("QA", 1),
                            tuple("QA", 2));
        }

        @Test
        @DisplayName("keeps the same stage in two cycles apart")
        void cycleIsPartOfTheKey() {
            // PLAN.md §3.4's reason for carrying cycleNo at all: a reopened
            // ticket re-entering the same stage at the same iteration would
            // otherwise fold cycle 1's hours into cycle 2's line.
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry("DEV", 1, 1, MONDAY, "2.00"),
                    entry("DEV", 1, 2, MONDAY.plusDays(1), "3.00")));

            assertThat(week(MONDAY).rows()).hasSize(2);
        }

        @Test
        @DisplayName("sums a row across the days it touches and totals the week")
        void rowAndWeekTotals() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry("DEV", 1, 1, MONDAY, "2.50"),
                    entry("DEV", 1, 1, MONDAY.plusDays(1), "1.25"),
                    entry("DEV", 1, 1, MONDAY.plusDays(1), "0.25")));

            TimesheetDtos.Timesheet sheet = week(MONDAY);
            TimesheetDtos.Row row = sheet.rows().getFirst();

            assertThat(row.hours()).containsExactly(
                    java.util.Map.entry("2026-08-10", new BigDecimal("2.50")),
                    java.util.Map.entry("2026-08-11", new BigDecimal("1.50")));
            assertThat(row.totalHours()).isEqualTo(new BigDecimal("4.00"));
            assertThat(sheet.totalHours()).isEqualTo(new BigDecimal("4.00"));
        }

        @Test
        @DisplayName("orders the busiest row first, so a week reads as where the time went")
        void busiestFirst() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry("DEV", 1, 1, MONDAY, "1.00"),
                    entry("QA", 1, 1, MONDAY, "6.00")));

            assertThat(week(MONDAY).rows()).extracting(TimesheetDtos.Row::stage)
                    .containsExactly("QA", "DEV");
        }

        @Test
        @DisplayName("keeps an entry that predates the ribbon rather than dropping it")
        void nullStageSurvives() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry(null, 1, 1, MONDAY, "4.00")));

            List<TimesheetDtos.Row> rows = week(MONDAY).rows();

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().stage()).isNull();
            assertThat(rows.getFirst().totalHours()).isEqualTo(new BigDecimal("4.00"));
        }
    }

    @Nested
    @DisplayName("corrections")
    class Corrections {

        @Test
        @DisplayName("a reversal nets out of the totals and marks its row")
        void reversalNetsOut() {
            // ticket_effort_logs is append-only: a wrong entry is reversed by a
            // negative compensating row, never edited. A timesheet that hid them
            // would not add up to the same week the ticket's own effort tab shows.
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry("DEV", 1, 1, MONDAY, "5.00"),
                    correction("DEV", 1, 1, MONDAY, "-5.00"),
                    entry("DEV", 1, 1, MONDAY, "2.00")));

            TimesheetDtos.Timesheet sheet = week(MONDAY);

            assertThat(sheet.totalHours()).isEqualTo(new BigDecimal("2.00"));
            assertThat(sheet.rows().getFirst().hasCorrection()).isTrue();
            assertThat(sheet.days().getFirst().loggedHours()).isEqualTo(new BigDecimal("2.00"));
        }

        @Test
        @DisplayName("a row with no correction says so")
        void ordinaryRowIsNotFlagged() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry("DEV", 1, 1, MONDAY, "5.00")));

            assertThat(week(MONDAY).rows().getFirst().hasCorrection()).isFalse();
        }
    }

    @Nested
    @DisplayName("capacity and utilisation")
    class Capacity {

        @Test
        @DisplayName("a day the calendar offers nothing is not a working day")
        void weekendIsNotAWorkingDay() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of());
            zeroCapacityOn(MONDAY.plusDays(5));   // Saturday
            zeroCapacityOn(MONDAY.plusDays(6));   // Sunday

            TimesheetDtos.Timesheet sheet = week(MONDAY);

            assertThat(sheet.days().get(5).isWorkingDay()).isFalse();
            assertThat(sheet.days().get(4).isWorkingDay()).isTrue();
            assertThat(sheet.capacityHours()).isEqualTo(new BigDecimal("45.00"));
        }

        @Test
        @DisplayName("hours logged on a non-working day are still shown")
        void weekendWorkIsNotHidden() {
            // Somebody worked a Saturday. Capacity says zero and the hours are
            // real — dropping either would be a different lie.
            zeroCapacityOn(MONDAY.plusDays(5));
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry("DEV", 1, 1, MONDAY.plusDays(5), "4.00")));

            TimesheetDtos.Timesheet sheet = week(MONDAY);

            assertThat(sheet.days().get(5).loggedHours()).isEqualTo(new BigDecimal("4.00"));
            assertThat(sheet.days().get(5).capacityHours()).isEqualTo(new BigDecimal("0.00"));
            assertThat(sheet.days().get(5).isWorkingDay()).isFalse();
        }

        @Test
        @DisplayName("utilisation is logged over available, to one place")
        void utilisation() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of(
                    entry("DEV", 1, 1, MONDAY, "9.00")));
            zeroCapacityOn(MONDAY.plusDays(5));
            zeroCapacityOn(MONDAY.plusDays(6));

            // 9 of 45.
            assertThat(week(MONDAY).utilisationPct()).isEqualTo(new BigDecimal("20.0"));
        }

        @Test
        @DisplayName("a week that offered no working hours has no utilisation, not 0%")
        void nullWhenNothingWasAvailable() {
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of());
            for (int i = 0; i < 7; i++) {
                zeroCapacityOn(MONDAY.plusDays(i));
            }

            // Somebody on leave all week. 0% is a claim about how they spent it;
            // null says the question has no answer.
            assertThat(week(MONDAY).utilisationPct()).isNull();
        }
    }

    @Nested
    @DisplayName("who may look at whom")
    class Visibility {

        @Test
        @DisplayName("a caller who may not see the subject gets nothing, and so does one asking after a ghost")
        void refusalAndAbsenceAreTheSameAnswer() {
            // Both become 404 at the controller. A 403 for one and a 404 for the
            // other would let anyone walk the id space and learn who exists.
            when(repository.entries(anyLong(), any(), any())).thenReturn(List.of());

            when(people.isVisibleTo(anyLong(), anyLong(), anyString())).thenReturn(false);
            assertThat(service.week(ADMIN, 7L, MONDAY)).isEmpty();

            when(people.isVisibleTo(anyLong(), anyLong(), anyString())).thenReturn(true);
            when(people.subject(anyLong())).thenReturn(Optional.empty());
            assertThat(service.week(ADMIN, 7L, MONDAY)).isEmpty();
        }

        @Test
        @DisplayName("the effort log is never read for a subject the caller may not see")
        void refusedBeforeReading() {
            when(people.isVisibleTo(anyLong(), anyLong(), anyString())).thenReturn(false);

            assertThat(service.week(ADMIN, 7L, MONDAY)).isEmpty();
            verify(repository, never())
                    .entries(anyLong(), any(), any());
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private TimesheetDtos.Timesheet week(LocalDate weekOf) {
        return service.week(ADMIN, 7L, weekOf).orElseThrow();
    }

    private void zeroCapacityOn(LocalDate date) {
        Instant start = date.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        when(workingHours.workingHoursBetween(eq(start), any(), isNull(), anyLong()))
                .thenReturn(new BigDecimal("0.00"));
    }

    private static TimesheetRepository.Entry entry(String stage, int iteration, int cycle,
                                                   LocalDate date, String hours) {
        return new TimesheetRepository.Entry(42L, "CRM-26-00347", "Login fails",
                3L, "CRM", "CRM Portal", stage, stage == null ? null : stage + " work",
                iteration, cycle, date, new BigDecimal(hours), false);
    }

    private static TimesheetRepository.Entry correction(String stage, int iteration, int cycle,
                                                        LocalDate date, String hours) {
        return new TimesheetRepository.Entry(42L, "CRM-26-00347", "Login fails",
                3L, "CRM", "CRM Portal", stage, stage + " work",
                iteration, cycle, date, new BigDecimal(hours), true);
    }
}

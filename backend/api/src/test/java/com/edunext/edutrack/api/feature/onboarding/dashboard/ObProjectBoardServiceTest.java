package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoard;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoardRow;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObProjectBoardRepository.TaskCounts;
import com.edunext.edutrack.api.feature.onboarding.projects.ObRunningProject;
import com.edunext.edutrack.api.feature.onboarding.projects.ObRunningProjectReader;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The bucket arithmetic behind OB-02's project board.
 *
 * <h2>The calendar is a fake here and real in {@code ObProjectBoardIT}</h2>
 *
 * <p>{@code ObDelayedProjectsServiceTest} draws this line and this follows it:
 * a unit test that needs a container to assert a rounding rule is a unit test
 * nobody runs. {@link WeekdayHours} counts Mon–Fri 09:00–18:00 by stepping
 * minutes — twenty lines of arithmetic that owe nothing to the code under
 * test — so every case below runs in milliseconds, and the IT beside it pins
 * the same Friday-to-Monday example against the real
 * {@link WorkingHoursService} and the org's actual holiday table.
 *
 * <p>What is under test is not the hour count: it is the conversion of that
 * count into a bucket, the null-not-zero rule, and the six cards being counted
 * from the very rows the response carries.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObProjectBoardServiceTest {

    /** A Wednesday, an hour into the working day. */
    private static final Instant WEDNESDAY = Instant.parse("2026-09-16T10:00:00Z");

    private final ObRunningProjectReader projects = mock(ObRunningProjectReader.class);
    private final ObProjectBoardRepository counts = mock(ObProjectBoardRepository.class);
    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);

    @BeforeEach
    void wireUp() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setTimezone("UTC");
        calendar.setWeeklyOff(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        calendar.setWorkDayStart(LocalTime.of(9, 0));
        calendar.setWorkDayEnd(LocalTime.of(18, 0));
        when(calendars.getCalendar()).thenReturn(calendar);

        when(workingHours.workingHoursBetween(any(), any()))
                .thenAnswer(call -> WeekdayHours.between(call.getArgument(0), call.getArgument(1)));

        when(counts.taskCountsByProject(anyCollection())).thenReturn(Map.of());
        when(counts.openEscalationsByProject(anyCollection())).thenReturn(Map.of());
    }

    // ── the working-calendar conversion ─────────────────────────────────────

    @Test
    @DisplayName("a Friday-due project is not late on Saturday, and is one working day late on Monday")
    void aFridayDueProjectIsNotLateOnSaturdayAndIsOneDayLateOnMonday() {
        given(project(1, LocalDate.parse("2026-09-11")));

        // Saturday morning. A naive day subtraction would already say one.
        ObProjectBoardRow saturday = board(Instant.parse("2026-09-12T10:00:00Z")).projects().get(0);
        assertThat(saturday.daysPastCompletion()).isNull();
        assertThat(saturday.bucket()).isEqualTo("ON_TIME");

        // The following Monday, an hour into the day. A naive subtraction says three.
        ObProjectBoardRow monday = board(Instant.parse("2026-09-14T10:00:00Z")).projects().get(0);
        assertThat(monday.daysPastCompletion()).isEqualTo(1);
        assertThat(monday.bucket()).isEqualTo("DELAYED");
    }

    @Test
    @DisplayName("a project due today is not late during that day — the date is measured from close of business")
    void aProjectDueTodayIsNotLateYet() {
        given(project(1, LocalDate.parse("2026-09-16")));

        ObProjectBoardRow row = board(WEDNESDAY).projects().get(0);

        assertThat(row.daysPastCompletion()).isNull();
        assertThat(row.bucket()).isEqualTo("ON_TIME");
    }

    // ── the seven-day line ──────────────────────────────────────────────────

    @Test
    @DisplayName("the seventh working day past is DELAYED and the eighth is AT_RISK")
    void sevenWorkingDaysIsDelayedAndEightIsAtRisk() {
        /*
          Counted in working *hours*, then ceilinged — not in whole days, which
          is where an eyeballed boundary goes wrong. Close of business Monday
          the 7th to 10:00 Wednesday the 16th is 55 hours (four days that week,
          two the next, one this morning), which over a nine-hour day ceilings
          to 7. Friday the 4th adds one more day and tips to 8.
        */
        given(project(1, LocalDate.parse("2026-09-07")), project(2, LocalDate.parse("2026-09-04")));

        Map<Long, ObProjectBoardRow> rows = byId(board(WEDNESDAY));

        assertThat(rows.get(1L).daysPastCompletion())
                .isEqualTo(ObProjectBoardService.AT_RISK_AFTER_WORKING_DAYS);
        assertThat(rows.get(1L).bucket()).isEqualTo("DELAYED");
        assertThat(rows.get(2L).daysPastCompletion()).isEqualTo(8);
        assertThat(rows.get(2L).bucket()).isEqualTo("AT_RISK");
    }

    // ── the two facts that are not the same fact ────────────────────────────

    @Test
    @DisplayName("a late task inside the completion date does not make the project late")
    void aLateTaskDoesNotMoveTheBucket() {
        given(running(1, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-30"), 14, 10));

        ObProjectBoardRow row = board(WEDNESDAY).projects().get(0);

        // The task delay is reported, and the project is still on time.
        assertThat(row.delayedByDays()).isEqualTo(14);
        assertThat(row.daysPastCompletion()).isNull();
        assertThat(row.bucket()).isEqualTo("ON_TIME");
    }

    @Test
    @DisplayName("a project with no completion date is NOT_SCHEDULED rather than on time")
    void noCompletionDateIsItsOwnBucket() {
        given(project(1, null));

        assertThat(board(WEDNESDAY).projects().get(0).bucket()).isEqualTo("NOT_SCHEDULED");
    }

    // ── AHEAD is a claim, so it is made from evidence ───────────────────────

    @Test
    @DisplayName("AHEAD needs more work done than budget spent, and nothing overdue")
    void aheadNeedsProgressToOutrunTheBudget() {
        // Opened Monday the 14th on a twenty-day budget — two days in by
        // Wednesday — with nine of ten tasks already finished.
        given(running(1, LocalDate.parse("2026-09-14"), LocalDate.parse("2026-10-09"), null, 20));
        when(counts.taskCountsByProject(anyCollection()))
                .thenReturn(Map.of(1L, new TaskCounts(1, 10, 9)));

        ObProjectBoardRow row = board(WEDNESDAY).projects().get(0);

        assertThat(row.bucket()).isEqualTo("AHEAD");
        assertThat(row.budgetUsedPercent()).isLessThan(90);
    }

    @Test
    @DisplayName("the same project with a task overdue is ON_TIME, never AHEAD")
    void anOverdueTaskDisqualifiesAhead() {
        given(running(1, LocalDate.parse("2026-09-14"), LocalDate.parse("2026-10-09"), 3, 20));
        when(counts.taskCountsByProject(anyCollection()))
                .thenReturn(Map.of(1L, new TaskCounts(1, 10, 9)));

        assertThat(board(WEDNESDAY).projects().get(0).bucket()).isEqualTo("ON_TIME");
    }

    @Test
    @DisplayName("a project that has finished nothing is on time, not ahead")
    void nothingDoneIsNotAhead() {
        given(running(1, LocalDate.parse("2026-09-14"), LocalDate.parse("2026-10-09"), null, 20));
        when(counts.taskCountsByProject(anyCollection()))
                .thenReturn(Map.of(1L, new TaskCounts(1, 10, 0)));

        assertThat(board(WEDNESDAY).projects().get(0).bucket()).isEqualTo("ON_TIME");
    }

    // ── the cards, counted from those rows ──────────────────────────────────

    @Test
    @DisplayName("the five schedule slices sum to the ongoing-projects card")
    void theScheduleSlicesPartitionTheBoard() {
        given(project(1, LocalDate.parse("2026-09-30")),    // on time
                project(2, LocalDate.parse("2026-09-14")),  // delayed
                project(3, LocalDate.parse("2026-08-20")),  // at risk
                project(4, null));                          // not scheduled

        ObProjectBoard board = board(WEDNESDAY);
        var schedule = board.schedule();

        assertThat(schedule.onTime() + schedule.ahead() + schedule.delayed()
                + schedule.atRisk() + schedule.notScheduled())
                .isEqualTo(board.cards().ongoingProjects())
                .isEqualTo(4);
        assertThat(board.cards().overdueProjects()).isEqualTo(schedule.delayed());
        assertThat(board.cards().atRiskProjects()).isEqualTo(schedule.atRisk());
    }

    @Test
    @DisplayName("this week's deadlines counts the whole Mon–Sun week, the days already gone included")
    void thisWeekIncludesTheDaysAlreadyGone() {
        // The week of Wednesday 16 Sep 2026 runs Mon 14 – Sun 20.
        given(project(1, LocalDate.parse("2026-09-14")),    // Monday, already past
                project(2, LocalDate.parse("2026-09-16")),  // today
                project(3, LocalDate.parse("2026-09-20")),  // Sunday
                project(4, LocalDate.parse("2026-09-21"))); // next Monday

        ObProjectBoard board = board(WEDNESDAY);

        assertThat(board.weekStart()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(board.weekEnd()).isEqualTo(LocalDate.parse("2026-09-20"));
        assertThat(board.cards().thisWeeksDeadlines()).isEqualTo(3);
        assertThat(board.cards().todaysDelivery()).isEqualTo(1);
    }

    @Test
    @DisplayName("the escalations card counts projects, not escalations")
    void escalationsCardCountsProjects() {
        given(project(1, LocalDate.parse("2026-09-30")), project(2, LocalDate.parse("2026-09-30")));
        when(counts.openEscalationsByProject(anyCollection())).thenReturn(Map.of(1L, 3));

        assertThat(board(WEDNESDAY).cards().clientEscalations()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty board is every count at zero, not an exception")
    void anEmptyBoardIsZeroes() {
        given();

        ObProjectBoard board = board(WEDNESDAY);

        assertThat(board.projects()).isEmpty();
        assertThat(board.cards().ongoingProjects()).isZero();
        assertThat(board.truncated()).isFalse();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void given(ObRunningProject... rows) {
        when(projects.runningProjects(any())).thenReturn(Arrays.asList(rows));
    }

    private ObProjectBoard board(Instant now) {
        return new ObProjectBoardService(projects, counts, workingHours, calendars,
                Clock.fixed(now, ZoneOffset.UTC))
                .board(caller());
    }

    private static Map<Long, ObProjectBoardRow> byId(ObProjectBoard board) {
        return board.projects().stream()
                .collect(Collectors.toMap(ObProjectBoardRow::id, row -> row));
    }

    /** A project with only a completion date to its name — nothing overdue, a ten-day budget. */
    private static ObRunningProject project(long id, LocalDate completion) {
        return running(id, LocalDate.parse("2026-09-01"), completion, null, 10);
    }

    private static ObRunningProject running(long id, LocalDate start, LocalDate completion,
                                            Integer delayedByDays, int totalTatDays) {
        return new ObRunningProject(id, "Project " + id, start,
                100 + id, "Client " + id, "CL-" + id, "Pune",
                7, "ERP", "ERP Suite",
                21L, "Priya Nair", 31L, "Aarav Joshi",
                "OPEN", "Configuration", delayedByDays, completion, totalTatDays);
    }

    private static CallerIdentity caller() {
        return new CallerIdentity(1, "ADMIN", List.of(), List.of("ONBOARDING"),
                Map.of("ONBOARDING", "OB_ADMIN"));
    }

    /**
     * Mon–Fri, 09:00–18:00 UTC, counted a minute at a time.
     *
     * <p>Deliberately naive and deliberately independent: it shares no code
     * with {@link WorkingHoursService}, so a case passing here and failing in
     * the IT means the two disagree — which is information — rather than the
     * same bug asserting itself twice. No holidays and no leave, because the
     * unit cases are about weekends and the IT owns the rest.
     */
    private static final class WeekdayHours {

        private static final LocalTime OPEN = LocalTime.of(9, 0);
        private static final LocalTime CLOSE = LocalTime.of(18, 0);

        static BigDecimal between(Instant start, Instant end) {
            if (!start.isBefore(end)) {
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            long minutes = 0;
            for (LocalDateTime at = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
                 at.isBefore(LocalDateTime.ofInstant(end, ZoneOffset.UTC));
                 at = at.plusMinutes(1)) {
                if (isWorking(at)) {
                    minutes++;
                }
            }
            return BigDecimal.valueOf(minutes)
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        }

        private static boolean isWorking(LocalDateTime at) {
            DayOfWeek day = at.getDayOfWeek();
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
                return false;
            }
            LocalTime time = at.toLocalTime();
            return !time.isBefore(OPEN) && time.isBefore(CLOSE);
        }

        /** The day length every percentage on the board is a share of. */
        static final BigDecimal DAY = BigDecimal.valueOf(Duration.between(OPEN, CLOSE).toMinutes())
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("the fake calendar agrees with the nine-hour working day the service divides by")
    void theFakeAgreesWithTheCalendarsDayLength() {
        // Not ceremony: every `daysPastCompletion` below is a division by this
        // figure, so a fake that counted eight-hour days would shift every
        // boundary in this file by an hour and nothing would say so.
        assertThat(WeekdayHours.DAY).isEqualByComparingTo(BigDecimal.valueOf(9));
        assertThat(WeekdayHours.between(
                Instant.parse("2026-09-16T09:00:00Z"), Instant.parse("2026-09-16T18:00:00Z")))
                .isEqualByComparingTo(BigDecimal.valueOf(9));
        // A Friday close to a Monday open is nothing at all — the weekend rule.
        assertThat(WeekdayHours.between(
                Instant.parse("2026-09-11T18:00:00Z"), Instant.parse("2026-09-14T09:00:00Z")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}

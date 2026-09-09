package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDelayedProject;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDelayedProjectsRepository.CandidateRow;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-128 · the Delayed Projects grid without a database: the ceiling
 * working-day conversion, {@code minDelayDays}, the descending sort, the
 * cursor round-trip and {@code currentStep} being null on a journey whose
 * overdue step is not the one in flight. {@code ObDelayedProjectsIT} covers
 * the SQL and pins the real Friday-due/Monday-open example CLAUDE.md names,
 * against the real {@link WorkingHoursService} — this class mocks that
 * service instead, since its package-private constructor is not reachable
 * from here, and isolates the ceiling arithmetic this class owns from the
 * calendar arithmetic {@code WorkingHoursServiceTest} already covers.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObDelayedProjectsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    private final ObDelayedProjectsRepository repository = mock(ObDelayedProjectsRepository.class);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);
    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);
    private ObDelayedProjectsService service;

    @BeforeEach
    void wireUp() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setTimezone("UTC");
        calendar.setWeeklyOff(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        calendar.setWorkDayStart(LocalTime.of(9, 0));
        calendar.setWorkDayEnd(LocalTime.of(18, 0));
        when(calendars.getCalendar()).thenReturn(calendar);

        service = new ObDelayedProjectsService(
                repository, workingHours, calendars, Clock.fixed(NOW, ZoneOffset.UTC));

        when(repository.productsBoughtByClient(anyList())).thenReturn(Map.of());
    }

    // ── the ceiling conversion, the reason this class exists ────────────────

    @Test
    @DisplayName("a naive implementation would floor or truncate; this ceils, so any elapsed time counts a whole day")
    void anyPositiveElapsedTimeIsAtLeastOneDayLate() {
        // 1 working hour elapsed of a 9-hour day: floor or int division give 0.
        when(workingHours.workingHoursBetween(any(), any())).thenReturn(new BigDecimal("1.00"));
        when(repository.candidates(any(), any(), any(), any())).thenReturn(List.of(candidate(1L)));

        List<ObDelayedProject> rows = service.list(caller("OB_MANAGER"), null, null, null, null, null).data();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).delayedByDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("exactly one working day elapsed is one day late, not two")
    void exactlyOneWorkingDayIsOneDayLate() {
        when(workingHours.workingHoursBetween(any(), any())).thenReturn(new BigDecimal("9.00"));
        when(repository.candidates(any(), any(), any(), any())).thenReturn(List.of(candidate(1L)));

        assertThat(service.list(caller("OB_MANAGER"), null, null, null, null, null).data().get(0).delayedByDays())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a fraction over one working day rounds up to two")
    void aFractionOverOneWorkingDayRoundsUpToTwo() {
        when(workingHours.workingHoursBetween(any(), any())).thenReturn(new BigDecimal("9.01"));
        when(repository.candidates(any(), any(), any(), any())).thenReturn(List.of(candidate(1L)));

        assertThat(service.list(caller("OB_MANAGER"), null, null, null, null, null).data().get(0).delayedByDays())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a row that has not yet accrued a full tick of working time is dropped, not sent as zero")
    void aRowWithNoElapsedWorkingTimeIsDropped() {
        when(workingHours.workingHoursBetween(any(), any())).thenReturn(BigDecimal.ZERO.setScale(2));
        when(repository.candidates(any(), any(), any(), any())).thenReturn(List.of(candidate(1L)));

        assertThat(service.list(caller("OB_MANAGER"), null, null, null, null, null).data()).isEmpty();
    }

    // ── minDelayDays ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("minDelayDays hides the barely-late")
    void minDelayDaysFiltersOutRowsBelowTheThreshold() {
        when(repository.candidates(any(), any(), any(), any()))
                .thenReturn(List.of(candidate(1L), candidate(2L)));
        // journeyId 1 -> 1 hour elapsed -> 1 day late; journeyId 2 -> 27 hours -> 3 days late.
        when(workingHours.workingHoursBetween(any(), any()))
                .thenReturn(new BigDecimal("1.00"), new BigDecimal("27.00"));

        List<ObDelayedProject> rows = service.list(caller("OB_MANAGER"), null, null, 2, null, null).data();

        assertThat(rows).extracting(ObDelayedProject::journeyId).containsExactly(2L);
    }

    // ── sort order ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the grid is ordered by delayedByDays descending, per the contract")
    void sortedDescendingByDelayedByDays() {
        when(repository.candidates(any(), any(), any(), any()))
                .thenReturn(List.of(candidate(1L), candidate(2L), candidate(3L)));
        when(workingHours.workingHoursBetween(any(), any()))
                .thenReturn(new BigDecimal("9.00"), new BigDecimal("27.00"), new BigDecimal("18.00"));

        List<ObDelayedProject> rows = service.list(caller("OB_MANAGER"), null, null, null, null, null).data();

        assertThat(rows).extracting(ObDelayedProject::journeyId).containsExactly(2L, 3L, 1L);
        assertThat(rows).extracting(ObDelayedProject::delayedByDays).containsExactly(3, 2, 1);
    }

    // ── currentStep ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("no in-flight step is a null currentStep, not the overdue step standing in for it")
    void currentStepIsNullWhenNothingIsInFlight() {
        CandidateRow blockedOnly = new CandidateRow(1L, 10L, "Crestwood", null,
                20L, "ERP", "ERP", NOW.minusSeconds(3600), 30L, "Ravi Shah",
                null, null, null, null, null);
        when(repository.candidates(any(), any(), any(), any())).thenReturn(List.of(blockedOnly));
        when(workingHours.workingHoursBetween(any(), any())).thenReturn(new BigDecimal("1.00"));

        ObDelayedProject row = service.list(caller("OB_MANAGER"), null, null, null, null, null).data().get(0);

        assertThat(row.currentStep()).isNull();
        assertThat(row.responsible().displayName()).isEqualTo("Ravi Shah");
    }

    @Test
    @DisplayName("an in-flight step distinct from the overdue one is still surfaced as currentStep")
    void currentStepCanDifferFromTheOverdueStep() {
        CandidateRow row = new CandidateRow(1L, 10L, "Crestwood", null,
                20L, "ERP", "ERP", NOW.minusSeconds(3600), 30L, "Ravi Shah",
                40L, 3, "Data migration", "IN_PROGRESS", 39L);
        when(repository.candidates(any(), any(), any(), any())).thenReturn(List.of(row));
        when(workingHours.workingHoursBetween(any(), any())).thenReturn(new BigDecimal("1.00"));

        ObDelayedProject dto = service.list(caller("OB_MANAGER"), null, null, null, null, null).data().get(0);

        assertThat(dto.currentStep()).isNotNull();
        assertThat(dto.currentStep().id()).isEqualTo(40L);
        assertThat(dto.currentStep().name()).isEqualTo("Data migration");
        assertThat(dto.currentStep().rag()).isNull();
    }

    // ── cursor round-trip ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a cursor resumes strictly after the row it names, in the same descending order")
    void cursorResumesAfterTheNamedRow() {
        when(repository.candidates(any(), any(), any(), any()))
                .thenReturn(List.of(candidate(1L), candidate(2L), candidate(3L)));
        when(workingHours.workingHoursBetween(any(), any()))
                .thenReturn(new BigDecimal("9.00"), new BigDecimal("27.00"), new BigDecimal("18.00"));
        // Full order is [2 (3d), 3 (2d), 1 (1d)]; resume after 3.
        Cursor cursor = new Cursor("2", 3L);

        List<ObDelayedProject> rows = service.list(
                caller("OB_MANAGER"), null, null, null, cursor.encode(), null).data();

        assertThat(rows).extracting(ObDelayedProject::journeyId).containsExactly(1L);
    }

    // ── an empty candidate set is an empty, not a broken, page ───────────────

    @Test
    void noCandidatesIsAnEmptyPage() {
        when(repository.candidates(any(), any(), any(), any())).thenReturn(List.of());

        var response = service.list(caller("OB_MANAGER"), null, null, null, null, null);

        assertThat(response.data()).isEmpty();
        assertThat(response.meta().hasMore()).isFalse();
    }

    private static CandidateRow candidate(long journeyId) {
        return new CandidateRow(journeyId, 100L + journeyId, "Client " + journeyId, null,
                20L, "ERP", "ERP", NOW.minusSeconds(3600), 30L, "Ravi Shah",
                null, null, null, null, null);
    }

    private static CallerIdentity caller(String moduleRole) {
        return new CallerIdentity(
                42, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
    }
}

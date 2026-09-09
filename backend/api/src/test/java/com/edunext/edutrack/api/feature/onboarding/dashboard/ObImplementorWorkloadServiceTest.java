package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObImplementorWorkload;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObImplementorWorkloadRepository.Row;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-128 · the workload grid without a database: {@link ObImplementorWorkloadService#performanceScore}
 * pinned to exact figures, the arithmetic contract asserted as a sum rather
 * than field by field, the bench's zero-clients row, and the scope wiring
 * into {@link ObImplementorWorkloadRepository}. {@code ObImplementorWorkloadIT}
 * covers the SQL itself, including {@code implementorPredicate} against real
 * rows.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObImplementorWorkloadServiceTest {

    private final ObImplementorWorkloadRepository repository = mock(ObImplementorWorkloadRepository.class);
    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);
    private ObImplementorWorkloadService service;

    @BeforeEach
    void wireUp() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setTimezone("UTC");
        calendar.setWeeklyOff(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        // A clean 9-hour working day, so blocked-hours penalties land on
        // whole numbers a test can assert exactly.
        calendar.setWorkDayStart(LocalTime.of(9, 0));
        calendar.setWorkDayEnd(LocalTime.of(18, 0));
        when(calendars.getCalendar()).thenReturn(calendar);

        service = new ObImplementorWorkloadService(repository, calendars);
    }

    // ── performanceScore ─────────────────────────────────────────────────────

    @Test
    @DisplayName("zero completions is null, not zero — scoring nothing is not a judgement")
    void zeroCompletionsIsNull() {
        assertThat(ObImplementorWorkloadService.performanceScore(0, 0, 0, 0, nineHours())).isNull();
    }

    @Test
    void all_on_time_with_nothing_blocked_is_a_perfect_score() {
        assertThat(ObImplementorWorkloadService.performanceScore(5, 0, 0, 0, nineHours()))
                .isEqualByComparingTo("100.0");
    }

    @Test
    void all_late_with_nothing_blocked_is_zero() {
        assertThat(ObImplementorWorkloadService.performanceScore(0, 0, 5, 0, nineHours()))
                .isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("an early completion counts for more than an on-time one")
    void aMixOfOnTimeEarlyAndLate() {
        // good = 3*1 + 2*1.2 = 5.4; quality = 5.4 / 6 * 100 = 90.0 exactly.
        assertThat(ObImplementorWorkloadService.performanceScore(3, 2, 1, 0, nineHours()))
                .isEqualByComparingTo("90.0");
    }

    @Test
    @DisplayName("blocked time subtracts, in working days through the calendar — not raw hours")
    void blockedHoursCostPointsThroughTheCalendar() {
        // 18 blocked hours / 9-hour day = 2 working days * 2 points = 4 off 100.
        assertThat(ObImplementorWorkloadService.performanceScore(5, 0, 0, 18, nineHours()))
                .isEqualByComparingTo("96.0");
    }

    @Test
    @DisplayName("the blocked-hours penalty is capped, so one long block cannot erase the score")
    void theBlockPenaltyIsCapped() {
        assertThat(ObImplementorWorkloadService.performanceScore(5, 0, 0, 900, nineHours()))
                .isEqualByComparingTo("80.0");
    }

    @Test
    @DisplayName("the score never goes negative, even with every input working against it")
    void theScoreFloorsAtZero() {
        assertThat(ObImplementorWorkloadService.performanceScore(0, 0, 10, 900, nineHours()))
                .isEqualByComparingTo("0.0");
    }

    // ── the arithmetic contract, as a sum ────────────────────────────────────

    /**
     * A-108's own words: "the six columns partition {@code clientsOpen}". This
     * asserts the sum on a row that actually travelled through
     * {@link ObImplementorWorkloadService#list}, not a hand-built DTO — the
     * version of this test that can fail for the right reason, per B-120's own
     * note on the identical contract one table over.
     */
    @Test
    @DisplayName("the six workload counters sum to clientsOpen, on a row read through the service")
    void theSixCountersSumToClientsOpen() {
        LocalDate statDate = LocalDate.of(2026, 9, 2);
        Row row = new Row(statDate, 7L, "Priya Iyer",
                /* clientsOpen */ 11, /* onTrack */ 4, /* notStarted */ 2, /* delayed */ 1,
                /* atRisk */ 1, /* blockedWaiting */ 2, /* aheadOfSchedule */ 1,
                3, 1, 0, 0, true);
        when(repository.page(any(), eq(statDate), anyBoolean(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(row));

        ObImplementorWorkload workload = service.list(caller("OB_MANAGER"), statDate, false, null, null)
                .data().get(0);

        assertThat(workload.onTrack() + workload.notStarted() + workload.delayed() + workload.atRisk()
                + workload.blockedWaiting() + workload.aheadOfSchedule())
                .isEqualTo(workload.clientsOpen());
    }

    // ── the bench ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an implementor with zero clients is a row, not an absence")
    void anImplementorWithZeroClientsIsStillARow() {
        LocalDate statDate = LocalDate.of(2026, 9, 2);
        Row bench = new Row(statDate, 9L, "Arjun Nair", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, true);
        when(repository.page(any(), eq(statDate), anyBoolean(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(bench));

        ObImplementorWorkload workload = service.list(caller("OB_MANAGER"), statDate, false, null, null)
                .data().get(0);

        assertThat(workload.clientsOpen()).isZero();
        assertThat(workload.performanceScore()).isNull();
        assertThat(workload.user().displayName()).isEqualTo("Arjun Nair");
    }

    // ── the caller's own userId reaches the repository for OB_STEP_OWNER ────

    @Test
    @DisplayName("an OB_STEP_OWNER caller's own scope reaches the repository, not merely the moduleRole")
    void theScopeCarriesTheCallersOwnUserId() {
        LocalDate statDate = LocalDate.of(2026, 9, 2);
        when(repository.page(any(), eq(statDate), anyBoolean(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());

        service.list(caller("OB_STEP_OWNER"), statDate, false, null, null);

        verify(repository).page(argThatScopeUserIdIs(42L), eq(statDate), eq(false), isNull(), isNull(), anyInt());
    }

    // ── statDate defaulting ──────────────────────────────────────────────────

    @Test
    @DisplayName("no statDate reads the most recently computed day")
    void noStatDateReadsTheLatestDay() {
        LocalDate latest = LocalDate.of(2026, 9, 1);
        when(repository.latestStatDate()).thenReturn(Optional.of(latest));
        when(repository.page(any(), eq(latest), anyBoolean(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());

        service.list(caller("OB_MANAGER"), null, false, null, null);

        verify(repository).page(any(), eq(latest), eq(false), isNull(), isNull(), anyInt());
    }

    @Test
    @DisplayName("B-120 having never run is an empty page, not an error")
    void neverComputedIsAnEmptyPage() {
        when(repository.latestStatDate()).thenReturn(Optional.empty());

        var response = service.list(caller("OB_MANAGER"), null, false, null, null);

        assertThat(response.data()).isEmpty();
        assertThat(response.meta().hasMore()).isFalse();
    }

    // ── cursor round-trip ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a cursor decodes to the name and id it was encoded from")
    void aCursorRoundTripsNameAndId() {
        LocalDate statDate = LocalDate.of(2026, 9, 2);
        Cursor cursor = new Cursor("Meera Rao", 5L);
        when(repository.page(any(), eq(statDate), anyBoolean(), eq("Meera Rao"), eq(5L), anyInt()))
                .thenReturn(List.of());

        service.list(caller("OB_MANAGER"), statDate, false, cursor.encode(), null);

        verify(repository).page(any(), eq(statDate), eq(false), eq("Meera Rao"), eq(5L), anyInt());
    }

    private static ObDashboardScope argThatScopeUserIdIs(long userId) {
        return org.mockito.ArgumentMatchers.argThat(scope -> scope != null && scope.userId() == userId);
    }

    private static BigDecimal nineHours() {
        return BigDecimal.valueOf(9);
    }

    private static CallerIdentity caller(String moduleRole) {
        return new CallerIdentity(
                42, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
    }
}

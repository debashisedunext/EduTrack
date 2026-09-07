package com.edunext.edutrack.domain.onboarding;

import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** C-114 · {@link ObJourneyStepRagService} — the C-105 wiring. */
class ObJourneyStepRagServiceTest {

    private static final Instant DUE_AT = Instant.parse("2026-09-10T12:00:00Z");

    private WorkingHoursService workingHours;
    private WorkingCalendarRepository workingCalendars;
    private ObStepClockEventRepository clockEvents;
    private ObJourneyStepRagService service;

    @BeforeEach
    void setUp() {
        workingHours = mock(WorkingHoursService.class);
        workingCalendars = mock(WorkingCalendarRepository.class);
        clockEvents = mock(ObStepClockEventRepository.class);
        service = new ObJourneyStepRagService(workingHours, workingCalendars, clockEvents);

        WorkingCalendar calendar = mock(WorkingCalendar.class);
        when(calendar.workDayLength()).thenReturn(Duration.ofHours(8));
        when(workingCalendars.getCalendar()).thenReturn(calendar);
    }

    // ── no colour, no lookups at all ─────────────────────────────────

    @Test
    void pendingStepIsNullWithoutTouchingTheCalendarOrClock() {
        ObJourneyStep step = stepWithStatus(ObJourneyStepStatus.PENDING);

        assertThat(service.ragFor(step)).isNull();
        verifyNoInteractions(workingHours, clockEvents);
    }

    @Test
    void skippedStepIsNullWithoutTouchingTheCalendarOrClock() {
        ObJourneyStep step = stepWithStatus(ObJourneyStepStatus.SKIPPED);

        assertThat(service.ragFor(step)).isNull();
        verifyNoInteractions(workingHours, clockEvents);
    }

    // ── reference instant per status ─────────────────────────────────

    @Test
    void inProgressReadsTheClockLive() {
        // 1 day (8h) budget, 6h remaining → 25% consumed, well under the
        // default 75% threshold.
        ObJourneyStep step = stepWithStatus(ObJourneyStepStatus.IN_PROGRESS);
        when(workingHours.workingHoursBetween(any(), eq(DUE_AT))).thenReturn(BigDecimal.valueOf(6));

        assertThat(service.ragFor(step)).isEqualTo(ObRag.GREEN);
        // "Live" — some Instant close to Instant.now(), not a fixed one.
        verify(workingHours).workingHoursBetween(any(Instant.class), eq(DUE_AT));
    }

    @Test
    void waitingOnClientFreezesAtTheLastPauseNotNow() {
        Instant pausedAt = Instant.parse("2026-09-08T10:00:00Z");
        ObJourneyStep step = stepWithStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        ObStepClockEvent pause = pauseEventAt(pausedAt);
        when(clockEvents.findFirstByStepIdAndEventTypeOrderByOccurredAtDescIdDesc(
                step.getId(), ObStepClockEventType.PAUSED)).thenReturn(Optional.of(pause));
        when(workingHours.workingHoursBetween(pausedAt, DUE_AT)).thenReturn(BigDecimal.valueOf(2));

        // 8h budget, 2h remaining as of the pause → 75% consumed → Amber.
        assertThat(service.ragFor(step)).isEqualTo(ObRag.AMBER);
        verify(workingHours).workingHoursBetween(pausedAt, DUE_AT);
        verify(workingHours, never()).workingHoursBetween(any(Instant.class), any(Instant.class), any(), any());
    }

    @Test
    void waitingOnClientWithNoPauseRowIsAnInvariantViolation() {
        ObJourneyStep step = stepWithStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        when(clockEvents.findFirstByStepIdAndEventTypeOrderByOccurredAtDescIdDesc(
                step.getId(), ObStepClockEventType.PAUSED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ragFor(step))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAITING_ON_CLIENT");
    }

    @Test
    void doneFreezesAtFinishedAtNotNow() {
        Instant finishedAt = Instant.parse("2026-09-09T09:00:00Z");
        ObJourneyStep step = stepWithStatus(ObJourneyStepStatus.DONE);
        step.setFinishedAt(finishedAt);
        // Finished after due_at → workingHoursBetween floors at zero (breach).
        when(workingHours.workingHoursBetween(finishedAt, DUE_AT)).thenReturn(BigDecimal.ZERO);

        assertThat(service.ragFor(step)).isEqualTo(ObRag.RED);
        verify(workingHours).workingHoursBetween(finishedAt, DUE_AT);
        verifyNoInteractions(clockEvents);
    }

    @Test
    void blockedPastThresholdIsRedThroughTheRealWiring() {
        // Same 8h-budget/threshold-75% shape as ObRagCalculatorTest's own
        // blockedPastThresholdIsRedNotAmber, driven through the real
        // percentage plumbing instead of a literal double.
        ObJourneyStep step = stepWithStatus(ObJourneyStepStatus.BLOCKED);
        when(workingHours.workingHoursBetween(any(), eq(DUE_AT))).thenReturn(BigDecimal.valueOf(1.5));

        assertThat(service.ragFor(step)).isEqualTo(ObRag.RED);
    }

    private static ObJourneyStep stepWithStatus(ObJourneyStepStatus status) {
        ObJourneyStep step = new ObJourneyStep();
        step.setId(1L);
        step.setTatDays(1);
        step.setDueAt(DUE_AT);
        step.setStatus(status);
        return step;
    }

    private static ObStepClockEvent pauseEventAt(Instant occurredAt) {
        ObStepClockEvent event = new ObStepClockEvent();
        event.setOccurredAt(occurredAt);
        return event;
    }
}

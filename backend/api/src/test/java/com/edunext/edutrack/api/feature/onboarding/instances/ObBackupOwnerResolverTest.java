package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.masters.ResourceLeave;
import com.edunext.edutrack.domain.masters.ResourceLeaveRepository;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-108 · {@code effectiveOwnerUserId} — {@code WorkingCalendarTest}'s own
 * fixture-over-mock shape, on {@code WorkingHoursServiceTest}'s precedent one
 * package over: a seeded {@link WorkingCalendar} fixture rather than a real
 * database, so this runs without Docker.
 */
class ObBackupOwnerResolverTest {

    private static final long OWNER = 10L;
    private static final long BACKUP_OWNER = 11L;
    private static final long STEP = 700L;

    private final ResourceLeaveRepository leaves = mock(ResourceLeaveRepository.class);
    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);

    private final ObBackupOwnerResolver resolver = new ObBackupOwnerResolver(leaves, calendars);

    @BeforeEach
    void setUp() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setWeeklyOff(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        calendar.setWorkDayStart(LocalTime.of(9, 30));
        calendar.setWorkDayEnd(LocalTime.of(18, 30));
        calendar.setTimezone("Asia/Kolkata");
        when(calendars.getCalendar()).thenReturn(calendar);
        when(leaves.findApprovedOverlapping(any(), any(), any())).thenReturn(List.of());
    }

    private static ObJourneyStep step(Long ownerUserId, Long backupOwnerUserId) {
        ObJourneyStep step = new ObJourneyStep();
        step.setId(STEP);
        step.setOwnerUserId(ownerUserId);
        step.setBackupOwnerUserId(backupOwnerUserId);
        return step;
    }

    @Test
    void theOwnerIsEffectiveWhenNobodyIsOnLeave() {
        assertThat(resolver.effectiveOwnerUserId(step(OWNER, BACKUP_OWNER))).isEqualTo(OWNER);
    }

    @Test
    void theBackupOwnerIsEffectiveWhenTheOwnerIsOnApprovedLeaveToday() {
        when(leaves.findApprovedOverlapping(eq(OWNER), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new ResourceLeave()));

        assertThat(resolver.effectiveOwnerUserId(step(OWNER, BACKUP_OWNER))).isEqualTo(BACKUP_OWNER);
    }

    @Test
    void theOwnerStaysEffectiveOnLeaveWithNoBackupToInheritTo() {
        when(leaves.findApprovedOverlapping(eq(OWNER), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new ResourceLeave()));

        assertThat(resolver.effectiveOwnerUserId(step(OWNER, null))).isEqualTo(OWNER);
    }

    @Test
    void theBackupOwnerIsEffectiveWhenTheStepHasNoResolvedOwnerAtAll() {
        // C-103's "unresolved" case — a role-only or nameless template step.
        assertThat(resolver.effectiveOwnerUserId(step(null, BACKUP_OWNER))).isEqualTo(BACKUP_OWNER);
    }

    @Test
    void nullWhenNeitherOwnerNorBackupIsSet() {
        assertThat(resolver.effectiveOwnerUserId(step(null, null))).isNull();
    }

    @Test
    void leaveIsCheckedForTheOwnerNotTheBackup() {
        // The backup's own leave never enters into it — only the primary owner's does.
        when(leaves.findApprovedOverlapping(eq(BACKUP_OWNER), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new ResourceLeave()));

        assertThat(resolver.effectiveOwnerUserId(step(OWNER, BACKUP_OWNER))).isEqualTo(OWNER);
    }
}
